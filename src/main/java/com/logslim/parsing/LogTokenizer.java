package com.logslim.parsing;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class LogTokenizer {

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
            TokenType type = classifier.classify(stripped);
            tokens.add(new Token(part, type, i));
        }

        return new ParsedLog(tokens, rawLine, Instant.now(), source);
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
