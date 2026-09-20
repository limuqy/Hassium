package io.github.limuqy.mc.hassium.compat;

import net.minecraft.server.level.ChunkMap;

/**
 * 原版视距形状谓词收口：客户端影子选柱（{@code ShadowTrackingSession#enumerateDiscBiased}）
 * 与服务端形状判定共用同一原版几何，禁止业务代码自绘圆/方。
 * <p>
 * 1.20.1 无 {@code ChunkTrackingView}，直接用 {@link ChunkMap#isChunkInRange}
 * （玩家 tracking 同款，public static）；1.21.1+ 走 {@code ChunkTrackingView.of().contains()}。
 * 注意：1.21.4 起原版把 {@code isWithinDistance} 改成纯欧氏圆（旧版是「内切欧氏 + 角区
 * chebyshev 折算」），两段形状不同——凡依赖形状**尺寸/边界**的逻辑必须经由本类（它委托原版），
 * 不得自行抄公式（见 {@link #containsDilated} 的说明）。
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
     * 原版视距形状的**切比雪夫膨胀**判定：(x,z) 是否落在「{@code range} 形状外扩
     * {@code dilate} 环」内。
     * <p>
     * 实现 = <b>坐标向中心收缩 {@code dilate} 后委托 {@link #contains}</b>。两代原版公式都对
     * {@code |dx|}/{@code |dz|} 单调，故收缩后的判定恰为切比雪夫膨胀（数值验证：vd∈{10,16,20}×
     * dilate∈{0,1,2} 与朴素「3×3 邻域存在性」定义逐点等价；≤1.21.3 上与旧的内联实现逐点相同）。
     * <p>
     * <b>为什么不把公式抄进本方法</b>：原版形状在 <b>1.21.4 改过</b>——旧公式（chebyshev 角区折算
     * {@code k=max(0,max(i,j)-1)}，1.20.1–1.21.3）与 1.21.4+ 新公式（纯欧氏
     * {@code max(0,|d| - 2)² 求和}，{@code ChunkTrackingView.isWithinDistance} 的 includeBorder
     * 折算为 offset 2）不是同一公式，VD=20 形状从 1529 柱变为 1573 柱。抄公式就得按版本分段，而
     * 1.21.4 不是编译锚点、白名单没有对应 token（写 {@code < MC_1_21_5} 会把 1.21.4 错分进旧段）；
     * 委托则让膨胀自动跟随 {@link #contains} 的版本分支（含未来原版再变）。1.21.4 实证：硬编码
     * 旧公式时计算域（旧形状膨胀，1705）≠ 新形状膨胀（1749），新权威形状 44 柱 3×3 缺邻 →
     * 缺邻柱被按 Bedrock 挡天光（红线 failure mode）；委托后 {@code ChunkShapeDilationTest} 全绿。
     * <p>
     * <b>用途（S3 光照光环）</b>：权威柱的 3×3 必须全部落在计算域内，否则边界柱算光时缺邻被
     * 当基岩挡光。**不得用 {@code contains(range + R)} 近似**——那是「形状环」，在形状切角处
     * 每窗漏掉 8~20 个权威柱的邻柱（实测 VD=10/16/20 → 8/16/20 个；膨胀形式为 0 个）。
     * 膨胀形式的最大切比雪夫半径 = {@code range + dilate + 1}（两代公式同界），
     * {@code dilate=1} 时恰好贴满服务端签发上限 {@code range + ShadowPullRadii.AUTHORITY_MARGIN}。
     */
    public static boolean containsDilated(int cx, int cz, int range, int dilate, int x, int z) {
        int d = Math.max(0, dilate);
        int dx = x - cx;
        int dz = z - cz;
        int ax = Math.max(0, Math.abs(dx) - d);
        int az = Math.max(0, Math.abs(dz) - d);
        return contains(cx, cz, range,
                cx + (dx >= 0 ? ax : -ax),
                cz + (dz >= 0 ? az : -az));
    }

    /**
     * 膨胀形状的切比雪夫外接半径 = {@code boundingRadius(range) + dilate}。
     * <p>
     * 「按方形循环 + {@link #containsDilated} 过滤」的枚举必须用本值当循环半径。
     */
    public static int dilatedBoundingRadius(int range, int dilate) {
        return boundingRadius(range) + Math.max(0, dilate);
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
