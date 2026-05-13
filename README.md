# LogSlim

**Lossless log compression that saves storage upto 80% — without losing a single line.**

LogSlim is a CLI tool and web dashboard that extracts repeating log templates, separates the variable parameters, and stores everything in *compressed Parquet files*. 

Every original log line is exactly reconstructable on demand. 

Sits in front of your existing storage. No agent, No SDK changes, No vendor lock-in.

---

![Product Preview](preview.gif)

---

## How It Works

```
Raw log line:  "2024-01-15 10:23:45 DEBUG 1234 DB SELECT table=sessions 5 rows 12ms"
               ↓  Drain algorithm
Template:      "{ts} DEBUG {num} DB SELECT table=sessions {rows} {duration}"
Parameters:    ["2024-01-15 10:23:45", "1234", "5 rows", "12ms"]
```

1. **Drain algorithm** learns which token positions are dynamic by observing variation across lines — no regex configuration needed. Novel token formats (like `req-755556`) are discovered automatically after seeing two different values.

2. **Pre-masking** handles well-known token types (numbers, UUIDs, IPs, hashes) immediately on the first occurrence, so common patterns lock after a single line.

3. **Storage** separates templates from parameters. A template seen 5,000 times is stored once; only the 4 variable values per occurrence are stored. DuckDB's columnar encoding + zstd compression handles the rest.

4. **`logslim compact`** exports `log_entries` and `raw_logs` to Parquet files and replaces the tables with UNION ALL views. The database stays queryable after compaction.

---

## Installation

**Requirements:** Java 17+, Node.js 18+ (dashboard only)

```bash
# Build from source
git clone https://github.com/<username>/logslim
cd logslim
mvn clean package -q
alias logslim="java -jar $(pwd)/target/logslim-1.0.0.jar"
```

By default LogSlim reads and writes `logs.duckdb` in the current directory. Override with `-Dlogslim.db.path=`:

```bash
java -Dlogslim.db.path=/var/log/myapp.duckdb -jar logslim-1.0.0.jar run --input <file>
```

---

## Web Dashboard

LogSlim ships with a Next.js dashboard for exploring logs without the CLI.

```bash
# Terminal 1 — start the API server
logslim serve
# → LogSlim API server running on http://localhost:8080

# Terminal 2 — start the dashboard
cd dashboard && npm install && npm run dev
# → http://localhost:3000
```

The dashboard exposes all CLI operations through a browser UI:

- **Dashboard** — storage stats and inline query with pattern search, slot filters, and "did you mean?" suggestions
- **Templates** — searchable table with hit counts and last-seen timestamps; click any row to inspect
- **Inspect** — per-slot parameter stats (top values, distinct count) and reconstructed recent log lines
- **Replay** — relative window (last 1h, 1d…) or absolute range with a calendar and clock picker
- **Ingest** — paste log content directly into the browser
- **Settings** — compact database to Parquet or clear all data, both with confirmation dialogs

The server reads only the compacted Parquet snapshot in `logs_data/`, so `logslim serve` can run concurrently with `logslim run` / `logslim consume` (the writer owns `logs.duckdb`). On first startup the server auto-bootstraps an empty Parquet snapshot if one doesn't exist yet — no manual `logslim compact` step needed. The dashboard sees new data after each subsequent compact.

---
## Getting started

Time to ingest logs! Make sure you start the api server and the dashboard. 
Use the following command to ingest logs into logslim. 

### Ingest logs: 

```bash
logslim run --input <file>
```

Logs will not be visible on the dashboard, until compacted. Since the dashboard queries for the 
compacted views, run the below command to compact the logs

### compact logs:

```bash
logslim compact
```

You should now be able to check the logs on the dashboard. 

P.S. You can check the size of *logs_data* + logs.duckdb file and compare it to the size of the original log file. 

- Click on templates to inspect them. It will show you top 10 recent logs matching that template.
- Use *Replay* tab to reconstruct logs in a specific time window. 


---

## CLI Usage

### Ingest logs

```bash
logslim run --input <file>
```

### Continuous ingestion from Kafka

For real-time log shipping, `logslim consume` runs as a daemon: it subscribes to a Kafka topic, buffers records, flushes via the same batched extraction pipeline, and periodically compacts so the on-disk live tail stays small.

```bash
logslim consume \
  --topic app-logs \
  --bootstrap-servers kafka-broker:9092 \
  --group-id logslim-prod \
  --batch-size 5000 \
  --flush-interval PT5S \
  --compact-interval PT10M
```

The consumer runs single-threaded — poll, write, and compact share the same thread, matching DuckDB's one-writer-per-process constraint. Offsets are committed only after a batch is successfully written (**at-least-once**); if the JVM crashes mid-batch, the next run re-consumes from the last committed offset.

