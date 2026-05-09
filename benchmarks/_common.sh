# Shared helpers for benchmark scripts. Source this file.
# Defines: ROOT, JAR, BENCH_DIR, BENCH_DB, BENCH_DATA, now_ms, mb,
# fresh_db, ensure_jar.

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[1]}" )" && pwd )"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
JAR="$ROOT/target/logslim-1.0.0.jar"
BENCH_DIR="$SCRIPT_DIR/.work"
BENCH_DB="$BENCH_DIR/bench.duckdb"
BENCH_DATA="$BENCH_DIR/bench_data"

mkdir -p "$BENCH_DIR"

now_ms() {
    python3 -c 'import time; print(int(time.monotonic_ns() // 1_000_000))'
}

# Bytes → MB (integer, two decimals)
mb() {
    python3 -c "print(f'{int($1)/1024/1024:.2f}')"
}

ensure_jar() {
    if [ ! -f "$JAR" ]; then
        echo "ERROR: $JAR not found. Build first: mvn package -DskipTests -q" >&2
        exit 1
    fi
}

fresh_db() {
    rm -rf "$BENCH_DB" "$BENCH_DATA" "$BENCH_DB.wal" 2>/dev/null || true
}

# Run logslim with the bench DB path. Quiet by default (stdout suppressed).
run_logslim() {
    java -Dlogslim.db.path="$BENCH_DB" -jar "$JAR" "$@"
}
