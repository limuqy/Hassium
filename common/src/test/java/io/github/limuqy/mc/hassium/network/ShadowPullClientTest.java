package io.github.limuqy.mc.hassium.network;

import java.util.List;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowPullClientTest {
    @Test
    void fullFallbackHasNoClientBaseline() {
        ShadowPullRequestC2SPacket request = ShadowPullClient.fullRequest(
                "minecraft:overworld", 17L, List.of(new ChunkPos(4, -9)));

        assertEquals("minecraft:overworld", request.dimension());
        assertEquals(0L, request.epoch());
        assertEquals(17L, request.requestId());
        assertEquals(1, request.entries().size());
        ShadowPullRequestC2SPacket.Entry entry = request.entries().get(0);
        assertEquals(4, entry.chunkX());
        assertEquals(-9, entry.chunkZ());
        assertEquals(0L, entry.chunkHash());
        assertTrue(entry.sectionHashes().isEmpty());
    }
}
