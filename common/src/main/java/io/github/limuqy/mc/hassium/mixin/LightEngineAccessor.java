package io.github.limuqy.mc.hassium.mixin;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 访问 {@link LightEngine} 内部 deferred 队列。
 * <p>
 * {@code runLightUpdates} 若中途 NPE（邻居 section 无 DataLayer），队列残留会在
 * {@code LevelRenderer.renderLevel} 再次 drain 时未捕获崩溃。失败后必须清空。
 */
@Mixin(LightEngine.class)
public interface LightEngineAccessor {

    @Accessor("blockNodesToCheck")
    LongOpenHashSet hassium$getBlockNodesToCheck();

    @Accessor("decreaseQueue")
    LongArrayFIFOQueue hassium$getDecreaseQueue();

    @Accessor("increaseQueue")
    LongArrayFIFOQueue hassium$getIncreaseQueue();
}
