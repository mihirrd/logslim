package com.logslim.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

@Service
public class AdminService {

    private final JdbcTemplate jdbc;

    public AdminService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void compactDatabase(Path dataDir) {
        dataDir.toFile().mkdirs();

        String templatesParquet = dataDir.resolve("templates.parquet").toAbsolutePath().toString();
        String logEntriesParquet = dataDir.resolve("log_entries.parquet").toAbsolutePath().toString();
        String rawLogsParquet = dataDir.resolve("raw_logs.parquet").toAbsolutePath().toString();

        jdbc.execute("COPY templates TO '" + templatesParquet + "' (FORMAT PARQUET, COMPRESSION ZSTD)");
        jdbc.execute("COPY log_entries TO '" + logEntriesParquet + "' (FORMAT PARQUET, COMPRESSION ZSTD)");
        jdbc.execute("COPY raw_logs TO '" + rawLogsParquet + "' (FORMAT PARQUET, COMPRESSION ZSTD)");

        for (String name : List.of("log_entries", "raw_logs", "templates")) {
            dropObject(name);
        }
        jdbc.execute("DROP SEQUENCE IF EXISTS templates_id_seq");
        jdbc.execute("DROP SEQUENCE IF EXISTS log_entries_id_seq");
        jdbc.execute("DROP SEQUENCE IF EXISTS raw_logs_id_seq");

        jdbc.execute("CREATE VIEW templates AS SELECT * FROM read_parquet('" + templatesParquet + "')");
        jdbc.execute("CREATE VIEW log_entries AS SELECT * FROM read_parquet('" + logEntriesParquet + "')");
        jdbc.execute("CREATE VIEW raw_logs AS SELECT * FROM read_parquet('" + rawLogsParquet + "')");
        jdbc.execute("CHECKPOINT");
    }

    public record ClearResult(int entriesDeleted, int templatesDeleted, int rawLogsDeleted) {}

    public ClearResult clearDatabase() {
        int entries   = count("log_entries");
        int templates = count("templates");
        int raw       = count("raw_logs");

        for (String name : List.of("log_entries", "raw_logs", "templates")) {
            dropObject(name);
        }

        recreateTables();
        jdbc.execute("CHECKPOINT");

        return new ClearResult(entries, templates, raw);
    }

    public long calculateParquetSize(Path dataDir) {
        String templatesParquet = dataDir.resolve("templates.parquet").toAbsolutePath().toString();
        String logEntriesParquet = dataDir.resolve("log_entries.parquet").toAbsolutePath().toString();
        String rawLogsParquet = dataDir.resolve("raw_logs.parquet").toAbsolutePath().toString();

        return new File(templatesParquet).length()
                + new File(logEntriesParquet).length()
                + new File(rawLogsParquet).length();
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }

    private void recreateTables() {
        jdbc.execute("""
            CREATE TABLE templates (
                template_id INTEGER PRIMARY KEY AUTOINCREMENT,
                pattern     TEXT    NOT NULL UNIQUE,
                occurrences INTEGER NOT NULL DEFAULT 1,
                created_at  TEXT    NOT NULL,
                updated_at  TEXT    NOT NULL
            )""");
        jdbc.execute("""
            CREATE TABLE log_entries (
                entry_id          INTEGER PRIMARY KEY AUTOINCREMENT,
                template_id       INTEGER NOT NULL REFERENCES templates(template_id),
                log_timestamp     INTEGER NOT NULL,
                parameter_values  TEXT    NOT NULL DEFAULT '[]',
                continuation_text TEXT
            )""");
        jdbc.execute("CREATE INDEX idx_le_template  ON log_entries(template_id)");
        jdbc.execute("CREATE INDEX idx_le_timestamp ON log_entries(log_timestamp)");
        jdbc.execute("""
            CREATE TABLE raw_logs (
                log_id        INTEGER PRIMARY KEY AUTOINCREMENT,
                content       TEXT NOT NULL,
                log_timestamp TEXT NOT NULL,
                source        TEXT,
                created_at    TEXT NOT NULL
            )""");
        jdbc.execute("CREATE INDEX idx_raw_timestamp ON raw_logs(log_timestamp)");
    }

    private void dropObject(String name) {
        List<String> types = jdbc.queryForList(
                "SELECT table_type FROM information_schema.tables WHERE table_name = ?",
                String.class, name);
        if (types.isEmpty()) return;
        if ("VIEW".equals(types.get(0))) {
            jdbc.execute("DROP VIEW " + name);
        } else {
            jdbc.execute("DROP TABLE " + name);
        }
    }
}
