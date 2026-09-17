package io.github.limuqy.mc.hassium.shadow.storage;

import io.github.limuqy.mc.hassium.shadow.storage.HassiumType126Codec;
import io.github.limuqy.mc.hassium.compression.HassiumCompression;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void decodeVanillaNoneReturnsRawPayload() throws Exception {
        byte[] raw = "uncompressed-anvil-nbt".getBytes();
        assertArrayEquals(raw,
                HassiumType126Codec.decodeVanillaPayload(HassiumType126Codec.TYPE_NONE, raw));
    }

    @Test
    void decodeVanillaZlibAndGzipRoundtrip() throws Exception {
        byte[] nbt = "vanilla-payload-for-reencode".repeat(4).getBytes();
        byte[] zlib;
        try (java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
             java.util.zip.DeflaterOutputStream def =
                     new java.util.zip.DeflaterOutputStream(bos)) {
            def.write(nbt);
            def.finish();
            zlib = bos.toByteArray();
        }
        byte[] gzip;
        try (java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
             java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(bos)) {
            gz.write(nbt);
            gz.finish();
            gzip = bos.toByteArray();
        }
        assertArrayEquals(nbt,
                HassiumType126Codec.decodeVanillaPayload(HassiumType126Codec.TYPE_ZLIB, zlib));
        assertArrayEquals(nbt,
                HassiumType126Codec.decodeVanillaPayload(HassiumType126Codec.TYPE_GZIP, gzip));
    }

    @Test
    void reencodeVanillaToHassiumProducesType126WithMagic() throws Exception {
        byte[] nbt = "c2me-fallback-column".repeat(6).getBytes();
        byte[] zlib;
        try (java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
             java.util.zip.DeflaterOutputStream def =
                     new java.util.zip.DeflaterOutputStream(bos)) {
            def.write(nbt);
            def.finish();
            zlib = bos.toByteArray();
        }
        long hash = 0xA1B2C3D4E5F60718L;
        byte[] sector = HassiumType126Codec.reencodeVanillaToHassium(
                HassiumType126Codec.TYPE_ZLIB, zlib, hash, 1);
        assertEquals(HassiumType126Codec.COMPRESSION_TYPE, sector[4], "重编码必须产出 type 126");
        byte[] payload = HassiumType126Codec.payloadAfterType(sector);
        assertEquals(HassiumType126Codec.HASH_MAGIC, payload[0], "载荷首字节须为 0x48");
        assertEquals(hash, HassiumType126Codec.probeHash(payload));
        HassiumType126Codec.Decoded decoded = HassiumType126Codec.decode(payload);
        assertArrayEquals(nbt, decoded.nbt(), "重编码后解压须还原原始 NBT");
        assertTrue(decoded.contentHash() != null && decoded.contentHash() == hash);
    }

    @Test
    void decodeVanillaRejectsUnknownType() {
        assertThrows(java.io.IOException.class,
                () -> HassiumType126Codec.decodeVanillaPayload((byte) 99, new byte[]{1, 2, 3}));
        assertThrows(java.io.IOException.class,
                () -> HassiumType126Codec.decodeVanillaPayload(HassiumType126Codec.TYPE_ZLIB, null));
    }
}
