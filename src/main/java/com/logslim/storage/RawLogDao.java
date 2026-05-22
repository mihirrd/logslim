package com.logslim.storage;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;


@Repository
public class RawLogDao {

    private final NamedParameterJdbcTemplate jdbc;
    private final WriteTarget writeTarget;

    private static final RowMapper<RawLog> ROW_MAPPER = (rs, rowNum) -> new RawLog(
            rs.getLong("log_id"),
            rs.getString("content"),
            InstantUtil.parse(rs.getString("log_timestamp")),
            rs.getString("source"),
            InstantUtil.parse(rs.getString("created_at"))
    );

    public RawLogDao(NamedParameterJdbcTemplate jdbc, WriteTarget writeTarget) {
        this.jdbc = jdbc;
        this.writeTarget = writeTarget;
    }

    public RawLog insert(RawLog rawLog) {
        String sql = "INSERT INTO " + writeTarget.tableFor("raw_logs") +
                " (content, log_timestamp, source, created_at) " +
                "VALUES (:content, :logTs, :source, :createdAt) " +
                "RETURNING log_id";
        Instant now = Instant.now();
        var params = new MapSqlParameterSource()
                .addValue("content",   rawLog.getContent())
                .addValue("logTs",     InstantUtil.format(rawLog.getLogTimestamp()))
                .addValue("source",    rawLog.getSource())
                .addValue("createdAt", InstantUtil.format(rawLog.getCreatedAt() != null ? rawLog.getCreatedAt() : now));

        Long id = jdbc.queryForObject(sql, params, Long.class);
        rawLog.setId(id);
        return rawLog;
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM raw_logs", Map.of(), Long.class);
        return n == null ? 0 : n;
    }

    public List<RawLog> findByTimeRange(Instant from, Instant to) {
        return findByTimeRange(from, to, Integer.MAX_VALUE);
    }

    public List<RawLog> findByTimeRange(Instant from, Instant to, int limit) {
        return findByTimeRangeAndSearch(from, to, null, limit);
    }

    public List<RawLog> findByTimeRangeAndSearch(Instant from, Instant to, String search, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("from",  InstantUtil.format(from))
                .addValue("to",    InstantUtil.format(to))
                .addValue("limit", limit);

        String sql;
        if (search != null && !search.isBlank()) {
            sql = """
                    SELECT * FROM raw_logs
                    WHERE log_timestamp >= :from AND log_timestamp <= :to
                    AND content LIKE '%' || :search || '%'
                    ORDER BY log_timestamp ASC, log_id ASC
                    LIMIT :limit
                    """;
            params.addValue("search", search);
        } else {
            sql = """
                    SELECT * FROM raw_logs
                    WHERE log_timestamp >= :from AND log_timestamp <= :to
                    ORDER BY log_timestamp ASC, log_id ASC
                    LIMIT :limit
                    """;
        }
        return jdbc.query(sql, params, ROW_MAPPER);
    }
}
