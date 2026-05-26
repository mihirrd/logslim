package com.logslim.reconstruction;

import com.logslim.storage.LogEntry;
import com.logslim.storage.Template;
import com.logslim.storage.TemplateDao;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Reconstructs the original log line from a stored template + parameter values.
 * Invariant: reconstruct(extract(line)).equals(line) for every log line.
 */
@Component
public class LogReconstructor {

    private final TemplateDao templateDao;

    public LogReconstructor(TemplateDao templateDao) {
        this.templateDao = templateDao;
    }

    /**
     * Reconstruct from a LogEntry (looks up template internally).
     */
    public String reconstruct(LogEntry entry) {
        Template template = templateDao.findById(entry.getTemplateId())
                .orElseThrow(() -> new ReconstructionException(
                        "Template not found for id=" + entry.getTemplateId()));
        String header = reconstruct(template.getPattern(), entry.getParameterValues());
        String cont = entry.getContinuationText();
        if (cont != null && !cont.isEmpty()) return header + "\n" + cont;
        return header;
    }

    /**
     * Reconstruct from a pattern and positional parameter values.
     * A {placeholder} is treated as a substitution point ONLY when it is a complete
     * whitespace-delimited token: the '{' must be at position 0 or immediately preceded
     * by whitespace, and the matching '}' must be at end-of-pattern or immediately
     * followed by whitespace. All other characters (including multi-space runs, tabs,
     * leading/trailing whitespace, and literal {…} substrings inside larger tokens) are
     * emitted verbatim, preserving the exact original whitespace structure.
     */
    public String reconstruct(String pattern, List<String> paramValues) {
        if (!pattern.contains("{")) return pattern;
        if (paramValues == null || paramValues.isEmpty()) {
            if (pattern.contains("{"))
                throw new ReconstructionException("Missing parameters for pattern: " + pattern);
            return pattern;
        }

        int pos = 0;
        int len = pattern.length();
        StringBuilder sb = new StringBuilder(len + 64);

        for (int i = 0; i < len; ) {
            char c = pattern.charAt(i);
            if (c == '{') {
                // Only a complete-token placeholder if preceded by start or whitespace
                boolean tokenStart = (i == 0) || isWhitespace(pattern.charAt(i - 1));
                if (tokenStart) {
                    // Scan for closing '}' with no whitespace inside
                    int j = i + 1;
                    while (j < len && pattern.charAt(j) != '}' && !isWhitespace(pattern.charAt(j))) {
                        j++;
                    }
                    if (j < len && pattern.charAt(j) == '}') {
                        // Confirm '}' is at end-of-pattern or followed by whitespace
                        boolean tokenEnd = (j + 1 == len) || isWhitespace(pattern.charAt(j + 1));
                        if (tokenEnd) {
                            if (pos >= paramValues.size()) {
                                throw new ReconstructionException(
                                        "Not enough parameter values for pattern: " + pattern);
                            }
                            sb.append(paramValues.get(pos++));
                            i = j + 1;
                            continue;
                        }
                    }
                }
                // Not a complete-token placeholder — emit '{' literally
                sb.append(c);
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * Reconstruct from an explicit pattern + named parameter map.
     * Kept for backward compatibility with tests and query filtering.
     * Uses the same complete-token placeholder rule as the positional overload.
     */
    public String reconstruct(String pattern, Map<String, String> parameters) {
        if (!pattern.contains("{")) return pattern;
        if (parameters == null) parameters = Map.of();

        int len = pattern.length();
        Map<String, Integer> consumed = new java.util.HashMap<>();
        StringBuilder sb = new StringBuilder(len + 64);

        for (int i = 0; i < len; ) {
            char c = pattern.charAt(i);
            if (c == '{') {
                boolean tokenStart = (i == 0) || isWhitespace(pattern.charAt(i - 1));
                if (tokenStart) {
                    int j = i + 1;
                    while (j < len && pattern.charAt(j) != '}' && !isWhitespace(pattern.charAt(j))) {
                        j++;
                    }
                    if (j < len && pattern.charAt(j) == '}') {
                        boolean tokenEnd = (j + 1 == len) || isWhitespace(pattern.charAt(j + 1));
                        if (tokenEnd) {
                            String base = pattern.substring(i + 1, j);
                            int count = consumed.getOrDefault(base, 0);
                            String key = count == 0 ? base : base + "_" + count;
                            String value = parameters.get(key);
                            if (value == null) value = parameters.get("{" + base + "}");
                            if (value == null)
                                throw new ReconstructionException(
                                        "Missing parameter '" + key + "' for pattern: " + pattern);
                            consumed.put(base, count + 1);
                            sb.append(value);
                            i = j + 1;
                            continue;
                        }
                    }
                }
                sb.append(c);
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n';
    }
}
