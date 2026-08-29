package io.github.limuqy.mc.hassium.network.sectiondelta;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.github.limuqy.mc.hassium.network.sectiondelta.SectionPlaneSyndromeTest.air;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionDeltaPlannerTest {

    private static final int STONE = 1;

    @Test
    @DisplayName("1×2 巷道 → n=32，选 BLOCKS")
    void tunnelSelectsBlocks() {
        int[] client = air();
        int[] server = air();
        for (int z = 0; z < 16; z++) {
            server[SectionPlaneSyndrome.index(3, 4, z)] = STONE;
            server[SectionPlaneSyndrome.index(3, 5, z)] = STONE;
        }
        SectionDeltaPlanner.SectionDecision d = planOne(client, server);
        assertEquals(SectionDeltaPlanner.Kind.BLOCKS, d.kind());
        assertEquals(32, d.candidates().length);
    }

    @Test
    @DisplayName("9×9×9 炸坑 AABB n=729 ≥ 400 → FULL")
    void creeperAabbSelectsFull() {
        int[] client = air();
        int[] server = air();
        for (int y = 0; y < 9; y++) {
            for (int z = 0; z < 9; z++) {
                for (int x = 0; x < 9; x++) {
                    server[SectionPlaneSyndrome.index(x, y, z)] = STONE;
                }
            }
        }
        SectionDeltaPlanner.SectionDecision d = planOne(client, server);
        assertEquals(SectionDeltaPlanner.Kind.FULL, d.kind());
    }

    @Test
    @DisplayName("空↔非空 / 缺平面 → FULL")
    void airToNonAirIsFull() {
        int[] server = air();
        server[SectionPlaneSyndrome.index(0, 0, 0)] = STONE;
        SectionDeltaSnapshot client = padded(0L, null);
        SectionDeltaSnapshot serverSnap = padded(2L, SectionPlaneSyndrome.compute(server));
        SectionDeltaPlanner.SectionDecision d = SectionDeltaPlanner.plan(client, serverSnap).sections().get(0);
        assertEquals(SectionDeltaPlanner.Kind.FULL, d.kind());
    }

    @Test
    @DisplayName("16×1×16 铺平：列表 vs 单 palette 整段，走更小者")
    void flattenPicksSmallerEncoding() {
        int[] client = air();
        int[] server = air();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                server[SectionPlaneSyndrome.index(x, 3, z)] = STONE;
            }
        }
        SectionDeltaPlanner.SectionDecision d = planOne(client, server);
        assertEquals(SectionDeltaPlanner.Kind.BLOCKS, d.kind());
        assertEquals(256, d.candidates().length);

        int[] stateIds = new int[d.candidates().length];
        java.util.Arrays.fill(stateIds, STONE);
        byte[] tinyPalette = new byte[8];
        assertEquals(SectionDeltaPlanner.Kind.FULL,
                SectionDeltaPlanner.pickSmaller(d.candidates(), stateIds, tinyPalette));

        byte[] hugeFull = new byte[4096];
        assertEquals(SectionDeltaPlanner.Kind.BLOCKS,
                SectionDeltaPlanner.pickSmaller(d.candidates(), stateIds, hugeFull));
    }

    @Test
    @DisplayName("少量格子分散在各 section：不触发整柱回退")
    void sparseChangesAcrossSectionsDoNotFallbackWholeChunk() {
        int[][] clientPlanes = new int[4][];
        int[][] serverPlanes = new int[4][];
        for (int section = 0; section < 4; section++) {
            int[] client = air();
            int[] server = air();
            client[SectionPlaneSyndrome.index(1, 1, 1)] = STONE;
            server[SectionPlaneSyndrome.index(1, 1, 1)] = STONE + 1;
            clientPlanes[section] = SectionPlaneSyndrome.compute(client);
            serverPlanes[section] = SectionPlaneSyndrome.compute(server);
        }
        SectionDeltaPlanner.ChunkDecision decision = SectionDeltaPlanner.plan(
                new SectionDeltaSnapshot(new long[] {1L, 2L, 3L, 4L}, clientPlanes),
                new SectionDeltaSnapshot(new long[] {5L, 6L, 7L, 8L}, serverPlanes));
        assertFalse(decision.skipWholeChunk());
        assertEquals(SectionDeltaPlanner.Kind.BLOCKS, decision.sections().get(0).kind());
    }

    @Test
    @DisplayName("75% FULL section：触发整柱回退")
    void fullSectionsAt75PercentFallbackWholeChunk() {
        long[] server = {1L, 2L, 3L, 4L};
        java.util.List<SectionDeltaPlanner.SectionDecision> decisions = java.util.List.of(
                new SectionDeltaPlanner.SectionDecision(0, SectionDeltaPlanner.Kind.FULL, new int[0]),
                new SectionDeltaPlanner.SectionDecision(1, SectionDeltaPlanner.Kind.FULL, new int[0]),
                new SectionDeltaPlanner.SectionDecision(2, SectionDeltaPlanner.Kind.FULL, new int[0]),
                new SectionDeltaPlanner.SectionDecision(3, SectionDeltaPlanner.Kind.BLOCKS, new int[] {1}));
        assertTrue(SectionDeltaPlanner.shouldFallbackFullChunk(decisions, server));
    }

    @Test
    @DisplayName("无服务端哈希：不预判回退")
    void emptyServerHashesDoNotPreempt() {
        assertFalse(SectionDeltaPlanner.shouldFallbackFullChunk(java.util.List.of(), new long[0]));
        assertFalse(SectionDeltaPlanner.shouldFallbackFullChunk(java.util.List.of(), null));
    }


    private static SectionDeltaPlanner.SectionDecision planOne(int[] clientCells, int[] serverCells) {
        return SectionDeltaPlanner.plan(
                padded(1L, SectionPlaneSyndrome.compute(clientCells)),
                padded(2L, SectionPlaneSyndrome.compute(serverCells)))
                .sections().get(0);
    }

    /** 垫 3 个未变更非空段，避免单段变更触发 75% 整柱回退。 */
    private static SectionDeltaSnapshot padded(long dirtyHash, int[] dirtyPlanes) {
        return new SectionDeltaSnapshot(
                new long[] {dirtyHash, 10L, 11L, 12L},
                new int[][] {dirtyPlanes, null, null, null});
    }
}
