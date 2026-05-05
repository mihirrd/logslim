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
     * Placeholders in the pattern ({num}, {uuid}, etc.) are substituted
     * left-to-right from the values list.
     */
    public String reconstruct(String pattern, List<String> paramValues) {
        if (!pattern.contains("{")) return pattern;
        if (paramValues == null || paramValues.isEmpty()) {
            if (pattern.contains("{"))
                throw new ReconstructionException("Missing parameters for pattern: " + pattern);
            return pattern;
        }

        String[] tokens = pattern.split("\\s+");
        int pos = 0;
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) sb.append(' ');
            String tok = tokens[i];
            if (tok.startsWith("{") && tok.endsWith("}")) {
                if (pos >= paramValues.size()) {
                    throw new ReconstructionException(
                            "Not enough parameter values for pattern: " + pattern);
                }
                sb.append(paramValues.get(pos++));
            } else {
                sb.append(tok);
            }
        }
        return sb.toString();
    }

    /**
     * Reconstruct from an explicit pattern + named parameter map.
     * Kept for backward compatibility with tests and query filtering.
     */
    public String reconstruct(String pattern, Map<String, String> parameters) {
        if (!pattern.contains("{")) return pattern;
        if (parameters == null) parameters = Map.of();

        String[] tokens = pattern.split("\\s+");
        Map<String, Integer> consumed = new java.util.HashMap<>();
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) sb.append(' ');
            String tok = tokens[i];
            if (tok.startsWith("{") && tok.endsWith("}")) {
                String base = tok.substring(1, tok.length() - 1);
                int count = consumed.getOrDefault(base, 0);
                String key = count == 0 ? base : base + "_" + count;
                String value = parameters.get(key);
                if (value == null) value = parameters.get(tok);
                if (value == null)
                    throw new ReconstructionException(
                            "Missing parameter '" + key + "' for pattern: " + pattern);
                consumed.put(base, count + 1);
                sb.append(value);
            } else {
                sb.append(tok);
            }
        }
        return sb.toString();
    }
}
