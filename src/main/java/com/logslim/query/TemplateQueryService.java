package com.logslim.query;

import com.logslim.extraction.TemplateNormalizer;
import com.logslim.storage.LogEntry;
import com.logslim.storage.LogEntryDao;
import com.logslim.storage.Template;
import com.logslim.storage.TemplateDao;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
public class TemplateQueryService {

    private final TemplateDao templateDao;
    private final LogEntryDao logEntryDao;
    private final TemplateNormalizer normalizer;

    @Value("${logslim.query.default-page-size:50}")
    private int defaultPageSize;

    public TemplateQueryService(TemplateDao templateDao, LogEntryDao logEntryDao,
                                TemplateNormalizer normalizer) {
        this.templateDao = templateDao;
        this.logEntryDao = logEntryDao;
        this.normalizer  = normalizer;
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

    public Optional<TemplateDetailFull> getTemplateFull(long templateId, int recentCount, int topN) {
        return getTemplateFull(templateId, recentCount, topN, null, null);
    }

    /**
     * Template detail with configurable slot-value breadth and optional example lines.
     * When {@code recentCount <= 0} no reconstructed lines are fetched — the response is
     * pure structure (slot distributions + numeric summaries), which is the token-cheap
     * way to answer "which values / how many / what distribution" without pulling rows.
     * When {@code from}/{@code to} are supplied the slot distributions are scoped to that
     * window (so an agent gets the distribution for the window it localized); pass nulls
     * for all-time (back-compat).
     */
    public Optional<TemplateDetailFull> getTemplateFull(long templateId, int recentCount, int topN,
                                                        Instant from, Instant to) {
        return templateDao.findById(templateId).map(template -> {
            List<LogEntry> recent = recentCount > 0
                    ? logEntryDao.findByTemplateId(templateId, recentCount)
                    : List.of();
            List<SlotStats> stats = buildSlotStats(template, templateId, topN, from, to);
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
     * Per-template window-vs-baseline delta, computed server-side in two aggregation queries
     * so an agent gets {windowCount, baselineCount, ratio} in ONE call instead of fetching two
     * count arrays and diffing by hand. Only templates present in the window are returned.
     * {@code ratio} is window/baseline, or {@code null} when the template is absent from the
     * baseline (a brand-new spike) — sorted those first, then by descending ratio and count.
     */
    public List<TemplateCountDelta> templateCountsWithBaseline(Instant from, Instant to,
                                                               Instant baselineFrom, Instant baselineTo) {
        Map<Long, Long> windowCounts   = logEntryDao.countByTemplateInRange(from, to);
        Map<Long, Long> baselineCounts = logEntryDao.countByTemplateInRange(baselineFrom, baselineTo);
        Set<Long> ids = new HashSet<>(windowCounts.keySet());
        ids.addAll(baselineCounts.keySet());
        Map<Long, Template> templates = templateDao.findByIds(ids);
        return windowCounts.entrySet().stream()
                .map(e -> {
                    long wCount = e.getValue();
                    long bCount = baselineCounts.getOrDefault(e.getKey(), 0L);
                    Template t = templates.get(e.getKey());
                    Double ratio = bCount > 0 ? (double) wCount / bCount : null;
                    return new TemplateCountDelta(e.getKey(),
                            t != null ? t.getPattern() : "(unknown)", wCount, bCount, ratio);
                })
                .sorted(Comparator
                        .<TemplateCountDelta, Double>comparing(
                                d -> d.ratio() != null ? d.ratio() : Double.MAX_VALUE,
                                Comparator.reverseOrder())
                        .thenComparing(Comparator.comparingLong(TemplateCountDelta::windowCount).reversed()))
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

    private List<SlotStats> buildSlotStats(Template template, long templateId, int topN,
                                           Instant from, Instant to) {
        int effectiveTopN = topN > 0 ? topN : 3;
        List<SlotStats> result = new ArrayList<>();
        // Canonical, de-duplicated slot names (shared with query/filter paths) so the `name`
        // the agent sees here is exactly the key it can project/filter on.
        List<String> names = normalizer.slotNames(template.getPattern());
        for (int slotIndex = 0; slotIndex < names.size(); slotIndex++) {
            String name = names.get(slotIndex);
            String type = TemplateNormalizer.slotBaseName(name);
            long distinct = logEntryDao.countDistinctForSlot(templateId, slotIndex, from, to);
            // Strip the redundant `key=` mask from displayed top values (key is the slot name).
            List<Map.Entry<String, Long>> top =
                    logEntryDao.getTopValuesForSlot(templateId, slotIndex, effectiveTopN, from, to)
                            .stream()
                            .map(e -> (Map.Entry<String, Long>) new AbstractMap.SimpleEntry<>(
                                    TemplateNormalizer.stripKeyPrefix(e.getKey(), name), e.getValue()))
                            .collect(Collectors.toList());
            LogEntryDao.NumericAggregate s =
                    logEntryDao.numericSummaryForSlot(templateId, slotIndex, from, to);
            NumericSummary numeric = s == null ? null
                    : new NumericSummary(s.count(), s.min(), s.max(), s.avg(), s.p50(), s.p95(), s.unit());
            result.add(new SlotStats(slotIndex, name, type, distinct, top, numeric));
        }
        return result;
    }

    public record TemplateDetail(Template template, List<LogEntry> recentEntries) {}

    public record SlotStats(int slotIndex, String name, String slotType, long distinctCount,
                            List<Map.Entry<String, Long>> topValues,
                            NumericSummary numeric) {}

    /** Aggregate stats for slots whose values parse as numbers (durations, counts, sizes). */
    public record NumericSummary(long count, double min, double max,
                                 double avg, double p50, double p95, String unit) {}

    public record TemplateDetailFull(Template template, List<LogEntry> recentEntries,
                                     List<SlotStats> slotStats) {}

    public record TemplateCount(long templateId, String pattern, long count) {}

    public record TemplateCountDelta(long templateId, String pattern,
                                     long windowCount, long baselineCount, Double ratio) {}

    public record NewTemplate(long templateId, String pattern, long firstSeenMs, long windowCount) {}
}
