# LogSlim benchmarks

Simple bash + Python scripts for measuring real-world LogSlim performance.

## Quick start

```bash
mvn package -DskipTests -q              # build the JAR once
chmod +x benchmarks/*.sh
benchmarks/run_all.sh                   # ingest + compress + compact, default 100k lines
benchmarks/run_all.sh 1000000           # 1M lines
```

For API latency, the dashboard server has to be running and pointed at a
*compacted* database (the server reads `logs_data/*.parquet` only). Run the
serve from your real `logs.duckdb`, not the benchmark DB:

```bash
java -jar target/logslim-1.0.0.jar serve &
benchmarks/04_query.sh
```

The benchmark scripts use a separate path (`benchmarks/.work/bench.duckdb`)
so they never touch your real database.

## What each script measures

| Script | What | Why it matters |
| --- | --- | --- |
| `01_ingest.sh` | lines/sec, MB/sec for `logslim run --input` | Tells you how fast a single ingest pass can chew through a log file |
| `02_compress.sh` | size of source log → `.duckdb` after ingest → `.duckdb` + Parquet after compact | The product's headline value: end-to-end compression ratio |
| `03_compact.sh` | wall time of first compact, and of re-compact after a 10% live-tail append | The cost of moving the live data into the immutable archive |
| `04_query.sh` | latency of every dashboard endpoint (5 runs each: min/median/max) | What the user actually sees in the UI |

## Generator (`gen.py`)

`gen.py N` writes `N` deterministic log lines (10 templates mixed,
realistic-ish IDs/IPs/timestamps). Same seed each run, so benchmark numbers
are repeatable. Pipe straight to `--input`:

```bash
python3 benchmarks/gen.py 500000 | java -jar target/logslim-1.0.0.jar run --input -
```

## Sample sizes

JVM + Spring startup is ~5–10 seconds before any work happens. For the
ingest/compact numbers to be meaningful, use **≥100,000 lines** so the
fixed startup cost is amortised. Below that, you're mostly measuring
Java warmup. The 1M default for serious runs is recommended.

## Reading the numbers

- **Ingest throughput** scales sub-linearly with template diversity. The
  generator's 10 templates lock quickly, so most lines hit the fast path.
  Real production logs with hundreds of templates will be slower per line
  during the learning phase, then catch up once Drain stabilises.
- **Compression ratio** improves with template repetition. A workload with
  thousands of distinct one-shot patterns compresses worse than the steady-state
  service log this generator produces.
- **Compact time** is dominated by Parquet write speed (zstd encoding).
  Re-compact reads through the unified view (archive Parquet + live tail) and
  writes a single new Parquet — cost is roughly proportional to total row
  count, not to the size of the new tail.
- **Query latency**: `/api/stats` is the floor (one COUNT per table); replay
  scales with the row cap (`limit=N`).

## Cleanup

```bash
rm -rf benchmarks/.work
```
