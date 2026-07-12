package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.network.PlayerCompressionTracker;
import io.github.limuqy.mc.hassium.network.ServerChunkPushManager;
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
#if MC_VER >= MC_1_20_2
@Mixin(net.minecraft.server.network.PlayerChunkSender.class)
#else
@Mixin(net.minecraft.server.MinecraftServer.class)
#endif
public abstract class MixinPlayerChunkSender {

#if MC_VER >= MC_1_20_2
    /**
     * 拦截 sendChunk：对 Hassium 客户端异步发送 chunkHash 元数据，取消原版区块包。
     * <p>
     * sendChunk 是 private static，注入方法也必须为 private static。
     */
    @Inject(method = "sendChunk", at = @At("HEAD"), cancellable = true)
    private static void hassium$onSendChunk(ServerGamePacketListenerImpl listener, ServerLevel level,
                                             LevelChunk chunk, CallbackInfo ci) {
        ServerPlayer player = listener.getPlayer();
        if (!PlayerCompressionTracker.isCompressionEnabled(player)) {
            return; // 非 Hassium 玩家，放行原版发送
        }

        ChunkPos pos = chunk.getPos();
        String dimension = level.dimension()
#if MC_VER < MC_1_21_11
                .location()
#else
                .identifier()
#endif
                .toString();

        // 异步计算 hash 并发送元数据
        ServerChunkPushManager.getInstance().submitMetadataTaskFromChunk(player, pos, chunk, dimension);

        ci.cancel(); // 取消原版区块包发送
    }
#endif
}
