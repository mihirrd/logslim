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
import java.util.List;

/**
 * Server DataSource that reads only the compacted Parquet snapshot —
 * never opens `logs.duckdb`. This sidesteps DuckDB's single-process file
 * lock entirely: the CLI can ingest into `logs.duckdb` (or run
 * `logslim consume`) while the dashboard keeps serving reads from
 * `logs_data/*.parquet`.
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
 * <p><b>Why per-request, not a shared connection?</b> The Parquet files
 * are rewritten atomically by {@link com.logslim.service.AdminService#compactDatabase}
 * via a {@code .parquet.new} → {@code .parquet} rename. A query that
 * lands during that swap can fail transiently. With a shared connection,
 * one transient failure poisons DuckDB's pending-result state and every
 * subsequent request fails with
 * {@code Attempting to execute an unsuccessful or closed pending query result}
 * until the JVM is restarted. A fresh connection per request isolates
 * failures to that one call.
 */
@Configuration
@ConditionalOnWebApplication
public class ParquetDataSourceConfig {

    private static final List<String> CORE_TABLES =
            List.of("templates", "log_entries", "raw_logs");

    @Value("${logslim.db.path:logs.duckdb}")
    private String dbPath;

    @Bean
    public DataSource dataSource() {
        final Path dataDir = resolveDataDir(dbPath);
        for (String name : CORE_TABLES) {
            Path parquet = dataDir.resolve(name + ".parquet");
            if (!Files.exists(parquet)) {
                throw new IllegalStateException(
                        "Parquet snapshot not found: " + parquet + ". " +
                        "Run `logslim compact -y` first to produce the snapshot the dashboard reads from.");
            }
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
                    for (String name : CORE_TABLES) {
                        Path parquet = dataDir.resolve(name + ".parquet");
                        s.execute("CREATE VIEW " + name +
                                " AS SELECT * FROM read_parquet('" + parquet.toAbsolutePath() + "')");
                    }
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

    private static Path resolveDataDir(String dbPath) {
        String base = dbPath.endsWith(".duckdb")
                ? dbPath.substring(0, dbPath.length() - ".duckdb".length())
                : dbPath;
        return Paths.get(base + "_data").toAbsolutePath();
    }
}
