#!/usr/bin/env bash
# Runs the Android JVM unit and integration tests against a real relay.
#   scripts/integration-test.sh [extra gradle args]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
exec "$ROOT/scripts/with-relay.sh" bash -c 'cd "$0/android" && bash ./gradlew :app:testDebugUnitTest --rerun-tasks "$@"' "$ROOT" "$@"
