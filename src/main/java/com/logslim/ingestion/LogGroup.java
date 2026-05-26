package com.logslim.ingestion;

import java.time.Instant;
import java.util.List;

public record LogGroup(String headerLine, List<String> continuationLines, String source,
                       Instant sourceTimestamp) {

    public boolean isMultiLine() {
        return continuationLines != null && !continuationLines.isEmpty();
    }

    public static LogGroup singleLine(String line, String source) {
        return new LogGroup(line, List.of(), source, null);
    }

    public static LogGroup singleLine(String line, String source, Instant sourceTimestamp) {
        return new LogGroup(line, List.of(), source, sourceTimestamp);
    }
}
