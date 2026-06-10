package com.logslim.extraction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

import com.logslim.ingestion.LogGroup;
import com.logslim.parsing.DrainTree;
import com.logslim.parsing.LogTokenizer;
import com.logslim.parsing.ParsedLog;
import com.logslim.parsing.Token;
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
 * Orchestrates the full extraction pipeline for a single log line:
 * parse → pre-mask → Drain matching → template DB sync → store log entry
 *
 * Pre-masking converts tokens already classified as DYNAMIC by TokenClassifier
 * into wildcards before passing to Drain, so the regex knowledge bootstraps
 * the algorithm and is not re-learned from scratch.
 *
 * Lines processed while a Drain cluster is still learning (pre-lock) are stored
 * as raw_logs. Once a cluster locks it is written to the templates table and
 * all
 * subsequent matching lines are stored as structured log_entries.
 */
@Component
public class TemplateExtractor {

    private static final Logger log = LoggerFactory.getLogger(TemplateExtractor.class);

    private final LogTokenizer tokenizer;
    private final TemplateNormalizer normalizer;
    private final DrainTree drainTree;
    private final TemplateCache cache;
    private final TemplateDao templateDao;
    private final LogEntryDao logEntryDao;
    private final RawLogDao rawLogDao;
    private final LogReconstructor reconstructor;

    @Value("${logslim.template.max-count:100000}")
    private long maxTemplateCount;

    @Value("${logslim.ingestion.worker-threads:8}")
    private int workerThreads;

    /** Maps Drain raw pattern (e.g. "User <*> login") → persisted Template. */
    private final Map<String, Template> drainPatternToTemplate = new ConcurrentHashMap<>();

    /**
     * Dedicated pool for the stateless parse stage (tokenize + pre-mask).
     * Sized to workerThreads so parallelism is bounded and the common pool is not starved.
     * Created in @PostConstruct (after @Value injection) and shut down in @PreDestroy.
     */
    private ForkJoinPool parsePool;

    public TemplateExtractor(LogTokenizer tokenizer,
            TemplateNormalizer normalizer,
            DrainTree drainTree,
            TemplateCache cache,
            TemplateDao templateDao,
            LogEntryDao logEntryDao,
            RawLogDao rawLogDao,
            LogReconstructor reconstructor) {
        this.tokenizer = tokenizer;
        this.normalizer = normalizer;
        this.drainTree = drainTree;
        this.cache = cache;
        this.templateDao = templateDao;
        this.logEntryDao = logEntryDao;
        this.rawLogDao = rawLogDao;
        this.reconstructor = reconstructor;
    }

    /** Seed the Drain tree from all templates already stored in the DB, and create the parse pool. */
    @PostConstruct
    void bootstrapDrain() {
        parsePool = new ForkJoinPool(workerThreads);
        List<Template> all = templateDao.findAll();
        drainTree.bootstrap(all);
        for (Template t : all) {
            String rawPattern = rawPattern(DrainTree.toWildcardTokens(t.getPattern()));
            drainPatternToTemplate.put(rawPattern, t);
        }
        if (!all.isEmpty()) {
            log.info("Bootstrapped Drain tree with {} existing templates", all.size());
        }
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
        drainTree.reset();
        drainPatternToTemplate.clear();
        cache.clearAll();
    }

    // — public API ————————————————————————————————————————————

    public Template process(String rawLine, String source) {
        return process(LogGroup.singleLine(rawLine, source));
    }

