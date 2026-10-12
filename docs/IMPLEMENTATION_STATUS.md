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
- Commit: `4260647` — `feat(security): implement device pairing`
- Push: SUCCESS

## Module 5 — End-to-end encrypted message transport

- Status: `COMPLETED` (emulator + JVM scope; no real carrier SMS or physical phones yet)
- Created: `transport/MessageCodec` (versioned envelope, HPKE seal/open bound to sender,
  recipient and message id, Ed25519 signature, strict validation), `RelayOutboxTransport`
  (source upload), `RelaySync` (fetch, verify, decrypt, store, then acknowledge), `RelaySocket`
  (authenticated WebSocket hints, exponential backoff with jitter), `EnvelopeRouter`;
  `inbox/InboxRepository` with field-level encryption; Room v2 (`messages`) with
  `MIGRATION_1_2`; `SyncWorker` (expedited one-time + 15-minute periodic); immediate delivery
  attempt while the process is alive in addition to WorkManager; foreground lifecycle hook
- Delivery policy: the source keeps an item until the relay accepts it, then keeps a content-free
  tombstone for 7 days; the relay keeps ciphertext until acknowledged or 72 h; the companion
  acknowledges only after the database commit; unverifiable messages are dropped and counted
- Acceptance: controlled SMS reaches the companion inbox with the same sender, body and
  timestamp; ciphertext only on and through the relay; transient disconnection retries instead
  of losing; repeated delivery creates no duplicate entry — all met
- Tests: Android JVM 70 (adds `MessageCodecTest` x9, `TransportIntegrationTest` x10 against a
  real relay: lost acknowledgement, storage failure, offline queueing across pages, unreachable
  relay, revocation, relay-altered ciphertext, cross-pair injection) — PASS; on-device 28
  (`MigrationTest`, `InboxRepositoryTest` x7, `TransportOnDeviceTest` incl. live WebSocket hint)
  — PASS; `scripts/emulator-forward-check.sh` (three SMS injected into the emulator modem, two
  allowed ones arrive decrypted at the companion, filtered one does not, no plaintext in relay
  database or app log) — PASS; `emulator-sms-check.sh` — PASS
- Build: `:app:assembleDebug`, `:app:lintDebug` — PASS
- Defects found by these tests and fixed in this module: (1) a link check triggered by the
  relay's own "link changed" hint could run between link creation and the phone storing its peer
  and revoke the brand-new pairing; (2) the same check compared the relay's answer with a peer
  snapshot taken before the request; both now tolerate an overlapping pairing
- Known issues: the companion home shows a plain list until Module 6; the live connection is
  held only while the app is in the foreground until Module 8
- Commit: `ce25a9c` — `feat(transport): deliver end-to-end encrypted messages`
- Push: SUCCESS

## Module 6 — Companion inbox and local storage

- Status: `COMPLETED` (emulator scope)
- Created: `feature/inbox/InboxScreen` (list with sender, preview, time, unread dot, code badge,
  connection and sync bar, search, All/Unread/Codes filters, sort, pull to refresh, loading /
  empty / no-match / storage-error states), `MessageDetailScreen` (full text, sender, times,
  code card with copy, copy whole message, delete, delivery details), `InboxViewModel`,
  `inbox/InboxQuery`, `ConnectionSummaries` (status never claims a connection that is not open),
  `otp/OtpDetector` (first version, on-device only), `SecureClipboard`, `ClipboardClearer`;
  retention (7/30/90/365 days or keep) with tombstones; settings rewritten per role (connection,
  paired device, forwarding and filters, retention, delete all, relay server, privacy, reset);
  `docs/DATA_RETENTION.md`
- Acceptance: received message appears; inbox survives restart; search and sorting work; no
  duplicates; expired and deleted messages follow the documented behaviour; migration preserves
  data (v1→v2 `MigrationTest`); usable in light and dark themes (theme switch covered by
  `OnboardingFlowTest`; visual check of both themes is part of Module 12) — met
- Tests: Android JVM 88 (adds `OtpDetectorTest` x12, `InboxQueryTest` x6) — PASS;
  on-device 36 (adds `InboxScreenTest` x8: list, filters, search, sort, open/read/copy,
  delete, restart persistence, retention + delete all) — PASS
