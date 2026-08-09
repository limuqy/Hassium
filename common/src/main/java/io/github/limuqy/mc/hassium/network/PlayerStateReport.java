package io.github.limuqy.mc.hassium.network;

/**
 * 握手时客户端上报的完整玩家状态（x/y/z/yaw/pitch/维度），T7 位置上报扩展。
 * <p>
 * 纯数据记录：{@code present() = dimension != null}（真实上报恒带维度；
 * 旧客户端无扩展字段时以 {@link #fromXZ} 兜底）。
 */
public record PlayerStateReport(double x, double y, double z, float yaw, float pitch, String dimension) {

    /** 缺失/旧客户端占位（dimension=null → present()=false） */
    public static PlayerStateReport absent() {
        return new PlayerStateReport(0.0, 0.0, 0.0, 0.0f, 0.0f, null);
    }

    /** 仅位置兜底（旧客户端 x/z） */
    public static PlayerStateReport fromXZ(double x, double z) {
        return new PlayerStateReport(x, 0.0, z, 0.0f, 0.0f, null);
    }

    public boolean present() {
        return dimension != null;
    }

    public String describe() {
        return present()
                ? String.format("(%.1f, %.1f, %.1f) yaw=%.1f pitch=%.1f dim=%s", x, y, z, yaw, pitch, dimension)
                : String.format("(%.1f, %.1f) [legacy]", x, z);
    }
}
