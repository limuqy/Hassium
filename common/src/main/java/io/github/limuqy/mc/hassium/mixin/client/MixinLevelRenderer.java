package io.github.limuqy.mc.hassium.mixin.client;

import io.github.limuqy.mc.hassium.client.ChunkMeshCompileLog;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

/**
 * 网格编译完成钩子：与 {@code [CHUNK_APPLY]} 对照 apply→mesh 延迟。
 * 1.20.1（<1.21.1）={@code addRecentlyCompiledChunk}；1.21.1+={@code addRecentlyCompiledSection}。
 */
@Mixin(LevelRenderer.class)
public class MixinLevelRenderer {

    @Inject(method = "addRecentlyCompiledSection", at = @At("HEAD"))
    private void hassium$onCompiled(SectionRenderDispatcher.RenderSection section, CallbackInfo ci) {
        // ≥1.21.5：RenderSection.getOrigin() 改名 getRenderOrigin()
        BlockPos origin = section.getRenderOrigin();
        ChunkMeshCompileLog.onCompiled(origin.getX(), origin.getY(), origin.getZ());
    }
}
