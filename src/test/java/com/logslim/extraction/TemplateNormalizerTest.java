package com.logslim.extraction;

import com.logslim.parsing.Token;
import com.logslim.parsing.TokenType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateNormalizerTest {

    private final TemplateNormalizer normalizer = new TemplateNormalizer();

    @Test
    void staticTokens_unchanged() {
        List<Token> tokens = List.of(
                new Token("User",   TokenType.STATIC, 0),
                new Token("failed", TokenType.STATIC, 1),
                new Token("login",  TokenType.STATIC, 2)
        );
        assertThat(normalizer.normalize(tokens)).isEqualTo("User failed login");
    }

    @Test
    void dynamicToken_replacedWithPlaceholder() {
        List<Token> tokens = List.of(
                new Token("User",  TokenType.STATIC,  0),
                new Token("123",   TokenType.DYNAMIC, 1),
                new Token("failed",TokenType.STATIC,  2),
                new Token("login", TokenType.STATIC,  3)
        );
        assertThat(normalizer.normalize(tokens)).isEqualTo("User {num} failed login");
    }

    @Test
    void uuid_replacedWithUuidPlaceholder() {
        List<Token> tokens = List.of(
                new Token("Request", TokenType.STATIC, 0),
                new Token("550e8400-e29b-41d4-a716-446655440000", TokenType.DYNAMIC, 1)
        );
        assertThat(normalizer.normalize(tokens)).isEqualTo("Request {uuid}");
    }

    @Test
    void emptyTokenList_returnsEmpty() {
        assertThat(normalizer.normalize(List.of())).isEmpty();
    }

    @Test
    void extractParameters_singleDynamic() {
        List<Token> tokens = List.of(
                new Token("User",   TokenType.STATIC,  0),
                new Token("456",    TokenType.DYNAMIC, 1),
                new Token("login",  TokenType.STATIC,  2)
        );
        Map<String, String> params = normalizer.extractParameters(tokens);
        assertThat(params).containsEntry("num", "456");
    }

    @Test
    void extractParameters_multipleDynamic_suffixed() {
        List<Token> tokens = List.of(
                new Token("10",  TokenType.DYNAMIC, 0),
                new Token("of",  TokenType.STATIC,  1),
                new Token("20",  TokenType.DYNAMIC, 2)
        );
        Map<String, String> params = normalizer.extractParameters(tokens);
        assertThat(params).containsEntry("num", "10");
        assertThat(params).containsEntry("num_1", "20");
    }

    @Test
    void twoLinesWithSamePattern_normalizeToSameString() {
        List<Token> tokens1 = List.of(
                new Token("User",   TokenType.STATIC,  0),
                new Token("123",    TokenType.DYNAMIC, 1),
                new Token("failed", TokenType.STATIC,  2),
                new Token("login",  TokenType.STATIC,  3)
        );
        List<Token> tokens2 = List.of(
                new Token("User",   TokenType.STATIC,  0),
                new Token("456",    TokenType.DYNAMIC, 1),
                new Token("failed", TokenType.STATIC,  2),
                new Token("login",  TokenType.STATIC,  3)
        );
        assertThat(normalizer.normalize(tokens1)).isEqualTo(normalizer.normalize(tokens2));
    }

    // — slotNames: canonical, de-duplicated names aligned to reconstruction —————

    @Test
    void slotNames_simplePattern() {
        assertThat(normalizer.slotNames("User {id} failed login")).containsExactly("id");
    }

    @Test
    void slotNames_dedupsRepeatedSlots() {
        assertThat(normalizer.slotNames("DB {num} rows in {num} ms")).containsExactly("num", "num_1");
    }

    @Test
    void slotNames_embeddedBraceIsNotASlot() {
        // {id} inside the path token is a literal, not a substitution point — so the only
        // slot is {status}, and index 0 must line up with parameterValues[0].
        assertThat(normalizer.slotNames("GET /api/v1/users/{id} {status}")).containsExactly("status");
    }

    // — slotBaseName ————————————————————————————————————————————————

    @Test
    void slotBaseName_stripsOnlyNumericDedupSuffix() {
        assertThat(TemplateNormalizer.slotBaseName("num_1")).isEqualTo("num");
        assertThat(TemplateNormalizer.slotBaseName("user")).isEqualTo("user");
        // a legitimately underscored name with a non-numeric tail is preserved
        assertThat(TemplateNormalizer.slotBaseName("pool_wait")).isEqualTo("pool_wait");
    }

    // — stripKeyPrefix: read-side display only ——————————————————————————

    @Test
    void stripKeyPrefix_stripsWhenKeyMatchesSlotBase() {
        assertThat(TemplateNormalizer.stripKeyPrefix("user=usr_1", "user")).isEqualTo("usr_1");
        assertThat(TemplateNormalizer.stripKeyPrefix("num=5", "num_1")).isEqualTo("5"); // dedup base
    }

    @Test
    void stripKeyPrefix_leavesUnrelatedOrBareValues() {
        assertThat(TemplateNormalizer.stripKeyPrefix("16ms", "duration")).isEqualTo("16ms");   // no '='
        assertThat(TemplateNormalizer.stripKeyPrefix("a=b", "user")).isEqualTo("a=b");          // key != base
        assertThat(TemplateNormalizer.stripKeyPrefix("=x", "x")).isEqualTo("=x");               // leading '='
        assertThat(TemplateNormalizer.stripKeyPrefix(null, "user")).isNull();
    }
}
