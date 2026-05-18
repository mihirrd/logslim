package com.logslim.service;

import com.logslim.storage.WriteTarget;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Service
public class AdminService {

    private static final List<String> CORE_TABLES = List.of("templates", "log_entries", "raw_logs");

    /**
     * Same set as CORE_TABLES, ordered so that FK-referencing tables are
     * dropped before the tables they reference. Use whenever DROP-ing the
     * un-compacted schema, where `log_entries.template_id` REFERENCES
     * `templates(template_id)`.
     */
    private static final List<String> CORE_TABLES_DROP_ORDER = List.of("log_entries", "raw_logs", "templates");

    private final JdbcTemplate jdbc;
    private final WriteTarget writeTarget;

    @Value("${logslim.db.path:logs.duckdb}")
    private String dbPath;

    public AdminService(JdbcTemplate jdbc, WriteTarget writeTarget) {
        this.jdbc = jdbc;
        this.writeTarget = writeTarget;
    }

    /**
     * Compact the database. Idempotent over the hybrid layout:
     *
     * - First compact (un-compacted state): export each base table to Parquet,
     * capture MAX(id), drop the table, then build the three-object layout
     * ({name}_archive view + {name}_live writable table + unified {name} view).
     *
     * - Re-compact (already compacted): copy the unified-view contents (archive
     * ∪ live) to a fresh Parquet file, drop everything, then rebuild the
     * layout from scratch.
     *
     * After this returns, reads continue against {name} (now a view), and writes
     * via the DAOs land in {name}_live.
     */
    public void compactDatabase(Path dataDir) {
        jdbc.execute("BEGIN TRANSACTION");
        dataDir.toFile().mkdirs();
        Path templatesParquet = dataDir.resolve("templates.parquet");
        Path logEntriesParquet = dataDir.resolve("log_entries.parquet");
        Path rawLogsParquet = dataDir.resolve("raw_logs.parquet");

        // Write to .parquet.new first so the COPY never overwrites a file that
        // an _archive view is currently mapping. After all reads are done and
        // catalog is torn down, atomically rename the temp files into place.
        Path templatesTmp = dataDir.resolve("templates.parquet.new");
        Path logEntriesTmp = dataDir.resolve("log_entries.parquet.new");
        Path rawLogsTmp = dataDir.resolve("raw_logs.parquet.new");
        try {
            // 1. Snapshot current state to TEMP Parquet files. Reads through the
            // unified view (archive + live) and writes to a separate filename.
            copyToParquet("templates", templatesTmp);
            copyToParquet("log_entries", logEntriesTmp);
            copyToParquet("raw_logs", rawLogsTmp);

            // 2. Capture MAX(id) BEFORE we drop, so live sequences start past the archive.
            long templatesMaxId = maxId("templates", "template_id");
            long logEntriesMaxId = maxId("log_entries", "entry_id");
            long rawLogsMaxId = maxId("raw_logs", "log_id");

            // 3. Tear down whatever's currently there. Order matters:
            // - Unified views first (they depend on _archive and _live).
            // - In the un-compacted case the same loop drops base tables in
            // FK-safe order (log_entries → raw_logs → templates).
            // - Then the _archive and _live objects (no-op pre-compact).
            // - Then sequences.
            for (String name : CORE_TABLES_DROP_ORDER)
                dropObject(name);
            dropHybridLayoutIfPresent();
            dropSequencesIfExist();

            // 4. With the catalog cleared, swap the new Parquet files into place.
            try {
                Files.move(templatesTmp, templatesParquet, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.move(logEntriesTmp, logEntriesParquet, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.move(rawLogsTmp, rawLogsParquet, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.io.IOException e) {
                throw new RuntimeException(
                        "Compact failed while swapping Parquet files: " + e.getMessage(), e);
            }

            // 5. Rebuild the three-object layout fresh.
            buildHybridLayout(templatesParquet, logEntriesParquet, rawLogsParquet,
                    templatesMaxId, logEntriesMaxId, rawLogsMaxId);

            jdbc.execute("CHECKPOINT");
            writeTarget.invalidate();
            jdbc.execute("COMMIT");
        } catch (Exception e) {
            jdbc.execute("ROLLBACK");
            for (Path tmp : List.of(templatesTmp, logEntriesTmp, rawLogsTmp)) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                }
            }
            throw new RuntimeException("Compact failed: " + e.getMessage(), e);
        }

    }

    private void copyToParquet(String unsuffixed, Path target) {
        jdbc.execute("COPY (SELECT * FROM " + unsuffixed + ") TO '"
                + target.toAbsolutePath() + "' (FORMAT PARQUET, COMPRESSION ZSTD)");
    }

    private long maxId(String unsuffixed, String idColumn) {
        Long max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(" + idColumn + "), 0) FROM " + unsuffixed, Long.class);
        return max == null ? 0L : max;
    }

