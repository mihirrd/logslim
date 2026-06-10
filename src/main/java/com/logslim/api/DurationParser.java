package com.logslim.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

/**
 * Strict parser for relative-window / bucket strings like {@code 5m}, {@code 1h},
 * {@code 7d}, {@code 30s}. Rejects anything malformed with HTTP 400 rather than
 * silently defaulting — a bad window must never be coerced into a full-history scan.
 */
final class DurationParser {

    private DurationParser() {}

    static Duration parse(String s) {
        if (s == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing duration value.");
        }
        String v = s.trim().toLowerCase();
        if (v.length() >= 2) {
            char unit = v.charAt(v.length() - 1);
            String num = v.substring(0, v.length() - 1);
            try {
                long n = Long.parseLong(num);
                if (n >= 0) {
                    switch (unit) {
                        case 'd': return Duration.ofDays(n);
                        case 'h': return Duration.ofHours(n);
                        case 'm': return Duration.ofMinutes(n);
                        case 's': return Duration.ofSeconds(n);
                        default: break;
                    }
                }
            } catch (NumberFormatException ignored) { /* fall through to 400 */ }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid duration '" + s + "'; expected e.g. '5m', '1h', '7d', '30s'.");
    }
}
