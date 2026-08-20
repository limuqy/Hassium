package io.github.limuqy.mc.hassium.network.core.outbound;

import io.github.limuqy.mc.hassium.network.ChunkCompressionHandler;
import io.github.limuqy.mc.hassium.network.SeedRefS2CPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChunkApplyAckCodecTest {

    @Test
    void controlFrameIdsRemainAppendOnly() {
        for (int id = 1; id <= 12; id++) {
            assertEquals(id, ControlFrameType.fromId(id).id());
        }
        assertEquals(13, ControlFrameType.CHUNK_APPLY_ACK.id());
        assertEquals(ControlFrameType.CHUNK_APPLY_ACK, ControlFrameType.fromId(13));
    }

    @Test
    void chunkApplyAckRoundTrip() {
        ChunkApplyAck original = new ChunkApplyAck(new long[]{1L, 42L, Long.MAX_VALUE});
        ByteBuf payload = Unpooled.buffer();
        try {
            original.encode(payload);
            ChunkApplyAck decoded = ChunkApplyAck.decode(payload);
            assertArrayEquals(new long[]{1L, 42L, Long.MAX_VALUE}, decoded.deliveryIds());
        } finally {
            payload.release();
        }
    }

    @Test
    void malformedChunkApplyAcksAreRejectedAsWholeFrames() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkApplyAck(new long[0]));
        assertThrows(IllegalArgumentException.class, () -> new ChunkApplyAck(new long[]{0L}));
        assertThrows(IllegalArgumentException.class, () -> new ChunkApplyAck(new long[65]));

        assertMalformedAck(payload -> { });
        assertMalformedAck(payload -> ControlFrameCodec.writeVarInt(payload, 0));
        assertMalformedAck(payload -> ControlFrameCodec.writeVarInt(payload, 65));
        assertMalformedAck(payload -> {
            ControlFrameCodec.writeVarInt(payload, 1);
            payload.writeLong(0L);
        });
        assertMalformedAck(payload -> {
            ControlFrameCodec.writeVarInt(payload, 1);
            payload.writeInt(7);
        });
        assertMalformedAck(payload -> {
            ControlFrameCodec.writeVarInt(payload, 1);
            payload.writeLong(7L);
            payload.writeByte(1);
        });
    }

    @Test
    void fullAndSeedGenPayloadsRoundTripDeliveryId() {
        ChunkCompressionHandler.CompressedChunkData full =
                new ChunkCompressionHandler.CompressedChunkData(3, -4, new byte[]{9, 8, 7}, 11, "zstd", 77L);
        ChunkCompressionHandler.CompressedChunkData decodedFull =
                ChunkCompressionHandler.CompressedChunkData.decode(full.encode());
        assertNotNull(decodedFull);
        assertEquals(77L, decodedFull.deliveryId);
        assertArrayEquals(new byte[]{9, 8, 7}, decodedFull.compressedData);

        SeedRefS2CPacket seedRef = new SeedRefS2CPacket(3, -4, 9L, new long[]{10L, 11L}, 78L);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            seedRef.encode(buffer);
            SeedRefS2CPacket decodedSeedRef = SeedRefS2CPacket.decode(buffer);
            assertEquals(78L, decodedSeedRef.deliveryId());
            assertArrayEquals(new long[]{10L, 11L}, decodedSeedRef.sectionHashes());
        } finally {
            buffer.release();
        }
    }

    private static void assertMalformedAck(java.util.function.Consumer<ByteBuf> encoder) {
        ByteBuf payload = Unpooled.buffer();
        try {
            encoder.accept(payload);
            assertThrows(IllegalArgumentException.class, () -> ChunkApplyAck.decode(payload));
        } finally {
            payload.release();
        }
    }
}
