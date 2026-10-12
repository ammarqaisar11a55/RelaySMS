# RelaySMS

A private companion SMS app. One Android phone keeps your SIM and forwards a copy of the incoming
text messages you choose, end-to-end encrypted, to a second Android phone you own. The second phone
shows them in RelaySMS's own inbox and needs only an internet connection, not a SIM.

It was built for one situation: the SIM stays in a phone that is allowed on the mobile network,
and login and banking codes need to be readable on another phone.

## What it does

- **Source phone** (has the SIM): notices new SMS, applies your sender list, encrypts each
  message for the paired phone, queues it and uploads it. Retries until delivered or 72 hours old.
- **Companion phone**: fetches, verifies, decrypts and stores messages; inbox with search and
  filters; highlights likely verification codes for a one-tap copy; notifications that reveal
  nothing by default.
- **Relay server** (you host it): passes ciphertext between the two phones. It never sees message
  text, sender addresses or codes.
- **Pairing** by QR code, with a six-digit comparison code on both screens. Either phone can
  revoke the other at any time.

## What it does not do

- It does not replace your SIM, register a phone with PTA, or bypass any network or Android
  restriction.
- By default it does not put messages into Google Messages or the system SMS store; forwarded
  messages appear inside RelaySMS. Only if you choose to make RelaySMS the default SMS app are
  copies written to Android's SMS store (see [docs/DEFAULT_SMS.md](docs/DEFAULT_SMS.md)).
- It does not send anything on its own. Sending through the source phone is an optional feature
  that is off until the source phone's owner turns it on, and each message is confirmed by you.
- It does not enter codes into other apps, read your existing SMS history, or handle MMS.
- It does not guarantee instant delivery. Both phones need internet access, and Android decides
  when background apps may run (see [docs/RELIABILITY.md](docs/RELIABILITY.md)).
- It is not on Google Play and makes no claim of Play approval. It is installed from an APK.

## How it fits together

```
 Source phone (SIM)              Relay (your server)             Companion phone
 SMS -> filter -> encrypt  --->  stores ciphertext only   --->   verify -> decrypt -> inbox
        (HPKE + signature)       until acknowledged              (encrypted at rest)
```

One Android app, two roles chosen at first launch. Kotlin, Jetpack Compose, Room, WorkManager,
Google Tink. The relay is Node.js/TypeScript with PostgreSQL and runs either as a container
(WebSocket hints) or on Vercel (polling).

| Path | Contents |
| --- | --- |
| `android/` | The app (`app/src/main`), JVM tests (`src/test`), device tests (`src/androidTest`) |
| `server/` | The relay: `src/`, SQL `migrations/`, tests, `Dockerfile`, `vercel.json` |
| `infra/` | Docker Compose and Caddy configuration |
| `scripts/` | Development database, test runners, emulator scenarios, release checks |
| `docs/` | Design, security, operations and testing documents |

## Getting started

1. **Host a relay.** Container: [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md). Vercel with a managed
   PostgreSQL database (can be shared with other apps): [docs/VERCEL.md](docs/VERCEL.md).
2. **Install the app on both phones**, grant what each role needs, and pair:
   [docs/INSTALL.md](docs/INSTALL.md).
3. On the source phone choose which senders to forward, then turn forwarding on.

### Permissions

| Permission | Which phone | When | Why |
| --- | --- | --- | --- |
| Receive SMS | Source | Asked after you choose the source role, with an explanation | To notice new messages |
| Camera | Source | When you scan the pairing code | To read the QR code (or type the pairing text instead) |
| Notifications | Companion | When you turn notifications on | To announce new messages |
| Internet, network state, boot, foreground service | Both | Granted at install | Delivery, retry after restart, optional background connection |

| Send SMS | Source | Only if you turn on "Let the companion send SMS through this phone" | Optional remote sending |
| SMS read/send/MMS | Companion | Only if you make RelaySMS the default SMS app; Android grants them with the role | Required of a default SMS app |

