package io.github.limuqy.mc.hassium.mixin.shadow;

import io.github.limuqy.mc.hassium.server.ServerNetworkGate;
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

/** Filter only negotiated chunk takeover recipients. Block/entity updates always use vanilla. */
@Mixin(ChunkHolder.class)
public class MixinChunkHolder {
    @Inject(method = "broadcast", at = @At("HEAD"), cancellable = true)
    private void hassium$onBroadcast(List<ServerPlayer> players, Packet<?> packet, CallbackInfo ci) {
        if (!(packet instanceof ClientboundLevelChunkWithLightPacket)
                && !(packet instanceof ClientboundLightUpdatePacket)) return;
        if (players.stream().noneMatch(ServerNetworkGate::shouldTakeOverChunks)) return;
        for (ServerPlayer player : players) {
            if (!ServerNetworkGate.shouldTakeOverChunks(player)) player.connection.send(packet);
        }
        ci.cancel();
    }
}
