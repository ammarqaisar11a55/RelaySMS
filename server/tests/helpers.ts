import { generateKeyPairSync, randomBytes, randomUUID, sign, type KeyObject } from 'node:crypto';
import { Writable } from 'node:stream';
import type { Express } from 'express';
import { pino } from 'pino';
import request from 'supertest';
import { createApp } from '../src/app.js';
import { loadConfig, type Config } from '../src/config/env.js';
import type { AppContext } from '../src/context.js';
import { migrate } from '../src/database/migrate.js';
import { createPool, type Db } from '../src/database/pool.js';
import type { Metrics } from '../src/modules/health/health.routes.js';
import { Hub } from '../src/realtime/hub.js';
import { messageSigningInput } from '../src/security/signatures.js';

export const TEST_DATABASE_URL =
  process.env.TEST_DATABASE_URL ?? 'postgres://relaysms@127.0.0.1:54329/relaysms_test';

export interface Harness {
  app: Express;
  ctx: AppContext;
  db: Db;
  metrics: Metrics;
  /** Everything the server logged, as one string. */
  logs: () => string;
  /** Moves the server clock forward. */
  advance: (ms: number) => void;
  close: () => Promise<void>;
}

/** Fresh app over an empty database. Limits are generous unless overridden. */
export async function createHarness(overrides: Partial<Record<keyof Config, string>> = {}): Promise<Harness> {
  const config = loadConfig({
    NODE_ENV: 'test',
    DATABASE_URL: TEST_DATABASE_URL,
    LOG_LEVEL: 'debug',
    RATE_LIMIT_MAX: '100000',
    RATE_LIMIT_REGISTRATION_MAX: '10000',
    RATE_LIMIT_PAIRING_MAX: '10000',
    ...overrides,
  });
  const db = createPool(config.DATABASE_URL);
  await migrate(db);
  await db.query('TRUNCATE messages, links, pairing_sessions, devices CASCADE');

  const chunks: string[] = [];
  const logger = pino(
    { level: config.LOG_LEVEL, base: null },
    new Writable({
      write(chunk: Buffer, _enc, cb) {
        chunks.push(chunk.toString('utf8'));
        cb();
      },
    }),
  );
  let offset = 0;
  const hub = new Hub();
  const ctx: AppContext = { db, config, logger, hub, now: () => new Date(Date.now() + offset) };
  const metrics: Metrics = { startedAt: new Date(), expiredMessages: 0, expiredPairings: 0 };
  return {
    app: createApp(ctx, metrics),
    ctx,
    db,
    metrics,
    logs: () => chunks.join(''),
    advance: (ms) => {
      offset += ms;
    },
    close: async () => {
      hub.close();
      await db.end();
    },
  };
}

export interface TestDevice {
  id: string;
  token: string;
  role: 'source' | 'companion';
  auth: { Authorization: string };
  privateKey: KeyObject;
  hpkePublicKey: string;
  signingPublicKey: string;
}

const rawEd25519 = (key: KeyObject) => (key.export({ format: 'der', type: 'spki' }) as Buffer).subarray(-32);

export async function registerDevice(
  app: Express,
  role: 'source' | 'companion',
  extra: object = {},
): Promise<TestDevice> {
  const { publicKey, privateKey } = generateKeyPairSync('ed25519');
  const hpkePublicKey = randomBytes(32).toString('base64url');
  const signingPublicKey = rawEd25519(publicKey).toString('base64url');
  const res = await request(app)
    .post('/v1/devices')
    .send({ role, hpkePublicKey, signingPublicKey, ...extra });
  if (res.status !== 201) throw new Error(`register failed: ${res.status} ${JSON.stringify(res.body)}`);
  return {
    id: res.body.deviceId as string,
    token: res.body.token as string,
    role,
    auth: { Authorization: `Bearer ${res.body.token as string}` },
    privateKey,
    hpkePublicKey,
    signingPublicKey,
  };
}

/** Runs the full three-step pairing between a new source and a new companion. */
export async function pairedDevices(
  app: Express,
): Promise<{ source: TestDevice; companion: TestDevice; pairingId: string }> {
  const companion = await registerDevice(app, 'companion');
  const source = await registerDevice(app, 'source');
  const pairingId = await pair(app, source, companion);
  return { source, companion, pairingId };
}

export async function pair(app: Express, source: TestDevice, companion: TestDevice): Promise<string> {
  const created = await request(app).post('/v1/pairings').set(companion.auth).send();
  const { pairingId, secret } = created.body as { pairingId: string; secret: string };
  await request(app).post(`/v1/pairings/${pairingId}/redeem`).set(source.auth).send({ secret }).expect(200);
  await request(app).post(`/v1/pairings/${pairingId}/confirm`).set(source.auth).send().expect(200);
  await request(app).post(`/v1/pairings/${pairingId}/confirm`).set(companion.auth).send().expect(200);
  return pairingId;
}

/** A correctly signed upload body. The "ciphertext" is opaque random bytes: the relay never decrypts. */
export function signedMessage(
  sender: TestDevice,
  recipientId: string,
  opts: { messageId?: string; ciphertext?: Buffer } = {},
) {
  const messageId = opts.messageId ?? randomUUID();
  const ciphertext = opts.ciphertext ?? randomBytes(200);
  const signature = sign(
    null,
    messageSigningInput(sender.id, recipientId, messageId, ciphertext),
    sender.privateKey,
  );
  return {
    messageId,
    ciphertext: ciphertext.toString('base64url'),
    signature: signature.toString('base64url'),
  };
}
