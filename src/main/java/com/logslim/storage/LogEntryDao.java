package com.logslim.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Repository
public class LogEntryDao {

    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {};
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NamedParameterJdbcTemplate jdbc;
    private final WriteTarget writeTarget;

    @Value("${logslim.storage.batch-insert-size:500}")
    private int batchSize;

    private final RowMapper<LogEntry> ROW_MAPPER = (rs, rowNum) -> new LogEntry(
            rs.getLong("entry_id"),
            rs.getLong("template_id"),
            Instant.ofEpochMilli(rs.getLong("log_timestamp")),
            parseJsonArray(rs.getString("parameter_values")),
            rs.getString("continuation_text")
    );

    public LogEntryDao(NamedParameterJdbcTemplate jdbc, WriteTarget writeTarget) {
        this.jdbc = jdbc;
        this.writeTarget = writeTarget;
    }

    public LogEntry insert(LogEntry entry) {
        String sql = "INSERT INTO " + writeTarget.tableFor("log_entries") +
                " (template_id, log_timestamp, parameter_values, continuation_text) " +
                "VALUES (:templateId, :logTs, :params, :continuation) " +
                "RETURNING entry_id";
        Long id = jdbc.queryForObject(sql, entryToParams(entry), Long.class);
        entry.setId(id);
        return entry;
    }

    @Transactional
    public void insertBatch(List<LogEntry> entries) {
        String sql = "INSERT INTO " + writeTarget.tableFor("log_entries") +
                " (template_id, log_timestamp, parameter_values, continuation_text) " +
                "VALUES (:templateId, :logTs, :params, :continuation)";
        for (int i = 0; i < entries.size(); i += batchSize) {
            List<LogEntry> chunk = entries.subList(i, Math.min(i + batchSize, entries.size()));
            MapSqlParameterSource[] batchParams = chunk.stream()
                    .map(this::entryToParams)
                    .toArray(MapSqlParameterSource[]::new);
            jdbc.batchUpdate(sql, batchParams);
        }
    }

    public List<LogEntry> findByTemplateId(long templateId, int limit) {
        String sql = """
                SELECT * FROM log_entries WHERE template_id = :templateId
                ORDER BY log_timestamp ASC LIMIT :limit
                """;
        return jdbc.query(sql, Map.of("templateId", templateId, "limit", limit), ROW_MAPPER);
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM log_entries", Map.of(), Long.class);
        return n == null ? 0 : n;
    }

    public List<LogEntry> findByTimeRange(Instant from, Instant to) {
        return findByTimeRange(from, to, Integer.MAX_VALUE);
    }

    public List<LogEntry> findByTimeRange(Instant from, Instant to, int limit) {
        String sql = """
                SELECT * FROM log_entries
                WHERE log_timestamp >= :from AND log_timestamp <= :to
                ORDER BY log_timestamp ASC, entry_id ASC
                LIMIT :limit
                """;
        return jdbc.query(sql,
                Map.of("from", from.toEpochMilli(), "to", to.toEpochMilli(), "limit", limit),
                ROW_MAPPER);
    }

    public List<Map.Entry<String, Long>> getTopValuesForSlot(long templateId, int slotIndex, int topN) {
        String sql = ("SELECT json_extract_string(parameter_values, '$[%d]') AS v, COUNT(*) AS c " +
                      "FROM log_entries WHERE template_id = :tid " +
                      "AND json_extract_string(parameter_values, '$[%d]') IS NOT NULL " +
                      "GROUP BY v ORDER BY c DESC LIMIT :topN").formatted(slotIndex, slotIndex);
        return jdbc.query(sql,
                new MapSqlParameterSource().addValue("tid", templateId).addValue("topN", topN),
                (rs, i) -> new AbstractMap.SimpleEntry<>(rs.getString("v"), rs.getLong("c")));
    }

    public long countDistinctForSlot(long templateId, int slotIndex) {
        String sql = ("SELECT COUNT(DISTINCT json_extract_string(parameter_values, '$[%d]')) " +
                      "FROM log_entries WHERE template_id = :tid").formatted(slotIndex);
        Long n = jdbc.queryForObject(sql,
                new MapSqlParameterSource().addValue("tid", templateId), Long.class);
        return n == null ? 0 : n;
    }

