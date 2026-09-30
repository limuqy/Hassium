package io.github.limuqy.mc.hassium.compat;

import net.minecraft.world.level.LevelHeightAccessor;

/**
 * 世界高度 / section 坐标跨版本兼容。
 * <p>
 * 1.21.2-: {@code getMinSection()} / {@code getMaxSection()}（max exclusive）
 * 1.21.2+: {@code getMinSectionY()} / {@code getMaxSectionY()}（max inclusive → +1 为 exclusive）
 */
public final class LevelHeightCompat {
    private LevelHeightCompat() {}

    /** 最低 section Y（inclusive）。 */
    public static int getMinSection(LevelHeightAccessor accessor) {
        return accessor.getMinSectionY();
    }

    /** 最高 section Y 上界（exclusive），适合 {@code for (y = min; y < max; y++)}。 */
    public static int getMaxSectionExclusive(LevelHeightAccessor accessor) {
        return accessor.getMaxSectionY() + 1;
    }

    /** 世界最低格 Y（inclusive）。 */
    public static int getMinBlockY(LevelHeightAccessor accessor) {
        return accessor.getMinY();
    }

    /** 世界最高格 Y 上界（exclusive）= 最高可放置方块 Y + 1（section Y × 16）。 */
    public static int getMaxBlockYExclusive(LevelHeightAccessor accessor) {
        return getMaxSectionExclusive(accessor) << 4;
    }
}
