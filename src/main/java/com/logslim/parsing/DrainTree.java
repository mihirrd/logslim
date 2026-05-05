package com.logslim.parsing;

import com.logslim.storage.Template;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Online log template mining via the Drain algorithm.
 *
 * Groups log token sequences into clusters by routing through a parse tree
 * keyed first by token count, then by the first token.  Positions that vary
 * across lines in the same cluster become wildcards ({@value WILDCARD}).
 *
 * A cluster is locked (finalized) after it has been seen {@code lockAfterN}
 * times.  Lines that arrive before a cluster locks are NOT stored here — the
 * caller is responsible for falling back to raw storage.
 *
 * Thread-safe: all public methods are synchronized.
 */
@Component
public class DrainTree {

    private static final Logger log = LoggerFactory.getLogger(DrainTree.class);

    /** Placeholder used in template token lists for variable positions. */
    public static final String WILDCARD = "<*>";

    private final double simThreshold;
    private final int lockAfterN;
    private final int maxChildren;

    /** Parse tree: token-count → first-token → candidate clusters. */
    private final Map<Integer, Map<String, List<LogCluster>>> tree = new HashMap<>();

    public DrainTree(
            @Value("${logslim.drain.sim-threshold:0.5}") double simThreshold,
            @Value("${logslim.drain.lock-after-n:10}") int lockAfterN,
            @Value("${logslim.drain.max-children:100}") int maxChildren) {
        this.simThreshold = simThreshold;
        this.lockAfterN   = lockAfterN;
        this.maxChildren  = maxChildren;
    }

    /**
     * Result of processing one log line.
     *
     * @param templateTokens current template shape (mix of static values and {@value WILDCARD})
     * @param justLocked     true only on the occurrence that caused the cluster to lock
     * @param isLocked       true once the cluster has reached {@code lockAfterN} occurrences
     */
    public record ProcessResult(
            List<String> templateTokens,
            boolean justLocked,
            boolean isLocked
    ) {}

    /**
     * Process a sequence of raw token strings from one log line.
     *
     * <p>Tokens that are already {@value WILDCARD} (pre-masked by the caller)
     * are treated as wildcards from the start — they never block matching.
     */
    public synchronized ProcessResult process(List<String> tokens) {
        if (tokens.isEmpty()) {
            return new ProcessResult(List.of(), false, true);
        }

        LogCluster cluster = findMatch(tokens);
        boolean justLocked = false;

        if (cluster == null) {
            cluster = new LogCluster(tokens);
            insertToTree(cluster);
            if (lockAfterN <= 1) {
                cluster.locked   = true;
                justLocked       = true;
            }
        } else if (!cluster.locked) {
            boolean firstWasStatic = !WILDCARD.equals(cluster.templateTokens.get(0));
            cluster.merge(tokens);
            if (firstWasStatic && WILDCARD.equals(cluster.templateTokens.get(0))) {
                rerouteToWildcard(cluster);
            }
            if (cluster.occurrences >= lockAfterN) {
                cluster.locked = true;
                justLocked     = true;
            }
        } else {
            cluster.occurrences++;
        }

        return new ProcessResult(
                Collections.unmodifiableList(new ArrayList<>(cluster.templateTokens)),
                justLocked,
                cluster.locked
        );
    }

    /**
     * Load templates already stored in the DB as locked clusters so that the
     * tree is consistent with persisted state across restarts.
     */
    public synchronized void bootstrap(Iterable<Template> templates) {
        for (Template t : templates) {
            List<String> tokens = toWildcardTokens(t.getPattern());
            LogCluster cluster = new LogCluster(tokens);
            cluster.occurrences = (int) Math.min(t.getOccurrences(), Integer.MAX_VALUE);
            cluster.locked      = true;
            insertToTree(cluster);
            log.debug("Bootstrapped drain cluster: {}", t.getPattern());
        }
    }

    /** Clear the in-memory tree (used in tests between runs). */
    public synchronized void reset() {
        tree.clear();
    }

    /**
     * Convert a typed pattern (e.g. {@code "User {num} failed login"}) to a
     * wildcard token list (e.g. {@code ["User", "<*>", "failed", "login"]}).
     */
    public static List<String> toWildcardTokens(String pattern) {
        String[] parts = pattern.split("\\s+");
        List<String> result = new ArrayList<>(parts.length);
        for (String p : parts) {
            result.add((p.startsWith("{") && p.endsWith("}")) ? WILDCARD : p);
        }
        return result;
    }

