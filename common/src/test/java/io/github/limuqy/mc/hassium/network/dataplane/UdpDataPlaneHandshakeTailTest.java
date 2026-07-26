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
}
