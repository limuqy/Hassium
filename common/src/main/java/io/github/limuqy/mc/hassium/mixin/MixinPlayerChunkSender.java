package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.network.PlayerCompressionTracker;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.20.2+：拦截 {@code PlayerChunkSender.sendChunk}，为 Hassium 客户端保留 shadowPull
 * 的主动取数路径，同时让非 shadowPull 客户端继续走原版区块包发送。
 * 1.20.2 移除了 {@code ServerPlayer.trackChunk}，初始区块发送改走
 * {@code PlayerChunkSender.sendChunk}（private static）。此 Mixin 在 1.20.2+ 替代
 * {@link MixinServerPlayer} 的 trackChunk 注入。
 * <p>
 * 区块更新广播仍由 {@link MixinChunkHolder} 拦截。
 * <p>
 * 1.20.1 无 {@code PlayerChunkSender}，挂空壳到 {@code MinecraftServer}
 * 以满足 mixins.json 注册（同 {@link MixinClientCommonPacketListenerImpl} 模式）。
 */
#if MC_VER >= MC_1_21_1
@Mixin(net.minecraft.server.network.PlayerChunkSender.class)
#else
@Mixin(net.minecraft.server.MinecraftServer.class)
#endif
public abstract class MixinPlayerChunkSender {

#if MC_VER >= MC_1_21_1
    @org.spongepowered.asm.mixin.Shadow
    private boolean memoryConnection;
    @org.spongepowered.asm.mixin.Shadow
    private float desiredChunksPerTick;
    @org.spongepowered.asm.mixin.Shadow
    private float batchQuota;
    @org.spongepowered.asm.mixin.Unique
    private boolean hassium$forceQuota;


    /**
     * 源头定额：把原版 {@code sendNextChunks} 的 batch 钳到 {@code maxChunksPerTick}。
     * 与压缩/网关会话无关；shadowPull 客户端由主动取数路径接管区块数据。
     */
    @Inject(method = "sendNextChunks", at = @At("HEAD"))
    private void hassium$capSourceRate(ServerPlayer player, CallbackInfo ci) {
        if (!hassium$forceQuota) {
            return;
        }
        int max = HassiumConfigService.getInstance().getConfig().master().maxChunksPerTick();
        if (max <= 0) {
            max = 4;
        }
        if (desiredChunksPerTick > max) {
            desiredChunksPerTick = max;
        }
        if (batchQuota > max) {
            batchQuota = max;
        }
    }


    /**
     * 本机连接（integrated）会无视 quota 一次吐完全部 pending。Hassium 路径强制走定额 nearest-N。
     */
    @org.spongepowered.asm.mixin.injection.Redirect(method = "collectChunksToSend",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/server/network/PlayerChunkSender;memoryConnection:Z"))
    private boolean hassium$quotaLimitedCollect(net.minecraft.server.network.PlayerChunkSender self) {
        return !hassium$forceQuota && memoryConnection;
    }

    /**
     * 截获原版已构造的首个 level-chunk packet：Hassium 客户端统一由 shadowPull 主动取数，
     * 其他客户端原样交回原版发送路径。
     */
    @org.spongepowered.asm.mixin.injection.Redirect(method = "sendChunk",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V",
                    ordinal = 0))
    private static void hassium$onChunkPacketSend(ServerGamePacketListenerImpl listener, Packet<?> packet) {
        if (packet instanceof ClientboundLevelChunkWithLightPacket
                && PlayerCompressionTracker.isCompressionEnabled(listener.getPlayer())) {
            return;
        }
        listener.send(packet);
    }

#endif
}
