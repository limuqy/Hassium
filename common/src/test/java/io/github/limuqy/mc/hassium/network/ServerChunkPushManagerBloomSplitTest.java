package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.cache.client.ChunkBloomFilter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bloom 分流：miss / 空 / 未就绪 → {@code shouldPushFull=true}（只直推）；
 * hit → {@code false}（只发 hash）。
 */
class ServerChunkPushManagerBloomSplitTest {

    private static final String DIM = "minecraft:overworld";

    @Test
    void shouldPushFull_unready_isDirectPush() {
        assertTrue(ServerChunkPushManager.shouldPushFull(null, 0, 0, DIM));
    }

    @Test
    void shouldPushFull_emptyLayers_isDirectPush() {
        ServerChunkPushManager.PlayerBloomLayers layers = new ServerChunkPushManager.PlayerBloomLayers();
        assertTrue(ServerChunkPushManager.shouldPushFull(layers, 1, 2, DIM));
    }

    @Test
    void shouldPushFull_emptyBloom_isDirectPush() {
        ChunkBloomFilter empty = ChunkBloomFilter.createDefault();
        ServerChunkPushManager.PlayerBloomLayers layers = new ServerChunkPushManager.PlayerBloomLayers();
        layers.reset(empty);
        assertTrue(ServerChunkPushManager.shouldPushFull(layers, 3, 4, DIM));
    }

    @Test
    void shouldPushFull_miss_isDirectPush() {
        ChunkBloomFilter filter = ChunkBloomFilter.createDefault();
        filter.put(10, 20, DIM);
        ServerChunkPushManager.PlayerBloomLayers layers = new ServerChunkPushManager.PlayerBloomLayers();
        layers.reset(filter);
        assertTrue(ServerChunkPushManager.shouldPushFull(layers, 0, 0, DIM));
    }

    @Test
    void shouldPushFull_hit_isHashOnly() {
        ChunkBloomFilter filter = ChunkBloomFilter.createDefault();
        filter.put(7, 8, DIM);
        ServerChunkPushManager.PlayerBloomLayers layers = new ServerChunkPushManager.PlayerBloomLayers();
        layers.reset(filter);
        assertFalse(ServerChunkPushManager.shouldPushFull(layers, 7, 8, DIM));
    }

    @Test
    void sessionTable_ignoredWhenInBloom() {
        assertFalse(ServerChunkPushManager.shouldReuseSessionPush(true, 42L, 42L));
        assertFalse(ServerChunkPushManager.shouldRecordSessionPush(true, 42L));
    }

    @Test
    void sessionTable_reusesSameHashWhenNotInBloom() {
        assertTrue(ServerChunkPushManager.shouldReuseSessionPush(false, 42L, 42L));
        assertFalse(ServerChunkPushManager.shouldReuseSessionPush(false, 42L, 99L));
        assertFalse(ServerChunkPushManager.shouldReuseSessionPush(false, null, 42L));
        assertTrue(ServerChunkPushManager.shouldRecordSessionPush(false, 42L));
        assertFalse(ServerChunkPushManager.shouldRecordSessionPush(false, 0L));
    }
}
