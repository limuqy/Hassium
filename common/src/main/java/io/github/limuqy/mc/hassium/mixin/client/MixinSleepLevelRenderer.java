package io.github.limuqy.mc.hassium.mixin.client;

import io.github.limuqy.mc.hassium.client.sleep.SleepTerrainClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

/** Reuses compiled meshes when waking; never rebuilds the player's world or releases input. */
@Mixin(net.minecraft.client.renderer.LevelRenderer.class)
public abstract class MixinSleepLevelRenderer {
#if MC_VER < MC_1_21_1
    @Redirect(method={"renderChunkLayer","renderLevel"},at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/chunk/ChunkRenderDispatcher$RenderChunk;getCompiledChunk()Lnet/minecraft/client/renderer/chunk/ChunkRenderDispatcher$CompiledChunk;"))
    private net.minecraft.client.renderer.chunk.ChunkRenderDispatcher.CompiledChunk hassium$visible(
            net.minecraft.client.renderer.chunk.ChunkRenderDispatcher.RenderChunk chunk) {
        var pos=chunk.getOrigin();
        return SleepTerrainClient.renderColumn(pos.getX()>>4,pos.getZ()>>4)?chunk.getCompiledChunk():
                net.minecraft.client.renderer.chunk.ChunkRenderDispatcher.CompiledChunk.UNCOMPILED;
    }
#elif MC_VER < MC_1_21_2
    @Redirect(method={"renderSectionLayer","renderLevel"},at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;getCompiled()Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$CompiledSection;"))
    private net.minecraft.client.renderer.chunk.SectionRenderDispatcher.CompiledSection hassium$visible(
            net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection section) {
        var pos=section.getOrigin();
        return SleepTerrainClient.renderColumn(pos.getX()>>4,pos.getZ()>>4)?section.getCompiled():
                net.minecraft.client.renderer.chunk.SectionRenderDispatcher.CompiledSection.UNCOMPILED;
    }
#endif
}
