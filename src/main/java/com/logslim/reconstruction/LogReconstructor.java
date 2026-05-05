package com.logslim.reconstruction;

import com.logslim.storage.LogEntry;
import com.logslim.storage.Template;
import com.logslim.storage.TemplateDao;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Reconstructs the original log line from a stored template + parameter map.
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
        return reconstruct(template.getPattern(), entry.getParameters());
    }

    /**
     * Reconstruct from an explicit pattern + parameter map.
     * Pattern tokens like {num}, {uuid}, {hash}, {ts} are replaced in left-to-right order
     * by matching parameter keys. Indexed keys ({num_1}, {uuid_0}, etc.) are resolved first.
     */
    public String reconstruct(String pattern, Map<String, String> parameters) {
        if (!pattern.contains("{")) {
            return pattern;
        }
        if (parameters == null) {
            parameters = Map.of();
        }

        String[] tokens = pattern.split("\\s+");
        // Track how many times each base placeholder has been consumed
        java.util.Map<String, Integer> consumed = new java.util.HashMap<>();
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) sb.append(' ');
            String tok = tokens[i];
            if (tok.startsWith("{") && tok.endsWith("}")) {
                String base = tok.substring(1, tok.length() - 1); // e.g. "num", "uuid"
                int count = consumed.getOrDefault(base, 0);
                // Look up: first try "base" (count==0), then "base_1", "base_2", …
                String key = count == 0 ? base : base + "_" + count;
                String value = parameters.get(key);
                if (value == null) {
                    // Fallback: try exact token without braces
                    value = parameters.get(tok);
                }
                if (value == null) {
                    throw new ReconstructionException(
                            "Missing parameter '" + key + "' for pattern: " + pattern);
                }
                consumed.put(base, count + 1);
                sb.append(value);
            } else {
                sb.append(tok);
            }
        }
        return sb.toString();
    }
}
