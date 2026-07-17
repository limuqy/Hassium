package io.github.limuqy.mc.hassium.compat;

import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;

import java.util.Collection;

/**
 * CompoundTag / 标量 Tag 读取 API 兼容层。
 * <p>
 * 1.21.4-: {@code getAllKeys()}、{@code getAsByte()} 等
 * 1.21.5+: {@code keySet()}、{@code byteValue()} 等
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
}
