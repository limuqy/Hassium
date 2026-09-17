package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageHashes;
import java.util.List;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowPullClientTest {
    @AfterEach
    void clearHashes() {
        ShadowStorageHashes.clear();
    }

    @Test
    void compareRequestCarriesKnownShadowBaseline() {
        ChunkPos pos = new ChunkPos(4, -9);
        ShadowStorageHashes.put("minecraft:overworld", pos, 0xCAFEBABEL);

        ShadowPullRequestC2SPacket request = ShadowPullClient.fullRequest(
                "minecraft:overworld", 17L, List.of(pos));

        assertEquals("minecraft:overworld", request.dimension());
        assertEquals(0L, request.epoch());
        assertEquals(17L, request.requestId());
        assertEquals(1, request.entries().size());
        ShadowPullRequestC2SPacket.Entry entry = request.entries().get(0);
        assertEquals(4, entry.chunkX());
        assertEquals(-9, entry.chunkZ());
        assertEquals(0xCAFEBABEL, entry.chunkHash());
        assertTrue(entry.sectionHashes().isEmpty());
    }

    @Test
    void authoritativeRetryOmitsShadowBaseline() {
        ChunkPos pos = new ChunkPos(4, -9);
        ShadowStorageHashes.put("minecraft:overworld", pos, 0xCAFEBABEL);

        ShadowPullRequestC2SPacket request = ShadowPullClient.fullRequest(
                "minecraft:overworld", 18L, List.of(pos), false);

        assertEquals(0L, request.entries().get(0).chunkHash());
        assertTrue(request.entries().get(0).sectionHashes().isEmpty());
    }
}
