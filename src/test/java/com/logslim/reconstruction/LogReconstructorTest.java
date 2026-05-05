package com.logslim.reconstruction;

import com.logslim.storage.LogEntry;
import com.logslim.storage.Template;
import com.logslim.storage.TemplateDao;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

class LogReconstructorTest {

    // Test the pattern+params overload directly — no DB needed
    private final LogReconstructor reconstructor = new LogReconstructor(null);

    @Test
    void singleParam_roundTrips() {
        assertRoundTrip("User {num} failed login",
                Map.of("num", "123"),
                "User 123 failed login");
    }

    @Test
    void multiParam_roundTrips() {
        assertRoundTrip("User {num} paid {num} dollars",
                Map.of("num", "42", "num_1", "99"),
                "User 42 paid 99 dollars");
    }

    @Test
    void uuid_roundTrips() {
        assertRoundTrip("Request {uuid} received",
                Map.of("uuid", "550e8400-e29b-41d4-a716-446655440000"),
                "Request 550e8400-e29b-41d4-a716-446655440000 received");
    }

    @Test
    void noParams_returnsPatternAsIs() {
        assertRoundTrip("server started", Map.of(), "server started");
    }

    @Test
    void nullParams_returnsPatternAsIs() {
        String result = reconstructor.reconstruct("server started", (Map<String, String>) null);
        assertThat(result).isEqualTo("server started");
    }

    @Test
    void hashToken_roundTrips() {
        assertRoundTrip("Checksum {hash} verified",
                Map.of("hash", "d41d8cd98f00b204e9800998ecf8427e"),
                "Checksum d41d8cd98f00b204e9800998ecf8427e verified");
    }

    @Test
    void timestamp_roundTrips() {
        assertRoundTrip("Event at {ts} completed",
                Map.of("ts", "2024-01-15T10:30:00Z"),
                "Event at 2024-01-15T10:30:00Z completed");
    }

    @Test
    void missingParam_throwsReconstructionException() {
        assertThatThrownBy(() ->
                reconstructor.reconstruct("User {num} login", Map.of())
        ).isInstanceOf(ReconstructionException.class)
         .hasMessageContaining("Missing parameter");
    }

    @Test
    void specialChars_inStaticToken_preservedVerbatim() {
        // Static token containing special chars is kept as-is
        assertRoundTrip("Error: {num} code=ERR",
                Map.of("num", "500"),
                "Error: 500 code=ERR");
    }

    @Test
    void threeOfSamePlaceholder_allSubstituted() {
        assertRoundTrip("A {num} B {num} C {num}",
                Map.of("num", "1", "num_1", "2", "num_2", "3"),
                "A 1 B 2 C 3");
    }

    @Test
    void multiLineEntry_reconstructsFullBlock() {
        Template tmpl = new Template(1L, "ERROR {num} - DB failed", 1L, Instant.now(), Instant.now());
        TemplateDao dao = new TemplateDao(null) {
            @Override public Optional<Template> findById(long id) { return Optional.of(tmpl); }
        };

        LogEntry entry = new LogEntry(1L, 1L, Instant.now(), List.of("42"),
                "\tat com.example.Dao.find(Dao.java:42)\n\tat com.example.Svc.get(Svc.java:18)");

        String result = new LogReconstructor(dao).reconstruct(entry);
        assertThat(result).isEqualTo(
                "ERROR 42 - DB failed\n\tat com.example.Dao.find(Dao.java:42)\n\tat com.example.Svc.get(Svc.java:18)");
    }

    @Test
    void singleLineEntry_noTrailingNewline() {
        Template tmpl = new Template(2L, "INFO server started", 1L, Instant.now(), Instant.now());
        TemplateDao dao = new TemplateDao(null) {
            @Override public Optional<Template> findById(long id) { return Optional.of(tmpl); }
        };

        LogEntry entry = new LogEntry(1L, 2L, Instant.now(), List.of(), null);

        String result = new LogReconstructor(dao).reconstruct(entry);
        assertThat(result).isEqualTo("INFO server started");
        assertThat(result).doesNotContain("\n");
    }

    private void assertRoundTrip(String pattern, Map<String, String> params, String expected) {
        assertThat(reconstructor.reconstruct(pattern, params)).isEqualTo(expected);
    }
}
