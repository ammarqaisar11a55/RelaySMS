import request from 'supertest';
import { afterAll, describe, expect, it } from 'vitest';
import { createHarness, pairedDevices, registerDevice, signedMessage, type Harness } from './helpers.js';
import { PROOF } from './helpers.js';

const open: Harness[] = [];
async function harness(overrides: Parameters<typeof createHarness>[0] = {}) {
  const h = await createHarness(overrides);
  open.push(h);
  return h;
}
afterAll(async () => {
  for (const h of open) await h.close();
});

describe('rate limiting', () => {
  it('limits requests per client address', async () => {
    const h = await harness({ RATE_LIMIT_MAX: '5', RATE_LIMIT_WINDOW_SECONDS: '60' });
    const statuses: number[] = [];
    for (let i = 0; i < 8; i++) statuses.push((await request(h.app).get('/v1/devices/me')).status);
    expect(statuses.slice(0, 5)).toEqual([401, 401, 401, 401, 401]);
    expect(statuses.slice(5)).toEqual([429, 429, 429]);
    // Health checks are exempt so that monitoring cannot be locked out.
    await request(h.app).get('/healthz').expect(200);
  });

  it('limits device registration separately', async () => {
    const h = await harness({ RATE_LIMIT_REGISTRATION_MAX: '2' });
    await registerDevice(h.app, 'source');
    await registerDevice(h.app, 'source');
    await expect(registerDevice(h.app, 'source')).rejects.toThrow(/429/);
  });

  it('limits pairing attempts', async () => {
    const h = await harness({ RATE_LIMIT_PAIRING_MAX: '3' });
    const companion = await registerDevice(h.app, 'companion');
    for (let i = 0; i < 3; i++)
      await request(h.app).post('/v1/pairings').set(companion.auth).send().expect(201);
    const res = await request(h.app).post('/v1/pairings').set(companion.auth).send();
    expect(res.status).toBe(429);
    expect(res.body.error.code).toBe('rate_limited');
  });
});

describe('error handling and privacy', () => {
  it('returns one consistent error shape and no stack traces', async () => {
    const h = await harness();
    const missing = await request(h.app).get('/v1/nope');
    expect(missing.status).toBe(404);
    expect(missing.body).toEqual({ error: { code: 'not_found', message: 'Not found.' } });
    expect(missing.headers['x-powered-by']).toBeUndefined();
  });

  it('answers 500 without detail and keeps serving when the database fails', async () => {
    const h = await harness();
    const d = await registerDevice(h.app, 'source');
    await h.db.query('ALTER TABLE relay_links RENAME TO relay_links_broken');
    try {
      const res = await request(h.app).get('/v1/devices/me').set(d.auth);
      expect(res.status).toBe(500);
      expect(res.body).toEqual({ error: { code: 'internal_error', message: 'Something went wrong.' } });
    } finally {
      await h.db.query('ALTER TABLE relay_links_broken RENAME TO relay_links');
    }
    await request(h.app).get('/v1/devices/me').set(d.auth).expect(200);
  });

  it('never writes tokens, pairing secrets, ciphertext or signatures to the log', async () => {
    const h = await harness();
    const companion = await registerDevice(h.app, 'companion');
    const source = await registerDevice(h.app, 'source');
    const created = await request(h.app).post('/v1/pairings').set(companion.auth).send().expect(201);
    const secret = created.body.secret as string;
    await request(h.app)
      .post(`/v1/pairings/${created.body.pairingId}/redeem`)
      .set(source.auth)
      .send({ secret: 'B'.repeat(22), proof: PROOF })
      .expect(403);
    await request(h.app)
      .post(`/v1/pairings/${created.body.pairingId}/redeem`)
      .set(source.auth)
      .send({ secret, proof: PROOF })
      .expect(200);
    await request(h.app)
      .post(`/v1/pairings/${created.body.pairingId}/confirm`)
      .set(source.auth)
      .send()
      .expect(200);
    await request(h.app)
      .post(`/v1/pairings/${created.body.pairingId}/confirm`)
      .set(companion.auth)
      .send()
      .expect(200);
    const m = signedMessage(source, companion.id);
    await request(h.app).post('/v1/messages').set(source.auth).send(m).expect(201);
    await request(h.app)
      .post('/v1/messages')
      .set(source.auth)
      .send({ ...m, signature: 'bad' })
      .expect(400);
    await request(h.app).get('/v1/messages/pending').set(companion.auth).expect(200);
    await request(h.app).post('/v1/devices/me/token').set(source.auth).expect(200);

    const logs = h.logs();
    expect(logs).toContain('pairing completed');
    for (const sensitive of [
      source.token.split('.')[1]!,
      companion.token.split('.')[1]!,
      secret,
      m.ciphertext,
      m.signature,
      source.hpkePublicKey,
    ]) {
      expect(logs).not.toContain(sensitive);
    }
  });

  it('stores nothing but opaque bytes and routing metadata for a message', async () => {
    const h = await harness();
    const { source, companion } = await pairedDevices(h.app);
    await request(h.app)
      .post('/v1/messages')
      .set(source.auth)
      .send(signedMessage(source, companion.id))
      .expect(201);
    const columns = await h.db.query(
      `SELECT column_name FROM information_schema.columns WHERE table_name = 'relay_messages' AND table_schema = current_schema() ORDER BY column_name`,
    );
    expect(columns.rows.map((r) => r.column_name as string)).toEqual([
      'acked_at',
      'ciphertext',
      'created_at',
      'expires_at',
      'message_id',
      'recipient_id',
      'sender_id',
      'signature',
      'size_bytes',
    ]);
  });
});
