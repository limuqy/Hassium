package io.github.limuqy.mc.hassium.metrics;

import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import io.github.limuqy.mc.hassium.shadow.light.ShadowVanillaLightPipeline;
import io.github.limuqy.mc.hassium.shadow.light.SmokeChunkTrace;
import net.minecraft.world.level.ChunkPos;

/**
 * 原版 {@code ClientPacketListener} 成功替换区块数据后的指标收口。
 *
 * <p>客户端与世界侧保持纯原版协议时，区块不会经过 Hassium 自定义 payload /
 * {@code ShadowLightCompute}；指标必须在原版 {@code handleLevelChunkWithLight} 已成功
 * 返回后记录。这里的 {@code payloadBytes} 是已解码的原版 chunk-data payload，不把
 * 尚未落地的网络包、影子端注入尝试或光照缓存事件混入统计。</p>
 */
public final class NativeChunkMetrics {

    private NativeChunkMetrics() {
    }

    /**
     * Records a complete chunk successfully installed by the vanilla world-side handler.
     * This is a server push, not a client compare/pull request; counting it as a request made
     * normal smoke reports pretend every direct chunk was a cache miss.
     */
    public static void recordAppliedFullChunk(String dimension, int chunkX, int chunkZ, int payloadBytes) {
        if (payloadBytes <= 0) {
            return;
        }
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        NetworkStats.recordChunkReceived(payloadBytes);
        NetworkStats.recordWireBytesReceived(payloadBytes);
        NetworkStats.recordServerPushApplied(chunkX, chunkZ);
        SmokeChunkTrace.recordNetworkReceived(dimension, pos);
        SmokeChunkTrace.recordClientApplied(dimension, pos);
    }

    /** 当前原版客户端处理维度；保持与影子链路的 trace key 一致。 */
    public static String currentDimension() {
        return ShadowVanillaLightPipeline.currentDimension();
    }
}
