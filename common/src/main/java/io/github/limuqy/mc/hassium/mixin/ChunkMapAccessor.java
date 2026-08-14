package io.github.limuqy.mc.hassium.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * {@code ChunkMap} 磁盘加载访问器：官方 {@code scheduleChunkLoad}（readChunk + 解析 +
 * 主线程回调完整链）。影子端读盘（R2 缓存命中 / OVD 环带回填）复用官方加载路径，
 * 保证 126 解压（MixinRegionFile 读 hook）与 light 恢复语义与正式加载一致。
 * <p>
 * 签名分段：{@code < MC_1_21_2} 返回 {@code Either<ChunkAccess, ChunkLoadingFailure>}；
 * 1.21.2+ 重构后直接返回 {@code CompletableFuture<ChunkAccess>}。
 */
@Mixin(net.minecraft.server.level.ChunkMap.class)
public interface ChunkMapAccessor {

    /** POI 管理器（影子端 region 文件刷新链用；全版本字段名一致）。 */
    @org.spongepowered.asm.mixin.gen.Accessor("poiManager")
    net.minecraft.world.entity.ai.village.poi.PoiManager hassium$getPoiManager();

    @Invoker("readChunk")
    CompletableFuture<Optional<CompoundTag>> hassium$readChunk(ChunkPos pos);

#if MC_VER < MC_1_20_5
    @Invoker("scheduleChunkLoad")
    CompletableFuture<com.mojang.datafixers.util.Either<net.minecraft.world.level.chunk.ChunkAccess,
            net.minecraft.server.level.ChunkHolder.ChunkLoadingFailure>>
            hassium$scheduleChunkLoad(ChunkPos pos);
#else
    @Invoker("scheduleChunkLoad")
    CompletableFuture<net.minecraft.world.level.chunk.ChunkAccess> hassium$scheduleChunkLoad(ChunkPos pos);
#endif
}
