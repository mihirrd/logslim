package com.logslim.cli;

import picocli.CommandLine.ITypeConverter;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts strings like "10m", "2h", "1d" to java.time.Duration for Picocli options.
 */
public class DurationConverter implements ITypeConverter<Duration> {

    private static final Pattern PATTERN = Pattern.compile("(\\d+)([smhd])");

    @Override
    public Duration convert(String value) {
        Matcher m = PATTERN.matcher(value.trim().toLowerCase());
        if (!m.matches()) {
            throw new IllegalArgumentException(
                    "Invalid duration '" + value + "'. Use format: 10s, 5m, 2h, 1d");
        }
        long amount = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            case "d" -> Duration.ofDays(amount);
            default  -> throw new IllegalArgumentException("Unknown unit: " + m.group(2));
        };
    }
}
