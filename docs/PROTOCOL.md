# RelaySMS End-to-End Protocol (version 1)

This document specifies how two RelaySMS devices identify each other, pair, and exchange messages
through an untrusted relay. The relay API is in [API.md](API.md).

## 1. Goals and non-goals

- The relay and any network observer learn no message text, SMS sender address or code.
- Only a device the user paired in person can receive messages, and only that device can make the
  source phone act (remote sending).
- A relay that misbehaves during pairing is detected rather than trusted.
- Not a goal: protecting data on a phone that is itself compromised or unlocked in someone else's
  hands; hiding traffic metadata (who is linked to whom, when, and how much) from the relay;
  forward secrecy against later theft of a device's long-term key (see §8).

## 2. Primitives

All from Google Tink 1.15 (`tink-android`), plus JCA HMAC-SHA-256 and SHA-256. No custom
cryptography.

| Purpose | Algorithm |
| --- | --- |
| Message encryption | HPKE (RFC 9180) base mode: DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, AES-256-GCM. Fresh ephemeral key per message. Tink variant `NO_PREFIX`; ciphertext = `enc (32 bytes) ‖ AEAD ciphertext` |
| Message authenticity | Ed25519, raw 64-byte signatures |
| Pairing proof | HMAC-SHA-256 |
| Local storage | AES-256-GCM (Tink AEAD) with associated data naming the record |
| Key wrapping at rest | AES-256-GCM key in Android Keystore (`android-keystore://relaysms_local_master`) |

Encodings: public keys are raw 32-byte values; binary values on the wire are base64url without
padding; text is UTF-8.

## 3. Keys

Each device has two long-term key pairs, generated on the device the first time it registers:

- an HPKE key pair (X25519) — others encrypt to it;
- a signing key pair (Ed25519) — it signs what it sends.

**Storage.** Each private keyset is serialised by Tink and stored in the private preferences file
`relaysms_keysets`, encrypted by the Keystore master key. Private keys are never exported, logged,
placed in a QR code or sent to the relay. The relay credential and the peer's public keys are
stored in `relaysms_identity`, each encrypted with the local AES-256-GCM keyset. Both files, the
database and DataStore are excluded from cloud backup and device-to-device transfer.

**Relay credential.** `deviceId.secret`, where `secret` is 256 random bits issued by the relay at
registration. The relay stores only `SHA-256(secret)`. It authenticates requests; it is not an
encryption key and gives no ability to read messages.

## 4. Pairing

Actors: companion **C** (shows the QR code), source **S** (scans it), relay **R** (untrusted).

```
C                                   R                                   S
| register(hpkeC, signC) ---------->|                                   |
| POST /pairings ------------------>|  pairingId, redeemSecret, expiry  |
| macKey := 32 random bytes         |                                   |
| QR = {v, url, pairingId, redeemSecret, macKey, idC, hpkeC, signC}     |
|================== optical channel: S scans the QR ===================>|
|                                   |<----- register(hpkeS, signS) -----|
|                                   |   proof := HMAC(macKey, T_proof)  |
|                                   |<-- redeem(redeemSecret, proof) ---|
|                                   |--- companion keys as R knows ---->|  S: must equal QR keys
| GET /pairings/:id --------------->|  idS, hpkeS, signS, proof         |
| C: verify proof with macKey       |                                   |
| show code = SAS(T_sas)            |                 show code = SAS(T_sas)
|        user compares the two 6-digit codes and confirms on both       |
| confirm ------------------------->|<------------------------- confirm |
|             link exists only after both confirmations                 |
```

Transcripts (`\n`-joined, trailing `\n`, UTF-8):

```
T_x = label ‖ pairingId ‖ idC ‖ hpkeC ‖ signC ‖ idS ‖ hpkeS ‖ signS
T_proof label = "relaysms/v1/pairing-proof"
T_sas   label = "relaysms/v1/safety-code"
```

`SAS(T)` = first 7 bytes of `SHA-256(T)` as an unsigned integer, modulo 10^6, shown as `ddd ddd`.

### Why each step exists

- **Companion keys travel in the QR code**, so S encrypts to the right device no matter what the
  relay says. S aborts (`KEY_MISMATCH`) if the relay reports different keys for C.
- **`macKey` never reaches the relay.** It is the only way to produce `proof`, so C accepts S's
  keys only if they were chosen by whoever saw C's screen. A relay that substitutes S's keys is
  detected (`PROOF_INVALID`) and the session is cancelled. A short comparison code alone would not
  achieve this: a relay could search for substitute keys that yield the same six digits.
