package io.github.limuqy.mc.hassium.storage;

import io.github.limuqy.mc.hassium.compression.HassiumCompression;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class HassiumType126CodecTest {

    @BeforeAll
    static void initCompression() {
        HassiumCompression.reset();
        HassiumCompression.initialize();
    }

    @Test
    void encodeDecodeRoundtripWithHash() throws Exception {
        byte[] nbt = "shadow-column-nbt-payload".repeat(8).getBytes();
        long hash = 0x0123456789ABCDEFL;
        byte[] sector = HassiumType126Codec.encodeSector(nbt, hash, 1);
        assertEquals(HassiumType126Codec.COMPRESSION_TYPE, sector[4]);
        byte[] payload = HassiumType126Codec.payloadAfterType(sector);
        assertEquals(HassiumType126Codec.HASH_MAGIC, payload[0]);
        assertEquals(hash, HassiumType126Codec.probeHash(payload));
        HassiumType126Codec.Decoded decoded = HassiumType126Codec.decode(payload);
        assertArrayEquals(nbt, decoded.nbt());
        assertEquals(hash, decoded.contentHash());
    }

    @Test
    void probeHashDoesNotNeedFullDecode() throws Exception {
        byte[] nbt = new byte[]{9, 8, 7, 6, 5, 4, 3, 2, 1};
        byte[] sector = HassiumType126Codec.encodeSector(nbt, 42L, 1);
        byte[] payload = HassiumType126Codec.payloadAfterType(sector);
        assertEquals(42L, HassiumType126Codec.probeHash(payload));
        assertNull(HassiumType126Codec.probeHash(new byte[]{0x28, 1, 2, 3}));
        assertNotNull(payload);
    }
}
