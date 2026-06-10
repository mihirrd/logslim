package com.logslim.api;

import com.logslim.query.LogQueryService;
import com.logslim.storage.RawLogDao;
import org.springframework.http.HttpStatus;
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

@RestController
@RequestMapping("/api")
public class LogController {

    private static final DateTimeFormatter LOCAL_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final LogQueryService queryService;
    private final RawLogDao rawLogDao;

    public LogController(LogQueryService queryService, RawLogDao rawLogDao) {
        this.queryService = queryService;
        this.rawLogDao = rawLogDao;
    }

    @PostMapping("/query")
    public List<String> query(@RequestBody QueryRequest req) {
        Map<String, String> filters = req.filters() != null ? req.filters() : Map.of();
        int limit = req.limit() != null ? Math.max(1, Math.min(req.limit(), 5000)) : 500;
        Instant[] range = resolveRange(req.from(), req.to(), req.last());
        return queryService.queryByPattern(req.pattern(), filters, range[0], range[1], limit);
    }

    @PostMapping("/query-structured")
    public Map<String, Object> queryStructured(@RequestBody StructuredQueryRequest req) {
        Map<String, String> filters = req.filters() != null ? req.filters() : Map.of();
        int limit = req.limit() != null ? Math.max(1, Math.min(req.limit(), 5000)) : 500;
        Instant[] range = resolveRange(req.from(), req.to(), req.last());

        var result = queryService.queryStructured(
                req.pattern(), filters, range[0], range[1], limit, req.slots());

        return Map.of(
                "templateId",   result.templateId(),
                "template",     result.template() != null ? result.template() : "",
                "slots",        result.slots(),
                "matchedCount", result.matchedCount(),
                "scanCapped",   result.scanCapped(),
                "returned",     result.occurrences().size(),
                "occurrences",  result.occurrences());
    }

    @GetMapping("/replay")
    public List<String> replay(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String last,
            @RequestParam(defaultValue = "5000") int limit) {

        int clamped = Math.max(1, Math.min(limit, 50000));
        if (from != null || to != null) {
            Instant f = from != null ? parseInstant(from) : Instant.EPOCH;
            Instant t = to   != null ? parseInstant(to)   : Instant.now();
            return queryService.replayLogs(f, t, clamped);
        }
        Duration window = last != null ? parseDuration(last) : null;
        return queryService.replayLogs(window, clamped);
    }

    @GetMapping("/raw-logs")
    public List<Map<String, Object>> rawLogs(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String last,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "500") int limit) {

        int clamped = Math.max(1, Math.min(limit, 5000));
        Instant f, t;
        if (from != null || to != null) {
            f = from != null ? parseInstant(from) : Instant.EPOCH;
            t = to   != null ? parseInstant(to)   : Instant.now();
        } else {
            Duration window = last != null ? parseDuration(last) : Duration.ofHours(1);
            t = Instant.now();
            f = t.minus(window);
        }
        return rawLogDao.findByTimeRangeAndSearch(f, t, search, clamped).stream()
                .map(r -> Map.<String, Object>of(
                        "id",      r.getId(),
                        "content", r.getContent(),
                        "ts",      r.getLogTimestamp().toEpochMilli(),
                        "source",  r.getSource()))
                .toList();
    }

    @GetMapping("/suggestions")
    public List<Map<String, Object>> suggestions(@RequestParam String pattern) {
        return queryService.findSuggestions(pattern, 5).stream()
                .map(t -> Map.<String, Object>of(
                    "id",      t.getId(),
                    "pattern", t.getPattern(),
                    "hits",    t.getOccurrences()))
                .toList();
    }

    /**
     * Resolve a [from, to] window for the query endpoints. Absolute from/to win; else a
     * relative `last` ends at now; else EPOCH..now (all history — kept bounded by the SQL
     * row limit, so it no longer scans the whole table). A malformed value yields HTTP 400.
     */
    private Instant[] resolveRange(String from, String to, String last) {
        Instant t = to != null ? parseInstant(to) : Instant.now();
        Instant f;
        if (from != null) {
            f = parseInstant(from);
        } else if (last != null) {
            f = t.minus(parseDuration(last));
        } else {
            f = Instant.EPOCH;
        }
        return new Instant[]{f, t};
    }

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

    record QueryRequest(String pattern, Map<String, String> filters, String from, String to,
                        String last, Integer limit) {}

    record StructuredQueryRequest(String pattern, Map<String, String> filters, String from, String to,
                                  String last, Integer limit, List<String> slots) {}
}
