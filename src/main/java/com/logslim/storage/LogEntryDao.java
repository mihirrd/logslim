package com.logslim.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Repository
public class LogEntryDao {

    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {};
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NamedParameterJdbcTemplate jdbc;

    @Value("${logslim.storage.batch-insert-size:500}")
    private int batchSize;

    private final RowMapper<LogEntry> ROW_MAPPER = (rs, rowNum) -> new LogEntry(
            rs.getLong("entry_id"),
            rs.getLong("template_id"),
            Instant.ofEpochMilli(rs.getLong("log_timestamp")),
            parseJsonArray(rs.getString("parameter_values")),
            rs.getString("continuation_text")
    );

    public LogEntryDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public LogEntry insert(LogEntry entry) {
        String sql = """
                INSERT INTO log_entries (template_id, log_timestamp, parameter_values, continuation_text)
                VALUES (:templateId, :logTs, :params, :continuation)
                """;
        var p = entryToParams(entry);
        var keyHolder = new GeneratedKeyHolder();
        jdbc.update(sql, p, keyHolder, new String[]{"entry_id"});
        entry.setId(keyHolder.getKey().longValue());
        return entry;
    }

    @Transactional
    public void insertBatch(List<LogEntry> entries) {
        String sql = """
                INSERT INTO log_entries (template_id, log_timestamp, parameter_values, continuation_text)
                VALUES (:templateId, :logTs, :params, :continuation)
                """;
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

    public List<LogEntry> findByTimeRange(Instant from, Instant to) {
        String sql = """
                SELECT * FROM log_entries
                WHERE log_timestamp >= :from AND log_timestamp <= :to
                ORDER BY log_timestamp ASC, entry_id ASC
                """;
        return jdbc.query(sql,
                Map.of("from", from.toEpochMilli(), "to", to.toEpochMilli()),
                ROW_MAPPER);
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
