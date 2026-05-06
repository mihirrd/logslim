# LogSlim — Continuous Ingestion + Periodic Compaction

## Context

Currently `logslim compact` makes the database permanently read-only: it drops all three tables and replaces them with Parquet-backed views, so a subsequent `logslim run` can't append new data. The goal is a **hot/cold architecture** where compaction rotates data out to dated Parquet segments while keeping the hot tables writable — enabling `logslim run` to keep appending and `logslim compact` to be called any number of times without interrupting ingestion.

A `--tail` flag is added to `logslim run` so it can follow a live log file (like `tail -f`) instead of reading it once and exiting.

---

## Architecture

```
logs.duckdb (always mutable)
  templates          — permanent table; never compacted (tiny: ~5 KB)
  log_entries_hot    — writable ring buffer for new entries
  raw_logs_hot       — writable ring buffer for new raw lines
  log_entries        — UNION ALL view: hot + all compacted Parquet segments
  raw_logs           — UNION ALL view: hot + all compacted Parquet segments

logs_data/
  segment_20240115_103000/
    log_entries.parquet
    raw_logs.parquet
  segment_20240115_140000/
    log_entries.parquet
    raw_logs.parquet
```

**compact** rotates the hot tables into a new dated segment, TRUNCATEs them, and rebuilds the UNION ALL views. The hot tables stay intact so `run` can keep writing immediately after.

---

## Files to Change

| File | Change |
|------|--------|
| `src/main/resources/schema.sql` | Rename tables to `_hot`; add initial views for `log_entries` and `raw_logs` |
| `src/main/java/com/logslim/storage/LogEntryDao.java` | INSERT targets `log_entries_hot`; SELECTs unchanged (query views) |
| `src/main/java/com/logslim/storage/RawLogDao.java` | INSERT targets `raw_logs_hot`; SELECTs unchanged |
| `src/main/java/com/logslim/cli/CompactCommand.java` | Rotate to dated segment, TRUNCATE hot tables, rebuild UNION ALL views |
| `src/main/java/com/logslim/cli/RunCommand.java` | Add `--tail` flag + file-tail loop |
| `src/main/java/com/logslim/cli/ClearCommand.java` | Delete from `log_entries_hot` and `raw_logs_hot` instead of views |
| `src/test/java/com/logslim/extraction/DrainLearningTest.java` | `@BeforeEach` deletes target hot tables |
| `src/test/java/com/logslim/integration/EndToEndPipelineTest.java` | Same |

---

## Step 1 — `schema.sql`

Rename the two mutable log tables to `_hot`, and add initial views that schema init creates on a fresh database. Because `spring.sql.init.continue-on-error=true` is set, `CREATE VIEW` statements silently fail if the views already exist (e.g. after compact rebuilt them as UNION ALL) — preserving the compacted views on restart.

```sql
-- sequences (unchanged)
CREATE SEQUENCE IF NOT EXISTS templates_id_seq    START 1;
CREATE SEQUENCE IF NOT EXISTS raw_logs_id_seq     START 1;
CREATE SEQUENCE IF NOT EXISTS log_entries_id_seq  START 1;

-- permanent templates table (unchanged)
CREATE TABLE IF NOT EXISTS templates (
    template_id BIGINT  DEFAULT nextval('templates_id_seq') PRIMARY KEY,
    pattern     TEXT    NOT NULL UNIQUE,
    occurrences BIGINT  NOT NULL DEFAULT 1,
    created_at  TEXT    NOT NULL,
    updated_at  TEXT    NOT NULL
);

-- hot tables (writable, truncated by compact)
CREATE TABLE IF NOT EXISTS raw_logs_hot (
    log_id        BIGINT DEFAULT nextval('raw_logs_id_seq') PRIMARY KEY,
    content       TEXT   NOT NULL,
    log_timestamp TEXT   NOT NULL,
    source        TEXT,
    created_at    TEXT   NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_raw_hot_ts ON raw_logs_hot(log_timestamp);

CREATE TABLE IF NOT EXISTS log_entries_hot (
    entry_id          BIGINT DEFAULT nextval('log_entries_id_seq') PRIMARY KEY,
    template_id       BIGINT NOT NULL REFERENCES templates(template_id),
    log_timestamp     BIGINT NOT NULL,
    parameter_values  TEXT   NOT NULL DEFAULT '[]',
    continuation_text TEXT
);
CREATE INDEX IF NOT EXISTS idx_le_hot_template  ON log_entries_hot(template_id);
CREATE INDEX IF NOT EXISTS idx_le_hot_timestamp ON log_entries_hot(log_timestamp);

-- Initial views (silently skipped by continue-on-error if compact has rebuilt them as UNION ALL)
CREATE VIEW log_entries AS SELECT * FROM log_entries_hot;
CREATE VIEW raw_logs    AS SELECT * FROM raw_logs_hot;
```

