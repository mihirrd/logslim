# LogSlim — Module Architecture

This document describes every module: what it does, its public interface, and how it fits into the pipeline.

---

## Pipeline overview

```
Raw log line
    │
    ▼
[Parsing]  LogTokenizer → ParsedLog
    │
    ▼
[Extraction]  TemplateNormalizer → pattern string
              TemplateCache / TemplateDao → matched or new Template
              TemplateNormalizer → parameter map
              LogEntryDao.insert()
    │
    ▼
[Storage]  SQLite — templates / log_entries / raw_logs
    │
    ▼
[Reconstruction]  LogReconstructor → original log line
    │
    ▼
[Query]  TemplateQueryService / LogQueryService → CLI output
```

---

## 1. Parsing

**Package:** `com.logslim.parsing`  
**Purpose:** Break a raw log string into classified tokens.

---

### `TokenType` (enum)

Two values:

| Value | Meaning |
|-------|---------|
| `STATIC` | Alphabetic word — becomes part of the template |
| `DYNAMIC` | Number, UUID, hex hash, or timestamp — becomes a parameter |

---

### `Token` (record)

Immutable. Represents one whitespace-delimited piece of the log line after classification.

```
Token(String value, TokenType type, int position)
```

| Field | Type | Description |
|-------|------|-------------|
| `value` | `String` | The original string from the log line |
| `type` | `TokenType` | STATIC or DYNAMIC |
| `position` | `int` | Zero-based index in the token sequence |

Helper methods: `isStatic()`, `isDynamic()`.

---

### `TokenClassifier`

**Input:** `String value`  
**Output:** `TokenType`

Classifies a single stripped token using regex priority:

1. UUID `[0-9a-fA-F]{8}-…` → DYNAMIC
2. ISO timestamp `\d{4}-\d{2}-\d{2}.*` → DYNAMIC
3. Hex hash 32+ hex chars (no dashes) → DYNAMIC
4. Number `-?\d+(\.\d+)?` → DYNAMIC
5. Anything else → STATIC

Note: mixed alphanumeric tokens like `shard7` or `v1alpha1` fall through to STATIC.

---

### `LogTokenizer`

**Input:** `(String rawLine, String source)`  
**Output:** `ParsedLog`

Steps:
1. Splits `rawLine` on `\s+`
2. Strips surrounding punctuation (`"`, `'`, `()`, `[]`, `{}`, `,`, `;`, `:`) from each part before classifying — the original `value` is kept unstripped
3. Classifies each part via `TokenClassifier`
4. Sets `timestamp` to `Instant.now()` (log-embedded timestamps are not yet parsed)
5. Returns empty token list for null or blank input

---

### `ParsedLog` (record)

Output of tokenization. Consumed by the extraction layer.

```
ParsedLog(List<Token> tokens, String originalContent, Instant timestamp, String source)
```

`originalContent` is the raw string exactly as received — used to verify losslessness.

---

## 2. Extraction

**Package:** `com.logslim.extraction`  
**Purpose:** Group similar logs under a shared template and store only the variable parameters.

---

### `TemplateNormalizer`

Converts tokens → normalized pattern string, and extracts the parameter map.

**`normalize(List<Token> tokens)` → `String`**

Replaces each DYNAMIC token with a typed placeholder:

| Token | Placeholder |
|-------|-------------|
| UUID | `{uuid}` |
| ISO timestamp | `{ts}` |
| Hex hash | `{hash}` |
| Number / other | `{num}` |

Static tokens are kept verbatim. Tokens are joined with a single space.

Example: `["User","123","failed","login"]` → `"User {num} failed login"`

---

**`extractParameters(List<Token> tokens)` → `Map<String, String>`**

Returns a `LinkedHashMap` of placeholder key → original token value, for every DYNAMIC token. When the same placeholder type appears more than once, keys are indexed: `num`, `num_1`, `num_2`, …

Example: `["10","of","20"]` → `{num=10, num_1=20}`

---

**`placeholderFor(String value)` → `String`**

Internal helper used by both methods above. Returns the `{…}` placeholder string for a raw dynamic token value.

---

### `TemplateMatcher`

Scores how similar a normalized input pattern is to a stored template pattern, and picks the best candidate.

**`score(String normalizedInput, String candidatePattern)` → `double`**

```
score = matching_tokens / max(input_token_count, candidate_token_count)
```

Tokens are compared positionally and must match exactly (no fuzzy). Returns 0.0–1.0.