- Build: `:app:assembleDebug`, `:app:lintDebug` — PASS
- Defect found and fixed: navigation after deleting a message could run off the main thread
- Known issues: search decrypts and filters in memory, which is fine for a personal inbox
  bounded by retention but is not designed for hundreds of thousands of messages
- Commit: `21b2de4` — `feat(inbox): implement companion message storage`
- Push: SUCCESS

## Module 10 — Backend deployment and configuration

- Status: `COMPLETED`. **Not verified:** the reverse proxy with real traffic and a real
  certificate, and any deployment on a public host. Docker is not installed on the development
  host; the image, the Compose stack (database + relay) and the Caddyfile syntax were checked by
  CI on GitHub instead. `docs/DEPLOYMENT.md` §9 lists exactly what ran where.
- Created: `server/Dockerfile` (multi-stage, production dependencies only, `USER node`,
  `HEALTHCHECK`), `server/.dockerignore`, `server/src/healthcheck.ts`, `infra/compose.yaml`
  (PostgreSQL 17 with a named volume on an internal network, relay with read-only file system and
  dropped capabilities, Caddy; log rotation 5 x 10 MB per container; stop grace periods),
  `infra/reverse-proxy/Caddyfile` (automatic TLS, WebSocket pass-through, `/metrics` not
  published, body-size bound, no access log), `scripts/relay-smoke.mjs` (round-trip check of a
  running relay), `docs/DEPLOYMENT.md` (deploy, verify, operate, upgrade, roll back, backup and
  restore, configuration reference, observable metadata, running without Docker);
  `.env.example` extended with `RELAY_DOMAIN` and `POSTGRES_PASSWORD`
- Acceptance: start with documented commands, apply migrations, check health, verify message
  delivery — met locally for the non-Docker path and on CI for `docker compose up` (database and
  relay; not the proxy). Configuration errors reported clearly without printing credentials —
  met, tested at process level
- Tests: server 42 at this commit (`npm test`, adds `startup.test.ts` x6: exit 78 naming only
  the bad variables, no password printed when the database is unreachable, clean SIGTERM exit,
  health probe up / down / database down) — PASS; `scripts/relay-smoke.mjs` against a relay
  started from an image-layout directory with production dependencies only — 8/8 steps PASS, and
  correctly refused without the registration code; `pg_dump -Fc` / `pg_restore --clean
  --if-exists --no-owner` round trip on PostgreSQL 18.6 — PASS; CI job `deployment-files` — PASS
  on GitHub for `cf28179` and `fc3c97d`
- Build: `npm run typecheck`, `lint`, `format:check`, `build` — PASS
- Known issues: Caddy has only been syntax-checked (`caddy validate` in CI), never run with a
  domain; no real deployment exists
- Commit: `cace14b` — `feat(deploy): add self-hosted relay deployment`
- Push: SUCCESS

## CI and quality gates

- Status: `COMPLETED`. All four jobs were observed passing on GitHub for commit `fc3c97d` (run
  38265649613). The run for `cf28179` was cancelled by the next push after its `server`,
  `deployment-files` and `secrets` jobs had passed.
- Created: `.github/workflows/ci.yml` with four jobs — `server` (`npm ci`, typecheck, lint,
  format check, tests against a PostgreSQL 17 service container, build, migrations applied twice
  to an empty database with the built CLI, `npm audit --omit=dev --audit-level=high`); `android`
  (JDK 17, starts a relay, `assembleDebug`, `testDebugUnitTest` with the relay integration tests
  enabled, `lintDebug`, reports uploaded on failure); `deployment-files` (Compose and Caddyfile
  validation, image build, database + relay started, smoke test, backup/restore, clean stop);
  `secrets` (`scripts/secret-scan.sh` and gitleaks over the full history). `scripts/secret-scan.sh`,
  `.gitleaks.toml`. The workflow uses no repository secrets, signing keys or SSH keys, has
  read-only permissions, publishes no APK and deploys nothing.
- Verified locally before pushing: workflow parses as YAML; server commands PASS; the migration
  step's commands PASS; `npm audit --omit=dev` — 0 vulnerabilities; the three Gradle commands
  PASS on a clean export of commit `cace14b` with a relay running (`assembleDebug`;
  `testDebugUnitTest` 88 tests, 0 skipped; `lintDebug`); `scripts/secret-scan.sh` — no findings,
  and it flags a planted keystore, private key, device token and database URL in a scratch
  repository
