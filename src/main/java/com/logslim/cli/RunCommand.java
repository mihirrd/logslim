package com.logslim.cli;

import com.logslim.extraction.TemplateExtractor;
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
            List<String> batch = new ArrayList<>(batchSize);
            long total = 0;
            String line;

            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                batch.add(line);
                if (batch.size() >= batchSize) {
                    extractor.processBatch(batch, source);
                    total += batch.size();
                    batch.clear();
                    System.out.printf("\rIngested %,d lines...", total);
                }
            }
            if (!batch.isEmpty()) {
                extractor.processBatch(batch, source);
                total += batch.size();
            }
            System.out.printf("%nDone. Ingested %,d lines.%n", total);
        } catch (IOException e) {
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
