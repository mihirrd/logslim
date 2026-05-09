package com.logslim.api;

import com.logslim.query.LogQueryService;
import org.springframework.web.bind.annotation.*;

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

    public LogController(LogQueryService queryService) {
        this.queryService = queryService;
    }

    @PostMapping("/query")
    public List<String> query(@RequestBody QueryRequest req) {
        Duration window = req.last() != null ? parseDuration(req.last()) : null;
        Map<String, String> filters = req.filters() != null ? req.filters() : Map.of();
        return queryService.queryByPattern(req.pattern(), filters, window);
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

    @GetMapping("/suggestions")
    public List<Map<String, Object>> suggestions(@RequestParam String pattern) {
        return queryService.findSuggestions(pattern, 5).stream()
                .map(t -> Map.<String, Object>of(
                    "id",      t.getId(),
                    "pattern", t.getPattern(),
                    "hits",    t.getOccurrences()))
                .toList();
    }

    private Instant parseInstant(String s) {
        try { return Instant.parse(s); } catch (DateTimeParseException ignored) {}
        try { return LocalDateTime.parse(s, LOCAL_FMT).toInstant(ZoneOffset.UTC); }
        catch (DateTimeParseException ignored) {}
        return Instant.EPOCH;
    }

    private Duration parseDuration(String s) {
        s = s.trim().toLowerCase();
        if (s.endsWith("d")) return Duration.ofDays(Long.parseLong(s.replace("d", "")));
        if (s.endsWith("h")) return Duration.ofHours(Long.parseLong(s.replace("h", "")));
        if (s.endsWith("m")) return Duration.ofMinutes(Long.parseLong(s.replace("m", "")));
        return Duration.ofHours(1);
    }

    record QueryRequest(String pattern, Map<String, String> filters, String last) {}
}
