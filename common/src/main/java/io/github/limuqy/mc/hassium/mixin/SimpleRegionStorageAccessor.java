package io.github.limuqy.mc.hassium.mixin;

import net.minecraft.world.level.chunk.storage.IOWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 区块/实体存储层统一 worker 访问器：1.21.2+ 的 {@code SimpleRegionStorage}
 * （ChunkMap / EntityStorage 均持有）与旧版的 {@code ChunkStorage}（1.21.2 移除）
 * 都有 {@code worker}（IOWorker）字段——双目标 + 同字段名，跨段零 #if 调用。
 * <p>
 * 用途：跨会话并发场景下刷新 region 文件缓存（影子端
 * {@code ShadowSeedServer.refreshRegionFiles}）。
 */
#if MC_VER >= MC_1_21_2
@Mixin(net.minecraft.world.level.chunk.storage.SimpleRegionStorage.class)
#else
@Mixin(net.minecraft.world.level.chunk.storage.ChunkStorage.class)
#endif
public interface SimpleRegionStorageAccessor {

    @Accessor("worker")
    IOWorker hassium$getWorker();
}
