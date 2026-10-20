package io.github.limuqy.mc.hassium.mixin.shadow;

import io.github.limuqy.mc.hassium.utils.DebugLogger;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Shadow injected columns retain their light after the temporary worldgen holder unloads. */
@Mixin(ThreadedLevelLightEngine.class)
public class MixinThreadedLevelLightEngine {
    @org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Final
    private net.minecraft.server.level.ChunkMap chunkMap;

    @Inject(method = "updateChunkStatus", at = @At("HEAD"), cancellable = true)
    private void hassium$retainInjectedLight(ChunkPos pos,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        var level = ((ChunkMapAccessor) chunkMap).hassium$getLevel();
        if (level.getServer() instanceof io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer shadow
                && shadow.injectedChunk(io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer.dimensionId(level), pos.x, pos.z) != null) {
            DebugLogger.info(DebugLogger.LogType.LIGHT,
                    "[SHADOW_LIGHT] retain injected light after native holder unload ({}, {})", pos.x, pos.z);
            ci.cancel();
        }
    }


    @Inject(method = "lightChunk(Lnet/minecraft/world/level/chunk/ChunkAccess;Z)"
                    + "Ljava/util/concurrent/CompletableFuture;",
            at = @At("HEAD"), require = 1)
    private void hassium$traceLightChunk(ChunkAccess chunk, boolean lit,
                                         CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!DebugLogger.isEnabled(DebugLogger.LogType.LIGHT) || chunk == null) {
            return;
        }
        ChunkPos pos = chunk.getPos();
        DebugLogger.info(DebugLogger.LogType.LIGHT,
                "[LIGHT_CALL] pos=({},{}) lit={} class={} thread={}",
                pos.x, pos.z, lit, chunk.getClass().getSimpleName(),
                Thread.currentThread().getName());
    }
}
