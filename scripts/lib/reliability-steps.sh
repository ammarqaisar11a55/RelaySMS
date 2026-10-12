#!/usr/bin/env bash
# Body of emulator-reliability-check.sh; runs inside with-relay.sh (relay up, RELAY_TEST_* set).
set -euo pipefail
ROOT="$1"; WHICH="$2"
PKG=io.github.ammarqaisar11a55.relaysms.debug
RUNNER=$PKG.test/androidx.test.runner.AndroidJUnitRunner
CP="$(cat "$ROOT/android/app/build/unit-test-classpath.txt")"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
STATE="$WORK/peer.json"; SEEN="$WORK/seen.txt"; : >"$SEEN"
PASS=0

peer() { java -Xmx192m -cp "$CP" io.github.ammarqaisar11a55.relaysms.PeerToolKt "$STATE" "$@" 2>/dev/null; }
say() { printf '%s\n' "$*"; }
ok() { PASS=$((PASS + 1)); say "  PASS: $*"; }
die() { say "  FAIL: $*"; adb logcat -d -s RelaySMS | tail -15; exit 1; }
net() { adb shell svc wifi "$1" >/dev/null; adb shell svc data "$1" >/dev/null; }
sms() { adb emu sms send "$1" "$2" >/dev/null; }
launch() { adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 4; adb shell input keyevent KEYCODE_HOME; }
wait_boot() {
  adb wait-for-device
  for _ in $(seq 1 120); do [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break; sleep 3; done
  sleep 8
}
setup() { # setup <step> [-e k v]
  local out; out=$(adb shell am instrument -w -e relayUrl "http://10.0.2.2:$RELAY_TEST_PORT" -e class io.github.ammarqaisar11a55.relaysms.ReliabilityScenario -e step "$@" "$RUNNER" 2>&1)
  grep -q "^OK (" <<<"$out" || { say "$out"; die "setup $1"; }
  adb shell run-as $PKG cat files/e2e-peer.json >"$STATE"
  [ -s "$STATE" ] || die "peer state not written"
}
# Source role: wait until the simulated companion has received a message containing $1.
companion_gets() { # <text> <timeout seconds>
  local deadline=$(( $(date +%s) + $2 ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    peer sync >>"$SEEN" || true
    grep -q "MSG .*$1" "$SEEN" && return 0
    sleep 3
  done
  return 1
}
companion_has_not() { peer sync >>"$SEEN" || true; ! grep -q "MSG .*$1" "$SEEN"; }
# Companion role: the relay reports nothing waiting only after the app stored and acknowledged.
app_stored_all() { # <timeout seconds>
  local deadline=$(( $(date +%s) + $1 ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    peer awaiting | grep -q "^AWAITING 0 PAIRED" && return 0
    sleep 3
  done
  return 1
}

adb install -r "$ROOT/android/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
adb install -r "$ROOT/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" >/dev/null
adb shell pm grant $PKG android.permission.RECEIVE_SMS
net enable; adb logcat -c

if [ "$WHICH" = source ] || [ "$WHICH" = all ]; then
  say "== Source phone (app forwards real incoming SMS) =="
  setup setupSource
  launch

  say "S1 screen off, app in background"
  adb shell input keyevent KEYCODE_SLEEP; sleep 2
  sms TESTBANK "Synthetic locked 101"
  companion_gets "Synthetic locked 101" 90 && ok "delivered with the screen off" || die "S1"

  say "S2 app process killed"
  adb shell am kill $PKG; sleep 1
  [ -z "$(adb shell pidof $PKG | tr -d '\r')" ] || say "  note: process was not killable (still foreground-adjacent)"
  sms TESTBANK "Synthetic killed 202"
  companion_gets "Synthetic killed 202" 90 && ok "delivered after the process was killed" || die "S2"

  say "S3 no network when the SMS arrives"
  net disable; sleep 4
  sms TESTBANK "Synthetic offline 303"; sleep 10
  companion_has_not "Synthetic offline 303" && ok "held while offline (nothing reached the relay)" || die "S3 leaked while offline"
  net enable
  companion_gets "Synthetic offline 303" 240 && ok "delivered after the network returned" || die "S3"

  say "S4 relay not answering"
  kill -STOP "$RELAY_TEST_PID"
  sms TESTBANK "Synthetic stalled 404"; sleep 20
  kill -CONT "$RELAY_TEST_PID"
  companion_gets "Synthetic stalled 404" 300 && ok "delivered after the relay recovered" || die "S4"

  say "S5 device restart with a message still queued"
  net disable; sleep 4
  sms TESTBANK "Synthetic reboot 505"; sleep 8
  adb reboot; wait_boot
  companion_has_not "Synthetic reboot 505" || die "S5 delivered before network"
  net enable
  # The app is not opened after the restart: delivery must come from the persisted queue alone.
  companion_gets "Synthetic reboot 505" 300 && ok "queued message survived the restart and was delivered" || die "S5"

  DUPES=$(grep "^MSG " "$SEEN" | sort | uniq -d | wc -l)
  [ "$DUPES" = 0 ] && ok "no message was delivered twice" || die "duplicates seen"
fi

if [ "$WHICH" = companion ] || [ "$WHICH" = all ]; then
  say "== Companion phone (app receives with the background connection on) =="
  : >"$SEEN"; net enable
  setup setupCompanion
  launch
  sleep 6
  adb shell dumpsys activity services $PKG | grep -q "ConnectionService" && ok "background connection service is running" || die "service not running"

  say "C1 screen off, app in background"
  adb shell input keyevent KEYCODE_SLEEP; sleep 2
  peer send TESTBANK "Synthetic code 616161" | grep -q "SEND ACCEPTED" || die "C1 send"
  app_stored_all 60 && ok "received, stored and acknowledged with the screen off" || die "C1"
  adb shell dumpsys notification --noredact | grep -q "android.text=String (New message received)" && ok "generic notification posted" || die "C1 no notification"
  adb shell dumpsys notification --noredact | grep -q "616161" && die "notification leaked the code" || ok "notification does not contain the message"

  say "C2 companion offline when the message is sent"
  net disable; sleep 4
  peer send TESTBANK "Synthetic offline 727272" | grep -q "SEND ACCEPTED" || die "C2 send"
  sleep 6
  peer awaiting | grep -q "^AWAITING 1" && ok "held on the relay while the companion is offline" || die "C2 not queued"
  net enable
  app_stored_all 180 && ok "received after the network returned" || die "C2"

  say "C3 device restart"
  adb reboot; wait_boot; sleep 10
  adb shell dumpsys activity services $PKG | grep -q "ConnectionService" && ok "service came back after the restart without opening the app" || die "C3 service not restarted"
  peer send TESTBANK "Synthetic reboot 838383" | grep -q "SEND ACCEPTED" || die "C3 send"
  app_stored_all 120 && ok "received after the restart" || die "C3"
fi

say "app log (event names only):"; adb logcat -d -s RelaySMS | tail -12
if adb logcat -d | grep -E "RelaySMS|relaysms" | grep -vE "InboundSmsHandler|emu sms" | grep -qE "Synthetic|616161|727272|838383"; then die "message text in app log"; fi
say "ALL $PASS CHECKS PASSED"