---

## Step 2 — `LogEntryDao.java`

Change only the two INSERT statements to target `log_entries_hot`. All SELECT methods stay unchanged — they query through the `log_entries` view.

```java
// insert()
"INSERT INTO log_entries_hot (template_id, log_timestamp, parameter_values, continuation_text) ..."

// insertBatch()
"INSERT INTO log_entries_hot (template_id, log_timestamp, parameter_values, continuation_text) ..."
```

---

## Step 3 — `RawLogDao.java`

Same pattern — INSERT targets `raw_logs_hot`, SELECT is unchanged.

```java
"INSERT INTO raw_logs_hot (content, log_timestamp, source, created_at) ..."
```

---

## Step 4 — `CompactCommand.java`

Replace the current drop-and-replace logic with a rotate-and-truncate approach:

1. Create a dated segment directory: `{dataDir}/segment_{yyyyMMdd_HHmmss}/`
2. Export `log_entries_hot` → `segment/log_entries.parquet` (skip if table is empty)
3. Export `raw_logs_hot` → `segment/raw_logs.parquet` (skip if table is empty)
4. `TRUNCATE log_entries_hot` and `TRUNCATE raw_logs_hot`
5. Discover all `log_entries.parquet` paths under `{dataDir}/segment_*/`
6. Rebuild views:
   - If no Parquet files exist: `CREATE OR REPLACE VIEW log_entries AS SELECT * FROM log_entries_hot`
   - If Parquet files exist: `CREATE OR REPLACE VIEW log_entries AS SELECT * FROM log_entries_hot UNION ALL SELECT * FROM read_parquet(['/abs/path/seg1/log_entries.parquet', ...])`
   - Same for `raw_logs`
7. `CHECKPOINT`
8. Print size summary: each segment's Parquet sizes + live `.duckdb` size

`templates` is never touched by compact — it stays as a mutable table throughout.

`ClearCommand` must also be updated to `DELETE FROM log_entries_hot` and `DELETE FROM raw_logs_hot`.

---

## Step 5 — `RunCommand.java` (tail mode)

```java
@Option(names = "--tail", description = "Keep watching the input file for new lines (like tail -f). Ctrl+C to stop.")
private boolean tail;
```

When `--tail` is set, replace the one-shot `BufferedReader` loop with a `RandomAccessFile` poll loop:

1. Open `RandomAccessFile` at the input path
2. Loop: read all available complete lines from the current file offset
3. Feed lines to `MultiLineGrouper` + `TemplateExtractor` as normal
4. If no new lines available: sleep 200 ms, then retry
5. On `InterruptedException` or SIGINT: flush any partial group, print total count, exit cleanly

`MultiLineGrouper` currently wraps a `BufferedReader`. Extend it to also accept `Iterable<String>` so the tail loop can feed it lines from `RandomAccessFile.readLine()` without needing a real reader.

Only supported for file input — if `--tail` is combined with `--input -` (stdin), fail with a clear error message.

---

## Step 6 — Test updates

In `DrainLearningTest` and `EndToEndPipelineTest`, update `@BeforeEach` to delete from the hot tables:

```java
jdbc.update("DELETE FROM log_entries_hot", Map.of());
jdbc.update("DELETE FROM raw_logs_hot",    Map.of());
jdbc.update("DELETE FROM templates",       Map.of());
```

`countRows("log_entries")` and `countRows("raw_logs")` still work because they query the views, which point to the now-empty hot tables after the delete.

---

## Verification

```bash
mvn clean test   # all 88 tests must still pass

# Continuous workflow: two ingestion + compact cycles
rm -f logs.duckdb && rm -rf logs_data/

java -jar target/logslim-2.0.0.jar run --input app_logs.log
java -jar target/logslim-2.0.0.jar compact --yes
# → creates logs_data/segment_YYYYMMDD_HHMMSS/, hot tables are now empty

java -jar target/logslim-2.0.0.jar run --input app_logs.log   # second run appends to hot tables
java -jar target/logslim-2.0.0.jar templates --limit 3         # queries across hot + segment_001

java -jar target/logslim-2.0.0.jar compact --yes               # creates segment_002
java -jar target/logslim-2.0.0.jar templates --limit 3         # queries hot + segment_001 + segment_002

# Tail mode smoke test
head -100 app_logs.log > /tmp/live.log
java -jar target/logslim-2.0.0.jar run --input /tmp/live.log --tail &
sleep 1
tail -100 app_logs.log >> /tmp/live.log   # simulate new lines arriving
sleep 1 && kill %1
```
