package io.github.limuqy.mc.hassium.network.seedgen;

import io.github.limuqy.mc.hassium.network.ClientChunkHandler.TraceOrigin;
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

    /** 缓存快照的 packet 入口；与远程 full 共享同一 pre-LIGHT 路径。 */
    public static void submitCacheSnapshot(String dimension, ChunkPos pos,
                                           ClientboundLevelChunkWithLightPacket packet,
                                           TraceOrigin traceOrigin) {
        submit(dimension, pos, packet, ShadowChunkSource.CACHE_SNAPSHOT,
                traceOrigin == null ? TraceOrigin.SHADOW_DISK_CACHE : traceOrigin);
    }

    /** 影子端尚未装配时不把一次竞态误判为永久失败。 */
    static boolean shouldFailShadowWhenServerUnavailable() {
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
        server.setPersistenceRole(resolvedDimension, pos,
                ShadowChunkPersistenceRole.VISIBLE_FULL_LIGHT);
        if (source == ShadowChunkSource.REMOTE_FULL) {
            SmokeChunkTrace.recordNetworkReceived(resolvedDimension, pos);
        }
        if (!server.injectPreLight(resolvedDimension, pos, packet, source)) {
            ShadowServerRegistry.getInstance().failShadowServer();
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
