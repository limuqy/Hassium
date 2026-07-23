package io.github.limuqy.mc.hassium.concurrent;

import net.minecraft.world.level.ChunkPos;
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
    @DisplayName("renderOnly 永远低于同位置权威块")
    void renderOnlyAlwaysAfterAuthoritative() {
        ChunkPos pos = new ChunkPos(3, -7);
        double auth = ChunkDistancePriority.authoritativeFromWorld(pos, 48.0, -112.0);
        double ovd = ChunkDistancePriority.renderOnlyFromWorld(pos, 48.0, -112.0);
        assertTrue(auth < ovd);
        // 极远权威仍应优于极近 OVD
        ChunkPos farAuth = new ChunkPos(400, 400);
        double farAuthPri = ChunkDistancePriority.authoritative(farAuth, 0, 0);
        double nearOvd = ChunkDistancePriority.renderOnly(new ChunkPos(0, 0), 0, 0);
        assertTrue(farAuthPri < nearOvd,
                "tier bias must dominate: farAuth=" + farAuthPri + " nearOvd=" + nearOvd);
    }

    @Test
    @DisplayName("层序：权威 < 未知任务 < 环带（数值越小越优先）")
    void tierOrderAuthoritativeThenUnknownThenRenderOnly() {
        double farAuth = ChunkDistancePriority.authoritative(new ChunkPos(400, 400), 0, 0);
        double unknown = ChunkDistancePriority.unknown();
        double nearOvd = ChunkDistancePriority.renderOnly(new ChunkPos(0, 0), 0, 0);
        assertTrue(farAuth < unknown, "auth < unknown: farAuth=" + farAuth + " unknown=" + unknown);
        assertTrue(unknown < nearOvd, "unknown < ovd: unknown=" + unknown + " nearOvd=" + nearOvd);

        // 坐标未知时仅 base：权威 base < 未知 base < 环带 base
        assertTrue(ChunkDistancePriority.ofUnknownDistance(ChunkDistancePriority.Tier.AUTHORITATIVE)
                < ChunkDistancePriority.unknown());
        assertTrue(ChunkDistancePriority.unknown()
                < ChunkDistancePriority.ofUnknownDistance(ChunkDistancePriority.Tier.RENDER_ONLY));
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
