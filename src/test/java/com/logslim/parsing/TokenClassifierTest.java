package com.logslim.parsing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenClassifierTest {

    private final TokenClassifier classifier = new TokenClassifier();

    @Test
    void plainWord_isStatic() {
        assertThat(classifier.classify("User")).isEqualTo(TokenType.STATIC);
        assertThat(classifier.classify("failed")).isEqualTo(TokenType.STATIC);
        assertThat(classifier.classify("login")).isEqualTo(TokenType.STATIC);
    }

    @Test
    void integer_isDynamic() {
        assertThat(classifier.classify("123")).isEqualTo(TokenType.DYNAMIC);
        assertThat(classifier.classify("0")).isEqualTo(TokenType.DYNAMIC);
        assertThat(classifier.classify("-42")).isEqualTo(TokenType.DYNAMIC);
    }

    @Test
    void decimal_isDynamic() {
        assertThat(classifier.classify("3.14")).isEqualTo(TokenType.DYNAMIC);
        assertThat(classifier.classify("0.001")).isEqualTo(TokenType.DYNAMIC);
    }

    @Test
    void uuid_isDynamic() {
        assertThat(classifier.classify("550e8400-e29b-41d4-a716-446655440000"))
                .isEqualTo(TokenType.DYNAMIC);
    }

    @Test
    void hexHash_isDynamic() {
        assertThat(classifier.classify("d41d8cd98f00b204e9800998ecf8427e"))
                .isEqualTo(TokenType.DYNAMIC);
    }

    @Test
    void isoTimestamp_isDynamic() {
        assertThat(classifier.classify("2024-01-15T10:30:00Z")).isEqualTo(TokenType.DYNAMIC);
        assertThat(classifier.classify("2024-01-15")).isEqualTo(TokenType.DYNAMIC);
    }

    @Test
    void empty_isStatic() {
        assertThat(classifier.classify("")).isEqualTo(TokenType.STATIC);
        assertThat(classifier.classify(null)).isEqualTo(TokenType.STATIC);
    }

    @Test
    void mixedAlphanumeric_isStatic() {
        // e.g. "shard7" — a label, not a pure number
        assertThat(classifier.classify("shard7")).isEqualTo(TokenType.STATIC);
        assertThat(classifier.classify("v1alpha1")).isEqualTo(TokenType.STATIC);
    }
}
