package com.logslim.storage;

import java.time.Instant;

public class Template {

    private Long id;
    private String pattern;
    private long occurrences;
    private Instant createdAt;
    private Instant updatedAt;

    public Template() {}

    public Template(Long id, String pattern, long occurrences, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.pattern = pattern;
        this.occurrences = occurrences;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Template newTemplate(String pattern) {
        Instant now = Instant.now();
        return new Template(null, pattern, 1, now, now);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPattern() { return pattern; }
    public void setPattern(String pattern) { this.pattern = pattern; }

    public long getOccurrences() { return occurrences; }
    public void setOccurrences(long occurrences) { this.occurrences = occurrences; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public String toString() {
        return "Template{id=" + id + ", pattern='" + pattern + "', occurrences=" + occurrences + "}";
    }
}