**`findBestMatch(String normalizedInput, List<Template> candidates)` → `Optional<Template>`**

Iterates candidates, returns the one with the highest score above `logslim.template.similarity-threshold` (default 0.95). Returns empty if nothing qualifies.

---

### `TemplateCache`

Two-level lookup: Caffeine LRU (fast path) → SQLite DB (fallback).

| Method | Input | Output | Description |
|--------|-------|--------|-------------|
| `get(pattern)` | `String` | `Optional<Template>` | Cache hit returns immediately; miss queries DB and populates cache |
| `put(template)` | `Template` | `Template` | Inserts into DB, then adds to cache; returns saved template with generated ID |
| `refresh(template)` | `Template` | void | Increments occurrence counter in both cache and DB |
| `invalidate(pattern)` | `String` | void | Evicts one entry from cache |
| `clearAll()` | — | void | Evicts everything (used in tests) |
| `cachedSize()` | — | `long` | Estimated cache entry count |

Cache key: the normalized pattern string. Max size: `logslim.template.max-count` (default 100 000), eviction: LRU.

---

### `TemplateExtractor`

**The core orchestrator.** Drives the full ingestion pipeline for one log line.

**`process(String rawLine, String source)` → `Template` (or `null`)**

Steps:
1. `LogTokenizer.tokenize()` → `ParsedLog`
2. `TemplateNormalizer.normalize()` → `normalizedPattern`
3. `TemplateCache.get(normalizedPattern)` → existing `Template` or empty
4. If found: `TemplateCache.refresh()` (increments occurrence count)
5. If not found, and `templateDao.count() >= maxTemplateCount`: store raw fallback, **return null**
6. If not found, and count is safe: `TemplateCache.put(Template.newTemplate(pattern))` → new `Template`
7. `TemplateNormalizer.extractParameters()` → `Map<String, String> params`
8. `LogEntryDao.insert(new LogEntry(templateId, timestamp, params, metadata))`
9. Return the `Template`

**`processBatch(List<String> rawLines, String source)`** — calls `process()` in a loop.

Configuration: `logslim.template.max-count` (explosion guard), `logslim.template.similarity-threshold` (via TemplateMatcher).

---

## 3. Storage

**Package:** `com.logslim.storage`  
**Purpose:** Persist and query templates, log entries, and raw fallback logs in SQLite.

---

### Schema

```sql
templates   (template_id PK, pattern UNIQUE, occurrences, created_at, updated_at)
log_entries (entry_id PK, template_id FK, log_timestamp, parameter_json, metadata_json, created_at)
raw_logs    (log_id PK, content, log_timestamp, source, created_at)
```

`log_entries.parameter_json` and `metadata_json` are JSON objects stored as TEXT.  
All timestamp columns use fixed-width ISO-8601 strings (`uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'`) via `InstantUtil` so that lexicographic ORDER BY equals chronological order.

---

### `Template` (POJO)

| Field | Type | Notes |
|-------|------|-------|
| `id` | `Long` | null before insert |
| `pattern` | `String` | Normalized pattern, e.g. `"User {num} failed login"` |
| `occurrences` | `long` | Count of logs matched |
| `createdAt` | `Instant` | |
| `updatedAt` | `Instant` | Bumped on every occurrence increment |

Factory: `Template.newTemplate(String pattern)` — sets `occurrences=1`, `createdAt/updatedAt=now()`, `id=null`.

---

### `LogEntry` (POJO)

| Field | Type | Notes |
|-------|------|-------|
| `id` | `Long` | null before insert |
| `templateId` | `long` | FK to `templates` |
| `logTimestamp` | `Instant` | When the log event occurred |
| `parameters` | `Map<String,String>` | Dynamic token values, e.g. `{num=123}` |
| `metadata` | `Map<String,String>` | Context, e.g. `{source=app.log}` |
| `createdAt` | `Instant` | When the row was inserted |

---

### `RawLog` (POJO)

| Field | Type | Notes |
|-------|------|-------|
| `id` | `Long` | |
| `content` | `String` | Original log line verbatim |
| `logTimestamp` | `Instant` | |
| `source` | `String` | File path or `"reconstruction-fallback"` |
| `createdAt` | `Instant` | |

---

### `TemplateDao`

