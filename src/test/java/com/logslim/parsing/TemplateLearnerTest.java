package com.logslim.parsing;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateLearnerTest {

    private final TemplateLearner learner = new TemplateLearner(4);
    private final EntityMasker masker = new EntityMasker();

    // — merge vs split: the core decision ————————————————————————

    @Test
    void highCardinalityPositionBecomesVar() {
        List<String> patterns = IntStream.range(0, 10)
                .mapToObj(i -> "session sess-" + "abcdefghij".charAt(i) + "7x opened")
                .collect(Collectors.toList());
        Map<String, TemplateLearner.MergePlan> plans = learner.learn(patterns);
        assertThat(plans).hasSize(10);
        assertThat(plans.values().stream().map(TemplateLearner.MergePlan::finalPattern))
                .containsOnly("session {var} opened");
    }

    @Test
    void lowCardinalityVariationStaysSeparate() {
        // succeeded/failed is signal, not a variable — 2 ≤ maxBranching → no merge
        List<String> patterns = List.of(
                "Verification succeeded for blk_{num}",
                "Verification failed for blk_{num}");
        assertThat(learner.learn(patterns)).isEmpty();
    }

    @Test
    void thresholdBoundary() {
        // exactly maxBranching distinct values → split; one more → merge
        List<String> four = IntStream.range(0, 4)
                .mapToObj(i -> "worker w" + "qrst".charAt(i) + "9z stopped").toList();
        assertThat(learner.learn(four)).isEmpty();

        List<String> five = IntStream.range(0, 5)
                .mapToObj(i -> "worker w" + "qrstu".charAt(i) + "9z stopped").toList();
        assertThat(learner.learn(five).values())
                .extracting(TemplateLearner.MergePlan::finalPattern)
                .containsOnly("worker {var} stopped");
    }

    @Test
    void recursionSplitsSemanticsThenMergesIds() {
        // 2 actions × 10 users: split on the action, merge the user position
        List<String> patterns = new ArrayList<>();
        for (String action : List.of("login", "logout")) {
            for (int i = 0; i < 10; i++) {
                patterns.add("user u" + "abcdefghij".charAt(i) + "7q did " + action);
            }
        }
        Map<String, TemplateLearner.MergePlan> plans = learner.learn(patterns);
        Set<String> finals = plans.values().stream()
                .map(TemplateLearner.MergePlan::finalPattern).collect(Collectors.toSet());
        assertThat(finals).containsExactlyInAnyOrder(
                "user {var} did login",
                "user {var} did logout");
    }

    @Test
    void keyValuePrefixSurvivesTheMerge() {
        List<String> patterns = IntStream.range(0, 10)
                .mapToObj(i -> "request done status=S" + "abcdefghij".charAt(i) + "w")
                .collect(Collectors.toList());
        Map<String, TemplateLearner.MergePlan> plans = learner.learn(patterns);
        assertThat(plans.values())
                .extracting(TemplateLearner.MergePlan::finalPattern)
                .containsOnly("request done status={var}");
    }

    @Test
    void deterministicRegardlessOfInputOrder() {
        List<String> patterns = new ArrayList<>();
        for (int i = 0; i < 12; i++) patterns.add("node host-" + "abcdefghijkl".charAt(i) + "3 up");
        Map<String, TemplateLearner.MergePlan> a = learner.learn(patterns);
        Collections.reverse(patterns);
        Map<String, TemplateLearner.MergePlan> b = learner.learn(patterns);
        assertThat(a.keySet()).isEqualTo(b.keySet());
        for (String k : a.keySet()) {
            assertThat(a.get(k).finalPattern()).isEqualTo(b.get(k).finalPattern());
        }
    }

    // — param building keeps the round-trip byte-exact ————————————————

    @Test
    void buildParamsRoundTripsThroughMergedTemplate() {
        // var token contains its own universal slots: the whole token becomes one param
        List<String> lines = IntStream.range(0, 6)
                .mapToObj(i -> "conn 10.0.0." + (i + 1) + ":sess-q" + "abcdef".charAt(i) + "z up")
                .toList();
        List<String> patterns = lines.stream().map(l -> masker.mask(l).pattern()).toList();
        Map<String, TemplateLearner.MergePlan> plans = learner.learn(patterns);

        for (String line : lines) {
            EntityMasker.MaskResult r = masker.mask(line);
            TemplateLearner.MergePlan plan = plans.get(r.pattern());
            assertThat(plan).as("plan for %s", r.pattern()).isNotNull();
            List<String> params = TemplateLearner.buildParams(r.pattern(), r.params(), plan);
            assertThat(EntityMasker.unmask(plan.finalPattern(), params))
                    .as("round-trip for: %s", line)
                    .isEqualTo(line);
        }
    }

    @Test
    void buildParamsKeepsPassThroughSlotsInOrder() {
        List<String> lines = IntStream.range(0, 6)
                .mapToObj(i -> "put block id-w" + "abcdef".charAt(i) + "7k from 10.0.0." + (i + 1)
                        + " took 5" + i + "ms")
                .toList();
        List<String> patterns = lines.stream().map(l -> masker.mask(l).pattern()).toList();
        Map<String, TemplateLearner.MergePlan> plans = learner.learn(patterns);

        EntityMasker.MaskResult r = masker.mask(lines.get(0));
        TemplateLearner.MergePlan plan = plans.get(r.pattern());
        assertThat(plan).isNotNull();
        List<String> params = TemplateLearner.buildParams(r.pattern(), r.params(), plan);
        // slots: {var}=id-wak, {ip}=10.0.0.1, {size}=50ms
        assertThat(params).containsExactly("id-wa7k", "10.0.0.1", "50ms");
        assertThat(EntityMasker.unmask(plan.finalPattern(), params)).isEqualTo(lines.get(0));
    }

    // — matching new patterns against learned templates ————————————————

    @Test
    void learnedTemplateMatchesUnseenSibling() {
        List<String> patterns = IntStream.range(0, 10)
                .mapToObj(i -> "gc pause pool-p" + "abcdefghij".charAt(i) + "7m freed")
                .collect(Collectors.toList());
        TemplateLearner.MergePlan plan = learner.learn(patterns).values().iterator().next();
        TemplateLearner.LearnedTemplate lt = new TemplateLearner.LearnedTemplate(plan);

        assertThat(lt.tryMatch(TemplateLearner.split("gc pause pool-NEVERSEEN7 freed")))
                .isSameAs(plan);
        assertThat(lt.tryMatch(TemplateLearner.split("gc pause pool-x7 resumed"))).isNull();
        assertThat(lt.tryMatch(TemplateLearner.split("gc pause freed"))).isNull();
    }

    @Test
    void learnedTemplateEnforcesKeyPrefix() {
        List<String> patterns = IntStream.range(0, 10)
                .mapToObj(i -> "req done status=S" + "abcdefghij".charAt(i) + "w")
                .collect(Collectors.toList());
        TemplateLearner.MergePlan plan = learner.learn(patterns).values().iterator().next();
        TemplateLearner.LearnedTemplate lt = new TemplateLearner.LearnedTemplate(plan);

        assertThat(lt.tryMatch(TemplateLearner.split("req done status=UNSEEN"))).isSameAs(plan);
        assertThat(lt.tryMatch(TemplateLearner.split("req done other=X"))).isNull();
    }

    // — escaped-brace helpers ————————————————————————————————————

    @Test
    void slotCountUnderstandsEscapedBraces() {
        assertThat(TemplateLearner.slotCount("{ip}:sess-{num}")).isEqualTo(2);
        assertThat(TemplateLearner.slotCount("{{\"n\":{num}}}")).isEqualTo(1);
        assertThat(TemplateLearner.slotCount("plain")).isZero();
        assertThat(TemplateLearner.slotCount("{{}}")).isZero();
    }
}
