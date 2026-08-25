package io.github.limuqy.mc.hassium.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

class ShadowHaloWindowTest {
    @Test
    void containsOnlyTheServerTrackingRing() {
        ChunkPos center = new ChunkPos(0, 0);
        int viewDistance = 4;
        Set<Long> halo = ShadowHaloWindow.positions(center, viewDistance);

        assertFalse(halo.isEmpty());
        for (long packed : halo) {
            int x = ChunkPos.getX(packed);
            int z = ChunkPos.getZ(packed);
            assertTrue(ServerChunkPushManager.isServerChunkInRange(x, z, 0, 0, viewDistance + 1));
            assertFalse(ServerChunkPushManager.isServerChunkInRange(x, z, 0, 0, viewDistance));
        }
    }

    @Test
    void movesTheWholeRingWithTheCenter() {
        Set<Long> initial = ShadowHaloWindow.positions(new ChunkPos(0, 0), 4);
        Set<Long> moved = ShadowHaloWindow.positions(new ChunkPos(1, 0), 4);

        assertFalse(initial.equals(moved));
        assertTrue(initial.stream().allMatch(packed -> moved.contains(
                ChunkPos.asLong(ChunkPos.getX(packed) + 1, ChunkPos.getZ(packed)))));
        assertTrue(moved.stream().allMatch(packed -> initial.contains(
                ChunkPos.asLong(ChunkPos.getX(packed) - 1, ChunkPos.getZ(packed)))));
    }
}
