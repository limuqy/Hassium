package io.github.limuqy.mc.hassium.network.dataplane;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 5 — {@link UdpDataPlaneHandshakeTail} append-only tail codec.
 */
class UdpDataPlaneHandshakeTailTest {

    @Test
    @DisplayName("旧包无可读尾 → S2C 返回 disabled 默认（hasUdpDataplane=false）")
    void oldHandshakeWithNoTailDecodesAsDisabled() {
        ByteBuf buf = Unpooled.buffer();
        assertFalse(UdpDataPlaneHandshakeTail.readS2C(buf).hasUdpDataplane());
    }

    @Test
    @DisplayName("S2C tail 往返：epoch / control / udp / token / protocol 完全一致")
    void s2CRoundTripsEndpointsTokenEpochAndCandidates() {
        byte[] token = new byte[16];
        for (int i = 0; i < token.length; i++) token[i] = (byte) i;
        var expected = new UdpDataPlaneHandshakeTail.S2CTail(
                true, true, 7777L, 3, token,
                List.of(
                    new UdpDataPlaneHandshakeTail.ControlEndpoint("b.example.com", 25565, 80),
                    new UdpDataPlaneHandshakeTail.ControlEndpoint("c.example.com", 25566, 50)
                ),
                List.of(
                    new UdpDataPlaneHandshakeTail.UdpEndpointInfo("a.example.com", 25565, 100, 1),
                    new UdpDataPlaneHandshakeTail.UdpEndpointInfo("a.example.com", 25566, 50, 2)
                ));
        ByteBuf buf = Unpooled.buffer();
        UdpDataPlaneHandshakeTail.writeS2C(buf, expected);
        var decoded = UdpDataPlaneHandshakeTail.readS2C(buf);

        assertTrue(decoded.hasUdpDataplane());
        assertTrue(decoded.hasControlFailover());
        assertEquals(7777L, decoded.connectionEpoch());
        assertEquals(3, decoded.protocol());
        assertArrayEquals(token, decoded.token());
        assertEquals(expected.controlEndpoints(), decoded.controlEndpoints());
        assertEquals(expected.udpEndpoints(), decoded.udpEndpoints());
    }
    @Test
    @DisplayName("S2C tail 往返分组 UDP candidates，同时保留 legacy projection")
    void roundTripsGroupsWhileKeepingLegacyProjection() {
        var legacyEndpoints = List.of(
                new UdpDataPlaneHandshakeTail.UdpEndpointInfo("edge-a.example", 41001, 60, 0),
                new UdpDataPlaneHandshakeTail.UdpEndpointInfo("edge-c.example", 43001, 40, 1));
        var groups = List.of(
                new UdpDataPlaneHandshakeTail.UdpListenerGroup(0, 60, List.of(
                        new UdpDataPlaneHandshakeTail.UdpReachableEndpoint("edge-a.example", 41001, 100),
                        new UdpDataPlaneHandshakeTail.UdpReachableEndpoint("edge-b.example", 42001, 80))),
                new UdpDataPlaneHandshakeTail.UdpListenerGroup(1, 40, List.of(
                        new UdpDataPlaneHandshakeTail.UdpReachableEndpoint("edge-c.example", 43001, 100))));
        var expected = new UdpDataPlaneHandshakeTail.S2CTail(
                true, true, 7777L, 3, new byte[16], List.of(), legacyEndpoints, groups);
        ByteBuf buf = Unpooled.buffer();

        UdpDataPlaneHandshakeTail.writeS2C(buf, expected);

        var decoded = UdpDataPlaneHandshakeTail.readS2C(buf);
        assertEquals(legacyEndpoints, decoded.udpEndpoints());
        assertEquals(groups, decoded.udpListenerGroups());
    }

    @Test
    @DisplayName("分组字段之前的 S2C payload 解码为空分组")
    void readsPreGroupPayloadAsEmptyGroups() {
        var legacy = List.of(new UdpDataPlaneHandshakeTail.UdpEndpointInfo("edge.example", 41001, 60, 0));
        ByteBuf buf = Unpooled.buffer();
        writePreGroupS2C(buf, legacy);

        var decoded = UdpDataPlaneHandshakeTail.readS2C(buf);

        assertEquals(legacy, decoded.udpEndpoints());
        assertTrue(decoded.udpListenerGroups().isEmpty());
    }

