#!/usr/bin/env bash
# Full forwarding path on an emulator: real SMS injected into the emulator's modem -> RelaySMS
# receiver -> filter -> encrypted outbox -> upload -> real relay -> simulated companion decrypts.
# Also checks that neither the app's log nor the relay's database contains message text.
#
#   (cd android && bash ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest)
#   scripts/emulator-forward-check.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG=io.github.ammarqaisar11a55.relaysms.debug
adb install -r "$ROOT/android/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
adb install -r "$ROOT/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" >/dev/null
adb shell pm grant $PKG android.permission.RECEIVE_SMS

exec "$ROOT/scripts/with-relay.sh" bash -c '
  set -euo pipefail
  PKG='"$PKG"'
  adb logcat -c
  OUT=$(mktemp)
  adb shell am instrument -w -e relayUrl "http://10.0.2.2:$RELAY_TEST_PORT" -e step forward \
    -e class io.github.ammarqaisar11a55.relaysms.TransportOnDeviceTest#appAsSourceForwardsRealIncomingSmsToTheCompanion \
    $PKG.test/androidx.test.runner.AndroidJUnitRunner >"$OUT" 2>&1 &
  TEST_PID=$!
  for _ in $(seq 1 120); do
    adb logcat -d -s RelaySmsE2E | grep -q E2E_READY && break
    sleep 0.5
  done
  adb logcat -d -s RelaySmsE2E | grep -q E2E_READY || { cat "$OUT"; echo "FAIL: app never became ready"; exit 1; }
  echo "app paired and forwarding; injecting SMS"
  adb emu sms send TESTBANK "Synthetic code 246810" >/dev/null
  sleep 2
  adb emu sms send 5550100 "Synthetic personal message" >/dev/null
  sleep 2
  adb emu sms send TESTBANK "$(printf "Multipart synthetic text %03d. " $(seq 1 12))" >/dev/null
  wait $TEST_PID || true
  tail -8 "$OUT"
  grep -q "^OK (1 test)" "$OUT" || { echo "FAIL: forwarding test"; exit 1; }

  echo "checking the relay database for plaintext"
  LEAKS=$(psql "$RELAY_TEST_DATABASE_URL" -qAt -c "SELECT COUNT(*) FROM relaysms.relay_messages WHERE ciphertext IS NOT NULL AND (position(convert_to('"'"'Synthetic'"'"', '"'"'UTF8'"'"') in ciphertext) > 0 OR position(convert_to('"'"'TESTBANK'"'"', '"'"'UTF8'"'"') in ciphertext) > 0)")
  ROWS=$(psql "$RELAY_TEST_DATABASE_URL" -qAt -c "SELECT COUNT(*) FROM relaysms.relay_messages")
  HELD=$(psql "$RELAY_TEST_DATABASE_URL" -qAt -c "SELECT COUNT(*) FROM relaysms.relay_messages WHERE ciphertext IS NOT NULL")
  # Acknowledged rows no longer hold ciphertext at all; any that still do must be opaque.
  echo "relay rows: $ROWS (still holding ciphertext: $HELD), rows containing plaintext: $LEAKS"
  [ "$LEAKS" = "0" ] && [ "$ROWS" -ge 2 ] || { echo "FAIL: relay database check"; exit 1; }

  echo "checking app log lines for message text"
  if adb logcat -d | grep -E "RelaySMS|relaysms" | grep -vE "InboundSmsHandler|emu sms" | grep -qE "Synthetic|TESTBANK|246810"; then
    echo "FAIL: message text in app log"; exit 1
  fi
  adb logcat -d -s RelaySMS | tail -6
  echo "ALL CHECKS PASSED"
'
