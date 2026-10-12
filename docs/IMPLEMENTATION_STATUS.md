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
- Commit message: `feat(android): receive, filter and queue incoming SMS`

Module order note: Module 4 (backend) is implemented before Module 3 (pairing) because pairing's
acceptance criteria need the server.

## Modules

| # | Module | Status |
| --- | --- | --- |
| 1 | Android foundation and application design | COMPLETED |
| 2 | SMS reception and sender permissions | COMPLETED |
| 3 | Device identity and secure pairing | NOT STARTED |
| 4 | Node.js/TypeScript relay backend | NOT STARTED |
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
