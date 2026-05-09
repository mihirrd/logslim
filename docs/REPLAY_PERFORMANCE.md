# Spec: Replay Performance

Status: implemented (May 2026)

## Problem

`GET /api/replay` was very slow against the working dataset (~191 templates, ~15,725 `log_entries`, ~2,945 `raw_logs`). Wall-clock time for a full-window replay was multiple seconds; the response also stalled the dashboard's `LogOutput` component because every line is rendered as a DOM node.

Two compounding issues in the hot path were responsible for almost all the latency:

### Cause 1 — N+1 template lookup

In `src/main/java/com/logslim/reconstruction/LogReconstructor.java`, `reconstruct(LogEntry)` calls `templateDao.findById(entry.getTemplateId())` once per log entry. `TemplateDao.findById` had no caching annotation, so every call issued a fresh `SELECT * FROM templates WHERE template_id = ?`. For a full replay over `log_entries` that meant ~15,725 single-row template lookups in addition to the two range queries.

### Cause 2 — No connection pool in serve mode

`src/main/java/com/logslim/LogSlimApplication.java` sets `spring.datasource.type=org.springframework.jdbc.datasource.DriverManagerDataSource` in `serve` mode. This was added earlier to work around HikariCP's connection-state management calling `Connection.setReadOnly(...)`, which DuckDB's JDBC driver rejects with `SQLFeatureNotSupportedException`. `DriverManagerDataSource` opens a fresh physical DuckDB connection on every `getConnection()`. Combined with Cause 1, a single replay opened **~15,727 connections**. Each DuckDB connection open is non-trivial (file open, metadata read, WAL replay check); multiplied by ~15.7k it dominates total latency.

### Secondary issues

- **No row cap.** `findByTimeRange` had no `LIMIT`; the dashboard's "All time" option (`9999d`) materialised every row into memory and serialised it to JSON.
- **In-memory sort over two pre-sorted lists.** Both DAOs return `ORDER BY log_timestamp ASC`, but `LogQueryService.replayLogs` then called `Collections.sort` on the merged list — O((n+m) log(n+m)) where O(n+m) merge would do.
- **No frontend virtualisation.** `LogOutput.tsx` renders one `<div>` per line; large responses cause layout/paint stalls even after the network is done.

---

## Alternatives considered

### A. Add `@Cacheable` on `TemplateDao.findById` only

Smallest possible change. Annotate `findById` with `@Cacheable(value = "templates", key = "#id")`. After the first replay, all 191 templates are in memory; subsequent replays skip the per-row DB hit.

**Pros**
- One-line change.
- Helps `inspect` and `query` paths automatically.

**Cons**
- The **first** replay is still slow (cache cold).
- Doesn't address the connection-per-call multiplier from Cause 2 — even cached, the cache miss still goes through `DriverManagerDataSource`.
- 191 cache misses × DuckDB connection cost is still slow on cold start.

**Verdict**: necessary but not sufficient. Keep as defence-in-depth.

### B. Replace `DriverManagerDataSource` with HikariCP

Restoring HikariCP would batch connections via the pool, dropping per-call overhead to near zero.

**Pros**
- Solves the connection-per-call problem at the root.

**Cons**
- HikariCP's `ProxyConnection.setReadOnly(...)` delegates straight to the DuckDB driver, which throws `SQLFeatureNotSupportedException` for any state change. This is the exact bug we worked around earlier. Solving it requires either:
  - Custom HikariCP `ConnectionInitSql` and disabling `readOnly` reset (fragile, not documented), or
  - A custom `DataSource` proxy that no-ops `setReadOnly` (more code, more surface area).
- Risk of regressing the earlier fix.

**Verdict**: rejected for now — the bulk-load alternative removes the motivation by reducing connection count from ~15.7k to 3 per request.

### C. Bulk-load all templates referenced by the replay window in one query

Collect distinct `template_id`s from the fetched `log_entries`, fetch them in a single `SELECT * FROM templates WHERE template_id IN (…)`, build a `Map<Long, Template>`, then reconstruct each entry by calling the existing positional reconstructor `reconstruct(String pattern, List<String> paramValues)` — which doesn't touch the DAO at all.

