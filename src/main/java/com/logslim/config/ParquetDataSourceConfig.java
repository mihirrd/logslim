package com.logslim.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

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
 * lock entirely: the CLI can ingest into `logs.duckdb` while the dashboard
 * keeps serving reads from `logs_data/*.parquet`.
 *
 * Trade-off: the dashboard sees only data that has been compacted. The
 * live tail (entries added since the last compact) is invisible. Run
 * `logslim compact -y` periodically (manually or via cron) to refresh.
 *
 * Implementation: in-memory DuckDB (`jdbc:duckdb:`) with three views over
 * the Parquet files. DAO read methods continue to query the unsuffixed
 * names (`templates`, `log_entries`, `raw_logs`) without modification.
 *
 * The connection is kept alive via SingleConnectionDataSource because
 * each new in-memory DuckDB connection is a fresh, empty database — the
 * views must persist across requests, so we hold a single shared
 * connection. JdbcTemplate is thread-safe over it; concurrent dashboard
 * requests serialize at the JDBC level (acceptable at dashboard scale).
 */
@Configuration
@ConditionalOnWebApplication
public class ParquetDataSourceConfig {

    private static final List<String> CORE_TABLES =
            List.of("templates", "log_entries", "raw_logs");

    @Value("${logslim.db.path:logs.duckdb}")
    private String dbPath;

    @Bean
    public DataSource dataSource() throws SQLException {
        Path dataDir = resolveDataDir(dbPath);
        for (String name : CORE_TABLES) {
            Path parquet = dataDir.resolve(name + ".parquet");
            if (!Files.exists(parquet)) {
                throw new IllegalStateException(
                        "Parquet snapshot not found: " + parquet + ". " +
                        "Run `logslim compact -y` first to produce the snapshot the dashboard reads from.");
            }
        }

        Connection conn = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement s = conn.createStatement()) {
            for (String name : CORE_TABLES) {
                Path parquet = dataDir.resolve(name + ".parquet");
                s.execute("CREATE VIEW " + name +
                          " AS SELECT * FROM read_parquet('" + parquet.toAbsolutePath() + "')");
            }
        }

        // suppressClose=true so JdbcTemplate.close() doesn't end the connection
        // (and with it, the in-memory database).
        return new SingleConnectionDataSource(conn, true);
    }

    private static Path resolveDataDir(String dbPath) {
        String base = dbPath.endsWith(".duckdb")
                ? dbPath.substring(0, dbPath.length() - ".duckdb".length())
                : dbPath;
        return Paths.get(base + "_data").toAbsolutePath();
    }
}
