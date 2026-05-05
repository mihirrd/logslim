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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class LogEntryDao {

    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {};
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NamedParameterJdbcTemplate jdbc;

    @Value("${logslim.storage.batch-insert-size:500}")
    private int batchSize;

    private final RowMapper<LogEntry> ROW_MAPPER = (rs, rowNum) -> {
        Map<String, String> params = parseJson(rs.getString("parameter_json"));
        Map<String, String> meta   = parseJson(rs.getString("metadata_json"));
        return new LogEntry(
                rs.getLong("entry_id"),
                rs.getLong("template_id"),
                InstantUtil.parse(rs.getString("log_timestamp")),
                params,
                meta,
                InstantUtil.parse(rs.getString("created_at"))
        );
    };

    public LogEntryDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public LogEntry insert(LogEntry entry) {
        String sql = """
                INSERT INTO log_entries (template_id, log_timestamp, parameter_json, metadata_json, created_at)
                VALUES (:templateId, :logTs, :params, :meta, :createdAt)
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
                INSERT INTO log_entries (template_id, log_timestamp, parameter_json, metadata_json, created_at)
                VALUES (:templateId, :logTs, :params, :meta, :createdAt)
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
                Map.of("from", InstantUtil.format(from), "to", InstantUtil.format(to)),
                ROW_MAPPER);
    }

    /**
     * Find entries for a template where a specific parameter matches a value.
     */
    public List<LogEntry> findByParameterValue(long templateId, String key, String value) {
        String sql = """
                SELECT * FROM log_entries
                WHERE template_id = :templateId
                  AND json_extract(parameter_json, '$.' || :key) = :value
                ORDER BY log_timestamp ASC, entry_id ASC
                """;
        return jdbc.query(sql,
                Map.of("templateId", templateId, "key", key, "value", value),
                ROW_MAPPER);
    }

    private MapSqlParameterSource entryToParams(LogEntry entry) {
        Instant now = Instant.now();
        return new MapSqlParameterSource()
                .addValue("templateId", entry.getTemplateId())
                .addValue("logTs", InstantUtil.format(entry.getLogTimestamp()))
                .addValue("params", toJson(entry.getParameters()))
                .addValue("meta",   toJson(entry.getMetadata()))
                .addValue("createdAt", InstantUtil.format(entry.getCreatedAt() != null ? entry.getCreatedAt() : now));
    }

    private String toJson(Map<String, String> map) {
        if (map == null || map.isEmpty()) return "{}";
        try {
            return MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private Map<String, String> parseJson(String json) {
        if (json == null || json.isBlank()) return new HashMap<>();
        try {
            return MAPPER.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException e) {
            return new HashMap<>();
        }
    }
}
