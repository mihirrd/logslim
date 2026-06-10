package com.logslim.api;

import com.logslim.query.TemplateQueryService;
import com.logslim.query.TemplateQueryService.SlotStats;
import com.logslim.query.TemplateQueryService.TemplateDetailFull;
import com.logslim.reconstruction.LogReconstructor;
import com.logslim.storage.LogEntry;
import com.logslim.storage.Template;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class TemplateController {

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private final TemplateQueryService queryService;
    private final LogReconstructor reconstructor;

    public TemplateController(TemplateQueryService queryService, LogReconstructor reconstructor) {
        this.queryService = queryService;
        this.reconstructor = reconstructor;
    }

    @GetMapping("/templates")
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

    @GetMapping("/templates/{id}")
    public ResponseEntity<Map<String, Object>> inspect(
            @PathVariable long id,
            @RequestParam(defaultValue = "10") int recent,
            @RequestParam(defaultValue = "5") int topN,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String last) {

        // Scope slot distributions to the window when given; all-time otherwise (back-compat).
        Instant f = null, t = null;
        if (from != null || to != null || last != null) {
            Instant[] range = resolveRange(from, to, last);
            f = range[0];
            t = range[1];
        }
        return queryService.getTemplateFull(id, recent, topN, f, t)
                .map(this::detailToMap)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/templates/{id}/timeseries")
    public ResponseEntity<Map<String, Object>> timeseries(
            @PathVariable long id,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String last,
            @RequestParam(defaultValue = "1m") String bucket) {

        if (queryService.getTemplate(id, 0).isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Duration bucketDuration = parseDuration(bucket);
        Instant f, t;
        if (from != null || to != null) {
            f = from != null ? parseInstant(from) : Instant.EPOCH;
            t = to   != null ? parseInstant(to)   : Instant.now();
        } else {
            Duration window = last != null ? parseDuration(last) : Duration.ofHours(1);
            t = Instant.now();
            f = t.minus(window);
        }

        List<Map<String, Object>> series = queryService.getTimeSeries(id, f, t, bucketDuration)
                .stream()
                .map(e -> Map.<String, Object>of("ts", e.getKey(), "count", e.getValue()))
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "templateId", id,
                "bucketMs",   bucketDuration.toMillis(),
                "series",     series));
    }

    @GetMapping("/anomalies")
    public List<Map<String, Object>> anomalies(
            @RequestParam(defaultValue = "1h") String last) {

        Duration window = parseDuration(last);
        return queryService.getAnomalies(window)
                .stream()
                .map(this::toMap)
                .collect(Collectors.toList());
    }

    @GetMapping("/template-counts")
    public List<Map<String, Object>> templateCounts(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String last,
            @RequestParam(required = false) String baseline) {

        Instant[] range = resolveRange(from, to, last);

        // Default: plain per-template counts. With baseline enabled, compute the
        // window-vs-baseline delta (ratio) server-side in one call so callers don't
        // fetch two arrays and diff by hand. The baseline is the equal-length window
        // immediately preceding the requested window.
        if (baseline == null || baseline.isBlank() || baseline.equalsIgnoreCase("false")) {
            return queryService.templateCounts(range[0], range[1]).stream()
                    .map(c -> Map.<String, Object>of(
                            "templateId", c.templateId(),
                            "pattern",    c.pattern(),
                            "count",      c.count()))
                    .collect(Collectors.toList());
        }

        Duration windowLen = Duration.between(range[0], range[1]);
        Instant baselineFrom = range[0].minus(windowLen);
        Instant baselineTo   = range[0];
        return queryService.templateCountsWithBaseline(range[0], range[1], baselineFrom, baselineTo)
                .stream()
                .map(d -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("templateId",    d.templateId());
                    m.put("pattern",       d.pattern());
                    m.put("windowCount",   d.windowCount());
                    m.put("baselineCount", d.baselineCount());
                    m.put("ratio",         d.ratio() != null ? round(d.ratio()) : null);
                    return m;
                })
                .collect(Collectors.toList());
    }

    @GetMapping("/new-templates")
    public List<Map<String, Object>> newTemplates(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String last) {

        Instant[] range = resolveRange(from, to, last);
        return queryService.newTemplates(range[0], range[1]).stream()
                .map(n -> Map.<String, Object>of(
                        "templateId",  n.templateId(),
                        "pattern",     n.pattern(),
                        "firstSeenMs", n.firstSeenMs(),
                        "windowCount", n.windowCount()))
                .collect(Collectors.toList());
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** Resolve a [from, to] window from explicit timestamps or a relative `last` (default 1h). */
    private Instant[] resolveRange(String from, String to, String last) {
        Instant t = to != null ? parseInstant(to) : Instant.now();
        Instant f;
        if (from != null) {
            f = parseInstant(from);
        } else {
            Duration window = last != null ? parseDuration(last) : Duration.ofHours(1);
            f = t.minus(window);
        }
        return new Instant[]{f, t};
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

        List<Map<String, Object>> slots = d.slotStats().stream().map(s -> {
            Map<String, Object> slot = new java.util.LinkedHashMap<>();
            slot.put("index",    s.slotIndex());
            slot.put("name",     s.name());
            slot.put("type",     s.slotType());
            slot.put("distinct", s.distinctCount());
            slot.put("topValues", s.topValues().stream()
                    .map(e -> Map.of("value", e.getKey(), "count", e.getValue()))
                    .collect(Collectors.toList()));
            if (s.numeric() != null) {
                var n = s.numeric();
                Map<String, Object> num = new java.util.LinkedHashMap<>();
                num.put("count", n.count());
                num.put("min",   n.min());
                num.put("max",   n.max());
                num.put("avg",   round(n.avg()));
                num.put("p50",   round(n.p50()));
                num.put("p95",   round(n.p95()));
                if (n.unit() != null) num.put("unit", n.unit());
                slot.put("numeric", num);
            }
            return slot;
        }).collect(Collectors.toList());

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

    private static final DateTimeFormatter LOCAL_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Instant parseInstant(String s) {
        try { return Instant.parse(s); } catch (DateTimeParseException ignored) {}
        try { return LocalDateTime.parse(s, LOCAL_FMT).toInstant(ZoneOffset.UTC); }
        catch (DateTimeParseException ignored) {}
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid timestamp '" + s + "'; expected ISO-8601 or 'yyyy-MM-dd HH:mm:ss' (UTC).");
    }

    private Duration parseDuration(String s) {
        return DurationParser.parse(s);
    }
}