- Observed on GitHub: job and step conclusions read through the public Actions API
- Known issues: results were read from the API's job and step conclusions, not from full logs;
  instrumented (emulator) tests are not part of CI
- Commit: `cf28179` — `ci: add build and test workflow` (this status entry was added by the
  following commit)
- Push: SUCCESS

## Module 7 — OTP detection, copying and notifications

- Status: `COMPLETED` (emulator scope)
- Created: `notifications/MessageNotifier` + `NotificationText` (local notifications built on the
  device after the message is already decrypted; generic "New message received" by default,
  opt-in preview, `VISIBILITY_PRIVATE` with a generic public version for the lock screen,
  suppressed while the app is open, cleared when the app is opened, tap opens the message);
  notification settings (enable, preview, permission prompt and App-info fallback);
  clipboard auto-clear setting (off / 30 s / 1 / 2 / 5 min) with the platform limits explained;
  `ClipboardClearer` clears only if the clipboard still holds the copied value;
  `SecureClipboard` flags copied text as sensitive; OTP detector from Module 6 with further cases
- Not built, deliberately: no automatic copying, no auto-fill or submission of codes, no
  accessibility service, no push service (so no message content can reach one)
- Acceptance: detection on-device; relay never receives an extracted code (the detector has no
  network access and runs only on decrypted local text); copying works; previews follow the
  privacy setting; disabling notifications does not stop persistence; messages without a code
  show no code card — met
- Tests: Android JVM 92 (adds `NotificationTextTest` x4 incl. more OTP edge cases) — PASS with
  relay integration; on-device 42 (adds `NotificationAndClipboardTest` x6: generic content,
  preview vs lock-screen version, disabled / foreground suppression, storage with notifications
  off, timed clipboard clearing that leaves later copies alone, sensitive flag) — PASS
- Build: `:app:assembleDebug`, `:app:lintDebug` — PASS
- Known issues: lock-screen behaviour was checked through the notification's visibility and
  public version, not by looking at a locked physical screen; Samsung One UI rendering untested
- Commit: `fc3c97d` — `feat(notifications): add private message notifications and clipboard clearing`
- Push: SUCCESS

## Module 9 — Security hardening

- Status: `COMPLETED` (relay part in `d1a3b7e`, Android part in the commit named below). No
  unresolved high-severity issue is known; open medium and low items are in `THREAT_MODEL.md` §5
- Created (Android part): `security/AppLock` + `LockScreen` (opt-in, phone's own biometric or
  screen lock, nothing composed while locked), `WindowSecurity` (screenshots blocked by default,
  always on pairing screens), `pairing/CredentialRotator` (30-day and on-demand renewal), security
  settings; release build type (R8, resource shrinking, signing from an untracked
  `keystore.properties`) and `proguard-rules.pro`; `scripts/release-check.sh`;
  `scripts/android-dependency-scan.sh`; relay migration `003_token_rotation_grace.sql` so the
  replaced token works until the new one is first used (at most 24 h) — the first rotation design
  could lock a device out if the relay's answer was lost
- Tests (Android part): Android JVM 100 with relay (adds `SecurityHardeningTest` x6: rotation
  keeps the pairing working, lost-answer rotation does not lock out, due/not-due, app lock state,
  window protection, logging API and no stray log calls) — PASS; relay 55 (adds 3 rotation grace
  tests) — PASS; on-device 48 (adds `SecurityOnDeviceTest` x4: exported components, permissions
  and backup, screenshot flag, lock hides messages) — PASS on rerun; one earlier full run had a
  10 s UI wait time out in `OnboardingFlowTest`, which passed alone and on the next full run (wait
  raised to 30 s); `release-check.sh` on the minified release APK — PASS;
  `android-dependency-scan.sh` (OSV, 147 libraries) — no known vulnerabilities
- Not verified: the biometric/credential prompt itself (the emulator has no screen lock, so the
  switch is disabled there and the unlock was simulated in the test); runtime behaviour of the
  minified release build
