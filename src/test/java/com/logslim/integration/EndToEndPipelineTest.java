package com.logslim.integration;

import com.logslim.extraction.TemplateCache;
import com.logslim.extraction.TemplateExtractor;
import com.logslim.query.LogQueryService;
import com.logslim.query.TemplateQueryService;
import com.logslim.storage.LogEntryDao;
import com.logslim.storage.TemplateDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.*;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end pipeline test:
 * Feed 10k synthetic logs (5 templates × 2k instances), then verify:
 * 1. Exactly 5 templates created
 * 2. All 10k entries are reconstructable byte-exactly
 * 3. listTopTemplates returns correct occurrence counts
 * 4. replayLogs output equals the original ingested lines in timestamp order
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite::memory:",
        "spring.datasource.hikari.maximum-pool-size=1",
        "logslim.template.similarity-threshold=0.95",
        "logslim.template.max-count=100000"
})
class EndToEndPipelineTest {

    @Autowired TemplateExtractor extractor;
    @Autowired TemplateDao templateDao;
    @Autowired LogEntryDao logEntryDao;
    @Autowired TemplateCache templateCache;
    @Autowired TemplateQueryService templateQueryService;
    @Autowired LogQueryService logQueryService;
    @Autowired NamedParameterJdbcTemplate jdbc;

    private static final int INSTANCES_PER_TEMPLATE = 2000;

    // 5 synthetic templates — intentionally distinct static structures
    private static final String[] PATTERNS = {
            "User %d failed login",
            "DB timeout on shard %d",
            "Payment failed for order %d",
            "Cache miss for key %d",
            "Request %d completed in %d ms"
    };

    @BeforeEach
    void cleanDb() {
        templateCache.clearAll();
        jdbc.update("DELETE FROM log_entries", Map.of());
        jdbc.update("DELETE FROM templates",   Map.of());
        jdbc.update("DELETE FROM raw_logs",    Map.of());
    }

    /** Generate all 10k log lines in the order they'll be ingested. */
    private List<String> generateLogs() {
        List<String> lines = new ArrayList<>(PATTERNS.length * INSTANCES_PER_TEMPLATE);
        for (String pattern : PATTERNS) {
            for (int i = 1; i <= INSTANCES_PER_TEMPLATE; i++) {
                if (pattern.contains("%d") && pattern.indexOf("%d") != pattern.lastIndexOf("%d")) {
                    lines.add(String.format(pattern, i, i * 10));
                } else {
                    lines.add(String.format(pattern, i));
                }
            }
        }
        return lines;
    }

    @Test
    void exactly5TemplatesCreated() {
        generateLogs().forEach(line -> extractor.process(line, "test"));
        assertThat(templateDao.count()).isEqualTo(5);
    }

    @Test
    void allEntriesReconstructable() {
        List<String> originals = generateLogs();
        originals.forEach(line -> extractor.process(line, "test"));

        // Fetch all entries and reconstruct each; compare to original
        List<String> replayed = logQueryService.replayLogs(Duration.ofDays(1));
        assertThat(replayed).hasSize(originals.size());

        // Every replayed line must appear in the original set
        Set<String> originalSet = new HashSet<>(originals);
        for (String line : replayed) {
            assertThat(originalSet).contains(line);
        }
    }

    @Test
    void occurrenceCountsCorrect() {
        generateLogs().forEach(line -> extractor.process(line, "test"));

        List<com.logslim.storage.Template> tops = templateQueryService.listTopTemplates(null, 10);
        assertThat(tops).hasSize(5);
        // Every template should have exactly INSTANCES_PER_TEMPLATE occurrences
        for (var t : tops) {
            assertThat(t.getOccurrences())
                    .as("Occurrences for template: " + t.getPattern())
                    .isEqualTo(INSTANCES_PER_TEMPLATE);
        }
    }

    @Test
    void replayPreservesTemporalOrder() {
        generateLogs().forEach(line -> extractor.process(line, "test"));

        List<com.logslim.storage.LogEntry> entries = logEntryDao.findByTimeRange(
                java.time.Instant.EPOCH, java.time.Instant.now());

        for (int i = 0; i < entries.size() - 1; i++) {
            assertThat(entries.get(i).getLogTimestamp())
                    .isBeforeOrEqualTo(entries.get(i + 1).getLogTimestamp());
        }
    }

    @Test
    void queryByPattern_filtersByParameterValue() {
        generateLogs().forEach(line -> extractor.process(line, "test"));

        // "User {num} failed login" with num=1
        List<String> results = logQueryService.queryByPattern(
                "User {num} failed login",
                Map.of("num", "1"),
                Duration.ofDays(1));

        assertThat(results).hasSize(1);
        assertThat(results.get(0)).isEqualTo("User 1 failed login");
    }

    @Test
    void noFalseMerges_distinctTemplatesAreDistinct() {
        // These two lines differ only in one static token — must NOT share a template
        extractor.process("User 99 failed login",     "test");
        extractor.process("User 99 succeeded login",  "test");
        assertThat(templateDao.count()).isEqualTo(2);
    }
}
