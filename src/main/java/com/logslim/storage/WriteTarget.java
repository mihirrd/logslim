package com.logslim.storage;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Resolves the correct INSERT target table for a given core base name
 * (`templates`, `log_entries`, `raw_logs`).
 *
 * Pre-compact: writes go straight to the unsuffixed base table.
 * Post-compact: the unsuffixed name is a unified view over `<name>_archive`
 * (Parquet, immutable) and `<name>_live` (writable). Writes must target
 * `<name>_live`.
 *
 * `isCompacted()` is cached because the answer changes only on
 * `compact` / `clear` — both call {@link #invalidate()} when done.
 */
@Component
public class WriteTarget {

    private final NamedParameterJdbcTemplate jdbc;
    private volatile Boolean cachedCompacted;

    public WriteTarget(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isCompacted() {
        Boolean c = cachedCompacted;
        if (c == null) {
            List<String> types = jdbc.queryForList(
                    "SELECT table_type FROM information_schema.tables " +
                    "WHERE table_name IN ('templates','log_entries','raw_logs')",
                    Map.of(), String.class);
            c = types.stream().anyMatch("VIEW"::equals);
            cachedCompacted = c;
        }
        return c;
    }

    public String tableFor(String base) {
        return isCompacted() ? base + "_live" : base;
    }

    public void invalidate() {
        cachedCompacted = null;
    }
}
