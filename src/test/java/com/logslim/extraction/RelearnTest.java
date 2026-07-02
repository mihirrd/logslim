package com.logslim.extraction;

import com.logslim.ingestion.LogGroup;
import com.logslim.reconstruction.LogReconstructor;
import com.logslim.storage.LogEntry;
import com.logslim.storage.LogEntryDao;
import com.logslim.storage.Template;
import com.logslim.storage.TemplateDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The streaming (Kafka) learning cycle: ingest batches fragment on unknown ID
 * species, {@link TemplateExtractor#relearn()} folds them into merged {var}
 * templates, and subsequent batches resolve against the learned state.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:duckdb:",
        "spring.datasource.hikari.maximum-pool-size=1",
        "logslim.parser.max-branching=4"
})
class RelearnTest {

    @Autowired TemplateExtractor extractor;
    @Autowired TemplateDao templateDao;
    @Autowired LogEntryDao logEntryDao;
    @Autowired LogReconstructor reconstructor;
    @Autowired NamedParameterJdbcTemplate jdbc;

    private static final Instant TS = Instant.parse("2026-07-02T10:00:00Z");

    @BeforeEach
    void cleanDb() {
        extractor.reset();
        jdbc.update("DELETE FROM log_entries", Map.of());
        jdbc.update("DELETE FROM templates",   Map.of());
        jdbc.update("DELETE FROM raw_logs",    Map.of());
    }

    private List<String> fragmentingLines() {
        // 6 distinct id-like tokens (> max-branching=4) in an otherwise identical line
        return List.of(
                "job j-a7x finished ok",
                "job j-b7x finished ok",
                "job j-c7x finished ok",
                "job j-d7x finished ok",
                "job j-e7x finished ok",
                "job j-f7x finished ok");
    }

    private void ingest(List<String> lines) {
        extractor.processBatch(lines.stream()
                .map(l -> LogGroup.singleLine(l, "kafka-test", TS)).toList());
    }

    @Test
    void relearnFoldsFragmentsAndRewritesEntries() {
        ingest(fragmentingLines());
        assertThat(templateDao.count()).isEqualTo(6); // fragmented, one per id

        TemplateExtractor.RelearnResult r = extractor.relearn();

        assertThat(r.mergedTemplates()).isEqualTo(1);
        assertThat(r.folded()).isEqualTo(6);
        assertThat(r.remappedForward()).isZero();

        List<Template> remaining = templateDao.findAll();
        assertThat(remaining).hasSize(1);
        Template merged = remaining.get(0);
        assertThat(merged.getPattern()).isEqualTo("job {var} finished ok");
        assertThat(merged.getOccurrences()).isEqualTo(6);

        // Every entry re-pointed at the merged template, params re-planned,
        // and the original line still reconstructs byte-exactly.
        List<LogEntry> entries = logEntryDao.findByTemplateId(merged.getId(), 100);
        assertThat(entries).hasSize(6);
        assertThat(entries.stream()
                .map(e -> reconstructor.reconstruct(merged.getPattern(), e.getParameterValues())))
                .containsExactlyInAnyOrderElementsOf(fragmentingLines());
    }

    @Test
    void nextBatchResolvesAgainstLearnedTemplate() {
        ingest(fragmentingLines());
        extractor.relearn();

        // New batch with NEVER-SEEN ids: must fold into the merged template, not fragment.
        ingest(List.of("job j-zz9 finished ok", "job j-qq2 finished ok"));

        assertThat(templateDao.count()).isEqualTo(1);
        Template merged = templateDao.findAll().get(0);
        List<LogEntry> entries = logEntryDao.findByTemplateId(merged.getId(), 100);
        assertThat(entries).hasSize(8);
        assertThat(reconstructor.reconstruct(merged.getPattern(),
                entries.get(entries.size() - 1).getParameterValues()))
                .isEqualTo("job j-qq2 finished ok");
    }

    @Test
    void relearnIsIdempotentAndMonotone() {
        ingest(fragmentingLines());
        extractor.relearn();
        TemplateExtractor.RelearnResult second = extractor.relearn();

        assertThat(second.changedAnything()).isFalse();
        assertThat(templateDao.count()).isEqualTo(1);
    }

    @Test
    void semanticVariationSurvivesRelearn() {
        ingest(List.of(
                "backup finished ok",
                "backup finished FAILED"));
        TemplateExtractor.RelearnResult r = extractor.relearn();

        assertThat(r.changedAnything()).isFalse();
        assertThat(templateDao.count()).isEqualTo(2); // succeeded/failed is signal, never {var}
    }

    @Test
    void bootstrapRemapsIdentityFragmentsAgainstLearnedTemplates() {
        // Simulate the fold-forward situation: a merged template exists in the DB
        // alongside a leftover identity fragment (as after a fold whose entries
        // were already archived).
        templateDao.insert(Template.newTemplate("job {var} finished ok"));
        templateDao.insert(Template.newTemplate("job j-old7 finished ok"));

        // Restart: rebuild in-memory state from the DB alone.
        extractor.reset();
        extractor.bootstrap();

        // A line matching the leftover fragment's pattern must land in the merged
        // template, not resurrect the fragment.
        ingest(List.of("job j-old7 finished ok"));
        Template merged = templateDao.findAll().stream()
                .filter(t -> t.getPattern().contains("{var}")).findFirst().orElseThrow();
        assertThat(logEntryDao.findByTemplateId(merged.getId(), 10)).hasSize(1);

        // And the fragment gained no entries.
        Template fragment = templateDao.findAll().stream()
                .filter(t -> !t.getPattern().contains("{var}")).findFirst().orElseThrow();
        assertThat(logEntryDao.findByTemplateId(fragment.getId(), 10)).isEmpty();
    }
}
