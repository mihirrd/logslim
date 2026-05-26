package com.logslim.ingestion;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;

public class MultiLineGrouper implements Iterable<LogGroup> {

    // Matches a leading ISO-style timestamp, e.g. "2026-05-19 16:51:29.006" or "2026-05-19T16:51:29".
    private static final Pattern TIMESTAMP_PREFIX =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}");

    private final BufferedReader reader;
    private final String source;

    public MultiLineGrouper(BufferedReader reader, String source) {
        this.reader = reader;
        this.source = source;
    }

    @Override
    public Iterator<LogGroup> iterator() {
        return new GroupIterator();
    }

    static boolean startsWithTimestamp(String line) {
        if (line == null || line.isEmpty()) return false;
        return TIMESTAMP_PREFIX.matcher(line).find();
    }

    static boolean isContinuation(String line) {
        if (line == null || line.isEmpty()) return false;
        char first = line.charAt(0);
        if (first == '\t') return true;
        if (first == ' ') {
            int spaces = 0;
            for (int i = 0; i < line.length(); i++) {
                if (line.charAt(i) == ' ') spaces++; else break;
            }
            return spaces >= 4;
        }
        if (line.startsWith("Caused by:")) return true;
        if (line.matches("\\.\\.\\.\\s+\\d+\\s+more.*")) return true;
        return false;
    }

    private class GroupIterator implements Iterator<LogGroup> {

        private String bufferedLine = null;
        private boolean done = false;
        private boolean started = false;

        @Override
        public boolean hasNext() {
            if (done) return false;
            if (!started) {
                advance();
                started = true;
            }
            return !done;
        }

        @Override
        public LogGroup next() {
            if (!hasNext()) throw new NoSuchElementException();

            String header = bufferedLine;
            boolean tsAnchored = startsWithTimestamp(header);
            List<String> continuations = new ArrayList<>();
            bufferedLine = null;

            while (true) {
                String line = readLine();
                if (line == null) {
                    done = true;
                    break;
                }
                if (line.isBlank()) {
                    advance();
                    break;
                }
                if (startsWithTimestamp(line)) {
                    // A new timestamped record always starts a new group.
                    bufferedLine = line;
                    break;
                }
                if (tsAnchored || isContinuation(line)) {
                    // When the header is timestamped, any non-timestamped line belongs to it
                    // (handles Python tracebacks, bare exception lines, 2-space indents, etc.).
                    continuations.add(line);
                } else {
                    bufferedLine = line;
                    break;
                }
            }

            return new LogGroup(header, continuations, source, null);
        }

        private String readLine() {
            try {
                return reader.readLine();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private void advance() {
            while (true) {
                String line = readLine();
                if (line == null) { done = true; return; }
                if (!line.isBlank()) { bufferedLine = line; return; }
            }
        }
    }
}
