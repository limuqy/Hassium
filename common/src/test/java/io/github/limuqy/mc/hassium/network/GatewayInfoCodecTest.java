package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.network.GatewayInfoCodec.Endpoint;
import io.github.limuqy.mc.hassium.network.GatewayInfoCodec.GatewayInfo;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关 bootstrap 载荷线格式验证（CONTRACTS §1）。
 * <p>
 * 字节序：varint protocolVersion | utf modVersion | varint n |
 * n×(utf host | ushort port | varint priority) | utf authToken | bool×3。
 * ushort 按 Netty 默认大端（writeShort/readUnsignedShort 同对）。
 */
class GatewayInfoCodecTest {

    @Test
    void wireLayoutMatchesContract() {
        GatewayInfo info = new GatewayInfo(
                1,
                "2.0.0",
                List.of(
                        new Endpoint("127.0.0.1", 25567, 0),
                        new Endpoint("10.0.0.2", 25566, 5)),
                "tok",
                true, true, false);

        byte[] data = GatewayInfoCodec.encode(info);

        // varint protocolVersion=1 | utf "2.0.0" | varint count=2
        assertArrayEquals(new byte[]{
                0x01,
                0x05, '2', '.', '0', '.', '0',
                0x02
        }, java.util.Arrays.copyOfRange(data, 0, 8), "头部字段序不符 CONTRACTS §1");

        // ep1: utf "127.0.0.1" | ushort 25567 (0x63DF, 大端) | varint priority=0
        assertArrayEquals(new byte[]{
                0x09, '1', '2', '7', '.', '0', '.', '0', '.', '1',
                0x63, (byte) 0xDF,
                0x00
        }, java.util.Arrays.copyOfRange(data, 8, 21), "端点1字段序不符");

        // ep2: utf "10.0.0.2" | ushort 25566 (0x63DE, 大端) | varint priority=5
        assertArrayEquals(new byte[]{
                0x08, '1', '0', '.', '0', '.', '0', '.', '2',
                0x63, (byte) 0xDE,
                0x05
        }, java.util.Arrays.copyOfRange(data, 21, 33), "端点2字段序不符");

        // utf "tok" | bool true | bool true | bool false
        assertArrayEquals(new byte[]{
                0x03, 't', 'o', 'k',
                0x01, 0x01, 0x00
        }, java.util.Arrays.copyOfRange(data, 33, 40), "尾部字段序不符");

        assertEquals(40, data.length, "总长与手算线格式不符");
    }

    @Test
    void fullRoundTrip() {
        GatewayInfo original = new GatewayInfo(
                7,
                "2.0.0",
                List.of(
                        new Endpoint("gw.example.com", 25566, 10),
                        new Endpoint("10.0.0.9", 25567, 5),
                        new Endpoint("127.0.0.1", 25568, 0)),
                "s3cr3t-token-中文",
                true, true, true);

        GatewayInfo decoded = GatewayInfoCodec.decode(GatewayInfoCodec.encode(original));

        assertEquals(original.protocolVersion(), decoded.protocolVersion());
        assertEquals(original.modVersion(), decoded.modVersion());
        assertEquals(original.endpoints(), decoded.endpoints());
        assertEquals(original.authToken(), decoded.authToken());
        assertTrue(decoded.compressionSupported());
        assertTrue(decoded.seedGenSupported());
        assertTrue(decoded.lightComputeSupported());
    }

    @Test
    void emptyRoundTrip() {
        GatewayInfo decoded = GatewayInfoCodec.decode(GatewayInfoCodec.encode(GatewayInfo.EMPTY));

        assertEquals(0, decoded.protocolVersion());
        assertEquals("", decoded.modVersion());
        assertNotNull(decoded.endpoints());
        assertTrue(decoded.endpoints().isEmpty());
        assertEquals("", decoded.authToken());
        assertFalse(decoded.compressionSupported());
        assertFalse(decoded.seedGenSupported());
        assertFalse(decoded.lightComputeSupported());
    }

    @Test
    void decodeFromFriendlyByteBufDirectEntry() {
        byte[] data = GatewayInfoCodec.encode(new GatewayInfo(
                1, "2.0.0", List.of(new Endpoint("127.0.0.1", 25566, 3)), "", false, false, true));

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        GatewayInfo decoded = GatewayInfoCodec.decode(buf);

        assertEquals(1, decoded.protocolVersion());
        assertEquals(1, decoded.endpoints().size());
        assertEquals(25566, decoded.endpoints().get(0).port());
        assertEquals(3, decoded.endpoints().get(0).priority());
        assertTrue(decoded.lightComputeSupported());
        assertFalse(decoded.compressionSupported());
    }

    @Test
    void nullEndpointsAndAuthTokenAreTolerated() {
        GatewayInfo info = new GatewayInfo(1, "2.0.0", null, null, false, false, false);
        GatewayInfo decoded = GatewayInfoCodec.decode(GatewayInfoCodec.encode(info));

        assertNotNull(decoded.endpoints());
        assertTrue(decoded.endpoints().isEmpty());
        assertEquals("", decoded.authToken());
    }
}
