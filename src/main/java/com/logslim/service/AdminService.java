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
import java.util.Comparator;
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

    // Tables whose archive is append-only partitioned Parquet directories.
    // Each compact appends one new partition file; existing partitions are never touched.
    // This makes compact cost O(live tail) instead of O(total archive).
    private static final List<String> PARTITIONED_TABLES = List.of("log_entries", "raw_logs");

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
     * - Re-compact (already compacted): for log_entries and raw_logs, append
     * only the current live tail as a new partition file — existing partitions
     * are never rewritten (O(tail) cost). For templates, rewrite the single
     * templates.parquet in full (templates are mutable/bounded and a full rewrite
     * avoids duplicate-pattern bugs).
     *
     * After this returns, reads continue against {name} (now a view), and writes
     * via the DAOs land in {name}_live.
     */
    /**
     * @return true if compaction ran, false if the live tail was empty and nothing needed doing.
     */
    public boolean compactDatabase(Path dataDir) {
        // Determine whether this is first compact (base tables exist) or re-compact.
        boolean alreadyCompacted = isCompacted();

        // Skip if the live tail is empty — nothing new to flush to Parquet.
        if (alreadyCompacted) {
            Long liveCount = jdbc.queryForObject("SELECT COUNT(*) FROM log_entries_live", Long.class);
            if (liveCount == null || liveCount == 0) return false;
        }

        jdbc.execute("BEGIN TRANSACTION");
        dataDir.toFile().mkdirs();

        // templates → single flat file (full rewrite each compact, mutable + bounded)
        Path templatesParquet = dataDir.resolve("templates.parquet");
        Path templatesTmp = dataDir.resolve("templates.parquet.new");

        // log_entries and raw_logs → partition directories; each compact appends one file
        Path logEntriesDir = dataDir.resolve("log_entries");
        Path rawLogsDir = dataDir.resolve("raw_logs");

        // Source table for partitioned tables:
        // - first compact: read the entire base table (it's the only data)
        // - re-compact: read only _live (existing partitions stay as-is)
        String logEntriesSource = alreadyCompacted ? "log_entries_live" : "log_entries";
        String rawLogsSource = alreadyCompacted ? "raw_logs_live" : "raw_logs";

        // New partition file path (write to .new, then rename into the dir).
        long partSeq = System.currentTimeMillis();
        String partName = String.format("part-%016d.parquet", partSeq);
        String partNameTmp = partName + ".new";

        Path logEntriesPartTmp = null;
        Path rawLogsPartTmp = null;

        try {
            logEntriesDir.toFile().mkdirs();
            rawLogsDir.toFile().mkdirs();

            logEntriesPartTmp = logEntriesDir.resolve(partNameTmp);
            rawLogsPartTmp = rawLogsDir.resolve(partNameTmp);

            // 1. Snapshot templates (full rewrite) and live tails (new partition only).
            copyToParquet("templates", templatesTmp);
            copyToParquet(logEntriesSource, logEntriesPartTmp);
            copyToParquet(rawLogsSource, rawLogsPartTmp);

            // 2. Capture MAX(id) BEFORE we drop, so live sequences start past the archive.
            long templatesMaxId = maxId("templates", "template_id");
            long logEntriesMaxId = maxId("log_entries", "entry_id");
            long rawLogsMaxId = maxId("raw_logs", "log_id");

            // 3. Tear down whatever's currently there. Order matters:
            // unified views first, then _archive/_live objects, then sequences.
            for (String name : CORE_TABLES_DROP_ORDER)
                dropObject(name);
            dropHybridLayoutIfPresent();
            dropSequencesIfExist();

            // 4. With the catalog cleared, atomically move files into place.
            try {
                Files.move(templatesTmp, templatesParquet, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Path logEntriesPart = logEntriesDir.resolve(partName);
                Files.move(logEntriesPartTmp, logEntriesPart, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                logEntriesPartTmp = null;
                Path rawLogsPart = rawLogsDir.resolve(partName);
                Files.move(rawLogsPartTmp, rawLogsPart, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                rawLogsPartTmp = null;
            } catch (java.io.IOException e) {
                throw new RuntimeException(
                        "Compact failed while swapping Parquet files: " + e.getMessage(), e);
            }

            // 5. Rebuild the three-object layout fresh.
            buildHybridLayout(templatesParquet, logEntriesDir, rawLogsDir,
                    templatesMaxId, logEntriesMaxId, rawLogsMaxId);
            writeTarget.invalidate();
            jdbc.execute("COMMIT");
            jdbc.execute("CHECKPOINT");
            return true;
        } catch (Exception e) {
            jdbc.execute("ROLLBACK");
            try { Files.deleteIfExists(templatesTmp); } catch (IOException ignored) {}
            if (logEntriesPartTmp != null) {
                try { Files.deleteIfExists(logEntriesPartTmp); } catch (IOException ignored) {}
            }
            if (rawLogsPartTmp != null) {
                try { Files.deleteIfExists(rawLogsPartTmp); } catch (IOException ignored) {}
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

    /**
     * Build the archive view SQL for a partitioned table directory.
     * If the directory has at least one .parquet file, use read_parquet glob.
     * Otherwise return an empty typed select so the view always has the right schema.
     */
    private String partitionedArchiveViewSql(String name, Path partDir) {
        File[] parts = partDir.toFile().listFiles(
                (d, n) -> n.endsWith(".parquet") && !n.endsWith(".new"));
        if (parts != null && parts.length > 0) {
            return "SELECT * FROM read_parquet('" + partDir.toAbsolutePath() + "/*.parquet')";
        }
        // No partitions yet — return a zero-row typed select matching the live table schema.
        if ("log_entries".equals(name)) {
            return "SELECT CAST(NULL AS BIGINT) AS entry_id, " +
                   "CAST(NULL AS BIGINT) AS template_id, " +
                   "CAST(NULL AS BIGINT) AS log_timestamp, " +
                   "CAST(NULL AS TEXT) AS parameter_values, " +
                   "CAST(NULL AS TEXT) AS continuation_text " +
                   "WHERE false";
        } else { // raw_logs
            return "SELECT CAST(NULL AS BIGINT) AS log_id, " +
                   "CAST(NULL AS TEXT) AS content, " +
                   "CAST(NULL AS TEXT) AS log_timestamp, " +
                   "CAST(NULL AS TEXT) AS source, " +
                   "CAST(NULL AS TEXT) AS created_at " +
                   "WHERE false";
        }
    }

    private void buildHybridLayout(Path templatesParquet, Path logEntriesDir,
            Path rawLogsDir,
            long templatesMaxId, long logEntriesMaxId, long rawLogsMaxId) {
        try {
            // Archive view over templates Parquet (single file, full rewrite each compact).
            jdbc.execute("CREATE VIEW templates_archive AS SELECT * FROM read_parquet('"
                    + templatesParquet.toAbsolutePath() + "')");

            // Archive views over partition directories; glob expands across all sealed partitions.
            jdbc.execute("CREATE VIEW log_entries_archive AS "
                    + partitionedArchiveViewSql("log_entries", logEntriesDir));
            jdbc.execute("CREATE VIEW raw_logs_archive AS "
                    + partitionedArchiveViewSql("raw_logs", rawLogsDir));

            // Live writable tables — column types must match the un-compacted production
            // schema (schema.sql) so the UNION view below is type-compatible with
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
        } catch (Exception e) {
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

            // Wipe Parquet files and partition directories on disk (best-effort).
            Path dataDir = resolveDataDir();
            // templates is a flat file
            try {
                Files.deleteIfExists(dataDir.resolve("templates.parquet"));
            } catch (Exception ignored) { /* best-effort */ }
            // log_entries and raw_logs are partition directories
            for (String name : PARTITIONED_TABLES) {
                deleteDirectoryTree(dataDir.resolve(name));
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

    /** Recursively delete a directory tree (best-effort; ignores errors). */
    private void deleteDirectoryTree(Path dir) {
        if (!Files.exists(dir)) return;
        try {
            Files.walk(dir)
                 .sorted(Comparator.reverseOrder())
                 .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }

    /**
     * Sum the sizes of all Parquet data: templates flat file + partition dirs.
     */
    public long calculateParquetSize(Path dataDir) {
        long total = dataDir.resolve("templates.parquet").toFile().length();
        for (String name : PARTITIONED_TABLES) {
            File partDir = dataDir.resolve(name).toFile();
            File[] parts = partDir.listFiles((d, n) -> n.endsWith(".parquet") && !n.endsWith(".new"));
            if (parts != null) {
                for (File f : parts) total += f.length();
            }
        }
        return total;
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
