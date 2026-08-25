package io.github.limuqy.mc.hassium.network;

import io.netty.buffer.Unpooled;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ChunkPayloadWireFormatTest {

    @Test
    void compressedChunkDataCarriesItsRoleInTheWireFormat() throws Exception {
        byte[] compressed = {7, 8, 9};
        ChunkCompressionHandler.CompressedChunkData payload =
                new ChunkCompressionHandler.CompressedChunkData(-5, 12, compressed, 4096, "zstd", ShadowChunkRole.HALO);
        byte[] encoded = payload.encode();

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            assertEquals(-5, input.readInt());
            assertEquals(12, input.readInt());
            assertEquals(4096, input.readInt());
            assertEquals(ShadowChunkRole.HALO.wireValue(), input.readByte());
            assertEquals("zstd", input.readUTF());
            assertEquals(3, input.readInt());
            assertArrayEquals(compressed, input.readNBytes(3));
            assertEquals(-1, input.read());
        }

        ChunkCompressionHandler.CompressedChunkData decoded =
                ChunkCompressionHandler.CompressedChunkData.decode(encoded);
        assertNotNull(decoded);
        assertEquals(-5, decoded.chunkX);
        assertEquals(12, decoded.chunkZ);
        assertEquals(4096, decoded.originalSize);
        assertEquals("zstd", decoded.algorithm);
        assertEquals(ShadowChunkRole.HALO, decoded.role);
        assertArrayEquals(compressed, decoded.compressedData);
    }

    @Test
    void seedReferenceCarriesOnlyCoordinatesAndHashes() {
        SeedRefS2CPacket original = new SeedRefS2CPacket(-5, 12, 0x0102030405060708L,
                new long[]{11L, -12L});
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            original.encode(buffer);

            assertEquals(-5, buffer.readVarInt());
            assertEquals(12, buffer.readVarInt());
            assertEquals(0x0102030405060708L, buffer.readLong());
            assertEquals(2, buffer.readVarInt());
            assertEquals(11L, buffer.readLong());
            assertEquals(-12L, buffer.readLong());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }

        FriendlyByteBuf encoded = new FriendlyByteBuf(Unpooled.buffer());
        try {
            original.encode(encoded);
            SeedRefS2CPacket decoded = SeedRefS2CPacket.decode(encoded);
            assertEquals(original.chunkX(), decoded.chunkX());
            assertEquals(original.chunkZ(), decoded.chunkZ());
            assertEquals(original.contentHash(), decoded.contentHash());
            assertArrayEquals(original.sectionHashes(), decoded.sectionHashes());
        } finally {
            encoded.release();
        }
    }
}
