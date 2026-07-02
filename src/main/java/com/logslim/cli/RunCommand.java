package com.logslim.cli;

import com.logslim.extraction.TemplateExtractor;
import com.logslim.ingestion.LogGroup;
import com.logslim.ingestion.MultiLineGrouper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Two-pass ingest. Pass 1 streams the input through the learning stage
 * (mask + count distinct shapes, no writes) so the template set is learned from
 * the whole corpus; pass 2 re-streams and stores entries against the learned
 * templates. This keeps the template set a deterministic function of the data —
 * no per-dataset configuration. Stdin is spooled to a temp file so it can be
 * read twice.
 */
@Component
@Command(name = "run", mixinStandardHelpOptions = true,
         description = "Ingest logs and store them deduplicated into the output database.")
public class RunCommand implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(RunCommand.class);

    private final TemplateExtractor extractor;

    @Option(names = {"--input", "-i"}, description = "Input log file path, or '-' for stdin", defaultValue = "-")
    private String inputPath;

    @Option(names = {"--batch-size"}, description = "Lines to buffer before processing", defaultValue = "1000")
    private int batchSize;

    public RunCommand(TemplateExtractor extractor) {
        this.extractor = extractor;
    }

    @Override
    public void run() {
        Path spooled = null;
        try {
            Path input;
            String source;
            if ("-".equals(inputPath)) {
                spooled = spoolStdin();
                input = spooled;
                source = "stdin";
            } else {
                input = Paths.get(inputPath);
                if (!Files.exists(input)) {
                    throw new IOException("File not found: " + inputPath);
                }
                source = inputPath;
            }

            long learned = streamGroups(input, source, extractor::learnBatch, "Learning from");
            extractor.finishLearning();
            long total = streamGroups(input, source, extractor::processBatch, "Ingested");

            if (learned != total) {
                log.warn("Group count differs between passes: {} learned vs {} ingested", learned, total);
            }
            System.out.printf("%nDone. Ingested %,d groups.%n", total);
        } catch (IOException | UncheckedIOException e) {
            System.err.println("Error reading input: " + e.getMessage());
            System.exit(1);
        } finally {
            if (spooled != null) {
                try { Files.deleteIfExists(spooled); } catch (IOException ignored) { }
            }
        }
    }

    private long streamGroups(Path input, String source,
                              Consumer<List<LogGroup>> sink, String verb) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(input)) {
            List<LogGroup> batch = new ArrayList<>(batchSize);
            long total = 0;
            for (LogGroup group : new MultiLineGrouper(reader, source)) {
                batch.add(group);
                if (batch.size() >= batchSize) {
                    sink.accept(batch);
                    total += batch.size();
                    batch = new ArrayList<>(batchSize);
                    System.out.printf("\r%s %,d groups...", verb, total);
                }
            }
            if (!batch.isEmpty()) {
                sink.accept(batch);
                total += batch.size();
            }
            System.out.printf("\r%s %,d groups.%n", verb, total);
            return total;
        }
    }

    private static Path spoolStdin() throws IOException {
        Path tmp = Files.createTempFile("logslim-stdin-", ".log");
        Files.copy(System.in, tmp, StandardCopyOption.REPLACE_EXISTING);
        return tmp;
    }
}
