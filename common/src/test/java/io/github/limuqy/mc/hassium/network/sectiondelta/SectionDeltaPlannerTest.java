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
    @DisplayName("75% 柱回退：3/4 非空变更 → skipped；1/4 → 不回退")
    void chunkFallbackAt75Percent() {
        long[] server = {1L, 2L, 3L, 4L};
        long[] client75 = {0L, 2L, 0L, 0L};
        assertTrue(SectionDeltaPlanner.shouldFallbackFullChunk(client75, server));

        long[] client25 = {1L, 2L, 0L, 4L};
        assertFalse(SectionDeltaPlanner.shouldFallbackFullChunk(client25, server));

        SectionDeltaSnapshot clientSnap = new SectionDeltaSnapshot(client75, new int[4][]);
        SectionDeltaSnapshot serverSnap = new SectionDeltaSnapshot(server, new int[4][]);
        assertTrue(SectionDeltaPlanner.plan(clientSnap, serverSnap).skipWholeChunk());
    }

    @Test
    @DisplayName("无服务端哈希 → 不预判回退")
    void emptyServerHashesDoNotPreempt() {
        assertFalse(SectionDeltaPlanner.shouldFallbackFullChunk(new long[] {1L}, new long[0]));
        assertFalse(SectionDeltaPlanner.shouldFallbackFullChunk(new long[] {1L}, null));
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
