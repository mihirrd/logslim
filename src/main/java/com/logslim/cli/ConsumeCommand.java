package com.logslim.cli;

import com.logslim.ingestion.KafkaIngestRunner;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.time.Duration;

@Component
@Command(name = "consume",
         mixinStandardHelpOptions = true,
         description = "Continuously ingest logs from a Kafka topic, batching writes "
                 + "into DuckDB and periodically compacting to keep the live tail small.")
public class ConsumeCommand implements Runnable {

    private final KafkaIngestRunner runner;

    @Option(names = "--topic", required = true,
            description = "Kafka topic to subscribe to.")
    private String topic;

    @Option(names = "--bootstrap-servers", defaultValue = "localhost:9092",
            description = "Kafka bootstrap servers (default: ${DEFAULT-VALUE}).")
    private String bootstrapServers;

    @Option(names = "--group-id", defaultValue = "logslim",
            description = "Consumer group id (default: ${DEFAULT-VALUE}).")
    private String groupId;

    @Option(names = "--source", defaultValue = "kafka",
            description = "Value stored in the raw_logs.source column for "
                    + "lines that arrive before a template locks "
                    + "(default: ${DEFAULT-VALUE}).")
    private String source;

    @Option(names = "--batch-size", defaultValue = "1000",
            description = "Flush after this many records are buffered "
                    + "(default: ${DEFAULT-VALUE}).")
    private int batchSize;

    @Option(names = "--flush-interval", defaultValue = "PT5S",
            description = "Flush at least this often even if --batch-size hasn't "
                    + "been reached (ISO-8601 duration, default: ${DEFAULT-VALUE}).")
    private Duration flushInterval;

    @Option(names = "--compact-interval", defaultValue = "PT5M",
            description = "Run `compact` this often to keep the live tail small "
                    + "(ISO-8601 duration, default: ${DEFAULT-VALUE}).")
    private Duration compactInterval;

    @Option(names = "--from-beginning",
            description = "Reset offsets to the earliest available on subscribe.")
    private boolean fromBeginning;

    @Option(names = "--max-records", defaultValue = "-1",
            description = "Exit after ingesting this many records. "
                    + "Useful for benchmarking. -1 means unlimited (default: ${DEFAULT-VALUE}).")
    private long maxRecords;

    public ConsumeCommand(KafkaIngestRunner runner) {
        this.runner = runner;
    }

    @Override
    public void run() {
        runner.consume(topic, bootstrapServers, groupId, source,
                batchSize, flushInterval, compactInterval, fromBeginning, maxRecords);
    }
}