    public Template process(LogGroup group) {
        ParsedLog parsed = tokenizer.tokenize(group.headerLine(), group.source());
        // Single-line path has no batch context to carry forward from, so an unstamped line
        // falls back to ingest time — but only after being flagged/counted in the tokenizer.
        Instant ts = resolveTimestamp(group.sourceTimestamp(), parsed, null, Instant.now());
        List<Token> orig = parsed.tokens();
        List<String> masked = toPreMasked(orig);

        DrainTree.ProcessResult drain = drainTree.process(masked);

        if (!drain.isLocked()) {
            storeRaw(group.headerLine(), group.source(), ts);
            return null;
        }

        Template template = resolveTemplate(drain, orig, group.headerLine());
        if (template == null) {
            storeRaw(group.headerLine(), group.source(), ts);
            return null;
        }

        List<String> paramValues = extractParamValues(drain.templateTokens(), orig);

        // Safety net: only store as a structured entry if the header round-trips byte-exactly.
        // Falls back to raw for any mismatch or reconstruction failure (guarantees losslessness).
        String reconstructedHeader;
        try {
            reconstructedHeader = reconstructor.reconstruct(template.getPattern(), paramValues);
        } catch (RuntimeException e) {
            reconstructedHeader = null;
        }
        if (!group.headerLine().equals(reconstructedHeader)) {
            String rawContent = group.isMultiLine()
                    ? group.headerLine() + "\n" + String.join("\n", group.continuationLines())
                    : group.headerLine();
            storeRaw(rawContent, group.source(), ts);
            return null;
        }

        String continuation = group.isMultiLine()
                ? String.join("\n", group.continuationLines())
                : null;
        logEntryDao.insert(new LogEntry(
                null, template.getId(), ts, paramValues, continuation));

        if (!drain.justLocked()) {
            cache.refresh(template);
        }
        return template;
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

    /**
     * Intermediate holder produced by the parallel parse stage.
     * Carries everything the sequential Drain+write stage needs; nothing stateful is touched here.
     */
    private record ParsedHolder(
            LogGroup group,
            ParsedLog parsed,
            List<Token> orig,
            List<String> masked
    ) {}

    /**
     * Two-stage batch processor.
     *
     * Stage 1 (parallel, CPU): tokenize + toPreMasked for every group concurrently.
     *   These are pure functions over precompiled patterns — safe to run in parallel.
     *   The dedicated ForkJoinPool (sized to workerThreads) bounds parallelism and
     *   avoids starving the JVM common pool. The stream is ordered, so the resulting
     *   list preserves input order exactly.
     *
     * Stage 2 (sequential, calling thread): iterate ParsedHolders in input order,
     *   run drainTree.process (stateful, order-dependent), resolve/create templates,
     *   and accumulate log entries — identical logic to before. Drain learning order
     *   is fully deterministic because this stage is single-threaded and in-order.
     */
    public void processBatch(List<LogGroup> groups) {
        // — Stage 1: parallel stateless parse (order-preserving) —
        List<ParsedHolder> parsed;
        try {
            parsed = parsePool.submit(() ->
                groups.parallelStream()
                      .map(g -> {
                          ParsedLog p = tokenizer.tokenize(g.headerLine(), g.source());
                          return new ParsedHolder(g, p, p.tokens(), toPreMasked(p.tokens()));
                      })
                      .collect(Collectors.toList())
            ).get();
        } catch (Exception e) {
            throw new RuntimeException("Parallel parse stage failed", e);
        }

        // — Stage 2: sequential Drain matching + DB accumulation (in input order) —
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
            List<Token> orig = h.orig();
            List<String> masked = h.masked();
            ParsedLog p = h.parsed();
            // Prefer the source-provided timestamp (e.g. Kafka record UTC epoch); else the
            // timestamp parsed from the line; else carry forward the last known event time.
            Instant ts = resolveTimestamp(group.sourceTimestamp(), p, lastKnownTs, batchFallback);
            if (group.sourceTimestamp() != null || p.timestampResolved()) {
                lastKnownTs = ts;
            }

            DrainTree.ProcessResult drain = drainTree.process(masked);

            if (!drain.isLocked()) {
                raws.add(new RawLog(null, group.headerLine(), ts, group.source(), Instant.now()));
                continue;
            }

            Template template = resolveTemplate(drain, orig, group.headerLine());
            if (template == null) {
                raws.add(new RawLog(null, group.headerLine(), ts, group.source(), Instant.now()));
                continue;
            }

            List<String> paramValues = extractParamValues(drain.templateTokens(), orig);

            // Safety net: only store as a structured entry if the header round-trips byte-exactly.
            // Falls back to raw for any mismatch or reconstruction failure (guarantees losslessness).
            String reconstructedHeader;
            try {
                reconstructedHeader = reconstructor.reconstruct(template.getPattern(), paramValues);
            } catch (RuntimeException e) {
                reconstructedHeader = null;
            }
            if (!group.headerLine().equals(reconstructedHeader)) {
                String rawContent = group.isMultiLine()
                        ? group.headerLine() + "\n" + String.join("\n", group.continuationLines())
                        : group.headerLine();
                raws.add(new RawLog(null, rawContent, ts, group.source(), Instant.now()));
                continue;
            }

            String continuation = group.isMultiLine()
                    ? String.join("\n", group.continuationLines()) : null;
            entries.add(new LogEntry(null, template.getId(), ts, paramValues, continuation));

            if (!drain.justLocked()) {
                occurrenceDeltas.merge(template.getId(), 1L, Long::sum);
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
     * Return the persisted Template for this cluster, creating it in the DB
     * on first lock. Returns null if the template-count cap is reached.
     *
     * @param rawLine the original log line used to build a whitespace-preserving pattern
     */
    private Template resolveTemplate(DrainTree.ProcessResult drain, List<Token> origTokens,
            String rawLine) {
        String raw = rawPattern(drain.templateTokens());
        Template existing = drainPatternToTemplate.get(raw);
        if (existing != null)
            return existing;

        // Just locked — write to DB for the first time
        if (drainPatternToTemplate.size() >= maxTemplateCount) {
            log.warn("Template count at max ({}), falling back to raw storage", maxTemplateCount);
            return null;
        }

        String typed = buildTypedPattern(drain.templateTokens(), origTokens, rawLine);
        Template template = cache.put(Template.newTemplate(typed));
        drainPatternToTemplate.put(raw, template);
        log.debug("New template [{}]: {}", template.getId(), typed);
        return template;
    }

    /** Replace typed {placeholder} tokens with <*> and join with spaces. */
    private static String rawPattern(List<String> tokens) {
        return String.join(" ", tokens);
    }

    /**
     * Replace each wildcard position with a typed placeholder name derived
     * from the original token value at that position, while preserving the
     * exact inter-token whitespace from the originating raw line.
     *
     * The resulting pattern encodes real whitespace (e.g. "INFO  [svc]" for a
     * log format that pads INFO to 5 chars) so that LogReconstructor can emit
     * the original line byte-for-byte without any normalisation.
     *
     * If the raw line cannot be walked token-by-token (e.g. an empty line or
     * token count mismatch), falls back to single-space joining.
     */
    private String buildTypedPattern(List<String> templateTokens, List<Token> origTokens,
            String rawLine) {
        if (rawLine == null || rawLine.isEmpty() || templateTokens.isEmpty()) {
            return buildTypedPatternNormalized(templateTokens, origTokens);
        }

        int n = templateTokens.size();
        // Part values as extracted by the tokenizer (rawLine.trim().split("\\s+"))
        String[] parts = rawLine.trim().split("\\s+");
        if (parts.length != n) {
            return buildTypedPatternNormalized(templateTokens, origTokens);
        }

        StringBuilder sb = new StringBuilder(rawLine.length() + 32);
        int cursor = 0; // position in rawLine

        for (int i = 0; i < n; i++) {
            String tokenValue = parts[i];
            // Find the start of this token in rawLine from current cursor
            int tokenStart = rawLine.indexOf(tokenValue, cursor);
            if (tokenStart < 0) {
                // Shouldn't happen for well-formed lines; fall back
                return buildTypedPatternNormalized(templateTokens, origTokens);
            }
            // Emit the gap (whitespace / leading chars) verbatim
            sb.append(rawLine, cursor, tokenStart);
            // Emit placeholder or static token
            String tok = templateTokens.get(i);
            if (DrainTree.WILDCARD.equals(tok)) {
                Token orig = i < origTokens.size() ? origTokens.get(i) : null;
                sb.append(orig != null ? normalizer.placeholderFor(orig) : "{val}");
            } else {
                sb.append(tokenValue);
            }
            cursor = tokenStart + tokenValue.length();
        }
        // Append trailing whitespace (if any)
        if (cursor < rawLine.length()) {
            sb.append(rawLine, cursor, rawLine.length());
        }
        return sb.toString();
    }

    /** Fallback: join with single spaces (original behaviour). */
    private String buildTypedPatternNormalized(List<String> templateTokens, List<Token> origTokens) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < templateTokens.size(); i++) {
            if (i > 0) sb.append(' ');
            String tok = templateTokens.get(i);
            if (DrainTree.WILDCARD.equals(tok)) {
                Token orig = i < origTokens.size() ? origTokens.get(i) : null;
                sb.append(orig != null ? normalizer.placeholderFor(orig) : "{val}");
            } else {
                sb.append(tok);
            }
        }
        return sb.toString();
    }

    /**
     * Extract actual parameter values from the original tokens at wildcard
     * positions.
     */
    private static List<String> extractParamValues(List<String> templateTokens, List<Token> origTokens) {
        List<String> params = new ArrayList<>();
        for (int i = 0; i < templateTokens.size(); i++) {
            if (DrainTree.WILDCARD.equals(templateTokens.get(i))) {
                params.add(i < origTokens.size() ? origTokens.get(i).value() : null);
            }
        }
        return params;
    }

    /**
     * Pre-mask tokens: tokens already classified as DYNAMIC become wildcards
     * so Drain starts with regex-known dynamic positions already marked.
     */
    private static List<String> toPreMasked(List<Token> tokens) {
        List<String> result = new ArrayList<>(tokens.size());
        for (Token t : tokens) {
            result.add(t.isDynamic() ? DrainTree.WILDCARD : t.value());
        }
        return result;
    }

    private void storeRaw(String content, String source, Instant ts) {
        rawLogDao.insert(new RawLog(null, content, ts, source, Instant.now()));
    }
}
