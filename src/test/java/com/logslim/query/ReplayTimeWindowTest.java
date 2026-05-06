package com.logslim.query;

import com.logslim.extraction.TemplateExtractor;
import com.logslim.storage.*;
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

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:duckdb:",
        "spring.datasource.hikari.maximum-pool-size=1",
        "logslim.drain.lock-after-n=1",
        "logslim.drain.sim-threshold=0.5"
})
class ReplayTimeWindowTest {

    @Autowired LogQueryService    queryService;
    @Autowired TemplateExtractor  extractor;
    @Autowired TemplateDao        templateDao;
    @Autowired LogEntryDao        logEntryDao;
    @Autowired RawLogDao          rawLogDao;
    @Autowired NamedParameterJdbcTemplate jdbc;

    // Fixed reference points used across all tests
    private static final Instant T_MORNING   = Instant.parse("2024-01-15T08:00:00Z");
    private static final Instant T_NOON      = Instant.parse("2024-01-15T12:00:00Z");
    private static final Instant T_AFTERNOON = Instant.parse("2024-01-15T16:00:00Z");
    private static final Instant T_EVENING   = Instant.parse("2024-01-15T20:00:00Z");

    @BeforeEach
    void reset() {
        extractor.reset();
        jdbc.update("DELETE FROM log_entries", Map.of());
        jdbc.update("DELETE FROM raw_logs",    Map.of());
        jdbc.update("DELETE FROM templates",   Map.of());
    }

    // ── exact window filtering ────────────────────────────────────────────────

    @Test
    void window_returnsOnlyLogsWithinRange() {
        seedThreeEntries();

        List<String> results = queryService.replayLogs(T_MORNING, T_AFTERNOON);

        assertThat(results).hasSize(2);
        assertThat(results.get(0)).contains("alice");
        assertThat(results.get(1)).contains("bob");
    }

    @Test
    void window_exclusiveOfOutsideEntries() {
        seedThreeEntries();

        List<String> results = queryService.replayLogs(T_NOON, T_AFTERNOON);

        assertThat(results).hasSize(1);
        assertThat(results.get(0)).contains("bob");
    }

    @Test
    void emptyWindow_returnsNoLogs() {
        seedThreeEntries();

        // window entirely before any data
        List<String> results = queryService.replayLogs(
                Instant.parse("2024-01-14T00:00:00Z"),
                Instant.parse("2024-01-14T23:59:59Z"));

        assertThat(results).isEmpty();
    }

    @Test
    void epochToNow_returnsAllLogs() {
        seedThreeEntries();

        List<String> results = queryService.replayLogs(Instant.EPOCH, Instant.now());

        assertThat(results).hasSize(3);
    }

    // ── temporal ordering ─────────────────────────────────────────────────────

    @Test
    void results_areOrderedByTimestamp() {
        seedThreeEntries();

        List<String> results = queryService.replayLogs(Instant.EPOCH, Instant.now());

        // inserted out of order — must come back in chronological order
        assertThat(results.get(0)).contains("alice");
        assertThat(results.get(1)).contains("bob");
        assertThat(results.get(2)).contains("charlie");
    }

    // ── raw_logs are included in the window ───────────────────────────────────

    @Test
    void window_includesRawLogs() {
        rawLogDao.insert(new RawLog(null, "raw morning event", T_MORNING, "test", Instant.now()));
        rawLogDao.insert(new RawLog(null, "raw evening event", T_EVENING, "test", Instant.now()));

        List<String> results = queryService.replayLogs(T_MORNING, T_AFTERNOON);

        assertThat(results).hasSize(1);
        assertThat(results.get(0)).isEqualTo("raw morning event");
    }

    @Test
    void window_mergesLogEntriesAndRawLogsInOrder() {
        Template t = templateDao.insert(Template.newTemplate("User {num} logged in"));
        logEntryDao.insert(new LogEntry(null, t.getId(), T_NOON, List.of("alice"), null));
        rawLogDao.insert(new RawLog(null, "raw morning event", T_MORNING, "test", Instant.now()));

        List<String> results = queryService.replayLogs(Instant.EPOCH, Instant.now());

        assertThat(results).hasSize(2);
        assertThat(results.get(0)).isEqualTo("raw morning event");   // T_MORNING comes first
        assertThat(results.get(1)).contains("alice");                 // T_NOON comes second
    }

    // ── boundary conditions ───────────────────────────────────────────────────

    @Test
    void fromEqualsTo_returnsLogsAtExactInstant() {
        Template t = templateDao.insert(Template.newTemplate("Ping {num}"));
        logEntryDao.insert(new LogEntry(null, t.getId(), T_NOON, List.of("1"), null));

        List<String> results = queryService.replayLogs(T_NOON, T_NOON);

        assertThat(results).hasSize(1);
    }

    @Test
    void noData_returnsEmptyList() {
        List<String> results = queryService.replayLogs(Instant.EPOCH, Instant.now());
        assertThat(results).isEmpty();
    }

    // ── integration: via TemplateExtractor (uses Instant.now() as timestamp) ──

    @Test
    void extractor_logsAreRetrievableWithBroadWindow() {
        extractor.process("User 1 logged in", "test");
        extractor.process("User 2 logged in", "test");

        // broad window centred on now captures both
        Instant from = Instant.now().minusSeconds(10);
        Instant to   = Instant.now().plusSeconds(10);

        List<String> results = queryService.replayLogs(from, to);
        assertThat(results).hasSize(2);
    }

    @Test
    void extractor_futureWindowReturnsNothing() {
        extractor.process("User 1 logged in", "test");

        Instant from = Instant.now().plusSeconds(60);
        Instant to   = Instant.now().plusSeconds(120);

        List<String> results = queryService.replayLogs(from, to);
        assertThat(results).isEmpty();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Inserts three log entries at MORNING, NOON, EVENING — intentionally out of insertion order. */
    private void seedThreeEntries() {
        Template t = templateDao.insert(Template.newTemplate("User {num} logged in"));
        logEntryDao.insert(new LogEntry(null, t.getId(), T_EVENING, List.of("charlie"), null));
        logEntryDao.insert(new LogEntry(null, t.getId(), T_MORNING, List.of("alice"),   null));
        logEntryDao.insert(new LogEntry(null, t.getId(), T_NOON,    List.of("bob"),     null));
    }
}
