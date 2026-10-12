import { randomBytes } from 'node:crypto';
import { createServer, type IncomingMessage, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import request from 'supertest';
import { afterAll, describe, expect, it } from 'vitest';
import WebSocket from 'ws';
import { WS_PATH } from '../src/app.js';
import { runExpiry } from '../src/jobs/expiry.js';
import { authenticateUpgrade } from '../src/middleware/auth.js';
import { clientAddress, createAttemptLimiter } from '../src/middleware/upgradeLimit.js';
import { createHarness, pairedDevices, registerDevice, signedMessage, type Harness } from './helpers.js';

const open: Harness[] = [];
const servers: Server[] = [];
async function harness(overrides: Parameters<typeof createHarness>[0] = {}) {
  const h = await createHarness(overrides);
  open.push(h);
  return h;
}
afterAll(async () => {
  for (const s of servers) await new Promise((resolve) => s.close(resolve));
  for (const h of open) await h.close();
});

/** Serves [h] on a real socket with the WebSocket hub attached, like the production entry point. */
async function listen(h: Harness, admit?: (req: IncomingMessage) => boolean): Promise<string> {
  const server = createServer(h.app);
  servers.push(server);
  h.ctx.hub.attach(
    server,
    WS_PATH,
    (req) => authenticateUpgrade(h.ctx, req),
    () => undefined,
    h.ctx.logger,
    admit,
  );
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  return `ws://127.0.0.1:${(server.address() as AddressInfo).port}${WS_PATH}`;
}

/** Resolves with the HTTP status of a refused upgrade, or 101 when the socket opened. */
function upgradeStatus(url: string, headers: Record<string, string> = {}): Promise<number> {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url, { headers });
    ws.on('open', () => {
      ws.close();
      resolve(101);
    });
    ws.on('unexpected-response', (_req, res) => resolve(res.statusCode ?? 0));
    ws.on('error', (err) => {
      if (!/Unexpected server response/.test(err.message)) reject(err);
    });
  });
}

function openSocket(url: string, headers: Record<string, string>): Promise<WebSocket> {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url, { headers });
    ws.on('open', () => resolve(ws));
    ws.on('error', reject);
  });
}

const closeCode = (ws: WebSocket) =>
  new Promise<number>((resolve) => ws.on('close', (code) => resolve(code)));

describe('response headers', () => {
  it('forbids caching of every response and sends the standard hardening headers', async () => {
    const h = await harness();
    const registration = await request(h.app)
      .post('/v1/devices')
      .send({
        role: 'companion',
        hpkePublicKey: randomBytes(32).toString('base64url'),
        signingPublicKey: randomBytes(32).toString('base64url'),
      })
      .expect(201);
    const refused = await request(h.app).get('/v1/devices/me').expect(401);
    const health = await request(h.app).get('/healthz').expect(200);
    for (const res of [registration, refused, health]) {
      expect(res.headers['cache-control']).toBe('no-store');
      expect(res.headers['x-content-type-options']).toBe('nosniff');
      expect(res.headers['strict-transport-security']).toMatch(/max-age=\d+/);
      expect(res.headers['x-powered-by']).toBeUndefined();
    }
  });
});

