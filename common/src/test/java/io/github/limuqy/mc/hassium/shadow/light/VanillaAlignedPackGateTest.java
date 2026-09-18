package io.github.limuqy.mc.hassium.shadow.light;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 原版语义整柱交付门：isLightCorrect + 本会话 Promote/LIGHT POST。
 * （chunk 判据需 MC 实例，此处只测 LightNeighborhoodGate.promoted 记账。）
 */
class VanillaAlignedPackGateTest {

    @AfterEach
    void tearDown() {
        LightNeighborhoodGate.clear();
    }

    @Test
    @DisplayName("tryPromote 前 wasPromoted=false；clear 后清空")
    void promotedTrackedOnGate() {
        long key = io.github.limuqy.mc.hassium.utils.DimensionKey.key(
                "minecraft:overworld", -13, 3);
        assertFalse(LightNeighborhoodGate.wasPromoted(key));
        assertFalse(LightNeighborhoodGate.wasPromoted("minecraft:overworld",
                new net.minecraft.world.level.ChunkPos(-13, 3)));
        LightNeighborhoodGate.clear();
        assertFalse(LightNeighborhoodGate.wasPromoted(key));
    }
}
