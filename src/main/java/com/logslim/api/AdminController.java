package com.logslim.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AdminController {

    private final JdbcTemplate jdbc;

    @Value("${logslim.db.path:logs.duckdb}")
    private String dbPath;

    public AdminController(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc.getJdbcTemplate();
    }

    @PostMapping("/compact")
    public Map<String, Object> compact() {
        Path dataDir = resolveDataDir();
        dataDir.toFile().mkdirs();

        String templatesParquet  = dataDir.resolve("templates.parquet").toAbsolutePath().toString();
        String logEntriesParquet = dataDir.resolve("log_entries.parquet").toAbsolutePath().toString();
        String rawLogsParquet    = dataDir.resolve("raw_logs.parquet").toAbsolutePath().toString();

        jdbc.execute("COPY templates TO '"  + templatesParquet  + "' (FORMAT PARQUET, COMPRESSION ZSTD)");
        jdbc.execute("COPY log_entries TO '" + logEntriesParquet + "' (FORMAT PARQUET, COMPRESSION ZSTD)");
        jdbc.execute("COPY raw_logs TO '"   + rawLogsParquet    + "' (FORMAT PARQUET, COMPRESSION ZSTD)");

        for (String name : List.of("log_entries", "raw_logs", "templates")) {
            dropObject(name);
        }
        jdbc.execute("DROP SEQUENCE IF EXISTS templates_id_seq");
        jdbc.execute("DROP SEQUENCE IF EXISTS log_entries_id_seq");
        jdbc.execute("DROP SEQUENCE IF EXISTS raw_logs_id_seq");

        jdbc.execute("CREATE VIEW templates   AS SELECT * FROM read_parquet('" + templatesParquet  + "')");
        jdbc.execute("CREATE VIEW log_entries AS SELECT * FROM read_parquet('" + logEntriesParquet + "')");
        jdbc.execute("CREATE VIEW raw_logs    AS SELECT * FROM read_parquet('" + rawLogsParquet    + "')");
        jdbc.execute("CHECKPOINT");

        return Map.of("message", "Compaction complete. Data exported to " + dataDir);
    }

    @PostMapping("/clear")
    public Map<String, Object> clear() {
        int entries   = jdbc.update("DELETE FROM log_entries");
        int templates = jdbc.update("DELETE FROM templates");
        int raw       = jdbc.update("DELETE FROM raw_logs");
        return Map.of(
            "entriesDeleted",   entries,
            "templatesDeleted", templates,
            "rawLogsDeleted",   raw
        );
    }

    private void dropObject(String name) {
        String type = jdbc.queryForObject(
                "SELECT table_type FROM information_schema.tables WHERE table_name = ?",
                String.class, name);
        if (type == null) return;
        if ("VIEW".equals(type)) {
            jdbc.execute("DROP VIEW " + name);
        } else {
            jdbc.execute("DROP TABLE " + name);
        }
    }

    private Path resolveDataDir() {
        String base = dbPath.endsWith(".duckdb") ? dbPath.substring(0, dbPath.length() - 7) : dbPath;
        return Paths.get(base + "_data").toAbsolutePath();
    }
}
