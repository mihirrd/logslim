#!/usr/bin/env bash
# Measure ingestion throughput.
#
# Usage:  ./01_ingest.sh [LINES]    (default: 100000)
set -euo pipefail
source "$(dirname "$0")/_common.sh"
ensure_jar

LINES="${1:-100000}"
LOG="$BENCH_DIR/synthetic.log"

echo ">> Generating $LINES lines..."
python3 "$(dirname "$0")/gen.py" "$LINES" > "$LOG"
SRC_BYTES=$(stat -f %z "$LOG" 2>/dev/null || stat -c %s "$LOG")
SRC_MB=$(mb "$SRC_BYTES")
echo "   Source: ${SRC_MB} MB (${LINES} lines)"

fresh_db
echo ">> Ingesting..."
T0=$(now_ms)
run_logslim run --input "$LOG" >/dev/null
T1=$(now_ms)

ELAPSED_MS=$((T1 - T0))
[ "$ELAPSED_MS" -lt 1 ] && ELAPSED_MS=1
LPS=$(python3 -c "print(int($LINES * 1000 / $ELAPSED_MS))")
MBS=$(python3 -c "print(f'{$SRC_BYTES * 1000 / $ELAPSED_MS / 1024 / 1024:.2f}')")

echo
echo "=== INGEST ==="
printf "  %-18s %s\n" "Source size:"   "${SRC_MB} MB"
printf "  %-18s %s\n" "Lines:"         "${LINES}"
printf "  %-18s %s\n" "Wall time:"     "${ELAPSED_MS} ms"
printf "  %-18s %s\n" "Throughput:"    "${LPS} lines/s, ${MBS} MB/s"
