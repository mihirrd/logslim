package com.logslim.parsing;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EntityMaskerTest {

    private final EntityMasker masker = new EntityMasker();

    private void assertRoundTrip(String line) {
        EntityMasker.MaskResult r = masker.mask(line);
        assertThat(EntityMasker.unmask(r.pattern(), r.params()))
                .as("lossless round-trip for: %s", line)
                .isEqualTo(line);
    }

    // — losslessness is the non-negotiable invariant ——————————————————

    @Test
    void roundTrips_realHdfsLines() {
        assertRoundTrip("081109 203518 143 INFO dfs.DataNode$DataXceiver: Receiving block "
                + "blk_-1608999687919862906 src: /10.250.19.102:54106 dest: /10.250.19.102:50010");
        assertRoundTrip("081109 213849 2471 WARN dfs.DataNode$DataXceiver: "
                + "10.251.71.68:50010:Got exception while serving blk_-8781759536960110370 to /10.250.17.225:");
        assertRoundTrip("081109 203518 35 INFO dfs.FSNamesystem: BLOCK* NameSystem.allocateBlock: "
                + "/mnt/hadoop/mapred/system/job_200811092030_0001/job.jar. blk_-1608999687919862906");
    }

    @Test
    void roundTrips_jsonLogWithLiteralBraces() {
        assertRoundTrip("2024-01-15 level=INFO msg={\"user\": 42, \"path\": \"/a/b\"}");
        assertRoundTrip("an empty object {} and a pair {x:1}");
    }

    @Test
    void roundTrips_lineWithNoEntities() {
        assertRoundTrip("INFO service started successfully");
    }

    // — intra-token masking is what Drain could not do ————————————————

    @Test
    void masksEntityGluedMidToken() {
        EntityMasker.MaskResult r = masker.mask("10.251.71.68:50010:Got exception");
        assertThat(r.pattern()).isEqualTo("{ip}:Got exception");
        assertThat(r.params()).containsExactly("10.251.71.68:50010");
    }

    @Test
    void masksBlockIdNumberWithoutDatasetSpecificRule() {
        // No HDFS-specific rule: the generic {num} entity absorbs the numeric part,
        // so every distinct block ID still collapses to the same template.
        EntityMasker.MaskResult r = masker.mask("Deleting block blk_-160 done");
        assertThat(r.pattern()).isEqualTo("Deleting block blk_{num} done");
        assertThat(r.params()).containsExactly("-160");
    }

    @Test
    void literalBracesAreDoubledInPattern() {
        EntityMasker.MaskResult r = masker.mask("msg={\"n\": 42}");
        // braces doubled, the number masked
        assertThat(r.pattern()).isEqualTo("msg={{\"n\": {num}}}");
        assertThat(r.params()).containsExactly("42");
    }

    // — the headline: variable values collapse to ONE template ————————

    @Test
    void blockIdVariantsCollapseToOneTemplate() {
        List<String> lines = List.of(
            "081109 203518 143 INFO x: Receiving block blk_-1608999687919862906 src: /10.250.19.102:54106",
            "081109 203519 143 INFO x: Receiving block blk_7503483334202473044 src: /10.250.10.6:40524",
            "081110 010101 999 INFO x: Receiving block blk_-933600745359216493 src: /10.251.91.229:11111"
        );
        Set<String> templates = new LinkedHashSet<>();
        for (String l : lines) {
            EntityMasker.MaskResult r = masker.mask(l);
            templates.add(r.pattern());
            assertThat(EntityMasker.unmask(r.pattern(), r.params())).isEqualTo(l);
        }
        assertThat(templates).hasSize(1);
        assertThat(templates.iterator().next())
                .isEqualTo("{num} {num} {num} INFO x: Receiving block blk_{num} src: /{ip}");
    }
}
