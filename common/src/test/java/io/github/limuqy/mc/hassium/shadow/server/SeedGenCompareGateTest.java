package io.github.limuqy.mc.hassium.shadow.server;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方案 A：seedGen 权威窗 compare 前禁止客户端交付。
 */
class SeedGenCompareGateTest {

    @AfterEach
    void tearDown() {
        SeedGenCompareGate.clearAll();
    }

    @Test
    @DisplayName("mark 后 blockClientDelivery 为 true；clear 后放行")
    void markBlocksUntilCleared() {
        ChunkPos pos = new ChunkPos(3, 5);
        assertFalse(SeedGenCompareGate.blockClientDelivery("minecraft:overworld", pos.x, pos.z));

        SeedGenCompareGate.mark("minecraft:overworld", pos);
        assertTrue(SeedGenCompareGate.isAwaiting("minecraft:overworld", pos));
        assertTrue(SeedGenCompareGate.blockClientDelivery("minecraft:overworld", 3, 5));
        // 其它柱不受影响
        assertFalse(SeedGenCompareGate.blockClientDelivery("minecraft:overworld", 4, 5));
        assertFalse(SeedGenCompareGate.blockClientDelivery("minecraft:the_nether", 3, 5));

        SeedGenCompareGate.clear("minecraft:overworld", pos);
        assertFalse(SeedGenCompareGate.blockClientDelivery("minecraft:overworld", 3, 5));
    }

    @Test
    @DisplayName("会话 reset / 切维 clearAll 清空全部 awaiting")
    void clearAllEmptiesTable() {
        SeedGenCompareGate.mark("minecraft:overworld", new ChunkPos(0, 0));
        SeedGenCompareGate.mark("minecraft:the_nether", new ChunkPos(1, 1));
        assertTrue(SeedGenCompareGate.size() >= 2);

        SeedGenCompareGate.clearAll();
        assertFalse(SeedGenCompareGate.isAwaiting("minecraft:overworld", 0, 0));
        assertFalse(SeedGenCompareGate.isAwaiting("minecraft:the_nether", 1, 1));
    }

    @Test
    @DisplayName("null 维度/坐标不写表、不误拦")
    void nullArgsAreNoOps() {
        SeedGenCompareGate.mark(null, new ChunkPos(1, 1));
        SeedGenCompareGate.mark("minecraft:overworld", null);
        assertFalse(SeedGenCompareGate.blockClientDelivery(null, 1, 1));
        assertFalse(SeedGenCompareGate.blockClientDelivery("minecraft:overworld", 1, 1));
    }
}
