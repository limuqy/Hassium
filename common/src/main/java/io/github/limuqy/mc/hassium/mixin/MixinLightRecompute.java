package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.cache.client.ClientLightRecomputeService;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端光照重算入口 Mixin。
 * <p>
 * 检测到空光照（服务端剥离 or 缓存无光照）后委托 {@link ClientLightRecomputeService} 重算。
 * 不受 {@code lightCacheEnabled} 控制——重算是渲染必需，缓存开关只影响是否存储重算结果。
 * <p>
 * 合并 pipeline：handleLevelChunkWithLight TAIL 时区块已 apply，直接同步重算光照，
 * 不再经过 MainThreadDispatcher 延迟调度，避免跨帧黑块。
 */
@Mixin(ClientPacketListener.class)
public class MixinLightRecompute {

    @Shadow
    private ClientLevel level;

    @Inject(method = "handleLevelChunkWithLight", at = @At("TAIL"))
    private void hassium$onHandleChunkWithLight(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        if (level == null) {
            return;
        }

        // chunk 数据已替换（本包 apply 完成）：失效快照/版本/任务重采样（并行引擎）。
        // 缓存 apply（applyChunkData → handleLevelChunkWithLight）与网络全量包共用此路径；
        // 不失效则同 chunk 对象的旧快照继续产出旧地形光（深水区亮暗跳变同根因），
        // 预提交任务（ClientCacheLoadQueue / delta merge）的核心柱也会采样到旧数据。
        // 官方引擎无快照概念（缓冲队列消费时直接读 chunk 当前状态），无需失效。
        if (io.github.limuqy.mc.hassium.cache.client.PromethiumLightBridge.isEnabled()) {
            io.github.limuqy.mc.hassium.cache.client.PromethiumLightBridge
                    .onChunkDataReplaced(level, new ChunkPos(packet.getX(), packet.getZ()));
        }

        var lightData = packet.getLightData();
        if (!lightData.getSkyYMask().isEmpty() || !lightData.getBlockYMask().isEmpty()
                || !lightData.getEmptySkyYMask().isEmpty() || !lightData.getEmptyBlockYMask().isEmpty()) {
            // 服务端带光块：权威光照随包落地（apply 时 queueSectionData 生效），无需重算。
            // 先落地内圈块重算时缺失本块的边界差值：官方路径由帧尾 flushPendingCalibrations
            // 兜底（官方队列跨块传播天然合并）；并行路径由引擎后台传播域（核心柱 ±16 格）承担。
            return;
        }

        // 空光照：需同步重算（服务端剥离网络光照 / 缓存 is_light_on=0）
        // 不受 lightCacheEnabled 控制——重算是渲染必需
        // 字节口径与 ClientChunkHandler.getLightBytesPerChunk 一致：用 NetworkStats.ESTIMATED_LIGHT_BYTES
        NetworkStats.recordLightCacheMiss(NetworkStats.ESTIMATED_LIGHT_BYTES);
        ClientLightRecomputeService.applyLightEngineNow(new ChunkPos(packet.getX(), packet.getZ()));
    }
}
