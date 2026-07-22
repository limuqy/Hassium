package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.cache.client.ClientLightRecomputeService;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
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
 * 检测到剥离光照后委托 {@link ClientLightRecomputeService}（逻辑不放 Mixin 类，
 * 避免 public static 触发 InvalidMixinException）。
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
        if (!HassiumConfigService.getInstance().isLightStrip()) {
            return;
        }
        if (level == null) {
            return;
        }

        var lightData = packet.getLightData();
        if (!lightData.getSkyYMask().isEmpty() || !lightData.getBlockYMask().isEmpty()
                || !lightData.getEmptySkyYMask().isEmpty() || !lightData.getEmptyBlockYMask().isEmpty()) {
            return;
        }

        // 空光照：需同步重算（服务端 lightStrip / 缓存 is_light_on=0）
        NetworkStats.recordLightCacheMiss();
        ClientLightRecomputeService.applyLightEngineNow(new ChunkPos(packet.getX(), packet.getZ()));
    }
}
