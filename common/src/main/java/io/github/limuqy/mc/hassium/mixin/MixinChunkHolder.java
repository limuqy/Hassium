package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.network.PlayerCompressionTracker;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 只隔离真实服务端到 Hassium 客户端的原版 chunk/light admission。
 * 影子端不注册 vanilla tracking 玩家，因此不会产生原版区块下行。
 */
@Mixin(ChunkHolder.class)
public class MixinChunkHolder {
    @Inject(method = "broadcast", at = @At("HEAD"), cancellable = true)
    private void hassium$onBroadcast(List<ServerPlayer> players, Packet<?> packet, CallbackInfo ci) {
        if (io.github.limuqy.mc.hassium.server.RuntimeServerContext.isShadowServerContext()) {
            ci.cancel();
            return;
        }
        if (!(packet instanceof ClientboundLevelChunkWithLightPacket)
                && !(packet instanceof ClientboundLightUpdatePacket)) {
            return;
        }
        boolean filtered = false;
        for (ServerPlayer player : players) {
            if (PlayerCompressionTracker.isCompressionEnabled(player)) {
                filtered = true;
            } else {
                player.connection.send(packet);
            }
        }
        if (filtered) {
            ci.cancel();
        }
    }
}
