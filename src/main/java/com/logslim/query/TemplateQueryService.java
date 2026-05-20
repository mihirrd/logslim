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
