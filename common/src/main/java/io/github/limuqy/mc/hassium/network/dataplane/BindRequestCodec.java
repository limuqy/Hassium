package io.github.limuqy.mc.hassium.network.dataplane;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * Data 面 BindRequest v2 编解码。
 * <p>
 * 线格式（硬切换，无 v1 兼容读）：
 * {@code token[16] + uuid[16] + protocol(VarInt=2) + channelId(VarInt)}。
 */
public final class BindRequestCodec {

    /** BindRequest 协议版本；v1 已废弃，服务端拒 protocol≠2。 */
    public static final int PROTOCOL_VERSION = 2;

    /** token(16) + uuid(16) + protocol VarInt 最少 1 + channelId VarInt 最少 1。 */
    public static final int MIN_PAYLOAD_LEN = 34;

    private BindRequestCodec() {}

    public record Parsed(
            byte[] token,
            UUID playerId,
            int protocol,
            int channelId
    ) {}

    /**
     * 编码 BindRequest payload（不含外层 DataPlaneFrame）。
     */
    public static byte[] encode(byte[] token, UUID playerId, int protocol, int channelId) {
        if (token == null || token.length != 16) {
            throw new IllegalArgumentException("token must be 16 bytes");
        }
        if (playerId == null) {
            throw new IllegalArgumentException("playerId required");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(40);
        try {
            out.write(token);
            ByteBuffer uuidBuf = ByteBuffer.allocate(16);
            uuidBuf.putLong(playerId.getMostSignificantBits());
            uuidBuf.putLong(playerId.getLeastSignificantBits());
            out.write(uuidBuf.array());
            DataPlaneFrame.writeVarInt(out, protocol);
            DataPlaneFrame.writeVarInt(out, channelId);
        } catch (IOException e) {
            throw new IllegalStateException("encode BindRequest failed", e);
        }
        return out.toByteArray();
    }

    /**
     * 解析 BindRequest payload。
     *
     * @return 解析结果；失败时抛 {@link IllegalArgumentException}（message 可作 BindAck reason）
     */
    public static Parsed decode(byte[] payload) {
        if (payload == null || payload.length < MIN_PAYLOAD_LEN) {
            throw new IllegalArgumentException("Bad request length");
        }
        byte[] token = new byte[16];
        System.arraycopy(payload, 0, token, 0, 16);
        ByteBuffer buf = ByteBuffer.wrap(payload, 16, payload.length - 16);
        long msb = buf.getLong();
        long lsb = buf.getLong();
        UUID playerId = new UUID(msb, lsb);
        int protocol = DataPlaneFrame.readVarInt(buf);
        int channelId = DataPlaneFrame.readVarInt(buf);
        return new Parsed(token, playerId, protocol, channelId);
    }
}
