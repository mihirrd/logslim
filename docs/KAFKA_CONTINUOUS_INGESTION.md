# Spec: Continuous Kafka ingestion

Status: implemented (May 2026)

## Problem

LogSlim's `run` subcommand reads from a file or stdin and exits when the input is exhausted. That works for batch backfill but not for the day-to-day "tail my service logs into LogSlim" use case, where logs arrive continuously and the user wants:

- ingestion that keeps running until told to stop;
- bounded memory and disk while it runs (no unbounded growth in any one table);
- the dashboard to keep working concurrently — both reads and writes happening in the same machine at the same time;
- a single command that does the right thing without a cron job, supervisord script, or external job runner.

Kafka is the obvious source because every modern log pipeline (Filebeat, Vector, Fluent Bit, application loggers via `KafkaAppender`) already publishes there. LogSlim becomes another consumer in the pipeline.

The constraint LogSlim has to live within is **DuckDB's single-writer-per-process model**: at most one OS process can hold the database file in read-write mode at a time. Any "consumer + dashboard" design has to either (a) keep both inside one JVM, (b) route the dashboard reads through Parquet snapshots so the writer process owns DuckDB alone, or (c) accept the dashboard going dark while ingest runs. (b) is what we already chose for the dashboard a few iterations back; this spec slots Kafka ingest into the same single-writer slot.

## Goals

1. `logslim consume --topic <topic>` runs as a daemon: subscribes to a Kafka topic, batches incoming records, writes through the existing extraction pipeline.
2. Periodic in-process compaction so the live tail stays small and the dashboard's Parquet snapshot stays fresh.
3. At-least-once semantics — no records dropped on a normal restart; some may be duplicated if the JVM crashes mid-batch.
4. Graceful `SIGINT` shutdown: flush in-flight batch, commit offsets, run a final compact, exit.
5. File ingestion (`logslim run --input`) is unchanged.

## Non-goals

- Exactly-once delivery (would need transactional Kafka consumer + matching DuckDB savepoints).
- TLS / SASL authentication to the broker.
- Multiple topics in one process.
- JSON / Avro / Protobuf payload deserialisation — v1 assumes the record value *is* a log line (plain text).
- Embedded-broker integration tests (would need testcontainers).

---

## Design

### Single-threaded reactor

One thread does everything: poll → write → (sometimes) compact. The pseudo-code:

```
loop while not shutdown:
    records = consumer.poll(100ms)
    for each record:
        batch.add(LogGroup.singleLine(record.value, source))

    if batch.size >= flush-size OR elapsed >= flush-interval:
        extractor.processBatch(batch)        # writes log_entries_live / raw_logs_live
        consumer.commitSync()                # at-least-once: commit AFTER write
        batch.clear(); reset flush timer

    if elapsed-since-last-compact >= compact-interval:
        adminService.compactDatabase(dataDir)  # idempotent re-compact
        reset compact timer
```

No worker threads, no lock-stepping, no thread-pool. DuckDB allows one writer; multiplying threads only fights that constraint. The poll loop's natural cadence (100ms polls + size-or-time flush) gives the consumer enough opportunity to wake up and react to shutdown / batch / compact triggers between each Kafka network round-trip.

### At-least-once semantics

The order is fixed: **`processBatch` succeeds → `commitSync` runs → next poll**. If the JVM crashes between `processBatch` and `commitSync`, the next run re-reads the same Kafka records and writes duplicates into `log_entries`. Duplicates show up to the user but don't corrupt anything (LogSlim has no application-level uniqueness on log content). If the crash happens during `processBatch`, the half-written batch's records re-enter on restart — same outcome.

Upgrading to exactly-once would require a Kafka transactional consumer plus matching transaction boundaries in DuckDB so an aborted batch rolls back. The complexity isn't justified for log shipping.

### Periodic compaction

The hybrid `archive + live` layout that `compact` produces is already idempotent: a re-compact reads the unified view (`templates_archive` ∪ `templates_live`), writes the merged result as a fresh Parquet, drops + recreates the live table empty. So the consumer just calls `adminService.compactDatabase(dataDir)` every N minutes.

Default `--compact-interval` is `PT5M` (five minutes). Smaller values shrink the dashboard's staleness window; larger values reduce the amortised cost of compaction. A few-MB live tail is comfortable.