    public Map<Long, Long> getFrequencyByTemplate(Instant from, Instant to) {
        String sql = """
                SELECT template_id, COUNT(*) as frequency
                FROM log_entries
                WHERE log_timestamp >= :from AND log_timestamp <= :to
                GROUP BY template_id
                ORDER BY frequency DESC
                """;
        List<Map.Entry<Long, Long>> results = jdbc.query(sql,
                Map.of("from", from.toEpochMilli(), "to", to.toEpochMilli()),
                (rs, i) -> new AbstractMap.SimpleEntry<>(rs.getLong("template_id"), rs.getLong("frequency")));
        Map<Long, Long> map = new java.util.LinkedHashMap<>();
        for (var entry : results) {
            map.put(entry.getKey(), entry.getValue());
        }
        return map;
    }

    public Map<Long, Long> getFrequencyComparison(Instant period1From, Instant period1To,
                                                   Instant period2From, Instant period2To) {
        Map<Long, Long> period1 = getFrequencyByTemplate(period1From, period1To);
        Map<Long, Long> period2 = getFrequencyByTemplate(period2From, period2To);

        Map<Long, Long> comparison = new java.util.LinkedHashMap<>();
        java.util.Set<Long> allTemplates = new java.util.HashSet<>();
        allTemplates.addAll(period1.keySet());
        allTemplates.addAll(period2.keySet());

        for (Long templateId : allTemplates) {
            long p1Count = period1.getOrDefault(templateId, 0L);
            long p2Count = period2.getOrDefault(templateId, 0L);
            comparison.put(templateId, p1Count); // Store observed count; service will calculate delta
        }
        return comparison;
    }

    public Map<Long, List<Map<String, Object>>> getFrequencyTrend(Instant from, Instant to, int buckets) {
        long fromMs = from.toEpochMilli();
        long toMs = to.toEpochMilli();
        long bucketWidth = (toMs - fromMs) / buckets;
        if (bucketWidth < 1) bucketWidth = 1;
        final long finalBucketWidth = bucketWidth;

        String sql = """
                SELECT
                    template_id,
                    FLOOR((log_timestamp - :from) / :bucketWidth) AS bucket_num,
                    COUNT(*) as frequency
                FROM log_entries
                WHERE log_timestamp >= :from AND log_timestamp <= :to
                GROUP BY template_id, bucket_num
                ORDER BY template_id, bucket_num
                """;

        Map<Long, List<Map<String, Object>>> trends = new java.util.LinkedHashMap<>();
        List<Map<String, Object>> rawResults = jdbc.query(sql,
                Map.of("from", fromMs, "to", toMs, "bucketWidth", finalBucketWidth),
                (rs, i) -> {
                    Map<String, Object> row = new java.util.HashMap<>();
                    row.put("templateId", rs.getLong("template_id"));
                    row.put("bucketNum", rs.getLong("bucket_num"));
                    row.put("frequency", rs.getLong("frequency"));
                    row.put("timestamp", fromMs + rs.getLong("bucket_num") * finalBucketWidth);
                    return row;
                });

        for (Map<String, Object> row : rawResults) {
            Long templateId = (Long) row.get("templateId");
            trends.computeIfAbsent(templateId, k -> new ArrayList<>()).add(row);
        }
        return trends;
    }

    private MapSqlParameterSource entryToParams(LogEntry entry) {
        return new MapSqlParameterSource()
                .addValue("templateId",   entry.getTemplateId())
                .addValue("logTs",        entry.getLogTimestamp().toEpochMilli())
                .addValue("params",       toJsonArray(entry.getParameterValues()))
                .addValue("continuation", entry.getContinuationText());
    }

    private String toJsonArray(List<String> values) {
        if (values == null || values.isEmpty()) return "[]";
        try {
            return MAPPER.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private List<String> parseJsonArray(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return MAPPER.readValue(json, LIST_TYPE);
        } catch (JsonProcessingException e) {
            return new ArrayList<>();
        }
    }
}
