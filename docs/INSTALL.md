# Installing, Pairing, Upgrading

RelaySMS is installed from an APK on both phones. The same APK serves both roles.

## 1. Get the APK

- **From a release:** download `RelaySMS-<version>.apk` from the repository's *Releases* page and
  check it: `sha256sum -c RelaySMS-<version>.apk.sha256`.
- **Build it yourself (debug, for development):**
  `cd android && bash ./gradlew :app:assembleDebug` →
  `app/build/outputs/apk/debug/app-debug.apk`. Debug builds have a different application id
  (`….debug`) and can be installed next to a release build.
- **Build a signed release yourself:** create a signing key once (section 6), then
  `cd android && bash ./gradlew :app:assembleRelease` →
  `app/build/outputs/apk/release/app-release.apk`.

## 2. Install on each phone

With a cable: enable *Developer options → USB debugging*, then `adb install RelaySMS-<version>.apk`.

From a file on the phone: open the APK and allow the installing app (file manager or browser) to
install unknown apps when Android asks.

- **Samsung:** *Auto Blocker* (Settings → Security and privacy) blocks installs from files. Turn
  it off for the installation; you can turn it back on afterwards.
- **Android 13 and newer, installed from a file:** Android treats the app as restricted. On the
  **source phone** the SMS permission request is then refused without a prompt. Open *Settings →
  Apps → RelaySMS → ⋮ (top right) → Allow restricted settings*, confirm with your screen lock,
  and ask again from inside RelaySMS. Installs through `adb` are not restricted.

Android 8.0 or newer is required.

## 3. First launch

| | Source phone (has the SIM) | Companion phone |
| --- | --- | --- |
| Role | *Source phone* | *Companion phone* |
| Asked for | SMS (receive), later Camera for the QR code | Notifications (optional) |
| Needs a SIM | Yes | No, internet only |

## 4. Pair the phones

Have your relay address ready (`https://…`). Keep both phones in front of you.

1. Companion: *Pair with source phone* → enter the relay address (and the registration code if
   your relay has one) → *Show pairing code*. A QR code appears for five minutes.
2. Source: *Pair with companion phone* → scan the QR code. If the camera cannot be used, choose
   *Enter pairing text instead* and type the text the companion shows under *Show pairing text*.
3. Source: check the destination and tap *Pair with this device*.
4. Both phones now show six digits. **If they are the same, tap *The codes match* on both.** If
   they differ, or one phone shows an error instead, tap *They don't match*: nothing is paired.
5. Source: open *Sender filters*, add the senders you want, then turn on *Forward incoming SMS*.

Recommended on the companion afterwards (Settings):

- *Stay connected in the background* so messages arrive within seconds when the app is closed.
- *Battery use → Unrestricted*; on Samsung also keep RelaySMS out of *Sleeping apps*.
- *Lock RelaySMS* if other people use the phone.

Do not use *Force stop* on the source phone's RelaySMS: Android stops delivering SMS to it until it
is opened again.

## 5. Upgrading and rolling back

- **Upgrade:** install the newer APK over the old one (`adb install -r …` or open the file). Data,
  keys and the pairing are kept. The database is migrated automatically; migrations never delete
  messages.
- An update must be signed with **the same key** as the installed app. If it is not, Android
  refuses the install; the only way forward is to uninstall (which deletes keys and messages) and
  pair again. This is why the signing key must be kept.
- **Rollback:** Android does not allow installing an older version over a newer one. To go back,
  uninstall and install the older APK, then pair again. Messages stored in the app are lost;
  nothing on the source phone's normal SMS app is affected.
- Upgrade the relay before the apps when a release says the protocol changed.

Versions are `major.minor.patch`; the Android version code is `major*10000 + minor*100 + patch`.

## 6. Signing key and GitHub releases

Create the key once:

```bash
scripts/create-release-keystore.sh
```

It asks for a password and writes `android/relaysms-release.jks` and `android/keystore.properties`.
Both are ignored by git. **Back them up**; losing them means future versions cannot update
existing installs.

To let GitHub build and publish releases, add four repository secrets (*Settings → Secrets and
variables → Actions*):

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 android/relaysms-release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | the keystore password |
| `ANDROID_KEY_ALIAS` | `relaysms` |
| `ANDROID_KEY_PASSWORD` | the key password (the script uses the same one) |

Then publish a version:

```bash
git tag v1.0.0 && git push origin v1.0.0
```

The *Release* workflow runs the unit tests, builds and signs the APK, verifies the signature and
the release checks (not debuggable, no cleartext, expected permissions), and attaches
`RelaySMS-1.0.0.apk` with its checksum to a GitHub release. Without the secrets it stops with a
message and publishes nothing, because an unsigned APK cannot be installed.

## 7. Removing RelaySMS

On each phone: *Settings → Reset RelaySMS on this phone* (tells the relay to delete the device),
then uninstall. On the relay: see [DEPLOYMENT.md](DEPLOYMENT.md) or [VERCEL.md](VERCEL.md).

## 8. What has and has not been verified

Verified on the Android 15 emulator: installation with `adb`, the restricted-settings behaviour
(simulated), both roles, pairing with typed pairing text, upgrade-safe database migration, and the
minified release build at runtime (`scripts/release-smoke.sh`).

Not verified: installation from a file on a real phone, Samsung Auto Blocker, QR scanning with a
real camera, and the release workflow's publishing step before the first tagged release.
