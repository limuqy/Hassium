package io.github.limuqy.mc.hassium.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code EntityStorage}（实体持久化存储）访问器：字段名与类型跨版本不同
 * （1.21.2+ {@code simpleRegionStorage} / 旧版 {@code worker}），同方法名按版本
 * 返回精确字段类型（@Accessor 要求返回类型与字段描述符一致）。影子端 region 文件
 * 刷新链用。
 */
@Mixin(net.minecraft.world.level.chunk.storage.EntityStorage.class)
public interface EntityStorageAccessor {

#if MC_VER >= MC_1_21_2
    @Accessor("simpleRegionStorage")
    net.minecraft.world.level.chunk.storage.SimpleRegionStorage hassium$getRegionStorage();
#else
    @Accessor("worker")
    net.minecraft.world.level.chunk.storage.IOWorker hassium$getRegionStorage();
#endif
}
