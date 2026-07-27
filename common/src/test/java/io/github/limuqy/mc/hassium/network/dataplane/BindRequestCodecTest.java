package io.github.limuqy.mc.hassium.network.dataplane;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BindRequestCodecTest {

    @Test
    @DisplayName("v2 encode/decode 往返保留 token/uuid/protocol/channelId")
    void roundTrip() {
        byte[] token = new byte[16];
        for (int i = 0; i < 16; i++) token[i] = (byte) (i + 1);
        UUID id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        byte[] payload = BindRequestCodec.encode(token, id, BindRequestCodec.PROTOCOL_VERSION, 1);
        assertTrue(payload.length >= BindRequestCodec.MIN_PAYLOAD_LEN);

        BindRequestCodec.Parsed p = BindRequestCodec.decode(payload);
        assertArrayEquals(token, p.token());
        assertEquals(id, p.playerId());
        assertEquals(2, p.protocol());
        assertEquals(1, p.channelId());
    }

    @Test
    @DisplayName("短 payload 抛 Bad request length")
    void tooShort() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> BindRequestCodec.decode(new byte[20]));
        assertTrue(e.getMessage().contains("Bad request length"));
    }

    @Test
    @DisplayName("encode 拒绝非 16B token / null uuid")
    void encodeGuards() {
        UUID id = new UUID(1L, 2L);
        assertThrows(IllegalArgumentException.class,
                () -> BindRequestCodec.encode(new byte[8], id, 2, 1));
        assertThrows(IllegalArgumentException.class,
                () -> BindRequestCodec.encode(new byte[16], null, 2, 1));
    }
}
