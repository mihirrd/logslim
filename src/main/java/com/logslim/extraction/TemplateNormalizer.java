package com.logslim.extraction;

import com.logslim.parsing.Token;
import com.logslim.parsing.TokenSubtype;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
                sb.append(placeholderFor(t));
            }
        }
        return sb.toString();
    }

    /**
     * Determine placeholder using the pre-classified subtype stored on the token.
     * Falls back to the value-based path for tokens constructed without a subtype.
     */
    public String placeholderFor(Token t) {
        if (t == null) return "{val}";
        TokenSubtype sub = t.subtype();
        if (sub != TokenSubtype.NONE) {
            return switch (sub) {
                case UUID -> "{uuid}";
                case TS   -> "{ts}";
                case TIME -> "{time}";
                case HASH -> "{hash}";
                case IP   -> "{ip}";
                case PROC -> "{proc}";
                case KV   -> {
                    int eq = t.value().indexOf('=');
                    yield eq > 0 ? "{" + t.value().substring(0, eq) + "}" : "{val}";
                }
                case NUM  -> "{num}";
                default   -> placeholderFor(t.value());
            };
        }
        return placeholderFor(t.value());
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
     * Extract dynamic token values as an ordered list for compact storage.
     * Position in the list corresponds to position of the placeholder in the pattern.
     */
    public List<String> extractParameterValues(List<Token> tokens) {
        List<String> values = new ArrayList<>();
        for (Token t : tokens) {
            if (t.isDynamic()) values.add(t.value());
        }
        return values;
    }

    /**
     * Canonical slot names for a stored pattern, in placeholder order, de-duplicated
     * with a {@code _N} suffix exactly the way {@link #extractParameters} builds its
     * keys (e.g. two {@code {num}} → {@code ["num", "num_1"]}). This is the single
     * source of truth for the names an agent sees, can project on, and can filter by:
     * structured-query slots, projection matching, and inspect_template all use it,
     * so the displayed name always equals the filterable key.
     *
     * <p>A {@code {...}} counts as a slot ONLY when it is a complete whitespace-delimited
     * token — the exact rule {@code LogReconstructor} uses for substitution points — so a
     * literal {@code {id}} embedded inside a larger token (e.g. {@code /users/{id}}) is NOT
     * a slot and slot index <em>k</em> lines up with {@code parameterValues[k]}.
     */
    public List<String> slotNames(String pattern) {
        List<String> names = new ArrayList<>();
        java.util.Map<String, Integer> counters = new java.util.HashMap<>();
        int len = pattern.length();
        for (int i = 0; i < len; ) {
            if (pattern.charAt(i) == '{' && (i == 0 || isSlotWhitespace(pattern.charAt(i - 1)))) {
                int j = i + 1;
                while (j < len && pattern.charAt(j) != '}' && !isSlotWhitespace(pattern.charAt(j))) j++;
                if (j < len && pattern.charAt(j) == '}'
                        && (j + 1 == len || isSlotWhitespace(pattern.charAt(j + 1)))) {
                    String base = pattern.substring(i + 1, j);
                    int count = counters.getOrDefault(base, 0);
                    names.add(count == 0 ? base : base + "_" + count);
                    counters.put(base, count + 1);
                    i = j + 1;
                    continue;
                }
            }
            i++;
        }
        return names;
    }

    private static boolean isSlotWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n';
    }

    /** Base name (without the {@code _N} de-dup suffix) for a canonical slot name. */
    public static String slotBaseName(String slotName) {
        if (slotName == null) return null;
        int u = slotName.lastIndexOf('_');
        return (u > 0 && slotName.substring(u + 1).matches("\\d+"))
                ? slotName.substring(0, u) : slotName;
    }

    /**
     * Strip a leading {@code key=} mask from a stored slot value when the key equals
     * the slot's base name (so under slot {@code user} the value {@code user=usr_1}
     * becomes {@code usr_1}, while a bare {@code 16ms} or an unrelated {@code a=b} is
     * left untouched). Read-side projection only — stored values are never mutated, so
     * reconstruction stays byte-exact.
     */
    public static String stripKeyPrefix(String value, String slotName) {
        if (value == null || slotName == null) return value;
        int eq = value.indexOf('=');
        if (eq <= 0) return value;
        return value.substring(0, eq).equals(slotBaseName(slotName))
                ? value.substring(eq + 1) : value;
    }

    /**
     * Rebuild the named parameter map from a stored pattern string and its positional values.
     * Inverse of extractParameterValues — used at query time for filter matching. Keyed by the
     * shared {@link #slotNames} canonical names so filter keys match what callers project on.
     */
    public java.util.Map<String, String> buildParameterMap(String pattern, List<String> values) {
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        List<String> names = slotNames(pattern);
        for (int i = 0; i < names.size() && i < values.size(); i++) {
            result.put(names.get(i), values.get(i));
        }
        return result;
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
                String base = placeholderFor(t).replace("{", "").replace("}", "");
                int count = counters.getOrDefault(base, 0);
                String key = count == 0 ? base : base + "_" + count;
                counters.put(base, count + 1);
                params.put(key, t.value());
            }
        }
        return params;
    }
}