Because compact drops and recreates views/tables, writes during compact would fail — but compact and ingest are on the same thread, so the consumer literally can't be mid-write when compact runs. No locks needed.

### Backpressure

The poll loop is synchronous. If `processBatch` is slow, the next `poll` happens later, the consumer falls behind, Kafka holds the backlog server-side. That's exactly what consumer-group lag-tracking is designed to surface. No in-process queue or disk spool. If the consumer can't keep up, the operator sees rising lag and provisions more consumers (separate `--group-id`s on separate machines).

### Multi-line records

v1 treats each Kafka record as one log line. If the record's value contains `\n`, the first line becomes the header and the rest becomes the `continuationLines` of a single `LogGroup` — same shape `MultiLineGrouper` produces from file input but without needing a `BufferedReader`. This handles the "stack trace published as a single Kafka record" case. Cross-record multi-line correlation (e.g., a Java exception spanning two records) is out of scope.

### `--from-beginning` actually seeks

Kafka's `auto.offset.reset` config (which `--from-beginning` toggles between `earliest` and `latest`) only takes effect when there is **no committed offset for the consumer group**. If the user has previously run `logslim consume --group-id X` and the group has stored an offset, restarting with `--from-beginning` does *not* re-read everything — the stored offset wins.

To make `--from-beginning` do what its name implies, the runner calls `consumer.seekToBeginning(consumer.assignment())` after the first poll completes (the first poll triggers partition assignment). The records that came back in that first poll are skipped via `continue` so they aren't double-counted post-rewind.

---

## Server-side fix that came up during integration

The dashboard server uses `ParquetDataSourceConfig` (Option 2 from the dashboard-concurrency spec): an in-memory DuckDB with three views over the Parquet snapshot, so it never opens `logs.duckdb` and the consumer can write freely. The first version of this bean wrapped the connection in `SingleConnectionDataSource` — one shared connection across all requests.

That broke as soon as the consumer's periodic compact started running. Compact does `Files.move(.parquet.new → .parquet, REPLACE_EXISTING)` atomically. A read against `read_parquet('templates.parquet')` that overlaps the rename can fail transiently. With a shared connection, DuckDB JDBC marks the connection's pending result as "unsuccessful," and every subsequent query on that connection fails with:

```
Invalid Input Error: Attempting to execute an unsuccessful or closed pending query result
```

…until the JVM is restarted. The dashboard stops working from the user's perspective even though everything else is fine.

**Fix:** `ParquetDataSourceConfig.dataSource()` now returns an `AbstractDataSource` whose `getConnection()` opens a **fresh in-memory DuckDB per request** and runs the three `CREATE VIEW … FROM read_parquet(...)` statements inline. The caller's `close()` destroys the connection. Failures are scoped to one request. The cost is a few dozen ms of DuckDB init per request, which is fine for dashboard traffic.

---

## Implementation

### New file: `src/main/java/com/logslim/ingestion/KafkaIngestRunner.java`

Spring `@Component`. Injects `TemplateExtractor` and `AdminService`. Single public entrypoint:

```java
public void consume(String topic, String bootstrapServers, String groupId,
                    String source, int batchSize,
                    Duration flushInterval, Duration compactInterval,
                    boolean fromBeginning)
```

Internally:

- Builds a `KafkaConsumer<String, String>` with `enable.auto.commit=false` and `auto.offset.reset = fromBeginning ? earliest : latest`.
- Installs a JVM shutdown hook that flips a `volatile boolean stop` flag.
- Runs the poll/flush/compact loop until `stop` is true.
- On shutdown: drains the in-flight batch, commits, runs a final compact, closes the consumer.

Helpers:
- `toLogGroup(value, source)` — single-line or multi-line `LogGroup` depending on whether `value` contains `\n`.
- `resolveDataDir()` — mirrors `CompactCommand.resolveDataDir`; strips `.duckdb`, appends `_data`, returns the absolute path.
- `runCompact(dataDir)` — catches `RuntimeException` from `adminService.compactDatabase` so a one-off compact failure doesn't kill the long-running consumer (the next compact-interval gets another shot).

Logs every flush: `flushed batch of N records (total M)`. Logs compact completion with wall-clock duration: `compact completed in X ms`.

### New file: `src/main/java/com/logslim/cli/ConsumeCommand.java`

