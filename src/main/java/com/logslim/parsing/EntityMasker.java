package com.logslim.parsing;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Span-based entity masker — the core of the v2 deterministic parser.
 *
 * <p>Replaces the Drain tree. Instead of routing whitespace tokens through a
 * parse tree (which fragments on unmasked variable tokens and is order-dependent),
 * this scans the <em>whole line</em> left-to-right for universal entity spans
 * (timestamps, IP[:port], paths, UUIDs, hashes, sizes, numbers) and replaces each
 * with a typed {@code {name}} placeholder. The resulting pattern is the template
 * key; identical patterns are the same template.
 *
 * <p>Properties:
 * <ul>
 *   <li><b>Deterministic &amp; order-independent</b> — {@code mask} is a pure
 *       function of one line; the template set is a pure function of the corpus.</li>
 *   <li><b>100% coverage</b> — every line maps to a template (a line with no
 *       entities is its own template); nothing is dumped to an invisible raw pile.</li>
 *   <li><b>Intra-token masking</b> — entities glued mid-token are masked
 *       (e.g. {@code 10.0.0.1:50010:Got} → {@code {ip}:Got}), which whole-token
 *       Drain could not do.</li>
 *   <li><b>Lossless</b> — {@link #unmask} reconstructs the original line
 *       byte-for-byte from pattern + params.</li>
 * </ul>
 *
 * <p>Template format is an <b>escaped brace string</b>: a single {@code {name}}
 * is a substitution slot; literal source braces are doubled ({@code {} → {{},
 * {@code }} → }}}) so JSON-ish content never collides with slots.
 */
@Component
public class EntityMasker {

    /**
     * Entity alternatives in priority order. Java alternation tries branches in
     * order at each position and {@link Matcher#find} scans positions left to
     * right, so this yields leftmost, priority-ordered, non-overlapping matches.
     * IP must precede PATH so {@code /10.0.0.1:50010} masks the address, not a path.
     */
    private static final Pattern ENTITY = Pattern.compile(
            "(?<ts>\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?)"
          + "|(?<time>\\b\\d{1,2}:\\d{2}:\\d{2}(?:[.,]\\d+)?)"
          + "|(?<date>\\b\\d{4}-\\d{2}-\\d{2}\\b)"
          + "|(?<uuid>[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})"
          + "|(?<ip>\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(?::\\d+)?)"
          + "|(?<path>/(?!\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})(?:[\\w.@*-]+/)*[\\w@*-]+(?:\\.[\\w@*-]+)*)"
          + "|(?<hex>\\b[0-9a-fA-F]{16,}\\b)"
          + "|(?<size>\\b\\d+(?:\\.\\d+)?(?:ms|ns|s|KB|MB|GB|B)\\b)"
          + "|(?<num>(?<![A-Za-z0-9])-?\\d+(?:\\.\\d+)?)"
    );

    /** Group names above, in declaration order, used to label each match's placeholder. */
    private static final String[] GROUPS =
            {"ts", "time", "date", "uuid", "ip", "path", "hex", "size", "num"};

    /** Placeholder name per entity group (what the agent sees and can filter on). */
    private static String placeholderName(String group) {
        return switch (group) {
            case "ts"   -> "ts";
            case "time" -> "time";
            case "date" -> "date";
            case "ip"   -> "ip";
            case "uuid" -> "uuid";
            case "hex"  -> "hash";
            case "size" -> "size";
            case "path" -> "path";
            default     -> "num";
        };
    }

    /**
     * Result of masking one line.
     *
     * @param pattern escaped-brace template string (e.g. {@code "{ip}:Got serving blk_{blk}"})
     * @param params  matched entity values in left-to-right order, parallel to the slots
     */
    public record MaskResult(String pattern, List<String> params) {}

    /** Mask one raw line into (template pattern, ordered params). Pure function. */
    public MaskResult mask(String line) {
        if (line == null) return new MaskResult("", List.of());
        StringBuilder pattern = new StringBuilder(line.length() + 16);
        List<String> params = new ArrayList<>();
        Matcher m = ENTITY.matcher(line);
        int last = 0;
        while (m.find()) {
            appendEscaped(pattern, line, last, m.start());
            String group = matchedGroup(m);
            pattern.append('{').append(placeholderName(group)).append('}');
            params.add(m.group());
            last = m.end();
        }
        appendEscaped(pattern, line, last, line.length());
        return new MaskResult(pattern.toString(), params);
    }

    /**
     * Reconstruct the original line from an escaped-brace pattern + ordered params.
     * Inverse of {@link #mask}: {@code {{}/{@code }}} unescape to literal braces and
     * each single {@code {name}} consumes the next param. Byte-exact.
     */
    public static String unmask(String pattern, List<String> params) {
        if (pattern == null) return "";
        StringBuilder sb = new StringBuilder(pattern.length() + 32);
        int pos = 0, len = pattern.length(), pi = 0;
        for (int i = 0; i < len; ) {
            char c = pattern.charAt(i);
            if (c == '{') {
                if (i + 1 < len && pattern.charAt(i + 1) == '{') { sb.append('{'); i += 2; continue; }
                int j = pattern.indexOf('}', i + 1);
                if (j < 0) { sb.append(c); i++; continue; }
                if (pi >= (params == null ? 0 : params.size())) {
                    throw new IllegalArgumentException("Not enough params for pattern: " + pattern);
                }
                sb.append(params.get(pi++));
                i = j + 1;
            } else if (c == '}') {
                if (i + 1 < len && pattern.charAt(i + 1) == '}') { sb.append('}'); i += 2; continue; }
                sb.append(c); i++;
            } else {
                sb.append(c); i++;
            }
        }
        return sb.toString();
    }

    // — helpers ——————————————————————————————————————————————

    /** Append literal text [from,to) of src, doubling any brace so it stays literal. */
    private static void appendEscaped(StringBuilder sb, String src, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = src.charAt(i);
            if (c == '{' || c == '}') sb.append(c);
            sb.append(c);
        }
    }

    private static String matchedGroup(Matcher m) {
        for (String g : GROUPS) {
            if (m.group(g) != null) return g;
        }
        return "num"; // unreachable for a successful match
    }
}
