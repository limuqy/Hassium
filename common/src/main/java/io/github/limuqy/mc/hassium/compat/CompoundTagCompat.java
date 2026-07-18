package io.github.limuqy.mc.hassium.compat;

import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.Collection;

/**
 * CompoundTag / 标量 Tag 读取 API 兼容层。
 * <p>
 * 1.21.4-: {@code getAllKeys()}、{@code getAsByte()} 等
 * 1.21.5+: {@code keySet()}、{@code byteValue()} 等
 * <p>
 * 1.21.5+ 起 {@code getInt}/{@code getBoolean}/{@code getList} 等返回 {@code Optional}，
 * 本类统一返回原始类型（缺失返回默认值）。
 */
public final class CompoundTagCompat {
    private CompoundTagCompat() {}

    /**
     * 获取 CompoundTag 的全部键。
     */
    public static Collection<String> getKeys(CompoundTag tag) {
#if MC_VER < MC_1_21_5
        return tag.getAllKeys();
#else
        return tag.keySet();
#endif
    }

    public static byte getByte(ByteTag tag) {
#if MC_VER < MC_1_21_5
        return tag.getAsByte();
#else
        return tag.byteValue();
#endif
    }

    public static short getShort(ShortTag tag) {
#if MC_VER < MC_1_21_5
        return tag.getAsShort();
#else
        return tag.shortValue();
#endif
    }

    public static int getInt(IntTag tag) {
#if MC_VER < MC_1_21_5
        return tag.getAsInt();
#else
        return tag.intValue();
#endif
    }

    public static long getLong(LongTag tag) {
#if MC_VER < MC_1_21_5
        return tag.getAsLong();
#else
        return tag.longValue();
#endif
    }

    public static float getFloat(FloatTag tag) {
#if MC_VER < MC_1_21_5
        return tag.getAsFloat();
#else
        return tag.floatValue();
#endif
    }

    public static double getDouble(DoubleTag tag) {
#if MC_VER < MC_1_21_5
        return tag.getAsDouble();
#else
        return tag.doubleValue();
#endif
    }

    public static String getString(StringTag tag) {
#if MC_VER < MC_1_21_5
        return tag.getAsString();
#else
        return tag.value();
#endif
    }

    /**
     * 读取 int 字段（1.21.5+ Optional 兼容）。
     */
    public static int getInt(CompoundTag tag, String key, int defaultValue) {
#if MC_VER < MC_1_21_5
        return tag.getInt(key);
#else
        return tag.getInt(key).orElse(defaultValue);
#endif
    }

    /**
     * 读取 boolean 字段（1.21.5+ Optional 兼容）。
     */
    public static boolean getBoolean(CompoundTag tag, String key, boolean defaultValue) {
#if MC_VER < MC_1_21_5
        return tag.getBoolean(key);
#else
        return tag.getBoolean(key).orElse(defaultValue);
#endif
    }

    /**
     * 读取 ListTag 字段（1.21.5+ Optional 与 TagType 重构兼容）。
     * 不校验 list element type，调用方自行 instanceof 判断。
     */
    public static ListTag getList(CompoundTag tag, String key) {
        Tag t = tag.get(key);
        return t instanceof ListTag lt ? lt : new ListTag();
    }

    /**
     * 判断是否含指定 key 且为 CompoundTag。
     */
    public static boolean containsCompound(CompoundTag tag, String key) {
        return tag.get(key) instanceof CompoundTag;
    }

    /**
     * 判断是否含指定 key 且为 ListTag（如 chunk NBT 的 {@code sections}）。
     */
    public static boolean containsList(CompoundTag tag, String key) {
        return tag.get(key) instanceof ListTag;
    }
}
