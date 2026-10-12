# Default SMS App and Sending Through the Source Phone

Two optional features, both off until you turn them on. RelaySMS works as a forwarded-message
inbox without either.

## 1. Making RelaySMS the default SMS app (companion phone)

*Settings → Default SMS application → Set as default SMS app.* RelaySMS explains the effects, then
opens Android's own confirmation dialog (`RoleManager.createRequestRoleIntent(ROLE_SMS)`; on
Android 9 and older the legacy "change default" intent). RelaySMS never changes the default
silently and reports only what Android says afterwards: if you decline, or Android refuses, the
status stays "No".

What changes while it is the default:

| | Not default | Default |
| --- | --- | --- |
| Forwarded messages | RelaySMS inbox only (encrypted at rest) | RelaySMS inbox **and** a copy in Android's SMS store |
| SMS to this phone's own SIM | Go to your other SMS app | Come to RelaySMS; stored in Android's SMS store and RelaySMS's inbox |
| MMS | Your other app | **Not supported**: RelaySMS shows a notice that one arrived, nothing more |
| Sending from this phone's number | Not possible in RelaySMS | Possible if the phone has a SIM with service |
| `sms:` links, "reply with message" on a call | Other app | RelaySMS |

Things to know before you do it:

- **Android's SMS store is not encrypted by RelaySMS** and is readable by any app holding the SMS
  permission. Copies of forwarded messages, including codes, go there. If you do not want that,
  do not make RelaySMS the default.
- Each message is copied **once**. RelaySMS remembers the system row it created; re-delivery,
  re-synchronisation and app restarts add nothing. Marking read, deleting, and the retention
  setting are applied to the system copy too.
- Messages that arrived before you made RelaySMS the default are copied when the role is granted.
- A companion phone without a SIM still receives forwarded messages over the internet. Only
  sending from its own number needs a SIM; the compose screen says so and offers the source phone
  instead.
- **Switching back:** *Open Android default apps → SMS app →* your previous app. RelaySMS keeps
  its inbox, stops writing to the system store, and deletes nothing. Copies already in the system
  store stay there and belong to the system.
- If RelaySMS was installed from a file, Android may refuse the role until *App info → ⋮ → Allow
  restricted settings* is chosen. RelaySMS does not work around this.
- Whether Google Messages or Samsung Messages shows the copied rows exactly like its own messages
  is up to that app; verify it on your phone.

Components (all reachable only by the system): `SmsDeliverReceiver` (`SMS_DELIVER`,
`BROADCAST_SMS`), `MmsDeliverReceiver` (`WAP_PUSH_DELIVER`, `BROADCAST_WAP_PUSH`), `MainActivity`
(`ACTION_SENDTO` for `sms:`, `smsto:`, `mms:`, `mmsto:`), `RespondViaMessageService`
(`SEND_RESPOND_VIA_MESSAGE`). Permissions `SEND_SMS`, `READ_SMS`, `RECEIVE_MMS`,
`RECEIVE_WAP_PUSH` are declared for this and are granted by Android together with the role.

## 2. Sending an SMS through the source phone

For a companion without mobile service. The message leaves from the **source phone's SIM and
number**, at that SIM's cost.

1. **Source phone, once:** *Settings → Sending from the companion → Let the companion send SMS
   through this phone.* RelaySMS explains, then Android asks for the SMS permission. The switch
   turns on only if Android grants it. Choose the hourly limit (5, 20 or 60).
2. **Companion:** pencil button → number and text → *Send with: Paired source phone* → *Review and
   send* → confirm. Nothing is sent before the confirmation.
3. The request is encrypted and signed for the source phone and passed through the relay like any
   other message. The relay cannot read the number or the text.
4. The source phone checks the request and, if all checks pass, hands it to its SMS stack.
5. The companion shows the state: *Waiting for source phone · not sent yet* → *Sent to the
   network* → *Delivered* (only if the carrier reports delivery), or *Not sent* with the reason.

Checks on the source phone, in this order:

| Check | Refusal shown on the companion |
| --- | --- |
| Request id never seen before (stored on disk) | – (a repeat is never sent again; the earlier answer is repeated) |
| Remote sending is switched on | turned off on that phone |
| SMS permission granted | not allowed to send SMS |
| Not expired, not older than one hour, not dated in the future | received too late |
| Recipient is a phone number or short code (3–15 digits) | number is not valid |
| Text is 1–1000 characters | empty or too long |
| Fewer than the hourly limit already sent | hourly limit reached |
| SIM ready, not in airplane mode | no SIM / radio off |

Who can make the source phone send: only the paired companion. A request must carry that device's
signature and be encrypted to this phone's key, and the relay delivers only between linked
devices. Revoking the companion, or resetting either phone, ends it immediately; a request still
waiting on the relay is deleted with the link.

What "Sent" means: the source phone's mobile network accepted the message. It is not proof of
delivery. Android does not tell an app *why* a network refused a message, so "no balance", "wrong
number" and "barred" all appear as *the mobile network refused the message*.

If the source phone is offline, the request waits (on the companion, then on the relay) and the
companion keeps saying *not sent yet*. After one hour without an answer it is marked *Not sent*;
the source phone will also refuse it by then. For requests to arrive promptly while the source
app is closed, turn on *Stay connected in the background* on the source phone.

## 3. What was verified, and what was not

On the Android 15 emulator (`scripts/emulator-default-sms-check.sh`, 12 checks, and the
instrumented tests): role availability and state read from Android; with the role granted by
`adb shell cmd role`, Android grants the SMS permissions, a forwarded message is written to the
real SMS store once and removed on deletion, an SMS sent to the emulator arrives through
`SMS_DELIVER` and is stored once with a content-free notification, and removing the role is
detected and leaves the data alone; without the role nothing is written to the system store; the
compose screen sends nothing before confirmation and explains when it cannot send; an `sms:` link
only prefills the screen; as source, a request from the paired companion is sent by the real
`SmsManager` and reported as sent, and is refused with "disabled" when the switch is off.

**Not verified:** tapping through Android's role dialog (the role was granted from the shell);
role eligibility for a file-installed app on the Galaxy S25 Ultra and on your source phone; how
Samsung Messages / Google Messages display the copied rows; MMS behaviour beyond the notice;
delivery reports from a real carrier; dual-SIM selection (the default SIM is used); "reply with
message" from a real call. Google Play's SMS and default-handler policies were not assessed; a
private APK install proves nothing about Play eligibility.
