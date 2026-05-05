package com.logslim.storage;

import java.time.Instant;

public class RawLog {

    private Long id;
    private String content;
    private Instant logTimestamp;
    private String source;
    private Instant createdAt;

    public RawLog() {}

    public RawLog(Long id, String content, Instant logTimestamp, String source, Instant createdAt) {
        this.id = id;
        this.content = content;
        this.logTimestamp = logTimestamp;
        this.source = source;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Instant getLogTimestamp() { return logTimestamp; }
    public void setLogTimestamp(Instant logTimestamp) { this.logTimestamp = logTimestamp; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
