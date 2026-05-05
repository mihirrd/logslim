package com.logslim.extraction;

import com.logslim.storage.Template;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Computes similarity between a normalized input pattern and a candidate template,
 * and selects the best match above the configured threshold.
 */
@Component
public class TemplateMatcher {

    @Value("${logslim.template.similarity-threshold:0.95}")
    private double similarityThreshold;

    /**
     * Score two normalized patterns by token-match ratio.
     * Score = tokens_matching_exactly / max(left.tokenCount, right.tokenCount)
     */
    public double score(String normalizedInput, String candidatePattern) {
        String[] inputTokens     = normalizedInput.split("\\s+");
        String[] candidateTokens = candidatePattern.split("\\s+");

        int maxLen = Math.max(inputTokens.length, candidateTokens.length);
        if (maxLen == 0) return 1.0;

        int minLen = Math.min(inputTokens.length, candidateTokens.length);
        int matches = 0;
        for (int i = 0; i < minLen; i++) {
            if (inputTokens[i].equals(candidateTokens[i])) matches++;
        }
        return (double) matches / maxLen;
    }

    /**
     * Find the best-matching template from the candidates list.
     * Returns empty if no candidate meets the threshold.
     */
    public Optional<Template> findBestMatch(String normalizedInput, List<Template> candidates) {
        Template best = null;
        double bestScore = -1;

        for (Template candidate : candidates) {
            double s = score(normalizedInput, candidate.getPattern());
            if (s >= similarityThreshold && s > bestScore) {
                bestScore = s;
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }

    public double getSimilarityThreshold() {
        return similarityThreshold;
    }
}
