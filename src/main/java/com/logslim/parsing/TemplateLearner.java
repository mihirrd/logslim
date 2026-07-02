package com.logslim.parsing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Learns dataset-specific variables from the corpus itself — the generalization
 * layer on top of {@link EntityMasker}.
 *
 * <p>{@code EntityMasker} handles <em>universal</em> syntax (numbers, IPs,
 * timestamps, UUIDs, paths). But every dataset has its own ID species — session
 * IDs, container names, hostnames, request tokens — that no fixed regex list
 * anticipates, and each unmasked one multiplies templates. Instead of
 * hand-writing per-dataset rules, this class discovers them statistically:
 *
 * <ol>
 *   <li>Group the distinct masked patterns by token count.</li>
 *   <li>Within a group, classify each varying token position: it is a
 *       <em>variable candidate</em> only when its cardinality exceeds
 *       {@code maxBranching} AND its values look like identifiers (contain
 *       digits, masked slots, or id punctuation). Everything else — low
 *       cardinality ({@code succeeded} vs {@code failed}) or word-like values
 *       (many distinct message texts) — is semantics.</li>
 *   <li>While any semantic position varies, split the group on the one with the
 *       fewest distinct values and recurse, keeping the templates separate.</li>
 *   <li>When only variable candidates remain, promote each to a {@code {var}}
 *       slot and merge the whole group into one template.</li>
 * </ol>
 *
 * <p>If all values at a promoted position share a literal {@code key=} /
 * {@code key:} prefix, the key stays in the template ({@code status={var}})
 * so the signal survives the merge.
 *
 * <p>The result is a pure, deterministic function of the corpus: input order
 * never matters, no per-dataset configuration exists, and re-running learning
 * on the same data reproduces the same template set.
 */
public final class TemplateLearner {

    public static final String VAR_SLOT = "{var}";

    private final int maxBranching;

    /**
     * @param maxBranching a varying token position with at most this many distinct
     *                     values is treated as semantic (split), above it as a
     *                     variable (merge)
     */
    public TemplateLearner(int maxBranching) {
        if (maxBranching < 1) throw new IllegalArgumentException("maxBranching must be >= 1");
        this.maxBranching = maxBranching;
    }

    /**
     * How a source pattern maps onto its merged template.
     *
     * @param finalPattern the merged template (contains {@code {var}} slots)
     * @param varPositions token indexes promoted to {@code {var}}, ascending
     * @param prefixes     literal prefix kept at each var position (e.g. {@code "status="}),
     *                     parallel to {@code varPositions}; empty string when none
     */
    public record MergePlan(String finalPattern, int[] varPositions, String[] prefixes) {}

    /**
     * Learn merges over the distinct masked patterns of a corpus.
     * Returns a plan for every pattern that merges into a shared template;
     * patterns absent from the map are already their own template (identity).
     */
    public Map<String, MergePlan> learn(Collection<String> patterns) {
        Map<Integer, List<String[]>> byLen = new HashMap<>();
        for (String p : new TreeSet<>(patterns)) { // sorted → deterministic
            String[] toks = split(p);
            byLen.computeIfAbsent(toks.length, k -> new ArrayList<>()).add(toks);
        }
        Map<String, MergePlan> plans = new HashMap<>();
        for (List<String[]> group : byLen.values()) {
            partition(group, plans);
        }
        return plans;
    }

    // — recursive split-or-merge ————————————————————————————————

