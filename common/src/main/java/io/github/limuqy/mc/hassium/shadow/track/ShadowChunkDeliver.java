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
        // 远离视距 + 余量外不投递；边界内仍投，原版可 Ignore 更远柱
        if (!renderOnly && !ShadowTrackingSession.isDeliverableToClient(pos.x, pos.z)) {
            DebugLogger.info(DebugLogger.LogType.CHUNK_APPLY,
                    "[SHADOW_DELIVER] skip beyond view+margin ({}, {}) dim={}",
                    pos.x, pos.z, dimension);
            return false;
        }
        // 【2026-09-19】原此处另有一道 `SeedGenCompareGate.isAwaiting` 检查，已删：
        // `publishCachedChunk` 内的 `blockClientDelivery` 就是 `isAwaiting` 本身
        // （见 SeedGenCompareGate.blockClientDelivery），同一判据查两遍。
        // 专用服语义：本地已有柱 = 服务端已 load → 直接投递客户端。
        return ShadowLightCompute.publishCachedChunk(dimension, pos, localGeneration, renderOnly);
    }
}