    @Test
    @DisplayName("重复 listener group id 与 wildcard reachable host 会被拒绝")
    void rejectsDuplicateGroupIdsAndWildcardReachableHost() {
        assertThrows(IllegalArgumentException.class, () ->
                new UdpDataPlaneHandshakeTail.UdpListenerGroup(2, 1, List.of(
                        new UdpDataPlaneHandshakeTail.UdpReachableEndpoint("0.0.0.0", 25565, 1))));
        assertThrows(IllegalArgumentException.class, () ->
                new UdpDataPlaneHandshakeTail.S2CTail(true, false, 1L, 3, new byte[16], List.of(),
                        List.of(
                                new UdpDataPlaneHandshakeTail.UdpEndpointInfo("a", 1, 1, 2),
                                new UdpDataPlaneHandshakeTail.UdpEndpointInfo("b", 2, 1, 3)),
                        List.of(
                                new UdpDataPlaneHandshakeTail.UdpListenerGroup(2, 1, List.of(
                                        new UdpDataPlaneHandshakeTail.UdpReachableEndpoint("a", 1, 1))),
                                new UdpDataPlaneHandshakeTail.UdpListenerGroup(2, 1, List.of(
                                        new UdpDataPlaneHandshakeTail.UdpReachableEndpoint("b", 2, 1))))));
    }


    @Test
    @DisplayName("C2S tail 往返：capabilities flags")
    void c2sRoundTripsCapabilityBits() {
        ByteBuf buf = Unpooled.buffer();
        UdpDataPlaneHandshakeTail.writeC2S(buf,
                new UdpDataPlaneHandshakeTail.C2STail(true, true));
        var decoded = UdpDataPlaneHandshakeTail.readC2S(buf);
        assertTrue(decoded.udpDataplaneSupported());
        assertTrue(decoded.controlFailoverSupported());

        ByteBuf buf2 = Unpooled.buffer();
        UdpDataPlaneHandshakeTail.writeC2S(buf2,
                new UdpDataPlaneHandshakeTail.C2STail(false, false));
        var d2 = UdpDataPlaneHandshakeTail.readC2S(buf2);
        assertFalse(d2.udpDataplaneSupported());
        assertFalse(d2.controlFailoverSupported());
    }

    @Test
    @DisplayName("非法 token 长度 → IllegalArgumentException")
    void rejectsBadTokenLength() {
        assertThrows(IllegalArgumentException.class, () ->
            new UdpDataPlaneHandshakeTail.S2CTail(true, false, 1L, 3,
                    new byte[5], List.of(), List.of()));
    }

    @Test
    @DisplayName("重复 endpointId → 拒绝；越界端口 → 拒绝")
    void rejectsDuplicateEndpointIdAndPortOutOfRange() {
        ByteBuf buf = Unpooled.buffer();
        var wrong = new UdpDataPlaneHandshakeTail.S2CTail(true, false, 1L, 3,
                new byte[16],
                List.of(),
                List.of(
                    new UdpDataPlaneHandshakeTail.UdpEndpointInfo("a.example.com", 25565, 100, 3),
                    new UdpDataPlaneHandshakeTail.UdpEndpointInfo("a.example.com", 25566, 100, 3)  // duplicate eid
                ));
        UdpDataPlaneHandshakeTail.writeS2C(buf, wrong);
        assertThrows(IllegalArgumentException.class,
                () -> UdpDataPlaneHandshakeTail.readS2C(buf));

        assertThrows(IllegalArgumentException.class, () ->
            new UdpDataPlaneHandshakeTail.UdpEndpointInfo("a", 70000, 1, 1));
    }
    private static void writePreGroupS2C(ByteBuf out, List<UdpDataPlaneHandshakeTail.UdpEndpointInfo> endpoints) {
        out.writeByte(0x01);
        out.writeLong(7777L);
        writeVarInt(out, UdpDataPlaneHandshakeTail.PROTOCOL_VERSION);
        writeVarInt(out, 16);
        out.writeZero(16);
        writeVarInt(out, 0);
        writeVarInt(out, endpoints.size());
        for (UdpDataPlaneHandshakeTail.UdpEndpointInfo endpoint : endpoints) {
            byte[] host = endpoint.host().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            writeVarInt(out, host.length);
            out.writeBytes(host);
            out.writeShort(endpoint.port());
            writeVarInt(out, endpoint.weight());
            writeVarInt(out, endpoint.endpointId());
        }
    }

    private static void writeVarInt(ByteBuf out, int value) {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }
}
