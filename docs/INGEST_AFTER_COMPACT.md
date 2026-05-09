# Spec: Ingestion after `compact`

Status: implemented (May 2026)

## Problem

After running `logslim compact`, subsequent `logslim run` invocations failed with:

```
org.springframework.jdbc.UncategorizedSQLException: PreparedStatementCallback;
uncategorized SQLException for SQL [INSERT INTO raw_logs (content, log_timestamp, source, created_at)
VALUES (?, ?, ?, ?) RETURNING log_id];
java.sql.SQLException: Catalog Error: raw_logs is not an table
```

The `compact` flow exported `templates`, `log_entries`, and `raw_logs` to Parquet files, then replaced each base table with a DuckDB view over the Parquet. Views in DuckDB are read-only, so the DAOs' `INSERT INTO …` statements at ingestion time hit `Catalog Error: <name> is not an table`. Compact was effectively a one-way trip — the user could read but never write again unless they deleted `logs.duckdb` and started over, losing their compacted history.

A naïve fix considered in an earlier task — auto-"uncompacting" the database (materialising the views back into base tables on the next `run`) — was rejected because it defeats the entire point of compact (~80% space saving). On every ingestion the user would silently re-inflate the data they just compressed.

We needed a way for ingestion to keep working **without** uncompacting, i.e. keeping the Parquet archive compressed and read-only.

## Goals

1. After compact, `logslim run` succeeds with no special handling and no auto-uncompact.
2. Reads (replay, query, inspect, dashboard) continue to see all data — both pre-compact archive and any new entries.
3. The Parquet archive stays compressed and read-only.
4. Compact stays meaningful: a re-compact merges the new tail into the archive and resets the writable side to empty.
5. `clear` truly wipes everything, including the Parquet files.

## Non-goals

- A dedicated `uncompact` subcommand. Compact is for space saving; un-doing it is not part of the workflow.
- Schema migration for existing un-compacted databases. They stay exactly as they were.
- Frontend changes. The dashboard reads through the unsuffixed names, which is what the new design preserves.

---

## Design — hybrid `archive + live` layout

After **compact**, each of the three core tables becomes a **three-object family**:

```
{name}_archive     -- VIEW over Parquet (immutable, compressed)
{name}_live        -- BASE TABLE (writable, initially empty)
{name}             -- VIEW = {name}_archive UNION ALL {name}_live   (unified read)
```

The unsuffixed name (`templates`, `log_entries`, `raw_logs`) — the one DAO read methods reference — is now a *unified view* over the immutable archive plus a small writable tail. The DAO write methods are taught to target `_live` post-compact.

### Constraint handling across the union

- **AUTOINCREMENT IDs across the union**. The hybrid layout creates an explicit DuckDB sequence per family (`templates_id_seq`, `log_entries_id_seq`, `raw_logs_id_seq`) and starts each at `MAX(archive_id) + 1`. The `_live` table uses `DEFAULT nextval('…_id_seq')` instead of `AUTOINCREMENT`, so newly inserted rows get IDs strictly greater than anything in the archive — no collision possible across the UNION.

- **`templates.pattern UNIQUE`**. UNIQUE can't be enforced across a UNION view, but the application's existing flow already handles it: `TemplateExtractor.resolveTemplate` calls `templateDao.findByPattern` (which reads from the unified view) *before* attempting an insert. If the pattern exists in the archive, the extractor reuses that template_id. Only genuinely new patterns go to `_live`, where the table-level UNIQUE constraint still catches the rare concurrent-insert race.

- **Foreign key `log_entries.template_id → templates.template_id`**. Foreign keys can't reference a view, so the FK is dropped in the live tables. Application logic (Drain extractor flow) maintains referential integrity in practice.

### What happens on re-compact

When the user runs `compact` again on an already-compacted database:

1. `COPY (SELECT * FROM {name}) TO {name}.parquet` — DuckDB reads through the unified view (archive + live) and overwrites the Parquet file with the merged contents.
2. Drop the unified view, archive view, live table, and the live sequence.
3. Capture the new `MAX(id)`, build a fresh three-object layout (live empty, sequence past the new archive max).

