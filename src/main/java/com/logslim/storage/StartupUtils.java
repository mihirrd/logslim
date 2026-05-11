package com.logslim.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

public class StartupUtils {
    public static void ensureSnapshot(String dbPath, Path dataDir, List<String> coreTables) throws Exception {
        Files.createDirectories(dataDir);

        boolean allPresent = true;
        for (String t : coreTables) {
            if (!Files.exists(dataDir.resolve(t + ".parquet"))) {
                allPresent = false;
                break;
            }
        }
        if (allPresent)
            return;

        System.out.println("Bootstrapping Parquet snapshot at " + dataDir + " ...");

        Class.forName("org.duckdb.DuckDBDriver");
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + dbPath);
                Statement s = c.createStatement()) {

            // Idempotent schema setup — must stay in sync with
            // src/main/resources/schema.sql.
            s.execute("CREATE SEQUENCE IF NOT EXISTS templates_id_seq START 1");
            s.execute("CREATE TABLE IF NOT EXISTS templates (" +
                    "  template_id BIGINT DEFAULT nextval('templates_id_seq') PRIMARY KEY," +
                    "  pattern     TEXT   NOT NULL UNIQUE," +
                    "  occurrences BIGINT NOT NULL DEFAULT 1," +
                    "  created_at  TEXT   NOT NULL," +
                    "  updated_at  TEXT   NOT NULL)");

            s.execute("CREATE SEQUENCE IF NOT EXISTS raw_logs_id_seq START 1");
            s.execute("CREATE TABLE IF NOT EXISTS raw_logs (" +
                    "  log_id        BIGINT DEFAULT nextval('raw_logs_id_seq') PRIMARY KEY," +
                    "  content       TEXT   NOT NULL," +
                    "  log_timestamp TEXT   NOT NULL," +
                    "  source        TEXT," +
                    "  created_at    TEXT   NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_raw_timestamp ON raw_logs(log_timestamp)");

            s.execute("CREATE SEQUENCE IF NOT EXISTS log_entries_id_seq START 1");
            s.execute("CREATE TABLE IF NOT EXISTS log_entries (" +
                    "  entry_id          BIGINT DEFAULT nextval('log_entries_id_seq') PRIMARY KEY," +
                    "  template_id       BIGINT NOT NULL REFERENCES templates(template_id)," +
                    "  log_timestamp     BIGINT NOT NULL," +
                    "  parameter_values  TEXT   NOT NULL DEFAULT '[]'," +
                    "  continuation_text TEXT)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_le_template  ON log_entries(template_id)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_le_timestamp ON log_entries(log_timestamp)");

            // Snapshot current data (or empty) to Parquet. If
            // templates/log_entries/raw_logs
            // happen to be unified VIEWS post-compact and the Parquet they reference is
            // missing, this COPY will fail loudly — that's the right behaviour
            // (broken state needs manual repair, not silent bootstrap).
            for (String t : coreTables) {
                Path target = dataDir.resolve(t + ".parquet");
                s.execute("COPY (SELECT * FROM " + t + ") TO '" +
                        target.toAbsolutePath() +
                        "' (FORMAT PARQUET, COMPRESSION ZSTD)");
            }
        }
    }
}
