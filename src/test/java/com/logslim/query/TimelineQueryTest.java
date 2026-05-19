package com.logslim.query;

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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:duckdb:",
        "spring.datasource.hikari.maximum-pool-size=1"
})
class TimelineQueryTest {

    @Autowired TemplateDao templateDao;
    @Autowired LogEntryDao logEntryDao;
    @Autowired TemplateQueryService queryService;
    @Autowired NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void cleanDb() {
        jdbc.update("DELETE FROM log_entries", Map.of());
        jdbc.update("DELETE FROM templates", Map.of());
    }

    @Test
    void frequencyByTemplate_countsCorrectly() {
        Instant now = Instant.now();
        Instant oneHourAgo = now.minus(1, ChronoUnit.HOURS);

        // Create 2 templates
        Template t1 = new Template();
        t1.setPattern("Error in service A");
        t1.setCreatedAt(oneHourAgo);
        t1.setUpdatedAt(oneHourAgo);
        t1 = templateDao.insert(t1);

        Template t2 = new Template();
        t2.setPattern("Error in service B");
        t2.setCreatedAt(oneHourAgo);
        t2.setUpdatedAt(oneHourAgo);
        t2 = templateDao.insert(t2);

        // Add 3 entries for t1, 2 entries for t2 in the past hour
        for (int i = 0; i < 3; i++) {
            logEntryDao.insert(new LogEntry(0L, t1.getId(), now.minus(30 + i, ChronoUnit.MINUTES), new ArrayList<>(), null));
        }
        for (int i = 0; i < 2; i++) {
            logEntryDao.insert(new LogEntry(0L, t2.getId(), now.minus(20 + i, ChronoUnit.MINUTES), new ArrayList<>(), null));
        }

        // Query frequency for the past hour
        Map<Long, Long> frequencies = logEntryDao.getFrequencyByTemplate(oneHourAgo, now);

        assertThat(frequencies.get(t1.getId())).isGreaterThanOrEqualTo(3L);
        assertThat(frequencies.get(t2.getId())).isGreaterThanOrEqualTo(2L);
        assertThat(frequencies).hasSize(2);
    }

    @Test
    void frequencyComparison_calculatesPercentChange() {
        Instant now = Instant.now();
        Instant twoHoursAgo = now.minus(2, ChronoUnit.HOURS);
        Instant oneHourAgo = now.minus(1, ChronoUnit.HOURS);

        // Create template
        Template t = new Template();
        t.setPattern("Error: {message}");
        t.setCreatedAt(twoHoursAgo);
        t.setUpdatedAt(now);
        t = templateDao.insert(t);

        // Add entries in first hour (2h ago to 1h ago)
        for (int i = 0; i < 50; i++) {
            logEntryDao.insert(new LogEntry(0L, t.getId(), twoHoursAgo.plus(i * 60L, ChronoUnit.SECONDS), List.of("error"), null));
        }

        // Add entries in second hour (1h ago to now)
        for (int i = 0; i < 150; i++) {
            logEntryDao.insert(new LogEntry(0L, t.getId(), oneHourAgo.plus(i * 24L, ChronoUnit.SECONDS), List.of("error"), null));
        }

        // Compare frequencies: observed (recent 1h) vs baseline (1h before that)
        List<FrequencyDelta> deltas = queryService.compareFrequencies(
                java.time.Duration.ofHours(1),
                java.time.Duration.ofHours(1),
                50);

        assertThat(deltas).hasSize(1);
        FrequencyDelta delta = deltas.get(0);
        assertThat(delta.template().getId()).isEqualTo(t.getId());
        assertThat(delta.observedCount()).isGreaterThan(delta.baselineCount());
        assertThat(delta.changePercent()).isGreaterThan(100.0);  // At least 100% increase
        assertThat(delta.isNew()).isFalse();
        assertThat(delta.disappeared()).isFalse();
    }

    @Test
    void frequencyComparison_marksNewTemplates() {
        Instant now = Instant.now();
        Instant twoHoursAgo = now.minus(2, ChronoUnit.HOURS);
        Instant oneHourAgo = now.minus(1, ChronoUnit.HOURS);

        // Create template that only has entries in the recent hour
        Template t = new Template();
        t.setPattern("New error pattern");
        t.setCreatedAt(oneHourAgo.plus(1, ChronoUnit.MINUTES));
        t.setUpdatedAt(now);
        t = templateDao.insert(t);

        // Add entries only in past hour (no entries in baseline period)
        // Add well after oneHourAgo to avoid edge cases
        for (int i = 0; i < 20; i++) {
            logEntryDao.insert(new LogEntry(0L, t.getId(), oneHourAgo.plus(10 + i * 180L, ChronoUnit.SECONDS), new ArrayList<>(), null));
        }

        // Compare
        List<FrequencyDelta> deltas = queryService.compareFrequencies(
                java.time.Duration.ofHours(1),
                java.time.Duration.ofHours(1),
                50);

        assertThat(deltas).hasSize(1);
        FrequencyDelta delta = deltas.get(0);
        assertThat(delta.isNew()).isTrue();
        assertThat(delta.observedCount()).isGreaterThan(0);
        assertThat(delta.baselineCount()).isEqualTo(0);
    }

