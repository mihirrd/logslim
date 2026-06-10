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

    /**
     * Numeric aggregate for a slot, or {@code null} if its values aren't numeric.
     * Strips a leading {@code key=} and a trailing unit ({@code ms}, {@code s}, {@code %}, …)
     * before casting, so {@code duration=16ms} → 16 while {@code user=usr_00337} stays
     * non-numeric (won't be counted). Returns [count, min, max, avg, p50, p95].
     */
    public double[] numericSummaryForSlot(long templateId, int slotIndex) {
        String numExpr = ("TRY_CAST(regexp_replace(regexp_replace(" +
                "json_extract_string(parameter_values, '$[%d]'), '^.*=', ''), " +
                "'[a-zA-Z%%]+$', '') AS DOUBLE)").formatted(slotIndex);
        String sql = "SELECT COUNT(num) AS n, MIN(num) AS mn, MAX(num) AS mx, AVG(num) AS av, " +
                "quantile_cont(num, 0.5) AS p50, quantile_cont(num, 0.95) AS p95 " +
                "FROM (SELECT " + numExpr + " AS num FROM log_entries WHERE template_id = :tid) t " +
                "WHERE num IS NOT NULL";
        return jdbc.query(sql,
                new MapSqlParameterSource().addValue("tid", templateId),
                (org.springframework.jdbc.core.ResultSetExtractor<double[]>) rs -> {
                    if (!rs.next()) return null;
                    long n = rs.getLong("n");
                    if (n == 0) return null;
                    return new double[]{ n, rs.getDouble("mn"), rs.getDouble("mx"),
                            rs.getDouble("av"), rs.getDouble("p50"), rs.getDouble("p95") };
                });
    }

    public long countDistinctForSlot(long templateId, int slotIndex) {
        String sql = ("SELECT COUNT(DISTINCT json_extract_string(parameter_values, '$[%d]')) " +
                      "FROM log_entries WHERE template_id = :tid").formatted(slotIndex);
        Long n = jdbc.queryForObject(sql,
                new MapSqlParameterSource().addValue("tid", templateId), Long.class);
        return n == null ? 0 : n;
    }

    public Map<Long, Long> countByTemplateInRange(Instant from, Instant to) {
        String sql = """
                SELECT template_id, COUNT(*) AS cnt
                FROM log_entries
                WHERE log_timestamp >= :from AND log_timestamp <= :to
                GROUP BY template_id
                """;
        Map<Long, Long> result = new java.util.HashMap<>();
        jdbc.query(sql,
                Map.of("from", from.toEpochMilli(), "to", to.toEpochMilli()),
                (org.springframework.jdbc.core.RowCallbackHandler)
                rs -> result.put(rs.getLong("template_id"), rs.getLong("cnt")));
        return result;
    }

    /**
     * Earliest log-event timestamp seen for each template, across all history.
     * Keyed by template id, value is epoch millis. Used to detect templates whose
     * very first occurrence falls inside an investigation window (event-time based,
     * independent of when the row was ingested).
     */
    public Map<Long, Long> firstSeenByTemplate() {
        String sql = """
                SELECT template_id, MIN(log_timestamp) AS first_seen
                FROM log_entries
                GROUP BY template_id
                """;
        Map<Long, Long> result = new java.util.HashMap<>();
        jdbc.query(sql, Map.of(),
                (org.springframework.jdbc.core.RowCallbackHandler)
                rs -> result.put(rs.getLong("template_id"), rs.getLong("first_seen")));
        return result;
    }

    public List<LogEntry> findByTemplateIdAndTimeRange(long templateId, Instant from, Instant to) {
        String sql = """
                SELECT * FROM log_entries
                WHERE template_id = :tid
                AND log_timestamp >= :from AND log_timestamp <= :to
                ORDER BY log_timestamp ASC
                """;
        return jdbc.query(sql,
                new MapSqlParameterSource()
                        .addValue("tid",  templateId)
                        .addValue("from", from.toEpochMilli())
                        .addValue("to",   to.toEpochMilli()),
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
