package io.github.limuqy.mc.hassium.network;

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
     * 光照光环：在可见形状（serverVD）之外再多拉 1 环，对齐原版
     * {@code ChunkStatus.LIGHT} 的 3×3 邻域依赖（range=1）。
     * <p>
     * 光环柱只进影子端 inject/算光/缓存，<b>不得</b>交付客户端；交付域仍是 serverVD
     * （∪ OVD）。服务端签发上界已是 {@link #AUTHORITY_MARGIN}，无需再放宽。
     */
    public static final int LIGHT_HALO_RADIUS = 1;
}