    /** Drops the {name}_archive views and {name}_live tables, if they exist. */
    private void dropHybridLayoutIfPresent() {
        for (String name : CORE_TABLES) {
            dropObject(name + "_archive");
            dropObject(name + "_live");
        }
    }

    private void dropSequencesIfExist() {
        jdbc.execute("DROP SEQUENCE IF EXISTS templates_id_seq");
        jdbc.execute("DROP SEQUENCE IF EXISTS log_entries_id_seq");
        jdbc.execute("DROP SEQUENCE IF EXISTS raw_logs_id_seq");
    }

    private void buildHybridLayout(Path templatesParquet, Path logEntriesParquet,
            Path rawLogsParquet,
            long templatesMaxId, long logEntriesMaxId, long rawLogsMaxId) {
        jdbc.execute("BEGIN TRANSACTION");
        try {
            // Archive views over Parquet (immutable).
            jdbc.execute("CREATE VIEW templates_archive AS SELECT * FROM read_parquet('"
                    + templatesParquet.toAbsolutePath() + "')");
            jdbc.execute("CREATE VIEW log_entries_archive AS SELECT * FROM read_parquet('"
                    + logEntriesParquet.toAbsolutePath() + "')");
            jdbc.execute("CREATE VIEW raw_logs_archive AS SELECT * FROM read_parquet('"
                    + rawLogsParquet.toAbsolutePath() + "')");

            // Live writable tables — column types must match the un-compacted production
            // schema (schema.sql) so the UNION view in step below is type-compatible with
            // the archive Parquet. In particular `log_timestamp` is BIGINT (epoch ms).
            jdbc.execute("CREATE SEQUENCE templates_id_seq START " + (templatesMaxId + 1));
            jdbc.execute("""
                    CREATE TABLE templates_live (
                        template_id BIGINT DEFAULT nextval('templates_id_seq') PRIMARY KEY,
                        pattern     TEXT   NOT NULL UNIQUE,
                        occurrences BIGINT NOT NULL DEFAULT 1,
                        created_at  TEXT   NOT NULL,
                        updated_at  TEXT   NOT NULL
                    )""");

            jdbc.execute("CREATE SEQUENCE log_entries_id_seq START " + (logEntriesMaxId + 1));
            jdbc.execute("""
                    CREATE TABLE log_entries_live (
                        entry_id          BIGINT DEFAULT nextval('log_entries_id_seq') PRIMARY KEY,
                        template_id       BIGINT NOT NULL,
                        log_timestamp     BIGINT NOT NULL,
                        parameter_values  TEXT   NOT NULL DEFAULT '[]',
                        continuation_text TEXT
                    )""");
            jdbc.execute("CREATE INDEX idx_le_live_template  ON log_entries_live(template_id)");
            jdbc.execute("CREATE INDEX idx_le_live_timestamp ON log_entries_live(log_timestamp)");

            jdbc.execute("CREATE SEQUENCE raw_logs_id_seq START " + (rawLogsMaxId + 1));
            jdbc.execute("""
                    CREATE TABLE raw_logs_live (
                        log_id        BIGINT DEFAULT nextval('raw_logs_id_seq') PRIMARY KEY,
                        content       TEXT   NOT NULL,
                        log_timestamp TEXT   NOT NULL,
                        source        TEXT,
                        created_at    TEXT   NOT NULL
                    )""");
            jdbc.execute("CREATE INDEX idx_raw_live_timestamp ON raw_logs_live(log_timestamp)");

            // Unified read views — what DAO read methods continue to query.
            jdbc.execute("""
                    CREATE VIEW templates AS
                      SELECT * FROM templates_archive UNION ALL SELECT * FROM templates_live""");
            jdbc.execute("""
                    CREATE VIEW log_entries AS
                      SELECT * FROM log_entries_archive UNION ALL SELECT * FROM log_entries_live""");
            jdbc.execute("""
                    CREATE VIEW raw_logs AS
                      SELECT * FROM raw_logs_archive UNION ALL SELECT * FROM raw_logs_live""");
            jdbc.execute("COMMIT");
        } catch (Exception e) {
            jdbc.execute("ROLLBACK");
            throw new RuntimeException("Failed to build hybrid layout: " + e.getMessage(), e);
        }
    }

    /**
     * Returns true if any of the core tables is currently a VIEW (i.e. compacted).
     */
    public boolean isCompacted() {
        List<String> types = jdbc.queryForList(
                "SELECT table_type FROM information_schema.tables " +
                        "WHERE table_name IN ('templates','log_entries','raw_logs')",
                String.class);
        return types.stream().anyMatch("VIEW"::equals);
    }

    public record ClearResult(int entriesDeleted, int templatesDeleted, int rawLogsDeleted) {
    }

