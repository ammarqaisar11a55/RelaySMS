#!/usr/bin/env bash
# Runs every automated check that needs no Android device, in order, one heavy process at a time
# (suited to a machine with little memory). Device checks are listed at the end.
#
#   scripts/test-all.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
step() { printf '\n=== %s ===\n' "$*"; }

step "Relay: typecheck, lint, format, tests, build"
"$ROOT/scripts/dev-db.sh" start >/dev/null
(cd "$ROOT/server" && { [ -d node_modules ] || npm ci --no-audit --no-fund; } && npm run -s typecheck && npm run -s lint && npm run -s format:check && npm test && npm run -s build)

step "Android: unit tests + integration tests against a real relay, lint"
"$ROOT/scripts/integration-test.sh" --no-daemon -q :app:lintDebug

step "Android: release build and static release checks"
"$ROOT/scripts/release-check.sh"

step "Secret scan"
bash "$ROOT/scripts/secret-scan.sh"

cat <<'TXT'

All device-independent checks passed.
With an emulator running (and nothing else heavy), also run:
  (cd android && bash ./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:writeUnitTestClasspath)
  scripts/device-e2e.sh                    # instrumented suite against a real relay
  scripts/emulator-sms-check.sh            # SMS reception, filters, logging
  scripts/emulator-forward-check.sh        # injected SMS -> relay -> companion
  scripts/emulator-reliability-check.sh    # screen off, kill, offline, relay stall, reboot
TXT
