package com.logslim.parsing;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Component
public class LogTokenizer {

    private static final List<DateTimeFormatter> TIMESTAMP_FORMATTERS = List.of(
        // With offset: 2025-12-29T09:56:43.968+0530
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXX"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXX"),
        // Without offset (assume UTC)
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS").withZone(ZoneOffset.UTC),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC),
        // Date only (midnight UTC)
        DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)
    );

    private final TokenClassifier classifier;

    public LogTokenizer(TokenClassifier classifier) {
        this.classifier = classifier;
    }

    /**
     * Split a raw log line on whitespace, classify each token, and return a ParsedLog.
     * Timestamp defaults to now if not embedded in the line.
     */
    public ParsedLog tokenize(String rawLine, String source) {
        if (rawLine == null || rawLine.isBlank()) {
            return new ParsedLog(List.of(), rawLine == null ? "" : rawLine, Instant.now(), source);
        }

        String[] parts = rawLine.trim().split("\\s+");
        List<Token> tokens = new ArrayList<>(parts.length);

        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            // Strip surrounding punctuation for classification purposes but preserve in value
            String stripped = stripPunctuation(part);
            TokenClassifier.Classification cls = classifier.classifyFull(stripped);
            tokens.add(new Token(part, cls.type(), i, cls.subtype(), stripped));
        }

        Instant timestamp = extractTimestamp(tokens);
        return new ParsedLog(tokens, rawLine, timestamp, source);
    }

    private Instant extractTimestamp(List<Token> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            String stripped = tokens.get(i).stripped();
            if (!classifier.isTimestamp(stripped)) continue;

            // Full datetime in one token (e.g. 2026-05-09T18:33:47.902Z)
            if (stripped.length() > 10) {
                Instant ts = tryParseTimestamp(stripped);
                if (ts != null) return ts;
            }

            // Date-only token — try combining with the next token as time
            // (e.g. "2026-05-19" + "16:51:29.006" or "09:56:43,968+0530")
            if (i + 1 < tokens.size()) {
                String next = tokens.get(i + 1).stripped();
                String combined = stripped + "T" + next.replace(',', '.');
                Instant ts = tryParseTimestamp(combined);
                if (ts != null) return ts;
            }

            // Date only — use midnight UTC as approximation
            Instant ts = tryParseTimestamp(stripped);
            if (ts != null) return ts;

            break; // only attempt the first timestamp-shaped token
        }
        return Instant.now();
    }

    private Instant tryParseTimestamp(String value) {
        try { return Instant.parse(value); } catch (Exception ignored) {}
        for (DateTimeFormatter fmt : TIMESTAMP_FORMATTERS) {
            try { return fmt.parse(value, Instant::from); } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * Strip leading/trailing punctuation that is not part of the token value itself
     * (e.g. quotes, brackets, commas, colons) before classification.
     */
    private String stripPunctuation(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isPunct(s.charAt(start))) start++;
        while (end > start && isPunct(s.charAt(end - 1))) end--;
        return s.substring(start, end);
    }

    private boolean isPunct(char c) {
        return c == '"' || c == '\'' || c == '(' || c == ')' ||
               c == '{' || c == '}' ||
               c == ',' || c == ';' || c == ':';
    }
}
