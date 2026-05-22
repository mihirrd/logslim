package com.logslim.query;

import com.logslim.storage.LogEntryDao;
import com.logslim.storage.RawLogDao;
import com.logslim.storage.Template;
import com.logslim.storage.TemplateDao;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class InvestigateService {

    private static final Pattern ERROR_PAT = Pattern.compile(
            "error|exception|fail|timeout|exhausted|oom|killed|panic|fatal|critical",
            Pattern.CASE_INSENSITIVE);

    private final LogEntryDao logEntryDao;
    private final RawLogDao rawLogDao;
    private final TemplateDao templateDao;

    public InvestigateService(LogEntryDao logEntryDao, RawLogDao rawLogDao, TemplateDao templateDao) {
        this.logEntryDao = logEntryDao;
        this.rawLogDao   = rawLogDao;
        this.templateDao = templateDao;
    }

    public InvestigateResult investigate(Instant windowFrom, Instant windowTo, int topN, int rawSample) {
        Duration windowLen    = Duration.between(windowFrom, windowTo);
        Instant baselineFrom  = windowFrom.minus(windowLen);
        Instant baselineTo    = windowFrom;

        // Two aggregation queries — one per window
        Map<Long, Long> windowCounts   = logEntryDao.countByTemplateInRange(windowFrom, windowTo);
        Map<Long, Long> baselineCounts = logEntryDao.countByTemplateInRange(baselineFrom, baselineTo);

        // Bulk-load every template referenced in either window
        Set<Long> allIds = new HashSet<>(windowCounts.keySet());
        allIds.addAll(baselineCounts.keySet());
        Map<Long, Template> templates = templateDao.findByIds(allIds);

        long totalWindow = windowCounts.values().stream().mapToLong(Long::longValue).sum();
        long errorWindow = 0;

        List<SpikeEntry> spikes = new ArrayList<>();
        for (Map.Entry<Long, Long> e : windowCounts.entrySet()) {
            Template t = templates.get(e.getKey());
            if (t == null) continue;
            long wCount = e.getValue();
            long bCount = baselineCounts.getOrDefault(e.getKey(), 0L);
            Double ratio = bCount > 0 ? (double) wCount / bCount : null;
            spikes.add(new SpikeEntry(t.getId(), t.getPattern(), wCount, bCount, ratio));
            if (ERROR_PAT.matcher(t.getPattern()).find()) errorWindow += wCount;
        }

        // Sort: null ratio (new in window) = infinity → highest first, then by ratio, then by count
        spikes.sort(Comparator
                .<SpikeEntry, Double>comparing(
                        s -> s.ratio() != null ? s.ratio() : Double.MAX_VALUE,
                        Comparator.reverseOrder())
                .thenComparingLong(SpikeEntry::windowCount).reversed());

        List<SpikeEntry> topSpikes = spikes.stream().limit(topN).toList();

        // Templates whose first appearance is inside the window — often the trigger
        List<NewTemplateEntry> newTemplates = templates.values().stream()
                .filter(t -> !t.getCreatedAt().isBefore(windowFrom)
                          && !t.getCreatedAt().isAfter(windowTo))
                .sorted(Comparator.comparing(Template::getCreatedAt))
                .map(t -> new NewTemplateEntry(t.getId(), t.getPattern(),
                        t.getCreatedAt().toEpochMilli(),
                        windowCounts.getOrDefault(t.getId(), 0L)))
                .toList();

        List<String> rawLogSample = rawLogDao
                .findByTimeRangeAndSearch(windowFrom, windowTo, null, rawSample)
                .stream().map(r -> r.getContent()).toList();

        double errorRate = totalWindow > 0 ? (double) errorWindow / totalWindow : 0.0;

        return new InvestigateResult(
                windowFrom.toEpochMilli(), windowTo.toEpochMilli(),
                baselineFrom.toEpochMilli(), baselineTo.toEpochMilli(),
                totalWindow, Math.round(errorRate * 1000.0) / 1000.0,
                topSpikes, newTemplates, rawLogSample);
    }

    public record SpikeEntry(
            long templateId,
            String pattern,
            long windowCount,
            long baselineCount,
            Double ratio           // null = never seen in baseline (new spike)
    ) {}

    public record NewTemplateEntry(
            long templateId,
            String pattern,
            long firstSeenMs,
            long windowCount
    ) {}

    public record InvestigateResult(
            long windowFromMs,
            long windowToMs,
            long baselineFromMs,
            long baselineToMs,
            long totalWindowEntries,
            double errorRate,
            List<SpikeEntry> spikes,
            List<NewTemplateEntry> newTemplates,
            List<String> rawLogSample
    ) {}
}
