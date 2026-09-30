package io.github.limuqy.mc.hassium.mixin.client;

import io.github.limuqy.mc.hassium.client.ChunkMeshCompileLog;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 网格编译完成钩子：与 {@code [CHUNK_APPLY]} 对照 apply→mesh 延迟。
 * 1.20.1（&lt;1.21.1）={@code addRecentlyCompiledChunk}；1.21.1–26.2={@code addRecentlyCompiledSection}；
 * 26.3 起 LevelRenderer 该方法移除，编译完成通知迁至
 * {@code SectionRenderDispatcher.RenderSection.setSectionMesh}（{@code onSectionMeshUpdate} 回调点）。
 * Mixin 目标类/方法跨版本，#if 留在本类（hassium-manifold 允许）。
 */
#if MC_VER >= MC_26_3
@Mixin(SectionRenderDispatcher.RenderSection.class)
public class MixinLevelRenderer {

    @Inject(method = "setSectionMesh", at = @At("HEAD"))
    private void hassium$onCompiled(net.minecraft.client.renderer.chunk.SectionMesh mesh, CallbackInfo ci) {
        BlockPos origin = ((SectionRenderDispatcher.RenderSection) (Object) this).getRenderOrigin();
        ChunkMeshCompileLog.onCompiled(origin.getX(), origin.getY(), origin.getZ());
    }
}
#else
@Mixin(net.minecraft.client.renderer.LevelRenderer.class)
public class MixinLevelRenderer {

    @Inject(method = "addRecentlyCompiledSection", at = @At("HEAD"))
    private void hassium$onCompiled(SectionRenderDispatcher.RenderSection section, CallbackInfo ci) {
        // ≥1.21.5：RenderSection.getOrigin() 改名 getRenderOrigin()
        BlockPos origin = section.getRenderOrigin();
        ChunkMeshCompileLog.onCompiled(origin.getX(), origin.getY(), origin.getZ());
    }
}
#endif