picocli subcommand. Constructor injects `KafkaIngestRunner`. CLI flags:

| Flag | Default | Description |
| --- | --- | --- |
| `--topic` | (required) | Kafka topic to subscribe to. |
| `--bootstrap-servers` | `localhost:9092` | Kafka brokers. |
| `--group-id` | `logslim` | Consumer group id. |
| `--source` | `kafka` | `source` column value for any line that lands in `raw_logs`. |
| `--batch-size` | `1000` | Flush after this many buffered records. |
| `--flush-interval` | `PT5S` | Flush at least this often (ISO-8601 duration). |
| `--compact-interval` | `PT5M` | Run `compact` this often. |
| `--from-beginning` | (off) | Seek to earliest offset on first poll, ignoring any committed offset. |

`run()` simply calls `runner.consume(...)` with the parsed flags.

### Modified: `src/main/java/com/logslim/config/PicocliConfig.java`

Add `ConsumeCommand consume` to the `commandLine(...)` bean's constructor and `cmd.addSubcommand("consume", consume)` alongside the other seven subcommands.

### Modified: `src/main/java/com/logslim/config/ParquetDataSourceConfig.java`

Replaced `SingleConnectionDataSource` with an `AbstractDataSource` that opens a fresh in-memory DuckDB per `getConnection()` (see the server-side fix above). The view-creation SQL is the same; what's different is when it runs.

### Modified: `pom.xml`

One new dependency:

```xml
<dependency>
    <groupId>org.apache.kafka</groupId>
    <artifactId>kafka-clients</artifactId>
    <version>3.6.1</version>
</dependency>
```

`spring-kafka` is overkill — we don't want `@KafkaListener` plumbing or Spring's auto-configured `KafkaTemplate`, just the raw consumer. `3.6.1` matches what Spring Boot 3.2.5 transitively expects elsewhere.

### Modified: `README.md`

Added a "Continuous ingestion from Kafka" subsection under CLI Usage with the example invocation, semantics note, and SIGINT-handling note.

---

## Reused utilities

- **`TemplateExtractor.processBatch(List<LogGroup>)`** (`src/main/java/com/logslim/extraction/TemplateExtractor.java`) — the batched-write pipeline from the prior ingestion-speedup task. Zero changes.
- **`AdminService.compactDatabase(Path)`** (`src/main/java/com/logslim/service/AdminService.java`) — idempotent over the hybrid layout. Called on a wall-clock timer from the consumer.
- **`LogGroup.singleLine(String, String)`** factory plus the canonical `new LogGroup(header, tail, source)` constructor — used to construct `LogGroup` from Kafka record values, with or without continuation lines.
- **`CompactCommand.resolveDataDir()` logic** (`src/main/java/com/logslim/cli/CompactCommand.java`) — three-line path derivation; mirrored inline in `KafkaIngestRunner` to avoid a one-method utility class.

`MultiLineGrouper` is **not** reused. Its iterator wants a `BufferedReader`; we'd have to pipe records into a `PipedReader` to use it. The inline split-on-`\n` for the rare multi-line record is simpler.

---

## Trade-offs accepted

- **At-least-once.** Crash mid-batch → duplicates on restart. Acceptable for log shipping; LogSlim has no application-level uniqueness on log content anyway.

- **Dashboard staleness window.** The dashboard reads only the compacted Parquet snapshot — records sitting in `*_live` between two compacts are invisible. The window equals roughly `flush-interval + compact-interval`. Default 5m is fine for an operations dashboard; tighten to seconds if needed.

- **Single thread = single point of slowness.** A long compact blocks the consumer. With the hybrid layout, compact is sub-second at small scale, but a very large archive (millions of rows) makes each compact slower. Tune `--compact-interval` upward when the archive is large to amortise.

- **`session.timeout.ms` / `max.poll.interval.ms`.** Kafka's default `max.poll.interval.ms` is five minutes. If `flush-interval + compact-interval` plus the work itself exceeds that, the broker considers the consumer dead and rebalances the partition away. The defaults (`PT5S` + `PT5M`) sit right on the edge of that; if compact regularly takes more than a few seconds, set `--compact-interval` shorter or increase `max.poll.interval.ms` via a custom property (not exposed as a CLI flag today; passes via Kafka client system properties).

