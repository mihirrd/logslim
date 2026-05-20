package com.logslim.ingestion;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MultiLineGrouperTest {

    private List<LogGroup> groups(String input) {
        BufferedReader reader = new BufferedReader(new StringReader(input));
        List<LogGroup> result = new java.util.ArrayList<>();
        for (LogGroup g : new MultiLineGrouper(reader, "test")) result.add(g);
        return result;
    }

    @Test
    void singleLine_producesOneGroup() {
        List<LogGroup> result = groups("ERROR something happened");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).headerLine()).isEqualTo("ERROR something happened");
        assertThat(result.get(0).isMultiLine()).isFalse();
    }

    @Test
    void stackTrace_groupedUnderHeader() {
        String input = """
                ERROR com.example.App - DB failed
                \tat com.example.Dao.find(Dao.java:42)
                \tat com.example.Svc.get(Svc.java:18)
                \tat com.example.Main.main(Main.java:5)
                """;
        List<LogGroup> result = groups(input);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).headerLine()).isEqualTo("ERROR com.example.App - DB failed");
        assertThat(result.get(0).continuationLines()).hasSize(3);
        assertThat(result.get(0).continuationLines().get(0)).isEqualTo("\tat com.example.Dao.find(Dao.java:42)");
    }

    @Test
    void causedByLine_isContinuation() {
        String input = """
                java.lang.RuntimeException: outer
                \tat com.example.A.m(A.java:1)
                Caused by: java.io.IOException: inner
                \tat com.example.B.m(B.java:2)
                """;
        List<LogGroup> result = groups(input);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).continuationLines()).hasSize(3);
    }

    @Test
    void ellipsisMore_isContinuation() {
        String input = """
                java.lang.RuntimeException: err
                \tat com.example.A.m(A.java:1)
                ... 5 more
                """;
        List<LogGroup> result = groups(input);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).continuationLines()).hasSize(2);
    }

    @Test
    void blankLineFlushesGroup() {
        String input = "ERROR first\n\tframe1\n\nERROR second\n\tframe2\n";
        List<LogGroup> result = groups(input);
        assertThat(result).hasSize(2);
        assertThat(result.get(0).headerLine()).isEqualTo("ERROR first");
        assertThat(result.get(1).headerLine()).isEqualTo("ERROR second");
    }

    @Test
    void consecutiveHeaders_twoGroups() {
        String input = "ERROR first\nERROR second\n";
        List<LogGroup> result = groups(input);
        assertThat(result).hasSize(2);
        assertThat(result.get(0).isMultiLine()).isFalse();
        assertThat(result.get(1).isMultiLine()).isFalse();
    }

    @Test
    void eofFlushesLastGroup() {
        // No trailing newline
        String input = "ERROR something\n\tat com.example.X.m(X.java:1)";
        List<LogGroup> result = groups(input);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).continuationLines()).hasSize(1);
    }

    @Test
    void allBlankInput_producesNoGroups() {
        List<LogGroup> result = groups("\n\n\n");
        assertThat(result).isEmpty();
    }

    @Test
    void pythonTraceback_groupedUnderTimestampHeader() {
        String input = """
                2026-05-19 16:51:33.508 [ERROR] worker-queue — Countdown task missed
                Traceback (most recent call last):
                  File "/app/workers/email_worker.py", line 67, in send_transactional_email
                    smtp.sendmail(sender, [recipient], msg.as_string())
                smtplib.SMTPException: SMTP AUTH extension not supported by server.
                2026-05-19 16:51:34.059 [INFO ] auth-service — OAuth2 login success
                """;
        List<LogGroup> result = groups(input);
        assertThat(result).hasSize(2);
        assertThat(result.get(0).headerLine()).startsWith("2026-05-19 16:51:33.508");
        assertThat(result.get(0).continuationLines()).hasSize(4);
        assertThat(result.get(1).headerLine()).startsWith("2026-05-19 16:51:34.059");
        assertThat(result.get(1).isMultiLine()).isFalse();
    }

    @Test
    void timestampedHeaders_consecutive_eachOwnGroup() {
        String input = """
                2026-05-19 16:51:29.006 [INFO ] notification — Template published
                2026-05-19 16:51:29.504 [DEBUG] auth-service — MFA TOTP verified
                """;
        List<LogGroup> result = groups(input);
        assertThat(result).hasSize(2);
        assertThat(result.get(0).isMultiLine()).isFalse();
        assertThat(result.get(1).isMultiLine()).isFalse();
    }

    @Test
    void startsWithTimestamp_isoSpace_true() {
        assertThat(MultiLineGrouper.startsWithTimestamp("2026-05-19 16:51:29.006 [INFO] x")).isTrue();
    }

    @Test
    void startsWithTimestamp_isoT_true() {
        assertThat(MultiLineGrouper.startsWithTimestamp("2026-05-19T16:51:29 something")).isTrue();
    }

    @Test
    void startsWithTimestamp_plainText_false() {
        assertThat(MultiLineGrouper.startsWithTimestamp("Traceback (most recent call last):")).isFalse();
    }

    // --- isContinuation static method unit tests ---

    @Test
    void isContinuation_tabLine_true() {
        assertThat(MultiLineGrouper.isContinuation("\tat com.example.X.m(X.java:1)")).isTrue();
    }

    @Test
    void isContinuation_fourSpaces_true() {
        assertThat(MultiLineGrouper.isContinuation("    indented content")).isTrue();
    }

    @Test
    void isContinuation_threeSpaces_false() {
        assertThat(MultiLineGrouper.isContinuation("   three spaces")).isFalse();
    }

    @Test
    void isContinuation_normalLine_false() {
        assertThat(MultiLineGrouper.isContinuation("ERROR something happened")).isFalse();
    }

    @Test
    void isContinuation_causedBy_true() {
        assertThat(MultiLineGrouper.isContinuation("Caused by: java.io.IOException: msg")).isTrue();
    }

    @Test
    void isContinuation_ellipsisMore_true() {
        assertThat(MultiLineGrouper.isContinuation("... 12 more")).isTrue();
    }
}
