package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.cache.client.ChunkMeshCompileLog;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
#if MC_VER < MC_1_20_2
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
#else
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
#endif

/**
 * 网格编译完成钩子：与 {@code [CHUNK_APPLY]} 对照 apply→mesh 延迟。
 * 1.20.1={@code addRecentlyCompiledChunk}；1.20.2+={@code addRecentlyCompiledSection}。
 */
@Mixin(LevelRenderer.class)
public class MixinLevelRenderer {

#if MC_VER < MC_1_20_2
    @Inject(method = "addRecentlyCompiledChunk", at = @At("HEAD"))
    private void hassium$onCompiled(ChunkRenderDispatcher.RenderChunk chunk, CallbackInfo ci) {
        BlockPos origin = chunk.getOrigin();
        ChunkMeshCompileLog.onCompiled(origin.getX(), origin.getY(), origin.getZ());
    }
#else
    @Inject(method = "addRecentlyCompiledSection", at = @At("HEAD"))
    private void hassium$onCompiled(SectionRenderDispatcher.RenderSection section, CallbackInfo ci) {
        BlockPos origin = section.getOrigin();
        ChunkMeshCompileLog.onCompiled(origin.getX(), origin.getY(), origin.getZ());
    }
#endif
}
