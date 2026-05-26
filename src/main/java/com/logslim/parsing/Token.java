package com.logslim.parsing;

import java.util.Objects;

public record Token(String value, TokenType type, int position, TokenSubtype subtype, String stripped) {

    /** Convenience constructor used in tests and non-hot-path code. */
    public Token(String value, TokenType type, int position) {
        this(value, type, position, TokenSubtype.NONE, value);
    }

    public boolean isStatic() {
        return type == TokenType.STATIC;
    }

    public boolean isDynamic() {
        return type == TokenType.DYNAMIC;
    }

    /** Equality and hash based on identity fields only — subtype/stripped are cache-only. */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Token other)) return false;
        return position == other.position
                && type == other.type
                && Objects.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, type, position);
    }

    @Override
    public String toString() {
        return "Token[value=" + value + ", type=" + type + ", position=" + position + "]";
    }
}
