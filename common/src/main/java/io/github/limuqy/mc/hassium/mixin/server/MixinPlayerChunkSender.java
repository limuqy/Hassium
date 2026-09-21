package io.github.limuqy.mc.hassium.mixin.server;

import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.server.PlayerCompressionTracker;
import io.github.limuqy.mc.hassium.server.ServerNetworkGate;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

#if MC_VER >= MC_1_21_1
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
#endif

/**
 * 1.21.1+ 原版整柱下发口：源限速 + 对 Hassium 客户端停发整柱。
 * <p>
 * 1.21.1 起原版把整柱下发从 {@code ServerPlayer.trackChunk} 挪进
 * {@code PlayerChunkSender}（{@code sendNextChunks} → {@code private static sendChunk}），
 * 且 {@code sendChunk} 直接 {@code connection.send(...)}，**绕过**
 * {@link MixinChunkHolder} 所拦的 {@code ChunkHolder.broadcast}——1.21.1 的
 * {@code ChunkHolder.broadcastChanges} 只广播光照 / 方块 / BE 更新，**从不广播整柱**
 * （该文件 import 里没有 {@code ClientboundLevelChunkWithLightPacket}）。
 * 故 1.21.1+ 的整柱抑制与限速只能挂在这里；1.20.1 由 {@link MixinServerPlayer} 的
 * {@code trackChunk} 注入承担（1.20.1 无 {@code PlayerChunkSender}）。
 * <p>
 * 影子端不挂钩：影子服务器已不创建 {@code ServerPlayer}（{@code 311f1367}），
 * 其官方包转发在 {@code MixinChunkMap} 的物化桥（{@code onChunkReadyToSend}）。
 * <p>
 * 1.20.1 挂空壳到 {@code MinecraftServer} 以满足 mixins.json 的无条件登记
 * （同 {@link MixinServerPlayer}「登记不分版本、体内 {@code #if} 门控」的约定）。
 */
#if MC_VER >= MC_1_21_1
@Mixin(net.minecraft.server.network.PlayerChunkSender.class)
#else
@Mixin(net.minecraft.server.MinecraftServer.class)
#endif
public abstract class MixinPlayerChunkSender {

#if MC_VER >= MC_1_21_1
    @Shadow
    private float desiredChunksPerTick;

    @Shadow
    private float batchQuota;

    /**
     * 源头定额：把原版 {@code sendNextChunks} 的 batch 钳到 {@code maxChunksPerTick}。
     * <p>
     * 必须在入口每次重钳：客户端 ACK 会经 {@code onChunkBatchReceivedByClient} 把
     * {@code desiredChunksPerTick} 回写到 64，只钳一次会被下一批 ACK 抬回去。
     */
    @Inject(method = "sendNextChunks", at = @At("HEAD"))
    private void hassium$capSourceRate(ServerPlayer player, CallbackInfo ci) {
        if (!ServerNetworkGate.shouldRateLimitChunkSend(player)) {
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
     * 截获原版首包：压缩门已开的玩家（Hassium 客户端）**停发整柱**，整柱数据改由客户端影子
     * tracking 统一 Compare+Pull / 本地生成交付；其它玩家原样交回原版发送路径。
     * <p>
     * 不能只丢弃 packet——客户端既收不到原版包，也不会进入 Hassium 的替代数据流；
     * 抑制点同时是权威声明点（{@code ChunkAuthorityNotifier} 语义）。
     */
    @Redirect(method = "sendChunk",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V",
                    ordinal = 0))
    private static void hassium$onChunkPacketSend(ServerGamePacketListenerImpl listener, Packet<?> packet) {
        if (!PlayerCompressionTracker.isCompressionEnabled(listener.getPlayer())) {
            listener.send(packet); // 未开压缩门：原样下发（vanilla 客户端 / 未握手）
            return;
        }
        if (!hassium$isWholeChunkPayload(packet)) {
            listener.send(packet); // 非整柱载荷：原样下发
        }
        // 压缩门已开：整柱一律不下发。bundle 情形下连同其辅助光照子包一并丢弃，
        // 与 lightStrip 语义一致——Hassium 客户端光照由影子端统一计算。
    }

    /**
     * 该载荷是否携带整柱包。
     * <p>
     * <b>必须解包 bundle</b>：NeoForge 在 {@code PlayerChunkSender.sendChunk} 里把整柱包与辅助
     * 光照包封进 {@link ClientboundBundlePacket} 再 {@code send}
     * （{@code chunk.getAuxLightManager(pos).sendLightDataTo(packet)}），于是 {@code packet}
     * 的运行时类型是 bundle 而非 {@link ClientboundLevelChunkWithLightPacket}，裸
     * {@code instanceof} 恒为假 ⇒ 既抑制不掉整柱、也发不出声明（F12：neoforge 全版本整柱抑制
     * 与权威边沿全程未生效）。fabric / forge 发的是裸包，行为不变。
     */
    @Unique
    private static boolean hassium$isWholeChunkPayload(Packet<?> packet) {
        if (packet instanceof ClientboundLevelChunkWithLightPacket) {
            return true;
        }
        if (packet instanceof ClientboundBundlePacket bundle) {
            for (Packet<?> sub : bundle.subPackets()) {
                if (sub instanceof ClientboundLevelChunkWithLightPacket) {
                    return true;
                }
            }
        }
        return false;
    }
#endif
}
