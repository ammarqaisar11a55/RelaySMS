import { randomBytes } from 'node:crypto';
import request from 'supertest';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { createHarness, pairedDevices, registerDevice, type Harness } from './helpers.js';

let h: Harness;
beforeAll(async () => {
  h = await createHarness();
});
afterAll(() => h.close());

describe('health', () => {
  it('reports liveness, readiness and content-free metrics without authentication', async () => {
    await request(h.app).get('/healthz').expect(200, { status: 'ok' });
    await request(h.app).get('/readyz').expect(200, { status: 'ready' });
    const m = await request(h.app).get('/metrics').expect(200);
    expect(Object.keys(m.body).sort()).toEqual(
      [
        'activeDevices',
        'expiredMessages',
        'expiredPairings',
        'links',
        'openConnections',
        'pendingMessages',
        'uptimeSeconds',
      ].sort(),
    );
  });
});

describe('device registration and authentication', () => {
  it('registers a device and stores only a hash of its token', async () => {
    const d = await registerDevice(h.app, 'companion');
    expect(d.token.startsWith(`${d.id}.`)).toBe(true);
    const row = await h.db.query('SELECT token_hash, role FROM devices WHERE id = $1', [d.id]);
    const stored = row.rows[0].token_hash as Buffer;
    expect(stored.length).toBe(32);
    expect(d.token).not.toContain(stored.toString('base64url'));
    expect(stored.toString('utf8')).not.toContain(d.token.split('.')[1]);

    const me = await request(h.app).get('/v1/devices/me').set(d.auth).expect(200);
    expect(me.body).toMatchObject({ deviceId: d.id, role: 'companion', peer: null });
  });

  it('rejects malformed registrations', async () => {
    const ok = {
      role: 'source',
      hpkePublicKey: randomBytes(32).toString('base64url'),
      signingPublicKey: randomBytes(32).toString('base64url'),
    };
    for (const bad of [
      {},
      { ...ok, role: 'admin' },
      { ...ok, hpkePublicKey: randomBytes(31).toString('base64url') },
      { ...ok, signingPublicKey: 'not base64!' },
      { ...ok, unexpected: true },
    ]) {
      const res = await request(h.app).post('/v1/devices').send(bad);
      expect(res.status).toBe(400);
      expect(res.body.error.code).toBe('invalid_request');
    }
    await request(h.app)
      .post('/v1/devices')
      .set('Content-Type', 'application/json')
      .send('{not json')
      .expect(400);
  });

  it('rejects missing, malformed, wrong and cross-device credentials', async () => {
    const a = await registerDevice(h.app, 'source');
    const b = await registerDevice(h.app, 'source');
    const secretOfA = a.token.split('.')[1];
    for (const header of [
      undefined,
      'Bearer',
      'Bearer nonsense',
      `Basic ${a.token}`,
      `Bearer ${a.id}.${'x'.repeat(43)}`,
      `Bearer ${b.id}.${secretOfA}`,
      `Bearer 00000000-0000-4000-8000-000000000000.${secretOfA}`,
    ]) {
      const req = request(h.app).get('/v1/devices/me');
      if (header) req.set('Authorization', header);
      const res = await req;
      expect(res.status).toBe(401);
      expect(res.body.error.code).toBe('unauthorized');
    }
  });

  it('rotates a token and invalidates the old one immediately', async () => {
    const d = await registerDevice(h.app, 'source');
    const rotated = await request(h.app).post('/v1/devices/me/token').set(d.auth).expect(200);
    await request(h.app).get('/v1/devices/me').set(d.auth).expect(401);
    await request(h.app)
      .get('/v1/devices/me')
      .set('Authorization', `Bearer ${rotated.body.token}`)
      .expect(200);
  });

  it('requires the registration code when the relay is configured with one', async () => {
    const locked = await createHarness({ REGISTRATION_CODE: 'correct-horse-battery-staple' });
    try {
      await expect(registerDevice(locked.app, 'source')).rejects.toThrow(/403/);
      await expect(
        registerDevice(locked.app, 'source', { registrationCode: 'wrong-code-wrong-code' }),
      ).rejects.toThrow(/403/);
      const d = await registerDevice(locked.app, 'source', {
        registrationCode: 'correct-horse-battery-staple',
      });
      expect(d.id).toBeTruthy();
      expect(locked.logs()).not.toContain('correct-horse-battery-staple');
    } finally {
      await locked.close();
    }
  });
});

describe('revocation', () => {
  it('link revocation by either side removes the link and queued ciphertext', async () => {
    const { source, companion } = await pairedDevices(h.app);
    const { signedMessage } = await import('./helpers.js');
    await request(h.app)
      .post('/v1/messages')
      .set(source.auth)
      .send(signedMessage(source, companion.id))
      .expect(201);

    await request(h.app).delete('/v1/link').set(companion.auth).expect(200, { revoked: true });
    await request(h.app).delete('/v1/link').set(companion.auth).expect(200, { revoked: false });

    const upload = await request(h.app)
      .post('/v1/messages')
      .set(source.auth)
      .send(signedMessage(source, companion.id));
    expect(upload.status).toBe(409);
    expect(upload.body.error.code).toBe('not_linked');
    const pending = await request(h.app).get('/v1/messages/pending').set(companion.auth).expect(200);
    expect(pending.body.messages).toEqual([]);
    const left = await h.db.query('SELECT COUNT(*) AS n FROM messages WHERE recipient_id = $1', [
      companion.id,
    ]);
    expect(Number(left.rows[0].n)).toBe(0);
    const me = await request(h.app).get('/v1/devices/me').set(source.auth).expect(200);
    expect(me.body.peer).toBeNull();
  });

  it('deleting a device disables its credential and unlinks its peer', async () => {
    const { source, companion } = await pairedDevices(h.app);
    await request(h.app).delete('/v1/devices/me').set(companion.auth).expect(204);
    await request(h.app).get('/v1/devices/me').set(companion.auth).expect(401);
    await request(h.app).get('/v1/messages/pending').set(companion.auth).expect(401);
    const me = await request(h.app).get('/v1/devices/me').set(source.auth).expect(200);
    expect(me.body.peer).toBeNull();
  });
});
