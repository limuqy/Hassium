package io.github.limuqy.mc.hassium.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T7 — {@link HandshakeStateTail} append-only 尾部编解码（C2S 状态+续流 / S2C 就绪标记）。
 */
class HandshakeStateTailTest {

    @Test
    @DisplayName("C2S 尾部往返：完整状态 + 续流票据完全一致")
    void c2sRoundTripsFullStateAndTicket() {
        PlayerStateReport state = new PlayerStateReport(12.5, 64.0, -33.25, 45.0f, 12.0f, "minecraft:overworld");
        byte[] ticket = ResumeTicket.sign(java.util.UUID.randomUUID(), 99L, new byte[]{9, 9, 9});

        ByteBuf buf = Unpooled.buffer();
        HandshakeStateTail.writeC2S(buf, new HandshakeStateTail.C2S(state, true, ticket, null, true));

        HandshakeStateTail.C2S decoded = HandshakeStateTail.readC2S(buf);
        assertNotNull(decoded);
        assertEquals(state, decoded.state());
        assertTrue(decoded.resumeRequested());
        assertArrayEquals(ticket, decoded.resumeTicket());
        assertNull(decoded.playerId());
        assertTrue(decoded.lightComputeSupported(), "A7 lightComputeSupported 字段往返一致");
    }

    @Test
    @DisplayName("C2S 尾部往返：T10 玩家 UUID 追加字段一致（旧端读新帧容忍尾字节）")
    void c2sRoundTripsPlayerId() {
        PlayerStateReport state = new PlayerStateReport(12.5, 64.0, -33.25, 45.0f, 12.0f, "minecraft:overworld");
        java.util.UUID playerId = java.util.UUID.randomUUID();

        ByteBuf buf = Unpooled.buffer();
        HandshakeStateTail.writeC2S(buf, new HandshakeStateTail.C2S(state, false, null, playerId, true));

        HandshakeStateTail.C2S decoded = HandshakeStateTail.readC2S(buf);
        assertNotNull(decoded);
        assertEquals(state, decoded.state());
        assertFalse(decoded.resumeRequested());
        assertNull(decoded.resumeTicket());
        assertEquals(playerId, decoded.playerId(), "T10 playerId 字段往返一致");
        assertTrue(decoded.lightComputeSupported(), "A7 lightComputeSupported 字段往返一致");

        // 兼容性：旧客户端格式（无 playerId/light 字节）→ 新端读 playerId=null、light=false
        ByteBuf legacy = Unpooled.buffer();
        HandshakeStateTail.writeC2S(legacy, new HandshakeStateTail.C2S(state, true, new byte[]{1}, null, true));
        ByteBuf truncated = Unpooled.buffer();
        truncated.writeBytes(legacy, 0, legacy.readableBytes() - 1); // 截掉 light 布尔字节（模拟旧客户端尾）
        HandshakeStateTail.C2S legacyDecoded = HandshakeStateTail.readC2S(truncated);
        assertNotNull(legacyDecoded);
        assertNull(legacyDecoded.playerId(), "旧格式无 playerId → null");
        assertTrue(legacyDecoded.resumeRequested());
        assertFalse(legacyDecoded.lightComputeSupported(), "缺尾默认 false");
        legacy.release();
        truncated.release();
    }

    @Test
    @DisplayName("旧包（无尾部字节）→ readC2S 返回 null，readS2C 返回 notAccepted")
    void oldPacketDecodesToDefaults() {
        assertNull(HandshakeStateTail.readC2S(Unpooled.buffer()), "无可读字节应返回 null");
        assertNull(HandshakeStateTail.readC2S(null));
        assertFalse(HandshakeStateTail.readS2C(Unpooled.buffer()).resumeAccepted());
    }

    @Test
    @DisplayName("C2S 尾部损坏/截断 → readC2S 返回 null（回退旧字段）")
    void truncatedTailDecodesToNull() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeDouble(1.0);
        buf.writeDouble(2.0);
        // 只有 2 个 double，缺 y/z/yaw/pitch/dim → 解析失败
        assertNull(HandshakeStateTail.readC2S(buf));
    }

    @Test
    @DisplayName("S2C 尾部往返：resumeAccepted 一致")
    void s2cRoundTrips() {
        ByteBuf buf = Unpooled.buffer();
        HandshakeStateTail.writeS2C(buf, new HandshakeStateTail.S2C(true));
        assertTrue(HandshakeStateTail.readS2C(buf).resumeAccepted());

        ByteBuf buf2 = Unpooled.buffer();
        HandshakeStateTail.writeS2C(buf2, new HandshakeStateTail.S2C(false));
        assertFalse(HandshakeStateTail.readS2C(buf2).resumeAccepted());
    }

    @Test
    @DisplayName("新客户端尾部对旧服务端语义：写入固定长度后可忽略（读端模拟旧服务端读前序字段）")
    void newTailIsAppendOnly() {
        // 新客户端尾部对旧服务端语义：写入固定长度后可忽略（读端模拟旧服务端读前序字段）
        // 尾部恒写 lightComputeSupported（最后 1 字节）；旧服务端读完前序字段后忽略剩余字节
        PlayerStateReport state = new PlayerStateReport(1.0, 2.0, 3.0, 4.0f, 5.0f, "minecraft:overworld");
        ByteBuf buf = Unpooled.buffer();
        // 模拟完整 C2S：前序字段由调用方写，这里只验证尾部追加不影响前序读取
        buf.writeDouble(state.x());
        buf.writeDouble(state.z());
        buf.writeBoolean(true); // seedGen
        buf.writeBoolean(false); // light
        HandshakeStateTail.writeC2S(buf, HandshakeStateTail.C2S.noResume(state));

        // 旧服务端解码前序字段
        assertEquals(1.0, buf.readDouble());
        assertEquals(3.0, buf.readDouble());
        assertTrue(buf.readBoolean());
        assertFalse(buf.readBoolean());
        // 剩余尾部字节（旧服务端忽略）
        assertTrue(buf.isReadable());
    }
}
