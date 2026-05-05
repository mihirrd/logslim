package com.logslim.parsing;

public record Token(String value, TokenType type, int position) {

    public boolean isStatic() {
        return type == TokenType.STATIC;
    }

    public boolean isDynamic() {
        return type == TokenType.DYNAMIC;
    }
}
