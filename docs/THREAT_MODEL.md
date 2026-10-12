# Threat Model

What RelaySMS protects, against whom, how, and what it does not protect. Protocol details are in
[PROTOCOL.md](PROTOCOL.md); stored data and deletion in [DATA_RETENTION.md](DATA_RETENTION.md);
relay operation in [DEPLOYMENT.md](DEPLOYMENT.md).

**Scope of evidence.** Statements marked *tested* are covered by automated tests in this
repository (server integration tests, Android JVM tests against a real relay, emulator tests).
Nothing here was verified on the physical phones, on a production deployment or by an independent
security review. See [section 6](#6-review-record).

## 1. System and assets

```
 SMS  ->  Source phone  --TLS-->  Relay (Node.js + PostgreSQL)  --TLS-->  Companion phone
          (holds the SIM)         (stores ciphertext only)                (decrypts, inbox)
```

| Asset | Where it lives | Why it matters |
| --- | --- | --- |
| SMS text, sender, one-time codes | Source: encrypted outbox until uploaded. Companion: inbox, encrypted columns | Codes open bank and login sessions |
| Device private keys (HPKE X25519, Ed25519) | Each phone, Tink keysets wrapped by an Android Keystore key | Decrypt messages / prove a message came from the paired phone |
| Relay credential (`deviceId.secret`) | Phone: encrypted at rest. Relay: SHA-256 hash only | Lets a device talk to the relay. Not an encryption key |
| Pairing secrets (QR code) | On the companion's screen for up to 5 minutes | Decide which phone becomes the peer |
| Link (who is paired with whom) | Relay database, both phones | Decides where ciphertext is routed |
| Metadata (timing, sizes, addresses) | Relay, network | Reveals activity, not content |

Trust assumptions: both phones are owned by the user, run an unmodified OS with a screen lock,
and are not compromised. The relay, its host, its database, its backups and every network in
between are **not** trusted with content.

## 2. What end-to-end encryption does and does not give you

It protects message content between the moment the source phone encrypts it and the moment the
companion decrypts it: from the relay, the relay's operator and hosting provider, backups, and
anyone on the network.

It does **not** protect a message on a phone that is compromised, unlocked in someone else's
hands, or running a modified app; it does not hide metadata from the relay; and it cannot make
SMS itself secure — the mobile network and the source phone's own SMS app see every message
before RelaySMS does. Forwarding codes to a second phone also means **two** devices can now leak
them. That is the cost of the feature.

## 3. Threats

Each entry: what could happen, what stops or limits it, and what remains.

### T1. Stolen or lost companion phone

- *Risk:* the thief reads forwarded codes in the inbox and keeps receiving new ones.
- *Controls:* message text and sender are encrypted in the database with a key that is wrapped by
  Android Keystore and never leaves the device (*tested* on the emulator); the database,
  preferences and key files are excluded from cloud backup and device transfer; notifications
  are generic by default and show content only if the user opts in (Module 7, not part of this
  review); the QR pairing screen blocks screenshots. From the source phone, **Revoke device** deletes the link and all queued
  ciphertext on the relay and stops forwarding (*tested*): nothing new reaches the stolen phone.
- *Residual:* **RelaySMS has no app lock of its own.** Anyone who can unlock the phone, or who
  takes it while unlocked, can open the app and read everything in the inbox. Revocation does not
  erase messages already delivered. On a rooted or exploited phone the Keystore protection can be
  bypassed while the device is unlocked. Mitigations available to the user: a strong screen lock,
  a short retention period (Settings → Message retention), remote wipe through the phone vendor,
  and revoking from the source phone as soon as the loss is noticed.

### T2. Stolen or lost source phone

- *Risk:* the thief reads SMS directly (they have the SIM and the phone's own SMS app — RelaySMS
  adds little here) and could try to forward messages elsewhere.
- *Controls:* the outbox is encrypted at rest; re-pairing to another companion needs the phone
  unlocked. The companion can revoke the link.
- *Residual:* the SIM itself is the larger problem; block it with the operator.

### T3. Unauthorised device tries to pair

- *Risk:* a stranger becomes the companion and receives the user's SMS, or becomes the source and
  feeds the user false messages.
- *Controls:* a pairing session needs a 128-bit secret that exists only in the QR code shown on
  the companion; it expires after 5 minutes, is single-use, is cancelled after 5 wrong guesses,
  and a new code cancels the old one (*tested*: expiry, replay, guessing, superseded code, wrong
  role, sessions hidden from non-participants). The source's keys are bound to the QR code by an
  HMAC whose key never reaches the relay, so the relay cannot substitute keys (*tested* with a
  key-substituting relay and a forged QR code). The link exists only after **both** users confirm
  a matching six-digit code. Pairing requests are rate limited per address. An optional
  `REGISTRATION_CODE` stops strangers from registering devices at all.
- *Residual:* someone who photographs the QR code within its lifetime and redeems it before the
  real source does becomes the pending peer; this is stopped only by the user noticing that their
  own source phone shows an error, not a code, and declining on the companion. A user who taps
  "confirm" without comparing the codes defeats this check.

### T4. Compromised, malicious or misconfigured relay

- *Risk:* the operator, an attacker who took over the host, or a leaked database or backup.
- *Controls:* the relay never receives plaintext or private keys; ciphertext is HPKE-sealed to
  the companion's key and bound to sender, recipient and message id; the companion verifies an
  Ed25519 signature against the peer key **it stored at pairing**, never a key supplied by the
  relay (*tested*: altered ciphertext, cross-pair injection, re-labelled ids are rejected). If
  the relay names a peer the phone did not verify, the phone removes the link instead of adopting
  it. Credentials are stored as hashes; ciphertext is deleted on acknowledgement or after 72
  hours; logs contain no bodies, tokens, secrets or keys (*tested*).
- *Residual:* the relay sees metadata (DEPLOYMENT.md §8). It can delay, drop, reorder or replay
  ciphertext (replays are ignored by id), report false link or presence state, lock a device out
  by answering `401` (the phone then discards its registration and asks for a new pairing), and
  exhaust itself. It can deny service; it cannot read or forge messages. There is no forward
  secrecy: someone who records ciphertext and **later** extracts the companion's private key can
  decrypt what they recorded (PROTOCOL.md §8). Misconfiguration that publishes the database port
  or `.env` exposes metadata and hashes, not content.

### T5. Stolen device credential (relay token)

- *Risk:* an attacker obtains `deviceId.secret` (from a compromised phone, or a TLS-inspecting
  proxy the user chose to trust).
- *Controls:* the token is not an encryption key. With a **companion** token an attacker can
  download ciphertext but cannot decrypt it. With a **source** token an attacker cannot inject
  messages: uploads must be signed with the source's Ed25519 key and the companion verifies the
  signature itself (*tested*). Tokens are sent only in the `Authorization` header, never in a URL
  (*tested*), responses are marked `no-store` (*tested*), the relay stores only a hash, a token
  can be rotated with immediate effect and rotation closes open connections (*tested*).
- *Residual:* a stolen token allows **denial of service against that pairing**: acknowledging
  pending messages (which deletes them undelivered), revoking the link, deleting the device, or
  starting pairing sessions. It also reveals the metadata of that one device. The apps do not yet
  rotate their token automatically; a token is replaced only by pairing again. If you suspect a
  token leaked, reset the phone in the app and pair again.

### T6. Network interception

- *Risk:* Wi-Fi operator, ISP or mobile network reads or alters traffic.
- *Controls:* release builds allow only HTTPS/WSS and refuse cleartext; certificate validation
  is the platform default against system trust anchors only — user-installed CAs are not trusted
  and there is no custom trust manager or hostname verifier. Underneath TLS, content is still
  end-to-end encrypted and signed, so even a TLS break reveals only what the relay sees.
- *Residual:* no certificate pinning (deliberate: a pinned key that is lost or rotated would lock
  both phones out of a self-hosted server with no update channel). A mis-issued certificate for
  the relay's domain would expose tokens and metadata, not content. Traffic analysis (when and
  how much) is possible. Debug builds permit cleartext to the local development host only; do not
  install a debug build on the real phones.

