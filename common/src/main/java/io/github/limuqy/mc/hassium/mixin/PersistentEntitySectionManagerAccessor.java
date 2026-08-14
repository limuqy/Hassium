package io.github.limuqy.mc.hassium.mixin;

import net.minecraft.world.level.entity.EntityPersistentStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code PersistentEntitySectionManager} 持久化存储访问器（全版本字段名一致）：
 * {@code permanentStorage} 实为 {@code EntityStorage}（1.21.2+ 持
 * {@code SimpleRegionStorage} / 旧版持 {@code IOWorker}，见 {@link EntityStorageAccessor}）。
 * 影子端 region 文件刷新链用。raw 返回类型匹配字段擦除描述符（@Accessor 要求一致）。
 */
@Mixin(net.minecraft.world.level.entity.PersistentEntitySectionManager.class)
public interface PersistentEntitySectionManagerAccessor {

    @Accessor("permanentStorage")
    EntityPersistentStorage hassium$getPermanentStorage();
}
