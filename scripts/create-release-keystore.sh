#!/usr/bin/env bash
# Creates the signing key for release builds, once. Keep the resulting file and its passwords
# safe and backed up: an app update must be signed with the same key, or Android refuses to
# install it over the existing app.
#
#   scripts/create-release-keystore.sh
#
# Writes android/relaysms-release.jks and android/keystore.properties. Both are ignored by git.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KS="$ROOT/android/relaysms-release.jks"
PROPS="$ROOT/android/keystore.properties"
[ -e "$KS" ] && { echo "A keystore already exists at $KS. Refusing to overwrite it."; exit 1; }

read -r -s -p "Choose a keystore password (at least 12 characters): " PASS; echo
[ "${#PASS}" -ge 12 ] || { echo "Too short."; exit 1; }
read -r -s -p "Repeat it: " PASS2; echo
[ "$PASS" = "$PASS2" ] || { echo "Passwords differ."; exit 1; }

umask 077
export PASS
keytool -genkeypair -v -keystore "$KS" -storetype PKCS12 -alias relaysms \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass:env PASS -keypass:env PASS \
  -dname "CN=RelaySMS private build" >/dev/null
printf 'storeFile=relaysms-release.jks\nstorePassword=%s\nkeyAlias=relaysms\nkeyPassword=%s\n' "$PASS" "$PASS" >"$PROPS"

cat <<TXT

Created:
  $KS
  $PROPS
Neither file is tracked by git. Back up both somewhere safe.

Local signed build:
  cd android && bash ./gradlew :app:assembleRelease

For releases built by GitHub Actions, add four repository secrets
(GitHub -> Settings -> Secrets and variables -> Actions -> New repository secret):
  ANDROID_KEYSTORE_BASE64    output of:  base64 -w0 android/relaysms-release.jks
  ANDROID_KEYSTORE_PASSWORD  the password you just chose
  ANDROID_KEY_ALIAS          relaysms
  ANDROID_KEY_PASSWORD       the same password
TXT
