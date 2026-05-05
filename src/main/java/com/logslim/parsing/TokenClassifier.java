package com.logslim.parsing;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class TokenClassifier {

    // UUID: 8-4-4-4-12 hex groups
    private static final Pattern UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    // Hex hash: 32+ hex chars without dashes
    private static final Pattern HEX_HASH =
            Pattern.compile("[0-9a-fA-F]{32,}");

    // ISO-ish timestamp: starts with 4 digits + dash
    private static final Pattern TIMESTAMP =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}.*");

    // Pure number (integer or decimal, optionally signed)
    private static final Pattern NUMBER =
            Pattern.compile("-?\\d+(\\.\\d+)?");

    public TokenType classify(String value) {
        if (value == null || value.isEmpty()) {
            return TokenType.STATIC;
        }
        if (UUID.matcher(value).matches()) return TokenType.DYNAMIC;
        if (TIMESTAMP.matcher(value).matches()) return TokenType.DYNAMIC;
        if (HEX_HASH.matcher(value).matches()) return TokenType.DYNAMIC;
        if (NUMBER.matcher(value).matches()) return TokenType.DYNAMIC;
        return TokenType.STATIC;
    }
}
