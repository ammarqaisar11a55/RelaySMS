#!/usr/bin/env bash
# Runtime check of the minified (R8) build on an emulator: the "staging" variant is the release
# build plus permission to reach the local development relay. The script drives the real UI:
# onboarding as companion, pairing with a simulated source phone, receiving an encrypted message
# in the background, restart persistence. It proves that code shrinking did not break
# cryptography, storage, serialisation or networking.
#
#   (cd android && bash ./gradlew --no-daemon :app:assembleStaging :app:writeUnitTestClasspath)
#   scripts/release-smoke.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
exec "$ROOT/scripts/with-relay.sh" bash -c '
set -euo pipefail
ROOT="$0"
PKG=io.github.ammarqaisar11a55.relaysms.staging
CP="$(cat "$ROOT/android/app/build/unit-test-classpath.txt")"
WORK="$(mktemp -d)"; trap "rm -rf $WORK" EXIT
peer() { java -Xmx192m -cp "$CP" io.github.ammarqaisar11a55.relaysms.PeerToolKt "$WORK/peer.json" "$@" 2>>"$WORK/peer.err"; }
die() { echo "FAIL: $*"; cat "$WORK/peer.err" 2>/dev/null | tail -5; adb logcat -d | grep -E "AndroidRuntime|FATAL EXCEPTION" -A12 | tail -30; exit 1; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml; }
has() { dump | grep -q "$1"; }
wait_for() { for _ in $(seq 1 30); do has "$1" && return 0; sleep 1; done; return 1; }
tap() {
  local b; b=$(dump | grep -oE "(text|content-desc)=\"$1\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" | grep -oE "\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]" | head -1)
  [ -n "$b" ] || return 1
  set -- $(echo "$b" | tr -c "0-9" " ")
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )); sleep 2
}
scroll() { adb shell input swipe 540 1800 540 500 300; sleep 1; }

APK="$(ls "$ROOT"/android/app/build/outputs/apk/staging/*.apk | head -1)"
adb uninstall $PKG >/dev/null 2>&1 || true
adb install "$APK" >/dev/null
adb logcat -c
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
wait_for "Welcome to RelaySMS" || die "app did not start"
echo "ok: minified app starts"
scroll; tap "Continue" || die "no Continue"
wait_for "Companion phone" || die "role screen"
tap "Companion phone"; scroll; tap "Set up as companion phone" || die "role confirm"
wait_for "Pair with source phone" || die "inbox empty state"
echo "ok: onboarding and preferences"
tap "Pair with source phone"
wait_for "Relay server address" || die "pairing screen"
# Type the relay address into the first field.
B=$(dump | grep -oE "class=\"android.widget.EditText\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" | grep -oE "\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]" | head -1)
set -- $(echo "$B" | tr -c "0-9" " "); adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )); sleep 1
adb shell input keyevent KEYCODE_MOVE_END; for _ in $(seq 1 60); do adb shell input keyevent KEYCODE_DEL; done
adb shell input text "http://10.0.2.2:$RELAY_TEST_PORT"; adb shell input keyevent KEYCODE_BACK; sleep 1
tap "Show pairing code" || die "start pairing"
wait_for "Scan this code" || die "no pairing code (registration or key generation failed)"
echo "ok: device keys generated, registered with the relay, QR shown"
scroll; tap "Camera not working\\? Show pairing text" || die "show text"
scroll
dump | grep -oE "text=\"RSMS1:[A-Za-z0-9_-]+\"" | head -1 | sed "s/text=\"//;s/\"$//" >"$WORK/qr.txt"
[ -s "$WORK/qr.txt" ] || die "pairing text not found"
peer pair-source "$WORK/qr.txt" >"$WORK/pair.out" &
PEER=$!
for _ in $(seq 1 40); do grep -q "^CODE " "$WORK/pair.out" && break; sleep 1; done
CODE=$(grep "^CODE " "$WORK/pair.out" | cut -c6-) || die "peer could not redeem"
wait_for "Compare this code" || die "verification screen"
has "text=\"$CODE\"" || die "app shows a different code than the peer ($CODE)"
echo "ok: both sides show the same code"
tap "The codes match" || { scroll; tap "The codes match" || die "confirm"; }
wait $PEER || die "peer pairing failed"
wait_for "Paired" || die "pairing not completed in app"
echo "ok: paired (HPKE, Ed25519, HMAC proof, encrypted identity store)"
scroll; tap "Done" || true
adb shell input keyevent KEYCODE_HOME; sleep 2
peer send TESTBANK "Synthetic code 909192" | grep -q "SEND ACCEPTED" || die "send"
# App is in the background: delivery happens at the periodic sync or when reopened.
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
wait_for "Synthetic code 909192" || die "message not received/decrypted/stored"
has "TESTBANK" || die "sender missing"
echo "ok: message received, decrypted, stored and shown"
for _ in $(seq 1 20); do peer awaiting | grep -q "^AWAITING 0" && break; sleep 1; done
peer awaiting | grep -q "^AWAITING 0" || die "not acknowledged"
adb shell am force-stop $PKG
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
wait_for "Synthetic code 909192" || die "message lost after restart (Room/Keystore under R8)"
echo "ok: survives restart"
tap "TESTBANK" && wait_for "Likely verification code" || die "detail / code detection"
has "909192" || die "code not shown"
echo "ok: detail screen and code detection"
if adb logcat -d | grep -q "FATAL EXCEPTION"; then die "crash in log"; fi
adb uninstall $PKG >/dev/null
echo "RELEASE SMOKE PASSED"
' "$ROOT"
