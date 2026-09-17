package io.github.limuqy.mc.hassium.shadow.track;

import io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute;

import io.github.limuqy.mc.hassium.utils.DebugLogger;
import net.minecraft.world.level.ChunkPos;

/**
 * 统一交付客户端出口（阶段 A：本地缓存/注入柱 → 影子算光 → ready → drainReady）。
 * <p>
 * 网络 FULL/DELTA 落地仍走 {@code ShadowLightCompute.submit*}（接收侧）；
 * 本类只收口「影子端已有柱、要把数据推给真实客户端」的唯一入口，
 * 避免 publish / OVD / redeliver 各自绕过窗口判定。
 * <p>
 * OVD 冻结：窗外非 renderOnly 一律不交付。
 */
public final class ShadowChunkDeliver {

    private ShadowChunkDeliver() {}

    /**
     * 本地源交付（缓存命中 / 磁盘 / 注入 redeliver）。
     * <p>
     * 重入（客户端无落地凭据 + 有基线 + compare 路径可用）时改为 compare-pull，
     * 不在此盲 publish；缓存命中由 UNCHANGED 响应侧记账。
     *
     * @param localGeneration true = SeedGen 产物（不计权威缓存全命中）
     * @param renderOnly      true = OVD 环带（冻结期调用方应避免）
     * @return 是否成功进入 ready 管线（含异步读盘已排队）或已由 reentry-compare 接管
     */
    public static boolean deliverLocal(String dimension, ChunkPos pos,
                                       boolean localGeneration, boolean renderOnly) {
        if (dimension == null || pos == null) {
            return false;
        }
        if (!renderOnly && !ShadowTrackingSession.isDeliverableToClient(pos.x, pos.z)) {
            DebugLogger.info(DebugLogger.LogType.CHUNK_APPLY,
                    "[SHADOW_DELIVER] skip out-of-window ({}, {}) dim={}",
                    pos.x, pos.z, dimension);
            return false;
        }
        if (!renderOnly && !localGeneration
                && ShadowTrackingSession.getInstance()
                        .tryReentryCompare(dimension, pos, "deliver")) {
            return true;
        }
        return ShadowLightCompute.publishCachedChunk(dimension, pos, localGeneration, renderOnly);
    }

    /** OVD 窗本地源（冻结：仅保留兼容入口，默认配置应关 OVD）。 */
    public static boolean deliverOvd(String dimension, ChunkPos pos) {
        if (dimension == null || pos == null) {
            return false;
        }
        if (!ShadowTrackingSession.isDeliverableToClient(pos.x, pos.z)) {
            return false;
        }
        return ShadowLightCompute.publishOvdCachedChunk(dimension, pos);
    }

    /**
     * 窗内 compare 保鲜：本地已交付后向真服比对（§3.2 保新鲜；防抖在 ShadowLightCompute）。
     * 重入交付请走 {@link ShadowTrackingSession#tryReentryCompare}（交付前 compare）。
     */
    public static void refreshCompare(String dimension, ChunkPos pos) {
        if (dimension == null || pos == null) {
            return;
        }
        if (ShadowTrackingSession.getInstance().tryReentryCompare(dimension, pos, "refresh")) {
            return;
        }
        if (!ShadowLightCompute.tryRequestMiss(dimension, pos)) {
            return;
        }
        if (!ShadowTrackingSession.getInstance()
                .markPullInFlightForAcquire(dimension, pos, System.currentTimeMillis())) {
            return;
        }
        ShadowChunkAcquire.pullOne(dimension, pos, true);
    }
}
