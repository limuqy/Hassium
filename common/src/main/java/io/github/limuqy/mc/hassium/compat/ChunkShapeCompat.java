package io.github.limuqy.mc.hassium.compat;

import net.minecraft.server.level.ChunkMap;

/**
 * 原版视距形状谓词收口：客户端影子选柱（{@code ShadowTrackingSession#enumerateDiscBiased}）
 * 与服务端形状判定共用同一原版几何，禁止业务代码自绘圆/方。
 * <p>
 * 1.20.1 无 {@code ChunkTrackingView}，直接用 {@link ChunkMap#isChunkInRange}
 * （玩家 tracking 同款，public static）；1.21.1+ 走 {@code ChunkTrackingView.of().contains()}。
 * 两者公式同族（圆角方形：内切欧氏 + 角区 chebyshev 折算），跨版本行为一致。
 * <p>
 * 语义对齐原版玩家 tracking：range = 通告视距 + 1（{@code ChunkMap.setViewDistance}
 * 用 {@code viewDistance+1} 构造 tracking view），因此调用方传 range 前需自行 +1。
 */
public final class ChunkShapeCompat {

    private ChunkShapeCompat() {}


    /** 原版视距形状判定：(x,z) 是否在 (cx,cz) 的 range 圆角方形内。 */
    public static boolean contains(int cx, int cz, int range, int x, int z) {
#if MC_VER < MC_1_21_1
        return ChunkMap.isChunkInRange(x, z, cx, cz, range);
#else
        return net.minecraft.server.level.ChunkTrackingView
                .of(new net.minecraft.world.level.ChunkPos(cx, cz), range)
                .contains(x, z);
#endif
    }

    /**
     * 原版形状的切比雪夫外接半径 = {@code range + 1}。
     * <p>
     * {@link #contains} 对齐的 {@code ChunkMap.isChunkInRange} 内含 {@code |d| - 1} 折算，故沿四条
     * 轴线方向形状可延伸到 {@code |d| = range + 1}（range=10 时 {@code (11, |dz|<=5)} 等共 44 柱在内）。
     * 原版 {@code ChunkMap.updatePlayerStatus} 的枚举循环正因此写 {@code ±(viewDistance + 1)}。
     * <p>
     * <b>凡「按方形循环 + {@link #contains} 过滤」的枚举，循环半径必须用本值</b>：用 {@code range}
     * 会漏掉每边中段，而这些柱又被 {@link #inOvdBand} 判给权威侧 → 两侧都不交付，客户端出现
     * 封闭虚空（实测 serverVD=10 / clientVD=16：环带外侧 44 柱永不投递）。
     */
    public static int boundingRadius(int range) {
        return range + 1;
    }

    /**
     * OVD 环带判定：(x,z) 落在 client 半径的切比雪夫窗内、且**不在** authority 半径的原版形状内。
     * <p>
     * 收口理由：该判据原先在 {@code ShadowTrackingSession#inOvdWindow} 手搓（切比雪夫 + {@link #contains}），
     * 而 OVD 环带现在同时是「本地源服务的窗」与「票驱动的目标集合」——两处各写一遍必然漂移。
     */
    public static boolean inOvdBand(int cx, int cz, int authorityRange, int clientRadius,
                                    int x, int z) {
        if (clientRadius <= authorityRange) {
            return false; // 环带为空：client 未超出权威边距
        }
        return Math.abs(x - cx) <= clientRadius && Math.abs(z - cz) <= clientRadius
                && !contains(cx, cz, authorityRange, x, z);
    }
}