### T7. Replay

- *Risk:* a captured message, pairing step or request is sent again.
- *Controls:* message ids are stable; the relay stores `(sender, id)` and answers `duplicate`,
  also after acknowledgement, until the original expiry (*tested*); the companion keeps the ids of
  stored and deleted messages (tombstones for 30 days) so a redelivered message never appears
  twice (*tested*); the id is part of the HPKE context and the signature, so a ciphertext cannot
  be replayed under another id or towards another device (*tested*). Pairing codes are
  single-use (*tested*).
- *Residual:* HTTP requests are not individually signed or timestamped; replaying one requires
  breaking TLS and gains nothing beyond repeating an idempotent action. A message replayed to the
  companion more than 30 days after the user deleted it, by a relay that kept the ciphertext,
  would be shown again.

### T8. Malformed or hostile payloads

- *Risk:* crashes, resource exhaustion, injection, or a parser confused into accepting something.
- *Controls (relay):* every body and parameter is validated with a strict schema that rejects
  unknown fields; identifiers must be UUIDs; sizes are bounded (ciphertext 128 KiB, JSON body,
  100 ids per acknowledgement, 500 pending messages per recipient, 1 KiB WebSocket frames, 3
  sockets per device); all SQL is parameterised; errors have one shape and never include stack
  traces; request and header timeouts are set (*tested*, including prototype-polluting keys,
  non-JSON bodies, SQL-shaped ids, oversized frames). Rate limits apply per address to every
  request, including WebSocket upgrades and `/metrics`, and per device on message endpoints
  (*tested*).