`logslim compact` is therefore idempotent over the layout. A user who ingests intermittently and runs `compact` periodically gets a cleanly compressed archive plus a small live tail, indefinitely.

### What happens on clear

`clear` means "wipe everything." The new behaviour:

1. Drop all three unified views.
2. Drop the `_archive` views and `_live` tables.
3. Drop the `*_id_seq` sequences.
4. Delete the Parquet files from the data directory (best-effort `Files.deleteIfExists`).
5. Recreate the original un-compacted base-table schema.

After `clear`, the database is back to exactly the shape a brand-new install would have.

---

## Implementation

### New: `WriteTarget` helper

`src/main/java/com/logslim/storage/WriteTarget.java`

A tiny `@Component` that resolves the right INSERT/UPDATE target table for any of the three base names. It caches `isCompacted()` (a single `information_schema.tables` query) and exposes:

```java
public boolean isCompacted();
public String  tableFor(String base);   // "templates" or "templates_live"
public void    invalidate();            // call on compact / clear
```

Each DAO is injected with `WriteTarget` and builds its INSERT SQL with `writeTarget.tableFor("...")` rather than a hard-coded name.

### `AdminService.compactDatabase` rewrite

`src/main/java/com/logslim/service/AdminService.java`

Idempotent over the hybrid layout. The flow is the same whether the input state is un-compacted or already compacted:

1. `COPY (SELECT * FROM {name}) TO {name}.parquet` for each of the three names — DuckDB transparently handles either base table or unified view as the source.
2. Capture `MAX(id)` for each table (used to seed the new sequences).
3. Tear down whatever's currently in place: drop `_archive` views and `_live` tables (if present), drop the unsuffixed object (table or view), drop the `*_id_seq` sequences (if present).
4. Build a fresh three-object layout (`_archive` view, `_live` table with `DEFAULT nextval(...)`, unified view).
5. `CHECKPOINT` and `writeTarget.invalidate()`.

Helper methods extracted for clarity: `copyToParquet`, `maxId`, `dropHybridLayoutIfPresent`, `dropSequencesIfExist`, `buildHybridLayout`.

### `AdminService.clearDatabase` extended

Now also drops `_archive` views, `_live` tables, sequences, and deletes the three Parquet files. Then calls the existing `recreateTables()` helper to restore the un-compacted schema. Returns the same `ClearResult` record (counts captured before the drops).

To know where the Parquet files live, `AdminService` now reads `${logslim.db.path:logs.duckdb}` directly and derives `<base>_data` itself (instead of taking the path as an argument from the CLI). Callers don't need to change.

### DAO write paths

Three small mechanical edits — every place that previously hard-coded `INSERT INTO templates`, `INSERT INTO log_entries`, `INSERT INTO raw_logs`, or `UPDATE templates …` now uses `writeTarget.tableFor("...")`:

| File | Method | Verb |
| --- | --- | --- |
| `TemplateDao.java` | `insert` | INSERT … RETURNING template_id |
| `TemplateDao.java` | `incrementOccurrences` | UPDATE |
| `LogEntryDao.java` | `insert` | INSERT … RETURNING entry_id |
| `LogEntryDao.java` | `insertBatch` | INSERT (batch) |
| `RawLogDao.java` | `insert` | INSERT … RETURNING log_id |

Read methods (`findByPattern`, `findById`, `findByTimeRange`, etc.) are unchanged — they target the unsuffixed name, which post-compact is the unified view.

### `RunCommand.java`

The auto-uncompact code added during the rejected approach is removed; `RunCommand` is back to its original form.

---

## Trade-offs

- **Archive `occurrences` counts are frozen at compact time.** When ingestion sees a pattern already in the archive, it reuses that template_id (so reconstruction stays correct), but the UPDATE on `templates_live` affects 0 rows — the count visible to the dashboard stays at its compact-time value. A user who cares about an exact running count can re-compact, which merges live back in and re-snapshots the counts.

- **No FK constraint on `log_entries_live.template_id`.** Application logic (Drain learns templates first, then writes log entries that reference them) maintains the invariant. The risk surface is small because all writes go through `TemplateExtractor`.

