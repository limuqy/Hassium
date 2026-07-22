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

        var lightData = packet.getLightData();
        if (!lightData.getSkyYMask().isEmpty() || !lightData.getBlockYMask().isEmpty()
                || !lightData.getEmptySkyYMask().isEmpty() || !lightData.getEmptyBlockYMask().isEmpty()) {
            return;
        }

        // 空光照：需同步重算（服务端剥离网络光照 / 缓存 is_light_on=0）
        // 不受 lightCacheEnabled 控制——重算是渲染必需
        NetworkStats.recordLightCacheMiss();
        ClientLightRecomputeService.applyLightEngineNow(new ChunkPos(packet.getX(), packet.getZ()));
    }
}
