package io.github.limuqy.mc.hassium.compat;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
#if MC_VER >= MC_1_21_6
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
#endif

/**
 * BlockEntity NBT 加载兼容层。
 * <p>
 * 1.20.1–1.20.4：{@code be.load(CompoundTag)}
 * 1.20.5–1.21.5：{@code be.loadWithComponents(CompoundTag, Provider)}
 * 1.21.6+：{@code be.loadWithComponents(ValueInput)}（经 {@link TagValueInput}）
 */
public final class BlockEntityCompat {
    private BlockEntityCompat() {}

    /**
     * 用网络/缓存下发的 CompoundTag 加载已有 BlockEntity 数据。
     */
    public static void loadFromTag(BlockEntity be, CompoundTag tag, HolderLookup.Provider registries) {
#if MC_VER < MC_1_20_5
        be.load(tag);
#elif MC_VER < MC_1_21_6
        be.loadWithComponents(tag, registries);
#else
        be.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));
#endif
    }
}