- **No UNIQUE pattern enforcement across the union view.** Drain's pre-insert pattern lookup catches duplicates from the application side; the table-level UNIQUE on `templates_live` catches the concurrent-insert race. Together they cover the realistic failure modes.

- **Slight read overhead** on the unified view. Every read scans both Parquet and a base table. For typical replay / inspect / templates-list workloads this is negligible (Parquet has dictionary encoding; the live tail is small). If the live tail grows large, the user runs `compact` again to absorb it.

---

## Files changed

| File | Change |
| --- | --- |
| `src/main/java/com/logslim/storage/WriteTarget.java` | **New** — `@Component` resolving the right write-target table name. |
| `src/main/java/com/logslim/storage/TemplateDao.java` | Inject `WriteTarget`. `insert` and `incrementOccurrences` use `writeTarget.tableFor("templates")`. |
| `src/main/java/com/logslim/storage/LogEntryDao.java` | Inject `WriteTarget`. `insert` and `insertBatch` use `writeTarget.tableFor("log_entries")`. |
| `src/main/java/com/logslim/storage/RawLogDao.java` | Inject `WriteTarget`. `insert` uses `writeTarget.tableFor("raw_logs")`. |
| `src/main/java/com/logslim/service/AdminService.java` | Inject `WriteTarget` and `${logslim.db.path}`. Rewritten `compactDatabase` builds the hybrid layout and is idempotent. `clearDatabase` extended to wipe live + archive + Parquet. Old `uncompactDatabase` removed. |
| `src/main/java/com/logslim/cli/RunCommand.java` | Reverted to original form (no auto-uncompact). |
| `src/test/java/com/logslim/reconstruction/LogReconstructorTest.java` | Test fixtures updated for the new `TemplateDao(NamedParameterJdbcTemplate, WriteTarget)` constructor. |

No DB schema migration. Existing un-compacted databases are unaffected.

---

## Verification

```bash
# 1. All tests still pass
mvn test -q                    # 99 tests, 0 failures

# 2. Build, restart server
mvn package -DskipTests -q
pkill -f "logslim-.*\.jar" || true

# 3. Ingest into a compacted DB — the original failure repro
java -jar target/logslim-1.0.0.jar run --input some_new.log
# Expect: ingestion succeeds. No "is not an table" error. Compacted state preserved.

# 4. Confirm the hybrid layout is in place
java -jar target/logslim-1.0.0.jar serve &
sleep 2
# (Use any DuckDB client, or: jdbc:duckdb:logs.duckdb?access_mode=READ_ONLY)
#   SELECT table_name, table_type FROM information_schema.tables
#   WHERE table_name LIKE 'templates%'
#       OR table_name LIKE 'log_entries%'
#       OR table_name LIKE 'raw_logs%'
#   ORDER BY table_name;
# Expect for each base name: a VIEW (unsuffixed), a VIEW (_archive), a BASE TABLE (_live).

# 5. Reads see archive + live transparently
curl -s http://localhost:8080/api/stats
# Expect: counts include both pre-compact rows and any new ingest.

# 6. Re-compact merges live back
java -jar target/logslim-1.0.0.jar compact -y
# After running: _live tables empty; templates.parquet contains everything.

# 7. Clear wipes everything (Parquet included)
java -jar target/logslim-1.0.0.jar clear -f
ls logs_data/ 2>&1                     # expect: empty or "No such file or directory"
java -jar target/logslim-1.0.0.jar run --input fresh.log
# Expect: a fresh, un-compacted database; ingestion works normally.
```

---

## Result

| Scenario | Before | After |
| --- | --- | --- |
| `run` after `compact` | fails with `Catalog Error: <name> is not an table` | succeeds; new rows go to `*_live` |
| Reads after `compact` (replay, inspect, query) | worked already | unchanged — same unified view |
| `compact` after `compact` | worked but redundantly re-exported the same data | merges live tail into archive, resets live to empty |
| `clear` after `compact` | drops only the views, leaves orphaned Parquet on disk | drops everything and removes the data directory |
| Disk footprint | compact saves ~80%; required deleting DB for new ingest | compact saves ~80%; new ingest appends to a small live tail |
