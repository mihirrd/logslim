# LogSlim Usage Guide

LogSlim is a lossless log deduplication engine. It extracts repeating templates from log lines, stores only the variable parameters, and guarantees byte-exact reconstruction of every original line.

## Prerequisites

- Java 21+
- Maven 3.8+

## Build

```bash
mvn clean package -q
```

This produces `target/logslim-0.1.0-SNAPSHOT.jar`. All commands below use:

```bash
java -jar target/logslim-0.1.0-SNAPSHOT.jar <command> [options]
```

You can alias this for convenience:

```bash
alias logslim='java -jar /path/to/logslim-0.1.0-SNAPSHOT.jar'
```

The database defaults to `logs.db` in the current directory. Override it with `-Dlogslim.db.path=<path>`:

```bash
java -Dlogslim.db.path=/var/log/app.db -jar target/logslim-0.1.0-SNAPSHOT.jar <command>
```

---

## Commands

### `run` — Ingest logs

Reads a log file (or stdin) and stores deduplicated entries in the database.

```bash
# From a file
logslim run --input /var/log/app.log

# From stdin (pipe)
tail -f /var/log/app.log | logslim run --input -

# With a custom batch size (default: 1000)
logslim run --input app.log --batch-size 500
```

Sample output:
```
Ingested 10,000 lines.
Done. Ingested 10,000 lines.
```

---

### `templates` — List top templates

Shows the most common log patterns, sorted by occurrence count.

```bash
# All time
logslim templates

# Last 10 minutes
logslim templates --last 10m

# Last 2 hours, top 5 results
logslim templates --last 2h --limit 5
```

Sample output:
```
ID      OCCURRENCES  PATTERN
------------------------------------------------------------------------
[3   ]  8245        DB timeout on shard {num}
[1   ]  4102        User {num} failed login
[2   ]  1893        Payment failed for order {num}
```

Duration units: `s` (seconds), `m` (minutes), `h` (hours), `d` (days).

---

### `inspect` — Drill into a template

Shows a template's pattern, hit count, and its most recent parameter values.

```bash
# Show template 3 with its 10 most recent entries (default)
logslim inspect 3

# Show 25 recent entries
logslim inspect 3 --recent 25
```

Sample output:
```
Template: DB timeout on shard {num}
ID:       3
Hits:     8245

Recent entries:
  2026-05-04T22:01:00Z  {num=7}
  2026-05-04T22:01:01Z  {num=3}
  2026-05-04T22:01:01Z  {num=7}
```

---

### `query` — Filter logs by pattern and parameters

Reconstructs and prints log lines that match a template pattern, with optional parameter filters.

```bash
# All logs matching the pattern
logslim query "DB timeout on shard {num}"

# Filter to a specific shard, last hour
logslim query "DB timeout on shard {num}" --last 1h --filter num=7

# All failed logins for user 456
logslim query "User {num} failed login" --filter num=456
```

Sample output:
```
DB timeout on shard 7
DB timeout on shard 7
DB timeout on shard 7

(3 results)
```

Note: use `{num}` for numbers, `{uuid}` for UUIDs, `{hash}` for hex hashes, `{ts}` for timestamps. The display aliases `{id}`, `{x}`, and `{count}` are also accepted for `{num}`.

---

### `replay` — Reconstruct all original logs

Outputs all stored logs in their original form, in timestamp order. Guaranteed byte-exact.

```bash
# Replay everything
logslim replay

# Replay the last 30 minutes
logslim replay --last 30m

# Pipe back into a log file
logslim replay --last 24h > recovered.log
```

---

## End-to-end Example

```bash
# 1. Create a sample log file
cat > /tmp/sample.log << 'EOF'
User 123 failed login
User 456 failed login
DB timeout on shard 3
Payment failed for order 9901
User 789 failed login
DB timeout on shard 7
DB timeout on shard 3
EOF

# 2. Ingest
java -Dlogslim.db.path=/tmp/demo.db -jar target/logslim-0.1.0-SNAPSHOT.jar \
  run --input /tmp/sample.log

# 3. See what patterns were found
java -Dlogslim.db.path=/tmp/demo.db -jar target/logslim-0.1.0-SNAPSHOT.jar templates

# 4. Inspect the most common template
java -Dlogslim.db.path=/tmp/demo.db -jar target/logslim-0.1.0-SNAPSHOT.jar inspect 1

# 5. Query for a specific user
java -Dlogslim.db.path=/tmp/demo.db -jar target/logslim-0.1.0-SNAPSHOT.jar \
  query "User {num} failed login" --filter num=456

# 6. Replay all logs (exact originals)
java -Dlogslim.db.path=/tmp/demo.db -jar target/logslim-0.1.0-SNAPSHOT.jar replay
```

---

## Running Tests

```bash
# All tests
mvn test

# A specific test class
mvn test -Dtest=TemplateExtractorTest

# Just the integration test
mvn test -Dtest=EndToEndPipelineTest
```

---

## Key Limits (configurable in `application.properties`)

| Property | Default | Effect |
|----------|---------|--------|
| `logslim.template.max-count` | 100000 | Max templates before falling back to raw storage |
| `logslim.template.similarity-threshold` | 0.95 | Fraction of tokens that must match to reuse a template |
| `logslim.ingestion.batch-size` | 1000 | Lines buffered per processing batch |
| `logslim.ingestion.worker-threads` | 8 | Parallel ingestion workers |

Override at runtime:

```bash
java -Dlogslim.template.similarity-threshold=0.99 \
     -jar target/logslim-0.1.0-SNAPSHOT.jar run --input app.log
```
