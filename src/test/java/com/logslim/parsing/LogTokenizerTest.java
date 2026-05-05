package com.logslim.parsing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LogTokenizerTest {

    private final LogTokenizer tokenizer = new LogTokenizer(new TokenClassifier());

    @Test
    void simpleLog_tokenizedCorrectly() {
        ParsedLog parsed = tokenizer.tokenize("User 123 failed login", "test");
        List<Token> tokens = parsed.tokens();

        assertThat(tokens).hasSize(4);
        assertThat(tokens.get(0)).isEqualTo(new Token("User",   TokenType.STATIC,  0));
        assertThat(tokens.get(1)).isEqualTo(new Token("123",    TokenType.DYNAMIC, 1));
        assertThat(tokens.get(2)).isEqualTo(new Token("failed", TokenType.STATIC,  2));
        assertThat(tokens.get(3)).isEqualTo(new Token("login",  TokenType.STATIC,  3));
    }

    @Test
    void originalContentPreserved() {
        String raw = "DB timeout on shard 7";
        ParsedLog parsed = tokenizer.tokenize(raw, "stdin");
        assertThat(parsed.originalContent()).isEqualTo(raw);
    }

    @Test
    void emptyLine_returnsEmptyTokenList() {
        ParsedLog parsed = tokenizer.tokenize("", "test");
        assertThat(parsed.tokens()).isEmpty();
    }

    @Test
    void blankLine_returnsEmptyTokenList() {
        ParsedLog parsed = tokenizer.tokenize("   ", "test");
        assertThat(parsed.tokens()).isEmpty();
    }

    @Test
    void nullLine_returnsEmptyTokenList() {
        ParsedLog parsed = tokenizer.tokenize(null, "test");
        assertThat(parsed.tokens()).isEmpty();
    }

    @Test
    void uuidInLog_classifiedDynamic() {
        ParsedLog parsed = tokenizer.tokenize(
                "Request 550e8400-e29b-41d4-a716-446655440000 started", "test");
        assertThat(parsed.tokens().get(1).type()).isEqualTo(TokenType.DYNAMIC);
    }

    @Test
    void punctuationStrippedForClassification_valuePreserved() {
        // Comma after a number should still yield DYNAMIC, and value keeps the comma
        ParsedLog parsed = tokenizer.tokenize("processed 42, items", "test");
        // "42," — stripped to "42" for classification → DYNAMIC
        assertThat(parsed.tokens().get(1).type()).isEqualTo(TokenType.DYNAMIC);
        assertThat(parsed.tokens().get(1).value()).isEqualTo("42,");
    }

    @Test
    void multipleSpaces_handledGracefully() {
        ParsedLog parsed = tokenizer.tokenize("User  123  failed", "test");
        assertThat(parsed.tokens()).hasSize(3);
    }
}
