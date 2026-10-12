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
- Commit message: `feat(android): establish app roles and navigation`

## Modules

| # | Module | Status |
| --- | --- | --- |
| 1 | Android foundation and application design | COMPLETED |
| 2 | SMS reception and sender permissions | NOT STARTED |
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
