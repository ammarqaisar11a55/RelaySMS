# Testing and Validation

How RelaySMS is tested, how to run each layer, what the last run showed, and what still has to
be done on the real phones. All test data is synthetic: no real bank message, code or credential
is used anywhere.

## 1. Layers

| Layer | Where | Needs | Run |
| --- | --- | --- | --- |
| Relay unit + integration | `server/tests` | PostgreSQL (`scripts/dev-db.sh start`) | `cd server && npm test` |
| Android unit (JVM) | `android/app/src/test` | JDK 17 | `cd android && bash ./gradlew :app:testDebugUnitTest` |
| Android integration (JVM, both phone roles against a real relay) | same source set; skipped unless `RELAY_TEST_URL` is set | PostgreSQL, Node | `scripts/integration-test.sh` |
| Android on-device (instrumented) | `android/app/src/androidTest` | Emulator or phone, relay on the host | `scripts/device-e2e.sh` |
| Emulator scenarios (app running on its own) | `scripts/emulator-*.sh` | Emulator (uses its console and radios) | see §3 |
| Static release checks | `scripts/release-check.sh` | Android SDK | `scripts/release-check.sh` |
| Dependency and secret scans | `scripts/android-dependency-scan.sh`, `scripts/secret-scan.sh`, `npm audit` | Network | run directly |
| Everything without a device | `scripts/test-all.sh` | all of the above except an emulator | `scripts/test-all.sh` |

The "other phone" in integration, on-device and scenario tests is a simulated phone
(`src/sharedTest/.../SimulatedPhone.kt`) that runs the app's real pairing, encryption, upload and
sync code with in-memory storage. For scenarios where the app must run undisturbed it is driven
from the host by `PeerTool`.

CI (`.github/workflows/ci.yml`) runs the relay checks, the Android build, unit and integration
tests and lint, the container build with a Compose smoke test, and a secret scan on every push.

## 2. What the automated tests cover

**Relay (55 tests):** configuration validation; registration and token hashing; missing, wrong and
cross-device credentials; credential rotation and its grace period; pairing expiry, replay, wrong
secret lock-out, roles, cancellation, re-pairing; signed upload, duplicate submission before and
after acknowledgement, acknowledgement scoping, forged and redirected messages, size limits, queue
bound, paging, expiry; revocation; rate limits (per address, registration, pairing, WebSocket
upgrade, metrics); consistent errors; database failure; log redaction; WebSocket authentication,
hints, presence and revocation; startup and shutdown behaviour.

**Android JVM (109 tests):** sender matching and allowlist policy; multipart assembly and event
ids; the incoming-SMS decision logic (off, unpaired, filtered, duplicate, queue full, malformed,
storage error); HPKE and signature properties; QR payload validation, pairing proof, safety code;
envelope sealing and every rejection reason; OTP detection (33 positive and negative cases,
Urdu/Arabic digits, hostile input); inbox search, filters, sorting; connection-status wording;
notification text privacy; backoff; app-lock and window-security state; logging API shape; relay
answer handling with a scripted server (every pairing error, upload outcome, sync outcome); coarse
performance bounds (20,000-message search, 2,000 detections). With a relay: full pairing of both
roles, hostile relay and forged QR, revocation, restart, transport (lost acknowledgement, storage
failure, offline queueing over several pages, altered ciphertext, cross-pair injection), live
channel, credential rotation with a lost answer.

**Android on-device (50 tests):** onboarding and role reset; Room queue behaviour; schema migration
1→2 with data; Keystore-backed storage, including the reset regression; pairing through the real
UI in both roles; transport with real storage and UI; inbox list, filters, search, sort, detail,
copy, delete, retention, restart persistence; notifications (generic, preview, lock-screen
version, suppression); clipboard clearing; exported components, permissions, backup flag;
screenshot blocking; app lock hides messages; 1,500-message inbox; SMS handling time.

## 3. Emulator scenarios and last results

Run on 2026-10-12 on the Android 15 (API 35) `google_apis` emulator, relay on the same machine.

