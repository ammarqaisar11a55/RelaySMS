# Delivery Guarantees and Background Reliability

What RelaySMS promises about delivery, how it keeps working when the apps are not open, and what
was actually tested.

## 1. Delivery semantics

RelaySMS provides **at-least-once transport with deduplication at both ends**. It does not claim
exactly-once delivery across a network, and it does not claim instant delivery.

| Stage | What is kept | Until |
| --- | --- | --- |
| Source phone outbox (Room, encrypted) | The accepted SMS | The relay answers "accepted" or "duplicate"; else 72 h |
| Relay queue (PostgreSQL) | Ciphertext | The companion acknowledges; else `MESSAGE_TTL_HOURS` (72) |
| Companion inbox (Room, encrypted) | The message | Retention setting or deletion |

- **No duplicates in the inbox.** The message id is a hash of the SMS. The relay ignores a second
  upload of the same id, and the companion ignores a second copy, including of a message the user
  deleted (tombstones, 30 days).
- **Explicit acknowledgement.** The companion acknowledges only after the database commit. If the
  acknowledgement is lost, the message is delivered again and recognised as a duplicate.
- **Bounded queues.** Source outbox: 500 pending items; when full, new messages are not queued
  and the home screen shows how many were not forwarded. Relay: `MAX_PENDING_PER_DEVICE` (500) per
  recipient; the source keeps retrying while the relay answers "queue full".
- **Expiry.** A code that could not be delivered for 72 hours is useless; stale items are dropped
  at both the source and the relay, and the drop is counted, not silent.
- **Retries.** WorkManager with exponential backoff starting at 30 seconds (Android caps it at 5
  hours); the live connection reconnects with exponential backoff from 1 to 60 seconds with
  +/-25% jitter, and immediately when Android reports that a validated network is back.

## 2. How each phone keeps working in the background

### Source phone

Nothing has to stay running. Android starts the app's receiver when an SMS arrives, even if the
process is dead. The receiver filters, encrypts and stores the message (under ten seconds, no
network) and schedules an expedited WorkManager job with a "network connected" constraint. While
the process is alive it also tries to upload at once. WorkManager persists its jobs, so a queued
message survives a process kill and a restart of the phone.

The one state Android does not recover from: **Force stop** (or never opening the app after
install). A force-stopped app receives no SMS broadcasts until it is opened again.

### Companion phone

RelaySMS uses no push service, so there are two modes:

| Mode | How messages arrive when the app is closed | Typical delay |
| --- | --- | --- |
| **Stay connected in the background** (opt-in) | A foreground service of type `remoteMessaging` keeps an authenticated WebSocket open; a content-free hint triggers a fetch | Seconds |
| Off (default) | WorkManager periodic sync | 15 minutes or more; longer in Doze |

The foreground service is justified by the product's purpose: without it or a push service, a
login code would arrive after it has expired. It is off until the user turns it on, shows an
ongoing notification with a "Turn off" action, states its real connection state ("Connected to
your relay" / "Waiting for the relay connection"), is restarted after a reboot, and is stopped
when the pairing is removed. Its type is the one Android defines for moving text messages between
a user's devices.

### What Android can still do

- **Doze / App Standby.** After the phone has been still with the screen off for a long time,
  Android suspends network access for apps whose battery use is optimised, foreground service or
  not. Setting RelaySMS to *Unrestricted* battery use (Settings → Background delivery → Open
  battery settings) avoids most of this. The app shows the current state and opens the system
  screen; it does not ask for or change the exemption itself.
- **Vendor battery managers.** Samsung One UI: keep RelaySMS out of *Sleeping apps* and *Deep
  sleeping apps* (Settings → Battery → Background usage limits). Other vendors have equivalents.
- **Background service start limits.** Android 12+ refuses to start a foreground service from
  the background except in cases such as boot. If the service was stopped by the system, it comes
  back at the next boot or the next time the app is opened; periodic sync covers the gap.

Because of these, RelaySMS states delivery as "usually within seconds", never as guaranteed.

### Push notifications (not implemented)

A push service (for example FCM) could wake the companion without a persistent connection. It is
deliberately not included: it would add a Google Play services dependency and a third party that
learns when messages arrive. If it is added later it must stay optional and carry no message
data, only a "sync now" signal; the message would still be fetched and decrypted by the app.

## 3. What was tested

All of the following ran on the **Android 15 emulator** (stock Android, `google_apis` image)
against a real relay on the development machine, with `scripts/emulator-reliability-check.sh`.
The app ran on its own, outside any test harness; the other phone was simulated on the host with
the app's real protocol code. **No physical phone, no Samsung firmware and no real carrier were
involved.** Doze and vendor battery behaviour cannot be reproduced meaningfully on an emulator
and are not covered.

| # | Condition | Result |
| --- | --- | --- |
| S1 | Source: screen off, app in background, SMS arrives | Delivered — PASS |
| S2 | Source: process killed, SMS arrives | Delivered — PASS |
| S3 | Source: Wi-Fi and mobile data off, SMS arrives; network restored | Held while offline, delivered after — PASS |
| S4 | Source: relay process frozen for 20 s, SMS arrives; relay resumed | Delivered after recovery — PASS |
| S5 | Source: SMS queued offline, device restarted, app not opened, network restored | Delivered from the persisted queue — PASS |
| – | Source: across S1–S5 | No message delivered twice — PASS |
| C1 | Companion (background connection on): screen off, message sent | Stored and acknowledged; generic notification without message text — PASS |
| C2 | Companion: offline when the message is sent; network restored | Held on the relay, then received — PASS |
| C3 | Companion: device restarted, app not opened | Service restarted by itself; message received — PASS |

Also covered by automated tests (JVM, against a real relay): lost acknowledgement, storage failure
on the companion, relay unreachable, more than one page of queued messages, ciphertext altered in
transit, revocation while messages are queued.

## 4. Still to verify on the real phones

- Source phone: delivery with the screen locked overnight (Doze), with and without unrestricted
  battery use; behaviour with two SIMs.
- Galaxy S25 Ultra: that One UI keeps the `remoteMessaging` service alive, how long delivery takes
  after long idle, and the effect of *Sleeping apps*.
- Battery cost of the background connection over a day.
- Mobile-data to Wi-Fi handover while a message is in flight.
