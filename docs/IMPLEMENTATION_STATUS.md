# Implementation Status

Statuses: `NOT STARTED`, `IN PROGRESS`, `BLOCKED`, `TESTING`, `COMPLETED`.
Only verifiable facts are recorded. Commit hashes are filled in by the commit that follows each
module (a commit cannot contain its own hash).

## Phase 0 — Repository audit and feasibility

- Status: `COMPLETED` (emulator scope; real-device checks listed as open in `FEASIBILITY.md`)
- Created: Gradle project in `android/`, `spike/` receiver + screen, `docs/FEASIBILITY.md`
- Acceptance: documented evidence that the private-install path supports incoming SMS — met on
  the Android 15 emulator; precise open device checks documented
- Tests: emulator scenarios T0–T9 in `FEASIBILITY.md` — PASS
- Build: `:app:assembleDebug` — PASS
- Known issues: no physical device attached; Docker not installed on the dev host
- Commit: `580504f` — `feat(android): add SMS permission feasibility spike and report`
- Push: SUCCESS (`origin/main`)

## Module 1 — Android foundation and application design

- Status: `COMPLETED`
- Created: `RelaySmsApp`, `AppContainer` (manual DI), `MainActivity`, `ui/navigation` (`AppRoot`,
  `Routes`), `ui/theme` (Material 3 light/dark), `ui/components` (scaffold, cards, status rows,
  loading/empty/error states, confirm dialog), `feature/onboarding`, `feature/home`,
  `feature/settings`, `core/prefs/AppPreferences` (DataStore), `core/network/ServerUrl`,
  `core/reset/ResetCoordinator`, network security config (TLS only; debug allows cleartext to the
  local dev host), backup exclusion rules. Phase Zero spike code removed (kept in `580504f`).
- Acceptance: builds; both roles selectable; navigation works; light/dark themes; preferences
  survive restart; role-specific screens render; companion requests no SMS permission (the APK
  declares only `INTERNET` and `ACCESS_NETWORK_STATE` at this point) — all met
- Tests: 12 JVM unit tests (`ServerUrlTest`, `RoutesTest`, `AppPreferencesTest`) — PASS;
  2 instrumented Compose tests (`OnboardingFlowTest`, Android 15 emulator) — PASS
- Build: `:app:assembleDebug`, `:app:lintDebug` — PASS
- Known issues: UI strings are English only and defined in code; home screens show only the
  states that exist so far (not paired, forwarding off)
- Commit: `90ed628` — `feat(android): establish app roles and navigation`
- Push: SUCCESS

## Module 2 — SMS reception and sender permissions

- Status: `COMPLETED` (emulator scope; real Telenor SMS not yet tested — no device attached)
- Created: `sms/SmsReceiver` (manifest receiver guarded by `BROADCAST_SMS`, `goAsync`, 7 s
  budget), `SmsAssembler` (multipart join + validation), `EventId` (stable hash id),
  `SenderMatcher` / `ForwardingPolicy`, `IncomingSmsProcessor`, `SourceStores`; Room database v1
  (`outbox`, `sender_filters`, `seen_senders`); `core/security/FieldCipher` (Tink AES-256-GCM,
  keyset wrapped by Android Keystore); `delivery/OutboxDeliverer` + `DeliveryWorker` (WorkManager,
  network constraint, exponential backoff, 72 h expiry, bounded queue of 500); `SafeLog`;
  source home with permission rationale, restricted-settings guidance, forwarding switch and
  content-free counters; sender filter screen with suggestions
- Acceptance: test SMS detected; multipart handled; filtered messages not queued; pausing stops
  new queueing; permission denial handled without crash; no content in logs — met on emulator.
  "Disabling forwarding prevents new uploads" is verified at the queue (nothing new is queued);
  the upload transport itself arrives with Module 5
- Tests: 28 JVM unit tests — PASS; 10 instrumented tests on the Android 15 emulator
  (`OutboxDelivererTest` x5, `OnboardingFlowTest` x2, scenario steps) — PASS;
  `scripts/emulator-sms-check.sh` 7 checks with injected SMS — PASS
- Build: `:app:assembleDebug`, `:app:lintDebug` — PASS
- Known issues: until pairing exists, accepted messages wait encrypted in the outbox and are
  discarded after 72 h; dual-SIM behaviour untested; OEM battery behaviour untested
- Commit: `9bec826` — `feat(android): receive, filter and queue incoming SMS`
- Push: SUCCESS

Module order note: Module 4 (backend) is implemented before Module 3 (pairing) because pairing's
acceptance criteria need the server.

## Module 4 — Node.js/TypeScript relay backend

