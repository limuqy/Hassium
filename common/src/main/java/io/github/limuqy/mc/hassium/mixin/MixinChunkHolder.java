package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.PlayerCompressionTracker;
import io.github.limuqy.mc.hassium.network.ServerChunkPushManager;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 拦截 ChunkHolder.broadcast：对 Hassium 客户端发送 contentHash 元数据。
 */
@Mixin(ChunkHolder.class)
public class MixinChunkHolder {
    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/ChunkHolder");

    @Shadow
    @Final
    private ChunkPos pos;

    @Inject(method = "broadcast", at = @At("HEAD"), cancellable = true)
    private void hassium$onBroadcast(List<ServerPlayer> players, Packet<?> packet, CallbackInfo ci) {
        if (!(packet instanceof ClientboundLevelChunkWithLightPacket chunkPacket)) {
            return;
        }

        // 分离 Hassium 和非 Hassium 玩家
        List<ServerPlayer> hassiumPlayers = null;
        for (ServerPlayer player : players) {
            if (PlayerCompressionTracker.isCompressionEnabled(player)) {
                if (hassiumPlayers == null) {
                    hassiumPlayers = new ArrayList<>();
                }
                hassiumPlayers.add(player);
            } else {
                // 检查是否强制要求客户端 Mod
                if (HassiumConfigService.getInstance().isRequireClientMod()
                        && PlayerCompressionTracker.isHandshakeTimeout(player)) {
                    player.connection.disconnect(Component.literal(
                            "This server requires the Hassium mod. Please install it to join."));
                    continue;
                }
                // 非 Hassium 玩家在主线程上发送原版 packet
                player.connection.send(packet);
            }
        }

        // 没有 Hassium 玩家时直接取消原版 broadcast
        if (hassiumPlayers == null) {
            ci.cancel();
            return;
        }

        // 异步计算 hash 并发送元数据到 pushPool 工作线程
        String dimension = hassiumPlayers.get(0).level().dimension()
#if MC_VER < MC_1_21_11
                .location()
#else
                .identifier()
#endif
                .toString();
        ServerChunkPushManager.getInstance().submitMetadataTask(
                hassiumPlayers, pos, chunkPacket, dimension);

        ci.cancel();
    }
}
