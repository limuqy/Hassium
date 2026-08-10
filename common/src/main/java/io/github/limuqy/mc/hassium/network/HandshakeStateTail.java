package io.github.limuqy.mc.hassium.network;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 握手 append-only 状态尾部（T7）：
 * <ul>
 *   <li>C2S：完整玩家状态（x/y/z/yaw/pitch/维度）+ 续流标记 + 票据字节（{@link ResumeTicket#encode()}）</li>
 *   <li>S2C：续流就绪标记（resumeAccepted）</li>
 * </ul>
 * <p>
 * 挂载点：C2S 位于 lightComputeSupported 之后；S2C 位于 SeedGen 尾部之后。
 * 旧端忽略尾字节；新端对缺失/损坏尾部取默认（{@link #readC2S} 返回 null，
 * 调用方回退旧字段）。维度为 UTF-8 字符串（varint 长度前缀，同 FriendlyByteBuf.writeUtf）。
 */
public final class HandshakeStateTail {

    /** C2S：完整玩家状态 + 是否请求续流 + 票据字节 + 玩家 UUID（T10 标准流程握手附着用）+ lightComputeSupported（A7 客户端影子光照能力） */
    public record C2S(PlayerStateReport state, boolean resumeRequested, byte[] resumeTicket, UUID playerId,
                      boolean lightComputeSupported) {
        public static C2S noResume(PlayerStateReport state) {
            return new C2S(state, false, null, null, false);
        }

        /** 标准流程（非续流）：携带玩家 UUID，主控据此把网关会话附着到 vanilla 物化玩家。 */
        public static C2S ident(PlayerStateReport state, UUID playerId) {
            return new C2S(state, false, null, playerId, false);
        }
    }

    /** S2C：服务端是否接受续流（「续流就绪」） */
    public record S2C(boolean resumeAccepted) {
        public static S2C notAccepted() {
            return new S2C(false);
        }
    }

    private HandshakeStateTail() {}

    public static void writeC2S(ByteBuf buf, C2S tail) {
        PlayerStateReport s = tail.state() != null ? tail.state() : PlayerStateReport.absent();
        buf.writeDouble(s.x());
        buf.writeDouble(s.y());
        buf.writeDouble(s.z());
        buf.writeFloat(s.yaw());
        buf.writeFloat(s.pitch());
        writeUtf(buf, s.dimension() != null ? s.dimension() : "");
        buf.writeBoolean(tail.resumeRequested());
        // review-fix: T2-77: resumeRequested=true 时恒写票据长度字段（ticket==null → 0），
        // 消除写侧跳过、读侧假定存在的结构不对称；readC2S 结构假定不变（resumeRequested=false
        // 仍不写，避免读端字段错位）
        if (tail.resumeRequested()) {
            int ticketLen = tail.resumeTicket() != null ? tail.resumeTicket().length : 0;
            buf.writeInt(ticketLen);
            if (ticketLen > 0) {
                buf.writeBytes(tail.resumeTicket());
            }
        }
        // T10 追加字段（append-only）：玩家 UUID（标准流程握手附着；旧端/新端读旧帧兼容）
        if (tail.playerId() != null) {
            buf.writeBoolean(true);
            buf.writeLong(tail.playerId().getMostSignificantBits());
            buf.writeLong(tail.playerId().getLeastSignificantBits());
        } else {
            buf.writeBoolean(false);
        }
        // A7 追加字段（append-only）：lightComputeSupported（客户端影子光照能力；恒写，旧端忽略尾字节）
        buf.writeBoolean(tail.lightComputeSupported());
    }

    /** 读取 C2S 尾部；无可读字节或解析失败 → null */
    public static C2S readC2S(ByteBuf buf) {
        if (buf == null || !buf.isReadable()) {
            return null;
        }
        try {
            double x = buf.readDouble();
            double y = buf.readDouble();
            double z = buf.readDouble();
            float yaw = buf.readFloat();
            float pitch = buf.readFloat();
            String dimension = readUtf(buf);
            boolean resumeRequested = false;
            if (buf.isReadable()) {
                resumeRequested = buf.readBoolean();
            }
            byte[] ticket = null;
            if (resumeRequested && buf.isReadable()) {
                int len = buf.readInt();
                if (len > 0 && len <= buf.readableBytes()) {
                    ticket = new byte[len];
                    buf.readBytes(ticket);
                }
            }
            UUID playerId = null;
            if (buf.isReadable()) {
                boolean hasPlayerId = buf.readBoolean();
                if (hasPlayerId && buf.readableBytes() >= 16) {
                    playerId = new UUID(buf.readLong(), buf.readLong());
                }
            }
            // A7 追加字段（append-only）：缺尾/旧端默认 false（旧帧无此字节）
            boolean lightComputeSupported = false;
            if (buf.isReadable()) {
                lightComputeSupported = buf.readBoolean();
            }
            return new C2S(new PlayerStateReport(x, y, z, yaw, pitch, dimension), resumeRequested, ticket, playerId,
                    lightComputeSupported);
        } catch (Exception e) {
            return null;
        }
    }

    public static void writeS2C(ByteBuf buf, S2C tail) {
        buf.writeBoolean(tail.resumeAccepted());
    }

    /** 读取 S2C 尾部；无可读字节/损坏 → notAccepted */
    public static S2C readS2C(ByteBuf buf) {
        if (buf == null || !buf.isReadable()) {
            return S2C.notAccepted();
        }
        try {
            return new S2C(buf.readBoolean());
        } catch (Exception e) {
            return S2C.notAccepted();
        }
    }

    private static void writeUtf(ByteBuf buf, String str) {
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        writeVarInt(buf, bytes.length);
        buf.writeBytes(bytes);
    }

    private static String readUtf(ByteBuf buf) {
        int len = readVarInt(buf);
        if (len < 0 || len > 1024 || len > buf.readableBytes()) {
            throw new IllegalArgumentException("Bad handshake tail string length " + len);
        }
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeVarInt(ByteBuf buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    private static int readVarInt(ByteBuf buf) {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            int b = buf.readByte();
            value |= (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new IllegalArgumentException("Bad handshake tail varint");
    }
}
