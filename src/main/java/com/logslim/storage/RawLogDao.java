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

    private static final RowMapper<RawLog> ROW_MAPPER = (rs, rowNum) -> new RawLog(
            rs.getLong("log_id"),
            rs.getString("content"),
            InstantUtil.parse(rs.getString("log_timestamp")),
            rs.getString("source"),
            InstantUtil.parse(rs.getString("created_at"))
    );

    public RawLogDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public RawLog insert(RawLog rawLog) {
        String sql = """
                INSERT INTO raw_logs (content, log_timestamp, source, created_at)
                VALUES (:content, :logTs, :source, :createdAt)
                RETURNING log_id
                """;
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

    public List<RawLog> findByTimeRange(Instant from, Instant to) {
        String sql = """
                SELECT * FROM raw_logs
                WHERE log_timestamp >= :from AND log_timestamp <= :to
                ORDER BY log_timestamp ASC, log_id ASC
                """;
        return jdbc.query(sql,
                Map.of("from", InstantUtil.format(from), "to", InstantUtil.format(to)),
                ROW_MAPPER);
    }
}
