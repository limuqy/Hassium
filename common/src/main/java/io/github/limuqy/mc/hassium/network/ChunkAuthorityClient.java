package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.LevelCompat;
import io.github.limuqy.mc.hassium.shadow.track.ShadowChunkAcquire;
import io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;

/**
 * 权威边沿消费端（客户端）——**选柱提示**，不是独立加载驱动。
 * <p>
 * 服务端 {@code chunk_authority_s2c} 声明 tracking 域内柱。客户端收到后：
 * <ul>
 *   <li>影子端已有该柱（injected 非占位）→ 跳过</li>
 *   <li>正在 pull 在途 → 跳过（与 tracking/sweep 选柱互斥）</li>
 *   <li>失败冷却未过 → 跳过</li>
 *   <li>否则触发 {@link ShadowChunkAcquire} §3.2 选柱（compare / 权威 FULL）</li>
 * </ul>
 * 不做 hash 零请求、不抑制影子自绘选柱、不参与出票。取块交付仍走统一
 * ShadowPull / 缓存 publish 管线。
 */
public final class ChunkAuthorityClient {

    /** 退役常量：看门狗窗口（让位门已不存在，仅为兼容引用保留）。 */
    public static final long AUTHORITY_WATCHDOG_MS = 10_000L;

    private static final java.util.concurrent.atomic.AtomicLong AUTHORITY_ACQUIRE_COUNT =
            new java.util.concurrent.atomic.AtomicLong();

    private ChunkAuthorityClient() {}

    /**
     * 影子端是否应停止发出 pull 请求。
     * <p>
     * 恒 {@code false}：权威包只做选柱提示，不接管采集。
     */
    public static boolean pullEmissionSuppressed() {
        return false;
    }

    /** 客户端维度变更：无权威状态可清（兼容调用方）。 */
    public static void onClientDimensionChanged() {
        // no-op
    }

    /** 该柱是否在本会话被权威声明覆盖过（齐套门诊断可选；当前不维护集合，恒 false）。 */
    public static boolean isAuthoritative(String dimension, int chunkX, int chunkZ) {
        return false;
    }

    /**
     * 权威 enter：S0 整族降级后 no-op（协商位已关，正常收不到包）。
     */
    public static void handle(ChunkAuthorityS2CPacket packet) {
        // Authority family demoted (S0 shadow-as-dedicated-server)
    }

    public static long authorityAcquireCount() {
        return AUTHORITY_ACQUIRE_COUNT.get();
    }
}
