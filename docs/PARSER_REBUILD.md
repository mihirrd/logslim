# Parser rebuild: from Drain to deterministic masking

## Why

Running LogSlim over the full Loghub **HDFS.log** (11.18M lines) exposed two
failures that make the current Drain-based parser unfit for production:

| Metric | Drain (current) | Target |
|---|---|---|
| Total lines | 11,175,629 | — |
| Lines invisible in `raw_logs` | **1,686,068 (15.1%)** | 0 |
| Distinct templates | **30,068** | ~30 (Loghub ground truth) |
| Templates with ≤10 occurrences | **28,708 (95%)** | few |
| Distinct event classes hiding in raw | 54 | 0 |

Two independent defects, one consequence — **the structured view an agent
reasons over is both polluted and incomplete**:

1. **1000× template explosion.** `TokenClassifier` masks numbers/IPs/UUIDs but
   **not block IDs (`blk_-160…`) or file paths**. Every distinct path/block ID
   becomes its own template (e.g. `allocateBlock: /user/root/.../_task_200… blk_…`).
   Token-count partitioning compounds it. `list_templates` returns 30k patterns —
   the "reason over a few dozen templates" thesis is dead on this dataset.

2. **A frequent event, 100% invisible.** `NameSystem.delete: blk_X is added to
   invalidSet of IP` occurs **1.4M times** yet is entirely in `raw_logs`. It
   fragments by unmasked block ID/path, so no variant reaches `lockAfterN=10`,
   so every occurrence falls through the **lock gate** into the invisible pile.
   The blind spot is correlated with rarity — and rarity is the incident signal.

## Principle

**The structured (template) view covers 100% of lines. `raw_logs` becomes a
pure lossless byte fallback (round-trip mismatch only), never a parser
give-up pile.** Singletons are first-class templates — they are the highest-value
signal during an incident, not noise to suppress.

## Design

A deterministic, **order-independent** parser: the template set is a pure
function of the data, so it is reproducible and auditable. Two layers — a
universal masker and a corpus-driven learner — and **zero per-dataset
configuration** (the HDFS-specific `blk_` rule is gone; the generic `{num}`
entity yields `blk_{num}` on its own).

1. **Universal entity masking (span-based), `EntityMasker`.** A line is masked
   by an ordered regex pass over the *whole line* (not per whitespace-token),
   because entities appear glued mid-token — e.g. `10.251.71.68:50010:Got` is
   one token. Each match is replaced by a typed placeholder and the matched span
   captured as a parameter in match order. Entities are dataset-independent
   syntax only: timestamps, IP[:port], file paths, UUIDs, long hex,
   sizes/durations, numbers. Literal braces are stored doubled (`{{`/`}}`) so
   JSON-ish content never collides with slots.
2. **Learned variables, `TemplateLearner`.** Every dataset has ID species no
   fixed regex list anticipates (session IDs, container names, hostnames).
   A learning pass over the corpus's distinct masked shapes discovers them
   statistically: group shapes by token count, then recursively —
   a varying token position is a **variable** only when its cardinality exceeds
   `logslim.parser.max-branching` (default 8) **and** its values look like
   identifiers (contain digits, masked slots, or id punctuation — never pure
   words); otherwise it is **semantics** and the group splits on it. Groups
   whose remaining variation is all identifier-like merge into one template
   with `{var}` slots. A shared literal `key=`/`key:` prefix survives the merge
   (`status={var}`). Splitting beats merging on ambiguity: `succeeded`/`failed`
   stay separate templates, and word-valued positions never blur to var-soup.
3. **Template = the (merged) masked shape.** Hash-map lookup, no parse tree, no
   lock gate. 100% structured coverage by construction. Patterns first seen
   after learning (streaming/Kafka) match against learned `{var}` templates
   token-wise, else become their own template.
4. **Reconstruction** re-inserts captured spans into placeholder positions →
   byte-exact original line. A merged `{var}` slot stores the full original
   token (minus any kept key prefix) as one parameter. Only a genuine
   round-trip mismatch falls to `raw_logs`.
5. **Two-pass file ingest.** Pass 1 streams the input through learning (mask +
   collect distinct shapes, no writes); pass 2 stores entries against the
   learned templates. Stdin is spooled to a temp file. This keeps the template
   set a deterministic function of the corpus.

### Proof (Python prototype on HDFS)

| Variant | Templates | Coverage | Notes |
|---|---|---|---|
| Drain (current) | 30,068 | 84.9% | 1.69M lines invisible |
| v2 whole-token masking | 1,900 | 100% | misses glued mid-token entities |
| **v2 line-level (span) masking** | **46** | **100%** | recommended; readable templates |
| v2 config-free frequency | 69 | 100% | over-masks semantic words |

The extra ~16 templates over the official ~30 are *distinct exception types*
(`IOException: Connection reset` vs `EOFException`) that are worth keeping
separate during an incident.

## Implementation status (2026-07-01)

- [x] `EntityMasker` — span-based universal masking: `mask(line) → (pattern, params[])`; no dataset-specific rules.
- [x] `TemplateLearner` — corpus-driven variable discovery (split-vs-merge on cardinality + id-likeness), KV-prefix preservation, `LearnedTemplate` matching for post-learning patterns.
- [x] Replaced `DrainTree` routing in `TemplateExtractor` with mask → learned-plan lookup; **removed the lock-gate → raw path** (raw is now only cap-overflow or round-trip failure). Deleted `DrainTree` and its tests.
- [x] `LogReconstructor` + `TemplateNormalizer.slotNames` on the escaped-brace scheme; byte-exact round-trip safety net kept. Full suite green (124 tests).
- [x] Two-pass ingest in `RunCommand` (learn → store; stdin spooled).
- [x] Cross-dataset validation, same binary, zero config (see table below).
- [x] Incremental relearn for streaming ingest: `TemplateExtractor.relearn()` runs before every compact (Kafka runner interval + `compact` command), learns over the accumulated identity templates, and folds fragmented families into merged `{var}` templates — entries rewritten in the writable tier with re-planned params (byte-exact round-trip preserved); fragments with already-archived entries are remapped forward (immutable Parquet stays; bootstrap re-derives the remap after restart). Monotone: merges are never undone.
- [ ] GA/PA harness against the Loghub HDFS labeled set (correctness number).

### Cross-dataset validation (one binary, no per-file config)

| Dataset | Lines/groups | Templates | 90% of volume in | Raw fallback |
|---|---|---|---|---|
| incident.log (synthetic microservices) | 13,728 | 91 | top 15 | 0 |
| atlassian-jira.log (real-world, multiline) | 29,931 | 2,468 | top 551 | 0 |
| linux_syslog sample (adversarial random cross-product) | 1,000 | 987 | — (intrinsic distinct count is 987) | 0 |
| HDFS.log (11.18M lines) | 11,175,629 | 54 | top 7 (top 10 = 99%) | 0 |

See `PARSER_EVOLUTION.md` for the full narrative — Drain's failure modes, the
overfit v2, the learned-variable design, and its tradeoffs.
