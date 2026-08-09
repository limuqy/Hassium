package io.github.limuqy.mc.hassium.network.gateway;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.network.core.outbound.HandshakeCodec;
import io.github.limuqy.mc.hassium.network.dataplane.UdpDataPlaneHandshakeTail;

/**
 * 主控侧握手响应信息提供缝（T11 骨架）。
 *
 * <p>网关帧侧握手不经过原版服务器状态机，S2C 响应里的服务端侧字段
 * （协议版本/压缩接受/种子/维度 NBT/SeedGen/UDP 数据面端点表）由平台侧
 * 实现体注入（默认实现全部取保守默认：接受握手、不启用 ZSTD/UDP/SeedGen）。
 *
 * <p>{@code resumeAccepted} 不在本缝内：由网关自身按续流验票结果决定
 * （{@link GatewayChannel} 组合进 S2C 尾部）。平台侧如需否决续流
 * （如 B 侧会话同步未就绪），可在此拒绝握手整体（accepted=false）。
 */
@FunctionalInterface
public interface GatewayServerInfoProvider {

    /** 解析一次握手响应的服务端侧字段（event loop 线程调用；不得阻塞）。 */
    ServerHandshakeInfo resolve(GatewayChannel channel, HandshakeCodec.ClientRequestOptions request);

    /** 保守默认：接受握手、压缩/UDP/SeedGen 全部关闭。 */
    static ServerHandshakeInfo acceptDefaults() {
        return new ServerHandshakeInfo(
                Constants.CURRENT_PROTOCOL_VERSION,
                true,
                false,
                false,
                UdpDataPlaneHandshakeTail.S2CTail.disabled(),
                0L,
                null,
                false);
    }

    /** 拒绝握手（线格式无原因字段；客户端按协议版本不匹配处理）。 */
    static ServerHandshakeInfo reject() {
        return new ServerHandshakeInfo(Constants.CURRENT_PROTOCOL_VERSION, false, false, false,
                UdpDataPlaneHandshakeTail.S2CTail.disabled(), 0L, null, false);
    }

    /** 一次握手响应的服务端侧字段（不含 resumeAccepted——网关自行组合）。 */
    record ServerHandshakeInfo(
            int protocolVersion,
            boolean accepted,
            boolean globalCompressionAccepted,
            boolean compactHeaderAccepted,
            UdpDataPlaneHandshakeTail.S2CTail udpTail,
            long worldSeed,
            byte[] levelStemNbt,
            boolean seedGenEnabled
    ) {
    }
}
