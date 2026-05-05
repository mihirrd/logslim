package com.logslim.ingestion;

import java.util.List;

public record LogGroup(String headerLine, List<String> continuationLines, String source) {

    public boolean isMultiLine() {
        return continuationLines != null && !continuationLines.isEmpty();
    }

    public static LogGroup singleLine(String line, String source) {
        return new LogGroup(line, List.of(), source);
    }
}
