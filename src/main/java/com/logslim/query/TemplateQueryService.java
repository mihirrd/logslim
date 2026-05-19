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
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
        return templateDao.findById(templateId).map(template -> {
            List<LogEntry> recent = logEntryDao.findByTemplateId(templateId,
                    Math.max(recentCount, 10));
            List<SlotStats> stats = buildSlotStats(template, templateId);
            return new TemplateDetailFull(template, recent, stats);
        });
    }

    public List<Template> searchTemplates(String text, int limit) {
        return templateDao.findByPatternContaining(text, limit);
    }

    public List<Template> getAnomalies(Duration window) {
        Instant since = window != null ? Instant.now().minus(window) : Instant.now().minus(Duration.ofHours(1));
        return templateDao.findAnomalies(since);
    }

    public List<FrequencyDelta> compareFrequencies(Duration observedPeriod, Duration baselinePeriod, int limit) {
        Instant now = Instant.now();
        Instant observedFrom = now.minus(observedPeriod);
        Instant baselineFrom = now.minus(baselinePeriod).minus(observedPeriod);
        Instant baselineTo = now.minus(baselinePeriod);

        Map<Long, Long> observed = logEntryDao.getFrequencyByTemplate(observedFrom, now);
        Map<Long, Long> baseline = logEntryDao.getFrequencyByTemplate(baselineFrom, baselineTo);

        java.util.Set<Long> allTemplateIds = new java.util.HashSet<>();
        allTemplateIds.addAll(observed.keySet());
        allTemplateIds.addAll(baseline.keySet());

        List<FrequencyDelta> results = new ArrayList<>();
        for (Long templateId : allTemplateIds) {
            long obsCount = observed.getOrDefault(templateId, 0L);
            long baseCount = baseline.getOrDefault(templateId, 0L);
            boolean isNew = baseCount == 0 && obsCount > 0;
            boolean disappeared = obsCount == 0 && baseCount > 0;

            double changePercent = 0;
            if (baseCount > 0) {
                changePercent = ((double)(obsCount - baseCount) / baseCount) * 100.0;
            } else if (obsCount > 0) {
                changePercent = Double.POSITIVE_INFINITY;
            }

            Optional<Template> template = templateDao.findById(templateId);
            if (template.isPresent()) {
                results.add(new FrequencyDelta(template.get(), obsCount, baseCount, changePercent, isNew, disappeared));
            }
        }

        results.sort((a, b) -> Double.compare(Math.abs(b.changePercent()), Math.abs(a.changePercent())));
        return results.stream().limit(limit).toList();
    }

    public Map<Long, List<FrequencyBucket>> getFrequencyTimeseries(Duration window, int buckets) {
        Instant now = Instant.now();
        Instant from = now.minus(window);

        Map<Long, List<Map<String, Object>>> rawTrends = logEntryDao.getFrequencyTrend(from, now, buckets);
        Map<Long, List<FrequencyBucket>> result = new java.util.LinkedHashMap<>();

        for (var entry : rawTrends.entrySet()) {
            Long templateId = entry.getKey();
            List<Map<String, Object>> trend = entry.getValue();

            List<FrequencyBucket> bucketList = new ArrayList<>();
            Long prevFrequency = null;
            for (Map<String, Object> bucket : trend) {
                long bucketNum = (long) bucket.get("bucketNum");
                long timestamp = (long) bucket.get("timestamp");
                long frequency = (long) bucket.get("frequency");
                Double changeFromPrevious = null;
                if (prevFrequency != null && prevFrequency > 0) {
                    changeFromPrevious = ((double)(frequency - prevFrequency) / prevFrequency) * 100.0;
                }
                bucketList.add(new FrequencyBucket(bucketNum, timestamp, frequency, changeFromPrevious));
                prevFrequency = frequency;
            }
            result.put(templateId, bucketList);
        }

        return result;
    }

    private List<SlotStats> buildSlotStats(Template template, long templateId) {
        List<SlotStats> result = new ArrayList<>();
        int slotIndex = 0;
        for (String token : template.getPattern().split("\\s+")) {
            if (token.startsWith("{") && token.endsWith("}")) {
                String raw = token.substring(1, token.length() - 1);
                int u = raw.lastIndexOf('_');
                String type = (u > 0 && raw.substring(u + 1).matches("\\d+"))
                              ? raw.substring(0, u) : raw;
                long distinct = logEntryDao.countDistinctForSlot(templateId, slotIndex);
                List<Map.Entry<String, Long>> top =
                        logEntryDao.getTopValuesForSlot(templateId, slotIndex, 3);
                result.add(new SlotStats(slotIndex, type, distinct, top));
                slotIndex++;
            }
        }
        return result;
    }

    public record TemplateDetail(Template template, List<LogEntry> recentEntries) {}

    public record SlotStats(int slotIndex, String slotType, long distinctCount,
                            List<Map.Entry<String, Long>> topValues) {}

    public record TemplateDetailFull(Template template, List<LogEntry> recentEntries,
                                     List<SlotStats> slotStats) {}
}
