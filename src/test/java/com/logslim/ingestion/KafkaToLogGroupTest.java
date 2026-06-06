package com.logslim.ingestion;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaToLogGroupTest {

    private static LogGroup toLogGroup(String value, String source, Instant ts) throws Exception {
        Method m = KafkaIngestRunner.class.getDeclaredMethod("toLogGroup", String.class, String.class, Instant.class);
        m.setAccessible(true);
        return (LogGroup) m.invoke(null, value, source, ts);
    }

    @Test
    void singleLine_noNewline_returnsSingleLineGroup() throws Exception {
        LogGroup g = toLogGroup("ERROR connection refused", "kafka-prod", Instant.now());
        assertThat(g.headerLine()).isEqualTo("ERROR connection refused");
        assertThat(g.isMultiLine()).isFalse();
    }

    @Test
    void multiLine_firstNewlineSplitsHeaderFromContinuation() throws Exception {
        LogGroup g = toLogGroup("ERROR DB failed\n\tat com.example.Dao(Dao.java:42)", "src", Instant.now());
        assertThat(g.headerLine()).isEqualTo("ERROR DB failed");
        assertThat(g.isMultiLine()).isTrue();
        assertThat(g.continuationLines()).containsExactly("\tat com.example.Dao(Dao.java:42)");
    }

    @Test
    void multipleNewlines_allLinesAfterFirstAreContinuation() throws Exception {
        LogGroup g = toLogGroup("WARN slow query\n\tat A.m(A.java:1)\n\tat B.m(B.java:2)", "src", Instant.now());
        assertThat(g.headerLine()).isEqualTo("WARN slow query");
        assertThat(g.continuationLines()).containsExactly("\tat A.m(A.java:1)", "\tat B.m(B.java:2)");
    }

    @Test
    void source_forwardedToLogGroup() throws Exception {
        LogGroup g = toLogGroup("INFO ok", "kafka-payments", Instant.now());
        assertThat(g.source()).isEqualTo("kafka-payments");
    }

    @Test
    void kafkaTimestamp_forwardedToLogGroup() throws Exception {
        Instant ts = Instant.parse("2024-06-15T12:30:00Z");
        LogGroup g = toLogGroup("INFO startup complete", "src", ts);
        assertThat(g.sourceTimestamp()).isEqualTo(ts);
    }

    @Test
    void singleLine_sourceTimestampSet() throws Exception {
        Instant ts = Instant.parse("2025-01-01T00:00:00Z");
        LogGroup g = toLogGroup("DEBUG cache hit", "src", ts);
        assertThat(g.sourceTimestamp()).isEqualTo(ts);
        assertThat(g.isMultiLine()).isFalse();
    }

    @Test
    void multiLine_sourceAndTimestampForwarded() throws Exception {
        Instant ts = Instant.parse("2025-03-15T09:00:00Z");
        LogGroup g = toLogGroup("ERROR oom\nCaused by: java.lang.OutOfMemoryError", "kafka-jvm", ts);
        assertThat(g.source()).isEqualTo("kafka-jvm");
        assertThat(g.sourceTimestamp()).isEqualTo(ts);
        assertThat(g.continuationLines()).containsExactly("Caused by: java.lang.OutOfMemoryError");
    }
}
