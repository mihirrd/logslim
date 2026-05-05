# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Status

LogSlim is in early design phase — only `design_doc.md` exists. No source code, build system, or tests have been written yet. When implementing, refer to `design_doc.md` as the authoritative spec.

## What LogSlim Is

A **high-throughput, lossless log deduplication engine** that reduces log storage 70–90% while preserving full debugging fidelity. It extracts log templates, separates variable parameters, and enables exact reconstruction of original logs. It is a transparent preprocessing layer, not a replacement for existing observability tools.

## Pipeline Architecture

```
[Ingestion] → [Parsing] → [Template Extraction] → [Storage] → [Query/Reconstruction]
```

### Components

**Ingestion Layer** — reads from files (tail) or stdin/streams using bounded buffered channels and parallel worker threads. Key tradeoff: batch size vs. latency.

**Parsing Layer** — tokenizes log lines and classifies tokens as static (strings) or dynamic (numbers, UUIDs, IDs, hashes).

**Template Extraction Engine** (core, most critical):
1. Normalize dynamic tokens to placeholders: `User 123 failed login` → `User <*> failed login`
2. Match against existing templates via hash lookup + similarity scoring (token match ratio)
3. If match found: append parameters. If not: create new template.

Template data structure:
```
Template { id: Integer, pattern: String, occurrences: Integer }
```

**Storage Layer** — columnar-like split:
- Templates table: `template_id | template_string`
- Parameters table: `template_id | timestamp | param_values | metadata`

**Reconstruction Engine** — fetches template, substitutes placeholders with stored parameters, preserves temporal ordering. Must produce byte-equivalent output.

**Query Engine** — supports template listing, parameter filtering, and frequency analysis.

## Key Invariants (Must Never Break)

1. **Losslessness** — every log must be exactly reconstructable (byte-equivalent)
2. **Ordering** — temporal order of logs must be preserved
3. **Correct grouping** — logs with distinct semantics must never be merged into the same template

Reconstruction correctness must be verified with invariant tests and checksum validation.

## Planned CLI Interface

```bash
logslim run --input /var/log/app.log --output logs.db
logslim templates --last 10m
logslim inspect T1
logslim query "User {id} failed login" --id=456
logslim replay --last 10m
logslim trace --request-id abc123
logslim anomalies --last 10m
```

## Performance Targets

| Metric | Goal |
|--------|------|
| Throughput | ≥100k events/sec |
| Compression | ≥70% |
| Reconstruction accuracy | 100% |
| Query latency | <100ms |

Performance approach: parallel ingestion workers, lock-free/low-lock data structures, template cache with eviction policies.

## Failure Modes to Handle

- **Template explosion** (too many unique logs): fallback to raw storage + template eviction
- **Incorrect grouping**: strict similarity thresholds + validation
- **Reconstruction errors**: invariant testing + checksums
- **Backpressure**: bounded queues + drop/spill strategies

## Template Extraction Challenges

| Problem | Solution |
|---------|----------|
| Over-generalization | Strict matching threshold |
| High cardinality | Template eviction / fallback to raw |
| Performance | Hash indexing on normalized patterns |
