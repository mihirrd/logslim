package com.logslim.extraction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

import com.logslim.ingestion.LogGroup;
import com.logslim.parsing.EntityMasker;
import com.logslim.parsing.ParsedLog;
import com.logslim.parsing.LogTokenizer;
import com.logslim.parsing.TemplateLearner;
import com.logslim.reconstruction.LogReconstructor;
import com.logslim.storage.LogEntry;
import com.logslim.storage.LogEntryDao;
import com.logslim.storage.RawLog;
import com.logslim.storage.RawLogDao;
import com.logslim.storage.Template;
import com.logslim.storage.TemplateDao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Orchestrates the extraction pipeline for a log line:
 * mask → (learned) template lookup → template DB sync → store log entry
 *
 * <p>Two deterministic layers replace the old Drain tree:
 * <ol>
 *   <li>{@link EntityMasker} masks universal syntax (numbers, IPs, timestamps,
 *       UUIDs, paths) — the masked shape is the provisional template key.</li>
 *   <li>{@link TemplateLearner} (fed by a learning pass over the corpus)
 *       discovers dataset-specific variables statistically and merges shapes
 *       that differ only at high-cardinality positions into one template with
 *       {@code {var}} slots.</li>
 * </ol>
 *
 * <p>There is no lock gate and no learn-in-flight raw pile: every line maps to
 * a template by construction. A line goes to {@code raw_logs} only when the
 * byte-exact round-trip safety net fails or the template-count cap is hit.
 *
 * <p>File ingest calls {@link #learnBatch} over the whole input first, then
 * {@link #finishLearning()}, then {@link #processBatch}. Streaming ingest
 * (Kafka) skips the learning pass: unseen patterns are matched against already
 * learned templates and otherwise become their own (identity) templates.
 */
@Component
public class TemplateExtractor {

    private static final Logger log = LoggerFactory.getLogger(TemplateExtractor.class);

    private final LogTokenizer tokenizer;
    private final EntityMasker masker;
    private final TemplateCache cache;
    private final TemplateDao templateDao;
    private final LogEntryDao logEntryDao;
    private final RawLogDao rawLogDao;
    private final LogReconstructor reconstructor;

    @Value("${logslim.template.max-count:100000}")
    private long maxTemplateCount;

    @Value("${logslim.ingestion.worker-threads:8}")
    private int workerThreads;

    /**
     * A varying token position with at most this many distinct values across the
     * corpus is message semantics (templates stay separate); above it, it is a
     * learned variable (templates merge). See {@link TemplateLearner}.
     */
    @Value("${logslim.parser.max-branching:8}")
    private int maxBranching;

    /** Masked source pattern → persisted template (identity or merged). */
    private final Map<String, Template> patternToTemplate = new ConcurrentHashMap<>();

    /** Masked source pattern → merge plan; absent for identity patterns. */
    private final Map<String, TemplateLearner.MergePlan> plans = new ConcurrentHashMap<>();

    /** Final template pattern → persisted template (dedups creation across source patterns). */
    private final Map<String, Template> finalPatternToTemplate = new ConcurrentHashMap<>();

    /** Merged templates in matchable form, for patterns first seen after learning. */
    private final List<TemplateLearner.LearnedTemplate> learnedTemplates = new CopyOnWriteArrayList<>();

    /** Distinct masked patterns accumulated by {@link #learnBatch} until {@link #finishLearning}. */
    private final Set<String> pendingPatterns = ConcurrentHashMap.newKeySet();

    /**
     * Dedicated pool for the stateless parse stage (tokenize + mask).
     * Sized to workerThreads so parallelism is bounded and the common pool is not starved.
     * Created in @PostConstruct (after @Value injection) and shut down in @PreDestroy.
     */
    private ForkJoinPool parsePool;

    public TemplateExtractor(LogTokenizer tokenizer,
            EntityMasker masker,
            TemplateCache cache,
            TemplateDao templateDao,
            LogEntryDao logEntryDao,
            RawLogDao rawLogDao,
            LogReconstructor reconstructor) {
        this.tokenizer = tokenizer;
        this.masker = masker;
        this.cache = cache;
        this.templateDao = templateDao;
        this.logEntryDao = logEntryDao;
        this.rawLogDao = rawLogDao;
        this.reconstructor = reconstructor;
    }

    /**
     * Seed lookup state from all templates already stored in the DB, and create the parse pool.
     *
     * <p>Two phases: merged ({@code {var}}) templates first, then identity templates.
     * An identity template whose pattern fits an already-learned merged template maps
     * to the merged one — so fold-forward remaps from {@link #relearn()} survive
     * restarts, and future lines never re-fragment. The identity row itself stays
     * (its archived entries reconstruct through its own pattern).
     */
    @PostConstruct
    void bootstrap() {
        parsePool = new ForkJoinPool(workerThreads);
        List<Template> all = templateDao.findAll();
        List<Template> identities = new java.util.ArrayList<>();
        for (Template t : all) {
            TemplateLearner.LearnedTemplate lt = TemplateLearner.fromFinalPattern(t.getPattern());
            if (lt != null) {
                finalPatternToTemplate.put(t.getPattern(), t);
                learnedTemplates.add(lt);
            } else {
                identities.add(t);
            }
        }
        int remapped = 0;
        for (Template t : identities) {
            TemplateLearner.MergePlan plan = matchLearned(t.getPattern());
            if (plan != null) {
                Template canonical = finalPatternToTemplate.get(plan.finalPattern());
                plans.put(t.getPattern(), plan);
                patternToTemplate.put(t.getPattern(), canonical);
                remapped++;
            } else {
                finalPatternToTemplate.put(t.getPattern(), t);
                patternToTemplate.put(t.getPattern(), t);
            }
        }
        if (!all.isEmpty()) {
            log.info("Bootstrapped {} templates ({} merged, {} identity remapped forward)",
                    all.size(), learnedTemplates.size(), remapped);
        }
    }

    /** Match a masked pattern against the learned merged templates; null when none fit. */
    private TemplateLearner.MergePlan matchLearned(String pattern) {
        if (learnedTemplates.isEmpty()) return null;
        String[] toks = TemplateLearner.split(pattern);
        for (TemplateLearner.LearnedTemplate lt : learnedTemplates) {
            TemplateLearner.MergePlan plan = lt.tryMatch(toks);
            if (plan != null) return plan;
        }
        return null;
    }

    @PreDestroy
    void shutdownParsePool() {
        if (parsePool != null) {
            parsePool.shutdown();
        }
    }

    /**
     * Reset in-memory state (used between test runs).
     * Does NOT touch the database — callers must clear tables separately.
     */
    public void reset() {
        patternToTemplate.clear();
        plans.clear();
        finalPatternToTemplate.clear();
        learnedTemplates.clear();
        pendingPatterns.clear();
        cache.clearAll();
    }

    // — learning pass ————————————————————————————————————————————

    /** Pass-1 for file ingest: mask the batch and accumulate distinct patterns. Writes nothing. */
    public void learnBatch(List<LogGroup> groups) {
        List<String> patterns;
        try {
            patterns = parsePool.submit(() ->
                groups.parallelStream()
                      .map(g -> masker.mask(g.headerLine()).pattern())
                      .collect(Collectors.toList())
            ).get();
        } catch (Exception e) {
            throw new RuntimeException("Parallel learn stage failed", e);
        }
        pendingPatterns.addAll(patterns);
    }

    /**
     * Run {@link TemplateLearner} over all patterns seen by {@link #learnBatch}.
     * Deterministic: the learned template set is a pure function of the corpus.
     */
    public void finishLearning() {
        if (pendingPatterns.isEmpty()) return;
        TemplateLearner learner = new TemplateLearner(maxBranching);
        Map<String, TemplateLearner.MergePlan> learned = learner.learn(pendingPatterns);
        plans.putAll(learned);
        Set<String> seenFinals = new java.util.HashSet<>();
        for (TemplateLearner.MergePlan p : learned.values()) {
            if (seenFinals.add(p.finalPattern())) {
                learnedTemplates.add(new TemplateLearner.LearnedTemplate(p));
            }
        }
        log.info("Learned {} merged templates from {} distinct masked patterns",
                seenFinals.size(), pendingPatterns.size());
        pendingPatterns.clear();
    }

    // — incremental relearn (streaming ingest) ————————————————————

    /**
     * Outcome of one {@link #relearn()} round.
     *
     * @param mergedTemplates merged templates created (or reused) this round
     * @param folded          identity templates fully folded: entries rewritten, row deleted
     * @param remappedForward identity templates with archived (immutable) entries: the row
     *                        stays for reconstruction, but future lines map to the merged template
     */
    public record RelearnResult(int mergedTemplates, int folded, int remappedForward) {
        public boolean changedAnything() {
            return mergedTemplates + folded + remappedForward > 0;
        }
    }

    /**
     * Learn over the identity templates accumulated so far and fold fragmented
     * families into merged {@code {var}} templates. The streaming counterpart of
     * the file-ingest learning pass: run it periodically (the Kafka runner calls
     * it before each compact), and each round builds on all prior rounds — shapes
     * that already match a learned template never become identity templates, so
     * the learner only ever reconsiders the unmerged residue.
     *
     * <p>Fold semantics per fragment:
     * <ul>
     *   <li><b>Fully live</b> (template row and all entries still in the writable
     *       tier): entries are rewritten onto the merged template with re-planned
     *       params (byte-exact round-trip preserved), occurrences move, and the
     *       fragment row is deleted.</li>
     *   <li><b>Partially archived</b> (survived an earlier compact): Parquet
     *       partitions are immutable, so the fragment row and its history stay
     *       frozen for reconstruction; only future lines are remapped. Counts for
     *       such a family split at the fold boundary — compare patterns, not ids,
     *       across folds.</li>
     * </ul>
     *
     * Monotone: existing merged templates are never split back apart.
     * Single-writer by design (same thread as ingest), like everything else here.
     */
    public RelearnResult relearn() {
        // Active identity templates: not merged, and not already remapped forward.
        List<Template> identities = new java.util.ArrayList<>();
        for (Template t : templateDao.findAll()) {
            if (TemplateLearner.fromFinalPattern(t.getPattern()) != null) continue;
            Template mapped = patternToTemplate.get(t.getPattern());
            if (mapped != null && !mapped.getId().equals(t.getId())) continue; // already folded forward
            identities.add(t);
        }
        Map<String, TemplateLearner.MergePlan> learned = new TemplateLearner(maxBranching)
                .learn(identities.stream().map(Template::getPattern).toList());
        if (learned.isEmpty()) return new RelearnResult(0, 0, 0);

        int created = 0, folded = 0, remapped = 0;
        Set<String> newFinals = new java.util.HashSet<>();
        Map<Long, Long> occurrenceMoves = new LinkedHashMap<>();

        for (Template fragment : identities) {
            TemplateLearner.MergePlan plan = learned.get(fragment.getPattern());
            if (plan == null) continue;

            Template canonical = finalPatternToTemplate.get(plan.finalPattern());
            if (canonical == null) {
                // occurrences start at 0 — folds below move the real counts over
                canonical = cache.put(new Template(null, plan.finalPattern(), 0,
                        Instant.now(), Instant.now()));
                finalPatternToTemplate.put(plan.finalPattern(), canonical);
                created++;
            }
            if (newFinals.add(plan.finalPattern())) {
                learnedTemplates.add(new TemplateLearner.LearnedTemplate(plan));
            }

            boolean fullyLive = templateDao.existsInWritableTier(fragment.getId())
                    && logEntryDao.countArchivedByTemplateId(fragment.getId()) == 0;
            if (fullyLive) {
                List<LogEntry> entries = logEntryDao.findWritableByTemplateId(fragment.getId());
                for (LogEntry e : entries) {
                    e.setTemplateId(canonical.getId());
                    e.setParameterValues(TemplateLearner.buildParams(
                            fragment.getPattern(), e.getParameterValues(), plan));
                }
                logEntryDao.refileEntries(fragment.getId(), entries);
                occurrenceMoves.merge(canonical.getId(), fragment.getOccurrences(), Long::sum);
                templateDao.deleteFromWritableTier(fragment.getId());
                finalPatternToTemplate.remove(fragment.getPattern());
                cache.invalidate(fragment.getPattern());
                folded++;
            } else {
                remapped++;
            }
            plans.put(fragment.getPattern(), plan);
            patternToTemplate.put(fragment.getPattern(), canonical);
        }

        if (!occurrenceMoves.isEmpty()) {
            templateDao.incrementOccurrencesBatch(occurrenceMoves);
            cache.refreshBatch(occurrenceMoves);
        }
        RelearnResult result = new RelearnResult(created, folded, remapped);
        log.info("Relearn: {} merged templates created, {} identity templates folded, {} remapped forward",
                created, folded, remapped);
        return result;
    }

    // — public processing API ————————————————————————————————————

    public Template process(String rawLine, String source) {
        return process(LogGroup.singleLine(rawLine, source));
    }

    public Template process(LogGroup group) {
        ParsedLog parsed = tokenizer.tokenize(group.headerLine(), group.source());
        // Single-line path has no batch context to carry forward from, so an unstamped line
        // falls back to ingest time — but only after being flagged/counted in the tokenizer.
        Instant ts = resolveTimestamp(group.sourceTimestamp(), parsed, null, Instant.now());

        EntityMasker.MaskResult mr = masker.mask(group.headerLine());
        Resolved r = resolve(mr.pattern());
        if (r == null) {
            storeRaw(group.headerLine(), group.source(), ts);
            return null;
        }

        List<String> paramValues = finalParams(mr, r);
        if (!roundTrips(group.headerLine(), r.template(), paramValues)) {
            storeRaw(rawContent(group), group.source(), ts);
            return null;
        }

        String continuation = group.isMultiLine()
                ? String.join("\n", group.continuationLines())
                : null;
        logEntryDao.insert(new LogEntry(
                null, r.template().getId(), ts, paramValues, continuation));

        if (!r.created()) {
            cache.refresh(r.template());
        }
        return r.template();
    }

    /**
     * Two-stage batch processor.
     *
     * Stage 1 (parallel, CPU): tokenize + mask every group concurrently.
     *   These are pure functions over precompiled patterns — safe to run in parallel.
     *   The dedicated ForkJoinPool (sized to workerThreads) bounds parallelism and
     *   avoids starving the JVM common pool. The stream is ordered, so the resulting
     *   list preserves input order exactly.
     *
     * Stage 2 (sequential, calling thread): iterate in input order, resolve or create
     *   templates (hash lookups — deterministic regardless of order), and accumulate
     *   log entries.
     */
    public void processBatch(List<LogGroup> groups) {
        // — Stage 1: parallel stateless parse (order-preserving) —
        List<ParsedHolder> parsed;
        try {
            parsed = parsePool.submit(() ->
                groups.parallelStream()
                      .map(g -> new ParsedHolder(g,
                              tokenizer.tokenize(g.headerLine(), g.source()),
                              masker.mask(g.headerLine())))
                      .collect(Collectors.toList())
            ).get();
        } catch (Exception e) {
            throw new RuntimeException("Parallel parse stage failed", e);
        }

        // — Stage 2: sequential template resolution + DB accumulation (in input order) —
        List<LogEntry> entries = new ArrayList<>();
        List<RawLog>   raws    = new ArrayList<>();
        Map<Long, Long> occurrenceDeltas = new LinkedHashMap<>();

        // Carry-forward for lines without their own timestamp: an unstamped line inherits the
        // previous line's event time, preserving chronological order. This state is intentionally
        // per-batch (not persisted across processBatch calls) to keep the extractor stateless
        // across ingestion sessions; the first orphan lines of a batch with no preceding real
        // timestamp fall back to ingest "now".
        Instant batchFallback = Instant.now();
        Instant lastKnownTs = null;

        for (ParsedHolder h : parsed) {
            LogGroup group = h.group();
            Instant ts = resolveTimestamp(group.sourceTimestamp(), h.parsed(), lastKnownTs, batchFallback);
            if (group.sourceTimestamp() != null || h.parsed().timestampResolved()) {
                lastKnownTs = ts;
            }

            Resolved r = resolve(h.mask().pattern());
            if (r == null) {
                raws.add(new RawLog(null, group.headerLine(), ts, group.source(), Instant.now()));
                continue;
            }

            List<String> paramValues = finalParams(h.mask(), r);
            if (!roundTrips(group.headerLine(), r.template(), paramValues)) {
                raws.add(new RawLog(null, rawContent(group), ts, group.source(), Instant.now()));
                continue;
            }

            String continuation = group.isMultiLine()
                    ? String.join("\n", group.continuationLines()) : null;
            entries.add(new LogEntry(null, r.template().getId(), ts, paramValues, continuation));

            if (!r.created()) {
                occurrenceDeltas.merge(r.template().getId(), 1L, Long::sum);
            }
        }

        if (!entries.isEmpty()) logEntryDao.insertBatch(entries);
        if (!raws.isEmpty())    rawLogDao.insertBatch(raws);
        if (!occurrenceDeltas.isEmpty()) {
            cache.refreshBatch(occurrenceDeltas);
            templateDao.incrementOccurrencesBatch(occurrenceDeltas);
        }
    }

    public void processBatch(List<String> rawLines, String source) {
        processBatch(rawLines.stream()
                .map(line -> LogGroup.singleLine(line, source))
                .toList());
    }

    // — private helpers ——————————————————————————————————————

    /**
     * Intermediate holder produced by the parallel parse stage.
     * Carries everything the sequential resolve+write stage needs; nothing stateful is touched here.
     */
    private record ParsedHolder(LogGroup group, ParsedLog parsed, EntityMasker.MaskResult mask) {}

    /**
     * A source pattern resolved to its persisted template.
     *
     * @param plan    the merge plan when the pattern folds into a learned template, else null
     * @param created true when this resolution created the template row (occurrences already 1)
     */
    private record Resolved(Template template, TemplateLearner.MergePlan plan, boolean created) {}

    /**
     * Resolve a masked source pattern to its template: direct hit, else learned
     * plan (from this session's learning pass or by matching the pattern against
     * merged templates), else a fresh identity template. Returns null only when
     * a new template is needed but the count cap is reached.
     */
    private Resolved resolve(String pattern) {
        Template direct = patternToTemplate.get(pattern);
        if (direct != null) {
            return new Resolved(direct, plans.get(pattern), false);
        }

        TemplateLearner.MergePlan plan = plans.get(pattern);
        if (plan == null) {
            plan = matchLearned(pattern);
            if (plan != null) plans.put(pattern, plan);
        }

        String finalPattern = plan != null ? plan.finalPattern() : pattern;
        Template template = finalPatternToTemplate.get(finalPattern);
        boolean created = false;
        if (template == null) {
            if (finalPatternToTemplate.size() >= maxTemplateCount) {
                log.warn("Template count at max ({}), falling back to raw storage", maxTemplateCount);
                return null;
            }
            template = cache.put(Template.newTemplate(finalPattern));
            finalPatternToTemplate.put(finalPattern, template);
            created = true;
            log.debug("New template [{}]: {}", template.getId(), finalPattern);
        }
        patternToTemplate.put(pattern, template);
        return new Resolved(template, plan, created);
    }

    /** Final slot values for a line: merged params under a plan, else the masked params as-is. */
    private static List<String> finalParams(EntityMasker.MaskResult mr, Resolved r) {
        return r.plan() != null
                ? TemplateLearner.buildParams(mr.pattern(), mr.params(), r.plan())
                : mr.params();
    }

    /**
     * Byte-exact round-trip safety net: a line is stored structured only if
     * template + params reconstruct it exactly; anything else falls to raw_logs
     * (guarantees losslessness).
     */
    private boolean roundTrips(String headerLine, Template template, List<String> paramValues) {
        try {
            return headerLine.equals(reconstructor.reconstruct(template.getPattern(), paramValues));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String rawContent(LogGroup group) {
        return group.isMultiLine()
                ? group.headerLine() + "\n" + String.join("\n", group.continuationLines())
                : group.headerLine();
    }

    /**
     * Resolve the event timestamp for a group: prefer a source-provided timestamp (e.g. a Kafka
     * record's UTC epoch), else the timestamp parsed from the line, else carry forward the last
     * known event time (so unstamped/continuation lines stay in chronological order), else a
     * stable fallback. Never silently stamps an unparseable line with the wall-clock "now".
     */
    private static Instant resolveTimestamp(Instant sourceTs, ParsedLog parsed,
                                            Instant lastKnownTs, Instant fallback) {
        if (sourceTs != null) return sourceTs;
        if (parsed.timestampResolved()) return parsed.timestamp();
        return lastKnownTs != null ? lastKnownTs : fallback;
    }

    private void storeRaw(String content, String source, Instant ts) {
        rawLogDao.insert(new RawLog(null, content, ts, source, Instant.now()));
    }
}
