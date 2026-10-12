# Data Storage, Retention and Deletion

What RelaySMS keeps, where, for how long, and what each delete action really removes.

## On the source phone

| Data | Where | Protection | Kept until |
| --- | --- | --- | --- |
| SMS accepted for forwarding, waiting to upload | Room `outbox.payload` | AES-256-GCM, Keystore-wrapped key | The relay accepts it; or 72 hours; or the pairing is removed |
| Tombstone of a forwarded SMS (id and state only, no content) | Room `outbox` | — | 7 days (rejects duplicate broadcasts) |
| Chosen senders (allowlist) | Room `sender_filters` | App-private storage | You remove them, or reset |
| Recently seen senders (suggestions; sender only, never text) | Room `seen_senders` | Sender encrypted | Newest 40 kept |
| Counters (how many SMS seen, forwarded, skipped) | DataStore | App-private storage | Reset |

SMS from senders that are not forwarded are never stored by RelaySMS. The original messages stay
in the phone's normal SMS app; RelaySMS never deletes or modifies them.

## On the relay server

| Data | Kept until |
| --- | --- |
| Ciphertext of an undelivered message | Acknowledged by the companion, or `MESSAGE_TTL_HOURS` (72 by default), or the link is revoked |
| Tombstone of a delivered message (ids, size, times; no ciphertext) | The message's original expiry time |
| Device public keys, hashed credential | One day after the device is reset or deleted on the relay. A device that merely stops being used stays registered |
| Link between two devices | Revoked by either phone, replaced by a new pairing, or either device is deleted |
| Pairing session | One day after it expires |

The relay never holds plaintext, sender addresses or codes.

## On the companion phone

| Data | Where | Protection | Kept until |
| --- | --- | --- | --- |
| Inbox messages (sender and text) | Room `messages.address_enc`, `body_enc` | AES-256-GCM with the record id as associated data; key wrapped by Android Keystore | The retention period, or you delete them |
| Message metadata (times, read flag, "has code" flag, hashed sender key) | Room `messages` | App-private storage | Same as the message |
| Tombstone of an erased message (id only) | Room `messages` | — | 30 days, so a re-delivered copy cannot reappear |

Room does not encrypt a database by itself; RelaySMS encrypts the sensitive columns before they
are written. Decrypted text exists only in memory. It is never written to DataStore, preferences,
logs, analytics or crash reports (the app has none of the last two).

### Retention setting

*Settings → Message retention*: 7 days, 30 days, 90 days (default), 1 year, or keep until deleted.
Age is measured from the time the SMS originally reached the source phone. Retention is applied at
app start, after each background sync and immediately when the setting is changed. Erasing
overwrites the encrypted sender and text with NULL; it cannot be undone.

### Delete actions

| Action | Effect |
| --- | --- |
| Delete a message | Erases its sender and text on this phone; keeps a tombstone for 30 days |
| Delete all messages | Same, for every message |
| Revoke device | Removes the pairing on the relay and its queued ciphertext; inbox messages already on the companion stay until deleted |
| Forget on this phone only | Removes the local pairing; the relay-side link is removed at next contact |
| Reset RelaySMS | Deletes the relay registration (best effort), this phone's private keys, the local storage key, the database and all preferences |

Deleting in RelaySMS never touches the SMS stored by the source phone's own messaging app.

## Backup, reinstall and new phones

- `android:allowBackup="false"` and data-extraction rules exclude the database, preferences and
  key files from cloud backup and device-to-device transfer.
- Private keys live in Android Keystore-wrapped storage and cannot be exported.
- **Uninstalling, clearing app data, or moving to a new phone therefore loses the inbox and the
  keys.** After reinstalling, choose the role again and pair again. Messages that were still
  waiting on the relay for the old installation cannot be decrypted by the new one.
- If the Keystore key is lost while the app stays installed (rare; some devices do this after
  certain lock-screen changes), RelaySMS detects that stored data is unreadable, discards it and
  asks for a new pairing.
