package io.github.limuqy.mc.hassium.network.seedgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 投机创建 seed=0 实例在真实握手 seed 到达后的重建判定（不启 MinecraftServer）。
 */
class ShadowSeedRebuildTest {

    @Test
    void rebuildWhenRealSeedArrivesAfterSpeculativeCreate() {
        // 投机创建：assembledSeed=0；真实 seed 到达且 SeedGen 开 → 必须重建
        assertTrue(ShadowServerRegistry.shouldRebuildForSeed(0L, -731295678912345L, true));
    }

    @Test
    void noRebuildWhenSeedGenDisabled() {
        // SeedGen 关闭（服务端未开 / 客户端配置关）：arrivedSeed=0 或 enabled=false
        assertFalse(ShadowServerRegistry.shouldRebuildForSeed(0L, 0L, true));
        assertFalse(ShadowServerRegistry.shouldRebuildForSeed(0L, -12345L, false));
        assertFalse(ShadowServerRegistry.shouldRebuildForSeed(0L, 0L, false));
    }

    @Test
    void noRebuildWhenAssembledSeedMatches() {
        // R1/R2 复用路径：旧会话实例已按真实 seed 装配 → 相等不重建，缓存复用不受影响
        long real = -731295678912345L;
        assertFalse(ShadowServerRegistry.shouldRebuildForSeed(real, real, true));
    }
}
