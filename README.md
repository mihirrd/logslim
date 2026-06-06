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
git clone https://github.com/mihirrd/logslim
cd logslim
mvn clean package -q
alias logslim="java -jar $(pwd)/target/logslim-1.0.0.jar"
```

By default LogSlim reads and writes `logs.duckdb` in the current directory. Override with `-Dlogslim.db.path=`:

```bash
java -Dlogslim.db.path=/var/log/myapp.duckdb -jar logslim-1.0.0.jar run --input <file>
```

---

## Docker

Pull the latest image:

```bash
docker pull mihirrd/logslim:latest
```

In production, pin to a specific version rather than `latest` to avoid unexpected changes:

```bash
docker pull mihirrd/logslim:1.3.0 
```

LogSlim stores state in a DuckDB file and a Parquet data directory. Always mount a host directory so data persists across container restarts:

```bash
mkdir -p ./data
```

Set up a shell alias to avoid repeating the `docker run` boilerplate:

```bash
alias logslim='docker run --rm -v $(pwd)/data:/data mihirrd/logslim:latest'
```

All examples below assume this alias. The database is written to `./data/logs.duckdb` on the host.

### Ingest a log file

```bash
logslim run --input /data/app.log
```

The file must be inside the mounted volume so the container can reach it. Copy it first if needed:

```bash
cp /var/log/app.log ./data/
logslim run --input /data/app.log
```

### Compact to Parquet

```bash
logslim compact --yes
```

Exports all data to `/data/logs_data/` as compressed Parquet and shrinks the `.duckdb` file to metadata only. Run this periodically to reclaim disk space.

### Start the API server

```bash
docker run --rm \
  -v $(pwd)/data:/data \
  -p 8080:8080 \
  mihirrd/logslim:latest serve
```

The `-p 8080:8080` flag exposes the API on the host. The dashboard at `http://localhost:3000` connects to it automatically.

### Query templates

```bash
logslim templates --limit 10
logslim templates --search "failed login"
logslim templates --last 1h --limit 5
```

### Inspect a template

```bash
logslim inspect <template-id> --recent 10
```

### Query by pattern

```bash
logslim query "User {id} failed login" --last 24h
logslim query "User {id} failed login" --filter id=456 --last 24h
```

### Replay original logs

```bash
logslim replay --last 1h
logslim replay --from 2024-01-15T00:00:00Z --to 2024-01-15T23:59:59Z
```

### Continuous ingestion from Kafka

If Kafka is running on the host machine:

```bash
docker run --rm \
  -v $(pwd)/data:/data \
  --network host \
  mihirrd/logslim:latest \
  consume \
  --topic app-logs \
  --bootstrap-servers localhost:9092 \
  --batch-size 5000 \
  --flush-interval PT5S \
  --compact-interval PT10M
```

If Kafka is running in another Docker container, connect them via a shared network:

```bash
docker run --rm \
  -v $(pwd)/data:/data \
  --network <kafka-network> \
  mihirrd/logslim:latest \
  consume \
  --topic app-logs \
  --bootstrap-servers <kafka-container-name>:9092
```

Find the network name with:

```bash
docker inspect <kafka-container-name> --format '{{json .NetworkSettings.Networks}}' | jq 'keys[]'
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

---

## License

MIT — see [LICENSE](LICENSE).
