package io.github.limuqy.mc.hassium.api;

import java.util.Set;

/**
 * Hassium 能力描述，用于握手协商
 */
public record HassiumCapabilities(
        String modVersion,
        int protocolVersion,
        Set<String> supportedAlgorithms,
        boolean clientCacheSupported,
        boolean chunkRevisionSupported,
        boolean scheme127Supported,
        boolean globalPacketCompressionSupported,
        boolean compactHeaderSupported
) {
    /**
     * 当前协议版本
     */
    public static final int CURRENT_PROTOCOL_VERSION = 3;

    /**
     * 创建默认的客户端能力
     *
     * @param modVersion        模组版本
     * @param supportedAlgorithms 支持的压缩算法集合（从配置读取）
     */
    public static HassiumCapabilities clientDefaults(String modVersion, Set<String> supportedAlgorithms) {
        return new HassiumCapabilities(
                modVersion,
                CURRENT_PROTOCOL_VERSION,
                supportedAlgorithms,
                true,
                true,
                false, // 1.20.1 暂不支持 scheme 127
                true,  // 支持全局包压缩
                true   // 支持紧凑包头
        );
    }

    /**
     * 创建默认的服务端能力
     *
     * @param modVersion        模组版本
     * @param supportedAlgorithms 支持的压缩算法集合（从配置读取）
     */
    public static HassiumCapabilities serverDefaults(String modVersion, Set<String> supportedAlgorithms) {
        return new HassiumCapabilities(
                modVersion,
                CURRENT_PROTOCOL_VERSION,
                supportedAlgorithms,
                true,
                true,
                false,
                true,  // 支持全局包压缩
                true   // 支持紧凑包头
        );
    }
}
