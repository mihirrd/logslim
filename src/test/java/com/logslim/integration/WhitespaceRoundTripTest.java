package com.logslim.integration;

import com.logslim.extraction.TemplateExtractor;
import com.logslim.query.LogQueryService;
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
 * Verifies that the reconstruction safety net guarantees byte-exact losslessness
 * for lines that cannot round-trip through tokenise→reconstruct (e.g. multi-space,
 * tab-separated, leading/trailing whitespace). Such lines must appear verbatim in
 * the replay output — they are routed to raw_logs rather than log_entries.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:duckdb:",
        "spring.datasource.hikari.maximum-pool-size=1",
        "logslim.template.max-count=100000",
        // Lock immediately so templates are created on the very first occurrence
        "logslim.drain.lock-after-n=1",
        "logslim.drain.sim-threshold=0.6"
})
class WhitespaceRoundTripTest {

    @Autowired TemplateExtractor extractor;
    @Autowired LogQueryService   queryService;
    @Autowired NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void cleanDb() {
        extractor.reset();
        jdbc.update("DELETE FROM log_entries", Map.of());
        jdbc.update("DELETE FROM templates",   Map.of());
        jdbc.update("DELETE FROM raw_logs",    Map.of());
    }

    /**
     * Lines with non-standard whitespace (multiple spaces, tab separator, or
     * leading/trailing spaces) must be replayed verbatim — byte-for-byte identical
     * to the original. The safety net routes these to raw_logs; replay emits them
     * unchanged, guaranteeing losslessness.
     */
    @Test
    void whitespaceLines_roundTripByteExact() {
        // multiple consecutive spaces between tokens (level padding)
        String multiSpace   = "INFO  [svc] x 5 ms";
        // tab as separator
        String tabSeparated = "WARN\t[svc] connection dropped";
        // leading and trailing whitespace
        String padded       = "  ERROR db timeout  ";

        List<String> originals = List.of(multiSpace, tabSeparated, padded);

        // Ingest all three lines
        originals.forEach(line -> extractor.process(line, "test"));

        // Replay and collect all lines
        List<String> replayed = queryService.replayLogs(Duration.ofDays(1));

        // Every original line must appear byte-exactly in the replay output
        for (String original : originals) {
            assertThat(replayed)
                    .as("Expected verbatim replay of: [" + original + "]")
                    .contains(original);
        }
    }

    /**
     * Lines that round-trip exactly (normal single-space tokens, no surrounding
     * whitespace) are still stored as structured entries and replay correctly.
     */
    @Test
    void normalLines_replayByteExact() {
        List<String> normals = List.of(
                "User 123 failed login",
                "DB timeout on shard 7",
                "Request completed in 42 ms"
        );

        normals.forEach(line -> extractor.process(line, "test"));
        normals.forEach(line -> extractor.process(line, "test")); // second pass — same templates

        List<String> replayed = queryService.replayLogs(Duration.ofDays(1));

        for (String original : normals) {
            long count = replayed.stream().filter(l -> l.equals(original)).count();
            assertThat(count)
                    .as("Expected exactly 2 occurrences of [" + original + "] in replay")
                    .isEqualTo(2);
        }
    }

    /**
     * Regression test: a log line whose static path contains literal {sku} braces must
     * ingest without crashing and replay byte-exactly.  The siblings (different
     * status/user/duration) exercise that the template is properly shared and that the
     * literal {sku} in the path is never mistaken for a placeholder.
     */
    @Test
    void literalBracesInPath_roundTripByteExact() {
        List<String> lines = List.of(
                "2026-05-15T14:21:00Z INFO [gw] GET /api/v1/inventory/{sku} status=200 user=u1 duration=10ms",
                "2026-05-15T14:21:01Z INFO [gw] GET /api/v1/inventory/{sku} status=404 user=u2 duration=5ms",
                "2026-05-15T14:21:02Z INFO [gw] GET /api/v1/inventory/{sku} status=500 user=u3 duration=20ms"
        );

        lines.forEach(line -> extractor.process(line, "test"));

        List<String> replayed = queryService.replayLogs(Duration.ofDays(36500));

        for (String original : lines) {
            assertThat(replayed)
                    .as("Expected byte-exact replay of: [" + original + "]")
                    .contains(original);
        }
    }
}
