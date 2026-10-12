#!/usr/bin/env bash
# Captures the main screens with synthetic content in light and dark themes, for visual review.
# Needs a running emulator or device with the debug and androidTest APKs built. No relay needed.
#
#   scripts/screenshots.sh <output-dir>
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:?output directory}"; mkdir -p "$OUT"
PKG=io.github.ammarqaisar11a55.relaysms.debug
RUNNER=$PKG.test/androidx.test.runner.AndroidJUnitRunner
adb install -r "$ROOT/android/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
adb install -r "$ROOT/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" >/dev/null

seed() { adb shell am instrument -w -e class io.github.ammarqaisar11a55.relaysms.ScreenshotScenario -e step seed "$@" "$RUNNER" | grep -q "^OK (" || { echo "seed failed: $*"; exit 1; }; }
launch() { adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 5; }
shot() { adb exec-out screencap -p >"$OUT/$1.png"; echo "  $1.png"; }
tap() { # tap the first node whose text or content-desc equals $1
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  local b; b=$(adb shell cat /sdcard/ui.xml | grep -oE "(text|content-desc)=\"$1\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1)
  [ -n "$b" ] || { echo "  (no '$1' on screen)"; return 1; }
  set -- $(echo "$b" | tr -c '0-9' ' ')
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )); sleep 2
}
scroll() { adb shell input swipe 540 1700 540 600 300; sleep 1; }

echo "onboarding"
seed -e role none; launch; shot 01-welcome
tap "Continue" && shot 02-role-selection || true

for theme in light dark; do
  echo "companion ($theme)"
  seed -e role companion -e theme $theme; launch; shot "10-inbox-$theme"
  tap "TESTBANK" && shot "11-message-detail-$theme" || true
  adb shell input keyevent KEYCODE_BACK; sleep 1
  tap "More options" && tap "Settings" && shot "12-settings-$theme" && scroll && shot "13-settings-2-$theme" || true
  echo "source ($theme)"
  seed -e role source -e theme $theme; launch; shot "20-source-home-$theme"; scroll; shot "21-source-home-2-$theme"
done

echo "empty and unpaired states"
seed -e role companion -e theme light -e paired no; launch; shot 30-inbox-unpaired
adb shell settings put system font_scale 1.3
seed -e role companion -e theme light; launch; shot 31-inbox-large-text
adb shell settings put system font_scale 1.0
echo "done: $OUT"
