package com.logslim.query;

import com.logslim.storage.LogEntry;
import com.logslim.storage.LogEntryDao;
import com.logslim.storage.Template;
import com.logslim.storage.TemplateDao;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class TemplateQueryService {

    private final TemplateDao templateDao;
    private final LogEntryDao logEntryDao;

    @Value("${logslim.query.default-page-size:50}")
    private int defaultPageSize;

    public TemplateQueryService(TemplateDao templateDao, LogEntryDao logEntryDao) {
        this.templateDao = templateDao;
        this.logEntryDao = logEntryDao;
    }

    public List<Template> listTopTemplates(Duration window, int limit) {
        Instant since = window != null ? Instant.now().minus(window) : null;
        int effectiveLimit = limit > 0 ? limit : defaultPageSize;
        return templateDao.findTopN(since, effectiveLimit);
    }

    public Optional<TemplateDetail> getTemplate(long templateId, int recentCount) {
        return templateDao.findById(templateId).map(template -> {
            int n = recentCount > 0 ? recentCount : 10;
            List<LogEntry> recent = logEntryDao.findByTemplateId(templateId, n);
            return new TemplateDetail(template, recent);
        });
    }

    public Optional<TemplateDetailFull> getTemplateFull(long templateId, int recentCount) {
        return getTemplateFull(templateId, Math.max(recentCount, 10), 3);
    }

    /**
     * Template detail with configurable slot-value breadth and optional example lines.
     * When {@code recentCount <= 0} no reconstructed lines are fetched — the response is
     * pure structure (slot distributions + numeric summaries), which is the token-cheap
     * way to answer "which values / how many / what distribution" without pulling rows.
     */
    public Optional<TemplateDetailFull> getTemplateFull(long templateId, int recentCount, int topN) {
        return templateDao.findById(templateId).map(template -> {
            List<LogEntry> recent = recentCount > 0
                    ? logEntryDao.findByTemplateId(templateId, recentCount)
                    : List.of();
            List<SlotStats> stats = buildSlotStats(template, templateId, topN);
            return new TemplateDetailFull(template, recent, stats);
        });
    }

    public List<Template> searchTemplates(String text, int limit) {
        return templateDao.findByPatternContaining(text, limit);
    }

    public List<Map.Entry<Long, Long>> getTimeSeries(long templateId, Instant from, Instant to, Duration bucket) {
        long bucketMillis = bucket.toMillis();
        if (bucketMillis <= 0) bucketMillis = Duration.ofMinutes(1).toMillis();

        TreeMap<Long, Long> buckets = new TreeMap<>();
        for (LogEntry e : logEntryDao.findByTemplateIdAndTimeRange(templateId, from, to)) {
            long ts = e.getLogTimestamp().toEpochMilli();
            long bucketStart = (ts / bucketMillis) * bucketMillis;
            buckets.merge(bucketStart, 1L, Long::sum);
        }
        return new ArrayList<>(buckets.entrySet());
    }

    public List<Template> getAnomalies(Duration window) {
        Instant since = window != null ? Instant.now().minus(window) : Instant.now().minus(Duration.ofHours(1));
        return templateDao.findAnomalies(since);
    }

    /**
     * Per-template occurrence counts within a window, highest first. The atomic
     * frequency primitive: call it for a window and for a prior baseline window,
     * then diff the two to find spikes — no baseline policy is baked in here.
     */
    public List<TemplateCount> templateCounts(Instant from, Instant to) {
        Map<Long, Long> counts = logEntryDao.countByTemplateInRange(from, to);
        Map<Long, Template> templates = templateDao.findByIds(counts.keySet());
        return counts.entrySet().stream()
                .map(e -> {
                    Template t = templates.get(e.getKey());
                    return new TemplateCount(e.getKey(),
                            t != null ? t.getPattern() : "(unknown)", e.getValue());
                })
                .sorted(Comparator.comparingLong(TemplateCount::count).reversed())
                .collect(Collectors.toList());
    }

    /**
     * Templates whose earliest log-event timestamp (across all history) falls
     * inside the window — i.e. first appeared here. Event-time based, so it is
     * correct for batch-ingested historical data. Often the incident trigger.
     */
    public List<NewTemplate> newTemplates(Instant from, Instant to) {
        long f = from.toEpochMilli();
        long t = to.toEpochMilli();
        Map<Long, Long> firstSeen = logEntryDao.firstSeenByTemplate();
        HashSet<Long> ids = firstSeen.entrySet().stream()
                .filter(e -> e.getValue() >= f && e.getValue() <= t)
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(HashSet::new));
        if (ids.isEmpty()) return List.of();

        Map<Long, Template> templates = templateDao.findByIds(ids);
        Map<Long, Long> windowCounts = logEntryDao.countByTemplateInRange(from, to);
        return ids.stream()
                .map(id -> {
                    Template tmpl = templates.get(id);
                    return new NewTemplate(id,
                            tmpl != null ? tmpl.getPattern() : "(unknown)",
                            firstSeen.get(id),
                            windowCounts.getOrDefault(id, 0L));
                })
                .sorted(Comparator.comparingLong(NewTemplate::firstSeenMs))
                .collect(Collectors.toList());
    }

    private List<SlotStats> buildSlotStats(Template template, long templateId, int topN) {
        int effectiveTopN = topN > 0 ? topN : 3;
        List<SlotStats> result = new ArrayList<>();
        int slotIndex = 0;
        Matcher m = SLOT_TOKEN.matcher(template.getPattern());
        while (m.find()) {
            String raw = m.group(1);
            int u = raw.lastIndexOf('_');
            String type = (u > 0 && raw.substring(u + 1).matches("\\d+"))
                          ? raw.substring(0, u) : raw;
            long distinct = logEntryDao.countDistinctForSlot(templateId, slotIndex);
            List<Map.Entry<String, Long>> top =
                    logEntryDao.getTopValuesForSlot(templateId, slotIndex, effectiveTopN);
            double[] s = logEntryDao.numericSummaryForSlot(templateId, slotIndex);
            NumericSummary numeric = s == null ? null
                    : new NumericSummary((long) s[0], s[1], s[2], s[3], s[4], s[5]);
            result.add(new SlotStats(slotIndex, type, distinct, top, numeric));
            slotIndex++;
        }
        return result;
    }

    public record TemplateDetail(Template template, List<LogEntry> recentEntries) {}

    private static final Pattern SLOT_TOKEN = Pattern.compile("\\{([^}]*)\\}");

    public record SlotStats(int slotIndex, String slotType, long distinctCount,
                            List<Map.Entry<String, Long>> topValues,
                            NumericSummary numeric) {}

    /** Aggregate stats for slots whose values parse as numbers (durations, counts, sizes). */
    public record NumericSummary(long count, double min, double max,
                                 double avg, double p50, double p95) {}

    public record TemplateDetailFull(Template template, List<LogEntry> recentEntries,
                                     List<SlotStats> slotStats) {}

    public record TemplateCount(long templateId, String pattern, long count) {}

    public record NewTemplate(long templateId, String pattern, long firstSeenMs, long windowCount) {}
}