    private void partition(List<String[]> rows, Map<String, MergePlan> out) {
        if (rows.size() <= 1) return; // singleton — its pattern is its template

        int tokenCount = rows.get(0).length;
        List<Integer> varying = new ArrayList<>();
        int minCard = Integer.MAX_VALUE, minPos = -1;
        for (int j = 0; j < tokenCount; j++) {
            Set<String> vals = new HashSet<>();
            for (String[] r : rows) vals.add(r[j]);
            if (vals.size() <= 1) continue;
            varying.add(j);
            // A position merges only when high-cardinality AND id-like; a varying
            // position that is either low-cardinality (succeeded/failed) or
            // word-like (many distinct message texts sharing a token count) is
            // semantics — the group must split on it, never blur it to {var}.
            boolean semantic = vals.size() <= maxBranching || !idLikeValues(vals);
            if (semantic && vals.size() < minCard) {
                minCard = vals.size();
                minPos = j;
            }
        }
        if (varying.isEmpty()) return; // distinct inputs guarantee this never happens

        if (minPos >= 0) {
            // Split on the most constant-like semantic position and recurse.
            Map<String, List<String[]>> sub = new TreeMap<>();
            for (String[] r : rows) {
                sub.computeIfAbsent(r[minPos], k -> new ArrayList<>()).add(r);
            }
            for (List<String[]> s : sub.values()) partition(s, out);
            return;
        }

        // Every varying position is a high-cardinality identifier → merge.
        int[] varPos = new int[varying.size()];
        String[] prefixes = new String[varying.size()];
        for (int k = 0; k < varPos.length; k++) {
            varPos[k] = varying.get(k);
            prefixes[k] = commonKeyPrefix(rows, varPos[k]);
        }
        String[] merged = rows.get(0).clone();
        for (int k = 0; k < varPos.length; k++) {
            merged[varPos[k]] = prefixes[k] + VAR_SLOT;
        }
        MergePlan plan = new MergePlan(String.join(" ", merged), varPos, prefixes);
        for (String[] r : rows) {
            out.put(String.join(" ", r), plan);
        }
    }

    /**
     * Do the values at a position look like identifiers rather than words?
     * True when at least half the distinct values carry an id signal: a digit
     * (universal masking leaves digits glued inside alphanumerics, e.g.
     * {@code sess-a8f3kz9}), a masked slot ({@code {ip}:...}), or id punctuation.
     * Pure natural-language words never qualify, so message text survives.
     */
    private static boolean idLikeValues(Set<String> values) {
        int idLike = 0;
        for (String v : values) {
            if (isIdLike(v)) idLike++;
        }
        return idLike * 2 >= values.size();
    }