**Pros**
- Drops query count for replay from ~15,727 to **3** (log_entries, raw_logs, templates).
- Doesn't depend on cache warmth — fast on the first call.
- Localised change in `LogQueryService` + one small new method on `TemplateDao`.
- Doesn't change the data source or revisit the HikariCP/DuckDB interaction.

**Cons**
- Adds one new DAO method (`findByIds`).
- Slight duplication with `LogReconstructor.reconstruct(LogEntry)` since we deliberately bypass that overload.

**Verdict**: chosen as the primary fix.

### D. Stream the HTTP response instead of buffering

Use Spring's `StreamingResponseBody` or write JSON in chunks to avoid holding the whole list in memory.

**Pros**
- Smoother memory profile for very large replays.

**Cons**
- Doesn't address the dominant cost (per-row DB lookups).
- Frontend would need to parse incremental JSON, which is non-trivial.
- Becomes unnecessary once the row count is capped.

**Verdict**: not needed once caps are in place.

### E. Frontend virtualisation in `LogOutput.tsx`

Render only the visible window using `react-virtuoso` or similar.

**Pros**
- Significantly improves render perf for any large response.

**Cons**
- Orthogonal to backend latency.
- Changes the component API; selection / search behaviours need re-thinking.

**Verdict**: out of scope for this spec — tracked as a follow-up.

---

## Finalised approach

A combination of **C** (bulk-load) + **A** (cache, defence-in-depth) + a row cap + a small in-memory merge optimisation.

### 1. Bulk-load templates

`LogQueryService.replayLogs(from, to, limit)`:

```text
entries  = logEntryDao.findByTimeRange(from, to, limit)
raws     = rawLogDao.findByTimeRange(from, to, limit)
ids      = distinct template_ids in entries
templates = templateDao.findByIds(ids)              // one IN-list query

for entry in entries:
    t = templates[entry.template_id]
    if t == null: skip                              // referential gap, don't fail
    line = reconstructor.reconstruct(t.pattern, entry.parameter_values)
    if entry.continuation_text: line += "\n" + continuation_text
    entryLines.append((entry.log_timestamp, line))

rawLines = [(raw.log_timestamp, raw.content) for raw in raws]
merged   = two_pointer_merge_by_timestamp(entryLines, rawLines)

if merged.size > limit:
    merged = merged[:limit] + ["[truncated — showing first N lines; …]"]

return merged
```

`reconstructor.reconstruct(String pattern, List<String> paramValues)` already exists and does not touch the DAO — so the only DB work in a replay is now the three `SELECT`s above.

### 2. Cache `findById`

```java
@Cacheable(value = "templates", key = "#id")
public Optional<Template> findById(long id) { … }
```

Spring Boot's default `ConcurrentMapCacheManager` is fine — only ~191 templates total. Templates are immutable enough for caching: `template_id` and `pattern` never change after insert; `occurrences` and `updated_at` do change but reconstruction doesn't read them. The inspect endpoint also benefits.

### 3. Row cap

A new `limit` query parameter on `GET /api/replay`:

- HTTP layer default: `5000`. Clamped to `[1, 50000]` in `LogController.replay`.
- Plumbed down to `LogQueryService.replayLogs(from, to, limit)` and SQL-level `LIMIT :limit` in both DAOs' `findByTimeRange(from, to, limit)` overloads.
- Internal callers using the existing arity (no `limit`) get `Integer.MAX_VALUE` so the integration test (which expects 10,000 rows) still passes.
- If the merged result exceeds `limit`, the response is truncated and a sentinel string is appended:

  ```
  [truncated — showing first 5000 lines; widen filter or narrow time window for more]
  ```

  The dashboard's `LogOutput` component renders this verbatim.

### 4. Two-pointer merge

Replace `Collections.sort` with an O(n+m) merge that interleaves the two pre-sorted lists by timestamp. Small win standalone, but cheap to do in the same edit.

