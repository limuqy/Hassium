package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DebugLogger.LogType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 客户端区块卸载诊断与影子光照生命周期钩子。 */
@Mixin(ClientLevel.class)
public class MixinClientLevel {
    @Inject(method = "unload", at = @At("HEAD"))
    private void hassium$onUnload(LevelChunk chunk, CallbackInfo ci) {
        ChunkPos pos = chunk.getPos();
        io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.onClientChunkUnloaded(pos);
        hassium$logChunkUnload(pos);
    }

    @Unique
    private static void hassium$logChunkUnload(ChunkPos pos) {
        if (!DebugLogger.isEnabled(LogType.CHUNK_APPLY)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            DebugLogger.info(LogType.CHUNK_APPLY,
                    "[CHUNK_UNLOAD] eventMs={} target=({},{}) player=unavailable",
                    System.currentTimeMillis(), pos.x, pos.z);
            return;
        }
        int playerX = (int) Math.floor(mc.player.getX());
        int playerY = (int) Math.floor(mc.player.getY());
        int playerZ = (int) Math.floor(mc.player.getZ());
        DebugLogger.info(LogType.CHUNK_APPLY,
                "[CHUNK_UNLOAD] eventMs={} target=({},{}) playerBlock=({},{},{}) playerChunk=({},{})",
                System.currentTimeMillis(), pos.x, pos.z, playerX, playerY, playerZ, playerX >> 4, playerZ >> 4);
    }
}
