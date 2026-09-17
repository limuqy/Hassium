package io.github.limuqy.mc.hassium.mixin.server;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.server.PlayerCompressionTracker;
import io.github.limuqy.mc.hassium.server.ServerChunkPushManager;
import io.github.limuqy.mc.hassium.server.ServerHandshakeActivation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to ServerGamePacketListenerImpl to track player connections for Hassium
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class MixinServerGamePacketListenerImpl {

    @Shadow
    public ServerPlayer player;

    // review-fix: T7-59: handler 统一加 hassium$ 前缀
    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void hassium$onPlayerDisconnect(
#if MC_VER < MC_1_21_1
            net.minecraft.network.chat.Component reason,
#else
            net.minecraft.network.DisconnectionDetails details,
#endif
            CallbackInfo ci) {
        Constants.LOG.info("Hassium: Player {} disconnected, push queue cleaned up", player.getName().getString());
        PlayerCompressionTracker.removePlayer(player);
        ServerChunkPushManager.getInstance().removePlayer(player.getUUID());
        ServerHandshakeActivation.removePlayer(player.getUUID());
    }
}
