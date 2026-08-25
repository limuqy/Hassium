package io.github.limuqy.mc.hassium.network;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.world.level.ChunkPos;

/**
 * Exact server-owned R+1 lighting boundary.  The shape intentionally delegates
 * to the same range predicate used by server tracking rather than assuming a
 * square or a Chebyshev disk.
 */
public final class ShadowHaloWindow {
    private ShadowHaloWindow() {}

    public static Set<Long> positions(ChunkPos center, int viewDistance) {
        if (center == null || viewDistance < 0) {
            return Set.of();
        }
        int outerDistance = viewDistance + 1;
        Set<Long> positions = new HashSet<>();
        for (int z = center.z - outerDistance - 1; z <= center.z + outerDistance + 1; z++) {
            for (int x = center.x - outerDistance - 1; x <= center.x + outerDistance + 1; x++) {
                if (ServerChunkPushManager.isServerChunkInRange(x, z, center.x, center.z, outerDistance)
                        && !ServerChunkPushManager.isServerChunkInRange(x, z, center.x, center.z, viewDistance)) {
                    positions.add(ChunkPos.asLong(x, z));
                }
            }
        }
        return positions;
    }
}
