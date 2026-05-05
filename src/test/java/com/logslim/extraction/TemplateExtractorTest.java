package com.logslim.extraction;

import com.logslim.storage.Template;
import com.logslim.storage.TemplateDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        // Single-connection pool so all threads share the same in-memory SQLite DB
        "spring.datasource.url=jdbc:sqlite::memory:",
        "spring.datasource.hikari.maximum-pool-size=1",
        "logslim.template.similarity-threshold=0.95",
        "logslim.template.max-count=10"
})
class TemplateExtractorTest {

    @Autowired TemplateExtractor extractor;
    @Autowired TemplateDao templateDao;
    @Autowired TemplateCache templateCache;
    @Autowired NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void cleanDb() {
        templateCache.clearAll();
        jdbc.update("DELETE FROM log_entries", Map.of());
        jdbc.update("DELETE FROM templates",   Map.of());
        jdbc.update("DELETE FROM raw_logs",    Map.of());
    }

    @Test
    void exactDuplicate_reusesSameTemplate() {
        extractor.process("User 123 failed login", "test");
        extractor.process("User 456 failed login", "test");

        assertThat(templateDao.count()).isEqualTo(1);
    }

    @Test
    void differentStaticToken_createsSeparateTemplates() {
        extractor.process("User 123 failed login",     "test");
        extractor.process("User 123 successful login", "test");

        assertThat(templateDao.count()).isEqualTo(2);
    }

    @Test
    void firstOccurrence_createsTemplate() {
        Template t = extractor.process("DB timeout on shard 7", "test");

        assertThat(t).isNotNull();
        assertThat(t.getPattern()).isEqualTo("DB timeout on shard {num}");
    }

    @Test
    void multiVarLine_normalizedCorrectly() {
        Template t = extractor.process("User 123 paid 99 dollars", "test");
        assertThat(t.getPattern()).isEqualTo("User {num} paid {num} dollars");
    }

    @Test
    void occurrencesIncrement() {
        extractor.process("User 1 failed login", "test");
        extractor.process("User 2 failed login", "test");
        extractor.process("User 3 failed login", "test");

        long count = templateDao.findTopN(null, 10).get(0).getOccurrences();
        assertThat(count).isEqualTo(3);
    }

    @Test
    void onlyStaticTokens_stillCreatesTemplate() {
        Template t = extractor.process("server started", "test");
        assertThat(t).isNotNull();
        assertThat(t.getPattern()).isEqualTo("server started");
    }

    @Test
    void uuidLine_placeholderInPattern() {
        Template t = extractor.process("Request 550e8400-e29b-41d4-a716-446655440000 received", "test");
        assertThat(t.getPattern()).isEqualTo("Request {uuid} received");
    }

    @Test
    void templateExplosionGuard_fallsBackToRaw() {
        // max-count = 10; create 10 unique templates first
        for (int i = 0; i < 10; i++) {
            extractor.process("unique" + i + " event occurred", "test");
        }
        // 11th unique template should be stored as raw log, not a new template
        Template result = extractor.process("overflow event here now", "test");
        assertThat(result).isNull();
        assertThat(templateDao.count()).isEqualTo(10);
    }
}
