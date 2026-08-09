package io.github.limuqy.mc.hassium.compat;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
#if MC_VER >= MC_1_21_6
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
#endif

/**
 * 跨版本实体 NBT 序列化适配。
 * <p>
 * save 侧：{@code < MC_1_21_6} {@code entity.save(CompoundTag)}；
 * 1.21.6+ {@code entity.save(ValueOutput)}（经 {@link TagValueOutput}）。
 * load 侧：{@code < MC_1_21_6} {@code entity.load(CompoundTag)}（1.20.5 移除的是
 * BlockEntity.load，Entity.load(CompoundTag) 保留到 1.21.5）；
 * 1.21.6+ {@code entity.load(ValueInput)}（经 {@link TagValueInput}）。对照
 * BlockEntityCompat 三段式（实体无 1.20.5 中间态）。
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

    /**
     * 用磁盘加载的 CompoundTag 恢复实体数据（R2 磁盘加载链路 / EntitySnapshotCompat
     * 使用者）。{@code registries} 在 1.21.6+ 传入 ValueInput。
     */
    public static void loadFromTag(Entity entity, CompoundTag tag, HolderLookup.Provider registries) {
#if MC_VER < MC_1_21_6
        entity.load(tag);
#else
        entity.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));
#endif
    }
}
