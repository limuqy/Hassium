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
#if MC_VER < MC_1_21_2
        return accessor.getMinSection();
#else
        return accessor.getMinSectionY();
#endif
    }

    /** 最高 section Y 上界（exclusive），适合 {@code for (y = min; y < max; y++)}。 */
    public static int getMaxSectionExclusive(LevelHeightAccessor accessor) {
#if MC_VER < MC_1_21_2
        return accessor.getMaxSection();
#else
        return accessor.getMaxSectionY() + 1;
#endif
    }
}
