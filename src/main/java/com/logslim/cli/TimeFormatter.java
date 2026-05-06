package com.logslim.cli;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public final class TimeFormatter {

    private static final DateTimeFormatter ABS_FMT =
            DateTimeFormatter.ofPattern("MMM d yyyy").withZone(ZoneOffset.UTC);

    private TimeFormatter() {}

    public static String relativeTime(Instant t) {
        long seconds = Duration.between(t, Instant.now()).getSeconds();
        if (seconds < 0)         return "just now";
        if (seconds < 60)        return seconds + " sec ago";
        if (seconds < 3600)      return (seconds / 60) + " min ago";
        if (seconds < 86400)     return (seconds / 3600) + " hrs ago";
        if (seconds < 2592000)   return (seconds / 86400) + " days ago";
        if (seconds < 31536000)  return (seconds / 2592000) + " months ago";
        return (seconds / 31536000) + " years ago";
    }

    public static String absoluteDate(Instant t) {
        return ABS_FMT.format(t);
    }
}
