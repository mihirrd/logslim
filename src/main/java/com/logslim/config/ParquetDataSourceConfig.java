package com.logslim.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Server DataSource that reads only the compacted Parquet snapshot —
 * never opens `logs.duckdb`. This sidesteps DuckDB's single-process file
 * lock entirely: the CLI can ingest into `logs.duckdb` (or run
 * `logslim consume`) while the dashboard keeps serving reads from
 * `logs_data/`.
 *
 * Trade-off: the dashboard sees only data that has been compacted. The
 * live tail (entries added since the last compact) is invisible. Run
 * `logslim compact -y` periodically (or use `logslim consume` which
 * compacts on its own schedule).
 *
 * Implementation: each {@code getConnection()} opens a fresh in-memory
 * DuckDB and creates three views over the Parquet files, then returns
 * that connection. On {@code close()} the connection is destroyed.
 *
 * <p><b>Why per-request, not a shared connection?</b> The templates Parquet
 * file is rewritten atomically via a {@code .parquet.new} → {@code .parquet}
 * rename; partition files for log_entries/raw_logs are written to a temp name
 * then renamed into the directory. A query that lands during a swap can fail
 * transiently. A fresh connection per request isolates failures to that call.
 *
 * <p><b>Partition layout:</b> {@code log_entries} and {@code raw_logs} live
 * under subdirectories ({@code log_entries/*.parquet}, {@code raw_logs/*.parquet}).
 * Each compact appends one partition file; old partitions are never rewritten.
 * {@code templates} remains a single flat file (mutable, bounded).
 */
@Configuration
@ConditionalOnWebApplication
public class ParquetDataSourceConfig {

    @Value("${logslim.db.path:logs.duckdb}")
    private String dbPath;

    @Bean
    public DataSource dataSource() {
        final Path dataDir = resolveDataDir(dbPath);

        // Require only templates.parquet; partition dirs may be empty or absent
        // on a fresh install (serve is allowed to start and return empty results).
        Path templatesParquet = dataDir.resolve("templates.parquet");
        if (!Files.exists(templatesParquet)) {
            throw new IllegalStateException(
                    "Parquet snapshot not found: " + templatesParquet + ". " +
                    "Run `logslim compact -y` first to produce the snapshot the dashboard reads from.");
        }

        // Ensure the DuckDB driver is loaded before the first request hits us.
        try {
            Class.forName("org.duckdb.DuckDBDriver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("DuckDB JDBC driver not on classpath", e);
        }

        return new AbstractDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                Connection c = DriverManager.getConnection("jdbc:duckdb:");
                try (Statement s = c.createStatement()) {
                    // templates — single flat file
                    s.execute("CREATE VIEW templates AS SELECT * FROM read_parquet('"
                            + templatesParquet.toAbsolutePath() + "')");

                    // log_entries and raw_logs — partition directory globs.
                    // If a partition dir has no files yet, fall back to a zero-row
                    // typed view so queries return empty results rather than an error.
                    s.execute("CREATE VIEW log_entries AS " + partitionViewSql("log_entries", dataDir));
                    s.execute("CREATE VIEW raw_logs AS " + partitionViewSql("raw_logs", dataDir));
                } catch (SQLException e) {
                    try { c.close(); } catch (SQLException ignored) {}
                    throw e;
                }
                return c;
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                return getConnection();
            }
        };
    }

    /** Returns the SELECT SQL for a partitioned table's view. */
    private static String partitionViewSql(String name, Path dataDir) {
        Path partDir = dataDir.resolve(name);
        java.io.File[] parts = partDir.toFile().listFiles(
                (d, n) -> n.endsWith(".parquet") && !n.endsWith(".new"));
        if (parts != null && parts.length > 0) {
            return "SELECT * FROM read_parquet('" + partDir.toAbsolutePath() + "/*.parquet')";
        }
        // No partitions yet — zero-row typed select so schema is still correct.
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

    private static Path resolveDataDir(String dbPath) {
        String base = dbPath.endsWith(".duckdb")
                ? dbPath.substring(0, dbPath.length() - ".duckdb".length())
                : dbPath;
        return Paths.get(base + "_data").toAbsolutePath();
    }
}
