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
import java.util.stream.Collectors;

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

    @Value("${logslim.ingestion.dlq.topic:}")
    private String dlqTopic;

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
            boolean fromBeginning,
            long maxRecords) {

        Path dataDir = resolveDataDir();

        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("group.id", groupId);
        props.put("enable.auto.commit", "false");
        props.put("auto.offset.reset", fromBeginning ? "earliest" : "latest");
        props.put("key.deserializer", StringDeserializer.class.getName());
        props.put("value.deserializer", StringDeserializer.class.getName());
        // Allow the broker to accumulate up to batchSize records per poll so a
        // single poll() fills a full batch rather than requiring multiple round-trips.
        props.put("max.poll.records", String.valueOf(batchSize));
        // Wait for at least 64 KB before returning a fetch response; reduces
        // round-trips when records are arriving faster than one-at-a-time.
        props.put("fetch.min.bytes", "65536");
        props.put("fetch.max.wait.ms", "500");

        // Track shutdown via a volatile flag flipped by the shutdown hook so an
        // in-flight poll/batch can drain cleanly.
        final ShutdownState state = new ShutdownState();
        Runtime.getRuntime().addShutdownHook(new Thread(state::requestShutdown, "logslim-consume-shutdown"));

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
             DeadLetterProducer dlq = new DeadLetterProducer(bootstrapServers, dlqTopic)) {
            consumer.subscribe(List.of(topic));
            log.info(
                    "logslim consume — subscribed to '{}' (group={}, source={}, batch={}, flush={}, compact={}, fromBeginning={})",
                    topic, groupId, source, batchSize, flushInterval, compactInterval, fromBeginning);

            // If --from-beginning is set, force seekToBeginning AFTER the first
            // poll has triggered partition assignment. `auto.offset.reset=earliest`
            // alone only takes effect when the group has no committed offset; an
            // explicit seek works regardless.
            boolean rewindPending = fromBeginning;

            List<LogGroup> batch    = new ArrayList<>(batchSize);
            List<String>   rawBatch = new ArrayList<>(batchSize);
            Instant lastFlush   = Instant.now();
            Instant lastCompact = Instant.now();
            long totalIngested = 0;

            while (!state.shutdown() && (maxRecords < 0 || totalIngested < maxRecords)) {
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
                    // r.timestamp() is the Kafka record timestamp: epoch ms, always UTC.
                    // Use it as the authoritative timestamp so log lines without an
                    // explicit timezone offset are stored in UTC rather than being
                    // parsed as an ambiguous local-time string.
                    batch.add(toLogGroup(value, source, Instant.ofEpochMilli(r.timestamp())));
                    rawBatch.add(value);
                }

                boolean sizeFull = batch.size() >= batchSize;
                boolean timeUp = Duration.between(lastFlush, Instant.now()).compareTo(flushInterval) >= 0;
                if (!batch.isEmpty() && (sizeFull || timeUp)) {
                    totalIngested += flushBatch(batch, rawBatch, consumer, dlq);
                    batch.clear();
                    rawBatch.clear();
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
                totalIngested += flushBatch(batch, rawBatch, consumer, dlq);
                batch.clear();
                rawBatch.clear();
            }
            runCompact(dataDir);
            log.info("logslim consume — shutdown complete (total ingested: {})", totalIngested);
        }
    }

    /**
     * Flush a batch to the extractor. On failure, falls back to per-record
     * processing so a single poison pill cannot stall the consumer indefinitely.
     * Offsets are committed regardless of per-record outcomes so the consumer
     * always advances past the failed batch.
     *
     * @return number of records that reached the extractor (excluding DLQ'd ones)
     */
    private long flushBatch(List<LogGroup> batch, List<String> rawBatch,
                            KafkaConsumer<String, String> consumer,
                            DeadLetterProducer dlq) {
        try {
            extractor.processBatch(batch);
            consumer.commitSync();
            log.info("flushed batch of {} records", batch.size());
            return batch.size();
        } catch (RuntimeException e) {
            log.error("batch of {} records failed, falling back to per-record processing: {}",
                    batch.size(), e.getMessage(), e);
            long processed = processOneByOne(batch, rawBatch, dlq);
            consumer.commitSync();
            return processed;
        }
    }

    /**
     * Process each record individually after a batch failure.
     * Records that throw are sent to the dead-letter producer instead of
     * blocking the consumer.
     */
    private long processOneByOne(List<LogGroup> batch, List<String> rawBatch,
                                 DeadLetterProducer dlq) {
        long processed = 0;
        int dlqCount   = 0;
        for (int i = 0; i < batch.size(); i++) {
            try {
                extractor.process(batch.get(i));
                processed++;
            } catch (RuntimeException e) {
                dlqCount++;
                log.warn("poison pill at batch index {}: {} — sending to dead-letter",
                        i, e.getMessage());
                dlq.send(rawBatch.get(i));
            }
        }
        log.info("per-record fallback: {} processed, {} sent to dead-letter", processed, dlqCount);
        return processed;
    }

    private void runCompact(Path dataDir) {
        // Relearn BEFORE compact, while fragmented identity templates and their
        // entries are still in the writable tier — folds are then full rewrites
        // (entries re-planned onto merged templates) instead of forward-only
        // remaps against immutable Parquet. Each round builds on the last: shapes
        // matching an already-learned template never fragment again.
        try {
            extractor.relearn();
        } catch (RuntimeException e) {
            log.warn("relearn failed (ingest continues, will retry next interval): {}",
                    e.getMessage(), e);
        }
        try {
            long t0 = System.currentTimeMillis();
            if (adminService.compactDatabase(dataDir)) {
                log.info("compact completed in {} ms", System.currentTimeMillis() - t0);
            }
        } catch (RuntimeException e) {
            // Compact failures shouldn't kill the ingest loop — log and continue.
            log.warn("compact failed (will retry on next interval): {}", e.getMessage(), e);
        }
    }

    private static LogGroup toLogGroup(String value, String source, Instant kafkaTimestamp) {
        int nl = value.indexOf('\n');
        if (nl < 0) {
            return LogGroup.singleLine(value, source, kafkaTimestamp);
        }
        String header = value.substring(0, nl);
        String[] tail = value.substring(nl + 1).split("\n", -1);
        List<String> continuation = new ArrayList<>(tail.length);
        for (String t : tail)
            continuation.add(t);
        return new LogGroup(header, continuation, source, kafkaTimestamp);
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
