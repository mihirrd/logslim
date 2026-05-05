package com.logslim.storage;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class TemplateDao {

    private final NamedParameterJdbcTemplate jdbc;

    private static final RowMapper<Template> ROW_MAPPER = (rs, rowNum) -> new Template(
            rs.getLong("template_id"),
            rs.getString("pattern"),
            rs.getLong("occurrences"),
            InstantUtil.parse(rs.getString("created_at")),
            InstantUtil.parse(rs.getString("updated_at"))
    );

    public TemplateDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Template insert(Template template) {
        String sql = """
                INSERT INTO templates (pattern, occurrences, created_at, updated_at)
                VALUES (:pattern, :occurrences, :createdAt, :updatedAt)
                """;
        var params = new MapSqlParameterSource()
                .addValue("pattern", template.getPattern())
                .addValue("occurrences", template.getOccurrences())
                .addValue("createdAt", InstantUtil.format(template.getCreatedAt()))
                .addValue("updatedAt", InstantUtil.format(template.getUpdatedAt()));

        var keyHolder = new GeneratedKeyHolder();
        jdbc.update(sql, params, keyHolder, new String[]{"template_id"});
        template.setId(keyHolder.getKey().longValue());
        return template;
    }

    public Optional<Template> findByPattern(String pattern) {
        String sql = "SELECT * FROM templates WHERE pattern = :pattern";
        List<Template> results = jdbc.query(sql, Map.of("pattern", pattern), ROW_MAPPER);
        return results.stream().findFirst();
    }

    public Optional<Template> findById(long id) {
        String sql = "SELECT * FROM templates WHERE template_id = :id";
        List<Template> results = jdbc.query(sql, Map.of("id", id), ROW_MAPPER);
        return results.stream().findFirst();
    }

    public void incrementOccurrences(long templateId) {
        String sql = """
                UPDATE templates
                SET occurrences = occurrences + 1, updated_at = :now
                WHERE template_id = :id
                """;
        jdbc.update(sql, Map.of("id", templateId, "now", InstantUtil.format(Instant.now())));
    }

    /**
     * Return the top N templates by occurrence count, optionally filtered by a time window.
     * @param since  only consider templates updated at or after this instant (null = no filter)
     * @param limit  max results
     */
    public List<Template> findTopN(Instant since, int limit) {
        String sql = since == null
                ? "SELECT * FROM templates ORDER BY occurrences DESC LIMIT :limit"
                : "SELECT * FROM templates WHERE updated_at >= :since ORDER BY occurrences DESC LIMIT :limit";

        var params = new MapSqlParameterSource()
                .addValue("limit", limit)
                .addValue("since", since == null ? null : InstantUtil.format(since));
        return jdbc.query(sql, params, ROW_MAPPER);
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM templates", Map.of(), Long.class);
        return n == null ? 0 : n;
    }
}
