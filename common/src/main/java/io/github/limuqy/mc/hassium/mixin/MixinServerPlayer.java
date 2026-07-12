package io.github.limuqy.mc.hassium.mixin;

import com.mojang.authlib.GameProfile;
import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.network.PlayerCompressionTracker;
import io.github.limuqy.mc.hassium.network.ServerChunkPushManager;
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

    @Inject(method = "<init>", at = @At("TAIL"))
    private void hassium$onPlayerInit(CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        PlayerCompressionTracker.setConnected(self);
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

        Constants.LOG.info("[TRACK_CHUNK] Player {} tracking chunk {} (compressionEnabled=true)",
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

