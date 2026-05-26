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

    // IPv4 address  (e.g. 192.168.1.144)
    private static final Pattern IPV4 =
            Pattern.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");

    // process[PID] or process[PID]:  (e.g. docker[25559], sshd[1234]:)
    private static final Pattern PROC_PID =
            Pattern.compile("[A-Za-z][\\w.-]*\\[\\d+\\]:?");

    public record Classification(TokenType type, TokenSubtype subtype) {
        static final Classification STATIC = new Classification(TokenType.STATIC, TokenSubtype.NONE);
    }

    public boolean isTimestamp(String stripped) {
        return TIMESTAMP.matcher(stripped).matches();
    }

    /**
     * Single-pass classification: returns both type and subtype without running
     * each regex twice. Call this instead of classify() + classifySubtype().
     */
    public Classification classifyFull(String value) {
        if (value == null || value.isEmpty()) return Classification.STATIC;
        if (UUID.matcher(value).matches())      return new Classification(TokenType.DYNAMIC, TokenSubtype.UUID);
        if (TIMESTAMP.matcher(value).matches()) return new Classification(TokenType.DYNAMIC, TokenSubtype.TS);
        if (TIME.matcher(value).matches())      return new Classification(TokenType.DYNAMIC, TokenSubtype.TIME);
        if (HEX_HASH.matcher(value).matches())  return new Classification(TokenType.DYNAMIC, TokenSubtype.HASH);
        if (NUMBER.matcher(value).matches())    return new Classification(TokenType.DYNAMIC, TokenSubtype.NUM);
        if (IPV4.matcher(value).matches())      return new Classification(TokenType.DYNAMIC, TokenSubtype.IP);
        if (PROC_PID.matcher(value).matches())  return new Classification(TokenType.DYNAMIC, TokenSubtype.PROC);
        int eq = value.indexOf('=');
        if (eq > 0 && eq < value.length() - 1) {
            Classification inner = classifyFull(value.substring(eq + 1));
            if (inner.type() == TokenType.DYNAMIC) {
                return new Classification(TokenType.DYNAMIC, TokenSubtype.KV);
            }
        }
        return Classification.STATIC;
    }

    public TokenType classify(String value) {
        return classifyFull(value).type();
    }

    /**
     * Classify the dynamic subtype of an already-stripped token value.
     * Returns {@link TokenSubtype#NONE} for static tokens.
     */
    public TokenSubtype classifySubtype(String value) {
        return classifyFull(value).subtype();
    }
}
