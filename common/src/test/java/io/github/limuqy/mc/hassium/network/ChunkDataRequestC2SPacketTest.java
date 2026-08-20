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
}
