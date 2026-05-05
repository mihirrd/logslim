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

    // ISO date or datetime: starts with 4-digit year + dash  (e.g. 2026-05-04)
    private static final Pattern TIMESTAMP =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}.*");

    // Wall-clock time: HH:MM:SS or H:MM:SS, optional fractional seconds  (e.g. 06:21:06, 6:21:06.123)
    private static final Pattern TIME =
            Pattern.compile("\\d{1,2}:\\d{2}:\\d{2}(\\.\\d+)?");

    // Number: optional sign, digits, optional decimal, optional % suffix  (e.g. 42, -3.14, 38%)
    private static final Pattern NUMBER =
            Pattern.compile("-?\\d+(\\.\\d+)?%?");

    public TokenType classify(String value) {
        if (value == null || value.isEmpty()) {
            return TokenType.STATIC;
        }
        if (UUID.matcher(value).matches())      return TokenType.DYNAMIC;
        if (TIMESTAMP.matcher(value).matches()) return TokenType.DYNAMIC;
        if (TIME.matcher(value).matches())      return TokenType.DYNAMIC;
        if (HEX_HASH.matcher(value).matches())  return TokenType.DYNAMIC;
        if (NUMBER.matcher(value).matches())    return TokenType.DYNAMIC;

        // key=value token (e.g. user_id=64, product_id=abc123): classify by the value part
        int eq = value.indexOf('=');
        if (eq > 0 && eq < value.length() - 1) {
            return classify(value.substring(eq + 1));
        }

        return TokenType.STATIC;
    }
}