- **`redeemSecret`** (128 random bits, hashed at rest) makes the session unguessable. It is
  single-use; a second redemption fails. Wrong guesses are counted and the session is cancelled
  after five. Sessions expire after five minutes; creating a new code cancels the previous one.
- **The six-digit code** covers what cryptography cannot: the user scanning a code from the wrong
  phone, or someone photographing the QR code and redeeming it first. In that case the user's own
  source phone shows an error instead of a code, and the instructions on the companion say to
  confirm only when both phones show the same digits.
- **Two confirmations** are required by the relay before a link exists, so neither phone can be
  linked by the other acting alone.

What the QR code contains: public keys, two short-lived single-use secrets, ids and the relay URL.
It never contains a private key or a device credential. The pairing screen sets `FLAG_SECURE`.

## 5. Message format

### 5.1 Envelope (plaintext, before encryption)

JSON, UTF-8:

```json
{ "v": 1, "kind": "sms", "id": "<messageId>", "sentAt": 1791810996883, "body": { ... } }
```

`kind` selects the body type. `kind = "sms"`:

```json
{ "sender": "TESTBANK", "body": "...", "receivedAtMillis": 1791810990000, "partCount": 1, "subscriptionId": -1 }
```

Unknown versions and kinds are rejected by the receiver.

### 5.2 Encryption and signature

```
info       = utf8("relaysms/v1/hpke\n" + senderId + "\n" + recipientId + "\n" + messageId)
ciphertext = HPKE.Seal(recipientHpkePublicKey, info, plaintext = envelope)
signature  = Ed25519.Sign(senderSigningKey,
               utf8("relaysms/v1/message\n" + senderId + "\n" + recipientId + "\n" + messageId + "\n") ‖ ciphertext)
```

The relay sees `messageId`, `ciphertext`, `signature` and the authenticated sender. It derives the
recipient from the link. It verifies the signature with the sender's registered key, which stops a
stolen credential alone from injecting messages, and stores the ciphertext until acknowledged or
expired.

### 5.3 Receiving

1. Verify `signature` against the **locally stored** peer signing key (not a key from the relay).
2. `HPKE.Open` with the same `info`; a ciphertext moved to another message id, sender or
   recipient fails to open.
3. Parse the envelope strictly; require `id == messageId`, `v == 1`, a known `kind`.
4. Store durably, then acknowledge. A message that fails steps 1–3 is acknowledged and dropped
   (it can never become valid), and counted as rejected.

### 5.4 Identifiers and replay

`messageId` for an SMS is a hash of the message (sender, timestamp, subscription, text) formatted
as a UUID: the same SMS always maps to the same id. Submission is idempotent on the relay, and the
receiver keeps ids it has already stored, so a replayed or re-delivered ciphertext cannot create a
second inbox entry. The id is a one-way hash and reveals nothing about the content.

## 6. Delivery semantics

At-least-once transport with deduplication at both ends. The source keeps an item until the relay
accepts it; the relay keeps ciphertext until the companion acknowledges it or it expires (72 h by
default); the companion acknowledges only after the message is committed to its database. Exactly
once delivery across a network is not claimed.

## 7. Revocation, replacement and recovery

- **Revoke device** (either phone): the relay deletes the link and all queued ciphertext between
  the two devices. The source turns forwarding off and destroys its unsent queue.
- **Reset / role change:** the device asks the relay to delete its registration, then deletes its
  private keys, credential, database and preferences.
- **Lost or replaced phone:** revoke from the remaining phone, then pair the replacement. A new
  phone has new keys; nothing is transferred.
- **Reinstall, cleared data or lost Keystore key:** private keys are unrecoverable by design.
  The app detects that stored data cannot be decrypted, discards it and requires a new pairing.
- **Consistency:** on each contact the phone compares the relay's view of the link with the peer
  it verified. If the relay names a peer the phone did not verify, the phone removes the link on
  both sides; it never adopts keys supplied by the relay.

## 8. Key rotation

- Relay credentials can be rotated at any time (`POST /v1/devices/me/token`); the old one stops
  working immediately.
- Long-term identity keys are rotated by pairing again: a reset generates new keys. There is no
  automatic ratchet. Consequence: someone who records ciphertext from the relay and later extracts
  a companion's HPKE private key from its Keystore-wrapped storage could decrypt the recorded
  messages. The relay's short retention and the Keystore wrapping are the mitigations.

## 9. Versioning

The QR payload (`v`), the envelope (`v`) and every signed or derived string (`relaysms/v1/...`)
carry a version. A future version 2 uses new labels, so version 1 ciphertexts, signatures and
proofs can never be reinterpreted under new rules. Devices reject versions they do not implement.
