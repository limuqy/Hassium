package io.github.limuqy.mc.hassium.protocol;

/**
 * 权威可视盒外扩边距的统一真相源。
 * <p>
 * 客户端影子选择窗口（{@code ShadowTrackingSession#resolveViewDistance}）与服务端 pull
 * 校验（各 loader 通道 {@code ShadowPullHandler#handle} 的 maxDistance）必须使用同一个
 * 常数：保证客户端按自身窗口发出的环形请求全部落在服务端校验范围内（window ⊇ request）。
 * <p>
 * 边距 = 2 的含义：vanilla cacheRadius 之上再扩展一环（cacheRadius+1）只够当前通告窗口，
 * 无法还原老推送链路曾经达成过的完整圆心柱形（VD20 圆锥计 1517 = 半径 22 圆心内的格子，
 * 见 docs/client-chunk-flow-handover.md §0 的 1268 vs 1529 缺口几何）。外扩 2 使影子
 * 选择与服务端签发覆盖到半径为 22 的完整圆心柱（含正飞方向身后的滞环），并在忽略移动
 * 尾巴的半圆区域补齐静态基准盘面。
 */
public final class ShadowPullRadii {
    private ShadowPullRadii() {
    }

    /** 相对 cacheRadius / viewDistance 的可视权威外扩边距（柱计数单位）。 */
    public static final int AUTHORITY_MARGIN = 2;

    /**
     * 光照光环**默认**半径（环）：在权威形状（serverVD）之外再算 1 环，对齐原版
     * {@code ChunkStatus.LIGHT} 的 3×3 邻域依赖（range=1）。
     * <p>
     * 光环柱只进影子端 inject/算光/缓存，**不得**交付客户端——其**唯一职责是给权威边界柱
     * 补齐 3×3**：一个边界权威柱的 8 邻里有 3~5 个落在此环上，它们都是已算光的真实柱
     * （有数据层，不是 Bedrock）→ 不会出现「缺邻当基岩」的错误传播。
     * <p>
     * <b>2026-09-19</b>：本常量不再是死常量——它是配置键 {@code chunk.lightHaloRadius}
     * 的默认值，运行期半径以配置为准（见 {@link #MAX_LIGHT_HALO_RADIUS} 的上限）。
     * <p>
     * <b>几何口径</b>：光环是**形状的切比雪夫膨胀**（{@code ChunkShapeCompat.containsDilated}），
     * 不是 {@code contains(serverVD + R)}——后者是「形状环」，在形状切角处会漏邻
     * （实测 VD=10/16/20 每窗漏 8/16/20 个权威柱的邻柱）。膨胀形式零缺口，且最大切比雪夫
     * 半径 {@code VD+R+1} 恰好贴满服务端签发上限 {@code VD+AUTHORITY_MARGIN}。
     */
    public static final int LIGHT_HALO_RADIUS = 1;

    /**
     * 光环半径上限 = {@link #AUTHORITY_MARGIN} − 1。
     * <p>
     * 推导（两侧边界必须对齐，否则外环被服务端 RANGE 拒）：
     * <ul>
     *   <li>客户端窗口 = {@code contains(center, serverVD + R)}，其**切比雪夫外接盒**是
     *       {@code cheb ≤ serverVD + R + 1}（见 {@code ChunkShapeCompat.boundingRadius}）。</li>
     *   <li>服务端签发（{@code ShadowPullServer} → {@code ShadowPullRequestValidator}）是
     *       **纯切比雪夫** {@code cheb ≤ serverVD + AUTHORITY_MARGIN}。</li>
     * </ul>
     * 两者对齐 ⟹ {@code R + 1 ≤ AUTHORITY_MARGIN} ⟹ {@code R ≤ AUTHORITY_MARGIN − 1 = 1}。
     * 即默认值（1）**恰好**贴满签发范围，没有余量；再大就必须同时放宽服务端签发范围
     * （两侧改动，且会放宽所有客户端的请求上界）——不是纯配置逃生口。
     */
    public static final int MAX_LIGHT_HALO_RADIUS = AUTHORITY_MARGIN - 1;
}
