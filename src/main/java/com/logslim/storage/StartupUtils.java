package com.logslim.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

/**
 * Bootstrap utility: ensures the Parquet snapshot required by the dashboard
 * exists before the server starts.
 *
 * Layout (post-compact):
 *   <dataDir>/templates.parquet          — flat file, full rewrite each compact
 *   <dataDir>/log_entries/*.parquet      — partition dir, append-only
 *   <dataDir>/raw_logs/*.parquet         — partition dir, append-only
 *
 * If templates.parquet is absent (no compact has run yet), this method opens
 * the writer DuckDB, bootstraps the schema, and writes initial snapshots:
 * a flat templates.parquet and a first partition in each partition directory.
 */
public class StartupUtils {

    private static final List<String> PARTITIONED = List.of("log_entries", "raw_logs");

    public static void ensureSnapshot(String dbPath, Path dataDir) throws Exception {
        Files.createDirectories(dataDir);

        // Only check templates.parquet — partition dirs may legitimately be
        // empty on first serve (no data ingested yet).
        if (Files.exists(dataDir.resolve("templates.parquet")))
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

            // templates → flat file (as before)
            Path templatesTarget = dataDir.resolve("templates.parquet");
            s.execute("COPY (SELECT * FROM templates) TO '" +
                    templatesTarget.toAbsolutePath() + "' (FORMAT PARQUET, COMPRESSION ZSTD)");

            // log_entries and raw_logs → first partition in each directory.
            // If tables happen to be unified VIEWS post-compact and Parquet is missing,
            // this COPY fails loudly — broken state needs manual repair, not silent bootstrap.
            long partSeq = System.currentTimeMillis();
            String partName = String.format("part-%016d.parquet", partSeq);
            for (String name : PARTITIONED) {
                Path partDir = dataDir.resolve(name);
                Files.createDirectories(partDir);
                Path partTarget = partDir.resolve(partName);
                s.execute("COPY (SELECT * FROM " + name + ") TO '" +
                        partTarget.toAbsolutePath() + "' (FORMAT PARQUET, COMPRESSION ZSTD)");
            }
        }
    }
}
