package io.github.limuqy.mc.hassium.network.core;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.network.HandshakeStateTail;
import io.github.limuqy.mc.hassium.network.PlayerStateReport;
import io.github.limuqy.mc.hassium.network.core.outbound.ControlFrameCodec;
import io.github.limuqy.mc.hassium.network.core.outbound.ControlFrameType;
import io.github.limuqy.mc.hassium.network.core.outbound.HandshakeCodec;
import io.github.limuqy.mc.hassium.network.dataplane.UdpDataPlaneHandshakeTail;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关握手编解码线格式验证（对齐三端 NetworkManager 内联格式；append-only 尾向后兼容）。
 */
class HandshakeCodecTest {

    private static final byte[] TOKEN = new byte[16];

    @Test
    void clientRequestWireLayout() {
        HandshakeCodec.ClientRequestOptions opts = new HandshakeCodec.ClientRequestOptions(
                Constants.CURRENT_PROTOCOL_VERSION, Constants.MOD_VERSION,
                Constants.NETWORK_COMPRESSION_ALGORITHM,
                true, true, 12.5, -34.0, true, false);
        ByteBuf buf = HandshakeCodec.encodeClientRequest(opts);
        try {
            assertEquals(Constants.CURRENT_PROTOCOL_VERSION, ControlFrameCodec.readVarInt(buf));
            assertEquals(Constants.MOD_VERSION, ControlFrameCodec.readUtf(buf));
            assertEquals(2, ControlFrameCodec.readVarInt(buf)); // 算法数量
            assertEquals(Constants.NETWORK_COMPRESSION_ALGORITHM, ControlFrameCodec.readUtf(buf));
            assertEquals(Constants.NETWORK_COMPRESSION_ALGORITHM + "_dict", ControlFrameCodec.readUtf(buf));
            assertTrue(buf.readBoolean());   // clientCacheSupported
            assertTrue(buf.readBoolean());   // chunkRevisionSupported
            assertFalse(buf.readBoolean());  // scheme127Supported
            assertTrue(buf.readBoolean());   // globalPacketCompressionSupported
            assertTrue(buf.readBoolean());   // compactHeaderSupported
            assertEquals(0x03, buf.readByte() & 0xFF); // udp 数据面 + 控制 failover 能力
            assertEquals(12.5, buf.readDouble(), 0.0);
            assertEquals(-34.0, buf.readDouble(), 0.0);
            assertTrue(buf.readBoolean());   // seedGenSupported
            assertFalse(buf.readBoolean());  // engineEnabled
            assertFalse(buf.isReadable(), "无残留尾字节");
        } finally {
            buf.release();
        }
    }

    @Test
    void serverResponseRoundTrip() {
        UdpDataPlaneHandshakeTail.S2CTail tail = new UdpDataPlaneHandshakeTail.S2CTail(
                false, true, 7L, 1, TOKEN, List.of(), List.of(), List.of());
        byte[] stem = new byte[] {1, 2, 3};
        ByteBuf encoded = HandshakeCodec.encodeServerResponse(
                3, true, true, true, tail, 12345L, stem, true);
        try {
            HandshakeCodec.ServerResponse resp = HandshakeCodec.decodeServerResponse(encoded);
            assertTrue(resp.accepted());
            assertEquals(3, resp.protocolVersion());
            assertTrue(resp.globalCompressionAccepted());
            assertTrue(resp.compactHeaderAccepted());
            assertNotNull(resp.udpTail());
            assertFalse(resp.udpTail().hasUdpDataplane());
            assertTrue(resp.udpTail().hasControlFailover());
            assertEquals(7L, resp.udpTail().connectionEpoch());
            assertEquals(12345L, resp.worldSeed());
            assertArrayEquals(stem, resp.levelStemNbt());
            assertTrue(resp.seedGenEnabled());
        } finally {
            encoded.release();
        }
    }

    @Test
    void legacyServerFixedFieldsOnly() {
        // 旧服务端：只有 4 个固定字段，无任何尾
        ByteBuf buf = Unpooled.buffer();
        ControlFrameCodec.writeVarInt(buf, 1);
        buf.writeBoolean(true);  // accepted
        buf.writeBoolean(false); // globalCompression
        buf.writeBoolean(false); // compactHeader
        try {
            HandshakeCodec.ServerResponse resp = HandshakeCodec.decodeServerResponse(buf);
            assertTrue(resp.accepted());
            assertFalse(resp.globalCompressionAccepted());
            assertFalse(resp.compactHeaderAccepted());
            assertNull(resp.udpTail(), "旧服务端无 UDP tail");
            assertEquals(0L, resp.worldSeed());
            assertNull(resp.levelStemNbt());
            assertFalse(resp.seedGenEnabled());
        } finally {
            buf.release();
        }
    }