- Created (relay part): `docs/THREAT_MODEL.md` (13 threats incl. every one named in the plan,
  controls, residual risks, non-goals, open issues, review record);
  `server/src/middleware/upgradeLimit.ts`; `server/tests/hardening.test.ts`
- Review findings fixed, each with a test: (1) WebSocket upgrades bypassed rate limiting — now
  limited per client address before the credential check; (2) `/metrics` was exempt from rate
  limiting — now limited (and not published by the proxy); (3) responses carrying credentials
  had no cache directive — every response is now `Cache-Control: no-store`; (4) records of
  revoked devices were kept forever — now deleted one day after revocation
- Dependency check: `npm audit --omit=dev` — 0 vulnerabilities before and after. Full
  `npm audit` first reported 6 (3 moderate, 1 high, 2 critical), all in the development-only
  test runner chain; vitest upgraded 2.1.9 → 5.0.4; `npm audit` now reports 0. Android
  dependencies were not scanned
- Tests: server 53 (`npm test` on vitest 5.0.4; adds `hardening.test.ts` x11: security headers,
  X-Forwarded-For spoofing with and without a trusted proxy, `/metrics` limit, client-address
  derivation, attempt limiter, hostile JSON and identifiers, revoked-device purge, upgrade rate
  limit, no credential in URL, oversized frame and token rotation closing sockets) — PASS
- Build: `npm run typecheck`, `lint`, `format:check`, `build` — PASS
- Known issues: no unresolved high-severity issue known; open medium/low items are listed in
  `THREAT_MODEL.md` §5 (no app lock, no automatic token rotation, no forward secrecy, unpaired
  device records not garbage-collected). No penetration test, fuzzing or second-person review
- Commit (relay part): `d1a3b7e` — `feat(security): harden relay and add threat model`
- Commit (Android part): `9174421` — `feat(security): add app lock, credential renewal and release checks`
- Push: SUCCESS; CI on GitHub passed for `d1a3b7e`, `031710e` and `9174421` (job conclusions read from the Actions API)

## Module 8 — Persistent connectivity and background reliability

- Status: `COMPLETED` (emulator scope; Doze and vendor battery managers not testable on an emulator)
- Created: `background/NetworkMonitor` (validated-network callback: reconnect at once and flush
  both directions), `ConnectionService` (opt-in foreground service, type `remoteMessaging`,
  truthful ongoing notification, "Turn off" action, sticky), `BootReceiver` (restart and app
  update recovery), `BatteryOptimization` (state and link to the system screen; no exemption is
  requested by the app), `RelaySocket.reconnectNow`, settings section "Background delivery",
  inbox hint when the background connection is off; `docs/RELIABILITY.md` (delivery semantics,
  what Android can still do, tested conditions, push considered and not included);
  test tooling: persistable simulated phone, host-side `PeerTool`, `ReliabilityScenario` setup
  steps, `scripts/emulator-reliability-check.sh`, Gradle task `writeUnitTestClasspath`
- Foreground service justification: no push service is used, so without a held connection a code
  would reach the companion only at the next periodic sync (15 minutes or more)
- Acceptance: tested with sender screen off, companion screen off, temporary disconnection,
  process termination and device restart; constraints recorded — met on the emulator
- Tests: `emulator-reliability-check.sh` — source 7 checks, companion 8 checks — PASS (table in
  `RELIABILITY.md`); Android JVM 94 with relay (adds live-channel connect/hint/reconnect and
  restored-phone tests) — PASS; on-device 44 — PASS; relay 53 (`npm test`, includes the hardening
  tests added in `d1a3b7e`, re-run here) — PASS
- Build: `:app:assembleDebug`, `:app:lintDebug` — PASS
- Known issues: with the background connection off, a closed companion app receives only at
  Android's periodic sync; that path was not timed; real-device Doze behaviour is unverified
- Commit: `031710e` — `feat(background): keep delivery working in the background`
- Push: SUCCESS

## Module 11 — Automated testing and end-to-end validation

- Status: `COMPLETED` for everything that can run without physical phones. **Real-device
  validation: NOT PERFORMED** — no phone was attached; the checklist is in `TESTING.md` §4
