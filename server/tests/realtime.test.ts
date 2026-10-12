import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import request from 'supertest';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import WebSocket from 'ws';
import { WS_PATH } from '../src/app.js';
import { authenticateUpgrade } from '../src/middleware/auth.js';
import {
  createHarness,
  pairedDevices,
  registerDevice,
  signedMessage,
  type Harness,
  type TestDevice,
} from './helpers.js';

let h: Harness;
let server: Server;
let url: string;

beforeAll(async () => {
  h = await createHarness();
  server = createServer(h.app);
  h.ctx.hub.attach(
    server,
    WS_PATH,
    (req) => authenticateUpgrade(h.ctx, req),
    () => undefined,
    h.ctx.logger,
  );
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  url = `ws://127.0.0.1:${(server.address() as AddressInfo).port}${WS_PATH}`;
});
afterAll(async () => {
  await h.close();
  await new Promise((resolve) => server.close(resolve));
});

function connect(device?: TestDevice, path = url): Promise<{ ws: WebSocket; events: unknown[] }> {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(path, device ? { headers: device.auth } : {});
    const events: unknown[] = [];
    ws.on('message', (data) => events.push(JSON.parse(data.toString())));
    ws.on('open', () => resolve({ ws, events }));
    ws.on('error', reject);
    ws.on('unexpected-response', (_req, res) => reject(new Error(`HTTP ${res.statusCode}`)));
  });
}

const until = async (cond: () => boolean) => {
  for (let i = 0; i < 100 && !cond(); i++) await new Promise((r) => setTimeout(r, 20));
  expect(cond()).toBe(true);
};

describe('realtime channel', () => {
  it('refuses unauthenticated and misdirected connections', async () => {
    await expect(connect()).rejects.toThrow(/401/);
    const d = await registerDevice(h.app, 'companion');
    await expect(connect({ ...d, auth: { Authorization: 'Bearer nope' } })).rejects.toThrow(/401/);
    await expect(connect(d, url.replace(WS_PATH, '/other'))).rejects.toThrow(/404/);
  });

  it('notifies only the recipient, with a content-free event, and reports presence', async () => {
    const a = await pairedDevices(h.app);
    const b = await pairedDevices(h.app);
    const recipient = await connect(a.companion);
    const bystander = await connect(b.companion);
    const sender = await connect(a.source);

    const me = await request(h.app).get('/v1/devices/me').set(a.source.auth).expect(200);
    expect(me.body.peer.online).toBe(true);

    await request(h.app)
      .post('/v1/messages')
      .set(a.source.auth)
      .send(signedMessage(a.source, a.companion.id))
      .expect(201);
    await until(() => recipient.events.length > 0);
    expect(recipient.events).toEqual([{ type: 'messages_available' }]);
    expect(bystander.events).toEqual([]);

    const pending = await request(h.app).get('/v1/messages/pending').set(a.companion.auth).expect(200);
    await request(h.app)
      .post('/v1/messages/ack')
      .set(a.companion.auth)
      .send({ messageIds: [pending.body.messages[0].messageId] })
      .expect(200);
    await until(() => sender.events.some((e) => (e as { type: string }).type === 'delivery_updated'));

    for (const c of [recipient, bystander, sender]) c.ws.close();
  });

  it('drops the connection of a revoked device', async () => {
    const { companion } = await pairedDevices(h.app);
    const c = await connect(companion);
    const closed = new Promise<number>((resolve) => c.ws.on('close', (code) => resolve(code)));
    await request(h.app).delete('/v1/devices/me').set(companion.auth).expect(204);
    expect(await closed).toBe(4401);
    await expect(connect(companion)).rejects.toThrow(/401/);
  });
});
