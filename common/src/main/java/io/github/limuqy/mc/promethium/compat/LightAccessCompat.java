package io.github.limuqy.mc.promethium.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 光照重算用 BlockState 光照 API 跨版本兼容。
 * <p>
 * 分界 = {@code MC_1_21_2}：1.21.2 起 BlockStateBase 缓存 {@code lightBlock} /
 * {@code occlusionShapesByFace} 字段，{@code getLightBlock(BlockGetter, BlockPos)} /
 * {@code getFaceOcclusionShape(BlockGetter, BlockPos, Direction)} 改为无参版本
 * （1.21.1 及以前带参；1.21.2 及以后无参，已反编译核实）。
 */
public final class LightAccessCompat {
    private LightAccessCompat() {}

    /** 原始 light block（0–15；不含 {@code Math.max(1,·)} 下限，天空注入边缘判定用）。 */
    public static int getLightBlock(BlockState state, BlockGetter level, BlockPos pos) {
#if MC_VER < MC_1_21_2
        return state.getLightBlock(level, pos);
#else
        return state.getLightBlock();
#endif
    }

    /** 指定方向的遮挡面形状（不参与形状光照遮挡的方块返回 empty）。 */
    public static VoxelShape getFaceOcclusionShape(BlockState state, BlockGetter level, BlockPos pos, Direction dir) {
#if MC_VER < MC_1_21_2
        return state.getFaceOcclusionShape(level, pos, dir);
#else
        return state.getFaceOcclusionShape(dir);
#endif
    }
}
