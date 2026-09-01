package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.network.dataplane.UdpDataPlaneHandshakeTail;

/**
 * 客户端握手请求参数（无网关拓扑：Play 阶段进入后由 {@link ClientHandshakeSender} 构造，
 * 三端各自编码为平台线格式后发送；字段布局与网关 {@code HandshakeCodec.encodeClientRequest}
 * 保持一致，append-only 尾字段集中在此）。
 */
public record ClientHandshakeRequest(
        int protocolVersion,
        String modVersion,
        String[] supportedAlgorithms,
        boolean clientCacheSupported,
        boolean chunkRevisionSupported,
        boolean scheme127Supported,
        boolean globalPacketCompressionSupported,
        boolean compactHeaderSupported,
        UdpDataPlaneHandshakeTail.C2STail dataplaneCapabilities,
        double posX,
        double posZ,
        boolean seedGenSupported,
        boolean lightComputeSupported
) {}