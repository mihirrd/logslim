package com.logslim.storage;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
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
                RETURNING template_id
                """;
        var params = new MapSqlParameterSource()
                .addValue("pattern", template.getPattern())
                .addValue("occurrences", template.getOccurrences())
                .addValue("createdAt", InstantUtil.format(template.getCreatedAt()))
                .addValue("updatedAt", InstantUtil.format(template.getUpdatedAt()));

        Long id = jdbc.queryForObject(sql, params, Long.class);
        template.setId(id);
        return template;
    }

    public Optional<Template> findByPattern(String pattern) {
        String sql = "SELECT * FROM templates WHERE pattern = :pattern";
        List<Template> results = jdbc.query(sql, Map.of("pattern", pattern), ROW_MAPPER);
        return results.stream().findFirst();
    }

    @Cacheable(value = "templates", key = "#id")
    public Optional<Template> findById(long id) {
        String sql = "SELECT * FROM templates WHERE template_id = :id";
        List<Template> results = jdbc.query(sql, Map.of("id", id), ROW_MAPPER);
        return results.stream().findFirst();
    }

    /**
     * Bulk-fetch templates by id. Returns a map keyed by template_id; missing ids
     * are simply absent from the map. Returns an empty map when ids is null/empty.
     * Used by replay to avoid the N+1 lookup of `findById` per log entry.
     */
    public Map<Long, Template> findByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        String sql = "SELECT * FROM templates WHERE template_id IN (:ids)";
        List<Template> results = jdbc.query(sql,
                new MapSqlParameterSource("ids", ids), ROW_MAPPER);
        Map<Long, Template> byId = new HashMap<>(results.size());
        for (Template t : results) byId.put(t.getId(), t);
        return byId;
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

    public List<Template> findByPatternContaining(String text, int limit) {
        String sql = "SELECT * FROM templates WHERE pattern LIKE '%' || :text || '%' " +
                     "ORDER BY occurrences DESC LIMIT :limit";
        return jdbc.query(sql,
                new MapSqlParameterSource().addValue("text", text).addValue("limit", limit),
                ROW_MAPPER);
    }

    public List<Template> findAll() {
        return jdbc.query("SELECT * FROM templates ORDER BY template_id", Map.of(), ROW_MAPPER);
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM templates", Map.of(), Long.class);
        return n == null ? 0 : n;
    }
}
