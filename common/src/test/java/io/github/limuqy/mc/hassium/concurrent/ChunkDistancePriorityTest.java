package io.github.limuqy.mc.hassium.concurrent;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChunkDistancePriority} 分层 + distSq 冻结键回归。
 */
class ChunkDistancePriorityTest {

#if MC_VER >= MC_1_21_2
    // 1.21.2+ ChunkPos.<clinit> 经 ChunkPyramid → ChunkStatus.register 触碰 BuiltInRegistries，
    // 而 BuiltInRegistries.internalRegister 各版本均调 Bootstrap.checkBootstrapCalled → gradle test
    // 未 bootstrap 直接 ExceptionInInitializerError。1.20.1/1.21.1 的 ChunkPos 无此链条，无需前置。
    @BeforeAll
    static void bootstrapRegistries() {
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
    }
#endif

    @Test
    @DisplayName("同层：近处 distSq 小于远处")
    void nearerHasSmallerDistSq() {
        ChunkPos near = new ChunkPos(0, 0);
        ChunkPos far = new ChunkPos(10, 0);
        double pcx = 0.5;
        double pcz = 0.5;
        assertTrue(ChunkDistancePriority.distSq(near, pcx, pcz)
                < ChunkDistancePriority.distSq(far, pcx, pcz));
        assertTrue(ChunkDistancePriority.authoritative(near, pcx, pcz)
                < ChunkDistancePriority.authoritative(far, pcx, pcz));
    }


    @Test
    @DisplayName("世界坐标与 chunk 分式坐标一致")
    void worldAndChunkCoordsMatch() {
        ChunkPos pos = new ChunkPos(6, -37);
        double worldX = 6 * 16 + 8.0;
        double worldZ = -37 * 16 + 8.0;
        double fromWorld = ChunkDistancePriority.authoritativeFromWorld(pos, worldX, worldZ);
        double fromChunks = ChunkDistancePriority.authoritative(pos, worldX / 16.0, worldZ / 16.0);
        assertEquals(fromChunks, fromWorld, 1e-9);
    }

    @Test
    @DisplayName("中心 chunk 整数 distSq 可用于 resync 排序")
    void centerDistSqSortsNearFirst() {
        int cx = 10;
        int cz = 20;
        List<ChunkPos> list = new ArrayList<>(List.of(
                new ChunkPos(cx + 5, cz),
                new ChunkPos(cx, cz),
                new ChunkPos(cx + 1, cz + 1)
        ));
        list.sort(Comparator.comparingDouble(p -> ChunkDistancePriority.distSq(p, cx, cz)));
        assertEquals(new ChunkPos(cx, cz), list.get(0));
        assertEquals(new ChunkPos(cx + 1, cz + 1), list.get(1));
        assertEquals(new ChunkPos(cx + 5, cz), list.get(2));
    }

    @Test
    @DisplayName("null / 非法入口返回 LOWEST")
    void nullYieldsLowest() {
        assertEquals(ChunkDistancePriority.LOWEST,
                ChunkDistancePriority.of(null, new ChunkPos(0, 0), 0, 0));
        assertEquals(ChunkDistancePriority.LOWEST,
                ChunkDistancePriority.of(ChunkDistancePriority.Tier.AUTHORITATIVE, null, 0, 0));
    }

    @Test
    @DisplayName("TIER_BIAS 大于极大视距 distSq")
    void tierBiasDominatesHugeViewDistance() {
        // 半径 2048 chunk 的对角 distSq
        double huge = 2.0 * 2048 * 2048;
        assertTrue(huge < ChunkDistancePriority.TIER_BIAS);
        // 两层间隔也能盖住最大 distSq（权威最远 < 未知；未知 < 环带最近）
        assertTrue(huge < ChunkDistancePriority.TIER_BIAS);
    }
}
