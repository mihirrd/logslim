package com.logslim.storage;

import java.time.Instant;
import java.util.List;

public class LogEntry {

    private Long id;
    private long templateId;
    private Instant logTimestamp;
    private List<String> parameterValues;
    private String continuationText;

    public LogEntry() {}

    public LogEntry(Long id, long templateId, Instant logTimestamp,
                    List<String> parameterValues, String continuationText) {
        this.id = id;
        this.templateId = templateId;
        this.logTimestamp = logTimestamp;
        this.parameterValues = parameterValues;
        this.continuationText = continuationText;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public long getTemplateId() { return templateId; }
    public void setTemplateId(long templateId) { this.templateId = templateId; }

    public Instant getLogTimestamp() { return logTimestamp; }
    public void setLogTimestamp(Instant logTimestamp) { this.logTimestamp = logTimestamp; }

    public List<String> getParameterValues() { return parameterValues; }
    public void setParameterValues(List<String> parameterValues) { this.parameterValues = parameterValues; }

    public String getContinuationText() { return continuationText; }
    public void setContinuationText(String continuationText) { this.continuationText = continuationText; }
}
