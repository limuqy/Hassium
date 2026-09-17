package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.network.PlayerCompressionTracker;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.20.2+：拦截 {@code PlayerChunkSender.sendChunk}，为 Hassium 客户端保留 shadowPull
 * 的主动取数路径；Pull 玩家停发整柱（客户端 Compare+Pull 自取）。
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
    @org.spongepowered.asm.mixin.Shadow
    private int unacknowledgedBatches;
    @org.spongepowered.asm.mixin.Unique
    private boolean hassium$forceQuota;


    /**
     * 源头定额：把原版 {@code sendNextChunks} 的 batch 钳到 {@code maxChunksPerTick}。
     * 与压缩/握手无关——专用服与 LAN 远程玩家一律限速，自有 Pull 通道与原版通道同频。
     * 影子虚拟玩家没有客户端 ACK，{@code unacknowledgedBatches} 会在首批后永久卡住；
     * 影子 {@code runMainLoop} 也不走 {@code MinecraftServer} 的 send-chunks 泵，
     * 由 {@link io.github.limuqy.mc.hassium.compat.ShadowPlayerCompat#flushVirtualPlayerChunks}
     * 每圈补泵，本钩子把 ACK 闸放开。
     */
    @Inject(method = "sendNextChunks", at = @At("HEAD"))
    private void hassium$capSourceRate(ServerPlayer player, CallbackInfo ci) {
        if (io.github.limuqy.mc.hassium.server.RuntimeServerContext.isShadowServerContext()) {
            unacknowledgedBatches = 0;
            hassium$forceQuota = false;
            return;
        }
        hassium$forceQuota = io.github.limuqy.mc.hassium.network.ServerNetworkGate
                .shouldRateLimitChunkSend(player);
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
        if (io.github.limuqy.mc.hassium.server.RuntimeServerContext.isShadowServerContext()) {
            // 影子待发队列一次性掏空：虚拟连接不是 WAN，且 dummy 永不 ACK。
            return true;
        }
        return !hassium$forceQuota && memoryConnection;
    }

    /**
     * 影子端禁止组 {@code ClientboundLevelChunkWithLightPacket}：dummy 管道丢包，
     * 1.21.11 {@code debugSynchronizers().startTrackingChunk} 还会在无同步器时把
     * 影子主循环打崩。物化桥在 {@code ChunkMap.onChunkReadyToSend}。
     */
    @Inject(method = "sendChunk", at = @At("HEAD"), cancellable = true)
    private static void hassium$skipShadowChunkPackets(
            ServerGamePacketListenerImpl listener,
            net.minecraft.server.level.ServerLevel level,
            net.minecraft.world.level.chunk.LevelChunk chunk,
            CallbackInfo ci) {
        if (io.github.limuqy.mc.hassium.server.RuntimeServerContext.isShadowServerContext()) {
            ci.cancel();
        }
    }

    /**
     * 截获原版首包：压缩门已开的玩家**停发整柱**，并在抑制点声明权威边沿
     * （{@link io.github.limuqy.mc.hassium.network.ChunkAuthorityNotifier}），
     * 整柱数据改由客户端影子 tracking 统一 Compare+Pull；其它玩家原样交回原版发送路径。
     * <p>
     * 1.20.2+ 的 {@code PlayerChunkSender.sendChunk} 是首包入口，不能只丢弃 packet；
     * 否则客户端既收不到原版包，也不会进入 Hassium 的替代数据流。
     */
    @org.spongepowered.asm.mixin.injection.Redirect(method = "sendChunk",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V",
                    ordinal = 0))
    private static void hassium$onChunkPacketSend(ServerGamePacketListenerImpl listener, Packet<?> packet) {
        ServerPlayer player = listener.getPlayer();
        if (io.github.limuqy.mc.hassium.server.RuntimeServerContext.isShadowServerContext()) {
            // S3 接法 B：影子上下文官方包转发真实客户端，不进 dummy、也不走真服抑制逻辑
            io.github.limuqy.mc.hassium.shadow.track.ShadowOfficialPacketBridge
                    .forwardToRealClient(packet);
            return;
        }
        if (!PlayerCompressionTracker.isCompressionEnabled(player)) {
            listener.send(packet);
            return;
        }
        java.util.List<net.minecraft.world.level.ChunkPos> enters = hassium$levelChunkPositions(packet);
        if (enters == null) {
            listener.send(packet); // 非整柱载荷：原样下发
            return;
        }
        if (!io.github.limuqy.mc.hassium.server.RuntimeServerContext.isShadowServerContext()
                && io.github.limuqy.mc.hassium.network.handshake.ServerHandshakeActivation.hasCaps(
                        player.getUUID(), io.github.limuqy.mc.hassium.network.handshake.LoginCaps.PULL_MODE)) {
            // Pull 模式：整柱不下发；数据由影子 Provider 交付（权威声明已降级）
        }
        // 压缩门已开：整柱一律不下发（bundle 情形下连同其辅助光照子包一并丢弃，
        // 与 lightStrip 语义一致——Hassium 客户端光照由影子端统一计算）。
    }

    /**
     * 取该载荷携带的整柱包坐标；非整柱载荷返回 {@code null}。
     * <p>
     * <b>必须解包 bundle</b>：NeoForge 在 {@code PlayerChunkSender.sendChunk} 里把整柱包与辅助光照包
     * 封进 {@link net.minecraft.network.protocol.game.ClientboundBundlePacket} 再 {@code send}
     * （{@code chunk.getAuxLightManager(pos).sendLightDataTo(packet)}），于是 {@code packet} 的运行时类型
     * 是 bundle 而非 {@code ClientboundLevelChunkWithLightPacket}，裸 {@code instanceof} 恒为假 ⇒
     * 既抑制不掉整柱、也发不出声明（F12：neoforge 全版本整柱抑制与权威边沿全程未生效）。
     * fabric / forge 发的是裸包，行为不变。
     */
    @org.spongepowered.asm.mixin.Unique
    private static java.util.List<net.minecraft.world.level.ChunkPos> hassium$levelChunkPositions(Packet<?> packet) {
        if (packet instanceof ClientboundLevelChunkWithLightPacket chunkPacket) {
            return java.util.List.of(new net.minecraft.world.level.ChunkPos(chunkPacket.getX(), chunkPacket.getZ()));
        }
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundBundlePacket bundle) {
            java.util.List<net.minecraft.world.level.ChunkPos> positions = new java.util.ArrayList<>(2);
            for (Packet<?> sub : bundle.subPackets()) {
                if (sub instanceof ClientboundLevelChunkWithLightPacket chunkPacket) {
                    positions.add(new net.minecraft.world.level.ChunkPos(chunkPacket.getX(), chunkPacket.getZ()));
                }
            }
            if (!positions.isEmpty()) {
                return positions;
            }
        }
        return null;
    }

#endif
}
