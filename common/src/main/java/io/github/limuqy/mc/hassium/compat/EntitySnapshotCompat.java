package io.github.limuqy.mc.hassium.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
#if MC_VER >= MC_1_21_6
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueOutput;
#endif

/**
 * 跨版本实体 NBT 序列化适配。
 */
public final class EntitySnapshotCompat {

    private EntitySnapshotCompat() {
    }

    /**
     * 将非乘客根实体序列化为包含 id 与乘客树的独立 NBT。
     */
    public static CompoundTag save(Entity entity) {
#if MC_VER < MC_1_21_6
        CompoundTag tag = new CompoundTag();
        return entity.save(tag) ? tag : null;
#else
        TagValueOutput output = TagValueOutput.createWithContext(
                ProblemReporter.DISCARDING, entity.registryAccess());
        return entity.save(output) ? output.buildResult() : null;
#endif
    }
}
