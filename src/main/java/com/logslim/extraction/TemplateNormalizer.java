package com.logslim.extraction;

import com.logslim.parsing.Token;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Converts a parsed log's dynamic tokens to typed placeholders, producing a
 * normalized pattern string that is used as the template key.
 *
 * Example: ["User","123","failed","login"] → "User {num} failed login"
 */
@Component
public class TemplateNormalizer {

    public String normalize(List<Token> tokens) {
        if (tokens == null || tokens.isEmpty())
            return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0)
                sb.append(' ');
            Token t = tokens.get(i);
            if (t.isStatic()) {
                sb.append(t.value());
            } else {
                sb.append(placeholderFor(t.value()));
            }
        }
        return sb.toString();
    }

    /**
     * Determine placeholder name based on the token's raw value so that
     * different dynamic types get distinct slot names in the pattern.
     */
    public String placeholderFor(String value) {
        if (value == null)
            return "{val}";
        if (value.contains("-") && value.length() == 36)
            return "{uuid}";
        if (value.matches("\\d{4}-\\d{2}-\\d{2}.*"))
            return "{ts}";
        if (value.matches("\\d{1,2}:\\d{2}:\\d{2}(\\.\\d+)?"))
            return "{time}";
        if (value.matches("[0-9a-fA-F]{32,}"))
            return "{hash}";
        if (value.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"))
            return "{ip}";
        if (value.matches("[A-Za-z][\\w.-]*\\[\\d+\\]:?"))
            return "{proc}";
        // key=value token: use the key name as the placeholder (e.g. user_id=64 → {user_id})
        int eq = value.indexOf('=');
        if (eq > 0 && eq < value.length() - 1)
            return "{" + value.substring(0, eq) + "}";
        return "{num}";
    }

    /**
     * Extract the parameter map from the original tokens given the normalized
     * pattern.
     * Returns an ordered map: placeholder → original value, suffixed with an index
     * when the same placeholder appears more than once (e.g. {num_0}, {num_1}).
     */
    public java.util.Map<String, String> extractParameters(List<Token> tokens) {
        java.util.Map<String, String> params = new java.util.LinkedHashMap<>();
        java.util.Map<String, Integer> counters = new java.util.HashMap<>();

        for (Token t : tokens) {
            if (t.isDynamic()) {
                String base = placeholderFor(t.value()).replace("{", "").replace("}", "");
                int count = counters.getOrDefault(base, 0);
                String key = count == 0 ? base : base + "_" + count;
                counters.put(base, count + 1);
                params.put(key, t.value());
            }
        }
        return params;
    }
}
