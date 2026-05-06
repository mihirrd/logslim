package com.logslim.api;

import com.logslim.query.TemplateQueryService;
import com.logslim.query.TemplateQueryService.SlotStats;
import com.logslim.query.TemplateQueryService.TemplateDetailFull;
import com.logslim.reconstruction.LogReconstructor;
import com.logslim.storage.LogEntry;
import com.logslim.storage.Template;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/templates")
public class TemplateController {

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private final TemplateQueryService queryService;
    private final LogReconstructor reconstructor;

    public TemplateController(TemplateQueryService queryService, LogReconstructor reconstructor) {
        this.queryService = queryService;
        this.reconstructor = reconstructor;
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String last) {

        List<Template> templates;
        if (search != null && !search.isBlank()) {
            templates = queryService.searchTemplates(search, limit);
        } else {
            Duration window = last != null ? parseDuration(last) : null;
            templates = queryService.listTopTemplates(window, limit);
        }
        return templates.stream().map(this::toMap).collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> inspect(
            @PathVariable long id,
            @RequestParam(defaultValue = "10") int recent) {

        return queryService.getTemplateFull(id, recent)
                .map(this::detailToMap)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    private Map<String, Object> toMap(Template t) {
        return Map.of(
            "id",        t.getId(),
            "pattern",   t.getPattern(),
            "hits",      t.getOccurrences(),
            "createdAt", t.getCreatedAt().toEpochMilli(),
            "updatedAt", t.getUpdatedAt().toEpochMilli()
        );
    }

    private Map<String, Object> detailToMap(TemplateDetailFull d) {
        Template t = d.template();

        List<Map<String, Object>> slots = d.slotStats().stream().map(s ->
            Map.<String, Object>of(
                "index",     s.slotIndex(),
                "type",      s.slotType(),
                "distinct",  s.distinctCount(),
                "topValues", s.topValues().stream()
                              .map(e -> Map.of("value", e.getKey(), "count", e.getValue()))
                              .collect(Collectors.toList())
            )
        ).collect(Collectors.toList());

        List<Map<String, String>> recentLogs = d.recentEntries().stream().map(e -> {
            String ts   = TS_FMT.format(e.getLogTimestamp());
            String line = reconstructEntry(t, e);
            return Map.of("ts", ts, "line", line);
        }).collect(Collectors.toList());

        return Map.of(
            "id",         t.getId(),
            "pattern",    t.getPattern(),
            "hits",       t.getOccurrences(),
            "createdAt",  t.getCreatedAt().toEpochMilli(),
            "updatedAt",  t.getUpdatedAt().toEpochMilli(),
            "slotStats",  slots,
            "recentLogs", recentLogs
        );
    }

    private String reconstructEntry(Template t, LogEntry e) {
        try {
            return reconstructor.reconstruct(t.getPattern(), e.getParameterValues());
        } catch (Exception ex) {
            return e.getParameterValues().toString();
        }
    }

    private Duration parseDuration(String s) {
        s = s.trim().toLowerCase();
        if (s.endsWith("d")) return Duration.ofDays(Long.parseLong(s.replace("d", "")));
        if (s.endsWith("h")) return Duration.ofHours(Long.parseLong(s.replace("h", "")));
        if (s.endsWith("m")) return Duration.ofMinutes(Long.parseLong(s.replace("m", "")));
        return Duration.ofHours(1);
    }
}
