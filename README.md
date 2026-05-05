# LogSlim

**Lossless log compression that fits 9 MB of logs into 1.7 MB — without losing a single line.**

LogSlim is a CLI tool that extracts repeating log templates, separates the variable parameters, and stores everything in compressed Parquet files. Every original log line is exactly reconstructable on demand. It sits in front of your existing storage — no agent, no SDK changes, no vendor lock-in.

---

## Benchmarks

| Log file | Original | After compact | Reduction |
|----------|----------|---------------|-----------|
| `app_logs.log` (100k lines) | 7.35 MB | **1.49 MB** | **80%** |
| `app.log` (100k lines) | 9.10 MB | **1.71 MB** | **81%** |

Compression improves with log repetition. Files with fewer distinct templates (57 vs 159) compress further because DuckDB's dictionary encoding on `template_id` becomes even more efficient.

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

4. **`logslim compact`** exports `log_entries` and `raw_logs` to Parquet files and replaces the tables with UNION ALL views. The database stays queryable and writable after compaction.

---

## Installation

**Requirements:** Java 17+

```bash
# Build from source
git clone https://github.com/your-org/logslim
cd logslim
mvn clean package -q
alias logslim="java -jar $(pwd)/target/logslim-2.0.0.jar"
```

By default LogSlim reads and writes `logs.duckdb` in the current directory. Override with `-Dlogslim.db.path=`:

```bash
java -Dlogslim.db.path=/var/log/myapp.duckdb -jar logslim-2.0.0.jar run --input /var/log/myapp.log
```

---

## Usage

### Ingest logs

```bash
logslim run --input /var/log/app.log

# From stdin
cat /var/log/app.log | logslim run --input -
```

### Compress to Parquet

```bash
logslim compact --yes
```

Exports all data to `logs_data/` as Parquet (zstd), shrinks the `.duckdb` file to view metadata only (~0.26 MB). All query commands continue to work unchanged after compaction.

### List top templates

```bash
logslim templates --limit 10

# Filter by time window
logslim templates --last 1h --limit 5
```

```
ID      OCCURRENCES  PATTERN
------------------------------------------------------------------------
[18  ]  4580        {ts} {time} | INFO | {num} | {num} {num} successfully
[1   ]  4025        {ts} {time} | {num} | {num} | Cache {num} for key {num}
[2   ]  3894        {ts} {time} | INFO | {num} | Circuit breaker {num} for service {num}
```

### Inspect a template

```bash
logslim inspect 18 --recent 5
```

Shows the template pattern, total hit count, and the most recent matching log entries with their parameter values.

### Query by pattern and parameter values

```bash
# All logs matching this template
logslim query "User {id} failed login" --last 24h

# Filter to a specific parameter value
logslim query "User {id} failed login" --filter id=456 --last 24h
```

### Replay original logs

```bash
# Reconstruct and print all logs from the last hour, in timestamp order
logslim replay --last 1h

# All logs ever stored
logslim replay --last 9999d
```

Output is byte-exact — identical to the original log lines including multi-line stack traces.

### Clear all data

```bash
logslim clear --yes
```

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
mvn clean test   # all 88 tests must pass
```

The test suite covers:
- Drain algorithm correctness (lock transitions, novel token discovery, bootstrap)
- End-to-end pipeline with 10k synthetic logs across 5 templates
- Lossless reconstruction including multi-line entries
- Parameter filtering and temporal ordering

If you're adding a new feature, add integration tests in `src/test/java/com/logslim/integration/` that verify the invariants that matter most: **losslessness**, **correct grouping**, and **temporal order**.

Open an issue first for large changes so we can discuss the approach before you invest the time.