describe('client address and rate limits', () => {
  it('ignores X-Forwarded-For when no proxy is trusted, so the limit cannot be dodged', async () => {
    const h = await harness({ RATE_LIMIT_MAX: '3', TRUST_PROXY: '0' });
    const statuses: number[] = [];
    for (let i = 0; i < 5; i++) {
      const res = await request(h.app).get('/v1/devices/me').set('X-Forwarded-For', `203.0.113.${i}`);
      statuses.push(res.status);
    }
    expect(statuses).toEqual([401, 401, 401, 429, 429]);
  });

  it('behind one proxy, limits each real client and ignores addresses the client invented', async () => {
    const h = await harness({ RATE_LIMIT_MAX: '3', TRUST_PROXY: '1' });
    const from = (forwardedFor: string) =>
      request(h.app)
        .get('/v1/devices/me')
        .set('X-Forwarded-For', forwardedFor)
        .then((r) => r.status);
    expect([await from('198.51.100.1'), await from('198.51.100.1'), await from('198.51.100.1')]).toEqual([
      401, 401, 401,
    ]);
    expect(await from('198.51.100.1')).toBe(429);
    // The proxy appends the address it saw; whatever the client put in front does not count.
    expect(await from('203.0.113.77, 198.51.100.1')).toBe(429);
    expect(await from('198.51.100.2')).toBe(401);
  });

  it('rate limits /metrics but never the liveness and readiness probes', async () => {
    const h = await harness({ RATE_LIMIT_MAX: '2' });
    const statuses: number[] = [];
    for (let i = 0; i < 4; i++) statuses.push((await request(h.app).get('/metrics')).status);
    expect(statuses).toEqual([200, 200, 429, 429]);
    for (let i = 0; i < 6; i++) {
      await request(h.app).get('/healthz').expect(200);
      await request(h.app).get('/readyz').expect(200);
    }
  });

  it('derives the client address of a raw request like Express does', () => {
    const req = (remoteAddress: string, forwardedFor?: string | string[]) =>
      ({
        socket: { remoteAddress },
        headers: forwardedFor === undefined ? {} : { 'x-forwarded-for': forwardedFor },
      }) as unknown as IncomingMessage;
    expect(clientAddress(req('10.0.0.5', '1.2.3.4'), 0)).toBe('10.0.0.5');
    expect(clientAddress(req('10.0.0.5'), 1)).toBe('10.0.0.5');
    expect(clientAddress(req('10.0.0.5', '1.2.3.4'), 1)).toBe('1.2.3.4');
    expect(clientAddress(req('10.0.0.5', '9.9.9.9, 1.2.3.4'), 1)).toBe('1.2.3.4');
    expect(clientAddress(req('10.0.0.5', '9.9.9.9, 1.2.3.4'), 2)).toBe('9.9.9.9');
    expect(clientAddress(req('10.0.0.5', ['9.9.9.9', '1.2.3.4']), 1)).toBe('1.2.3.4');
    // More proxies configured than present: fall back to the farthest known address.
    expect(clientAddress(req('10.0.0.5', '1.2.3.4'), 5)).toBe('1.2.3.4');
  });

  it('counts attempts per key in fixed windows', () => {
    let t = 1_000_000;
    const allowed = createAttemptLimiter(60, 2, () => t);
    expect([allowed('a'), allowed('a'), allowed('a')]).toEqual([true, true, false]);
    expect(allowed('b')).toBe(true);
    t += 59_000;
    expect(allowed('a')).toBe(false);
    t += 1_000;
    expect([allowed('a'), allowed('a'), allowed('a')]).toEqual([true, true, false]);
  });
});