    @Test
    void rejectedResponse() {
        ByteBuf encoded = HandshakeCodec.encodeServerResponse(1, false, false, false, null, 0, null, false);
        try {
            HandshakeCodec.ServerResponse resp = HandshakeCodec.decodeServerResponse(encoded);
            assertFalse(resp.accepted());
            assertNull(resp.udpTail());
        } finally {
            encoded.release();
        }
    }

    @Test
    void controlFrameRoundTrip() {
        ByteBuf payload = Unpooled.buffer().writeInt(42);
        ByteBuf frame = ControlFrameCodec.encodeFrame(ControlFrameType.PACKET_C2S, payload);
        try {
            ControlFrameCodec.Frame decoded = ControlFrameCodec.tryDecodeFrame(frame);
            assertNotNull(decoded);
            assertEquals(ControlFrameType.PACKET_C2S, decoded.type());
            assertEquals(42, decoded.payload().readInt());
            decoded.payload().release();
            assertFalse(frame.isReadable(), "帧缓冲应被完全消费");
        } finally {
            frame.release();
        }
    }

    @Test
    void partialFrameNotConsumed() {
        ByteBuf payload = Unpooled.buffer().writeInt(42);
        ByteBuf frame = ControlFrameCodec.encodeFrame(ControlFrameType.PING, payload);
        try {
            ByteBuf partial = frame.retainedSlice(0, 2);
            try {
                ControlFrameCodec.Frame decoded = ControlFrameCodec.tryDecodeFrame(partial);
                assertNull(decoded, "数据不足应返回 null");
                assertEquals(0, partial.readerIndex(), "数据不足不得消费");
            } finally {
                partial.release();
            }
        } finally {
            frame.release();
        }
    }

    @Test
    void clientRequestDecodeRoundTrip() {
        // T11 master 镜像：encodeClientRequest → decodeClientRequest 全字段一致
        HandshakeCodec.ClientRequestOptions opts = new HandshakeCodec.ClientRequestOptions(
                Constants.CURRENT_PROTOCOL_VERSION, Constants.MOD_VERSION,
                Constants.NETWORK_COMPRESSION_ALGORITHM,
                true, false, 12.5, -34.0, true, false);
        ByteBuf buf = HandshakeCodec.encodeClientRequest(opts);
        try {
            HandshakeCodec.ClientRequestOptions decoded = HandshakeCodec.decodeClientRequest(buf);
            assertEquals(opts.protocolVersion(), decoded.protocolVersion());
            assertEquals(opts.modVersion(), decoded.modVersion());
            assertEquals(opts.compressionAlgorithm(), decoded.compressionAlgorithm());
            assertEquals(opts.udpDataplaneSupported(), decoded.udpDataplaneSupported());
            assertEquals(opts.controlFailoverSupported(), decoded.controlFailoverSupported());
            assertEquals(opts.posX(), decoded.posX(), 0.0);
            assertEquals(opts.posZ(), decoded.posZ(), 0.0);
            assertEquals(opts.seedGenSupported(), decoded.seedGenSupported());
            assertEquals(opts.engineEnabled(), decoded.engineEnabled());
            assertFalse(buf.isReadable(), "固定字段应被完全消费（append-only 尾留给调用方）");
        } finally {
            buf.release();
        }
    }

    @Test
    void clientRequestDecodeKeepsAppendOnlyTail() {
        // 固定字段之后追加 T7 状态尾：decodeClientRequest 不得消费，调用方 readC2S 可得
        HandshakeCodec.ClientRequestOptions opts = new HandshakeCodec.ClientRequestOptions(
                Constants.CURRENT_PROTOCOL_VERSION, Constants.MOD_VERSION,
                Constants.NETWORK_COMPRESSION_ALGORITHM,
                true, true, 0.0, 0.0, false, true);
        ByteBuf buf = HandshakeCodec.encodeClientRequest(opts);
        HandshakeStateTail.C2S tail = new HandshakeStateTail.C2S(
                new PlayerStateReport(10.5, 64.0, 20.25, 90.0f, 0.0f, "minecraft:overworld"),
                true, new byte[] {1, 2, 3}, null, true);
        HandshakeStateTail.writeC2S(buf, tail);
        try {
            HandshakeCodec.ClientRequestOptions decoded = HandshakeCodec.decodeClientRequest(buf);
            assertEquals(opts.engineEnabled(), decoded.engineEnabled());
            HandshakeStateTail.C2S got = HandshakeStateTail.readC2S(buf);
            assertNotNull(got, "append-only 尾应保留给调用方");
            assertTrue(got.resumeRequested());
            assertEquals(10.5, got.state().x(), 0.0);
            assertEquals("minecraft:overworld", got.state().dimension());
            assertArrayEquals(new byte[] {1, 2, 3}, got.resumeTicket());
            assertTrue(got.lightComputeSupported(), "A7 lightComputeSupported 尾字段往返一致");
        } finally {
            buf.release();
        }
    }
}
