package com.logslim.extraction;

import com.logslim.ingestion.LogGroup;
import com.logslim.parsing.DrainTree;
import com.logslim.parsing.LogTokenizer;
import com.logslim.parsing.ParsedLog;
import com.logslim.parsing.Token;
import com.logslim.storage.*;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    @Value("${logslim.template.max-count:100000}")
    private long maxTemplateCount;

    /** Maps Drain raw pattern (e.g. "User <*> login") → persisted Template. */
    private final Map<String, Template> drainPatternToTemplate = new ConcurrentHashMap<>();

    public TemplateExtractor(LogTokenizer tokenizer,
            TemplateNormalizer normalizer,
            DrainTree drainTree,
            TemplateCache cache,
            TemplateDao templateDao,
            LogEntryDao logEntryDao,
            RawLogDao rawLogDao) {
        this.tokenizer = tokenizer;
        this.normalizer = normalizer;
        this.drainTree = drainTree;
        this.cache = cache;
        this.templateDao = templateDao;
        this.logEntryDao = logEntryDao;
        this.rawLogDao = rawLogDao;
    }

    /** Seed the Drain tree from all templates already stored in the DB. */
    @PostConstruct
    void bootstrapDrain() {
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
        List<Token> orig = parsed.tokens();
        List<String> masked = toPreMasked(orig);

        DrainTree.ProcessResult drain = drainTree.process(masked);

        if (!drain.isLocked()) {
            storeRaw(group.headerLine(), group.source(), parsed.timestamp());
            return null;
        }

        Template template = resolveTemplate(drain, orig);
        if (template == null) {
            storeRaw(group.headerLine(), group.source(), parsed.timestamp());
            return null;
        }

        List<String> paramValues = extractParamValues(drain.templateTokens(), orig);
        String continuation = group.isMultiLine()
                ? String.join("\n", group.continuationLines())
                : null;
        logEntryDao.insert(new LogEntry(
                null, template.getId(), parsed.timestamp(), paramValues, continuation));

        if (!drain.justLocked()) {
            cache.refresh(template);
        }
        return template;
    }

    public void processBatch(List<LogGroup> groups) {
        groups.forEach(this::process);
    }

    public void processBatch(List<String> rawLines, String source) {
        rawLines.stream()
                .map(line -> LogGroup.singleLine(line, source))
                .forEach(this::process);
    }

    // — private helpers ——————————————————————————————————————

    /**
     * Return the persisted Template for this cluster, creating it in the DB
     * on first lock. Returns null if the template-count cap is reached.
     */
    private Template resolveTemplate(DrainTree.ProcessResult drain, List<Token> origTokens) {
        String raw = rawPattern(drain.templateTokens());
        Template existing = drainPatternToTemplate.get(raw);
        if (existing != null)
            return existing;

        // Just locked — write to DB for the first time
        if (templateDao.count() >= maxTemplateCount) {
            log.warn("Template count at max ({}), falling back to raw storage", maxTemplateCount);
            return null;
        }

        String typed = buildTypedPattern(drain.templateTokens(), origTokens);
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
     * from the original token value at that position.
     */
    private String buildTypedPattern(List<String> templateTokens, List<Token> origTokens) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < templateTokens.size(); i++) {
            if (i > 0)
                sb.append(' ');
            String tok = templateTokens.get(i);
            if (DrainTree.WILDCARD.equals(tok)) {
                String value = i < origTokens.size() ? origTokens.get(i).value() : null;
                sb.append(normalizer.placeholderFor(value));
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