In normal use the companion phone is never asked for SMS access. If Android silently refuses the SMS permission
on the source phone, the app was installed from a file and needs *App info → ⋮ → Allow restricted
settings* first; the app explains this when it happens.

### Sender filtering

Forwarding is off until you turn it on. By default only senders on your list are forwarded; add
numbers, short codes or sender names exactly as they appear ("8558", "HBL"). Senders seen since
setup are offered as suggestions. You can switch to forwarding everything.

## Security in brief

- Messages are encrypted on the source phone with HPKE (X25519, HKDF-SHA256, AES-256-GCM) and
  signed with Ed25519; only the paired companion can open them. Details:
  [docs/PROTOCOL.md](docs/PROTOCOL.md).
- Private keys stay on each phone, wrapped by Android Keystore. Stored messages are encrypted at
  rest. Nothing is backed up to the cloud.
- The relay operator can see that two devices are paired, when they connect, and the size and
  time of each message. Not the content.
- Limits you should know: a phone that is unlocked in someone else's hands can show the inbox
  unless you turn on the app lock; there is no forward secrecy; the relay must be reachable over
  HTTPS. Full list: [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md).

## Building and testing

```bash
# Relay
scripts/dev-db.sh start                      # private throwaway PostgreSQL, no root needed
cd server && npm ci && npm test && npm run build

# App
cd android && bash ./gradlew :app:assembleDebug :app:testDebugUnitTest

# Everything that needs no device (relay checks, app unit + integration tests against a real
# relay, lint, release build checks, secret scan)
scripts/test-all.sh
```

Device and emulator checks, what they cover and their latest results are in
[docs/TESTING.md](docs/TESTING.md). Releases are built and published by
`.github/workflows/release.yml` when a tag `vX.Y.Z` is pushed.

## Status and verification

Module-by-module status, test results and commit hashes: [docs/IMPLEMENTATION_STATUS.md](docs/IMPLEMENTATION_STATUS.md).
Everything so far was verified on an Android 15 emulator and in automated tests. **It has not yet
been run on physical phones or with a real carrier**; the checklist for that is in
[docs/TESTING.md](docs/TESTING.md) §4.

## Troubleshooting

| Symptom | Likely cause and fix |
| --- | --- |
| SMS permission prompt never appears | Restricted settings for file installs: *App info → ⋮ → Allow restricted settings*, then try again |
| Nothing is forwarded | Forwarding is off, the sender is not on the list, or the phones are not paired. The source home screen shows each of these |
| Forwarding stopped after "Force stop" | Android delivers no SMS to a force-stopped app. Open RelaySMS once |
| "Relay not reachable" | Wrong address, relay down, or no HTTPS. Check `https://<relay>/readyz` in a browser |
| "No internet connection" | The phone itself is offline; messages wait and are sent later |
| Messages arrive late on the companion | Turn on *Stay connected in the background* and set battery use to *Unrestricted* (Settings → Background delivery) |
| "Pairing was removed" | The other phone or the relay revoked it. Pair again |
| Pairing fails with "registration code" | Your relay requires its `REGISTRATION_CODE`; enter it on the pairing screen |
| After reinstalling, the inbox is empty | Expected: keys and messages are never backed up. Pair again |

## Documents

[Feasibility](docs/FEASIBILITY.md) · [Protocol](docs/PROTOCOL.md) · [Relay API](docs/API.md) ·
[Threat model](docs/THREAT_MODEL.md) · [Reliability](docs/RELIABILITY.md) ·
[Data retention](docs/DATA_RETENTION.md) · [Deployment](docs/DEPLOYMENT.md) ·
[Vercel](docs/VERCEL.md) · [Default SMS app and sending](docs/DEFAULT_SMS.md) · [Install](docs/INSTALL.md) · [Testing](docs/TESTING.md)
