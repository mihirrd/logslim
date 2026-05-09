#!/usr/bin/env bash
# Measure end-to-end compression ratio: raw log file → DuckDB → compacted Parquet.
#
# Usage:  ./02_compress.sh [LINES]    (default: 100000)
set -euo pipefail
source "$(dirname "$0")/_common.sh"
ensure_jar

LINES="${1:-100000}"
LOG="$BENCH_DIR/synthetic.log"

echo ">> Generating $LINES lines..."
python3 "$(dirname "$0")/gen.py" "$LINES" > "$LOG"
SRC_BYTES=$(stat -f %z "$LOG" 2>/dev/null || stat -c %s "$LOG")

fresh_db
echo ">> Ingesting..."
run_logslim run --input "$LOG" >/dev/null
DB_BYTES=$(stat -f %z "$BENCH_DB" 2>/dev/null || stat -c %s "$BENCH_DB")

echo ">> Compacting..."
run_logslim compact -y >/dev/null
DB_AFTER=$(stat -f %z "$BENCH_DB" 2>/dev/null || stat -c %s "$BENCH_DB")
PQ_BYTES=$(find "$BENCH_DATA" -name "*.parquet" -exec stat -f %z {} \; 2>/dev/null \
            | awk '{s+=$1} END {print s+0}')
[ "$PQ_BYTES" = "0" ] && PQ_BYTES=$(find "$BENCH_DATA" -name "*.parquet" -exec stat -c %s {} \; \
                                      | awk '{s+=$1} END {print s+0}')
TOTAL_AFTER=$((DB_AFTER + PQ_BYTES))

ratio() { python3 -c "print(f'{(1 - $1/$2) * 100:.1f}%')"; }

echo
echo "=== COMPRESSION ==="
printf "  %-30s %12s\n" "Source log file:"          "$(mb "$SRC_BYTES") MB"
printf "  %-30s %12s\n" "After ingest (.duckdb):"   "$(mb "$DB_BYTES") MB"
printf "  %-30s %12s\n" "After compact (.duckdb):"  "$(mb "$DB_AFTER") MB"
printf "  %-30s %12s\n" "After compact (Parquet):"  "$(mb "$PQ_BYTES") MB"
printf "  %-30s %12s\n" "After compact (total):"    "$(mb "$TOTAL_AFTER") MB"
echo
printf "  %-30s %12s\n" "Reduction (vs source):"    "$(ratio "$TOTAL_AFTER" "$SRC_BYTES")"
printf "  %-30s %12s\n" "Reduction (vs ingested):"  "$(ratio "$TOTAL_AFTER" "$DB_BYTES")"