| Method | Input | Output | Notes |
|--------|-------|--------|-------|
| `insert(template)` | `Template` | `Template` | Sets `id` from generated key |
| `findByPattern(pattern)` | `String` | `Optional<Template>` | Exact match |
| `findById(id)` | `long` | `Optional<Template>` | |
| `incrementOccurrences(id)` | `long` | void | Also updates `updated_at` |
| `findTopN(since, limit)` | `Instant, int` | `List<Template>` | `since=null` means all time; ordered by occurrences DESC |
| `count()` | — | `long` | Total template count |

---

### `LogEntryDao`

| Method | Input | Output | Notes |
|--------|-------|--------|-------|
| `insert(entry)` | `LogEntry` | `LogEntry` | Single row insert |
| `insertBatch(entries)` | `List<LogEntry>` | void | Transactional; chunked at `logslim.storage.batch-insert-size` (default 500) |
| `findByTemplateId(id, limit)` | `long, int` | `List<LogEntry>` | Ordered by `log_timestamp ASC, entry_id ASC` |
| `findByTimeRange(from, to)` | `Instant, Instant` | `List<LogEntry>` | Same ordering |
| `findByParameterValue(id, key, value)` | `long, String, String` | `List<LogEntry>` | Uses `json_extract(parameter_json, '$.key')` |

Parameters and metadata are JSON-serialized by Jackson on write and deserialized on read.

---

### `RawLogDao`

| Method | Input | Output |
|--------|-------|--------|
| `insert(rawLog)` | `RawLog` | `RawLog` |
| `findByTimeRange(from, to)` | `Instant, Instant` | `List<RawLog>` ordered by `log_timestamp ASC, log_id ASC` |

---

### `InstantUtil`

All DAO timestamp formatting goes through this class.

| Method | Input | Output |
|--------|-------|--------|
| `format(instant)` | `Instant` | `String` — always 9 fractional-second digits |
| `parse(s)` | `String` | `Instant` |

**Why this exists:** `Instant.toString()` trims trailing zeros from fractional seconds (`.390Z` vs `.390993Z`). This causes incorrect SQLite string-sort ordering — `"…390Z"` sorts after `"…390993Z"` because `'Z' > '9'` in ASCII even though 390ms < 390993µs. `InstantUtil` fixes this by always emitting exactly 9 digits.

---

## 4. Reconstruction

**Package:** `com.logslim.reconstruction`  
**Purpose:** Rebuild the exact original log line from a stored template + parameter map.

---

### `LogReconstructor`

**`reconstruct(LogEntry entry)` → `String`**

Convenience overload. Looks up the template by `entry.templateId`, then calls the pattern overload below.

**`reconstruct(String pattern, Map<String, String> parameters)` → `String`**

Substitutes placeholders left-to-right:

1. Splits pattern on whitespace
2. For each token:
   - If it doesn't contain `{`: emit as-is
   - If it is a `{…}` placeholder: look up the parameter key
     - First occurrence of `{num}` → key `"num"`
     - Second occurrence of `{num}` → key `"num_1"`
     - Third → `"num_1"`, and so on (tracking per-base consume counter)
3. Joins tokens with a single space

Throws `ReconstructionException` if a required parameter key is missing.

**Invariant enforced by tests:** `reconstruct(extract(line)).equals(line)` for every log line.

---

### `ReconstructionValidator`

Validates a stored entry against the original line and stores a fallback if they diverge.

**`validate(LogEntry entry, String originalLine)` → `boolean`**

1. Reconstructs the entry
2. Compares to `originalLine` with `.equals()` (byte-equivalent)
3. **On match:** returns `true`
4. **On mismatch or exception:** logs SHA-256 hashes of both strings, inserts `originalLine` into `raw_logs` with `source="reconstruction-fallback"`, returns `false`

**`static sha256(String input)` → `String`** — hex-encoded SHA-256 digest, used for logging mismatches.

> Note: `ReconstructionValidator` is currently not called during ingestion — it exists and is tested, but `TemplateExtractor.process()` does not invoke it yet (see open items in CLAUDE.md).

---

### `ReconstructionException` (unchecked)

Thrown when a placeholder in the pattern has no matching key in the parameter map.

---

## 5. Query

**Package:** `com.logslim.query`  
**Purpose:** Expose read-oriented queries over the stored data and reconstruct original log lines for output.

---

### `TemplateQueryService`

**`listTopTemplates(Duration window, int limit)` → `List<Template>`**

- `window=null` → all time; otherwise filters `updated_at >= now() - window`
- `limit=0` → uses `logslim.query.default-page-size` (default 50)
- Ordered by `occurrences DESC`

**`getTemplate(long templateId, int recentCount)` → `Optional<TemplateDetail>`**

