package com.logslim.parsing;

import com.logslim.storage.Template;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static com.logslim.parsing.DrainTree.WILDCARD;
import static org.assertj.core.api.Assertions.assertThat;

class DrainTreeTest {

    // lockAfterN=2, simThreshold=0.5 for most tests
    private DrainTree tree;

    @BeforeEach
    void setup() {
        tree = new DrainTree(0.5, 2, 100);
    }

    // — locking behaviour ————————————————————————————————————

    @Test
    void firstOccurrence_notLocked() {
        DrainTree.ProcessResult r = tree.process(List.of("User", "123", "failed", "login"));
        assertThat(r.isLocked()).isFalse();
        assertThat(r.justLocked()).isFalse();
    }

    @Test
    void secondSimilarOccurrence_locks() {
        tree.process(List.of("User", "123", "failed", "login"));
        DrainTree.ProcessResult r = tree.process(List.of("User", "456", "failed", "login"));
        assertThat(r.isLocked()).isTrue();
        assertThat(r.justLocked()).isTrue();
    }

    @Test
    void lockAfterN3_locksOnThird() {
        DrainTree tree3 = new DrainTree(0.5, 3, 100);
        tree3.process(List.of("x", "1"));
        tree3.process(List.of("x", "2"));
        DrainTree.ProcessResult r = tree3.process(List.of("x", "3"));
        assertThat(r.isLocked()).isTrue();
        assertThat(r.justLocked()).isTrue();
    }

    @Test
    void afterLock_justLockedFalse() {
        tree.process(List.of("User", "1", "login"));
        tree.process(List.of("User", "2", "login")); // locks
        DrainTree.ProcessResult r = tree.process(List.of("User", "3", "login"));
        assertThat(r.isLocked()).isTrue();
        assertThat(r.justLocked()).isFalse();
    }

    // — template shape (wildcard discovery) ——————————————————

    @Test
    void differingPosition_becomesWildcard() {
        tree.process(List.of("User", "123", "failed"));
        DrainTree.ProcessResult r = tree.process(List.of("User", "456", "failed"));
        assertThat(r.templateTokens()).containsExactly("User", WILDCARD, "failed");
    }

    @Test
    void allStaticMatch_noWildcards() {
        tree.process(List.of("server", "started"));
        DrainTree.ProcessResult r = tree.process(List.of("server", "started"));
        assertThat(r.templateTokens()).containsExactly("server", "started");
    }

    @Test
    void preMaskedWildcard_remainsWildcard() {
        // Simulate caller having pre-masked "123" to <*>
        tree.process(List.of("User", WILDCARD, "failed"));
        DrainTree.ProcessResult r = tree.process(List.of("User", WILDCARD, "failed"));
        assertThat(r.templateTokens()).containsExactly("User", WILDCARD, "failed");
        assertThat(r.isLocked()).isTrue();
    }

    @Test
    void multipleWildcards_allDiscovered() {
        tree.process(List.of("paid", "10", "to", "user", "99"));
        DrainTree.ProcessResult r = tree.process(List.of("paid", "20", "to", "user", "77"));
        assertThat(r.templateTokens()).containsExactly("paid", WILDCARD, "to", "user", WILDCARD);
    }

    // — novel token discovery (the core Drain benefit) ——————

    @Test
    void novelPrefixedId_discoveredAsDynamic() {
        // req-755556 and req-123456 look like identifiers — not caught by regex,
        // but Drain learns they differ and marks the position as a wildcard.
        tree.process(List.of("Request", "req-755556", "processed"));
        DrainTree.ProcessResult r = tree.process(List.of("Request", "req-123456", "processed"));
        assertThat(r.isLocked()).isTrue();
        assertThat(r.templateTokens()).containsExactly("Request", WILDCARD, "processed");
    }

    @Test
    void novelSuffixedNum_discoveredAsDynamic() {
        tree.process(List.of("took", "27ms"));
        DrainTree.ProcessResult r = tree.process(List.of("took", "150ms"));
        assertThat(r.templateTokens()).containsExactly("took", WILDCARD);
    }

    // — dissimilar lines create separate clusters ————————————

    @Test
    void differentStaticToken_separateClusters() {
        // "failed" vs "succeeded" differ at the same position; sim = 2/4 = 0.5 (= threshold)
        // With simThreshold=0.5 they just barely match. Use stricter tree for this test.
        DrainTree strict = new DrainTree(0.6, 1, 100);
        strict.process(List.of("User", WILDCARD, "failed", "login"));
        DrainTree.ProcessResult r = strict.process(List.of("User", WILDCARD, "succeeded", "login"));
        // They should be separate clusters (no merge)
        assertThat(r.templateTokens()).containsExactly("User", WILDCARD, "succeeded", "login");
    }

    @Test
    void differentLength_separateClusters() {
        tree.process(List.of("a", "b", "c"));
        tree.process(List.of("a", "b", "c"));
        // Different length line should NOT match
        DrainTree.ProcessResult r = tree.process(List.of("a", "b"));
        assertThat(r.templateTokens()).containsExactly("a", "b");
    }

    // — bootstrap from DB ————————————————————————————————————

    @Test
    void bootstrap_existingTemplateRecognised() {
        Template t = new Template(1L, "User {num} failed login", 100L, Instant.now(), Instant.now());
        tree.bootstrap(List.of(t));

        // A line matching the bootstrapped template should be locked immediately
        DrainTree.ProcessResult r = tree.process(List.of("User", "42", "failed", "login"));
        assertThat(r.isLocked()).isTrue();
        assertThat(r.justLocked()).isFalse();  // already was locked via bootstrap
    }

    @Test
    void bootstrap_wildcardTokenConvertedCorrectly() {
        Template t = new Template(1L, "DB {num} timeout", 5L, Instant.now(), Instant.now());
        tree.bootstrap(List.of(t));

        DrainTree.ProcessResult r = tree.process(List.of("DB", "99", "timeout"));
        assertThat(r.templateTokens()).containsExactly("DB", WILDCARD, "timeout");
    }

    // — toWildcardTokens helper ——————————————————————————————

    @Test
    void toWildcardTokens_replacesTypedPlaceholders() {
        List<String> result = DrainTree.toWildcardTokens("User {num} paid {uuid}");
        assertThat(result).containsExactly("User", WILDCARD, "paid", WILDCARD);
    }

    @Test
    void toWildcardTokens_noPlaceholders_unchanged() {
        List<String> result = DrainTree.toWildcardTokens("server started");
        assertThat(result).containsExactly("server", "started");
    }
}
