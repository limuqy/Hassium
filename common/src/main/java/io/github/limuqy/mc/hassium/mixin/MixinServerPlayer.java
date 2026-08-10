package io.github.limuqy.mc.hassium.mixin;

import com.mojang.authlib.GameProfile;
import io.github.limuqy.mc.hassium.network.PlayerCompressionTracker;
import io.github.limuqy.mc.hassium.network.ServerChunkPushManager;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DebugLogger.LogType;
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
 * 拦截 trackChunk：对 Hassium 客户端异步发送 contentHash 元数据。
 * hash 计算和元数据发送在 pushPool 工作线程上执行，不阻塞主线程。
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
#if MC_VER < MC_1_20_2
    @Inject(method = "<init>(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerLevel;Lcom/mojang/authlib/GameProfile;)V", at = @At("TAIL"))
#else
    @Inject(method = "<init>(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerLevel;Lcom/mojang/authlib/GameProfile;Lnet/minecraft/server/level/ClientInformation;)V", at = @At("TAIL"))
#endif
    private void hassium$onPlayerInit(CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        PlayerCompressionTracker.setConnected(self);
        // login/配置阶段预握手的玩家在此提升：placeNewPlayer 创建 ServerPlayer 时
        // 立即启用压缩，进服第一圈 trackChunk/sendChunk 全部走 Hassium 链
        // （剥光 + maxChunksPerTick 限流 + chunkHash 元数据），消灭握手前原版直发窗口。
        PlayerCompressionTracker.tryEnableOnPlayerJoin(self);
    }

#if MC_VER < MC_1_20_2
    /**
     * 拦截 trackChunk：对 Hassium 客户端异步发送 contentHash 元数据。
     * hash 计算和元数据发送在 pushPool 工作线程上执行，不阻塞主线程。
     * <p>
     * 仅 1.20.1：1.20.2+ 移除了 {@code ServerPlayer.trackChunk}，初始区块发送
     * 改走 {@code PlayerChunkSender.sendChunk}（private static），需另行适配。
     * 区块更新广播仍由 {@link MixinChunkHolder#hassium$onBroadcast} 拦截。
     */
    @Inject(method = "trackChunk", at = @At("HEAD"), cancellable = true)
    private void hassium$onTrackChunk(ChunkPos pos, Packet<?> chunkPacket, CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (!PlayerCompressionTracker.isCompressionEnabled(self)) {
            return;
        }

        DebugLogger.info(LogType.NETWORK, "[TRACK_CHUNK] Player {} tracking chunk {} (compressionEnabled=true)",
                self.getName().getString(), pos);

        // 异步计算 hash 并发送元数据到 pushPool 工作线程
        String dimension = self.level().dimension()
#if MC_VER < MC_1_21_11
                .location()
#else
                .identifier()
#endif
                .toString();
        ServerChunkPushManager.getInstance().submitMetadataTask(self, pos, chunkPacket, dimension);

        ci.cancel();
    }
#endif
}

