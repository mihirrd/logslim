package com.logslim.ingestion;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

public class MultiLineGrouper implements Iterable<LogGroup> {

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
                if (isContinuation(line)) {
                    continuations.add(line);
                } else {
                    bufferedLine = line;
                    break;
                }
            }

            return new LogGroup(header, continuations, source);
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
