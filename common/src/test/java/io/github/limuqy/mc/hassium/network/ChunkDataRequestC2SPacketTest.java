package io.github.limuqy.mc.hassium.network;

import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChunkDataRequestC2SPacketTest {

    @Test
    void fallbackDeliveryIdRoundTripsAndOrdinaryRequestUsesZero() {
        assertRoundTrip(0L);
        assertRoundTrip(91L);
    }

    private static void assertRoundTrip(long fallbackDeliveryId) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ChunkDataRequestC2SPacket original = new ChunkDataRequestC2SPacket(
                    "minecraft:overworld", List.of(new ChunkPos(3, -4)), fallbackDeliveryId);
            original.encode(buffer);

            ChunkDataRequestC2SPacket decoded = ChunkDataRequestC2SPacket.decode(buffer);
            assertEquals(original.dimension(), decoded.dimension());
            assertEquals(original.chunks(), decoded.chunks());
            assertEquals(fallbackDeliveryId, decoded.fallbackDeliveryId());
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
