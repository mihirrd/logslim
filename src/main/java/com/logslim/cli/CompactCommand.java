package com.logslim.cli;

import com.logslim.extraction.TemplateExtractor;
import com.logslim.service.AdminService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;

@Component
@Command(name = "compact", mixinStandardHelpOptions = true, description = "Export database to Parquet (zstd) and replace tables with views. "
        + "After compaction the database is read-only; re-run `logslim run` to a fresh DB for new data.")
public class CompactCommand implements Runnable {

    private final AdminService adminService;
    private final TemplateExtractor extractor;

    @Value("${logslim.db.path:logs.duckdb}")
    private String dbPath;

    @Option(names = { "--yes", "-y" }, description = "Skip confirmation prompt")
    private boolean yes;

    public CompactCommand(AdminService adminService, TemplateExtractor extractor) {
        this.adminService = adminService;
        this.extractor = extractor;
    }

    @Override
    public void run() {
        Path dataDir = resolveDataDir();
        long dbBefore = new File(dbPath).length();

        if (!yes) {
            System.out.printf("This will export all data to %s/ and replace tables with views.%n", dataDir);
            System.out.print("The database will become read-only. Proceed? [y/N] ");
            String input;
            try (Scanner scanner = new Scanner(System.in)) {
                input = System.console() != null
                        ? System.console().readLine()
                        : scanner.nextLine();
            }
            if (input == null || !input.trim().equalsIgnoreCase("y")) {
                System.out.println("Aborted.");
                return;
            }
        }

        // Fold fragmented identity templates into learned ones while their rows are
        // still writable — after export they are sealed in immutable Parquet.
        TemplateExtractor.RelearnResult relearn = extractor.relearn();
        if (relearn.changedAnything()) {
            System.out.printf("Relearn: %d merged template(s), %d folded, %d remapped forward.%n",
                    relearn.mergedTemplates(), relearn.folded(), relearn.remappedForward());
        }

        boolean ran = adminService.compactDatabase(dataDir);
        if (!ran) {
            System.out.println("Nothing to compact — live tail is empty.");
            return;
        }

        long dbAfter = new File(dbPath).length();
        long parquetTotal = adminService.calculateParquetSize(dataDir);

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
