#!/usr/bin/env node
// Round-trip check of a running relay, using only its public API (docs/API.md).
//
//   node scripts/relay-smoke.mjs https://relay.example.com
//   REGISTRATION_CODE=... node scripts/relay-smoke.mjs https://relay.example.com
//
// Registers two throwaway devices, pairs them, uploads one signed opaque payload, fetches it as
// the companion, acknowledges it, and deletes both devices again. The payload is random bytes:
// the relay never decrypts anything, so this proves routing, authorisation and storage, not the
// phones' encryption (that is covered by the Android integration tests). Needs Node.js 22+.
// It uses two of the hourly device registrations allowed per client address.
import { generateKeyPairSync, randomBytes, randomUUID, sign } from 'node:crypto';

const base = (process.argv[2] ?? '').replace(/\/+$/, '');
if (!/^https?:\/\//.test(base)) {
  console.error('usage: node scripts/relay-smoke.mjs <relay base URL>');
  process.exit(2);
}
const registrationCode = process.env.REGISTRATION_CODE || undefined;

let step = 0;
const ok = (text) => console.log(`ok ${++step} - ${text}`);
function fail(text) {
  console.error(`FAILED - ${text}`);
  process.exit(1);
}

async function call(method, path, { token, body, expect = 200 } = {}) {
  const res = await fetch(base + path, {
    method,
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(15_000),
  });
  const text = await res.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    // Not JSON: reported below through the status check.
  }
  if (res.status !== expect) {
    // Only the status and the relay's error code are printed, never request data.
    fail(`${method} ${path}: expected ${expect}, got ${res.status} ${json?.error?.code ?? ''}`);
  }
  return json;
}

async function register(role) {
  const { publicKey, privateKey } = generateKeyPairSync('ed25519');
  const signingPublicKey = publicKey.export({ format: 'der', type: 'spki' }).subarray(-32);
  const res = await call('POST', '/v1/devices', {
    expect: 201,
    body: {
      role,
      hpkePublicKey: randomBytes(32).toString('base64url'),
      signingPublicKey: signingPublicKey.toString('base64url'),
      ...(registrationCode ? { registrationCode } : {}),
    },
  });
  return { id: res.deviceId, token: res.token, privateKey };
}

const created = [];
try {
  const health = await call('GET', '/healthz');
  if (health?.status !== 'ok') fail('/healthz did not report ok');
  const ready = await call('GET', '/readyz');
  if (ready?.status !== 'ready') fail('/readyz did not report ready');
  ok('relay is up and its database is reachable');

  const companion = await register('companion');
  created.push(companion);
  const source = await register('source');
  created.push(source);
  ok('registered a companion and a source device');

  await call('GET', '/v1/messages/pending', { expect: 401 });
  await call('POST', '/v1/messages', {
    token: source.token,
    expect: 409,
    body: {
      messageId: randomUUID(),
      ciphertext: randomBytes(16).toString('base64url'),
      signature: randomBytes(64).toString('base64url'),
    },
  });
  ok('unauthenticated and unpaired requests are refused');

  const pairing = await call('POST', '/v1/pairings', { token: companion.token, expect: 201 });
  await call('POST', `/v1/pairings/${pairing.pairingId}/redeem`, {
    token: source.token,
    body: { secret: pairing.secret, proof: randomBytes(32).toString('base64url') },
  });
  await call('POST', `/v1/pairings/${pairing.pairingId}/confirm`, { token: source.token });
  const done = await call('POST', `/v1/pairings/${pairing.pairingId}/confirm`, { token: companion.token });
  if (done?.status !== 'completed') fail('pairing did not complete');
  ok('paired the two devices');

  const messageId = randomUUID();
  const payload = randomBytes(300);
  const header = Buffer.from(`relaysms/v1/message\n${source.id}\n${companion.id}\n${messageId}\n`, 'utf8');
  const message = {
    messageId,
    ciphertext: payload.toString('base64url'),
    signature: sign(null, Buffer.concat([header, payload]), source.privateKey).toString('base64url'),
  };
  const first = await call('POST', '/v1/messages', { token: source.token, body: message, expect: 201 });
  if (first?.status !== 'accepted') fail('upload was not accepted');
  const again = await call('POST', '/v1/messages', { token: source.token, body: message, expect: 200 });
  if (again?.status !== 'duplicate') fail('repeated upload was not recognised as a duplicate');
  ok('uploaded a signed payload; the repeat was recognised as a duplicate');

  const pending = await call('GET', '/v1/messages/pending', { token: companion.token });
  if (pending.messages.length !== 1 || pending.messages[0].ciphertext !== message.ciphertext) {
    fail('the companion did not receive exactly the uploaded payload');
  }
  const foreign = await call('GET', '/v1/messages/pending', { token: source.token });
  if (foreign.messages.length !== 0) fail('the sender can see the message queue of its peer');
  ok('the companion received the payload unchanged; nobody else did');

  const ack = await call('POST', '/v1/messages/ack', { token: companion.token, body: { messageIds: [messageId] } });
  if (ack?.acknowledged !== 1) fail('acknowledgement was not counted');
  const after = await call('GET', '/v1/messages/pending', { token: companion.token });
  if (after.messages.length !== 0) fail('the payload is still queued after acknowledgement');
  ok('acknowledgement removed the payload from the relay');
} finally {
  for (const device of created) {
    await fetch(`${base}/v1/devices/me`, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${device.token}` },
      signal: AbortSignal.timeout(15_000),
    }).catch(() => undefined);
  }
}
for (const device of created) await call('GET', '/v1/devices/me', { token: device.token, expect: 401 });
ok('deleted the test devices; their credentials no longer work');
console.log('Relay smoke test passed.');
