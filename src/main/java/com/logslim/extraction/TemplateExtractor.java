package com.logslim.extraction;

import com.logslim.parsing.LogTokenizer;
import com.logslim.parsing.ParsedLog;
import com.logslim.storage.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Orchestrates the full extraction pipeline for a single log line:
 *   parse → normalize → match/create template → store log entry
 */
@Component
public class TemplateExtractor {

    private static final Logger log = LoggerFactory.getLogger(TemplateExtractor.class);

    private final LogTokenizer tokenizer;
    private final TemplateNormalizer normalizer;
    private final TemplateMatcher matcher;
    private final TemplateCache cache;
    private final TemplateDao templateDao;
    private final LogEntryDao logEntryDao;
    private final RawLogDao rawLogDao;

    @Value("${logslim.template.max-count:100000}")
    private long maxTemplateCount;

    public TemplateExtractor(LogTokenizer tokenizer,
                             TemplateNormalizer normalizer,
                             TemplateMatcher matcher,
                             TemplateCache cache,
                             TemplateDao templateDao,
                             LogEntryDao logEntryDao,
                             RawLogDao rawLogDao) {
        this.tokenizer   = tokenizer;
        this.normalizer  = normalizer;
        this.matcher     = matcher;
        this.cache       = cache;
        this.templateDao = templateDao;
        this.logEntryDao = logEntryDao;
        this.rawLogDao   = rawLogDao;
    }

    /**
     * Process a single raw log line and persist it.
     * Returns the template that was matched or created.
     */
    public Template process(String rawLine, String source) {
        ParsedLog parsed = tokenizer.tokenize(rawLine, source);
        String normalizedPattern = normalizer.normalize(parsed.tokens());

        // 1. Look up existing template
        Optional<Template> existingOpt = cache.get(normalizedPattern);

        Template template;
        if (existingOpt.isPresent()) {
            template = existingOpt.get();
            cache.refresh(template);
        } else {
            // 2. Check for template explosion guard
            if (templateDao.count() >= maxTemplateCount) {
                log.warn("Template count at max ({}), falling back to raw storage for: {}",
                        maxTemplateCount, rawLine.length() > 80 ? rawLine.substring(0, 80) : rawLine);
                storeRaw(rawLine, source, parsed.timestamp());
                return null;
            }
            // 3. Create new template
            template = cache.put(Template.newTemplate(normalizedPattern));
            log.debug("New template created: [{}] {}", template.getId(), normalizedPattern);
        }

        // 4. Store the log entry (parameters only)
        Map<String, String> params = normalizer.extractParameters(parsed.tokens());
        LogEntry entry = new LogEntry(
                null,
                template.getId(),
                parsed.timestamp(),
                params,
                source != null ? Map.of("source", source) : Map.of(),
                Instant.now()
        );
        logEntryDao.insert(entry);
        return template;
    }

    /**
     * Process a batch of raw log lines. More efficient than calling process() in a loop
     * when dealing with high-throughput ingestion.
     */
    public void processBatch(List<String> rawLines, String source) {
        rawLines.forEach(line -> process(line, source));
    }

    private void storeRaw(String content, String source, Instant ts) {
        rawLogDao.insert(new RawLog(null, content, ts, source, Instant.now()));
    }
}