- Created: `docs/TESTING.md` (layers, commands, coverage, last results, real-device checklist,
  what no test covers); `PairingFailureTest` (scripted relay: every pairing error, upload and
  sync outcome, API client failures, runaway-paging guard); `PerformanceTest`;
  `PerformanceOnDeviceTest` (1,500-message inbox, SMS handling time); `scripts/test-all.sh`
- Acceptance: unit, backend and integration coverage listed in the plan exists and passes; the
  twelve integration steps pass on the emulator; emulator results are kept separate from
  real-device results, of which there are none
- Tests: relay 55 — PASS; Android JVM 109 with relay — PASS; on-device 50 — PASS;
  `emulator-sms-check.sh` 7/7, `emulator-forward-check.sh`, `emulator-reliability-check.sh` 15/15
  — PASS; secret scan — no findings; SMS handling measured at 17–27 ms on the emulator
- Build: `:app:assembleDebug`, `:app:lintDebug` — PASS
- Known issues: battery impact and low-storage behaviour on real hardware are unmeasured
- Commit: `a85ef01` — `test(integration): verify end-to-end message delivery`
- Push: SUCCESS

## Module 12 — UX polish and accessibility

- Status: `COMPLETED` (emulator scope; no TalkBack session and no real-device check)
- Changed: "No internet connection" state driven by the network monitor (inbox bar and source
  home), separate from "Relay not reachable"; source home "Delivery" section (waiting, sent,
  on the relay awaiting the companion, expired, refused); connection bar on two lines so its
  detail is never cut off; system bar icons follow the app theme, not the system theme;
  `ScreenshotScenario` + `scripts/screenshots.sh` for visual review
- UX states of the plan and where they are shown: first launch, role selection (onboarding);
  permission explanation, permission denied incl. restricted-settings guidance (source home);
  waiting to pair, pairing in progress, success, expired, cancelled, failed (pairing screen);
  connected, connecting, synchronizing, relay not reachable, no internet, not paired, device
  revoked (inbox bar, source home, paired-device screen); pending delivery, delivery failure,
  message expired on the source (source home "Delivery"); no messages, nothing matches, message
  received, message no longer available, local storage error (inbox and detail). "Connected" is
  shown only while an authenticated connection is open (unit tested)
- Accessibility: every text/background pair in both themes meets WCAG AA 4.5:1
  (`AccessibilityTest`); interactive rows are at least 48 dp; icons have content descriptions or
  are marked decorative; status changes are live regions; selections use radio/switch semantics;
  layout verified at font scale 1.3
- Visual check: 15 screenshots (onboarding, inbox, detail, settings, source home, unpaired,
  large text; light and dark) captured on the emulator and reviewed; three defects found and
  fixed (invisible status-bar icons under a forced dark theme, truncated connection detail,
  capture of onboarding)
- Tests: Android JVM 110 — PASS; on-device 51 — PASS
- Build: `:app:assembleDebug` — PASS
- Known issues: English only, strings in code; not tested with TalkBack or Switch Access; pairing
  screens cannot be screenshotted by design
- Commit: `19d43f2` — `feat(ui): complete status states and accessibility checks`
- Push: SUCCESS

## Additional requirement — Vercel deployment on a shared PostgreSQL database

- Status: `COMPLETED` — deployed and verified live on 2026-10-13 (see "Open items" below for
  what was checked). `DATABASE_URL` was added by the owner; `CRON_SECRET` was generated on the
  development machine and stored without being displayed
- Created: `server/src/vercel.ts` + `server/api/index.js` + `server/vercel.json` (serverless
  entry, migrations in the build step, daily clean-up cron), `GET /v1/info`,
  `/internal/cron/expire`, poll mode (`REALTIME=poll`) with activity-based presence,
  libpq-style `sslmode` handling, `DATABASE_SCHEMA` (default `relaysms`; `public` refused),
  migrations `004` (row level security) and `005` (tables renamed `relay_*`), Android poll mode
  in `RelaySocket` with "Checking for messages" status, `docs/VERCEL.md`
- Shared-database safety: the target database was inspected read-only and already holds other
  applications (a `public` schema with 14 tables including `devices`, and a `pwma` schema).
  RelaySMS creates and uses only its own schema, verifies it is inside that schema before any
  DDL, and uses table names that exist nowhere else. **Nothing has been written to that database
  by this work**; the first deployment's build step will create the schema
