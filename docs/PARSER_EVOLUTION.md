# Parser evolution: Drain → hardcoded masking → learned variables

How LogSlim's template extraction went from an online clustering algorithm to a
deterministic two-layer parser, and what we traded to get there. Companion to
`PARSER_REBUILD.md` (the design doc); this is the reasoning.

## 1. What was wrong with Drain

Drain is an online log-clustering algorithm: it routes each line through a
parse tree keyed on token count and leading tokens, and wildcards positions
that vary within a similarity threshold. Running it over the full Loghub
`HDFS.log` (11.18M lines, ~30 ground-truth event types) exposed four defects:

| Defect | Measured impact |
|---|---|
| **Template explosion.** Any variable token the pre-masker misses (block IDs, paths) seeds a new cluster; token-count partitioning multiplies it. | 30,068 templates; 95% had ≤10 occurrences |
| **The lock gate hides exactly the wrong lines.** Clusters emit to `raw_logs` until they lock (N=10 similar lines). Rare or fragmented events never lock. | 1.69M lines (15.1%) invisible to the structured view — including a 1.4M-occurrence event fragmented below the lock threshold. Rarity is the incident signal, and the blind spot was correlated with rarity |
| **Order dependence.** The template set depends on line arrival order and tuned thresholds. | Not reproducible, not auditable; re-ingest ≠ same templates |
| **Whole-token wildcarding.** Entities glued mid-token can't be masked. | `10.251.71.68:50010:Got` is one token → fragmenting |

The consequence was strategic, not just cosmetic: "reason over a few dozen
templates" is LogSlim's core thesis, and 30k polluted, 85%-complete templates
kill it.

## 2. v2: deterministic masking — right idea, overfit execution

v2 replaced the tree with a pure function: scan the whole line for entity
spans (regex alternation), replace each with a typed slot, and use the masked
shape as the template key. This fixed determinism, coverage (no lock gate),
and intra-token entities in one move: 54 templates, 0% blind spot on HDFS.

But it cheated. The entity list contained `blk_-?\d+` — an HDFS-ism. Every new
dataset would demand its own additions (`usr_` IDs, container names, session
tokens), which is whack-a-mole curve-fitting to whichever file is on the desk:
the parser looked general because it had been tuned on the file it was
evaluated on. The failure mode is silent: one unanticipated ID species per
dataset re-inflates templates by orders of magnitude.

The opposite extreme also failed: a config-free frequency-based masker (mask
any rare token) hit 100% coverage but ate semantic words — 69 blurrier
templates where `succeeded` vs `failed` merged into wildcards.

## 3. Current design: universal syntax by rule, dataset specifics by learning

The synthesis is two layers with a sharp boundary:

**Layer 1 — `EntityMasker`, universal syntax only.** Timestamps, IPs, UUIDs,
paths, long hex, sizes, numbers. These are properties of machine-generated
text, not of any dataset — the `blk_` rule is deleted, and the generic number
rule yields `blk_{num}` on its own. Span-based (handles glued entities),
escaped-brace pattern scheme, byte-exact `unmask`.

**Layer 2 — `TemplateLearner`, the dataset's own ID species discovered
statistically.** Over the corpus's distinct masked shapes, grouped by token
count, recursively:

- A varying token position **merges** into a `{var}` slot only if its
  cardinality exceeds `logslim.parser.max-branching` (default 8) **and** its
  values look like identifiers (contain digits, masked slots, or id
  punctuation — never pure words).
- Otherwise the variation is semantics and the group **splits** on it.
- A literal `key=`/`key:` prefix shared by all values survives the merge
  (`status={var}`), so keys stay visible.

Ambiguity resolves toward splitting: `succeeded`/`failed` stay two templates;
word soup never becomes `{var}` soup. The template set is a pure, deterministic,
order-independent function of the corpus — re-run it, get the same answer.
File ingest is two-pass (learn, then store); there is no lock gate, and
`raw_logs` receives only genuine round-trip mismatches.

**Results — one binary, zero per-dataset configuration:**

| Dataset | Lines/groups | Templates | Raw fallback |
|---|---|---|---|
| HDFS.log | 11,175,629 | **54** (top 7 = 90% of volume, top 10 = 99%; learner merged 0 — universal masking sufficed) | 0 |
| incident.log | 13,728 | 91 (top 15 = 90% of volume) | 0 |
| atlassian-jira.log | 29,931 | 2,468 (real-world one-offs; 100% structured) | 0 |
| linux_syslog sample | 1,000 | 987 — equals the file's intrinsic distinct-shape count (random cross-product data) | 0 |

Byte-exact full-file replay verified on incident.log; 124 tests cover the
split-vs-merge decisions, round-trips through merged templates, and determinism.

