package com.logslim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import com.logslim.storage.StartupUtils;

@SpringBootApplication
@EnableCaching
public class LogSlimApplication {

    private static final List<String> CORE_TABLES = List.of("templates", "log_entries", "raw_logs");

    public static void main(String[] args) {
        if (args.length > 0 && "serve".equals(args[0])) {
            String dbPath = System.getProperty("logslim.db.path", "logs.duckdb");
            String dataDirBase = dbPath.endsWith(".duckdb")
                    ? dbPath.substring(0, dbPath.length() - ".duckdb".length())
                    : dbPath;
            Path dataDir = Paths.get(dataDirBase + "_data").toAbsolutePath();

            // The dashboard reads only the Parquet snapshot — see
            // ParquetDataSourceConfig. If it doesn't exist yet (fresh install,
            // or `logslim run` was used without a follow-up `logslim compact`),
            // bootstrap a snapshot of the current tables here so the user
            // doesn't have to run a separate compact just to start the server.
            try {
                StartupUtils.ensureSnapshot(dbPath, dataDir, CORE_TABLES);
            } catch (Exception e) {
                System.err.println("ERROR: Failed to bootstrap Parquet snapshot at "
                        + dataDir + ": " + e.getMessage());
                System.exit(1);
            }

            System.setProperty("spring.main.web-application-type", "servlet");
            System.setProperty("spring.sql.init.mode", "never");
            SpringApplication.run(LogSlimApplication.class, args);
        } else {
            System.exit(SpringApplication.exit(
                    SpringApplication.run(LogSlimApplication.class, args)));
        }
    }

    /**
     * Ensure the Parquet snapshot the dashboard reads from exists.
     *
     * Skip if all three Parquet files already exist — the normal post-compact case.
     * Otherwise open DuckDB at {@code dbPath}, ensure the schema exists
     * (idempotent),
     * and snapshot the current tables to Parquet. On a brand-new install this
     * writes
     * three empty Parquet files; if {@code logslim run} populated the database
     * without a follow-up {@code compact}, this captures that data into Parquet so
     * it's visible to the dashboard immediately.
     */

}
