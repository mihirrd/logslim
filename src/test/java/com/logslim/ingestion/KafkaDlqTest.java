package com.logslim.ingestion;

import com.logslim.extraction.TemplateExtractor;
import com.logslim.storage.RawLog;
import com.logslim.storage.RawLogDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;



import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the Kafka dead letter queue path: records that cannot yet be structured
 * (pre-lock) or exceed the template cap are stored in raw_logs.
 *
 * lock-after-n=2 means the first occurrence of any pattern goes to raw_logs
 * (DLQ) and the second locks the cluster, going to log_entries.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:duckdb:",
        "spring.datasource.hikari.maximum-pool-size=1",
        "logslim.template.max-count=10",
        "logslim.drain.lock-after-n=2",
        "logslim.drain.sim-threshold=0.6"
})
class KafkaDlqTest {

    @Autowired TemplateExtractor extractor;
    @Autowired RawLogDao rawLogDao;
    @Autowired NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void cleanDb() {
        extractor.reset();
        jdbc.update("DELETE FROM log_entries", Map.of());
        jdbc.update("DELETE FROM templates",   Map.of());
        jdbc.update("DELETE FROM raw_logs",    Map.of());
    }

    @Test
    void firstOccurrence_storedInRawLogs_notLogEntries() {
        extractor.processBatch(List.of(
                LogGroup.singleLine("ERROR user 123 not found", "kafka-app", Instant.now())
        ));
        assertThat(rawLogDao.count()).isEqualTo(1);
        assertThat(logEntryCount()).isZero();
    }

    @Test
    void firstOccurrence_kafkaSourcePreservedInDlq() {
        extractor.processBatch(List.of(
                LogGroup.singleLine("ERROR user 1 not found", "kafka-payments", Instant.now())
        ));
        List<RawLog> raws = rawLogDao.findByTimeRange(Instant.EPOCH, Instant.parse("9999-01-01T00:00:00Z"));
        assertThat(raws).hasSize(1);
        assertThat(raws.get(0).getSource()).isEqualTo("kafka-payments");
    }

    @Test
    void firstOccurrence_kafkaTimestampPreservedInDlq() {
        Instant kafkaTs = Instant.parse("2024-12-25T08:15:00Z");
        extractor.processBatch(List.of(
                LogGroup.singleLine("ERROR user 1 not found", "kafka-jobs", kafkaTs)
        ));
        List<RawLog> raws = rawLogDao.findByTimeRange(Instant.EPOCH, Instant.parse("9999-01-01T00:00:00Z"));
        assertThat(raws).hasSize(1);
        assertThat(raws.get(0).getLogTimestamp()).isEqualTo(kafkaTs);
    }

    @Test
    void secondOccurrence_locksTemplate_storedAsLogEntry() {
        Instant ts = Instant.parse("2025-01-01T00:00:00Z");
        extractor.processBatch(List.of(
                LogGroup.singleLine("ERROR user 1 not found", "kafka-app", ts),
                LogGroup.singleLine("ERROR user 2 not found", "kafka-app", ts)
        ));
        // First → DLQ (cluster not locked), second → locks cluster → log_entry
        assertThat(rawLogDao.count()).isEqualTo(1);
        assertThat(logEntryCount()).isEqualTo(1);
    }

    @Test
    void mixedBatch_firstOccurrenceToDlq_subsequentToEntries() {
        Instant ts = Instant.parse("2025-06-01T00:00:00Z");
        extractor.processBatch(List.of(
                LogGroup.singleLine("WARN connection pool limit 1 reached", "kafka-svc", ts),
                LogGroup.singleLine("WARN connection pool limit 2 reached", "kafka-svc", ts),
                LogGroup.singleLine("WARN connection pool limit 3 reached", "kafka-svc", ts)
        ));
        // First → DLQ, 2nd locks → log_entry, 3rd matches locked cluster → log_entry
        assertThat(rawLogDao.count()).isEqualTo(1);
        assertThat(logEntryCount()).isEqualTo(2);
    }

    @Test
    void multiLineRecord_preLock_onlyHeaderStoredInDlq() {
        Instant kafkaTs = Instant.parse("2025-05-10T14:00:00Z");
        LogGroup multiLine = new LogGroup(
                "ERROR database connection failed",
                List.of("\tat com.example.Dao.connect(Dao.java:42)", "\tat com.example.Svc.init(Svc.java:18)"),
                "kafka-db",
                kafkaTs
        );
        extractor.processBatch(List.of(multiLine));
        List<RawLog> raws = rawLogDao.findByTimeRange(Instant.EPOCH, Instant.parse("9999-01-01T00:00:00Z"));
        assertThat(raws).hasSize(1);
        assertThat(raws.get(0).getContent()).isEqualTo("ERROR database connection failed");
        assertThat(raws.get(0).getSource()).isEqualTo("kafka-db");
        assertThat(raws.get(0).getLogTimestamp()).isEqualTo(kafkaTs);
    }

    @Test
    void multiLineRecord_locked_storedAsLogEntry_notDlq() {
        Instant ts = Instant.now();
        // Two multi-line records with same header pattern: first → DLQ, second locks → log_entry
        extractor.processBatch(List.of(
                new LogGroup("ERROR database connection failed",
                        List.of("\tat com.example.Dao(Dao.java:42)"), "kafka-db", ts),
                new LogGroup("ERROR database connection failed",
                        List.of("\tat com.example.Dao(Dao.java:55)"), "kafka-db", ts)
        ));
        assertThat(rawLogDao.count()).isEqualTo(1);
        assertThat(logEntryCount()).isEqualTo(1);
    }

    @Test
    void templateCapExceeded_newPatternStoredInDlq() {
        // Fill the template cap: 10 unique patterns × 2 occurrences each
        String[] setupPatterns = {
            "alpha service restarted successfully",
            "beta cache eviction triggered now",
            "gamma queue depth exceeded threshold",
            "delta replica sync completed successfully",
            "epsilon circuit breaker opened now",
            "zeta health check passed successfully",
            "eta worker thread pool exhausted now",
            "theta rate limiter activated successfully",
            "iota checkpoint flush completed now",
            "kappa lease renewal succeeded successfully"
        };
        for (String line : setupPatterns) {
            extractor.processBatch(List.of(
                    LogGroup.singleLine(line, "src", Instant.now()),
                    LogGroup.singleLine(line, "src", Instant.now())
            ));
        }
        jdbc.update("DELETE FROM raw_logs", Map.of());

        // Sending a new pattern: first → DLQ (pre-lock), second → hits cap → DLQ
        extractor.processBatch(List.of(
                LogGroup.singleLine("overflow request failed now completely", "kafka-overflow", Instant.now()),
                LogGroup.singleLine("overflow request failed now completely", "kafka-overflow", Instant.now())
        ));
        assertThat(rawLogDao.count()).isEqualTo(2);
    }

    private long logEntryCount() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM log_entries", Map.of(), Long.class);
        return n == null ? 0 : n;
    }
}
