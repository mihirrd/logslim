package com.logslim.query;

import com.logslim.storage.LogEntry;
import com.logslim.storage.LogEntryDao;
import com.logslim.storage.Template;
import com.logslim.storage.TemplateDao;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

    /**
     * List templates sorted by occurrence count, optionally within a time window.
     * @param window  time window to look back (null = all time)
     * @param limit   max results (0 = use default page size)
     */
    public List<Template> listTopTemplates(Duration window, int limit) {
        Instant since = window != null ? Instant.now().minus(window) : null;
        int effectiveLimit = limit > 0 ? limit : defaultPageSize;
        return templateDao.findTopN(since, effectiveLimit);
    }

    /**
     * Fetch a single template by its numeric ID together with its most recent entries.
     */
    public Optional<TemplateDetail> getTemplate(long templateId, int recentCount) {
        return templateDao.findById(templateId).map(template -> {
            int n = recentCount > 0 ? recentCount : 10;
            List<LogEntry> recent = logEntryDao.findByTemplateId(templateId, n);
            return new TemplateDetail(template, recent);
        });
    }

    public record TemplateDetail(Template template, List<LogEntry> recentEntries) {}
}
