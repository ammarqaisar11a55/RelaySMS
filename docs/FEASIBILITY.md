# Phase Zero — Feasibility Report

Date of investigation: 2026-10-12

## 1. Decision

**GO, with two device checks still open.** On Android 15 (API 35) a privately installed build of
RelaySMS can be granted `RECEIVE_SMS` and receives `SMS_RECEIVED` broadcasts for single-part and
multipart messages, including when its process is not running. Nothing found requires bypassing
an Android restriction. The two open items (section 7) need the user's physical phones and do not
block development.

## 2. Environment

| Item | Value |
| --- | --- |
| Repository | `git@github.com:ammarqaisar11a55/RelaySMS.git`, branch `main`, empty before this work |
| Layout | Monorepo: `android/`, `server/`, `infra/`, `docs/`, `scripts/` |
| Host | Linux, 12 cores, 6 GB RAM (Gradle heap is capped accordingly) |
| JDK | Temurin 17.0.20 |
| Gradle / AGP / Kotlin | 8.10.2 (wrapper) / 8.7.3 / 2.0.21 |
| Android SDK | platform `android-35`, build-tools 35.0.0, emulator image `android-35 google_apis x86_64` |
| Node.js / npm | 22.22.1 (LTS) / 9.2.0 |
| PostgreSQL | 18.6 binaries installed locally (tests start a private throwaway cluster) |
| Docker | **Not installed on this host** — Compose files cannot be executed here |
| Physical devices | **None attached over adb** during this phase |
| Existing tests / CI | None (repository was empty) |

## 3. Installation pathway

RelaySMS is intended for **private APK installation**, not Google Play. These are separate
questions:

- *Permission availability* (can Android grant the permission to this APK on this phone) is what
  this report validates.
- *Google Play eligibility* (Play's SMS/Call Log policy and default-handler declaration) is **not
  claimed and not assessed as passing**. A Play submission would need its own review.

## 4. Permission requirements and findings

`android.permission.RECEIVE_SMS` is a `dangerous` runtime permission in the SMS group and is also
*hard-restricted*: the installer must allowlist it. `adb install` and the system package installer
allowlist it (observed flag `RESTRICTION_INSTALLER_EXEMPT`).

The system dialog for the SMS group reads "Allow RelaySMS to send and view SMS messages?". Android
words the dialog per permission *group*; RelaySMS on the source phone only declares and uses
`RECEIVE_SMS` unless the user later opts into remote sending.

### Restricted settings (Android 13+, extended in Android 15)

Android 15's Enhanced Confirmation Mode treats the SMS runtime permission and the SMS role as
restricted settings for apps installed from a local or downloaded file (browser, file manager,
messaging app). Observed on the Android 15 emulator, with the restricted state simulated by setting
the `ACCESS_RESTRICTED_SETTINGS` app-op to `ignore` (the state the system applies to such installs):

1. The app's permission request is **auto-denied without showing a dialog**.
2. *Settings → Apps → RelaySMS → ⋮* offers **Allow restricted settings**.
3. After the user chooses it, the normal permission dialog appears and the grant works.

This is a documented user-controlled switch, not a bypass. Installs through `adb install` are not
restricted (app-op stayed `default`). The app must therefore detect "request returned denied with
no dialog" and show instructions for this menu instead of failing silently.

Samsung One UI additionally ships *Auto Blocker*, which blocks installing apps from unknown sources
until the user turns it off in *Settings → Security and privacy → Auto Blocker*. Not testable on
the emulator; listed as an open device check.

### Background execution

- `SMS_RECEIVED` is exempt from the implicit-broadcast ban, so a manifest receiver is started even
  when the app process is dead (verified, test T4).
- An app in the **stopped** state receives nothing. That is the state right after install until
  first launch, and after the user taps *Force stop* (verified, T5/T6). The UI must tell the user
  not to force-stop the source app.
- Receivers get roughly 10 seconds. Network delivery must be handed to WorkManager.
- OEM battery managers (Samsung "Sleeping apps", others on the source phone) can delay background
  network work. To be measured on the real phones in Module 8.

## 5. Spike and results

A minimal receiver (`SpikeSmsReceiver`) and screen (`FeasibilityActivity`) were built. The receiver
records **only** an event counter, a timestamp and the PDU part count. It never stores or logs the
sender, body or any code. Synthetic messages were injected with the emulator console.

| # | Scenario | Expected | Result |
| --- | --- | --- | --- |
| T0 | Installed, never launched (stopped state) | no event | PASS — none |
| T1 | Launched, permission not granted / revoked | no event | PASS — none |
| T2 | Permission granted, single-part SMS | 1 event, 1 part | PASS |
| T3 | Permission granted, ~310 character SMS | 1 event, 3 parts in one broadcast | PASS |
| T4 | Process killed (`am kill`), alphanumeric sender | event delivered, process restarted | PASS |
| T5 | App force-stopped | no event | PASS — none (platform behaviour) |
| T6 | Relaunched after force-stop | events resume | PASS |
| T7 | Restricted state, permission requested | auto-denied, no dialog | PASS (documented behaviour) |
| T8 | "Allow restricted settings", then grant via dialog | granted, event delivered | PASS |
| T9 | App logcat searched for message text | 0 matches | PASS |

**These are emulator results on stock Android 15.** No Telenor SIM, Samsung firmware or real
carrier message was involved.

## 6. Verification commands

```bash
cd android
bash ./gradlew :app:assembleDebug
PKG=io.github.ammarqaisar11a55.relaysms.debug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n $PKG/io.github.ammarqaisar11a55.relaysms.spike.FeasibilityActivity
adb shell pm grant $PKG android.permission.RECEIVE_SMS      # or tap the button in the app
adb emu sms send 5551001 "synthetic test message"            # emulator only
adb shell run-as $PKG cat shared_prefs/feasibility_spike.xml # counter, timestamp, part count
adb shell appops get $PKG ACCESS_RESTRICTED_SETTINGS
```

On a physical phone, send a real SMS to the SIM instead of `adb emu sms send` and read the counter
on the app's screen.

## 7. Supported versions, limitations, open blockers

- `minSdk 26` (Android 8.0), `targetSdk 35`. Verified only on API 35 emulator so far.
- **OPEN — source phone:** model and Android version are unknown. Needs the spike APK installed by
  the same route the user will really use, one real Telenor SMS, and a note of whether "Allow
  restricted settings" was required.
- **OPEN — Galaxy S25 Ultra:** One UI / Android version, Auto Blocker behaviour, and SMS-role
  eligibility (`ROLE_SMS`) for a sideloaded app are unverified.
- Dual-SIM subscription handling, carrier-specific sender IDs and long-term battery behaviour are
  unverified until real-device testing.
- RelaySMS cannot make forwarded messages appear in Google Messages unless RelaySMS itself holds
  the default SMS role and writes them to the SMS provider; that is covered by the default-SMS-app
  module and must be tested on the S25.
