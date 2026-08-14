package io.github.limuqy.mc.hassium.mixin;

import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code IOWorker} 存储访问器（全版本字段名一致）：
 * 影子端 region 文件刷新链 {@code SimpleRegionStorage/ChunkStorage.worker →
 * IOWorker.storage → RegionFileStorage.regionCache}。
 */
@Mixin(IOWorker.class)
public interface IOWorkerAccessor {

    @Accessor("storage")
    RegionFileStorage hassium$getStorage();
}
