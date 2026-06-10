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

    /**
     * When slot-value filters are present they can't be pushed to SQL, so we over-fetch
     * up to this internal ceiling and filter in Java. Bounds memory while still letting a
     * filtered query find matches beyond the requested {@code limit}; matches past the
     * ceiling within the window won't be seen.
     */
    private static final int FILTER_SCAN_CAP = 50_000;

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
        return queryByPattern(pattern, filters, window, Integer.MAX_VALUE);
    }

    public List<String> queryByPattern(String pattern, Map<String, String> filters, Duration window, int limit) {
        Instant to   = Instant.now();
        Instant from = window != null ? to.minus(window) : Instant.EPOCH;
        return queryByPattern(pattern, filters, from, to, limit);
    }

    public List<String> queryByPattern(String pattern, Map<String, String> filters, Instant from, Instant to) {
        return queryByPattern(pattern, filters, from, to, Integer.MAX_VALUE);
    }

    public List<String> queryByPattern(String pattern, Map<String, String> filters,
                                       Instant from, Instant to, int limit) {
        if (limit <= 0) return List.of();
        Optional<Template> templateOpt = templateDao.findByPattern(normalizePatternInput(pattern));
        if (templateOpt.isEmpty()) return List.of();

        Template template = templateOpt.get();

        // Push template_id + time range + LIMIT into SQL so we never materialise the whole
        // window. With filters (which can't be pushed to SQL) we over-fetch to a bounded cap
        // and filter in Java.
        boolean hasFilters = !filters.isEmpty();
        int fetchLimit = hasFilters ? Math.max(limit, FILTER_SCAN_CAP) : limit;
        List<LogEntry> entries = logEntryDao.findByTemplateIdAndTimeRange(
                template.getId(), from, to, fetchLimit);

        if (hasFilters) {
            final Template tmpl = template;
            entries = entries.stream()
                    .filter(e -> matchesFilters(tmpl, e, filters))
                    .collect(Collectors.toList());
        }

        // Bound the result set before reconstruction — a filtered over-fetch may still
        // exceed the requested limit.
        if (entries.size() > limit) {
            entries = entries.subList(0, limit);
        }

        List<String> lines = new ArrayList<>(entries.size());
        for (LogEntry e : entries) {
            try {
                lines.add(reconstructor.reconstruct(e));
            } catch (RuntimeException ex) {
                lines.add("[!] reconstruction failed for entry " + e.getId()
                        + " (template " + e.getTemplateId() + "): " + ex.getMessage());
            }
        }
        return lines;
    }

    /**
     * Structured query: matches occurrences of a template the same way as
     * {@link #queryByPattern}, but instead of reconstructing each full line it
     * returns the template ONCE plus the per-occurrence parameter tuples. The
     * static skeleton of the line is stated a single time rather than repeated
     * per row — this is the token-efficient representation an agent should consume.
     *
     * @param selectSlots if non-empty, project to only these slot names (columnar)
     */
    public StructuredQueryResult queryStructured(String pattern, Map<String, String> filters,
                                                 Instant from, Instant to, int limit,
                                                 List<String> selectSlots) {
        Optional<Template> templateOpt = templateDao.findByPattern(normalizePatternInput(pattern));
        if (templateOpt.isEmpty()) {
            return new StructuredQueryResult(-1, null, List.of(), 0, false, List.of());
        }
        Template template = templateOpt.get();

        // Push template_id + time range + LIMIT into SQL (see queryByPattern). matchedCount
        // is the true window total via a COUNT(*) query — never entries.size() after limiting.
        boolean hasFilters = !filters.isEmpty();
        int cap = Math.max(0, limit);
        int fetchLimit = hasFilters ? Math.max(cap, FILTER_SCAN_CAP) : cap;
        List<LogEntry> entries = logEntryDao.findByTemplateIdAndTimeRange(
                template.getId(), from, to, fetchLimit);
        int rawFetched = entries.size();

        long matched;
        boolean scanCapped = false;
        if (hasFilters) {
            final Template tmpl = template;
            entries = entries.stream()
                    .filter(e -> matchesFilters(tmpl, e, filters))
                    .collect(Collectors.toList());
            // Filters can't be pushed to SQL: matchedCount counts matches within the scanned
            // window only. If the pre-filter fetch hit the cap, matches past it are invisible —
            // flag it so the count isn't trusted as exact.
            matched = entries.size();
            scanCapped = rawFetched >= fetchLimit;
        } else {
            matched = logEntryDao.countByTemplateIdAndTimeRange(template.getId(), from, to);
        }

        // Canonical, de-duplicated slot names — the same keys callers filter/project on.
        List<String> slotNames = normalizer.slotNames(template.getPattern());

        // Optional projection: keep only the requested slot indices.
        List<Integer> keep = null;
        if (selectSlots != null && !selectSlots.isEmpty()) {
            keep = new ArrayList<>();
            for (int i = 0; i < slotNames.size(); i++) {
                if (selectSlots.contains(slotNames.get(i))) keep.add(i);
            }
        }
        List<String> outSlots = keep == null ? slotNames
                : keep.stream().map(slotNames::get).collect(Collectors.toList());

        // Emit values with the redundant `key=` mask stripped (the key is the column name).
        // Read-side only — stored values are untouched so reconstruction stays byte-exact.
        List<List<String>> occurrences = new ArrayList<>(Math.min(cap, entries.size()));
        for (int i = 0; i < entries.size() && occurrences.size() < cap; i++) {
            List<String> pv = entries.get(i).getParameterValues();
            List<Integer> idx = keep != null ? keep : null;
            int n = idx != null ? idx.size() : pv.size();
            List<String> row = new ArrayList<>(n);
            for (int k = 0; k < n; k++) {
                int j = idx != null ? idx.get(k) : k;
                String v = j < pv.size() ? pv.get(j) : null;
                String name = j < slotNames.size() ? slotNames.get(j) : null;
                row.add(TemplateNormalizer.stripKeyPrefix(v, name));
            }
            occurrences.add(row);
        }
        return new StructuredQueryResult(template.getId(), template.getPattern(),
                outSlots, matched, scanCapped, occurrences);
    }

    public record StructuredQueryResult(long templateId, String template, List<String> slots,
                                        long matchedCount, boolean scanCapped,
                                        List<List<String>> occurrences) {}

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
            String header;
            try {
                header = reconstructor.reconstruct(t.getPattern(), entry.getParameterValues());
            } catch (RuntimeException e) {
                header = "[!] reconstruction failed for entry " + entry.getId()
                        + " (template " + entry.getTemplateId() + "): " + e.getMessage();
            }
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
        return filters.entrySet().stream().allMatch(f -> {
            String raw = paramMap.get(f.getKey());
            if (raw == null) return false;
            // Accept either the raw stored value (e.g. "user=usr_1") or the stripped
            // display value (e.g. "usr_1") — the latter is what query/inspect surface,
            // so an agent can filter by exactly the value it just saw.
            return f.getValue().equals(raw)
                || f.getValue().equals(TemplateNormalizer.stripKeyPrefix(raw, f.getKey()));
        });
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
