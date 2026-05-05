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
        try (BufferedReader reader = openReader()) {
            String source = "-".equals(inputPath) ? "stdin" : inputPath;
            List<LogGroup> batch = new ArrayList<>(batchSize);
            long total = 0;

            for (LogGroup group : new MultiLineGrouper(reader, source)) {
                batch.add(group);
                if (batch.size() >= batchSize) {
                    extractor.processBatch(batch);
                    total += batch.size();
                    batch.clear();
                    System.out.printf("\rIngested %,d groups...", total);
                }
            }
            if (!batch.isEmpty()) {
                extractor.processBatch(batch);
                total += batch.size();
            }
            System.out.printf("%nDone. Ingested %,d groups.%n", total);
        } catch (IOException | UncheckedIOException e) {
            System.err.println("Error reading input: " + e.getMessage());
            System.exit(1);
        }
    }

    private BufferedReader openReader() throws IOException {
        if ("-".equals(inputPath)) {
            return new BufferedReader(new InputStreamReader(System.in));
        }
        Path path = Paths.get(inputPath);
        if (!Files.exists(path)) {
            throw new IOException("File not found: " + inputPath);
        }
        return Files.newBufferedReader(path);
    }
}
