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
        Instant to   = Instant.now();
        Instant from = window != null ? to.minus(window) : Instant.EPOCH;
        return replayLogs(from, to);
    }

    public List<String> replayLogs(Instant from, Instant to) {
        List<TimestampedLine> lines = new ArrayList<>();

        for (LogEntry entry : logEntryDao.findByTimeRange(from, to)) {
            lines.add(new TimestampedLine(entry.getLogTimestamp(), reconstructor.reconstruct(entry)));
        }

        for (RawLog raw : rawLogDao.findByTimeRange(from, to)) {
            lines.add(new TimestampedLine(raw.getLogTimestamp(), raw.getContent()));
        }

        lines.sort(java.util.Comparator.comparing(TimestampedLine::timestamp));
        return lines.stream().map(TimestampedLine::line).collect(Collectors.toList());
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
