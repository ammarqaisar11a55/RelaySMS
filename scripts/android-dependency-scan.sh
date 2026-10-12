#!/usr/bin/env bash
# Looks up every library that ships in the release APK in the OSV vulnerability database
# (https://osv.dev). Sends only public Maven coordinates and versions. Requires network access.
#
#   scripts/android-dependency-scan.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
(cd "$ROOT/android" && bash ./gradlew --no-daemon -q :app:dependencies --configuration releaseRuntimeClasspath) >"$TMP/deps.txt"
python3 - "$TMP/deps.txt" <<'PY'
import json, re, sys, urllib.request
coords = set()
for line in open(sys.argv[1]):
    m = re.search(r'([\w.\-]+):([\w.\-]+):([\w.\-]+)(?: -> ([\w.\-]+))?', line)
    if m and not line.strip().endswith('(n)'):
        coords.add((m.group(1) + ':' + m.group(2), m.group(4) or m.group(3)))
coords = sorted(coords)
print(f"{len(coords)} libraries resolved for the release build")
found = 0
for i in range(0, len(coords), 200):
    batch = coords[i:i + 200]
    body = json.dumps({"queries": [{"package": {"ecosystem": "Maven", "name": n}, "version": v} for n, v in batch]}).encode()
    req = urllib.request.Request("https://api.osv.dev/v1/querybatch", data=body, headers={"Content-Type": "application/json"})
    results = json.load(urllib.request.urlopen(req, timeout=60))["results"]
    for (n, v), r in zip(batch, results):
        for vuln in r.get("vulns", []):
            found += 1
            print(f"  VULNERABLE {n}:{v}  {vuln['id']}")
print("no known vulnerabilities" if found == 0 else f"{found} advisories found")
sys.exit(1 if found else 0)
PY