## 4. Streaming ingest: learning in arrears

Kafka is the primary ingestion path, and a stream can't be read twice — so the
two-pass file flow doesn't apply. Instead, the two layers split across the
hot path and the compact cycle:

**Hot path (per record, single-threaded, no learning).** Mask the line, then
resolve the shape down a three-step ladder:

1. *Direct hit* — the shape already maps to a template → increment.
2. *Learned match* — the shape fits an existing merged template token-wise
   (`{var}` positions accept any token with the right key prefix) → fold in,
   params re-planned.
3. *New shape* — becomes its own identity template, immediately, from its
   first occurrence. Structured, queryable, loud in `new_templates`.

Nothing here needs corpus state; ingest stays mask + hashmap + append.

**Relearn (before every periodic compact).** The learner's input is not the
log stream — it is the accumulated *identity template rows* (dozens, not
millions). `relearn()` runs `TemplateLearner` over them and folds each
fragmented family into a merged template:

- *Fully live* fragments (born since the last compact): entries are rewritten
  onto the merged template with re-planned params — byte-exact round-trip
  preserved — occurrences move, and the fragment row is deleted. Relearn runs
  *before* the export precisely so folds hit the still-writable tier.
- *Partially archived* fragments (crossed an earlier compact): Parquet
  partitions are immutable, so the row stays frozen for reconstruction and
  only future lines are remapped to the merged template.

Relearn is monotone (merges are never undone — no schema flapping under
agents or dashboards) and idempotent (a round with no new evidence is a
no-op). On restart, bootstrap re-derives forward remaps from pattern text
alone: every identity pattern is matched against the merged templates, so a
folded family can never quietly resurrect.

The convergence loop is: new ID species fragments (visible, bounded) → next
compact folds it → subsequent records match the merged template on arrival.
Fragmentation lasts at most one compact interval.

**Verified equivalence.** The same 11.18M-line HDFS corpus was ingested both
ways — two-pass file ingest and Kafka streaming with periodic relearn+compact —
and produced the identical result: **54 templates, 11,175,629 structured
entries, 0 raw fallback**. (For HDFS every relearn is a no-op: universal
masking alone suffices, so identity shapes are already final.) The template
set is a function of the data, not of the ingestion path — the property Drain,
being order-dependent, could never offer.

## 5. Tradeoffs, and how we live with them

**Two-pass ingest.** The input is read twice and nothing is written until
learning completes; stdin must be spooled to disk. *Living with it:* pass 1 is
mask-and-count only (cheap, parallel); the input is a file we're reading
anyway. For very large corpora, learning can run over a bounded sample —
shapes converge long before 11M lines. Longer term, re-learning belongs in
`compact`, where a batch rewrite already exists.

**Streaming ingest learns in arrears, not in line.** Kafka records match
against already-learned `{var}` templates; a genuinely new ID species
fragments into identity templates until the next relearn. *Living with it:*
`relearn()` runs before every periodic compact — it learns over the
accumulated identity templates and folds fragmented families into merged
templates (entries rewritten with re-planned params while still in the
writable tier; families that already crossed a compact are remapped forward
against the immutable archive). Fragmentation is therefore bounded by the
compact cadence, always visible and queryable in the meantime, and merges are
monotone — never undone. Counts for a family folded across a compact boundary
split at the fold; compare patterns, not template ids, across folds.

**Conservative merging under-merges word-valued variables.** A username
position with pure-alpha values (`Mihir`) never merges; a variable seen with
≤ 8 distinct values stays split. *Living with it:* deliberate. Fragmentation
from this is bounded (at most `max-branching` templates per position) and
shrinks as data grows, whereas over-merging destroys signal permanently and
invisibly — the syslog var-soup incident is why the id-likeness guard exists.
The threshold is one knob, global, not per-dataset.

**Learned slots are untyped.** `{var}` says less than `{ip}`. *Living with
it:* the KV-prefix rule preserves key names where they exist, and slot-value
distributions remain fully queryable (`inspect_template`), which is where the
signal actually gets consumed.

**Faithfulness over flattery on adversarial data.** On corpora whose lines are
random cross-products (the syslog sample), the learner reports the data's true
structure — 987 shapes — rather than compressing to a pretty number.
*Living with it:* this is a feature; the fix for standard transport framing
(RFC 3164/5424 syslog headers, CLF) is universal *format* support in Layer 1 —
standards, not per-dataset rules — and is legitimate future work.

**Template identity is per-corpus.** Two databases ingested from different
corpora may merge differently; templates are stable within a DB (the extractor
bootstraps existing templates and matches against them), not across DBs.
*Living with it:* determinism guarantees the same corpus always yields the
same set; cross-DB comparison should compare patterns, not IDs.
