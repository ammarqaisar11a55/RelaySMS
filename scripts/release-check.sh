#!/usr/bin/env bash
# Static checks of the release APK: not debuggable, no backup, no cleartext traffic, no test-only
# defaults, expected permissions only. Builds the release variant (unsigned unless
# android/keystore.properties exists).
#
#   scripts/release-check.sh [--no-build]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
AAPT2="$(ls "$SDK"/build-tools/*/aapt2 | sort -V | tail -1)"
[ "${1:-}" = "--no-build" ] || (cd "$ROOT/android" && bash ./gradlew --no-daemon -q :app:assembleRelease)
APK="$(ls "$ROOT"/android/app/build/outputs/apk/release/*.apk | head -1)"
echo "checking $(basename "$APK") ($(du -h "$APK" | cut -f1))"
fail() { echo "FAIL: $*"; exit 1; }

MANIFEST="$("$AAPT2" dump xmltree --file AndroidManifest.xml "$APK")"
grep -q 'android:debuggable.*=true' <<<"$MANIFEST" && fail "release APK is debuggable"
grep -q 'android:allowBackup.*=false' <<<"$MANIFEST" || fail "allowBackup is not false"
grep -q 'android:usesCleartextTraffic.*=true' <<<"$MANIFEST" && fail "usesCleartextTraffic is true"
grep -q 'android:networkSecurityConfig' <<<"$MANIFEST" || fail "no network security config"
grep -q 'android:testOnly.*=true' <<<"$MANIFEST" && fail "testOnly APK"
echo "ok: not debuggable, backup off, no cleartext flag"

# Release builds shorten resource paths, so look the file up in the resource table first.
NSC_PATH="$("$AAPT2" dump resources "$APK" | grep -A2 'xml/network_security_config' | grep -o 'res/[^ ]*' | head -1)"
[ -n "$NSC_PATH" ] || fail "network security config resource not found"
NSC="$("$AAPT2" dump xmltree --file "$NSC_PATH" "$APK")"
grep -q 'cleartextTrafficPermitted.*=true' <<<"$NSC" && fail "release network config permits cleartext"
grep -q 'src.*"user"' <<<"$NSC" && fail "release trusts user-installed certificates"
grep -q 'domain-config' <<<"$NSC" && fail "release network config has per-domain exceptions"
echo "ok: TLS only, system trust anchors, no domain exceptions"

PERMS="$("$AAPT2" dump permissions "$APK" | grep "uses-permission" | sed "s/.*name='\([^']*\)'.*/\1/" | sort)"
echo "permissions:"; sed 's/^/  /' <<<"$PERMS"
for never in READ_CONTACTS ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION BIND_ACCESSIBILITY_SERVICE READ_PHONE_STATE REQUEST_INSTALL_PACKAGES; do
  grep -q "$never" <<<"$PERMS" && fail "unexpected permission $never"
done

# The debug default server and the debug-only suffix must not be compiled in.
unzip -p "$APK" 'classes*.dex' | strings -n 8 | grep -q 'http://10.0.2.2:8080' && fail "debug server address in release code"
echo "ok: no debug server default in release code"
echo "RELEASE CHECK PASSED"
