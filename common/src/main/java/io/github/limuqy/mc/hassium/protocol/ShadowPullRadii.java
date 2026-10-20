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
     * 固定一环光照光环，补齐权威边界柱的 3×3 邻域（ChunkStatus.LIGHT range=1）。
     * <p>
     * 计算/拉取域是权威形状的切比雪夫膨胀
     * {@code ChunkShapeCompat.containsDilated(serverVD, LIGHT_HALO_RADIUS)}，
     * 不能用 {@code contains(serverVD + LIGHT_HALO_RADIUS)} 近似，否则切角处会漏邻。
     * 光环不扩大客户端交付域；交付仍受原版视距/OVD 窗口和算光完成门约束。
     * <p>
     * 最大切比雪夫半径为 {@code serverVD + LIGHT_HALO_RADIUS + 1}，
     * 必须不超过服务端签发上限 {@code serverVD + AUTHORITY_MARGIN}。
     */
    public static final int LIGHT_HALO_RADIUS = 1;
}
