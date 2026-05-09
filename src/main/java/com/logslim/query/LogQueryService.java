package com.logslim.query;

import com.logslim.extraction.TemplateNormalizer;
import com.logslim.reconstruction.LogReconstructor;
import com.logslim.storage.*;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class LogQueryService {

    private final TemplateDao templateDao;
    private final LogEntryDao logEntryDao;
    private final RawLogDao rawLogDao;
    private final LogReconstructor reconstructor;
    private final TemplateNormalizer normalizer;

    public LogQueryService(TemplateDao templateDao, LogEntryDao logEntryDao,
                           RawLogDao rawLogDao, LogReconstructor reconstructor,
                           TemplateNormalizer normalizer) {
        this.templateDao   = templateDao;
        this.logEntryDao   = logEntryDao;
        this.rawLogDao     = rawLogDao;
        this.reconstructor = reconstructor;
        this.normalizer    = normalizer;
    }

    /**
     * Query log entries whose template matches the given pattern and optional parameter filters.
     * Returns reconstructed original log lines, in timestamp order.
     */
    public List<String> queryByPattern(String pattern, Map<String, String> filters, Duration window) {
        Optional<Template> templateOpt = templateDao.findByPattern(normalizePatternInput(pattern));
        if (templateOpt.isEmpty()) return List.of();

        Template template = templateOpt.get();
        Instant from = window != null ? Instant.now().minus(window) : Instant.EPOCH;
        Instant to   = Instant.now();

        List<LogEntry> entries = logEntryDao.findByTimeRange(from, to).stream()
                .filter(e -> e.getTemplateId() == template.getId())
                .collect(Collectors.toList());

        // Apply parameter filters (rebuild named map from pattern + positional values)
        if (!filters.isEmpty()) {
            final Template tmpl = template;
            entries = entries.stream()
                    .filter(e -> matchesFilters(tmpl, e, filters))
                    .collect(Collectors.toList());
        }

        return entries.stream()
                .map(reconstructor::reconstruct)
                .collect(Collectors.toList());
    }

    public List<String> replayLogs(Duration window) {
        return replayLogs(window, Integer.MAX_VALUE);
    }

    public List<String> replayLogs(Duration window, int limit) {
        Instant to   = Instant.now();
        Instant from = window != null ? to.minus(window) : Instant.EPOCH;
        return replayLogs(from, to, limit);
    }

    public List<String> replayLogs(Instant from, Instant to) {
        return replayLogs(from, to, Integer.MAX_VALUE);
    }

    public List<String> replayLogs(Instant from, Instant to, int limit) {
        List<LogEntry> entries = logEntryDao.findByTimeRange(from, to, limit);
        List<RawLog>   raws    = rawLogDao.findByTimeRange(from, to, limit);

        // Bulk-load every template referenced by these entries in one query.
        // Eliminates the N+1 lookup that would otherwise issue one SELECT per entry.
        java.util.Set<Long> templateIds = new java.util.HashSet<>(entries.size());
        for (LogEntry e : entries) templateIds.add(e.getTemplateId());
        Map<Long, Template> templatesById = templateDao.findByIds(templateIds);

        // Build (timestamp, line) pairs for entries, skipping any whose template is
        // missing (referential gap — don't fail the whole replay).
        List<TimestampedLine> entryLines = new ArrayList<>(entries.size());
        for (LogEntry entry : entries) {
            Template t = templatesById.get(entry.getTemplateId());
            if (t == null) continue;
            String header = reconstructor.reconstruct(t.getPattern(), entry.getParameterValues());
            String cont = entry.getContinuationText();
            String line = cont != null && !cont.isEmpty() ? header + "\n" + cont : header;
            Instant ts = entry.getLogTimestamp() != null ? entry.getLogTimestamp() : Instant.EPOCH;
            entryLines.add(new TimestampedLine(ts, line));
        }

        List<TimestampedLine> rawLines = new ArrayList<>(raws.size());
        for (RawLog raw : raws) {
            Instant ts = raw.getLogTimestamp() != null ? raw.getLogTimestamp() : Instant.EPOCH;
            String content = raw.getContent() != null ? raw.getContent() : "";
            rawLines.add(new TimestampedLine(ts, content));
        }

        // Both sides are already ORDER BY log_timestamp ASC from SQL — two-pointer merge.
        List<String> merged = new ArrayList<>(entryLines.size() + rawLines.size());
        int i = 0, j = 0;
        while (i < entryLines.size() && j < rawLines.size()) {
            if (entryLines.get(i).timestamp().compareTo(rawLines.get(j).timestamp()) <= 0) {
                merged.add(entryLines.get(i++).line());
            } else {
                merged.add(rawLines.get(j++).line());
            }
        }
        while (i < entryLines.size()) merged.add(entryLines.get(i++).line());
        while (j < rawLines.size()) merged.add(rawLines.get(j++).line());

        if (merged.size() > limit) {
            // Both sides may have hit the limit; the merged stream could be up to 2*limit.
            List<String> truncated = new ArrayList<>(limit + 1);
            truncated.addAll(merged.subList(0, limit));
            truncated.add("[truncated — showing first " + limit
                    + " lines; widen filter or narrow time window for more]");
            return truncated;
        }
        return merged;
    }


    public List<Template> findSuggestions(String queryPattern, int maxResults) {
        String[] words = queryPattern.split("[\\s{}]+");
        Map<Long, Template> seen = new LinkedHashMap<>();
        for (String word : words) {
            if (word.length() < 3) continue;
            templateDao.findByPatternContaining(word, maxResults * 2)
                       .forEach(t -> seen.putIfAbsent(t.getId(), t));
            if (seen.size() >= maxResults * 3) break;
        }
        return seen.values().stream()
                   .sorted(Comparator.comparingLong(Template::getOccurrences).reversed())
                   .limit(maxResults).toList();
    }

    private boolean matchesFilters(Template template, LogEntry entry, Map<String, String> filters) {
        Map<String, String> paramMap = normalizer.buildParameterMap(
                template.getPattern(), entry.getParameterValues());
        return filters.entrySet().stream()
                .allMatch(f -> f.getValue().equals(paramMap.get(f.getKey())));
    }

    /**
     * Allow callers to pass a display pattern like "User {id} failed login"
     * and normalize it to match the stored form "User {num} failed login".
     * For now, strip the placeholder names and replace with canonical forms.
     */
    private String normalizePatternInput(String pattern) {
        // Accept both user-friendly "{id}" and canonical "{num}" forms
        return pattern
                .replaceAll("\\{id(_\\d+)?}", "{num$1}")
                .replaceAll("\\{x(_\\d+)?}", "{num$1}")
                .replaceAll("\\{count(_\\d+)?}", "{num$1}");
    }

    private record TimestampedLine(Instant timestamp, String line) {}
}
