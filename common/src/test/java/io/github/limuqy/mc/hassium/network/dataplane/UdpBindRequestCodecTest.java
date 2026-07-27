package io.github.limuqy.mc.hassium.network.dataplane;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UdpBindRequestCodecTest {

    private static final UUID PLAYER = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @Test
    void requestRoundTripsTokenUuidEpochAndEndpoint() {
        byte[] token = new byte[16];
        token[0] = 9;
        token[15] = (byte) 0xFF;

        byte[] encoded = UdpBindRequestCodec.encodeRequest(token, PLAYER, 42L, 3, 7);
        UdpBindRequestCodec.Request decoded = UdpBindRequestCodec.decodeRequest(encoded);

        assertArrayEquals(token, decoded.token());
        assertEquals(PLAYER, decoded.playerId());
        assertEquals(42L, decoded.connectionEpoch());
        assertEquals(3, decoded.endpointId());
        assertEquals(7, decoded.channelId());
    }

    @Test
    void authenticatedBindAckRoundTripsEpochAndEndpoint() {
        UdpBindRequestCodec.Ack decoded = UdpBindRequestCodec.decodeAck(
                UdpBindRequestCodec.encodeAck(42L, 3));

        assertEquals(42L, decoded.connectionEpoch());
        assertEquals(3, decoded.endpointId());
    }

    @Test
    void bindAckRejectsMalformedPayloadAndNegativeEndpoint() {
        assertThrows(IllegalArgumentException.class, () -> UdpBindRequestCodec.decodeAck(new byte[11]));
        assertThrows(IllegalArgumentException.class, () -> UdpBindRequestCodec.encodeAck(42L, -1));
    }

    @Test
    void requestRejectsTruncatedPayload() {
        assertThrows(IllegalArgumentException.class,
                () -> UdpBindRequestCodec.decodeRequest(new byte[33]));
    }

    @Test
    void requestRejectsZeroLengthPayload() {
        assertThrows(IllegalArgumentException.class,
                () -> UdpBindRequestCodec.decodeRequest(new byte[0]));
    }

    @Test
    void requestRejectsWrongProtocolVersion() {
        // 写一个长度合法但 protocol != 3 的 payload
        byte[] token = new byte[16];
        byte[] buf = new java.io.ByteArrayOutputStream(48) {{
            writeBytes(token);
            // msb/lsb 全 0
            for (int i = 0; i < 16; i++) write(0);
            for (int i = 0; i < 8; i++) write(0); // epoch
            // protocol=1 (旧)，endpointId=1，channelId=1
            write(1);
            write(1);
            write(1);
        }}.toByteArray();
        assertThrows(IllegalArgumentException.class,
                () -> UdpBindRequestCodec.decodeRequest(buf));
    }

    @Test
    void encodeRejectsInvalidTokenAndNegativeIds() {
        byte[] token = new byte[15];
        assertThrows(IllegalArgumentException.class,
                () -> UdpBindRequestCodec.encodeRequest(token, PLAYER, 1L, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> UdpBindRequestCodec.encodeRequest(new byte[16], null, 1L, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> UdpBindRequestCodec.encodeRequest(new byte[16], PLAYER, 1L, -1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> UdpBindRequestCodec.encodeRequest(new byte[16], PLAYER, 1L, 1, -1));
    }
}
