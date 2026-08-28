package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.compat.LevelCompat;
import io.github.limuqy.mc.hassium.network.ServerChunkPushManager;
import io.github.limuqy.mc.hassium.network.gateway.GatewayServer;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.20.2+：拦截 {@code PlayerChunkSender.sendChunk}，对 Hassium 客户端发送元数据替代原版区块包。
 * <p>
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
     * 与压缩/网关会话无关；{@link #hassium$onSendChunk} 仍按会话决定是否改走元数据。
     */
    @Inject(method = "sendNextChunks", at = @At("HEAD"))
    private void hassium$capSourceRate(ServerPlayer player, CallbackInfo ci) {
        hassium$forceQuota = io.github.limuqy.mc.hassium.server.RuntimeServerContext.isDedicatedServerContext();
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
     * 拦截 sendChunk：对 Hassium 客户端异步发送 chunkHash 元数据，取消原版区块包。
     * <p>
     * sendChunk 是 private static，注入方法也必须为 private static。
     */
    @Inject(method = "sendChunk", at = @At("HEAD"), cancellable = true)
    private static void hassium$onSendChunk(ServerGamePacketListenerImpl listener, ServerLevel level,
                                             LevelChunk chunk, CallbackInfo ci) {
        ServerPlayer player = listener.getPlayer();
        // 拦截 gate = 网关会话存在：有会话即 Hassium 客户端（网关握手已确认身份）→ 转元数据
        // （chunkHash/seedgen 帧走网关自有通道，不依赖 ZSTD/压缩启用）；无网关会话（原版
        // 客户端）放行原版发送。不等压缩启用：enableCompression 在物化后 finishLoginBridge
        // 才置位，ChunkSender 抢跑窗口内必须已拦截，否则原版直发 → 客户端影子端无数据。
        if (GatewayServer.getInstance().registry().get(player.getUUID()) == null) {
            return;
        }

        ChunkPos pos = chunk.getPos();
        String dimension = LevelCompat.getDimensionId(level);

        // 异步计算 hash 并发送元数据
        ServerChunkPushManager.getInstance().submitMetadataTaskFromChunk(player, pos, chunk, dimension);

        ci.cancel(); // 取消原版区块包发送
    }

    /** PlayerChunkSender.dropChunk 是 1.20.2+ 的精确 tracking-view 移除回调。 */
    @Inject(method = "dropChunk", at = @At("HEAD"))
    private void hassium$onDropChunk(ServerPlayer player, ChunkPos pos, CallbackInfo ci) {
        if (GatewayServer.getInstance().registry().get(player.getUUID()) == null) {
            return;
        }
        String dimension = LevelCompat.getDimensionId(player.level());
        ServerChunkPushManager.getInstance().discardUntrackedChunk(player.getUUID(), dimension, pos);
    }
#endif
}
