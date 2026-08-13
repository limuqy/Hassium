package io.github.limuqy.mc.hassium.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关 bootstrap 载荷编解码（CONTRACTS §1）。
 * <p>
 * 字节序（FriendlyByteBuf）：
 * <pre>
 *   varint  protocolVersion（= Constants.CURRENT_PROTOCOL_VERSION）
 *   utf     modVersion（= Constants.MOD_VERSION）
 *   varint  endpointCount
 *   for each: utf host | ushort port | varint priority
 *   utf     authToken（空串 = 不鉴权）
 *   bool    compressionSupported
 *   bool    seedGenSupported
 *   bool    lightComputeSupported
 * </pre>
 * 纯函数：仅依赖 {@link FriendlyByteBuf}，无任何 MC 版本差异（1.20.1~1.21.11 一致），
 * 无 ResourceLocation/Identifier 依赖。服务端 encode → {@link PacketPayloadCompat#createClientboundPayload}
 * 经 vanilla 通道下发；客户端 {@link PacketPayloadCompat#extractPayloadData} 取 bytes →
 * {@link #decode(byte[])}。
 */
public final class GatewayInfoCodec {

    /** 网关端点（bootstrap 下发；与配置 ReachableEndpoint 同构：host:port:priority）。 */
    public record Endpoint(String host, int port, int priority) {
    }

    /** 网关 bootstrap 信息：协议版本 + 端点池 + 鉴权 token + 能力标志。 */
    public record GatewayInfo(
            int protocolVersion,
            String modVersion,
            List<Endpoint> endpoints,
            String authToken,
            boolean compressionSupported,
            boolean seedGenSupported,
            boolean lightComputeSupported) {

        /** 空载荷：无端点 + 无鉴权 + 全能力关（默认占位，decode 失败/未收到时的兜底语义）。 */
        public static final GatewayInfo EMPTY = new GatewayInfo(0, "", List.of(), "", false, false, false);
    }

    private GatewayInfoCodec() {
        // 工具类，禁止实例化
    }

    /**
     * 编码为载荷字节（严格按 CONTRACTS §1 字段序）。
     */
    public static byte[] encode(GatewayInfo info) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(info.protocolVersion());
            buf.writeUtf(info.modVersion());
            List<Endpoint> endpoints = info.endpoints() != null ? info.endpoints() : List.of();
            buf.writeVarInt(endpoints.size());
            for (Endpoint ep : endpoints) {
                buf.writeUtf(ep.host());
                buf.writeShort(ep.port());
                buf.writeVarInt(ep.priority());
            }
            buf.writeUtf(info.authToken() != null ? info.authToken() : "");
            buf.writeBoolean(info.compressionSupported());
            buf.writeBoolean(info.seedGenSupported());
            buf.writeBoolean(info.lightComputeSupported());
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            return data;
        } finally {
            buf.release();
        }
    }

    /**
     * 从 {@link PacketPayloadCompat#extractPayloadData} 取出的裸字节解码（内部包装 FriendlyByteBuf）。
     */
    public static GatewayInfo decode(byte[] data) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        try {
            return decode(buf);
        } finally {
            buf.release();
        }
    }

    /**
     * 从 FriendlyByteBuf 解码（1.20.1 ClientboundCustomPayloadPacket.getData() 可直接传入；
     * 仅读，不移动调用方剩余数据）。
     */
    public static GatewayInfo decode(FriendlyByteBuf buf) {
        int protocolVersion = buf.readVarInt();
        String modVersion = buf.readUtf();
        int endpointCount = buf.readVarInt();
        List<Endpoint> endpoints = new ArrayList<>(Math.max(0, endpointCount));
        for (int i = 0; i < endpointCount; i++) {
            String host = buf.readUtf();
            int port = buf.readUnsignedShort();
            int priority = buf.readVarInt();
            endpoints.add(new Endpoint(host, port, priority));
        }
        String authToken = buf.readUtf();
        boolean compressionSupported = buf.readBoolean();
        boolean seedGenSupported = buf.readBoolean();
        boolean lightComputeSupported = buf.readBoolean();
        return new GatewayInfo(protocolVersion, modVersion, endpoints, authToken,
                compressionSupported, seedGenSupported, lightComputeSupported);
    }
}
