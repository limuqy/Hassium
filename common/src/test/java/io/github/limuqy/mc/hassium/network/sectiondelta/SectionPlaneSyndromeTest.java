package io.github.limuqy.mc.hassium.network.sectiondelta;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionPlaneSyndromeTest {

    private static final int STONE = 1;

    @Test
    @DisplayName("1×2 巷道沿 Z 贯通 → n=32")
    void tunnel1x2Produces32Candidates() {
        int[] client = air();
        int[] server = air();
        for (int z = 0; z < 16; z++) {
            server[SectionPlaneSyndrome.index(3, 4, z)] = STONE;
            server[SectionPlaneSyndrome.index(3, 5, z)] = STONE;
        }
        int[] candidates = SectionPlaneSyndrome.candidates(
                SectionPlaneSyndrome.compute(client), SectionPlaneSyndrome.compute(server));
        assertEquals(32, candidates.length);
    }

    @Test
    @DisplayName("1×k×1 柱 → n=k")
    void column1xk1ProducesKCandidates() {
        int k = 7;
        int[] client = air();
        int[] server = air();
        for (int y = 0; y < k; y++) {
            server[SectionPlaneSyndrome.index(2, y, 8)] = STONE;
        }
        int[] candidates = SectionPlaneSyndrome.candidates(
                SectionPlaneSyndrome.compute(client), SectionPlaneSyndrome.compute(server));
        assertEquals(k, candidates.length);
    }

    @Test
    @DisplayName("9×9×9 炸坑 AABB → n=729")
    void creeperAabbProduces729Candidates() {
        int[] client = air();
        int[] server = air();
        for (int y = 0; y < 9; y++) {
            for (int z = 0; z < 9; z++) {
                for (int x = 0; x < 9; x++) {
                    server[SectionPlaneSyndrome.index(x, y, z)] = STONE;
                }
            }
        }
        int[] candidates = SectionPlaneSyndrome.candidates(
                SectionPlaneSyndrome.compute(client), SectionPlaneSyndrome.compute(server));
        assertEquals(729, candidates.length);
        assertTrue(candidates.length >= SectionDeltaPlanner.AABB_FULL_THRESHOLD);
    }

    @Test
    @DisplayName("peekBlockListCount 读 BLOCKS 开头 VarInt")
    void peekBlockListCountReadsLeadingVarInt() {
        int[] pos = {SectionPlaneSyndrome.packLocalPos(1, 2, 3)};
        byte[] payload = SectionPlaneSyndrome.encodeBlockList(pos, new int[] {7});
        assertEquals(1, SectionPlaneSyndrome.peekBlockListCount(payload));
        byte[] thirtyTwo = SectionPlaneSyndrome.encodeBlockList(new int[32], new int[32]);
        assertEquals(32, SectionPlaneSyndrome.peekBlockListCount(thirtyTwo));
        assertEquals(-1, SectionPlaneSyndrome.peekBlockListCount(new byte[0]));
        assertEquals(-1, SectionPlaneSyndrome.peekBlockListCount(null));
    }

    @Test
    @DisplayName("相同内容 → 无脏轴")
    void identicalCellsProduceNoCandidates() {
        int[] cells = air();
        cells[SectionPlaneSyndrome.index(1, 2, 3)] = STONE;
        int[] planes = SectionPlaneSyndrome.compute(cells);
        int[] candidates = SectionPlaneSyndrome.candidates(planes, planes);
        assertEquals(0, candidates.length);
    }

    static int[] air() {
        return new int[SectionPlaneSyndrome.CELLS];
    }
}