- Status: `COMPLETED`
- Created: `server/` (Express 5, `pg`, `ws`, `zod`, `pino`): config validation, migration runner
  and `001_init.sql` (devices, pairing_sessions, links, messages), token hashing, Ed25519
  signature verification, auth middleware, IP and per-device rate limits, device / pairing /
  message modules (routes, service, repo), authenticated WebSocket hub, expiry job, health and
  metrics endpoints, graceful shutdown; `scripts/dev-db.sh`; `docs/API.md`; `.env.example`
- Acceptance: starts reliably; migrations run; health checks work; unauthenticated upload and
  retrieval refused; devices cannot read others' messages; invalid and oversized requests
  rejected; repeated submissions deduplicated; expired messages removed; logs contain no bodies,
  secrets or keys — all met and covered by tests
- Tests: 36 integration tests against PostgreSQL 18 (`npm test`) — PASS
- Build: `npm run typecheck`, `npm run lint`, `npm run format:check`, `npm run build` — PASS;
  manual smoke test of `node dist/server.js` (health, readiness, metrics, SIGTERM) — PASS
- Known issues: rate-limit counters are in memory (single instance); Docker is not installed on
  the development host, so container files are added and documented in Module 10 but cannot be
  executed here
- Commit: `de28e19` — `feat(server): add encrypted message relay`
- Push: SUCCESS

## Module 3 — Device identity and secure pairing

- Status: `COMPLETED` (emulator + JVM scope). **Not verified:** scanning the QR code with a real
  camera — the emulator cannot present a QR code to its camera, so on-device tests use the
  "enter pairing text" path, which feeds the same parser and protocol. To be checked on the phones.
- Created: `docs/PROTOCOL.md` (key generation, exchange, verification, encryption, signing,
  storage, rotation, replacement, revocation, recovery, versioning); `core/security/DeviceCrypto`
  (Tink HPKE X25519/HKDF-SHA256/AES-256-GCM + Ed25519), `DeviceKeyStore` (Keystore-wrapped
  keysets), `LocalCipherHolder`; `core/network/RelayApi` (OkHttp); `pairing/PairingProtocol`
  (QR payload, HMAC proof, safety code, fingerprint), `PairingManager` (both roles),
  `LinkMonitor` (reconciliation, revocation), `SecureIdentityStore`; pairing UI (QR display with
  `FLAG_SECURE`, CameraX + ZXing scanner, manual entry, destination review, code comparison),
  paired-device screen (details, status, revoke, forget locally); server migration
  `002_pairing_proof.sql` and `pairing_cancelled` error
- Acceptance: source pairs with intended companion; expired codes refused (server tests);
  replayed codes refused; unpaired device cannot download messages; revocation enforced by the
  relay; restart creates no new pairing; private keys absent from logs, QR codes and server
  records — all met
- Tests: Android JVM 50 (`CryptoTest`, `PairingProtocolTest`, 10 `PairingIntegrationTest` cases
  running both roles against a real relay incl. key-substituting relay and forged QR) — PASS;
  server 36 — PASS; on-device 17 on the Android 15 emulator (`PairingOnDeviceTest` x3 with real
  UI and Keystore against the relay, `SecureStorageTest` x4) — PASS; `emulator-sms-check.sh` — PASS
- Build: `:app:assembleDebug`, `:app:lintDebug`, server typecheck/lint/format/build — PASS
- Defects found by these tests and fixed in this module: (1) after an in-app reset the app kept
  using a handle to the deleted local key, so a pairing made before the next restart would have
  been lost at restart; (2) HTTP response bodies were read on the calling (main) thread
- Known issues: pairing status is polled every 2 s while the pairing screen is open
- Commit message: `feat(security): implement device pairing`

## Modules

| # | Module | Status |
| --- | --- | --- |
| 1 | Android foundation and application design | COMPLETED |
| 2 | SMS reception and sender permissions | COMPLETED |
| 3 | Device identity and secure pairing | COMPLETED |
| 4 | Node.js/TypeScript relay backend | COMPLETED |
| 5 | End-to-end encrypted message transport | NOT STARTED |
| 6 | Companion inbox and local storage | NOT STARTED |
| 7 | OTP detection, copying and notifications | NOT STARTED |
| 8 | Persistent connectivity and background reliability | NOT STARTED |
| 9 | Security hardening | NOT STARTED |
| 10 | Backend deployment and configuration | NOT STARTED |
| 11 | Automated testing and end-to-end validation | NOT STARTED |
| 12 | UX polish and accessibility | NOT STARTED |
| 13 | Release builds and private installation | NOT STARTED |
| 14 | Default SMS app support and remote sending | NOT STARTED |
| — | CI and quality gates | NOT STARTED |
