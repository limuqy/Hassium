package io.github.limuqy.mc.hassium.compat;

/**
 * Hassium 自定义网络通道能力探测。
 * <p>
 * 用于运行时功能门控：编译通过不代表通道完整可用。
 * 当 common 侧仍跳过 {@code Packet.write()} / {@code getPacketsByIds} 等路径时，
 * {@link #isCustomChannelFullySupported()} 为 false，初始化阶段会强制关闭网络压缩。
 * <p>
 * 段 C（1.20.5+ STREAM_CODEC / 聚合写包）完成后，将本方法改为恒 true（或按能力细分）。
 * 详见 docs/version-segments.md。
 */
public final class NetworkCapability {
    private NetworkCapability() {}

    /**
     * 自定义通道（握手、区块推送、聚合压缩等）是否在本 MC 版本完整可用。
     * <p>
     * 当前：1.20.4 及以下为 true；1.20.5+ 因聚合序列化与原版包枚举未完成而为 false。
     */
    public static boolean isCustomChannelFullySupported() {
#if MC_VER < MC_1_20_5
        return true;
#else
        // 1.20.5+: Packet.write / getPacketsByIds 移除后，聚合与命名空间索引尚未接 StreamCodec
        return false;
#endif
    }

    /**
     * 门控关闭时的说明日志文案。
     */
    public static String unsupportedReason() {
        return "Hassium custom network channel is not fully supported on this MC version "
                + "(Packet.write / vanilla packet index pending StreamCodec). "
                + "Forcing network.enabled=false. See docs/version-segments.md segment C.";
    }
}
