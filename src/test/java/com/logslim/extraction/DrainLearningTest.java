package com.logslim.extraction;

import com.logslim.query.LogQueryService;
import com.logslim.storage.TemplateDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Drain-specific behaviours that only manifest with
 * lockAfterN > 1 (i.e. when the cluster must observe multiple similar lines
 * before a template is committed to the DB).
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:duckdb:",
        "spring.datasource.hikari.maximum-pool-size=1",
        "logslim.template.max-count=100000",
        "logslim.drain.lock-after-n=2",
        "logslim.drain.sim-threshold=0.5"
})
class DrainLearningTest {

    @Autowired TemplateExtractor extractor;
    @Autowired TemplateDao       templateDao;
    @Autowired LogQueryService   logQueryService;
    @Autowired NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        extractor.reset();
        jdbc.update("DELETE FROM log_entries", Map.of());
        jdbc.update("DELETE FROM templates",   Map.of());
        jdbc.update("DELETE FROM raw_logs",    Map.of());
    }

    // — pre-lock / lock transition ————————————————————————————

    @Test
    void preLockLine_storedAsRaw() {
        extractor.process("User 123 failed login", "test");

        assertThat(templateDao.count()).isZero();
        assertThat(countRows("raw_logs")).isEqualTo(1);
        assertThat(countRows("log_entries")).isZero();
    }

    @Test
    void lockOccurrence_createsTemplateAndLogEntry() {
        extractor.process("User 123 failed login", "test");  // pre-lock → raw
        extractor.process("User 456 failed login", "test");  // locks → log_entry

        assertThat(templateDao.count()).isEqualTo(1);
        assertThat(countRows("log_entries")).isEqualTo(1);
        assertThat(countRows("raw_logs")).isEqualTo(1);
    }

    @Test
    void postLockLines_storedAsLogEntry() {
        for (int i = 1; i <= 5; i++) {
            extractor.process("User " + i + " failed login", "test");
        }
        // line 1 → raw, line 2 → locks + log_entry, lines 3-5 → log_entries
        assertThat(countRows("raw_logs")).isEqualTo(1);
        assertThat(countRows("log_entries")).isEqualTo(4);
    }

    // — novel token discovery (the core Drain benefit) ————————

    @Test
    void novelPrefixedId_discoveredAsDynamic() {
        // req-755556 is not caught by any regex in TokenClassifier,
        // so Drain must learn it is dynamic by observing two different values.
        extractor.process("Request req-755556 processed", "test");
        extractor.process("Request req-123456 processed", "test");

        assertThat(templateDao.count()).isEqualTo(1);
        String pattern = templateDao.findTopN(null, 1).get(0).getPattern();
        assertThat(pattern).doesNotContain("req-755556");
        assertThat(pattern).doesNotContain("req-123456");
        assertThat(pattern).startsWith("Request");
        assertThat(pattern).endsWith("processed");
    }

    @Test
    void novelPrefixedId_bothLinesReplayable() {
        extractor.process("Request req-755556 processed", "test");
        extractor.process("Request req-123456 processed", "test");

        List<String> replayed = logQueryService.replayLogs(Duration.ofDays(1));
        assertThat(replayed).containsExactlyInAnyOrder(
                "Request req-755556 processed",
                "Request req-123456 processed");
    }

    @Test
    void novelTokenFormat_compressesAfterLearning() {
        // Both req-NNNNN and NNms are not pre-classified by TokenClassifier.
        // Drain must discover both positions as dynamic from the data.
        for (int i = 0; i < 20; i++) {
            extractor.process("job req-" + String.format("%05d", i) + " completed in " + (i + 1) + "ms", "test");
        }

        // Line 0 pre-lock → raw, lines 1-19 post-lock → log_entries
        assertThat(templateDao.count()).isEqualTo(1);
        assertThat(countRows("log_entries")).isEqualTo(19);
        assertThat(countRows("raw_logs")).isEqualTo(1);
    }

    // — bootstrap correctness ————————————————————————————————

    @Test
    void bootstrap_newLineUsesExistingTemplate() {
        // Seed two lines so the cluster locks and a template is written to DB
        extractor.process("DB timeout on shard 1", "test");
        extractor.process("DB timeout on shard 2", "test");
        assertThat(templateDao.count()).isEqualTo(1);
        assertThat(countRows("log_entries")).isEqualTo(1);

        // Simulate a restart: clear in-memory state, reload from DB
        extractor.reset();
        extractor.bootstrapDrain();

        // A new matching line should reuse the existing template, not create a duplicate
        extractor.process("DB timeout on shard 3", "test");

        assertThat(templateDao.count()).isEqualTo(1);
        assertThat(countRows("log_entries")).isEqualTo(2);
    }

    @Test
    void bootstrap_noTemplateDuplication() {
        extractor.process("Cache miss key 1", "test");
        extractor.process("Cache miss key 2", "test");
        assertThat(templateDao.count()).isEqualTo(1);

        // Reset + re-bootstrap (simulated restart)
        extractor.reset();
        extractor.bootstrapDrain();

        extractor.process("Cache miss key 3", "test");
        extractor.process("Cache miss key 4", "test");

        assertThat(templateDao.count()).isEqualTo(1);
    }

    // — helpers ——————————————————————————————————————————————

    private long countRows(String table) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Map.of(), Long.class);
        return n == null ? 0 : n;
    }
}
