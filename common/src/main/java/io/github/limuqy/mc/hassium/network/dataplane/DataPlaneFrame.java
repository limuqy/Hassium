package io.github.limuqy.mc.hassium.network.dataplane;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;

public class DataPlaneFrame {

    public static final int TYPE_BIND_REQUEST = 1;
    public static final int TYPE_BIND_ACK = 2;
    public static final int TYPE_BULK_COMPRESSED_CHUNK = 3;
    public static final int TYPE_BULK_SECTION_DELTA = 4;
    public static final int TYPE_KEEPALIVE = 5;
    public static final int TYPE_KEEPALIVE_ACK = 6;
    public static final int TYPE_CLOSE = 7;
    public static final int TYPE_FAILOVER_REQUEST = 8;
    public static final int TYPE_FAILOVER_PERMIT = 9;

    private static final int MIN_TYPE = 1;
    private static final int MAX_TYPE = 9;

    /** 编码：VarInt(frameLen) + type(u8) + payload。frameLen = 1 + payload.length */
    public static byte[] encode(int type, byte[] payload) {
        if (type < MIN_TYPE || type > MAX_TYPE) throw new IllegalArgumentException("Invalid frame type: " + type);
        int payloadLen = payload != null ? payload.length : 0;
        int frameLen = 1 + payloadLen; // type byte + payload
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarInt(out, frameLen);
        out.write(type);
        if (payloadLen > 0) out.writeBytes(payload);
        return out.toByteArray();
    }

    /** 从完整帧中提取 type */
    public static int decodeType(byte[] frame) {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(frame);
        int frameLen = readVarInt(buf);
        // review-fix: T4-84 — long 累加防 5 字节 VarInt（上限 2^35-1）整数溢出绕过截断检查；
        // frameLen < 1 直接拒绝（合法帧 frameLen = 1 + payload 恒 >= 1）。
        if (frameLen < 1 || frame.length < (long) frameLen + varIntSize(frameLen)) {
            throw new IllegalArgumentException("Truncated frame");
        }
        int type = buf.get() & 0xFF;
        // review-fix: T4-84 — 类型范围校验：解码侧与 encode 侧一致，拒绝 0 与 10..255 的非法类型，
        // 取代原先「返回任意 0..255」的宽容行为。
        if (type < MIN_TYPE || type > MAX_TYPE) {
            throw new IllegalArgumentException("Invalid frame type: " + type);
        }
        return type;
    }

    /** 从完整帧中提取 payload */
    public static byte[] decodePayload(byte[] frame) {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(frame);
        int frameLen = readVarInt(buf);
        int headerSize = varIntSize(frameLen);
        // review-fix: T4-84 — 同 decodeType：long 累加防溢出；frameLen < 1 拒绝（防 dataLen=-1 负数组分配）。
        if (frameLen < 1 || frame.length < (long) headerSize + frameLen) {
            throw new IllegalArgumentException("Truncated frame");
        }
        buf.get(); // 跳过 type 字节
        int dataLen = frameLen - 1; // payload 长度
        byte[] payload = new byte[dataLen];
        if (dataLen > 0) buf.get(payload);
        return payload;
    }

    // ---- VarInt helpers (MC-compatible 7-bit encoding) ----

    static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    static int readVarInt(java.nio.ByteBuffer buf) {
        int value = 0, shift = 0;
        byte b;
        do {
            if (!buf.hasRemaining()) {
                // CFB8 错误密钥解出乱码时，连续 0x80 会使循环无法终止 → 这里截断保护。
                // 返回已读到的 value（截断），调用方据此判断帧不完整。
                break;
            }
            b = buf.get();
            value |= (b & 0x7F) << shift;
            shift += 7;
            // VarInt 最多 5 字节（int32）；超长视为损坏帧，截断保护。
            if (shift >= 35) break;
        } while ((b & 0x80) != 0);
        return value;
    }

    static int varIntSize(int value) {
        int n = 1;
        while ((value & ~0x7F) != 0) { n++; value >>>= 7; }
        return n;
    }
}
