#!/usr/bin/env bash
# Module 2 device check: injects synthetic SMS into a running Android emulator and verifies that
# the source role detects, filters and queues them, and that nothing sensitive reaches logcat.
# Emulator only (uses the emulator console). Requires the debug and androidTest APKs to be built:
#   (cd android && bash ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG=io.github.ammarqaisar11a55.relaysms.debug
RUNNER=$PKG.test/androidx.test.runner.AndroidJUnitRunner
CLS=io.github.ammarqaisar11a55.relaysms.EmulatorSmsScenario

step() { # step <name> [-e key value]...
  local out
  out=$(adb shell am instrument -w -e class "$CLS" -e step "$@" "$RUNNER" 2>&1)
  if ! grep -q "^OK (" <<<"$out"; then echo "$out"; echo "FAIL: step $1"; exit 1; fi
}
sms() { adb emu sms send "$1" "$2" >/dev/null; sleep 4; }

adb install -r "$ROOT/android/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
adb install -r "$ROOT/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" >/dev/null
adb logcat -c

adb shell pm revoke $PKG android.permission.RECEIVE_SMS 2>/dev/null || true
step configure
echo "1. permission denied: SMS is not seen"
sms TESTBANK "Synthetic code 111111"
step assert -e pending 0 -e received 0 -e filtered 0

adb shell pm grant $PKG android.permission.RECEIVE_SMS
echo "2. allowed sender is queued"
sms TESTBANK "Synthetic code 222222"
step assert -e pending 1 -e received 1 -e filtered 0 -e lastBodyLength 21

echo "3. unlisted sender is filtered"
sms 5550100 "Synthetic personal message"
step assert -e pending 1 -e received 2 -e filtered 1

echo "4. multipart message is reassembled into one entry"
LONG=$(printf 'Multipart synthetic text %03d. ' $(seq 1 12))
sms TESTBANK "$LONG"
step assert -e pending 2 -e received 3 -e filtered 1 -e lastBodyLength ${#LONG}

echo "5. process killed: message still queued"
adb shell am kill $PKG
sms TESTBANK "Synthetic code 333333"
step assert -e pending 3 -e received 4 -e filtered 1

echo "6. forwarding paused: nothing new is queued"
step pause
sms TESTBANK "Synthetic code 444444"
step assert -e pending 3 -e received 5 -e filtered 1

echo "7. no message content in logcat"
if adb logcat -d | grep -E "Synthetic|TESTBANK|111111|222222|333333|444444|Multipart" | grep -v "emu sms\|GsmInboundSmsHandler\|InboundSmsHandler" | grep -q "RelaySMS\|relaysms"; then
  echo "FAIL: sensitive text found in app log lines"; exit 1
fi
adb logcat -d -s RelaySMS | tail -8
echo "ALL CHECKS PASSED"
