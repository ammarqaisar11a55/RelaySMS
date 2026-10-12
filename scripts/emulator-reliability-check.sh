#!/usr/bin/env bash
# Module 8 background reliability check on an Android emulator, against a real relay on this host.
# The app runs on its own (not inside a test) while a peer phone simulated on the host sends to it
# or receives from it. Conditions exercised: screen off, process killed, no network, relay not
# answering, and a restart of the device.
#
# Prerequisites (one Gradle run, then no Gradle while the emulator is up):
#   (cd android && bash ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:writeUnitTestClasspath)
#   scripts/emulator-reliability-check.sh [source|companion|all]
#
# Emulator only: it injects SMS through the emulator console and toggles the emulator's radios.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WHICH="${1:-all}"
exec "$ROOT/scripts/with-relay.sh" bash "$ROOT/scripts/lib/reliability-steps.sh" "$ROOT" "$WHICH"