    // — private helpers ——————————————————————————————————————————

    private LogCluster findMatch(List<String> tokens) {
        Map<String, List<LogCluster>> lenNode = tree.get(tokens.size());
        if (lenNode == null) return null;

        List<LogCluster> candidates = new ArrayList<>();
        List<LogCluster> byFirst = lenNode.get(tokens.get(0));
        if (byFirst != null) candidates.addAll(byFirst);
        List<LogCluster> wildcardBucket = lenNode.get(WILDCARD);
        if (wildcardBucket != null) candidates.addAll(wildcardBucket);

        return bestMatch(candidates, tokens);
    }

    private LogCluster bestMatch(List<LogCluster> candidates, List<String> tokens) {
        double bestSim       = -1;
        int    bestWildcards = Integer.MAX_VALUE;
        LogCluster best      = null;

        for (LogCluster c : candidates) {
            if (c.templateTokens.size() != tokens.size()) continue;
            double sim = similarity(c.templateTokens, tokens);
            int    wc  = countWildcards(c.templateTokens);
            if (sim >= simThreshold && (sim > bestSim || (sim == bestSim && wc < bestWildcards))) {
                bestSim       = sim;
                bestWildcards = wc;
                best          = c;
            }
        }
        return best;
    }

    /** Fraction of tokens that match exactly (wildcard positions are skipped). */
    private double similarity(List<String> template, List<String> tokens) {
        int matches = 0;
        for (int i = 0; i < template.size(); i++) {
            if (!WILDCARD.equals(template.get(i)) && template.get(i).equals(tokens.get(i))) {
                matches++;
            }
        }
        return (double) matches / template.size();
    }

    private int countWildcards(List<String> tokens) {
        int n = 0;
        for (String t : tokens) if (WILDCARD.equals(t)) n++;
        return n;
    }

    private void insertToTree(LogCluster cluster) {
        int    len        = cluster.templateTokens.size();
        String firstToken = cluster.templateTokens.get(0);
        cluster.bucketKey = WILDCARD.equals(firstToken) ? WILDCARD : firstToken;

        Map<String, List<LogCluster>> lenNode =
                tree.computeIfAbsent(len, k -> new HashMap<>());
        List<LogCluster> bucket =
                lenNode.computeIfAbsent(cluster.bucketKey, k -> new ArrayList<>());

        // If the named bucket is full, spill into the wildcard bucket
        if (!WILDCARD.equals(cluster.bucketKey) && bucket.size() >= maxChildren) {
            lenNode.computeIfAbsent(WILDCARD, k -> new ArrayList<>()).add(cluster);
            cluster.bucketKey = WILDCARD;
        } else {
            bucket.add(cluster);
        }
    }

    private void rerouteToWildcard(LogCluster cluster) {
        if (WILDCARD.equals(cluster.bucketKey)) return;
        int len = cluster.templateTokens.size();
        Map<String, List<LogCluster>> lenNode = tree.get(len);
        if (lenNode != null) {
            List<LogCluster> old = lenNode.get(cluster.bucketKey);
            if (old != null) old.remove(cluster);
        }
        lenNode.computeIfAbsent(WILDCARD, k -> new ArrayList<>()).add(cluster);
        cluster.bucketKey = WILDCARD;
    }

    // — inner class ——————————————————————————————————————————————

    static class LogCluster {
        final List<String> templateTokens;
        String bucketKey;
        int    occurrences;
        boolean locked;

        LogCluster(List<String> tokens) {
            this.templateTokens = new ArrayList<>(tokens);
            this.occurrences    = 1;
            this.locked         = false;
        }

        /** Merge a new line into this cluster: positions that differ become wildcards. */
        void merge(List<String> tokens) {
            for (int i = 0; i < templateTokens.size(); i++) {
                if (!WILDCARD.equals(templateTokens.get(i))
                        && !templateTokens.get(i).equals(tokens.get(i))) {
                    templateTokens.set(i, WILDCARD);
                }
            }
            occurrences++;
        }
    }
}
