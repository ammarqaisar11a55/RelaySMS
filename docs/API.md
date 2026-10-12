# Relay API (v1)

Base path `/v1`. JSON in and out. All errors have one shape:

```json
{ "error": { "code": "machine_readable_code", "message": "Human readable text." } }
```

Authentication: `Authorization: Bearer <deviceId>.<secret>`. The relay stores only a SHA-256 of
the secret. Binary values are base64url without padding.

The relay never receives plaintext. `ciphertext` is opaque to it.

## Health (no authentication)

| Method | Path | Result |
| --- | --- | --- |
| GET | `/healthz` | `200 {"status":"ok"}` — process is up |
| GET | `/readyz` | `200 {"status":"ready"}` or `503` — database reachable |
| GET | `/metrics` | Aggregate counters only: uptime, active devices, links, pending messages, open connections, expired counts. Rate limited per client address; not published by the bundled reverse proxy |

`/healthz` and `/readyz` are exempt from rate limits. Every response of the relay carries
`Cache-Control: no-store`.

## Devices

| Method | Path | Auth | Body | Result |
| --- | --- | --- | --- | --- |
| POST | `/v1/devices` | – | `{role: "source"\|"companion", hpkePublicKey: b64url(32), signingPublicKey: b64url(32), registrationCode?}` | `201 {deviceId, token}` — the token is shown once |
| GET | `/v1/devices/me` | yes | – | `{deviceId, role, createdAt, serverTime, peer: null \| {deviceId, role, hpkePublicKey, signingPublicKey, linkedAt, lastSeenAt, online, awaitingPeer}}` |
| POST | `/v1/devices/me/token` | yes | – | `{token}` — the old token stops working when the new one is first used, and after 24 hours at the latest; open connections are closed |
| DELETE | `/v1/devices/me` | yes | – | `204` — credential disabled; link, queued messages and pairing sessions deleted |
| DELETE | `/v1/link` | yes | – | `{revoked: boolean}` — removes the link and all queued ciphertext between the two devices |

## Pairing

| Method | Path | Who | Body | Result |
| --- | --- | --- | --- | --- |
| POST | `/v1/pairings` | companion | – | `201 {pairingId, secret, expiresAt}`; cancels the companion's earlier open sessions |
| POST | `/v1/pairings/:id/redeem` | source | `{secret, proof: b64url(32)}` | `{pairingId, expiresAt, companion: {deviceId, role, hpkePublicKey, signingPublicKey}}` |
| GET | `/v1/pairings/:id` | participant | – | `{pairingId, status, expiresAt, companion, source, sourceProof, sourceConfirmed, companionConfirmed}` |
| POST | `/v1/pairings/:id/confirm` | participant | – | `{status: "redeemed"\|"completed"}`; the link exists only once both sides confirmed |
| POST | `/v1/pairings/:id/cancel` | participant | – | `{status: "cancelled"}` |

Errors: `pairing_not_found` 404 (also returned to non-participants), `pairing_expired` 410,
`pairing_cancelled` 410 (cancelled by a participant, superseded by a newer code, or locked after
wrong guesses), `pairing_already_redeemed` 409, `invalid_pairing_secret` 403 (counted; the session is
cancelled after `PAIRING_MAX_FAILED_ATTEMPTS`), `pairing_not_redeemed` 409, `wrong_role` 403.

`proof` is opaque to the relay: it is an HMAC keyed with a secret that exists only in the QR code,
which lets the companion check the source's keys without trusting the relay (see `PROTOCOL.md`).

Completing a pairing replaces any previous link of either device and deletes that link's queued
ciphertext.

## Messages

| Method | Path | Body / query | Result |
| --- | --- | --- | --- |
| POST | `/v1/messages` | `{messageId: uuid, ciphertext: b64url(1..MAX_CIPHERTEXT_BYTES), signature: b64url(64)}` | `201 {"status":"accepted"}` or `200 {"status":"duplicate"}` |
| GET | `/v1/messages/pending` | `?limit=1..100` (default 50) | `{messages: [{messageId, senderId, ciphertext, signature, createdAt}], more}` oldest first |
| POST | `/v1/messages/ack` | `{messageIds: uuid[1..100]}` | `{acknowledged: n}` |

- The recipient is always the caller's linked peer. There is no recipient field.
- `signature` is Ed25519 by the sender over
  `utf8("relaysms/v1/message\n" + senderId + "\n" + recipientId + "\n" + messageId + "\n") || ciphertext`.
  The relay verifies it with the sender's registered key (`invalid_signature` 400).
- Submission is idempotent on `(sender, messageId)`, including after acknowledgement.
- Acknowledgement erases the ciphertext at once; a content-free tombstone remains until the
  original expiry so that late duplicates are recognised. Acknowledging twice is harmless.
- Errors: `not_linked` 409, `queue_full` 429, `payload_too_large` 413, `rate_limited` 429.

## Realtime hints

`GET /v1/ws` (WebSocket upgrade, same `Authorization` header). The server sends small JSON events
and ignores anything the client sends:

| Event | Meaning |
| --- | --- |
| `{"type":"messages_available"}` | Call `GET /v1/messages/pending` |
| `{"type":"pairing_updated","pairingId"}` | Call `GET /v1/pairings/:id` |
| `{"type":"link_changed"}` | Call `GET /v1/devices/me` |
| `{"type":"delivery_updated"}` | The peer acknowledged something; refresh `awaitingPeer` |

Events never contain message data. Close code `4401` means the credential was revoked or rotated.
The credential is accepted only in the `Authorization` header, never in the URL. Connection
attempts are rate limited per client address (`429`) before the credential is checked; frames
larger than 1 KiB close the connection (`1009`).

## What the relay can observe

Device ids and roles, public keys, which two devices are linked, when devices connect, message
ids (random-looking hashes), ciphertext sizes and timestamps. It cannot observe message text,
SMS sender addresses or codes.