- *Controls (phones):* a message is verified, then decrypted, then parsed strictly; unknown
  versions and kinds are rejected; a message that fails is acknowledged, dropped and counted, so
  one bad message cannot block the queue (*tested*). The SMS receiver validates the broadcast and
  is reachable only by the system (`BROADCAST_SMS`).
- *Residual:* rate-limit counters are in memory and per relay instance; a distributed flood can
  still exhaust a small server. The paired source could fill the relay with up to 500 pending
  messages and a larger number of content-free tombstones until they expire. An SMS crafted to
  *look* like a code from a bank is forwarded like any other from an allowed sender — RelaySMS
  does not authenticate SMS senders, and neither does the mobile network.

### T9. Accidental logging of codes or message text

- *Controls:* the relay never has plaintext. Its logger records fixed event names, ids and error
  class names, with a redaction list as a second line of defence (*tested*: tokens, pairing
  secrets, ciphertext, signatures and keys never appear). Configuration errors name variables,
  never values (*tested* at process level). The apps log through one helper that accepts only a
  fixed event name plus an enum or a number — no free text, no exception messages (*tested* on the
  emulator: injected SMS text does not appear in logcat or in the relay database). There is no
  analytics or crash-reporting SDK. The reverse proxy's access log is off.
- *Residual:* the phone's own SMS app, notification history, keyboard and clipboard are outside
  RelaySMS's control. A copied code sits in the system clipboard until cleared (the app clears it
  after a delay only if it is still the copied value). Android bug reports taken by the user
  include logcat and notification content.

### T10. Outdated application version

- *Risk:* an old build with a known flaw keeps running; two phones on different protocol versions
  misunderstand each other.
- *Controls:* the QR payload, the envelope and every signed string carry a version; a phone
  rejects versions it does not implement instead of guessing (*tested*). Relay API paths are
  versioned (`/v1`).
- *Residual:* the app is installed privately, so **there is no automatic update** and no way for
  the relay to force one. The user must rebuild and reinstall to receive fixes, including fixes in
  bundled libraries (Tink, OkHttp). Keep both phones on the same build.

### T11. Lost device and revoked pairing

- *Controls:* either phone can revoke; the relay deletes the link and all queued ciphertext in
  one transaction, notifies the peer and closes the device's connections (*tested*). The source
  then stops forwarding and destroys its unsent queue. A reset deletes the relay registration,
  the private keys, the database and preferences; the relay removes the device record a day later
  (*tested*). A revoked or deleted credential is refused immediately (*tested*).
- *Residual:* revocation needs the remaining phone to reach the relay. Until it does, the source
  keeps uploading and the relay keeps queueing for the lost phone (for at most 72 hours per
  message). Messages already delivered stay on the lost phone (T1). Revocation cannot be
  triggered from a web page or a third device.

### T12. The user forwards messages to the wrong device

- *Risk:* scanning the wrong QR code, or pairing with a phone that is later given away.
- *Controls:* forwarding is off until the user enables it; before pairing the source shows the
  destination's identity and both phones show the same six-digit code; only one companion can be
  linked at a time and pairing again replaces the old link and drops its queue (*tested*); the
  paired-device screen shows the peer, when it was paired and when it was last seen; sender
  filters limit what is forwarded at all.