describe('hostile input', () => {
  it('rejects prototype-polluting keys, wrong body types and identifiers that are not UUIDs', async () => {
    const h = await harness();
    const { source, companion } = await pairedDevices(h.app);
    const keys = {
      role: 'source',
      hpkePublicKey: randomBytes(32).toString('base64url'),
      signingPublicKey: randomBytes(32).toString('base64url'),
    };
    for (const body of [
      `{"__proto__":{"role":"companion"},${JSON.stringify(keys).slice(1)}`,
      `{"constructor":{"prototype":{"x":1}},${JSON.stringify(keys).slice(1)}`,
      JSON.stringify([keys]),
      JSON.stringify('source'),
      'null',
    ]) {
      const res = await request(h.app).post('/v1/devices').set('Content-Type', 'application/json').send(body);
      expect(res.status).toBe(400);
    }
    expect(({} as { role?: string }).role).toBeUndefined();

    // A body that is not JSON is not parsed at all.
    const form = await request(h.app).post('/v1/devices').type('form').send(keys);
    expect(form.status).toBe(400);

    for (const id of [
      "' OR '1'='1",
      '00000000-0000-0000-0000-00000000000g',
      '../devices/me',
      'a'.repeat(500),
    ]) {
      const res = await request(h.app)
        .post(`/v1/pairings/${encodeURIComponent(id)}/confirm`)
        .set(source.auth)
        .send();
      expect(res.status).toBe(400);
      const ack = await request(h.app)
        .post('/v1/messages/ack')
        .set(companion.auth)
        .send({ messageIds: [id] });
      expect(ack.status).toBe(400);
    }
    const upload = { ...signedMessage(source, companion.id), recipientId: companion.id };
    const extraField = await request(h.app).post('/v1/messages').set(source.auth).send(upload);
    expect(extraField.status).toBe(400);

    // Nothing above reached the tables.
    const counts = await h.db.query(
      'SELECT (SELECT COUNT(*) FROM messages) AS m, (SELECT COUNT(*) FROM devices) AS d',
    );
    expect(counts.rows[0]).toEqual({ m: '0', d: '2' });
  });
});

describe('revoked devices', () => {
  it('removes the record of a revoked device a day later and leaves other devices alone', async () => {
    const h = await harness();
    const { source, companion } = await pairedDevices(h.app);
    await request(h.app).delete('/v1/devices/me').set(companion.auth).expect(204);

    const ids = async () =>
      (await h.db.query('SELECT id FROM devices ORDER BY id')).rows.map((r) => r.id as string);
    await runExpiry(h.ctx, h.metrics);
    expect(await ids()).toEqual([source.id, companion.id].sort());
    await request(h.app).get('/v1/devices/me').set(companion.auth).expect(401);

    h.advance(25 * 3_600_000);
    await runExpiry(h.ctx, h.metrics);
    expect(await ids()).toEqual([source.id]);
    await request(h.app).get('/v1/devices/me').set(companion.auth).expect(401);
    const me = await request(h.app).get('/v1/devices/me').set(source.auth).expect(200);
    expect(me.body.peer).toBeNull();
  });
});

describe('realtime channel hardening', () => {
  it('limits connection attempts per client address before looking at credentials', async () => {
    const h = await harness();
    const allowed = createAttemptLimiter(60, 3);
    const url = await listen(h, (req) => allowed(clientAddress(req, 0)));
    const d = await registerDevice(h.app, 'companion');
    const statuses: number[] = [];
    for (let i = 0; i < 5; i++) statuses.push(await upgradeStatus(url, i === 2 ? d.auth : {}));
    expect(statuses).toEqual([401, 401, 101, 429, 429]);
  });

  it('does not accept a credential in the URL', async () => {
    const h = await harness();
    const url = await listen(h);
    const d = await registerDevice(h.app, 'companion');
    for (const query of [
      `?token=${d.token}`,
      `?authorization=Bearer%20${d.token}`,
      `?access_token=${d.token}`,
    ]) {
      expect(await upgradeStatus(url + query)).toBe(401);
    }
    expect(await upgradeStatus(url, d.auth)).toBe(101);
  });

  it('closes a connection that sends an oversized frame and one whose token was rotated', async () => {
    const h = await harness();
    const url = await listen(h);
    const d = await registerDevice(h.app, 'companion');

    const noisy = await openSocket(url, d.auth);
    const noisyClosed = closeCode(noisy);
    noisy.send(Buffer.alloc(4096, 1));
    expect(await noisyClosed).toBe(1009);

    const quiet = await openSocket(url, d.auth);
    const quietClosed = closeCode(quiet);
    await request(h.app).post('/v1/devices/me/token').set(d.auth).expect(200);
    expect(await quietClosed).toBe(4401);
    expect(await upgradeStatus(url, d.auth)).toBe(401);
  });
});
