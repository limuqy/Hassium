package io.github.limuqy.mc.hassium.network.dataplane;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Task 5 — append-only UDP / control-failover 握手尾部编解码。
 *
 * <p>纯 netty {@link ByteBuf}，不依赖 Minecraft / FriendlyByteBuf / loader API；写入只追加在新
 * 原版握手字段之后，读取用 {@code buf.isReadable()} 兜底（旧客户端 / feature-off 服务端 stay-disabled）。
 *
 * <p>线格式（全部 VarInt 之外用大端定长）：
 * <ul>
 *   <li>C2S tail（客户端 → 服务端，紧跟原版 LoginHello 之后）：1 bit=udpDataplaneSupported，
 *       1 bit=controlFailoverSupported，合并成 1 个 byte 低位（0x01 / 0x02 / 0x03）。</li>
 *   <li>S2C tail（服务端 → 客户端，紧跟原版 GameProfile 之后）：
 *       flags(u8: 0x01=hasUdpDataplane, 0x02=hasControlFailover) → connectionEpoch(i64) →
 *       protocol(varint) → tokenLen = 16（varint） + token[16] →
 *       controlCount(varint ≤ 8) + 每条 ControlEndpoint(hostUtf8 varstr + port(u16) + priority(varint)) →
 *       udpCount(varint ≤ 8) + 每条 UdpEndpointInfo(hostUtf8 varstr + port(u16) + weight(varint) + endpointId(varint))。</li>
 * </ul>
 *
 * <p>验证：host 非空、长度 ≤ 255、合法 hostname；port ∈ [1, 65535]；priority/weight ≥ 0；endpointId
 * 唯一非负；token 必须恰好 16 字节。任何非法项抛 {@link IllegalArgumentException}；旧包没有尾时
 * {@link #readS2C(ByteBuf)} 返回 disabled S2CTail。
 */
public final class UdpDataPlaneHandshakeTail {

    /** 握手协议版本（与 {@link UdpBindRequestCodec#PROTOCOL_VERSION} 对齐）。 */
    public static final int PROTOCOL_VERSION = 3;

    private static final int MAX_ENDPOINTS = 8;
    private static final int MAX_HOST_BYTES = 255;
    private static final int TOKEN_BYTES = 16;

    private UdpDataPlaneHandshakeTail() {}

    /** ---- C2S tail（客户端 capabilities） ---- */
    public record C2STail(boolean udpDataplaneSupported, boolean controlFailoverSupported) {}

    public static void writeC2S(ByteBuf out, C2STail tail) {
        int flags = (tail.udpDataplaneSupported() ? 0x01 : 0)
                  | (tail.controlFailoverSupported() ? 0x02 : 0);
        out.writeByte(flags & 0xFF);
    }

    public static C2STail readC2S(ByteBuf in) {
        if (in == null || !in.isReadable()) return new C2STail(false, false);
        int flags = in.readByte() & 0xFF;
        return new C2STail((flags & 0x01) != 0, (flags & 0x02) != 0);
    }

    /** ---- S2C tail（服务端 capabilities + 会话身份 + 端点表） ---- */
    public record S2CTail(
            boolean hasUdpDataplane,
            boolean hasControlFailover,
            long connectionEpoch,
            int protocol,
            byte[] token,                                  // exactly TOKEN_BYTES
            List<ControlEndpoint> controlEndpoints,         // immutable; may be empty
            List<UdpEndpointInfo> udpEndpoints              // immutable; may be empty
    ) {
        public S2CTail {
            Objects.requireNonNull(token, "token");
            if (token.length != TOKEN_BYTES) {
                throw new IllegalArgumentException("token must be 16 bytes, was " + token.length);
            }
            controlEndpoints = controlEndpoints == null ? List.of() : List.copyOf(controlEndpoints);
            udpEndpoints = udpEndpoints == null ? List.of() : List.copyOf(udpEndpoints);
        }

        /** disabled 默认（旧客户端读到包尾不可读时使用）。 */
        public static S2CTail disabled() {
            return new S2CTail(false, false, 0L, PROTOCOL_VERSION,
                    new byte[TOKEN_BYTES], List.of(), List.of());
        }
    }

    /** 客户端用于唯一元素 identity 的「备份 TCP 控制端点」。 */
    public record ControlEndpoint(String host, int port, int priority) {
        public ControlEndpoint {
            if (host == null || host.isEmpty() || host.length() > MAX_HOST_BYTES) {
                throw new IllegalArgumentException("invalid control host: " + host);
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("invalid control port: " + port);
            }
            if (priority < 0) {
                throw new IllegalArgumentException("invalid control priority: " + priority);
            }
        }
    }

    /** 客户端用于唯一元素 identity 的「UDP 数据端点广播信息」。 */
    public record UdpEndpointInfo(String host, int port, int weight, int endpointId) {
        public UdpEndpointInfo {
            if (host == null || host.isEmpty() || host.length() > MAX_HOST_BYTES) {
                throw new IllegalArgumentException("invalid udp host: " + host);
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("invalid udp port: " + port);
            }
            if (weight < 0) {
                throw new IllegalArgumentException("invalid udp weight: " + weight);
            }
            if (endpointId < 0) {
                throw new IllegalArgumentException("invalid endpointId: " + endpointId);
            }
        }
    }

    public static void writeS2C(ByteBuf out, S2CTail tail) {
        int flags = (tail.hasUdpDataplane() ? 0x01 : 0)
                  | (tail.hasControlFailover() ? 0x02 : 0);
        out.writeByte(flags & 0xFF);
        out.writeLong(tail.connectionEpoch());
        writeVarInt(out, tail.protocol());
        writeVarInt(out, tail.token().length);
        out.writeBytes(tail.token());
        writeVarInt(out, tail.controlEndpoints().size());
        for (ControlEndpoint e : tail.controlEndpoints()) {
            writeHost(out, e.host());
            out.writeShort(e.port());
            writeVarInt(out, e.priority());
        }
        writeVarInt(out, tail.udpEndpoints().size());
        for (UdpEndpointInfo e : tail.udpEndpoints()) {
            writeHost(out, e.host());
            out.writeShort(e.port());
            writeVarInt(out, e.weight());
            writeVarInt(out, e.endpointId());
        }
    }

    public static S2CTail readS2C(ByteBuf in) {
        if (in == null || !in.isReadable()) {
            return S2CTail.disabled();
        }
        int flags = in.readByte() & 0xFF;
        boolean hasUdp = (flags & 0x01) != 0;
        boolean hasFailover = (flags & 0x02) != 0;
        if (!in.isReadable(8)) {
            throw new IllegalArgumentException("S2C tail truncated: missing connectionEpoch");
        }
        long epoch = in.readLong();
        int protocol = readVarInt(in);
        int tokenLen = readVarInt(in);
        if (tokenLen != TOKEN_BYTES) {
            throw new IllegalArgumentException("token length must be 16, was " + tokenLen);
        }
        if (!in.isReadable(TOKEN_BYTES)) {
            throw new IllegalArgumentException("S2C tail truncated: token");
        }
        byte[] token = new byte[TOKEN_BYTES];
        in.readBytes(token);

        int controlCount = readVarInt(in);
        if (controlCount < 0 || controlCount > MAX_ENDPOINTS) {
            throw new IllegalArgumentException("controlEndpoints count out of range: " + controlCount);
        }
        java.util.HashSet<Integer> seenCtrl = new java.util.HashSet<>();
        List<ControlEndpoint> ctrl = new ArrayList<>(controlCount);
        for (int i = 0; i < controlCount; i++) {
            String host = readHost(in);
            int port = in.readUnsignedShort();
            int priority = readVarInt(in);
            ControlEndpoint ep = new ControlEndpoint(host, port, priority);
            if (!seenCtrl.add(ep.port() * 100003 + host.hashCode())) {
                throw new IllegalArgumentException("duplicate control endpoint: " + host + ":" + port);
            }
            ctrl.add(ep);
        }

        int udpCount = readVarInt(in);
        if (udpCount < 0 || udpCount > MAX_ENDPOINTS) {
            throw new IllegalArgumentException("udpEndpoints count out of range: " + udpCount);
        }
        java.util.HashSet<Integer> seenUdp = new java.util.HashSet<>();
        List<UdpEndpointInfo> udp = new ArrayList<>(udpCount);
        for (int i = 0; i < udpCount; i++) {
            String host = readHost(in);
            int port = in.readUnsignedShort();
            int weight = readVarInt(in);
            int eid = readVarInt(in);
            UdpEndpointInfo ep = new UdpEndpointInfo(host, port, weight, eid);
            if (!seenUdp.add(eid)) {
                throw new IllegalArgumentException("duplicate udp endpointId: " + eid);
            }
            udp.add(ep);
        }

        return new S2CTail(hasUdp, hasFailover, epoch, protocol, token,
                Collections.unmodifiableList(ctrl), Collections.unmodifiableList(udp));
    }

    // ===== helpers =====

    private static void writeHost(ByteBuf out, String host) {
        byte[] utf8 = host.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (utf8.length > MAX_HOST_BYTES) {
            throw new IllegalArgumentException("host too long: " + host);
        }
        writeVarInt(out, utf8.length);
        out.writeBytes(utf8);
    }

    private static String readHost(ByteBuf in) {
        int len = readVarInt(in);
        if (len <= 0 || len > MAX_HOST_BYTES || !in.isReadable(len)) {
            throw new IllegalArgumentException("invalid host length: " + len);
        }
        byte[] b = new byte[len];
        in.readBytes(b);
        return new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void writeVarInt(ByteBuf out, int v) {
        if (v < 0) throw new IllegalArgumentException("varint must be non-negative: " + v);
        while ((v & ~0x7F) != 0) {
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v & 0x7F);
    }

    private static int readVarInt(ByteBuf in) {
        int value = 0, shift = 0;
        int b;
        do {
            if (!in.isReadable()) {
                throw new IllegalArgumentException("varint truncated");
            }
            b = in.readByte() & 0xFF;
            value |= (b & 0x7F) << shift;
            shift += 7;
            if (shift > 35) {
                throw new IllegalArgumentException("varint too large");
            }
        } while ((b & 0x80) != 0);
        return value;
    }
}
