package com.logslim.api;

import com.logslim.query.InvestigateService;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

@RestController
@RequestMapping("/api")
public class InvestigateController {

    private static final DateTimeFormatter LOCAL_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final InvestigateService investigateService;

    public InvestigateController(InvestigateService investigateService) {
        this.investigateService = investigateService;
    }

    @GetMapping("/investigate")
    public InvestigateService.InvestigateResult investigate(
            @RequestParam(required = false) String window,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "10") int topN,
            @RequestParam(defaultValue = "5")  int rawSample) {

        Instant windowTo   = to   != null ? parseInstant(to)   : Instant.now();
        Instant windowFrom;
        if (from != null) {
            windowFrom = parseInstant(from);
        } else {
            Duration w = window != null ? parseDuration(window) : Duration.ofMinutes(5);
            windowFrom = windowTo.minus(w);
        }

        return investigateService.investigate(windowFrom, windowTo,
                Math.min(topN, 20), Math.min(rawSample, 20));
    }

    private Instant parseInstant(String s) {
        try { return Instant.parse(s); } catch (DateTimeParseException ignored) {}
        try { return LocalDateTime.parse(s, LOCAL_FMT).toInstant(ZoneOffset.UTC); }
        catch (DateTimeParseException ignored) {}
        return Instant.now();
    }

    private Duration parseDuration(String s) {
        s = s.trim().toLowerCase();
        if (s.endsWith("d")) return Duration.ofDays(Long.parseLong(s.replace("d", "")));
        if (s.endsWith("h")) return Duration.ofHours(Long.parseLong(s.replace("h", "")));
        if (s.endsWith("m")) return Duration.ofMinutes(Long.parseLong(s.replace("m", "")));
        if (s.endsWith("s")) return Duration.ofSeconds(Long.parseLong(s.replace("s", "")));
        return Duration.ofMinutes(5);
    }
}