Returns a `TemplateDetail` record:
```
TemplateDetail(Template template, List<LogEntry> recentEntries)
```
`recentCount=0` defaults to 10 entries. Entries ordered by `log_timestamp ASC`.

---

### `LogQueryService`

**`queryByPattern(String pattern, Map<String, String> filters, Duration window)` → `List<String>`**

1. Normalizes user-facing pattern aliases (`{id}`, `{x}`, `{count}` → `{num}`)
2. Looks up template by exact pattern — returns empty list if not found
3. Fetches entries in `[now()-window, now()]` (or all time if `window=null`)
4. Filters to entries whose parameter map contains all `filters` entries (AND semantics)
5. Reconstructs each entry → returns original log lines sorted by timestamp

**`replayLogs(Duration window)` → `List<String>`**

1. Fetches all `LogEntry` rows in the time window and reconstructs each
2. Fetches all `RawLog` rows in the same window
3. Merges both into a single `TimestampedLine` list
4. Sorts by `timestamp ASC`
5. Returns original log lines in exact chronological order

---

## 6. CLI

**Package:** `com.logslim.cli`  
**Purpose:** Expose the five MVP commands as Picocli subcommands. Each command is a Spring `@Component` so services are injected normally.

---

### Command summary

| Command | Positional args | Options | Delegates to |
|---------|----------------|---------|--------------|
| `run` | — | `--input FILE`, `--batch-size N` | `TemplateExtractor.processBatch()` |
| `templates` | — | `--last DURATION`, `--limit N` | `TemplateQueryService.listTopTemplates()` |
| `inspect` | `TEMPLATE_ID` | `--recent N` | `TemplateQueryService.getTemplate()` |
| `query` | `"PATTERN"` | `--last DURATION`, `--filter KEY=VALUE` (repeatable) | `LogQueryService.queryByPattern()` |
| `replay` | — | `--last DURATION` | `LogQueryService.replayLogs()` |

---

### `DurationConverter`

Picocli type converter for all `--last` / `--window` options.

| Input | Output |
|-------|--------|
| `10s` | `Duration.ofSeconds(10)` |
| `5m` | `Duration.ofMinutes(5)` |
| `2h` | `Duration.ofHours(2)` |
| `1d` | `Duration.ofDays(1)` |

Throws `IllegalArgumentException` on unrecognized format.

---

### `RunCommand` — detail

Reads log lines from a file or stdin, batches them, and calls `TemplateExtractor.processBatch()`.

- `--input -` reads from stdin (default)
- Skips blank lines
- Prints `Ingested X,XXX lines...` progress every batch
- Exits with code 1 on `IOException`

---

### `QueryCommand` — detail

The `--filter` option is a Picocli map option: repeatable, each occurrence in `KEY=VALUE` form. All filters are AND-ed together.

```
logslim query "User {num} failed login" --filter num=456 --last 1h
```

---

## 7. Config

**Package:** `com.logslim.config`

---

### `LogSlimApplication`

Standard `@SpringBootApplication` entry point. Uses `@EnableCaching` to activate Caffeine. Returns Picocli exit code to the JVM via `SpringApplication.exit()`.

---

### `AppConfig`

Defines the `CacheManager` bean — a `CaffeineCacheManager` with a single cache named `"templates"` and LRU eviction at `logslim.template.max-count` entries.

---

### `PicocliConfig`

Builds the root `CommandLine` bean and registers the five subcommand beans. Defines the top-level `logslim` command (description, version string, help mixin).

---

### `CliRunner`

Implements `CommandLineRunner` (Spring calls `run(args)` after context startup) and `ExitCodeGenerator` (provides the exit code back to `SpringApplication.exit()`). Bridges Spring Boot startup into Picocli command dispatch.

---

## Configuration reference

| Property | Default | Effect |
|----------|---------|--------|
| `logslim.db.path` | `logs.db` | SQLite file path (set via `-D` JVM flag) |
| `logslim.template.max-count` | `100000` | Max live templates; new ones fall back to raw storage |
| `logslim.template.similarity-threshold` | `0.95` | Fraction of tokens that must match to reuse a template |
| `logslim.ingestion.batch-size` | `1000` | Lines buffered per `processBatch` call |
| `logslim.ingestion.worker-threads` | `8` | (Declared, not yet wired to async workers) |
| `logslim.storage.batch-insert-size` | `500` | Rows per transactional chunk in `insertBatch` |
| `logslim.query.default-page-size` | `50` | Default limit for `listTopTemplates` |
