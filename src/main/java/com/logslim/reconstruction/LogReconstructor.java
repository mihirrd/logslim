package com.logslim.reconstruction;

import com.logslim.parsing.EntityMasker;
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
     *
     * <p>Escaped-brace scheme (the {@link com.logslim.parsing.EntityMasker} contract):
     * every single {@code {name}} is a substitution point — including mid-token, e.g.
     * {@code {ip}:Got} — and literal source braces are stored doubled
     * ({@code {{} / {@code }}}), so JSON-ish content never collides with slots.
     * All other characters are emitted verbatim; byte-exact by construction.
     */
    public String reconstruct(String pattern, List<String> paramValues) {
        try {
            return EntityMasker.unmask(pattern, paramValues);
        } catch (IllegalArgumentException e) {
            throw new ReconstructionException(e.getMessage());
        }
    }

    /**
     * Reconstruct from an explicit pattern + named parameter map.
     * Kept for query filtering. Same escaped-brace slot rule as the positional
     * overload; repeated slot names consume {@code name}, {@code name_1}, … keys
     * (matching {@code TemplateNormalizer.slotNames}).
     */
    public String reconstruct(String pattern, Map<String, String> parameters) {
        if (parameters == null) parameters = Map.of();

        int len = pattern.length();
        Map<String, Integer> consumed = new java.util.HashMap<>();
        StringBuilder sb = new StringBuilder(len + 64);

        for (int i = 0; i < len; ) {
            char c = pattern.charAt(i);
            if (c == '{') {
                if (i + 1 < len && pattern.charAt(i + 1) == '{') { sb.append('{'); i += 2; continue; }
                int j = pattern.indexOf('}', i + 1);
                if (j < 0) { sb.append(c); i++; continue; }
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
            } else if (c == '}') {
                if (i + 1 < len && pattern.charAt(i + 1) == '}') { sb.append('}'); i += 2; continue; }
                sb.append(c);
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }
}