### 5. Dashboard

`dashboard/lib/api.ts` `replay()` accepts an optional `limit` field; the replay page can pass it explicitly when needed. No required change to the page.

---

## Files changed

| File | Change |
| --- | --- |
| `src/main/java/com/logslim/storage/TemplateDao.java` | `@Cacheable("templates")` on `findById`; new `findByIds(Collection<Long>) → Map<Long, Template>`. |
| `src/main/java/com/logslim/storage/LogEntryDao.java` | Overload `findByTimeRange(from, to, limit)` with SQL `LIMIT :limit`. |
| `src/main/java/com/logslim/storage/RawLogDao.java` | Same overload pattern. |
| `src/main/java/com/logslim/query/LogQueryService.java` | New `replayLogs(from, to, limit)` doing bulk-load + merge + truncate; existing arities pass through with `Integer.MAX_VALUE`. |
| `src/main/java/com/logslim/api/LogController.java` | `replay(...)` accepts `?limit=N` (default 5000, clamped). |
| `dashboard/lib/api.ts` | `replay()` accepts optional `limit`. |

No DB schema changes. No migration.

---

## Verification

```bash
# Unit + integration tests still pass
mvn test -q

# Build
mvn package -DskipTests -q

# Restart and benchmark
pkill -f "logslim-.*\.jar" || true
java -jar target/logslim-1.0.0.jar serve &
sleep 2

time curl -s "http://localhost:8080/api/replay?last=9999d&limit=5000" > /tmp/replay.json
python3 -c "import json; d=json.load(open('/tmp/replay.json')); \
  print('rows:', len(d), '| last:', d[-1][:80])"
```

Expected: well under one second; rows ≤ 5000; the last entry is the truncation marker if the underlying window contained more rows.

Cache effect, separate from replay:

```bash
time curl -s "http://localhost:8080/api/templates/1?recent=10" > /dev/null
time curl -s "http://localhost:8080/api/templates/1?recent=10" > /dev/null
# Second call: noticeably faster (template fetched from cache).
```

---

## Trade-offs

- **Cache invalidation.** Templates can be re-ingested by the CLI while the server is running. We rely on the fact that `template_id` and `pattern` are immutable after insert; only fields the reconstructor doesn't read (`occurrences`, `updated_at`) change. If a code change later starts reading `occurrences` from a cached `Template`, it will be stale until a process restart. The cache is per-process, so restart-as-flush is acceptable.
- **Truncation sentinel as a string in the array.** The response shape stays `List<String>` — the dashboard's `LogOutput` component renders it without any code change. The downside is that the truncation signal is in-band; a future refactor could switch to `{lines, truncated, total}` JSON if a stronger contract is needed.
- **Bulk `IN (:ids)` query size.** Worst case the IN list contains every distinct `template_id` in the result window. With the current 5000-row default, that caps the IN list at ~5000 ids — well within DuckDB's limits.
- **HikariCP not restored.** We accept the per-request connection cost of `DriverManagerDataSource` because the request only opens 3 connections now. If future endpoints get chattier, revisit Alternative B with a custom `setReadOnly`-tolerant connection wrapper.

---

## Out of scope / follow-ups

- **Frontend virtualisation** in `LogOutput.tsx` (e.g., `react-virtuoso`) — separate task.
- **HikariCP with a `setReadOnly` shim** — only worth doing if a future feature requires many connections per request.
- **Streaming HTTP responses** — only worth doing if hard caps prove insufficient (unlikely for an interactive dashboard).
- **Structured truncation metadata** — replace the sentinel string with a typed response if/when an API consumer beyond the dashboard appears.

---

## Result

| Metric | Before | After |
| --- | --- | --- |
| JDBC queries per full replay | ~15,727 | 3 |
| New DuckDB connections opened per request | ~15,727 | 3 |
| Replay over 18,670 rows (full dataset) | multiple seconds | sub-second |
| Repeat `/api/templates/{id}` calls | full SQL each time | served from cache after first |
