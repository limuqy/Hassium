package io.github.limuqy.mc.hassium.network.core.outbound;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;

/**
 * outbound TCP 控制面帧协议（网关自有通道；纯 Netty 零 MC 依赖）。
 *
 * <p>线格式：{@code [varint 帧长(含 type 与 payload)] [type 1B] [payload 字节]}。
 * 帧长 VarInt 编码与 {@code FriendlyByteBuf#writeVarInt} 完全一致（5 字节上限，7bit 组）。
 *
 * <p>用法：
 * <ul>
 *   <li>发送：{@link #encodeFrame} 产出完整帧写入 channel。</li>
 *   <li>接收：{@link #tryDecodeFrame} 从累积缓冲解析一帧；数据不足返回 {@code null} 且不消费；
 *       未知帧类型抛 {@link IllegalArgumentException}（调用方断开连接防死循环）。
 *       Netty 管道由 {@link OutboundConnection} 以 {@code ByteToMessageDecoder} 形式挂载。</li>
 * </ul>
 *
 * <p>payload 所有权：{@link Frame#payload()} 为 retained slice，处理方必须
 * {@code release()}（OutboundConnection 的入站分发保证释放）。
 *
 * <p>ZSTD 压缩位于帧协议之外（握手明文 → 接受后装 ZstdContextDecoder/SkipAwareZstdEncoder，
 * 复用现有组件、不改其原挂载），见 {@link OutboundConnection#installZstd}。
 */
public final class ControlFrameCodec {

    private ControlFrameCodec() {
    }

    /** 解析出的一帧（payload 为 retained slice，处理方负责 release）。 */
    public record Frame(ControlFrameType type, ByteBuf payload) {
    }

    /**
     * 编码一帧。
     *
     * @param payload 读取期间不被并发修改；帧编码后 payload 的 readerIndex 不移动。
     */
    public static ByteBuf encodeFrame(ControlFrameType type, ByteBuf payload) {
        int payloadLen = payload.readableBytes();
        ByteBuf frame = Unpooled.buffer(5 + 1 + payloadLen);
        writeVarInt(frame, 1 + payloadLen);
        frame.writeByte(type.id());
        frame.writeBytes(payload, payload.readerIndex(), payloadLen);
        return frame;
    }

    /**
     * 从累积缓冲解析一帧；数据不足返回 {@code null}（不消费任何字节）。
     * 帧长非法（{@code < 1}）或类型未知时抛 {@link IllegalArgumentException}。
     */
    public static Frame tryDecodeFrame(ByteBuf in) {
        if (!in.isReadable()) {
            return null;
        }
        in.markReaderIndex();
        int frameLen = readVarInt(in);
        if (frameLen < 1) {
            in.resetReaderIndex();
            throw new IllegalArgumentException("invalid control frame length: " + frameLen);
        }
        if (in.readableBytes() < frameLen) {
            in.resetReaderIndex();
            return null;
        }
        int typeId = in.readByte() & 0xFF;
        ControlFrameType type = ControlFrameType.fromId(typeId);
        if (type == null) {
            in.resetReaderIndex();
            throw new IllegalArgumentException("unknown control frame type: " + typeId);
        }
        ByteBuf payload = in.readRetainedSlice(frameLen - 1);
        return new Frame(type, payload);
    }

    // ---- VarInt / UTF-8 助手（与 FriendlyByteBuf 线格式一致，供握手编解码与测试复用） ----

    /** 写入 VarInt（≤5 字节，7bit 组，高位置 1 续组）。 */
    public static void writeVarInt(ByteBuf out, int value) {
        int v = value;
        while ((v & ~0x7F) != 0) {
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v);
    }

    /** 读取 VarInt（最多 5 字节；超限抛 {@link IllegalArgumentException}）。 */
    public static int readVarInt(ByteBuf in) {
        int result = 0;
        int shift = 0;
        for (int i = 0; i < 5; i++) {
            if (!in.isReadable()) {
                throw new IllegalArgumentException("truncated varint");
            }
            byte b = in.readByte();
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
        }
        throw new IllegalArgumentException("varint too big");
    }

    /** 写入 UTF-8 字符串：varint 字节长 + UTF-8 字节。 */
    public static void writeUtf(ByteBuf out, String str) {
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.writeBytes(bytes);
    }

    /** 读取 UTF-8 字符串（varint 字节长 + UTF-8 字节）。 */
    public static String readUtf(ByteBuf in) {
        int len = readVarInt(in);
        if (len < 0 || len > in.readableBytes()) {
            throw new IllegalArgumentException("invalid utf length: " + len);
        }
        byte[] bytes = new byte[len];
        in.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
