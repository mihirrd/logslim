package com.logslim.storage;

import java.time.Instant;
import java.util.Map;

public class LogEntry {

    public static final String CONTINUATION_KEY = "__continuation__";

    private Long id;
    private long templateId;
    private Instant logTimestamp;
    private Map<String, String> parameters;
    private Map<String, String> metadata;
    private Instant createdAt;

    public LogEntry() {}

    public LogEntry(Long id, long templateId, Instant logTimestamp,
                    Map<String, String> parameters, Map<String, String> metadata,
                    Instant createdAt) {
        this.id = id;
        this.templateId = templateId;
        this.logTimestamp = logTimestamp;
        this.parameters = parameters;
        this.metadata = metadata;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public long getTemplateId() { return templateId; }
    public void setTemplateId(long templateId) { this.templateId = templateId; }

    public Instant getLogTimestamp() { return logTimestamp; }
    public void setLogTimestamp(Instant logTimestamp) { this.logTimestamp = logTimestamp; }

    public Map<String, String> getParameters() { return parameters; }
    public void setParameters(Map<String, String> parameters) { this.parameters = parameters; }

    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
