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
import java.util.BitSet;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;

/** Filter only negotiated chunk takeover recipients. Block/entity updates always use vanilla. */
@Mixin(ChunkHolder.class)
public class MixinChunkHolder {
#if MC_VER < MC_1_21_2
    @Shadow @Final private BitSet skyChangedLightSectionFilter;
    @Shadow @Final private BitSet blockChangedLightSectionFilter;

    /** Vanilla only sends incremental light to its view boundary; sleep has a smaller client boundary. */
    @Inject(method="broadcastChanges",at=@At("HEAD"))
    private void hassium$sleepLightBoundary(LevelChunk chunk,CallbackInfo ci) {
        if(skyChangedLightSectionFilter.isEmpty() && blockChangedLightSectionFilter.isEmpty()) return;
        if(!(chunk.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) return;
        ClientboundLightUpdatePacket packet=null;
        for(ServerPlayer player:level.players()) {
            if(!io.github.limuqy.mc.hassium.server.sleep.SleepTerrainServer.needsNativeLight(
                    player,chunk.getPos().x,chunk.getPos().z)) continue;
            if(packet==null) packet=new ClientboundLightUpdatePacket(chunk.getPos(),level.getChunkSource().getLightEngine(),
                    skyChangedLightSectionFilter,blockChangedLightSectionFilter);
            player.connection.send(packet);
        }
    }
#endif
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
