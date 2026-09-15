package io.github.limuqy.mc.hassium.mixin;

import com.mojang.authlib.GameProfile;
import io.github.limuqy.mc.hassium.network.PlayerCompressionTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 隔离真实服务端到 Hassium 客户端的原版 chunk admission；Hassium 客户端只接收
 * 影子虚拟服务端通过 vanilla connection 推送的区块包。
 */
@Mixin(ServerPlayer.class)
public abstract class MixinServerPlayer extends Player {

    public MixinServerPlayer(Level level, BlockPos pos, float yRot, GameProfile gameProfile) {
#if MC_VER < MC_1_21_6
        super(level, pos, yRot, gameProfile);
#else
        super(level, gameProfile);
#endif
    }

    // review-fix: T7-65: 带描述符精确注入主构造器——1.20.2+ 为四参 (…ClientInformation)，
    // 1.20.1 为三参；避免未来版本新增构造器时 <init> 无描述符命中全部重载重复执行
#if MC_VER < MC_1_21_1
    @Inject(method = "<init>(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerLevel;Lcom/mojang/authlib/GameProfile;)V", at = @At("TAIL"))
#else
    @Inject(method = "<init>(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerLevel;Lcom/mojang/authlib/GameProfile;Lnet/minecraft/server/level/ClientInformation;)V", at = @At("TAIL"))
#endif
    private void hassium$onPlayerInit(CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (io.github.limuqy.mc.hassium.server.RuntimeServerContext.isShadowServerContext()) {
            return;
        }
        // 登录期握手协商结果消费：启用压缩门（原版区块窗口自此压制）+ Play 激活入队。
        io.github.limuqy.mc.hassium.network.handshake.ServerHandshakeActivation.onPlayerInit(self);
    }

#if MC_VER < MC_1_21_1
    /**
     * 1.20.1 原版滴灌缓冲：trackChunk 无 PlayerChunkSender 批配额，专用服 / LAN 远程
     * 玩家的整柱包先入队，每 tick 按 {@code maxChunksPerTick} 近优先发出。
     * Pull 玩家走权威边沿 + 客户端自取，不进本缓冲。
     */
    @org.spongepowered.asm.mixin.Unique
    private final java.util.Map<Long, Packet<?>> hassium$pendingVanillaChunks =
            new java.util.LinkedHashMap<>();

    /** 1.20.1：Pull 抑制 / 原版滴灌入队 / 其余原样直发。 */
    @Inject(method = "trackChunk", at = @At("HEAD"), cancellable = true)
    private void hassium$onTrackChunk(ChunkPos pos, Packet<?> chunkPacket, CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (io.github.limuqy.mc.hassium.server.RuntimeServerContext.isShadowServerContext()) {
            return;
        }
        if (PlayerCompressionTracker.isCompressionEnabled(self)) {
            // Pull 模式：服务端停发 chunk_payload，整柱数据由客户端影子 tracking 统一拉取
            if (io.github.limuqy.mc.hassium.network.handshake.ServerHandshakeActivation.hasCaps(
                    self.getUUID(), io.github.limuqy.mc.hassium.network.handshake.LoginCaps.PULL_MODE)) {
                // 抑制整柱载荷的同时声明权威边沿（enter + 权威 hash）：客户端据此本地解析
                io.github.limuqy.mc.hassium.network.ChunkAuthorityNotifier.onAuthoritativeEnter(
                        self, io.github.limuqy.mc.hassium.compat.PlayerCompat.getServerLevel(self), pos);
                ci.cancel();
                return;
            }
            // 非 pull 兼容路径：落入下方原版滴灌判定
        }
        if (chunkPacket != null
                && io.github.limuqy.mc.hassium.network.ServerNetworkGate.shouldRateLimitChunkSend(self)) {
            hassium$pendingVanillaChunks.put(pos.toLong(), chunkPacket);
            ci.cancel();
        }
    }

    /**
     * 取消跟踪时丢弃未发出的 load，避免「先 forget 后补 load」把幽灵柱留在客户端。
     */
    @Inject(method = "untrackChunk", at = @At("HEAD"))
    private void hassium$dropPendingOnUntrack(ChunkPos pos, CallbackInfo ci) {
        hassium$pendingVanillaChunks.remove(pos.toLong());
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void hassium$flushPendingVanillaChunks(CallbackInfo ci) {
        if (hassium$pendingVanillaChunks.isEmpty()) {
            return;
        }
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (self.hasDisconnected() || self.isRemoved()
                || !io.github.limuqy.mc.hassium.network.ServerNetworkGate.shouldRateLimitChunkSend(self)) {
            hassium$pendingVanillaChunks.clear();
            return;
        }
        int max = io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance()
                .getConfig().master().maxChunksPerTick();
        if (max <= 0) {
            max = 4;
        }
        ChunkPos center = self.chunkPosition();
        java.util.List<java.util.Map.Entry<Long, Packet<?>>> live =
                new java.util.ArrayList<>(hassium$pendingVanillaChunks.entrySet());
        live.sort(java.util.Comparator.comparingLong(e -> {
            ChunkPos pos = new ChunkPos(e.getKey());
            long dx = (long) pos.x - center.x;
            long dz = (long) pos.z - center.z;
            return dx * dx + dz * dz;
        }));
        int sent = 0;
        for (java.util.Map.Entry<Long, Packet<?>> e : live) {
            if (sent >= max) {
                break;
            }
            hassium$pendingVanillaChunks.remove(e.getKey());
            self.connection.send(e.getValue());
            sent++;
        }
    }

    /**
     * 影子端跳过出生点探测：{@code fudgeSpawnLocation} 会对影子世界做出生点区块
     * 读/生成（{@code getChunk(FULL)}）——该柱可能被 tracking 悬置（worldgen 压制），
     * 在影子主循环线程上等待永不完成的 future = 自我死锁。虚拟玩家位置随后由
     * 真实玩家位置覆盖，出生点探测无意义。
     */
    @org.spongepowered.asm.mixin.injection.Redirect(
            method = "<init>(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerLevel;Lcom/mojang/authlib/GameProfile;)V",
            at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;fudgeSpawnLocation(Lnet/minecraft/server/level/ServerLevel;)V"))
    private void hassium$skipFudgeSpawn(net.minecraft.server.level.ServerPlayer self,
                                        net.minecraft.server.level.ServerLevel level) {
        if (!io.github.limuqy.mc.hassium.server.RuntimeServerContext.isShadowServerContext()) {
            ((MixinServerPlayer) (Object) self).invokeFudgeSpawnLocation(level);
        }
    }

    @org.spongepowered.asm.mixin.gen.Invoker
    public abstract void invokeFudgeSpawnLocation(net.minecraft.server.level.ServerLevel level);
#endif
}

