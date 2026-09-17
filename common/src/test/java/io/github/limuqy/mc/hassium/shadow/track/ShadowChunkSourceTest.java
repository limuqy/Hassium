package io.github.limuqy.mc.hassium.shadow.track;

import io.github.limuqy.mc.hassium.shadow.track.ShadowChunkSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowChunkSourceTest {

    @Test
    @DisplayName("full、缓存和 SeedGen 都声明为 pre-LIGHT 来源")
    void declaresAllPreLightSources() {
        assertEquals(3, ShadowChunkSource.values().length);
        assertTrue(ShadowChunkSource.REMOTE_FULL.isPacketSnapshot());
        assertFalse(ShadowChunkSource.REMOTE_FULL.isLocalChunk());
        assertTrue(ShadowChunkSource.CACHE_SNAPSHOT.isPacketSnapshot());
        assertTrue(ShadowChunkSource.CACHE_SNAPSHOT.isLocalChunk());
        assertFalse(ShadowChunkSource.SEEDGEN.isPacketSnapshot());
        assertTrue(ShadowChunkSource.SEEDGEN.isLocalChunk());
    }

    @Test
    @DisplayName("packet 与 materialized chunk 入口边界完整且无未知来源")
    void separatesPacketAndMaterializedIngress() {
        for (ShadowChunkSource source : ShadowChunkSource.values()) {
            assertTrue(source.isPacketSnapshot() || source.isLocalChunk(),
                    "source must have a pre-LIGHT ingress");
        }
        assertFalse(ShadowChunkSource.REMOTE_FULL.isLocalChunk());
        assertFalse(ShadowChunkSource.SEEDGEN.isPacketSnapshot());
    }
}