    @Test
    void frequencyComparison_marksDisappearedTemplates() {
        Instant now = Instant.now();
        Instant oneHourAgo = now.minus(1, ChronoUnit.HOURS);
        Instant twoHoursAgo = now.minus(2, ChronoUnit.HOURS);

        Template t = new Template();
        t.setPattern("Old error pattern");
        t.setCreatedAt(twoHoursAgo);
        t.setUpdatedAt(oneHourAgo);
        t = templateDao.insert(t);

        // Add entries only in baseline period (first hour)
        for (int i = 0; i < 20; i++) {
            logEntryDao.insert(new LogEntry(0L, t.getId(), twoHoursAgo.plus(i * 180L, ChronoUnit.SECONDS), new ArrayList<>(), null));
        }

        // Compare
        List<FrequencyDelta> deltas = queryService.compareFrequencies(
                java.time.Duration.ofHours(1),
                java.time.Duration.ofHours(1),
                50);

        assertThat(deltas).hasSize(1);
        FrequencyDelta delta = deltas.get(0);
        assertThat(delta.disappeared()).isTrue();
        assertThat(delta.observedCount()).isEqualTo(0);
        assertThat(delta.baselineCount()).isGreaterThan(0);
    }

    @Test
    void frequencyTrend_bucketsCorrectly() {
        Instant now = Instant.now();
        Instant fourHoursAgo = now.minus(4, ChronoUnit.HOURS);

        Template t = new Template();
        t.setPattern("Request completed");
        t.setCreatedAt(fourHoursAgo);
        t.setUpdatedAt(now);
        t = templateDao.insert(t);

        // Add entries distributed across 4 hours
        for (int h = 0; h < 4; h++) {
            int count = (h + 1) * 5;
            for (int i = 0; i < count; i++) {
                logEntryDao.insert(new LogEntry(0L, t.getId(), fourHoursAgo.plus((h * 60 + i), ChronoUnit.MINUTES), new ArrayList<>(), null));
            }
        }

        // Get trend with 4 buckets (one per hour)
        Map<Long, List<FrequencyBucket>> trends = queryService.getFrequencyTimeseries(
                java.time.Duration.ofHours(4), 4);

        assertThat(trends).containsKey(t.getId());
        List<FrequencyBucket> buckets = trends.get(t.getId());
        assertThat(buckets).hasSize(4);

        // Verify frequencies are increasing
        for (int i = 1; i < buckets.size(); i++) {
            assertThat(buckets.get(i).frequency()).isGreaterThanOrEqualTo(buckets.get(i-1).frequency());
        }

        // Verify % changes exist for non-first buckets
        assertThat(buckets.get(0).changeFromPrevious()).isNull();
        for (int i = 1; i < buckets.size(); i++) {
            assertThat(buckets.get(i).changeFromPrevious()).isNotNull();
        }
    }

    @Test
    void compareFrequencies_sortsByAbsoluteChangeDescending() {
        Instant now = Instant.now();
        Instant oneHourAgo = now.minus(1, ChronoUnit.HOURS);
        Instant twoHoursAgo = now.minus(2, ChronoUnit.HOURS);

        // Create 3 templates with different frequency changes
        Template t1 = new Template();
        t1.setPattern("Template 1");
        t1.setCreatedAt(twoHoursAgo);
        t1.setUpdatedAt(now);
        t1 = templateDao.insert(t1);

        Template t2 = new Template();
        t2.setPattern("Template 2");
        t2.setCreatedAt(twoHoursAgo);
        t2.setUpdatedAt(now);
        t2 = templateDao.insert(t2);

        Template t3 = new Template();
        t3.setPattern("Template 3");
        t3.setCreatedAt(twoHoursAgo);
        t3.setUpdatedAt(now);
        t3 = templateDao.insert(t3);

        // t1: baseline=50, observed=25 (-50%)
        for (int i = 0; i < 50; i++) {
            logEntryDao.insert(new LogEntry(0L, t1.getId(), twoHoursAgo.plus(i * 72L, ChronoUnit.SECONDS), new ArrayList<>(), null));
        }
        for (int i = 0; i < 25; i++) {
            logEntryDao.insert(new LogEntry(0L, t1.getId(), oneHourAgo.plus(i * 144L, ChronoUnit.SECONDS), new ArrayList<>(), null));
        }

        // t2: baseline=50, observed=175 (+250%)
        for (int i = 0; i < 50; i++) {
            logEntryDao.insert(new LogEntry(0L, t2.getId(), twoHoursAgo.plus(i * 72L, ChronoUnit.SECONDS), new ArrayList<>(), null));
        }
        for (int i = 0; i < 175; i++) {
            logEntryDao.insert(new LogEntry(0L, t2.getId(), oneHourAgo.plus(i * 20L, ChronoUnit.SECONDS), new ArrayList<>(), null));
        }

        // t3: baseline=50, observed=65 (+30%)
        for (int i = 0; i < 50; i++) {
            logEntryDao.insert(new LogEntry(0L, t3.getId(), twoHoursAgo.plus(i * 72L, ChronoUnit.SECONDS), new ArrayList<>(), null));
        }
        for (int i = 0; i < 65; i++) {
            logEntryDao.insert(new LogEntry(0L, t3.getId(), oneHourAgo.plus(i * 55L, ChronoUnit.SECONDS), new ArrayList<>(), null));
        }

        // Compare
        List<FrequencyDelta> deltas = queryService.compareFrequencies(
                java.time.Duration.ofHours(1),
                java.time.Duration.ofHours(1),
                50);

        // Should have all 3 templates and be sorted by absolute % change
        assertThat(deltas).hasSize(3);
        // t2 should have highest absolute change
        assertThat(deltas.get(0).template().getId()).isEqualTo(t2.getId());
    }
}
