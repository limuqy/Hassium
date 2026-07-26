package io.github.limuqy.mc.hassium.network.dataplane;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * UDP BindRequest v3 线协议编解码。
 *
 * <p>Wire layout: {@code token[16] + uuid[16] + connectionEpoch[long] + protocol(varint) +
 * endpointId(varint) + channelId(varint)}。
 *
 * <p>承载于 KCP 可靠层之上；不再使用外层 VarInt 长度前缀（KCP 自身分帧）。
 * 旧 TCP 版 BindRequest 不在此 codec 适用范围——Task 10 自行清理。
 */
public final class UdpBindRequestCodec {

    /** BindRequest 协议版本；v3 用于 UDP 数据面（含 uuid + epoch + endpointId）。 */
    public static final int PROTOCOL_VERSION = 3;

    /** 缺省合法最小字节：16 + 16 + 8 + 1(protocol) + 1(endpointId) + 1(channelId)。 */
    static final int MIN_BYTES = 34;

    private UdpBindRequestCodec() {}

    /** 解析后的 BindRequest。 */
    public record Request(byte[] token, UUID playerId, long connectionEpoch,
                          int endpointId, int channelId) {}

    /**
     * 编码 BindRequest。
     *
     * @throws IllegalArgumentException token 不是 16 字节、playerId 空、endpointId/channelId 负数
     */
    public static byte[] encodeRequest(byte[] token, UUID playerId, long epoch,
                                       int endpointId, int channelId) {
        if (token == null || token.length != 16) {
            throw new IllegalArgumentException("token must be 16 bytes");
        }
        if (playerId == null) {
            throw new IllegalArgumentException("playerId must not be null");
        }
        if (endpointId < 0 || channelId < 0) {
            throw new IllegalArgumentException("endpointId/channelId must be non-negative");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(48);
        out.writeBytes(token);
        writeLong(out, playerId.getMostSignificantBits());
        writeLong(out, playerId.getLeastSignificantBits());
        writeLong(out, epoch);
        DataPlaneFrame.writeVarInt(out, PROTOCOL_VERSION);
        DataPlaneFrame.writeVarInt(out, endpointId);
        DataPlaneFrame.writeVarInt(out, channelId);
        return out.toByteArray();
    }

    /**
     * 解码 BindRequest。
     *
     * @throws IllegalArgumentException 字节数不足、VarInt 越界、protocol != PROTOCOL_VERSION
     */
    public static Request decodeRequest(byte[] bytes) {
        if (bytes == null || bytes.length < MIN_BYTES) {
            throw new IllegalArgumentException("BindRequest too short");
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        byte[] token = new byte[16];
        buf.get(token);
        long msb = buf.getLong();
        long lsb = buf.getLong();
        UUID playerId = new UUID(msb, lsb);
        long epoch = buf.getLong();
        int protocol = DataPlaneFrame.readVarInt(buf);
        int endpointId = DataPlaneFrame.readVarInt(buf);
        int channelId = DataPlaneFrame.readVarInt(buf);
        if (protocol != PROTOCOL_VERSION) {
            throw new IllegalArgumentException("unsupported bind protocol: " + protocol);
        }
        if (endpointId < 0 || channelId < 0) {
            throw new IllegalArgumentException("BindRequest endpointId/channelId must be non-negative");
        }
        return new Request(token, playerId, epoch, endpointId, channelId);
    }

    private static void writeLong(ByteArrayOutputStream out, long v) {
        out.write((byte) (v >>> 56));
        out.write((byte) (v >>> 48));
        out.write((byte) (v >>> 40));
        out.write((byte) (v >>> 32));
        out.write((byte) (v >>> 24));
        out.write((byte) (v >>> 16));
        out.write((byte) (v >>> 8));
        out.write((byte) v);
    }
}
