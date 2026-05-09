#!/usr/bin/env bash
# Measure API query latency. Requires `logslim serve` to be running.
# The server reads the compacted Parquet snapshot, so the database must
# have been ingested+compacted at the path the server is using
# (default: $ROOT/logs.duckdb, NOT the bench DB).
#
# Usage:  ./04_query.sh [BASE_URL]    (default: http://localhost:8080)
set -euo pipefail

BASE="${1:-http://localhost:8080}"

if ! curl -fs "$BASE/api/stats" >/dev/null; then
    echo "ERROR: server not responding at $BASE/api/stats" >&2
    echo "       Start it first: java -jar target/logslim-1.0.0.jar serve" >&2
    exit 1
fi

# Time a curl, ignoring its body. Returns milliseconds (integer).
timecurl() {
    local url="$1"
    local data="${2:-}"
    local opts=(-s -o /dev/null -w '%{time_total}')
    if [ -n "$data" ]; then
        opts+=(-X POST -H 'Content-Type: application/json' -d "$data")
    fi
    secs=$(curl "${opts[@]}" "$url")
    python3 -c "print(int(float('$secs') * 1000))"
}

# Run 5 times, print min/median/max.
bench() {
    local label="$1"; shift
    local samples=()
    for _ in 1 2 3 4 5; do
        samples+=("$("$@")")
    done
    python3 - "$label" "${samples[@]}" <<'PY'
import sys
label = sys.argv[1]
xs = sorted(int(x) for x in sys.argv[2:])
print(f"  {label:<32} min={xs[0]:>5} ms  med={xs[len(xs)//2]:>5} ms  max={xs[-1]:>5} ms")
PY
}

# Pick a real template id from /api/templates (largest one).
TID=$(curl -fs "$BASE/api/templates?limit=1" \
        | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d[0]["id"]) if d else print(0)')

echo "=== QUERY LATENCY (5 runs each, min/med/max) ==="
bench "GET  /api/stats"                      timecurl "$BASE/api/stats"
bench "GET  /api/templates?limit=20"         timecurl "$BASE/api/templates?limit=20"
bench "GET  /api/templates?search=user"      timecurl "$BASE/api/templates?search=user"
if [ "$TID" != "0" ]; then
    bench "GET  /api/templates/$TID?recent=10" timecurl "$BASE/api/templates/$TID?recent=10"
fi
bench "GET  /api/replay?last=1h&limit=1000"  timecurl "$BASE/api/replay?last=1h&limit=1000"
bench "GET  /api/replay?last=9999d&limit=5000" timecurl "$BASE/api/replay?last=9999d&limit=5000"
bench "GET  /api/suggestions?pattern=login"  timecurl "$BASE/api/suggestions?pattern=login"
bench "POST /api/query (no filter)"          timecurl "$BASE/api/query" '{"pattern":"User {num} login"}'
