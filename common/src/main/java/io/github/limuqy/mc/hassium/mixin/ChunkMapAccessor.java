package io.github.limuqy.mc.hassium.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * {@code ChunkMap} 访问器：官方 {@code readChunk}/{@code scheduleChunkLoad}、
 * POI、可见 holder。影子端 type 126 读盘走 {@code ShadowStorageManager}，
 * 不再回落本访问器打开原版 RegionFile（与映像整文件重写双写同一 .mca）。
 * <p>
 * 签名分段：{@code < MC_1_21_2} 返回 {@code Either<ChunkAccess, ChunkLoadingFailure>}；
 * 1.21.2+ 重构后直接返回 {@code CompletableFuture<ChunkAccess>}。
 */
@Mixin(net.minecraft.server.level.ChunkMap.class)
public interface ChunkMapAccessor {

    /** POI 管理器（影子端 region 文件刷新链用；全版本字段名一致）。 */
    @org.spongepowered.asm.mixin.gen.Accessor("poiManager")
    net.minecraft.world.entity.ai.village.poi.PoiManager hassium$getPoiManager();

    /** 可见 ChunkHolder（与 {@code getChunkForLighting} 同一张表；不经 getChunk mixin）。 */
    @Invoker("getVisibleChunkIfPresent")
    net.minecraft.server.level.ChunkHolder hassium$getVisibleChunkIfPresent(long pos);

    @Invoker("readChunk")
    CompletableFuture<Optional<CompoundTag>> hassium$readChunk(ChunkPos pos);

#if MC_VER < MC_1_21_1
    @Invoker("scheduleChunkLoad")
    CompletableFuture<com.mojang.datafixers.util.Either<net.minecraft.world.level.chunk.ChunkAccess,
            net.minecraft.server.level.ChunkHolder.ChunkLoadingFailure>>
            hassium$scheduleChunkLoad(ChunkPos pos);
#else
    @Invoker("scheduleChunkLoad")
    CompletableFuture<net.minecraft.world.level.chunk.ChunkAccess> hassium$scheduleChunkLoad(ChunkPos pos);
#endif
}
