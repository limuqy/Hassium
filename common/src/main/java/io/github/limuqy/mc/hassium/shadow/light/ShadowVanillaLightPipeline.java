package io.github.limuqy.mc.hassium.shadow.light;

import io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer;
import io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry;
import io.github.limuqy.mc.hassium.shadow.server.ShadowChunkPersistenceRole;
import io.github.limuqy.mc.hassium.shadow.track.ShadowTrackingSession;
import io.github.limuqy.mc.hassium.shadow.track.ShadowChunkSource;

import io.github.limuqy.mc.hassium.platform.client.TraceOrigin;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;

/**
 * 区块来源统一进入影子端 pre-LIGHT 管线。
 *
 * <p>不再维护 halo；范围和生命周期由影子 ServerPlayer/ChunkMap 决定。</p>
 */
public final class ShadowVanillaLightPipeline {
    private ShadowVanillaLightPipeline() {}

    public static void submitVisible(String dimension, ChunkPos pos,
                                     ClientboundLevelChunkWithLightPacket packet,
                                     TraceOrigin traceOrigin) {
        submit(dimension, pos, packet, ShadowChunkSource.REMOTE_FULL,
                traceOrigin == null ? TraceOrigin.SERVER_PUSH : traceOrigin);
    }

    /** 影子端尚未装配时不把一次竞态误判为永久失败。 */
    public static boolean shouldFailShadowWhenServerUnavailable() {
        return false;
    }

    /** 单柱 inject 失败不得关整台影子端：真服仍抑制原版区块包，关引擎后切维世界会空。 */
    public static boolean shouldFailShadowOnInjectFailure() {
        return false;
    }

    private static void submit(String dimension, ChunkPos pos,
                               ClientboundLevelChunkWithLightPacket packet,
                               ShadowChunkSource source, TraceOrigin origin) {
        if (pos == null || packet == null) {
            return;
        }
        ShadowSeedServer server = ShadowServerRegistry.getInstance().getOrCreate();
        if (server == null) {
            if (shouldFailShadowWhenServerUnavailable()) {
                ShadowServerRegistry.getInstance().failShadowServer();
                return;
            }
            ShadowLightCompute.submitVisible(dimension, pos, packet);
            return;
        }
        String resolvedDimension = dimension == null ? currentDimension() : dimension;
        // 已有正确光的柱：跳过 injectChunk（会清光）。
        // R2 remote_pull 重注入不得重置已修正的光（否则清光后须重算再重交付 = 黑块窗口）。
        // 完整层 → publishCachedChunk；仅 isLightCorrect 但层未齐 → 只补光屏障，
        // 禁止 inject（清光）或 REUSE 空包（整柱抹光）。
        net.minecraft.world.level.chunk.LevelChunk existing =
                server.injectedChunk(resolvedDimension, pos.x, pos.z);
        if (existing != null && existing.isLightCorrect()) {
            // 回归原版：有引擎光且可复用则直接 publish；不再等齐套 promote。
            if (ShadowLightCompute.isLightReusable(server, pos, existing)) {
                // 【2026-09-20 同柱双路交付闸（用户拍板）】客户端已按影子端内容落地过该柱
                // （{@code shadowApplyEpochs} 落地凭据在）→ 原版 tracking 再推同一柱时**不得重复注入**：
                // 重复注入白吃 drainReady 主线程 apply 预算，且旧口径下被误记为缓存命中。
                // 该闸此前只挂在 tracking 泵（ShadowTrackingSession），本快路径漏了——
                // 实测 1.20.1 R1 每柱被交付 2~5 次（origin 序列 REMOTE_PULL + MEMORY_CACHE×N）。
                // 记账/形状扫描在途照旧，只跳过交付。
                if (ShadowLightCompute.hasClientApplyEpoch(resolvedDimension, pos)) {
                    SmokeChunkTrace.recordShadowInjected(resolvedDimension, pos);
                    if (source == ShadowChunkSource.REMOTE_FULL) {
                        ShadowTrackingSession.getInstance().onNetworkChunkQueued(resolvedDimension, pos);
                    }
                    return;
                }
                if (ShadowLightCompute.publishCachedChunk(resolvedDimension, pos)) {
                    SmokeChunkTrace.recordShadowInjected(resolvedDimension, pos);
                    if (source == ShadowChunkSource.REMOTE_FULL) {
                        ShadowTrackingSession.getInstance().onNetworkChunkQueued(resolvedDimension, pos);
                    }
                    return;
                }
            } else {
                ShadowLightCompute.enqueueInjectedForLight(resolvedDimension, pos, origin);
                if (source == ShadowChunkSource.REMOTE_FULL) {
                    ShadowTrackingSession.getInstance().onNetworkChunkQueued(resolvedDimension, pos);
                }
                return;
            }
        }
        server.setPersistenceRole(resolvedDimension, pos,
                ShadowChunkPersistenceRole.VISIBLE_FULL_LIGHT);
        if (source == ShadowChunkSource.REMOTE_FULL) {
            SmokeChunkTrace.recordNetworkReceived(resolvedDimension, pos);
        }
        if (!server.injectPreLight(resolvedDimension, pos, packet, source)) {
            io.github.limuqy.mc.hassium.Constants.LOG.warn(
                    "[SHADOW_INJECT] pre-light failed ({}, {}) dim={}",
                    pos.x, pos.z, resolvedDimension);
            if (shouldFailShadowOnInjectFailure()) {
                ShadowServerRegistry.getInstance().failShadowServer();
            }
            return;
        }
        SmokeChunkTrace.recordShadowInjected(resolvedDimension, pos);
        // 区块来源指标只能在 ClientChunkCache 实际落地后记账；此处仅排入光屏障。
        // CACHE_SNAPSHOT 因此不会伪装成网络 full miss。
        ShadowLightCompute.enqueueInjectedForLight(resolvedDimension, pos, origin);
        // 不得再走 onPullInjected → publishCachedChunk：那会把同一柱的 SERVER_PUSH/
        // REMOTE_PULL 来源覆盖成 MEMORY_CACHE，R1 首进被误记成缓存全命中（区块加载恒 0）。
        // 交付已由 enqueueInjectedForLight 入 generated 光屏障完成；这里只清形状扫描在途。
        if (source == ShadowChunkSource.REMOTE_FULL) {
            ShadowTrackingSession.getInstance().onNetworkChunkQueued(resolvedDimension, pos);
        }
    }

    public static String currentDimension() {
        return ShadowLightCompute.currentDimension();
    }

}
