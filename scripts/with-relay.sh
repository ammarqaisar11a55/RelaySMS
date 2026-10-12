#!/usr/bin/env bash
# Starts a private PostgreSQL and a freshly built relay on a local port, runs the given command
# with RELAY_TEST_URL and RELAY_TEST_PORT set, then stops the relay.
#
#   scripts/with-relay.sh <command> [args...]
#
# Environment: RELAY_TEST_PORT (default 18080), PAIRING_TTL_SECONDS, MESSAGE_TTL_HOURS.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PORT="${RELAY_TEST_PORT:-18080}"

DB_URL="$("$ROOT/scripts/dev-db.sh" start | tail -1)"
ADMIN_URL="${DB_URL%/relaysms}/postgres"
psql "$ADMIN_URL" -qAt -c "DROP DATABASE IF EXISTS relaysms_it" -c "CREATE DATABASE relaysms_it" >/dev/null 2>&1

(
  cd "$ROOT/server"
  [ -d node_modules ] || npm ci --no-audit --no-fund
  npm run -s build
)

LOG="$(mktemp)"
(
  cd "$ROOT/server"
  DATABASE_URL="${DB_URL%/relaysms}/relaysms_it" HOST=127.0.0.1 PORT="$PORT" LOG_LEVEL="${RELAY_LOG_LEVEL:-warn}" \
    RATE_LIMIT_MAX=100000 RATE_LIMIT_REGISTRATION_MAX=10000 RATE_LIMIT_PAIRING_MAX=10000 \
    PAIRING_TTL_SECONDS="${PAIRING_TTL_SECONDS:-300}" MESSAGE_TTL_HOURS="${MESSAGE_TTL_HOURS:-72}" \
    exec node dist/server.js
) >"$LOG" 2>&1 &
SERVER_PID=$!
cleanup() {
  kill "$SERVER_PID" 2>/dev/null || true
  wait "$SERVER_PID" 2>/dev/null || true
  [ -n "${RELAY_KEEP_LOG:-}" ] && cp "$LOG" "$RELAY_KEEP_LOG"
  rm -f "$LOG"
}
trap cleanup EXIT

for _ in $(seq 1 50); do
  curl -fsS "http://127.0.0.1:$PORT/readyz" >/dev/null 2>&1 && break
  sleep 0.2
done
curl -fsS "http://127.0.0.1:$PORT/readyz" >/dev/null || { cat "$LOG"; echo "relay did not start" >&2; exit 1; }

export RELAY_TEST_PID="$SERVER_PID"
export RELAY_TEST_URL="http://127.0.0.1:$PORT" RELAY_TEST_PORT="$PORT" RELAY_TEST_DATABASE_URL="${DB_URL%/relaysms}/relaysms_it"
"$@"
