package com.logslim.cli;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Component
@Command(name = "compact", mixinStandardHelpOptions = true,
         description = "Export database to Parquet (zstd) and replace tables with views. "
                 + "After compaction the database is read-only; re-run `logslim run` to a fresh DB for new data.")
public class CompactCommand implements Runnable {

    private final JdbcTemplate jdbc;

    @Value("${logslim.db.path:logs.duckdb}")
    private String dbPath;

    @Option(names = {"--yes", "-y"}, description = "Skip confirmation prompt")
    private boolean yes;

    public CompactCommand(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc.getJdbcTemplate();
    }

    @Override
    public void run() {
        Path dataDir = resolveDataDir();
        long dbBefore = new File(dbPath).length();

        if (!yes) {
            System.out.printf("This will export all data to %s/ and replace tables with views.%n", dataDir);
            System.out.print("The database will become read-only. Proceed? [y/N] ");
            String input = System.console() != null
                    ? System.console().readLine()
                    : new java.util.Scanner(System.in).nextLine();
            if (input == null || !input.trim().equalsIgnoreCase("y")) {
                System.out.println("Aborted.");
                return;
            }
        }

        dataDir.toFile().mkdirs();

        String templatesParquet   = dataDir.resolve("templates.parquet").toAbsolutePath().toString();
        String logEntriesParquet  = dataDir.resolve("log_entries.parquet").toAbsolutePath().toString();
        String rawLogsParquet     = dataDir.resolve("raw_logs.parquet").toAbsolutePath().toString();

        System.out.print("Exporting templates...");
        jdbc.execute("COPY templates TO '" + templatesParquet + "' (FORMAT PARQUET, COMPRESSION ZSTD)");
        System.out.println(" done.");

        System.out.print("Exporting log_entries...");
        jdbc.execute("COPY log_entries TO '" + logEntriesParquet + "' (FORMAT PARQUET, COMPRESSION ZSTD)");
        System.out.println(" done.");

        System.out.print("Exporting raw_logs...");
        jdbc.execute("COPY raw_logs TO '" + rawLogsParquet + "' (FORMAT PARQUET, COMPRESSION ZSTD)");
        System.out.println(" done.");

        System.out.print("Replacing tables with Parquet-backed views...");
        // Drop as VIEW first (post-compact re-run), then as TABLE (first-time compact)
        for (String name : List.of("log_entries", "raw_logs", "templates")) {
            jdbc.execute("DROP VIEW  IF EXISTS " + name);
            jdbc.execute("DROP TABLE IF EXISTS " + name);
        }
        jdbc.execute("DROP SEQUENCE IF EXISTS templates_id_seq");
        jdbc.execute("DROP SEQUENCE IF EXISTS log_entries_id_seq");
        jdbc.execute("DROP SEQUENCE IF EXISTS raw_logs_id_seq");

        jdbc.execute("CREATE VIEW templates   AS SELECT * FROM read_parquet('" + templatesParquet  + "')");
        jdbc.execute("CREATE VIEW log_entries AS SELECT * FROM read_parquet('" + logEntriesParquet + "')");
        jdbc.execute("CREATE VIEW raw_logs    AS SELECT * FROM read_parquet('" + rawLogsParquet    + "')");

        jdbc.execute("CHECKPOINT");
        System.out.println(" done.");

        long dbAfter     = new File(dbPath).length();
        long parquetTotal = new File(templatesParquet).length()
                          + new File(logEntriesParquet).length()
                          + new File(rawLogsParquet).length();

        System.out.printf("%nResults:%n");
        System.out.printf("  .duckdb file:   %6.2f MB → %4.2f MB (view metadata only)%n",
                mb(dbBefore), mb(dbAfter));
        System.out.printf("  Parquet total:  %6.2f MB%n", mb(parquetTotal));
        System.out.printf("  Total storage:  %6.2f MB%n", mb(dbAfter + parquetTotal));
    }

    private Path resolveDataDir() {
        String base = dbPath.endsWith(".duckdb") ? dbPath.substring(0, dbPath.length() - 7) : dbPath;
        return Paths.get(base + "_data").toAbsolutePath();
    }

    private static double mb(long bytes) {
        return bytes / (1024.0 * 1024.0);
    }
}
