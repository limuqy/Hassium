package io.github.limuqy.mc.hassium.network;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientChunkHandlerLoadingScreenTest {

    @Test
    void chebyshevIncludesPlayerColumnAndNeighbors() {
        ChunkPos center = new ChunkPos(-3, -1);
        assertTrue(ClientChunkHandler.isWithinChebyshev(center, center, 1));
        assertTrue(ClientChunkHandler.isWithinChebyshev(new ChunkPos(-2, -1), center, 1));
        assertTrue(ClientChunkHandler.isWithinChebyshev(new ChunkPos(-3, -2), center, 1));
        assertFalse(ClientChunkHandler.isWithinChebyshev(new ChunkPos(-1, -1), center, 1));
        assertFalse(ClientChunkHandler.isWithinChebyshev(null, center, 1));
    }
}
