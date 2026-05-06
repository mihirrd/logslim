package com.logslim.api;

import com.logslim.storage.LogEntryDao;
import com.logslim.storage.RawLogDao;
import com.logslim.storage.TemplateDao;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final TemplateDao templateDao;
    private final LogEntryDao logEntryDao;
    private final RawLogDao rawLogDao;

    public StatsController(TemplateDao templateDao, LogEntryDao logEntryDao, RawLogDao rawLogDao) {
        this.templateDao = templateDao;
        this.logEntryDao = logEntryDao;
        this.rawLogDao   = rawLogDao;
    }

    @GetMapping
    public Map<String, Long> stats() {
        return Map.of(
            "templateCount",  templateDao.count(),
            "logEntryCount",  logEntryDao.count(),
            "rawLogCount",    rawLogDao.count()
        );
    }
}