- Vercel: project `relaysms` created in the owner's team with the CLI (the Vercel connector
  returned 403 for project creation), GitHub repository connected, root directory `server`,
  Node 22. No environment variables set, no deployment made
- Tests: relay 62 (adds `serverless.test.ts` x7: isolation next to another app's
  `public.devices` and `public.messages`, schema refusal, sslmode parsing, info, presence, cron
  secret, serverless entry point) — PASS; Android JVM 112 against the relay in WebSocket mode and
  again in poll mode — PASS
- Not verified: anything on Vercel itself, and the connection to the Supabase pooler (TLS and
  session settings), which can only be checked once the secret is in place
- CI: the run for `28cd053` failed in the secret scan only, on a made-up cron secret in the new
  test file (confirmed locally as the single finding); the value was replaced and the historical
  string allowlisted in the next commit
- Commit: `28cd053` — `feat(server): support Vercel and shared PostgreSQL databases`; Push: SUCCESS

## Module 13 — Release builds and private installation

- Status: `COMPLETED`; pre-release `v0.9.0` was published by the release workflow on 2026-10-13
  (details under "Open items")
- Created: version from Gradle properties (`relaysmsVersionName`, `relaysmsVersionCode`);
  `staging` build type (the minified release build, allowed to reach the local relay, debug
  signed, never distributed); `scripts/release-smoke.sh`; `PeerTool pair-source`;
  `.github/workflows/release.yml` (tag `vX.Y.Z` or manual run: unit tests, signed
  `assembleRelease`, signature and release checks, GitHub release with APK and SHA-256);
  `scripts/create-release-keystore.sh`; `docs/INSTALL.md` (install on both phones, restricted
  settings, Samsung Auto Blocker, pairing, upgrade, rollback, signing key, removal); full
  `README.md`
- Final checklist: debug build — PASS; release build (R8) — PASS; database migrations — PASS
  (`MigrationTest`, relay migrations 001–005); tests — PASS; no hardcoded production secrets —
  PASS (secret scan over history: no findings); message contents absent from logs — PASS;
  pairing and revocation — PASS; offline retry — PASS; deduplication — PASS; companion needs no
  SIM — by design, no telephony call on the companion path, not checked on a SIM-less phone;
  sender requires SMS permission — PASS; limitations documented — yes
- Tests: `release-check.sh` on the unsigned R8 release APK — PASS; `release-smoke.sh` on the
  emulator with the minified build (start, onboarding, key generation, registration, pairing
  with matching codes, encrypted message received and stored, restart persistence, code
  detection) — PASS; Android JVM 112 — PASS; lint — PASS
- Not verified: a signed release build (no signing key exists yet), the release workflow run,
  installation from a file on a physical phone
- Known issues: none known in the build; see `INSTALL.md` §8
- Commit: `5950510` — `feat(release): add release build, GitHub release workflow and install guide`
- Push: SUCCESS

## Module 14 — Default SMS app support and remote sending

- Status: `COMPLETED` on the emulator. **Not verified on the Galaxy S25 Ultra**, which the
  requirement names: role eligibility for a file-installed app, Samsung firmware behaviour and
  interoperability with Samsung/Google Messages are open
- Created: `sms/defaultapp/DefaultSmsRole` (RoleManager, legacy fallback, settings intent),
  `SmsDeliverReceiver`, `MmsDeliverReceiver` (notice only; MMS is not supported),
  `RespondViaMessageService`, `ACTION_SENDTO` handling, `ProviderMirror` +
  `SmsProviderGateway` (one system row per message, read/delete/retention follow, backfill when
  the role is gained, nothing written without the role); `sms/send/*`: `RemoteSendPolicy`,
  `SendRequestHandler` (durable replay protection, hourly limit, expiry), `AndroidSmsSender` +
  `SmsOutcomeReceiver` (sent and delivered reported separately), `OutgoingMessages`,
  envelope kinds `send_request` / `send_result`; Room v3 (`send_requests`) with `MIGRATION_2_3`;
  compose screen with route choice and confirmation; outgoing status in inbox and detail;
  settings: "Default SMS application" (companion) and "Sending from the companion" (source);
  `docs/DEFAULT_SMS.md`; protocol §5.5; threat model T14, T15
