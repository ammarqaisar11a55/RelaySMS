#!/usr/bin/env bash
# Fails if a tracked file looks like it contains a credential or is a kind of file that must
# never be committed. A quick local guard; CI also runs gitleaks over the full history.
#
#   scripts/secret-scan.sh
#
# Prints file names and line numbers only, never the matching text.
set -euo pipefail
cd "$(dirname "$0")/.."
git() { command git -c safe.directory="$PWD" "$@"; }

status=0

# 1. File types that must not be tracked at all.
forbidden=$(git ls-files | grep -Ei \
  -e '(^|/)\.env($|\.)' \
  -e '\.(jks|keystore|p12|pfx|pem|key|crt|der)$' \
  -e '(^|/)keystore\.properties$' \
  -e '(^|/)local\.properties$' \
  -e '(^|/)google-services\.json$' \
  -e '(^|/)id_(rsa|ed25519|ecdsa)(\.pub)?$' |
  grep -Ev '(^|/)\.env\.example$' || true)
if [ -n "$forbidden" ]; then
  echo "Tracked files that must not be committed:"
  echo "$forbidden" | sed 's/^/  /'
  status=1
fi

# 2. Content that looks like a credential.
patterns=(
  '-----BEGIN [A-Z ]*PRIVATE KEY-----'
  'AKIA[0-9A-Z]{16}'
  'gh[pousr]_[A-Za-z0-9]{36,}'
  'github_pat_[A-Za-z0-9_]{40,}'
  'xox[abprs]-[A-Za-z0-9-]{10,}'
  'AIza[0-9A-Za-z_-]{35}'
  'sk_live_[0-9A-Za-z]{16,}'
  # A RelaySMS device credential: "<device uuid>.<43 base64url characters>".
  '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.[A-Za-z0-9_-]{43}'
  # A database URL with a real-looking password (placeholders and test hosts are allowed).
  'postgres(ql)?://[A-Za-z0-9_]+:[^@[:space:]"'"'"'$<{]{8,}@'
  # Signing passwords written into Gradle files.
  '(storePassword|keyPassword)[[:space:]]*=[[:space:]]*"[^"]+"'
)
# Placeholders that are allowed to appear where a password would.
placeholder=':(CHANGE_ME|password|relaysms_ci)@$'
for pattern in "${patterns[@]}"; do
  hits=$(git grep -nIoE -e "$pattern" -- . ':!server/package-lock.json' |
    grep -Ev "$placeholder" | cut -d: -f1,2 | sort -u || true)
  # Deliberate non-secrets: tests that check configuration values are never printed.
  hits=$(echo "$hits" | grep -Ev '^server/tests/(config|startup)\.test\.ts:' || true)
  if [ -n "$hits" ]; then
    echo "Possible credential (pattern: ${pattern:0:24}...):"
    echo "$hits" | sed 's/^/  /'
    status=1
  fi
done

if [ "$status" -eq 0 ]; then
  echo "secret scan: no findings in $(git ls-files | wc -l) tracked files"
fi
exit "$status"
