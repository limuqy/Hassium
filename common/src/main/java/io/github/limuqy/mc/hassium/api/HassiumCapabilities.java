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
        boolean compactHeaderSupported,
        boolean seedGenSupported
) {
    /**
     * 当前协议版本（本类专用）。
     * <p>
     * review-fix: T8-25: 原常量名 {@code CURRENT_PROTOCOL_VERSION} 与
     * {@code Constants.CURRENT_PROTOCOL_VERSION}（=1）同名不同值（3），改名为
     * {@code CAPABILITY_PROTOCOL_VERSION} 消除口径冲突。grep 确认本类全库无生产调用方
     * （HassiumHandshake 已删除、HandshakeCodec 为唯一握手编解码方，见 HandshakeCodec:13-16
     * 未激活说明）——保留为未来激活的占位常量，不再冒充全局协议版本。
     */
    public static final int CAPABILITY_PROTOCOL_VERSION = 3;

    /**
     * 创建默认的客户端能力
     *
     * @param modVersion        模组版本
     * @param supportedAlgorithms 支持的压缩算法集合（从配置读取）
     */
    public static HassiumCapabilities clientDefaults(String modVersion, Set<String> supportedAlgorithms) {
        return new HassiumCapabilities(
                modVersion,
                CAPABILITY_PROTOCOL_VERSION,
                supportedAlgorithms,
                true,
                true,
                false, // 1.20.1 暂不支持 scheme 127
                true,  // 支持全局包压缩
                true,  // 支持紧凑包头
                false  // SeedGen 能力由握手发送方按配置/上下文置位
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
                CAPABILITY_PROTOCOL_VERSION,
                supportedAlgorithms,
                true,
                true,
                false,
                true,  // 支持全局包压缩
                true,  // 支持紧凑包头
                false  // 服务端默认不声明 SeedGen（由配置开启）
        );
    }
}