- *Residual:* the check depends on the user actually comparing codes and on physically
  controlling both phones. Before selling or giving away either phone, use **Reset RelaySMS**.

### T13. Abuse of an open relay

- *Risk:* strangers register devices on a public relay and use it as free storage or to exhaust
  it.
- *Controls:* registration is rate limited per address; a device can only send to the one peer
  that confirmed pairing with it; queue and message sizes are bounded; everything expires.
  `REGISTRATION_CODE` closes registration to people who know the code.
- *Residual:* without `REGISTRATION_CODE`, unpaired device records accumulate slowly (at most
  `RATE_LIMIT_REGISTRATION_MAX` per address per hour) and are not garbage-collected. Set the
  code on any internet-facing relay.

## 4. Deliberate non-goals

- Hiding from the relay *that* two devices talk, when, and how much.
- Protecting against malware, root access or a modified OS on either phone.
- Forward secrecy and post-compromise security (no ratchet; see PROTOCOL.md §8).
- Protecting the SMS before it reaches RelaySMS (SS7 interception, SIM swap, the default SMS app).
- Anonymity towards the relay or the network.
- Google Play policy compliance. The app is for private installation.

## 5. Unresolved issues

No unresolved **high-severity** issue is known in the reviewed code. Open items, in order of
weight:

| # | Issue | Severity | Status |
| --- | --- | --- | --- |
| 1 | No app lock: an unlocked companion exposes the inbox (T1) | Medium | Open. Relies on the device screen lock |
| 2 | Tokens are not rotated automatically (T5) | Low | Open. Server support exists and is tested |
| 3 | No forward secrecy (T4) | Low for a personal relay with 72 h retention | Accepted by design, documented |
| 4 | Unpaired device records are never garbage-collected (T13) | Low | Open. Mitigated by `REGISTRATION_CODE` |
| 5 | Queue-size check and insert are separate statements, so concurrent uploads can exceed the bound by a few messages | Low | Open. Bounded by the per-device rate limit |
| 6 | Container deployment, real phones and real carrier traffic are untested | Unknown | Blocked on Docker and devices |

Items 1 and 2 need Android changes and belong to the Android part of this module.

## 6. Review record

Server review, 2026-10-12, by reading all of `server/src` and `server/migrations`.

Checked and found sound: authentication on every `/v1` route except registration; the device id
in a token only selects which hash to compare; constant-time comparisons; recipient derived from
the server-side link, never from the request; acknowledgement and retrieval scoped to the
authenticated recipient; pairing state changes under a row lock in a transaction; failed-attempt
counter committed even when the request fails; parameterised SQL throughout (the one interpolated
identifier is chosen from two constants); no endpoint returns or logs ciphertext to anyone but
its recipient; graceful shutdown.

Gaps found and fixed in this review, each with a test in `server/tests/hardening.test.ts`:

1. WebSocket upgrade requests bypassed the rate limiter, so connection attempts with well-formed
   tokens could drive unlimited database lookups. Upgrades are now limited per client address
   before any credential check.
2. `/metrics` was exempt from rate limiting although it runs three counting queries. It is now
   limited; the proxy configuration also stops publishing it.
3. Responses that carry credentials had no `Cache-Control`. Every response is now `no-store`.
4. Records of revoked devices (public keys, credential hash) were kept forever although the
   retention document said otherwise. They are now deleted a day after revocation.

Dependency check (`npm audit`, same date):

- Production dependencies (`npm audit --omit=dev`): **0 vulnerabilities**. These are the only
  packages in the container image (`npm ci --omit=dev`). CI fails on a high-severity finding.
- Including development tools: the first run reported 6 advisories (3 moderate, 1 high, 2
  critical), all in the test runner's dependency chain (vitest 2, vite, esbuild, tinypool) and
  none in code that ships. The test runner was upgraded from vitest 2.1.9 to 5.0.4; the full
  suite passes on it and `npm audit` now reports **0 vulnerabilities**.
- Android dependencies were not audited with a vulnerability scanner in this review.

Not done: penetration testing, fuzzing, static-analysis tooling beyond ESLint and the TypeScript
compiler, any review by a second person, and review of the Android code beyond the facts cited
above (Android hardening is tracked separately in Module 9).
