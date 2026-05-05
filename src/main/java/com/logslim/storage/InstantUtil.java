package com.logslim.storage;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Always format Instants with exactly 9 fractional-second digits so that
 * ISO strings sort lexicographically in the same order as chronologically.
 *
 * Instant.toString() trims trailing zeros (e.g. ".390Z" vs ".390993Z"),
 * which breaks SQLite ORDER BY log_timestamp ASC.
 */
public final class InstantUtil {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'")
                             .withZone(ZoneOffset.UTC);

    private InstantUtil() {}

    public static String format(Instant instant) {
        return FMT.format(instant);
    }

    public static Instant parse(String s) {
        return Instant.parse(s);
    }
}