    private static boolean isIdLike(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '0' && c <= '9') return true;
            switch (c) {
                case '{', '}', '=', '/', '\\', '@', '#', '+', '|', '~':
                    return true;
                default:
            }
        }
        return false;
    }

    /**
     * Longest literal prefix shared by every value at {@code pos}, truncated to its
     * last {@code =} or {@code :} separator — so {@code status=OK}/{@code status=FAIL}
     * keeps {@code status=} in the template. Empty when there is no separator or the
     * shared prefix contains brace characters (slot refs / escaped braces).
     */
    private static String commonKeyPrefix(List<String[]> rows, int pos) {
        String first = rows.get(0)[pos];
        int lcp = first.length();
        for (String[] r : rows) {
            String t = r[pos];
            int i = 0, max = Math.min(lcp, t.length());
            while (i < max && t.charAt(i) == first.charAt(i)) i++;
            lcp = i;
            if (lcp == 0) return "";
        }
        int cut = -1;
        for (int i = 0; i < lcp; i++) {
            char c = first.charAt(i);
            if (c == '{' || c == '}') return "";
            if (c == '=' || c == ':') cut = i;
        }
        return cut < 0 ? "" : first.substring(0, cut + 1);
    }

    // — applying a plan to one line ————————————————————————————

    /**
     * Build the final parameter list for a line masked as {@code sourcePattern}
     * with {@code universalParams}, under {@code plan}. Slots in pass-through
     * tokens keep their params; each var token contributes ONE param — its full
     * original text (minus the kept prefix), i.e. the token unmasked with its
     * own captured params. Byte-exact:
     * {@code unmask(plan.finalPattern(), buildParams(...)) == unmask(sourcePattern, universalParams)}.
     */
    public static List<String> buildParams(String sourcePattern, List<String> universalParams,
                                           MergePlan plan) {
        String[] tokens = split(sourcePattern);
        List<String> out = new ArrayList<>(universalParams.size());
        int pi = 0; // cursor into universalParams
        int vk = 0; // cursor into plan.varPositions
        for (int j = 0; j < tokens.length; j++) {
            String tok = tokens[j];
            int slots = slotCount(tok);
            if (vk < plan.varPositions().length && plan.varPositions()[vk] == j) {
                String remainder = tok.substring(plan.prefixes()[vk].length());
                out.add(EntityMasker.unmask(remainder, universalParams.subList(pi, pi + slots)));
                pi += slots;
                vk++;
            } else {
                for (int s = 0; s < slots; s++) out.add(universalParams.get(pi++));
            }
        }
        return out;
    }

    // — matching new patterns against learned templates ————————————

    /**
     * Rebuild a matchable template from a stored merged pattern (bootstrap from
     * an existing DB). Var positions are tokens of the form {@code prefix{var}}
     * with a brace-free prefix — exactly what {@link #learn} emits. Returns null
     * for a pattern with no {@code {var}} slots (identity templates need no matcher).
     */
    public static LearnedTemplate fromFinalPattern(String finalPattern) {
        String[] toks = split(finalPattern);
        List<Integer> varPos = new ArrayList<>();
        List<String> prefixes = new ArrayList<>();
        for (int j = 0; j < toks.length; j++) {
            String t = toks[j];
            if (t.endsWith(VAR_SLOT)) {
                String prefix = t.substring(0, t.length() - VAR_SLOT.length());
                if (prefix.indexOf('{') < 0 && prefix.indexOf('}') < 0) {
                    varPos.add(j);
                    prefixes.add(prefix);
                }
            }
        }
        if (varPos.isEmpty()) return null;
        int[] vp = varPos.stream().mapToInt(Integer::intValue).toArray();
        return new LearnedTemplate(
                new MergePlan(finalPattern, vp, prefixes.toArray(new String[0])));
    }

    /**
     * A merged template in matchable form, for patterns first seen after
     * learning (incremental ingest, Kafka): a new masked pattern that fits a
     * learned template's shape gets its plan instead of becoming a fresh template.
     */
    public static final class LearnedTemplate {
        private final MergePlan plan;
        private final String[] tokens;
        private final int[] varPositions;
        private final String[] prefixes;

        public LearnedTemplate(MergePlan plan) {
            this.plan = plan;
            this.tokens = split(plan.finalPattern());
            this.varPositions = plan.varPositions();
            this.prefixes = plan.prefixes();
        }

        public String finalPattern() {
            return plan.finalPattern();
        }

        /** The shared plan when {@code patternTokens} fits this template, else null. */
        public MergePlan tryMatch(String[] patternTokens) {
            if (patternTokens.length != tokens.length) return null;
            int vk = 0;
            for (int j = 0; j < tokens.length; j++) {
                if (vk < varPositions.length && varPositions[vk] == j) {
                    if (!patternTokens[j].startsWith(prefixes[vk])) return null;
                    vk++;
                } else if (!tokens[j].equals(patternTokens[j])) {
                    return null;
                }
            }
            return plan;
        }
    }

    // — escaped-brace helpers ————————————————————————————————————

    /** Tokenize a pattern on single spaces, preserving empties (multi-space runs). */
    public static String[] split(String pattern) {
        return pattern.split(" ", -1);
    }

    /** Number of {@code {name}} slot refs in an escaped-brace pattern fragment. */
    public static int slotCount(String fragment) {
        int n = 0, len = fragment.length();
        for (int i = 0; i < len; ) {
            char c = fragment.charAt(i);
            if (c == '{') {
                if (i + 1 < len && fragment.charAt(i + 1) == '{') { i += 2; continue; }
                int j = fragment.indexOf('}', i + 1);
                if (j < 0) { i++; continue; }
                n++;
                i = j + 1;
            } else if (c == '}') {
                i += (i + 1 < len && fragment.charAt(i + 1) == '}') ? 2 : 1;
            } else {
                i++;
            }
        }
        return n;
    }
}