| Script | What it proves | Result |
| --- | --- | --- |
| `emulator-sms-check.sh` | Real `SMS_RECEIVED` handling: permission denied, allowed sender queued, unlisted sender filtered, multipart reassembled, process killed, forwarding paused, nothing sensitive in logcat | 7/7 PASS |
| `emulator-forward-check.sh` | The 12 integration steps of the plan in one run: SMS injected into the modem → detected → filtered → encrypted → relay holds only ciphertext → companion retrieves, decrypts and stores → acknowledged → not shown twice; filtered sender never forwarded; no plaintext in the relay database or app log | PASS |
| `emulator-reliability-check.sh` | Source: screen off, process killed, offline, relay stalled, device restart. Companion with background connection: screen off, offline, device restart | 15/15 PASS (see `RELIABILITY.md`) |
| `device-e2e.sh` | The instrumented suite | 50/50 PASS |

Measured on the emulator: handling one incoming SMS (parse, filter, encrypt, store, schedule) took
17 ms for the first message and a median of 24 ms (maximum 27 ms) for three-part messages, against
a platform budget of about ten seconds.

Revocation preventing later delivery (step 12 of the plan) is covered by JVM integration tests
(`after revocation the source is refused...`) and on device (`appAsSourcePairs...CanRevoke`).

## 4. Real-device validation — NOT YET PERFORMED

No physical phone has been connected to the development machine. Nothing below has been verified;
the emulator results above do not stand in for it. Tick each item on the actual phones.

Preparation: install the same APK on both phones the way you really will (file or `adb`), note the
model and Android / One UI version of each, start your relay, and use a test SMS you send
yourself before trying any bank message.

| # | Check | Source phone | S25 Ultra |
| --- | --- | --- | --- |
| 1 | App installs; if installed from a file, "Allow restricted settings" is needed before SMS permission (source) | ☐ | – |
| 2 | SMS permission: grant, and separately deny, without a crash | ☐ | – |
| 3 | Pairing by scanning the QR code with the camera; both phones show the same six digits | ☐ | ☐ |
| 4 | Incoming SMS on Telenor from an allowed sender is forwarded; same sender, text and time on the S25 | ☐ | ☐ |
| 5 | Long (multipart) SMS arrives as one message | ☐ | ☐ |
| 6 | SMS from a sender not on the list is not forwarded | ☐ | – |
| 7 | App in foreground, in background, and with the screen locked | ☐ | ☐ |
| 8 | Wi-Fi off / mobile data off on each phone, then back on: queued message arrives once | ☐ | ☐ |
| 9 | Relay stopped for a few minutes, then started: message arrives once | ☐ | ☐ |
| 10 | Restart each phone without opening the app: forwarding and (if enabled) background connection resume | ☐ | ☐ |
| 11 | Phone idle overnight (Doze): delay measured, with battery use optimised and unrestricted | ☐ | ☐ |
| 12 | Revoke from either phone: nothing further is delivered | ☐ | ☐ |
| 13 | Notification on the S25 lock screen shows no sender or text by default | – | ☐ |
| 14 | Delete a message, delete all, retention setting | – | ☐ |
| 15 | App lock with fingerprint and with PIN | – | ☐ |
| 16 | S25 without a SIM, on Wi-Fi only, receives messages | – | ☐ |
| 17 | Battery use of each app over a normal day | ☐ | ☐ |
| 18 | Second SIM in the source phone, if used | ☐ | – |

Useful commands with a phone attached:

```bash
adb devices -l
adb shell getprop ro.build.version.release; adb shell getprop ro.product.model
adb logcat -s RelaySMS            # event names only; never message content
adb shell dumpsys deviceidle force-idle   # simulate Doze; `adb shell dumpsys deviceidle unforce` to end
```

## 5. Not covered by any test

Real carrier behaviour (sender IDs, delivery timing, long codes), Samsung One UI specifics,
battery measurements, storage-full conditions on a real device (a database write failure is
simulated in unit tests instead), camera scanning of the QR code, the system biometric prompt, the
minified release build at runtime, and the container deployment with a real domain and
certificate (the image build and Compose smoke test run in CI only).
