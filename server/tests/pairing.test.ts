import { randomUUID } from 'node:crypto';
import request from 'supertest';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { createHarness, pair, registerDevice, signedMessage, type Harness } from './helpers.js';
import { PROOF } from './helpers.js';

let h: Harness;
beforeAll(async () => {
  h = await createHarness({ PAIRING_TTL_SECONDS: '120', PAIRING_MAX_FAILED_ATTEMPTS: '3' });
});
afterAll(() => h.close());

async function open() {
  const companion = await registerDevice(h.app, 'companion');
  const source = await registerDevice(h.app, 'source');
  const created = await request(h.app).post('/v1/pairings').set(companion.auth).send().expect(201);
  return {
    companion,
    source,
    pairingId: created.body.pairingId as string,
    secret: created.body.secret as string,
  };
}

describe('pairing', () => {
  it('links two devices only after redeem and both confirmations', async () => {
    const { companion, source, pairingId, secret } = await open();
    const stored = await h.db.query('SELECT secret_hash FROM pairing_sessions WHERE id = $1', [pairingId]);
    expect((stored.rows[0].secret_hash as Buffer).toString('utf8')).not.toContain(secret);

    // Confirming before the source has scanned is refused.
    const early = await request(h.app).post(`/v1/pairings/${pairingId}/confirm`).set(companion.auth).send();
    expect(early.body.error.code).toBe('pairing_not_redeemed');

    const redeemed = await request(h.app)
      .post(`/v1/pairings/${pairingId}/redeem`)
      .set(source.auth)
      .send({ secret, proof: PROOF })
      .expect(200);
    expect(redeemed.body.companion).toEqual({
      deviceId: companion.id,
      role: 'companion',
      hpkePublicKey: companion.hpkePublicKey,
      signingPublicKey: companion.signingPublicKey,
    });

    const view = await request(h.app).get(`/v1/pairings/${pairingId}`).set(companion.auth).expect(200);
    expect(view.body.status).toBe('redeemed');
    expect(view.body.source.signingPublicKey).toBe(source.signingPublicKey);
    expect(view.body.sourceProof).toBe(PROOF);
    await request(h.app)
      .post(`/v1/pairings/${pairingId}/redeem`)
      .set(source.auth)
      .send({ secret })
      .expect(400);

    // One confirmation is not enough: no link, no message delivery.
    await request(h.app)
      .post(`/v1/pairings/${pairingId}/confirm`)
      .set(source.auth)
      .send()
      .expect(200, { status: 'redeemed' });
    const blocked = await request(h.app)
      .post('/v1/messages')
      .set(source.auth)
      .send(signedMessage(source, companion.id));
    expect(blocked.body.error.code).toBe('not_linked');

    await request(h.app)
      .post(`/v1/pairings/${pairingId}/confirm`)
      .set(companion.auth)
      .send()
      .expect(200, { status: 'completed' });
    // Confirming again is harmless.
    await request(h.app)
      .post(`/v1/pairings/${pairingId}/confirm`)
      .set(source.auth)
      .send()
      .expect(200, { status: 'completed' });

    const me = await request(h.app).get('/v1/devices/me').set(source.auth).expect(200);
    expect(me.body.peer).toMatchObject({
      deviceId: companion.id,
      role: 'companion',
      online: false,
      awaitingPeer: 0,
    });
    await request(h.app)
      .post('/v1/messages')
      .set(source.auth)
      .send(signedMessage(source, companion.id))
      .expect(201);
  });

  it('rejects a replayed redemption', async () => {
    const { source, pairingId, secret } = await open();
    await request(h.app)
      .post(`/v1/pairings/${pairingId}/redeem`)
      .set(source.auth)
      .send({ secret, proof: PROOF })
      .expect(200);
    const again = await request(h.app)
      .post(`/v1/pairings/${pairingId}/redeem`)
      .set(source.auth)
      .send({ secret, proof: PROOF });
    expect(again.status).toBe(409);
    expect(again.body.error.code).toBe('pairing_already_redeemed');
    const intruder = await registerDevice(h.app, 'source');
    const stolen = await request(h.app)
      .post(`/v1/pairings/${pairingId}/redeem`)
      .set(intruder.auth)
      .send({ secret, proof: PROOF });
    expect(stolen.status).toBe(409);
  });

  it('rejects an expired code and an expired confirmation', async () => {
    const a = await open();
    h.advance(121_000);
    const late = await request(h.app)
      .post(`/v1/pairings/${a.pairingId}/redeem`)
      .set(a.source.auth)
      .send({ secret: a.secret, proof: PROOF });
    expect(late.status).toBe(410);
    expect(late.body.error.code).toBe('pairing_expired');

    const b = await open();
    await request(h.app)
      .post(`/v1/pairings/${b.pairingId}/redeem`)
      .set(b.source.auth)
      .send({ secret: b.secret, proof: PROOF })
      .expect(200);
    h.advance(121_000);
    const lateConfirm = await request(h.app)
      .post(`/v1/pairings/${b.pairingId}/confirm`)
      .set(b.source.auth)
      .send();
    expect(lateConfirm.status).toBe(410);
    const view = await request(h.app).get(`/v1/pairings/${b.pairingId}`).set(b.companion.auth).expect(200);
    expect(view.body.status).toBe('expired');
  });

  it('cancels the session after repeated wrong secrets so the code cannot be guessed', async () => {
    const { source, pairingId, secret } = await open();
    for (let i = 0; i < 3; i++) {
      const res = await request(h.app)
        .post(`/v1/pairings/${pairingId}/redeem`)
        .set(source.auth)
        .send({ secret: 'A'.repeat(22), proof: PROOF });
      expect(res.status).toBe(403);
      expect(res.body.error.code).toBe('invalid_pairing_secret');
    }
    const locked = await request(h.app)
      .post(`/v1/pairings/${pairingId}/redeem`)
      .set(source.auth)
      .send({ secret, proof: PROOF });
    expect(locked.status).toBe(410);
    expect(locked.body.error.code).toBe('pairing_cancelled');
  });

  it('enforces roles and hides sessions from non-participants', async () => {
    const { companion, source, pairingId, secret } = await open();
    const wrongStart = await request(h.app).post('/v1/pairings').set(source.auth).send();
    expect(wrongStart.body.error.code).toBe('wrong_role');
    const wrongRedeem = await request(h.app)
      .post(`/v1/pairings/${pairingId}/redeem`)
      .set(companion.auth)
      .send({ secret, proof: PROOF });
    expect(wrongRedeem.body.error.code).toBe('wrong_role');

    const outsider = await registerDevice(h.app, 'source');
    await request(h.app).get(`/v1/pairings/${pairingId}`).set(outsider.auth).expect(404);
    await request(h.app).post(`/v1/pairings/${pairingId}/confirm`).set(outsider.auth).send().expect(404);
    await request(h.app).post(`/v1/pairings/${pairingId}/cancel`).set(outsider.auth).send().expect(404);
    await request(h.app).get(`/v1/pairings/${randomUUID()}`).set(source.auth).expect(404);
    await request(h.app).post('/v1/pairings').send().expect(401);
  });

  it('a new code invalidates the previous one, and a cancelled code cannot be redeemed', async () => {
    const { companion, source, pairingId, secret } = await open();
    const second = await request(h.app).post('/v1/pairings').set(companion.auth).send().expect(201);
    await request(h.app)
      .post(`/v1/pairings/${pairingId}/redeem`)
      .set(source.auth)
      .send({ secret, proof: PROOF })
      .expect(410);
    await request(h.app)
      .post(`/v1/pairings/${second.body.pairingId}/cancel`)
      .set(companion.auth)
      .send()
      .expect(200);
    await request(h.app)
      .post(`/v1/pairings/${second.body.pairingId}/redeem`)
      .set(source.auth)
      .send({ secret: second.body.secret, proof: PROOF })
      .expect(410);
  });

  it('re-pairing replaces the old link and drops its undelivered messages', async () => {
    const companion = await registerDevice(h.app, 'companion');
    const oldSource = await registerDevice(h.app, 'source');
    await pair(h.app, oldSource, companion);
    await request(h.app)
      .post('/v1/messages')
      .set(oldSource.auth)
      .send(signedMessage(oldSource, companion.id))
      .expect(201);

    const newSource = await registerDevice(h.app, 'source');
    await pair(h.app, newSource, companion);

    const blocked = await request(h.app)
      .post('/v1/messages')
      .set(oldSource.auth)
      .send(signedMessage(oldSource, companion.id));
    expect(blocked.body.error.code).toBe('not_linked');
    const pending = await request(h.app).get('/v1/messages/pending').set(companion.auth).expect(200);
    expect(pending.body.messages).toEqual([]);
    const me = await request(h.app).get('/v1/devices/me').set(companion.auth).expect(200);
    expect(me.body.peer.deviceId).toBe(newSource.id);
  });
});
