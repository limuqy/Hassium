package io.github.limuqy.mc.hassium.compat;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
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
#if MC_VER < MC_1_21_1
        be.load(tag);
#elif MC_VER < MC_1_21_6
        be.loadWithComponents(tag, registries);
#else
        be.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));
#endif
    }

    /**
     * 该 pending BE NBT 交给 vanilla promote 是否能干净通过。
     * <p>
     * 判据与 vanilla {@code BlockEntity.loadStatic} 同源：{@code id} 能解析出注册表类型，
     * 且 {@code BlockEntityType.isValid(state)} 成立（= 方块状态与该 BE 类型匹配）。任一不成立时
     * vanilla 会在 promote 阶段打 <b>ERROR 级</b>日志（{@code Failed to create block entity} /
     * {@code Block entity has invalid type}）并丢弃该 BE；调用方据此在序列化前提前剔除，
     * 落盘结果不变、只是不再产生噪音日志。
     * <p>
     * {@code DUMMY} 条目返回 {@code true}：vanilla promote 对它有独立分支（走
     * {@code newBlockEntity}，不查注册表），必须交回 vanilla 处理。
     */
    public static boolean canPromote(CompoundTag tag, BlockState state) {
        if (tag == null || state == null) {
            return false;
        }
        String id = getString(tag, "id");
        if ("DUMMY".equals(id)) {
            return true;
        }
        if (id.isEmpty()) {
            return false;
        }
        var location = ResourceLocationCompat.tryCreate(id);
        return location != null
                && BuiltInRegistries.BLOCK_ENTITY_TYPE.getOptional(location)
                        .map(type -> type.isValid(state))
                        .orElse(false);
    }

    /** {@code CompoundTag.getString} 跨版本读取：1.21.5 起返回 {@code Optional<String>}。 */
    private static String getString(CompoundTag tag, String key) {
#if MC_VER < MC_1_21_5
        return tag.getString(key);
#else
        return tag.getString(key).orElse("");
#endif
    }
}
