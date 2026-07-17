package io.github.limuqy.mc.hassium.compat;

/**
 * Hassium 自定义网络通道能力探测。
 * <p>
 * 用于运行时功能门控：编译通过不代表通道完整可用。
 * 段 C（1.20.5+ STREAM_CODEC / 聚合写包）完成后，自定义通道在各支持版本上完整可用。
 * 详见 docs/version-segments.md。
 */
public final class NetworkCapability {
    private NetworkCapability() {}

    /**
     * 自定义通道（握手、区块推送、聚合压缩等）是否在本 MC 版本完整可用。
     */
    public static boolean isCustomChannelFullySupported() {
        return true;
    }

    /**
     * 门控关闭时的说明日志文案。
     */
    public static String unsupportedReason() {
        return "Hassium custom network channel is not fully supported on this MC version. "
                + "Forcing network.enabled=false. See docs/version-segments.md segment C.";
    }
}
