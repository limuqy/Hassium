package io.github.limuqy.mc.hassium.network;

import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkDataRequestC2SPacketTest {

    @Test
    void resultConstantsAndWireOrderAreStable() {
        assertEquals(0, ChunkDataRequestC2SPacket.RESULT_MISS);
        assertEquals(1, ChunkDataRequestC2SPacket.RESULT_HIT);

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            new ChunkDataRequestC2SPacket("minecraft:overworld", List.of(new ChunkPos(3, -4)),
                    ChunkDataRequestC2SPacket.RESULT_MISS).encode(buffer);

            assertEquals("minecraft:overworld", buffer.readUtf());
            assertEquals(1, buffer.readVarInt());
            assertEquals(3, buffer.readVarInt());
            assertEquals(-4, buffer.readVarInt());
            assertEquals(ChunkDataRequestC2SPacket.RESULT_MISS, buffer.readByte());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void missAndHitFramesRoundTripWithTheirRequiredChunkShapes() {
        assertRoundTrip(ChunkDataRequestC2SPacket.RESULT_MISS, List.of(new ChunkPos(3, -4)));
        assertRoundTrip(ChunkDataRequestC2SPacket.RESULT_HIT, List.of());
    }

    @Test
    void resultShapeSelectsLoaderBoundaryAction() {
        assertTrue(new ChunkDataRequestC2SPacket("minecraft:overworld", List.of(new ChunkPos(0, 0)),
                ChunkDataRequestC2SPacket.RESULT_MISS).requestsFullChunks());
        assertFalse(new ChunkDataRequestC2SPacket("minecraft:overworld", List.of(),
                ChunkDataRequestC2SPacket.RESULT_HIT).requestsFullChunks());
    }

    @Test
    void rejectsInvalidResultShapesAndTrailingPayload() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkDataRequestC2SPacket(
                "minecraft:overworld", List.of(), ChunkDataRequestC2SPacket.RESULT_MISS));
        assertThrows(IllegalArgumentException.class, () -> new ChunkDataRequestC2SPacket(
                "minecraft:overworld", List.of(new ChunkPos(0, 0)), ChunkDataRequestC2SPacket.RESULT_HIT));
        assertThrows(IllegalArgumentException.class, () -> new ChunkDataRequestC2SPacket(
                "minecraft:overworld", List.of(), 2));

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeUtf("minecraft:overworld");
            buffer.writeVarInt(0);
            buffer.writeByte(ChunkDataRequestC2SPacket.RESULT_HIT);
            buffer.writeByte(0);
            assertThrows(IllegalArgumentException.class, () -> ChunkDataRequestC2SPacket.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    private static void assertRoundTrip(int result, List<ChunkPos> chunks) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ChunkDataRequestC2SPacket original = new ChunkDataRequestC2SPacket(
                    "minecraft:overworld", chunks, result);
            original.encode(buffer);

            ChunkDataRequestC2SPacket decoded = ChunkDataRequestC2SPacket.decode(buffer);
            assertEquals(original.dimension(), decoded.dimension());
            assertEquals(original.chunks(), decoded.chunks());
            assertEquals(result, decoded.result());
        } finally {
            buffer.release();
        }
    }

    @Test
    void partition_splitsOverMaxAndPreservesOrder() {
        List<ChunkPos> chunks = new java.util.ArrayList<>();
        for (int i = 0; i < ChunkDataRequestC2SPacket.MAX_CHUNKS_PER_REQUEST + 3; i++) {
            chunks.add(new ChunkPos(i, 0));
        }
        List<List<ChunkPos>> batches = ChunkDataRequestC2SPacket.partition(chunks);
        assertEquals(2, batches.size());
        assertEquals(ChunkDataRequestC2SPacket.MAX_CHUNKS_PER_REQUEST, batches.get(0).size());
        assertEquals(3, batches.get(1).size());
        assertEquals(new ChunkPos(0, 0), batches.get(0).get(0));
        assertEquals(new ChunkPos(ChunkDataRequestC2SPacket.MAX_CHUNKS_PER_REQUEST, 0), batches.get(1).get(0));
    }

    @Test
    void partition_emptyIsEmpty() {
        assertEquals(List.of(), ChunkDataRequestC2SPacket.partition(List.of()));
    }
}
