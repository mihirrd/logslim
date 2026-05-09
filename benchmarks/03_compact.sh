#!/usr/bin/env bash
# Measure compact duration. Runs ingest -> compact (first compact),
# then ingest more -> compact (re-compact, merging the live tail).
#
# Usage:  ./03_compact.sh [LINES]    (default: 100000)
set -euo pipefail
source "$(dirname "$0")/_common.sh"
ensure_jar

LINES="${1:-100000}"
LOG="$BENCH_DIR/synthetic.log"
TAIL_LINES=$((LINES / 10))
TAIL_LOG="$BENCH_DIR/synthetic_tail.log"

echo ">> Generating ${LINES} (initial) + ${TAIL_LINES} (tail) lines..."
python3 "$(dirname "$0")/gen.py" "$LINES" > "$LOG"
python3 "$(dirname "$0")/gen.py" "$TAIL_LINES" > "$TAIL_LOG"

fresh_db
echo ">> Ingest 1..."
run_logslim run --input "$LOG" >/dev/null

echo ">> First compact..."
T0=$(now_ms)
run_logslim compact -y >/dev/null
T1=$(now_ms)
FIRST_MS=$((T1 - T0))

echo ">> Ingest 2 (tail)..."
run_logslim run --input "$TAIL_LOG" >/dev/null

echo ">> Re-compact (merge tail into archive)..."
T0=$(now_ms)
run_logslim compact -y >/dev/null
T1=$(now_ms)
RECOMPACT_MS=$((T1 - T0))

echo
echo "=== COMPACT ==="
printf "  %-22s %s\n" "First compact:"  "${FIRST_MS} ms (over ${LINES} rows)"
printf "  %-22s %s\n" "Re-compact:"     "${RECOMPACT_MS} ms (merging ${TAIL_LINES} new rows)"
