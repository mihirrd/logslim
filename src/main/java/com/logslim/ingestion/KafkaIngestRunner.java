package com.logslim.ingestion;

import com.logslim.extraction.TemplateExtractor;
import com.logslim.service.AdminService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Long-running Kafka consumer that batches incoming records and writes them
 * through {@link TemplateExtractor#processBatch(List)} on a single thread.
 * Periodically calls {@link AdminService#compactDatabase(Path)} so the live
 * tail stays small.
 *
 * Single-threaded by design: DuckDB has one writer per process, so all of
 * (poll, write, compact) share one thread. No locks. At-least-once
 * semantics: offsets commit only after a batch is successfully written.
 */
@Component
public class KafkaIngestRunner {

    private static final Logger log = LoggerFactory.getLogger(KafkaIngestRunner.class);

    private final TemplateExtractor extractor;
    private final AdminService adminService;

    @Value("${logslim.db.path:logs.duckdb}")
    private String dbPath;

    public KafkaIngestRunner(TemplateExtractor extractor, AdminService adminService) {
        this.extractor = extractor;
        this.adminService = adminService;
    }

    public void consume(String topic,
            String bootstrapServers,
            String groupId,
            String source,
            int batchSize,
            Duration flushInterval,
            Duration compactInterval,
            boolean fromBeginning) {

        Path dataDir = resolveDataDir();

        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("group.id", groupId);
        props.put("enable.auto.commit", "false");
        props.put("auto.offset.reset", fromBeginning ? "earliest" : "latest");
        props.put("key.deserializer", StringDeserializer.class.getName());
        props.put("value.deserializer", StringDeserializer.class.getName());

        // Track shutdown via a volatile flag flipped by the shutdown hook so an
        // in-flight poll/batch can drain cleanly.
        final ShutdownState state = new ShutdownState();
        Runtime.getRuntime().addShutdownHook(new Thread(state::requestShutdown, "logslim-consume-shutdown"));

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            log.info(
                    "logslim consume — subscribed to '{}' (group={}, source={}, batch={}, flush={}, compact={}, fromBeginning={})",
                    topic, groupId, source, batchSize, flushInterval, compactInterval, fromBeginning);

            // If --from-beginning is set, force seekToBeginning AFTER the first
            // poll has triggered partition assignment. `auto.offset.reset=earliest`
            // alone only takes effect when the group has no committed offset; an
            // explicit seek works regardless.
            boolean rewindPending = fromBeginning;

            List<LogGroup> batch = new ArrayList<>(batchSize);
            Instant lastFlush = Instant.now();
            Instant lastCompact = Instant.now();
            long totalIngested = 0;
            long flushes = 0;

            while (!state.shutdown()) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));

                if (rewindPending && !consumer.assignment().isEmpty()) {
                    log.info("--from-beginning: seeking {} partition(s) to earliest offset",
                            consumer.assignment().size());
                    consumer.seekToBeginning(consumer.assignment());
                    rewindPending = false;
                    // The records we just polled are from the pre-seek position;
                    // skip them so we don't double-count after the rewind.
                    continue;
                }

                for (ConsumerRecord<String, String> r : records) {
                    String value = r.value();
                    if (value == null || value.isEmpty())
                        continue;
                    batch.add(toLogGroup(value, source));
                }

                boolean sizeFull = batch.size() >= batchSize;
                boolean timeUp = Duration.between(lastFlush, Instant.now()).compareTo(flushInterval) >= 0;
                if (!batch.isEmpty() && (sizeFull || timeUp)) {
                    int n = batch.size();
                    extractor.processBatch(batch);
                    consumer.commitSync();
                    totalIngested += n;
                    flushes++;
                    log.info("flushed batch of {} records (total {})", n, totalIngested);
                    batch.clear();
                    lastFlush = Instant.now();
                }

                if (Duration.between(lastCompact, Instant.now()).compareTo(compactInterval) >= 0) {
                    runCompact(dataDir);
                    lastCompact = Instant.now();
                }
            }

            // Drain the in-flight batch on shutdown before the consumer closes.
            if (!batch.isEmpty()) {
                log.info("draining {} buffered records before shutdown", batch.size());
                extractor.processBatch(batch);
                consumer.commitSync();
                totalIngested += batch.size();
                batch.clear();
            }
            runCompact(dataDir);
            log.info("logslim consume — shutdown complete (total ingested: {})", totalIngested);
        }
    }

    private void runCompact(Path dataDir) {
        try {
            long t0 = System.currentTimeMillis();
            adminService.compactDatabase(dataDir);
            log.info("compact completed in {} ms", System.currentTimeMillis() - t0);
        } catch (RuntimeException e) {
            // Compact failures shouldn't kill the ingest loop — log and continue.
            log.warn("compact failed (will retry on next interval): {}", e.getMessage(), e);
        }
    }

    private static LogGroup toLogGroup(String value, String source) {
        // Most Kafka log records are a single line. If the payload contains
        // newlines (e.g. an embedded stack trace), treat the first line as
        // the header and the rest as continuation — same shape as the
        // file-input MultiLineGrouper produces.
        int nl = value.indexOf('\n');
        if (nl < 0) {
            return LogGroup.singleLine(value, source);
        }
        String header = value.substring(0, nl);
        String[] tail = value.substring(nl + 1).split("\n", -1);
        List<String> continuation = new ArrayList<>(tail.length);
        for (String t : tail)
            continuation.add(t);
        return new LogGroup(header, continuation, source);
    }

    /**
     * Mirrors CompactCommand.resolveDataDir so periodic compact targets the same
     * Parquet dir.
     */
    private Path resolveDataDir() {
        String base = dbPath.endsWith(".duckdb")
                ? dbPath.substring(0, dbPath.length() - ".duckdb".length())
                : dbPath;
        return Paths.get(base + "_data").toAbsolutePath();
    }

    /** Mutable shutdown flag — flipped by JVM hook, read by the poll loop. */
    private static final class ShutdownState {
        private volatile boolean stop = false;

        void requestShutdown() {
            stop = true;
        }

        boolean shutdown() {
            return stop;
        }
    }
}