- **No retry/backoff on compact failure.** A compact that throws is logged and skipped; the next compact-interval gets another attempt. The consumer doesn't stop. If compact failures are persistent, the live tail grows unbounded — the operator has to notice.

---

## Operational guidance

```bash
# Real-time ingestion from a Kafka topic
logslim consume \
  --topic app-logs \
  --bootstrap-servers kafka-broker:9092 \
  --group-id logslim-prod \
  --batch-size 5000 \
  --flush-interval PT5S \
  --compact-interval PT10M

# Read from offset 0, ignoring any prior committed offset for the group
logslim consume --topic app-logs --from-beginning

# Run consumer + dashboard concurrently (works because they own different files)
java -jar target/logslim-1.0.0.jar serve &
java -jar target/logslim-1.0.0.jar consume --topic app-logs
```

`SIGINT` (Ctrl-C) triggers graceful shutdown: in-flight batch flushed, offsets committed, final compact run, exit 0.

Operational signals to watch in the consumer log:

| Log line | Meaning |
| --- | --- |
| `subscribed to '<topic>' (...)` | Startup completed, beginning to poll. |
| `--from-beginning: seeking N partition(s) to earliest offset` | Rewind happened (only if `--from-beginning` was passed). |
| `flushed batch of N records (total M)` | A batch was written. `M` should grow monotonically. |
| `compact completed in X ms` | A compact ran. The dashboard sees the new data after this fires. |
| `compact failed (will retry on next interval): …` | A compact threw; live tail will grow until the next attempt succeeds. |
| `draining N buffered records before shutdown` | SIGINT received; flushing in-flight work. |
| `logslim consume — shutdown complete (total ingested: N)` | Clean exit. |

---

## Verification

```bash
# 1. Tests pass
mvn test -q                                            # 99 tests, 0 failures

# 2. Build, confirm wiring
mvn package -DskipTests -q
java -jar target/logslim-1.0.0.jar --help | grep consume
java -jar target/logslim-1.0.0.jar consume --help

# 3. Smoke test against a local Kafka (Docker)
docker run -d --name kafka -p 9092:9092 \
  -e KAFKA_PROCESS_ROLES=controller,broker \
  -e KAFKA_NODE_ID=1 \
  -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 \
  -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092 \
  -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
  -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
  -e CLUSTER_ID=ciWo7IWazngRchmPES6q5A \
  apache/kafka:3.7.0
sleep 5
docker exec kafka /opt/kafka/bin/kafka-topics.sh \
  --create --topic app-logs --bootstrap-server localhost:9092

# Terminal A
java -jar target/logslim-1.0.0.jar consume \
  --topic app-logs --group-id logslim-diag \
  --batch-size 100 --flush-interval PT2S --compact-interval PT5S

# Terminal B
python3 benchmarks/gen.py 10000 | docker exec -i kafka \
  /opt/kafka/bin/kafka-console-producer.sh \
  --topic app-logs --bootstrap-server localhost:9092

# 4. Verify the consumer flushed and compacted
#    Watch Terminal A — expect `flushed batch of N` and `compact completed` lines.
java -jar target/logslim-1.0.0.jar templates --limit 10

# 5. Concurrent dashboard
java -jar target/logslim-1.0.0.jar serve &
sleep 2
curl -s http://localhost:8080/api/stats
# Should keep working through compact cycles. If a request lands during the
# .parquet.new rename it may fail individually — the next request succeeds.

# 6. Graceful shutdown
kill -INT $(pgrep -f 'logslim.*consume')
# Expect: drain log + final compact + clean exit.

# 7. At-least-once: produce 100 more, kill -9 the consumer mid-batch,
#    restart it without --from-beginning. Re-consumes uncommitted records,
#    some may be duplicated. No data loss.
```

---

## Result

| | Before | After |
| --- | --- | --- |
| Continuous ingestion | not supported — `run` exits at EOF | `consume` daemon polls Kafka forever |
| Dashboard while ingesting | unsupported (DuckDB single-writer) | works through compact cycles (per-request DuckDB) |
| Compact cadence | manual (`logslim compact -y`) | automatic on a configurable timer |
| Crash safety | n/a | at-least-once via post-write offset commit |
| Multi-line records | yes (file input) | yes (Kafka record body split on `\n`) |

The CLI's surface area grows by one subcommand; the existing seven and the file-input path are untouched.
