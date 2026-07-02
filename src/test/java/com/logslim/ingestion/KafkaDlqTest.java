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
 * Tests the Kafka dead-letter path under the deterministic parser: every record
 * is structured immediately (there is no lock gate and no learn-in-flight raw
 * pile), so raw_logs receives a record only when the template-count cap is
 * exceeded — and must then preserve the Kafka source and timestamp.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:duckdb:",
        "spring.datasource.hikari.maximum-pool-size=1",
        "logslim.template.max-count=10"
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
    void firstOccurrence_storedStructured_notDlq() {
        extractor.processBatch(List.of(
                LogGroup.singleLine("ERROR user 123 not found", "kafka-app", Instant.now())
        ));
        assertThat(rawLogDao.count()).isZero();
        assertThat(logEntryCount()).isEqualTo(1);
    }

    @Test
    void variantsOfSamePattern_shareOneTemplateFromTheFirstRecord() {
        Instant ts = Instant.parse("2025-01-01T00:00:00Z");
        extractor.processBatch(List.of(
                LogGroup.singleLine("ERROR user 1 not found", "kafka-app", ts),
                LogGroup.singleLine("ERROR user 2 not found", "kafka-app", ts)
        ));
        assertThat(rawLogDao.count()).isZero();
        assertThat(logEntryCount()).isEqualTo(2);
        assertThat(templateCount()).isEqualTo(1);
    }

    @Test
    void multiLineRecord_firstOccurrence_storedAsLogEntry_notDlq() {
        Instant kafkaTs = Instant.parse("2025-05-10T14:00:00Z");
        LogGroup multiLine = new LogGroup(
                "ERROR database connection failed",
                List.of("\tat com.example.Dao.connect(Dao.java:42)", "\tat com.example.Svc.init(Svc.java:18)"),
                "kafka-db",
                kafkaTs
        );
        extractor.processBatch(List.of(multiLine));
        assertThat(rawLogDao.count()).isZero();
        assertThat(logEntryCount()).isEqualTo(1);
    }

    @Test
    void templateCapExceeded_newPatternStoredInDlq() {
        fillTemplateCap();

        extractor.processBatch(List.of(
                LogGroup.singleLine("overflow request failed now completely", "kafka-overflow", Instant.now()),
                LogGroup.singleLine("overflow request failed now completely", "kafka-overflow", Instant.now())
        ));
        assertThat(rawLogDao.count()).isEqualTo(2);
    }

    @Test
    void capOverflow_kafkaSourceAndTimestampPreservedInDlq() {
        fillTemplateCap();

        Instant kafkaTs = Instant.parse("2024-12-25T08:15:00Z");
        extractor.processBatch(List.of(
                LogGroup.singleLine("overflow request failed now completely", "kafka-payments", kafkaTs)
        ));
        List<RawLog> raws = rawLogDao.findByTimeRange(Instant.EPOCH, Instant.parse("9999-01-01T00:00:00Z"));
        assertThat(raws).hasSize(1);
        assertThat(raws.get(0).getSource()).isEqualTo("kafka-payments");
        assertThat(raws.get(0).getLogTimestamp()).isEqualTo(kafkaTs);
    }

    /** Ten entity-free lines → ten distinct identity templates → cap (max-count=10) reached. */
    private void fillTemplateCap() {
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
            extractor.processBatch(List.of(LogGroup.singleLine(line, "src", Instant.now())));
        }
        assertThat(templateCount()).isEqualTo(10);
        assertThat(rawLogDao.count()).isZero();
    }

    private long logEntryCount() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM log_entries", Map.of(), Long.class);
        return n == null ? 0 : n;
    }

    private long templateCount() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM templates", Map.of(), Long.class);
        return n == null ? 0 : n;
    }
}
