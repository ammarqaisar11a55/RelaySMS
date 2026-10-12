#!/usr/bin/env bash
# Starts or stops a private, throwaway PostgreSQL cluster for local development and tests.
# It uses PostgreSQL binaries already installed on the machine, needs no root access and does not
# touch any system database. Data lives outside the repository (it needs POSIX permissions).
#
#   scripts/dev-db.sh start    # prints the connection URL
#   scripts/dev-db.sh stop
#   scripts/dev-db.sh destroy  # stop and delete the data
set -euo pipefail
PORT="${RELAYSMS_DEVDB_PORT:-54329}"
DIR="${RELAYSMS_DEVDB_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/relaysms/devdb}"
URL="postgres://relaysms@127.0.0.1:$PORT/relaysms"

bin() {
  if command -v "$1" >/dev/null 2>&1; then command -v "$1"; return; fi
  local candidate
  candidate=$(ls -d /usr/lib/postgresql/*/bin 2>/dev/null | sort -V | tail -1)
  [ -n "$candidate" ] && [ -x "$candidate/$1" ] && { echo "$candidate/$1"; return; }
  echo "PostgreSQL binary '$1' not found. Install PostgreSQL or use infra/compose.yaml." >&2
  exit 1
}

case "${1:-}" in
  start)
    if [ ! -f "$DIR/PG_VERSION" ]; then
      mkdir -p "$DIR"
      chmod 700 "$DIR"
      "$(bin initdb)" -D "$DIR" -U relaysms --auth=trust -E UTF8 >/dev/null
    fi
    if ! "$(bin pg_ctl)" -D "$DIR" status >/dev/null 2>&1; then
      "$(bin pg_ctl)" -D "$DIR" -w -l "$DIR/server.log" \
        -o "-p $PORT -c listen_addresses=127.0.0.1 -c unix_socket_directories=$DIR" start >/dev/null
    fi
    for db in relaysms relaysms_test; do
      "$(bin psql)" -h 127.0.0.1 -p "$PORT" -U relaysms -d postgres -tAc \
        "SELECT 1 FROM pg_database WHERE datname='$db'" | grep -q 1 ||
        "$(bin createdb)" -h 127.0.0.1 -p "$PORT" -U relaysms "$db"
    done
    echo "$URL"
    ;;
  stop)
    "$(bin pg_ctl)" -D "$DIR" -m fast stop >/dev/null 2>&1 || true
    ;;
  destroy)
    "$(bin pg_ctl)" -D "$DIR" -m fast stop >/dev/null 2>&1 || true
    rm -rf "$DIR"
    ;;
  *)
    echo "usage: $0 start|stop|destroy" >&2
    exit 2
    ;;
esac
