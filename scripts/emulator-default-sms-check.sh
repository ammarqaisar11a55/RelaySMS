#!/usr/bin/env bash
# Default SMS app and remote sending on an Android emulator, against a real relay on this host.
#
#   (cd android && bash ./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:writeUnitTestClasspath)
#   scripts/emulator-default-sms-check.sh
#
# The SMS role is granted and removed with `adb shell cmd role`, which stands in for the user
# tapping through Android's "set default SMS app" dialog; that dialog itself is not exercised.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
exec "$ROOT/scripts/with-relay.sh" bash -c '
set -euo pipefail
ROOT="$0"
PKG=io.github.ammarqaisar11a55.relaysms.debug
RUNNER=$PKG.test/androidx.test.runner.AndroidJUnitRunner
CP="$(cat "$ROOT/android/app/build/unit-test-classpath.txt")"
WORK="$(mktemp -d)"; PREVIOUS=""
restore() { adb shell cmd role remove-role-holder android.app.role.SMS $PKG >/dev/null 2>&1 || true; [ -n "$PREVIOUS" ] && adb shell cmd role add-role-holder android.app.role.SMS "$PREVIOUS" >/dev/null 2>&1 || true; rm -rf "$WORK"; }
trap restore EXIT
PASS=0; ok() { PASS=$((PASS+1)); echo "  PASS: $*"; }; die() { echo "  FAIL: $*"; adb logcat -d -s RelaySMS | tail -12; exit 1; }
peer() { java -Xmx192m -cp "$CP" io.github.ammarqaisar11a55.relaysms.PeerToolKt "$WORK/peer.json" "$@" 2>/dev/null; }
test_class() { # <class#method or class> [-e k v]...
  local c="$1"; shift
  local out; out=$(adb shell am instrument -w -e relayUrl "http://10.0.2.2:$RELAY_TEST_PORT" -e class "io.github.ammarqaisar11a55.relaysms.$c" "$@" "$RUNNER" 2>&1)
  grep -q "^OK (" <<<"$out" || { echo "$out" | grep -vE "^\s+at " | tail -15; return 1; }
}
Q=$(printf "\x27")
sys_count() { adb shell "content query --uri content://sms --projection _id --where \"body=${Q}$1${Q}\"" 2>/dev/null | grep -c "_id=" || true; }

adb install -r "$ROOT/android/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
adb install -r "$ROOT/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" >/dev/null
adb logcat -c
PREVIOUS=$(adb shell cmd role get-role-holders android.app.role.SMS | tr -d "\r" | head -1)
echo "current default SMS app: ${PREVIOUS:-none}"

echo "== Not the default SMS app =="
test_class "DefaultSmsOnDeviceTest#defaultRoleStatusIsReadFromAndroid" -e expectDefault no && ok "role reported as not held, and as available on this device" || die "role status"
test_class "DefaultSmsOnDeviceTest" && ok "mirroring rules, compose screen, sms: link, settings (companion-only mode)" || die "suite without role"

echo "== RelaySMS as the default SMS app =="
adb shell cmd role add-role-holder android.app.role.SMS $PKG >/dev/null
test_class "DefaultSmsOnDeviceTest#defaultRoleStatusIsReadFromAndroid" -e expectDefault yes && ok "role reported as held after Android granted it" || die "role not held (device refused the role)"
adb shell dumpsys package $PKG | grep -q "android.permission.SEND_SMS: granted=true" && ok "Android granted the SMS permissions with the role" || die "permissions not granted with role"
test_class "DefaultSmsOnDeviceTest#asDefaultAppForwardedMessagesReachAndroidsSmsStoreOnceAndFollowDeletion" && ok "forwarded message written to Android SMS store once; deletion follows" || die "provider integration"

echo "D1 an SMS to this phone itself while RelaySMS is the default app"
test_class "ScreenshotScenario#seed" -e step seed -e role companion -e theme light >/dev/null || die "seed"
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
adb shell cmd notification cancel_all >/dev/null 2>&1 || true
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 4; adb shell input keyevent KEYCODE_HOME; sleep 3   # let Android register that the app left the screen
DIRECT="Synthetic direct $(date +%s)"
adb emu sms send 5550199 "$DIRECT" >/dev/null; sleep 6
[ "$(sys_count "$DIRECT")" = "1" ] && ok "stored in Android SMS store exactly once (SMS_DELIVER handled)" || die "D1 system store count=$(sys_count "$DIRECT")"
adb shell dumpsys notification --noredact | grep -q "android.text=String (New message received)" && ok "notified without content" || die "D1 notification"

echo "D2 switching back to another SMS app keeps RelaySMS data"
adb shell cmd role remove-role-holder android.app.role.SMS $PKG >/dev/null
[ -n "$PREVIOUS" ] && adb shell cmd role add-role-holder android.app.role.SMS "$PREVIOUS" >/dev/null
test_class "DefaultSmsOnDeviceTest#defaultRoleStatusIsReadFromAndroid" -e expectDefault no && ok "role loss detected" || die "D2 role"
[ "$(sys_count "$DIRECT")" = "1" ] && ok "system copy untouched after switching away" || die "D2 system copy"

echo "== Remote sending through the app as source =="
adb shell pm grant $PKG android.permission.SEND_SMS
adb shell pm grant $PKG android.permission.RECEIVE_SMS
test_class "ReliabilityScenario#setupRemoteSendSource" -e step setupRemoteSendSource || die "setup"
adb shell run-as $PKG cat files/e2e-peer.json >"$WORK/peer.json"
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 5
REQ=$(peer request-send 5550123 "Synthetic remote 161803" | awk "/^REQUEST/{print \$2}")
[ -n "$REQ" ] || die "request not uploaded"
RES=""
for _ in $(seq 1 40); do RES=$(peer sync | grep "^RESULT $REQ" | tail -1 || true); [ -n "$RES" ] && break; sleep 2; done
echo "  source answered: ${RES:-nothing}"
grep -q "^RESULT $REQ sent" <<<"$RES" && ok "R1 request from the paired companion was sent by the source and reported as sent" || die "R1"
adb logcat -d | grep -E "RelaySMS|relaysms" | grep -q "161803" && die "message text in app log" || ok "no message text in app log"

echo "R2 remote sending turned off on the source"
test_class "ReliabilityScenario#setupRemoteSendSource" -e step setupRemoteSendSource -e remote off || die "setup off"
adb shell run-as $PKG cat files/e2e-peer.json >"$WORK/peer.json"
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 5
REQ=$(peer request-send 5550123 "Synthetic refused" | awk "/^REQUEST/{print \$2}")
RES=""
for _ in $(seq 1 30); do RES=$(peer sync | grep "^RESULT $REQ" | tail -1 || true); [ -n "$RES" ] && break; sleep 2; done
grep -q "^RESULT $REQ failed disabled" <<<"$RES" && ok "R2 refused with reason: disabled" || die "R2 got: $RES"

adb shell pm revoke $PKG android.permission.SEND_SMS >/dev/null 2>&1 || true
echo "ALL $PASS CHECKS PASSED"
' "$ROOT"
