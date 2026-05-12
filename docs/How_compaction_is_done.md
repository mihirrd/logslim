# How Compaction Actually Works: Streaming, Not Loading

> *A common mental model for log-structured storage is wrong in an instructive way.*

---

When you hear "compaction," the instinct is to picture something like this:

1. Load the entire Parquet archive into RAM
2. Append the new log entries
3. Write it all back out

That's the intuitive model. It's also not what's happening.

## The Actual Architecture

The system maintains two layers:

- **`templates_live`** — an in-process table holding recent writes
- **`templates_archive`** — a view over the on-disk Parquet file

These are unified through a view:

```sql
CREATE VIEW templates AS
  SELECT * FROM templates_archive
  UNION ALL
  SELECT * FROM templates_live;
```

Where `templates_archive` itself is:

```sql
CREATE VIEW templates_archive AS
  SELECT * FROM read_parquet('logs_data/templates.parquet');
```

When `AdminService.compactDatabase` fires, it runs:

```sql
COPY (SELECT * FROM templates) TO 'templates.parquet.new'
  (FORMAT PARQUET, COMPRESSION ZSTD)
```

This looks simple, but the key is what DuckDB's executor does with that query plan.

## The Pipeline, Not the Buffer

DuckDB doesn't materialize the full result set before writing. Instead, it builds a streaming pipeline:

```
[ read_parquet(templates.parquet) ] ──┐
                                       ├──► [ vectorized stream ] ──► [ write templates.parquet.new ]
[ scan(templates_live)            ] ──┘
```

The executor pulls rows through this pipeline in **2048-row vectors**. Each vector flows through the full chain:

1. Read a chunk from the old Parquet file
2. Concatenate with a chunk from `templates_live`
3. Encode into a Parquet row group
4. Emit to disk

Only one vector is in RAM at any point. The full archive is never materialized.

After the write completes, we atomically rename `templates.parquet.new → templates.parquet`, drop the old views, rebuild the layout from the freshly-written file, and clear `templates_live`. Three core tables, same dance each time.

## What This Means in Practice

**Peak RSS is bounded by DuckDB's buffer pool** (~100–200 MB), not by archive size. You can compact 10 GB of Parquet on a 1 GB-heap JVM and it works fine. The streaming pipeline never forces the full dataset into memory.

**But wall time scales with total dataset size.** The entire archive is re-read and re-written on every compact — just in streaming form, not buffered. At roughly 7 seconds per 100k rows:

| Rows     | Estimated compact time |
|----------|------------------------|
| 100k     | ~7s                    |
| 1M       | ~70s                   |
| 10M      | ~700s                  |

With the default `--compact-interval PT5M`, once the archive crosses ~5M rows, compact takes longer than the interval. The consumer falls behind, `templates_live` grows unbounded, and the system stops catching up.

## The Shape of the Real Fix

The current approach — rewrite the whole archive every compact — is the simple version. It trades memory for time: constant memory, linear time cost.

The natural evolution is **time-partitioned archives**:

```
logs_data/
  templates_2026_01.parquet
  templates_2026_02.parquet
  templates_2026_03.parquet   ← only this one gets touched
```

Compact would only rewrite the latest partition. The dashboard's `read_parquet` would glob across all partitions. Old partitions become immutable once sealed.

This changes the cost profile from **O(total archive size)** to **O(live tail size)** — cheap regardless of how much history has accumulated.

That's not implemented today. But it's the clear next step once you start hitting the 5M-row inflection point.

---

## Summary

| Property | Current behavior |
|---|---|
| Memory during compact | Bounded (~100–200 MB buffer pool) |
| Data loaded into RAM | Never — streamed in 2048-row vectors |
| Write semantics | Old archive rows + new live rows, atomically swapped |
| Time cost | Linear in total archive size |
| Scalability ceiling | ~5M rows at PT5M compact interval |
| Next step | Time-partitioned archives to make cost O(live tail) |

The takeaway: streaming compaction solves the memory problem elegantly, but doesn't escape the read-amplification problem inherent in whole-archive rewrites. Knowing which constraint you're hitting — memory or time — determines which fix to reach for.