    /**
     * Wipe everything — both the live tables AND the Parquet archive (if any).
     * Restores the un-compacted base-table schema.
     */
    public ClearResult clearDatabase() {
        int entries = count("log_entries");
        int templates = count("templates");
        int raw = count("raw_logs");

        jdbc.execute("BEGIN TRANSACTION");
        try {
            // Drop unified views (or base tables, pre-compact) first, then
            // _archive views and _live tables, then sequences.
            for (String name : CORE_TABLES_DROP_ORDER)
                dropObject(name);
            dropHybridLayoutIfPresent();
            dropSequencesIfExist();
            // (clearDatabase already had this order; kept here for parity.)

            // Wipe Parquet files on disk (best-effort).
            Path dataDir = resolveDataDir();
            for (String base : CORE_TABLES) {
                try {
                    Files.deleteIfExists(dataDir.resolve(base + ".parquet"));
                } catch (Exception ignored) {
                    /* best-effort */ }
            }
            // Try to remove the now-empty data dir; harmless if non-empty.
            try {
                Files.deleteIfExists(dataDir);
            } catch (Exception ignored) {
            }

            recreateTables();
            jdbc.execute("CHECKPOINT");
            writeTarget.invalidate();
            return new ClearResult(entries, templates, raw);
        } catch (Exception e) {
            jdbc.execute("ROLLBACK");
            throw new RuntimeException("Failed to clear database: " + e.getMessage(), e);
        }
    }

    public long calculateParquetSize(Path dataDir) {
        String templatesParquet = dataDir.resolve("templates.parquet").toAbsolutePath().toString();
        String logEntriesParquet = dataDir.resolve("log_entries.parquet").toAbsolutePath().toString();
        String rawLogsParquet = dataDir.resolve("raw_logs.parquet").toAbsolutePath().toString();

        return new File(templatesParquet).length()
                + new File(logEntriesParquet).length()
                + new File(rawLogsParquet).length();
    }

    private Path resolveDataDir() {
        String base = dbPath.endsWith(".duckdb")
                ? dbPath.substring(0, dbPath.length() - ".duckdb".length())
                : dbPath;
        return Paths.get(base + "_data").toAbsolutePath();
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }

    /**
     * Recreate the un-compacted schema. Must match `src/main/resources/schema.sql`
     * exactly so that a post-clear database is indistinguishable from a fresh one.
     */
    private void recreateTables() {
        jdbc.execute("BEGIN TRANSACTION");
        try {
            jdbc.execute("CREATE SEQUENCE templates_id_seq START 1");
            jdbc.execute("""
                    CREATE TABLE templates (
                        template_id BIGINT DEFAULT nextval('templates_id_seq') PRIMARY KEY,
                        pattern     TEXT   NOT NULL UNIQUE,
                        occurrences BIGINT NOT NULL DEFAULT 1,
                        created_at  TEXT   NOT NULL,
                        updated_at  TEXT   NOT NULL
                    )""");
            jdbc.execute("CREATE SEQUENCE raw_logs_id_seq START 1");
            jdbc.execute("""
                    CREATE TABLE raw_logs (
                        log_id        BIGINT DEFAULT nextval('raw_logs_id_seq') PRIMARY KEY,
                        content       TEXT   NOT NULL,
                        log_timestamp TEXT   NOT NULL,
                        source        TEXT,
                        created_at    TEXT   NOT NULL
                    )""");
            jdbc.execute("CREATE INDEX idx_raw_timestamp ON raw_logs(log_timestamp)");
            jdbc.execute("CREATE SEQUENCE log_entries_id_seq START 1");
            jdbc.execute("""
                    CREATE TABLE log_entries (
                        entry_id          BIGINT DEFAULT nextval('log_entries_id_seq') PRIMARY KEY,
                        template_id       BIGINT NOT NULL REFERENCES templates(template_id),
                        log_timestamp     BIGINT NOT NULL,
                        parameter_values  TEXT   NOT NULL DEFAULT '[]',
                        continuation_text TEXT
                    )""");
            jdbc.execute("CREATE INDEX idx_le_template  ON log_entries(template_id)");
            jdbc.execute("CREATE INDEX idx_le_timestamp ON log_entries(log_timestamp)");
        } catch (Exception e) {
            jdbc.execute("ROLLBACK");
            throw new RuntimeException("Failed to recreate tables: " + e.getMessage(), e);
        }

    }

    private void dropObject(String name) {
        List<String> types = jdbc.queryForList(
                "SELECT table_type FROM information_schema.tables WHERE table_name = ?",
                String.class, name);
        if (types.isEmpty())
            return;
        if ("VIEW".equals(types.get(0))) {
            jdbc.execute("DROP VIEW " + name);
        } else {
            jdbc.execute("DROP TABLE " + name);
        }
    }
}