- Acceptance: role requested through the supported API; approve/decline handled; role state
  detected; handler components implemented; forwarded messages stored without duplicates;
  companion inbox works without a SIM; compose behaves correctly without cellular service;
  authorised remote sending works when the source is online and permitted; unauthorised and
  replayed requests rejected; revocation prevents further sending; switching away does not
  corrupt messages — met on the emulator, except that Android's role dialog itself was not
  tapped through (the role was granted with `adb shell cmd role`)
- Tests: Android JVM 129 with relay (adds `RemoteSendTest` x11, `RemoteSendIntegrationTest` x6)
  — PASS; on-device 61 (adds `DefaultSmsOnDeviceTest` x9, migration to v3, updated component and
  permission audit) — PASS; `emulator-default-sms-check.sh` 12/12 — PASS (real system SMS store,
  real `SMS_DELIVER`, real `SmsManager` send as source); `emulator-forward-check.sh` — PASS;
  `release-check.sh` — PASS; lint — PASS
- Defect found and fixed: catching up the system SMS store after gaining the role ran only from
  the app container, not from the repository's housekeeping
- Known issues: no MMS; the default SIM is always used for sending; "Sent" cannot distinguish
  carrier refusal reasons; Google Play SMS and default-handler policy not assessed
- Commit: `5d09929` — `feat(sms): add default SMS app support and sending through the source phone`
- Push: SUCCESS; CI on GitHub passed for the following commit `9523757`, which contains this module

## Open items that need the owner (as of 2026-10-13)

| Item | State | What is needed |
| --- | --- | --- |
| Relay on Vercel | **Working** at `https://relaysms-theta.vercel.app` since 2026-10-13. Checked live: `/healthz` and `/readyz` 200, `/v1/info` reports poll mode, unauthenticated requests get 401, `scripts/relay-smoke.mjs` passed all 8 steps (register, pair, signed upload, duplicate, delivery, acknowledgement, device deletion). The build applied migrations 001–005. `/metrics` (aggregate counts only) is publicly reachable on Vercel; the scheduled clean-up has not run yet | Nothing. The team-scoped `…-projects.vercel.app` addresses are behind Vercel's login; phones must use the `relaysms-theta.vercel.app` address |
| Supabase database | Schema `relaysms` created with 5 tables, row level security on all of them. `public` still has 14 tables and `pwma` 17, as before; `public.devices` is unchanged | Nothing |
| GitHub release | **Published** on 2026-10-13: pre-release `v0.9.0` with `RelaySMS-0.9.0.apk` (3.2 MB) and its SHA-256 file. The Release workflow succeeded on its second attempt, after the four signing secrets were added. The downloaded APK matches its checksum, is signed by the release key (certificate SHA-256 `ffad97dd…601596`), version code 900, not debuggable. Not yet installed on any phone | Keep `android/relaysms-release.jks` and `android/keystore.properties` backed up; every later version must be signed with the same key |
| Real-device validation | **Not performed.** No phone was attached at any point | Work through `TESTING.md` §4 on the source phone and the Galaxy S25 Ultra |

## Modules

| # | Module | Status |
| --- | --- | --- |
| 1 | Android foundation and application design | COMPLETED |
| 2 | SMS reception and sender permissions | COMPLETED |
| 3 | Device identity and secure pairing | COMPLETED |
| 4 | Node.js/TypeScript relay backend | COMPLETED |
| 5 | End-to-end encrypted message transport | COMPLETED |
| 6 | Companion inbox and local storage | COMPLETED |
| 7 | OTP detection, copying and notifications | COMPLETED |
| 8 | Persistent connectivity and background reliability | COMPLETED |
| 9 | Security hardening | COMPLETED |
| 10 | Backend deployment and configuration | COMPLETED (container files not executed: no Docker on dev host) |
| 11 | Automated testing and end-to-end validation | COMPLETED (emulator and JVM; real devices not tested) |
| 12 | UX polish and accessibility | COMPLETED |
| 13 | Release builds and private installation | COMPLETED (v0.9.0 pre-release published) |
| 14 | Default SMS app support and remote sending | COMPLETED (emulator; S25 Ultra not tested) |
| — | CI and quality gates | COMPLETED |
