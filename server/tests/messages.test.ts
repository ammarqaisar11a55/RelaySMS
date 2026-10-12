import { randomBytes, randomUUID } from 'node:crypto';
import request from 'supertest';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { runExpiry } from '../src/jobs/expiry.js';
import { createHarness, pairedDevices, registerDevice, signedMessage, type Harness } from './helpers.js';

let h: Harness;
beforeAll(async () => {
  h = await createHarness({
    MAX_CIPHERTEXT_BYTES: '4096',
    MAX_PENDING_PER_DEVICE: '5',
    MESSAGE_TTL_HOURS: '1',
  });
});
afterAll(() => h.close());

describe('message relay', () => {
  it('delivers ciphertext unchanged to the linked peer and erases it on acknowledgement', async () => {
    const { source, companion } = await pairedDevices(h.app);
    const first = signedMessage(source, companion.id);
    const second = signedMessage(source, companion.id);
    await request(h.app)
      .post('/v1/messages')
      .set(source.auth)
      .send(first)
      .expect(201, { status: 'accepted' });
    await request(h.app).post('/v1/messages').set(source.auth).send(second).expect(201);

    const status = await request(h.app).get('/v1/devices/me').set(source.auth).expect(200);
    expect(status.body.peer.awaitingPeer).toBe(2);

    const pending = await request(h.app).get('/v1/messages/pending').set(companion.auth).expect(200);
    expect(pending.body.more).toBe(false);
    expect(pending.body.messages.map((m: { messageId: string }) => m.messageId)).toEqual([
      first.messageId,
      second.messageId,
    ]);
    expect(pending.body.messages[0]).toMatchObject({
      senderId: source.id,
      ciphertext: first.ciphertext,
      signature: first.signature,
    });

    // The sender cannot read back what it sent.
    const own = await request(h.app).get('/v1/messages/pending').set(source.auth).expect(200);
    expect(own.body.messages).toEqual([]);

    await request(h.app)
      .post('/v1/messages/ack')
      .set(companion.auth)
      .send({ messageIds: [first.messageId] })
      .expect(200, { acknowledged: 1 });
    // Acknowledging again, or acknowledging unknown ids, is safe.
    await request(h.app)
      .post('/v1/messages/ack')
      .set(companion.auth)
      .send({ messageIds: [first.messageId, randomUUID()] })
      .expect(200, { acknowledged: 0 });

    const row = await h.db.query('SELECT ciphertext, acked_at FROM relay_messages WHERE message_id = $1', [
      first.messageId,
    ]);
    expect(row.rows[0].ciphertext).toBeNull();
    expect(row.rows[0].acked_at).not.toBeNull();
    const after = await request(h.app).get('/v1/messages/pending').set(companion.auth).expect(200);
    expect(after.body.messages.map((m: { messageId: string }) => m.messageId)).toEqual([second.messageId]);
  });

  it('treats a repeated submission as a duplicate, before and after acknowledgement', async () => {
    const { source, companion } = await pairedDevices(h.app);
    const m = signedMessage(source, companion.id);
    await request(h.app).post('/v1/messages').set(source.auth).send(m).expect(201);
    await request(h.app).post('/v1/messages').set(source.auth).send(m).expect(200, { status: 'duplicate' });
    await request(h.app)
      .post('/v1/messages/ack')
      .set(companion.auth)
      .send({ messageIds: [m.messageId] })
      .expect(200);
    await request(h.app).post('/v1/messages').set(source.auth).send(m).expect(200, { status: 'duplicate' });
    const pending = await request(h.app).get('/v1/messages/pending').set(companion.auth).expect(200);
    expect(pending.body.messages).toEqual([]);
  });

  it('a device cannot acknowledge or fetch messages addressed to another device', async () => {
    const a = await pairedDevices(h.app);
    const b = await pairedDevices(h.app);
    const m = signedMessage(a.source, a.companion.id);
    await request(h.app).post('/v1/messages').set(a.source.auth).send(m).expect(201);

    for (const other of [b.companion, b.source, a.source]) {
      const pending = await request(h.app).get('/v1/messages/pending').set(other.auth).expect(200);
      expect(pending.body.messages).toEqual([]);
      await request(h.app)
        .post('/v1/messages/ack')
        .set(other.auth)
        .send({ messageIds: [m.messageId] })
        .expect(200, { acknowledged: 0 });
    }
    const still = await request(h.app).get('/v1/messages/pending').set(a.companion.auth).expect(200);
    expect(still.body.messages).toHaveLength(1);
  });

  it('rejects forged, redirected and re-labelled messages', async () => {
    const a = await pairedDevices(h.app);
    const b = await pairedDevices(h.app);
    // Signed by another device's key.
    const forged = signedMessage(b.source, a.companion.id);
    const r1 = await request(h.app).post('/v1/messages').set(a.source.auth).send(forged);
    expect(r1.body.error.code).toBe('invalid_signature');
    // Signed for a different recipient than the server-side link.
    const redirected = signedMessage(a.source, b.companion.id);
    const r2 = await request(h.app).post('/v1/messages').set(a.source.auth).send(redirected);
    expect(r2.body.error.code).toBe('invalid_signature');
    // Valid signature replayed under a new message id.
    const original = signedMessage(a.source, a.companion.id);
    const r3 = await request(h.app)
      .post('/v1/messages')
      .set(a.source.auth)
      .send({ ...original, messageId: randomUUID() });
    expect(r3.body.error.code).toBe('invalid_signature');
    // A client-supplied recipient field is not part of the API.
    const r4 = await request(h.app)
      .post('/v1/messages')
      .set(a.source.auth)
      .send({ ...original, recipientId: b.companion.id });
    expect(r4.status).toBe(400);
    const none = await request(h.app).get('/v1/messages/pending').set(b.companion.auth).expect(200);
    expect(none.body.messages).toEqual([]);
  });

  it('requires authentication and a link', async () => {
    const lone = await registerDevice(h.app, 'source');
    await request(h.app).post('/v1/messages').send({}).expect(401);
    await request(h.app).get('/v1/messages/pending').expect(401);
    await request(h.app)
      .post('/v1/messages/ack')
      .send({ messageIds: [randomUUID()] })
      .expect(401);
    const res = await request(h.app)
      .post('/v1/messages')
      .set(lone.auth)
      .send(signedMessage(lone, randomUUID()));
    expect(res.status).toBe(409);
  });

  it('rejects invalid and oversized requests', async () => {
    const { source, companion } = await pairedDevices(h.app);
    const ok = signedMessage(source, companion.id);
    for (const bad of [
      {},
      { ...ok, messageId: 'not-a-uuid' },
      { ...ok, ciphertext: '' },
      { ...ok, ciphertext: 'has spaces and +/=' },
      { ...ok, signature: randomBytes(63).toString('base64url') },
      { ...ok, extra: 1 },
    ]) {
      await request(h.app).post('/v1/messages').set(source.auth).send(bad).expect(400);
    }
    const tooBig = signedMessage(source, companion.id, { ciphertext: randomBytes(4097) });
    await request(h.app).post('/v1/messages').set(source.auth).send(tooBig).expect(400);
    const atLimit = signedMessage(source, companion.id, { ciphertext: randomBytes(4096) });
    await request(h.app).post('/v1/messages').set(source.auth).send(atLimit).expect(201);
    const huge = await request(h.app)
      .post('/v1/messages')
      .set(source.auth)
      .send({ ...ok, ciphertext: 'A'.repeat(50_000) });
    expect(huge.status).toBe(413);
    expect(huge.body.error.code).toBe('payload_too_large');
    await request(h.app).post('/v1/messages/ack').set(companion.auth).send({ messageIds: [] }).expect(400);
    await request(h.app)
      .post('/v1/messages/ack')
      .set(companion.auth)
      .send({ messageIds: Array(101).fill(randomUUID()) })
      .expect(400);
    await request(h.app).get('/v1/messages/pending?limit=0').set(companion.auth).expect(400);
    await request(h.app).get('/v1/messages/pending?limit=1000').set(companion.auth).expect(400);
  });

  it('bounds the per-recipient queue and pages results', async () => {
    const { source, companion } = await pairedDevices(h.app);
    for (let i = 0; i < 5; i++) {
      await request(h.app)
        .post('/v1/messages')
        .set(source.auth)
        .send(signedMessage(source, companion.id))
        .expect(201);
    }
    const full = await request(h.app)
      .post('/v1/messages')
      .set(source.auth)
      .send(signedMessage(source, companion.id));
    expect(full.status).toBe(429);
    expect(full.body.error.code).toBe('queue_full');

    const page = await request(h.app).get('/v1/messages/pending?limit=2').set(companion.auth).expect(200);
    expect(page.body.messages).toHaveLength(2);
    expect(page.body.more).toBe(true);
    await request(h.app)
      .post('/v1/messages/ack')
      .set(companion.auth)
      .send({ messageIds: page.body.messages.map((m: { messageId: string }) => m.messageId) })
      .expect(200);
    await request(h.app)
      .post('/v1/messages')
      .set(source.auth)
      .send(signedMessage(source, companion.id))
      .expect(201);
  });

  it('removes undelivered and acknowledged rows once they expire', async () => {
    const { source, companion } = await pairedDevices(h.app);
    const m = signedMessage(source, companion.id);
    await request(h.app).post('/v1/messages').set(source.auth).send(m).expect(201);
    h.advance(3_600_000 + 1000);
    const pending = await request(h.app).get('/v1/messages/pending').set(companion.auth).expect(200);
    expect(pending.body.messages).toEqual([]);
    const removed = await runExpiry(h.ctx, h.metrics);
    expect(removed.messages).toBeGreaterThanOrEqual(1);
    const left = await h.db.query('SELECT COUNT(*) AS n FROM relay_messages WHERE message_id = $1', [
      m.messageId,
    ]);
    expect(Number(left.rows[0].n)).toBe(0);
    expect(h.metrics.expiredMessages).toBeGreaterThanOrEqual(1);
  });
});