`--from-beginning` resets the consumer to the earliest available offset on subscribe (otherwise the default is `latest`). The default `--source kafka` populates the `source` column for any record that lands in `raw_logs` (i.e., pre-lock during Drain's learning phase).

`SIGINT` triggers a graceful shutdown: in-flight batch flushed, offsets committed, final compact run, then exit.

### Compress to Parquet

```bash
logslim compact --yes
```

Exports all data to `logs_data/` as Parquet (zstd), shrinks the `.duckdb` file to view metadata only (~0.26 MB). All query commands continue to work unchanged after compaction.

### List top templates

```bash
logslim templates --limit 10

# Filter by keyword
logslim templates --search "failed login"

# Filter by time window
logslim templates --last 1h --limit 5
```

```
ID      HITS   LAST SEEN     PATTERN
------------------------------------------------------------------------------
[18  ]  4580   2 min ago    {ts} {time} | INFO | {num} | {num} {num} successfully
[1   ]  4025   5 min ago    {ts} {time} | {num} | {num} | Cache {num} for key {num}
[2   ]  3894   12 min ago   {ts} {time} | INFO | {num} | Circuit breaker {num} for service {num}
```

### Inspect a template

```bash
logslim inspect 18 --recent 5
```

Shows the template pattern, total hit count, per-slot parameter stats (top values and distinct count), and the most recent matching log entries reconstructed to their original form.

### Query by pattern and parameter values

```bash
# All logs matching this template
logslim query "User {id} failed login" --last 24h

# Filter to a specific parameter value
logslim query "User {id} failed login" --filter id=456 --last 24h
```

If no template matches, LogSlim prints "Did you mean?" suggestions based on the closest patterns in the database.

### Replay original logs

```bash
# Reconstruct and print all logs from the last hour, in timestamp order
logslim replay --last 1h

# Absolute time range
logslim replay --from 2024-01-15T00:00:00Z --to 2024-01-15T23:59:59Z

# All logs ever stored
logslim replay --last 9999d
```

Output is byte-exact — identical to the original log lines including multi-line stack traces.

---

## Benchmarks

Real-world numbers from a single Apple Silicon laptop (Java 17, DuckDB 1.1.3).
Reproducible end-to-end via `benchmarks/run_all.sh` — see `benchmarks/README.md`.

### Compression (100,000 synthetic log lines, 10 templates, ~7 MB raw)

| Stage | Size |
|---|---|
| Source `.log` file | 7.30 MB |
| After ingest (`.duckdb`) | 16.26 MB |
| After `compact` (`.duckdb` + Parquet) | **1.71 MB** |
| Reduction vs source | **76.5%** |

Compression improves with log repetition. Files with fewer distinct templates compress 
further because DuckDB's dictionary encoding on `template_id` becomes even more efficient. 


| Log file | Original | After compact | Reduction |
|----------|----------|---------------|-----------|
| `app_logs.log` (100k lines) | 7.35 MB | 1.49 MB | 80% |
| `app.log` (100k lines) | 9.10 MB | 1.71 MB | 81% |


---

## Key Properties

**Lossless** — every log line is exactly reconstructable. Multi-line entries (stack traces, `Caused by:` chains) are stored and replayed intact.

**Zero configuration** — no regex patterns to write. The Drain algorithm learns dynamic token positions from the data itself.

**Queryable after compression** — `logslim compact` replaces tables with DuckDB views over Parquet files. All query commands work without decompressing anything.

**Transparent** — LogSlim is a preprocessing layer. Feed it logs, query them back. No changes to your application or log format required.

---

## Tuning

| Property | Default | Description |
|----------|---------|-------------|
| `logslim.drain.lock-after-n` | `10` | Occurrences before a cluster is committed as a template. Lower = faster compression, slightly noisier templates. |
| `logslim.drain.sim-threshold` | `0.5` | Token similarity required to merge a line into an existing cluster (0–1). Higher = stricter grouping. |
| `logslim.template.max-count` | `100000` | Cap on distinct templates before falling back to raw storage. |
| `logslim.storage.batch-insert-size` | `500` | Rows per batch insert. |
| `logslim.db.path` | `logs.duckdb` | Path to the DuckDB database file. |

Set via `-D` flags or `application.properties`:

```bash
java -Dlogslim.drain.lock-after-n=5 -jar logslim-2.0.0.jar run --input app.log
```

---

## Contributing

Pull requests are welcome. Before submitting:

```bash
mvn clean test   # all tests must pass
```

The test suite covers:
- Drain algorithm correctness (lock transitions, novel token discovery, bootstrap)
- End-to-end pipeline with 10k synthetic logs across 5 templates
- Lossless reconstruction including multi-line entries
- Parameter filtering and temporal ordering

If you're adding a new feature, add integration tests in `src/test/java/com/logslim/integration/` that verify the invariants that matter most: **losslessness**, **correct grouping**, and **temporal order**.

Open an issue first for large changes so we can discuss the approach before you invest the time.
