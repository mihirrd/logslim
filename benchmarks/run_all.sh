#!/usr/bin/env bash
# Run the offline benchmarks (no server required) in sequence.
# For API latency, run ./04_query.sh against a running server separately.
#
# Usage:  ./run_all.sh [LINES]    (default: 100000)
set -euo pipefail
DIR="$(dirname "$0")"
LINES="${1:-100000}"

echo "=========================================="
echo "  LogSlim benchmarks  (lines=$LINES)"
echo "=========================================="
echo
"$DIR/01_ingest.sh"  "$LINES"
echo
"$DIR/02_compress.sh" "$LINES"
echo
"$DIR/03_compact.sh"  "$LINES"
echo
echo "(skip 04_query.sh — requires `logslim serve` running; run separately.)"
