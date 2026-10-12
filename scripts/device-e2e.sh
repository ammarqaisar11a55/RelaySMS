#!/usr/bin/env bash
# Runs the on-device (instrumented) test suite on a connected emulator against a real relay on
# this machine. The emulator reaches the host's loopback interface as 10.0.2.2.
#
#   (cd android && bash ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest)
#   scripts/device-e2e.sh [-e class <fully.qualified.TestClass>]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG=io.github.ammarqaisar11a55.relaysms.debug
adb install -r "$ROOT/android/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
adb install -r "$ROOT/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" >/dev/null
exec "$ROOT/scripts/with-relay.sh" bash -c '
  out=$(adb shell am instrument -w -e relayUrl "http://10.0.2.2:$RELAY_TEST_PORT" "$@" '"$PKG"'.test/androidx.test.runner.AndroidJUnitRunner 2>&1)
  echo "$out" | tail -40
  grep -q "^OK (" <<<"$out"
' _ "$@"
