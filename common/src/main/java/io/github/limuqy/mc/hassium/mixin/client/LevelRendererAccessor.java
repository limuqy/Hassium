package io.github.limuqy.mc.hassium.mixin.client;

import net.minecraft.client.renderer.SectionOcclusionGraph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * LevelRenderer 的遮挡图 accessor：侵蚀自愈（{@code OcclusionGraphHealer}）用
 * {@code invalidate()} 触发全图重算——26.x 遮挡图只能穿过「已编译/EMPTY」section 传播
 * （{@code SectionMesh.UNCOMPILED.facesCanSeeEachother=false}），影子端 compare 门两波交付
 * 在图前沿留洞时，洞后的已交付柱会死锁在图外（数据/光照齐、永不编译 → 天空视角虚空）。
 * invalidate 让全图以当前 mesh 状态重 BFS，被图外的脏柱重新入图、照常编译。
 */
@Mixin(net.minecraft.client.renderer.LevelRenderer.class)
public interface LevelRendererAccessor {

    @Accessor("sectionOcclusionGraph")
    SectionOcclusionGraph hassium$getSectionOcclusionGraph();
}
