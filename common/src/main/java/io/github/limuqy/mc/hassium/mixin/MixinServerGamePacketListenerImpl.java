package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.network.PlayerCompressionTracker;
import io.github.limuqy.mc.hassium.network.ServerChunkPushManager;
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

    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void onPlayerDisconnect(
#if MC_VER < MC_1_21_1
            net.minecraft.network.chat.Component reason,
#else
            net.minecraft.network.DisconnectionDetails details,
#endif
            CallbackInfo ci) {
        Constants.LOG.info("Hassium: Player {} disconnected, push queue cleaned up", player.getName().getString());
        PlayerCompressionTracker.removePlayer(player);
        ServerChunkPushManager.getInstance().removePlayer(player.getUUID());
        // 关闭该玩家真实 UUID 的 PoC 数据面 bundle（避免重连旧 channel 残留致 BulkRouter 选到 inactive channel）。
        io.github.limuqy.mc.hassium.network.dataplane.DataPlaneServer.onPrimaryDisconnect(player.getUUID());
    }
}
