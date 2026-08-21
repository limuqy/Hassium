package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.cache.client.ChunkBloomFilter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bloom 分流：未就绪 / 空层 → 只发 hash；已收到 Bloom 且 miss → 直推；hit → 只发 hash。
 */
class ServerChunkPushManagerBloomSplitTest {

    private static final String DIM = "minecraft:overworld";

    @Test
    void shouldPushFull_unready_isHashOnly() {
        assertFalse(ServerChunkPushManager.shouldPushFull(null, 0, 0, DIM));
        assertFalse(ServerChunkPushManager.isBloomReady(null));
    }

    @Test
    void shouldPushFull_emptyLayers_isHashOnly() {
        ServerChunkPushManager.PlayerBloomLayers layers = new ServerChunkPushManager.PlayerBloomLayers();
        assertFalse(ServerChunkPushManager.isBloomReady(layers));
        assertFalse(ServerChunkPushManager.shouldPushFull(layers, 1, 2, DIM));
    }

    @Test
    void shouldPushFull_emptyBloom_isDirectPush() {
        ChunkBloomFilter empty = ChunkBloomFilter.createDefault();
        ServerChunkPushManager.PlayerBloomLayers layers = new ServerChunkPushManager.PlayerBloomLayers();
        layers.reset(empty);
        assertTrue(ServerChunkPushManager.isBloomReady(layers));
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
    void unreadyWithoutSessionTableStillHashOnly() {
        boolean miss = ServerChunkPushManager.shouldPushFull(null, 0, 0, DIM);
        boolean ready = ServerChunkPushManager.isBloomReady(null);
        assertFalse(ServerChunkPushManager.shouldDirectPushWithoutHash(miss, null, ready));
    }

    @Test
    void bloomMissWithoutSessionTableIsDirectPush() {
        ChunkBloomFilter filter = ChunkBloomFilter.createDefault();
        filter.put(1, 1, DIM);
        ServerChunkPushManager.PlayerBloomLayers layers = new ServerChunkPushManager.PlayerBloomLayers();
        layers.reset(filter);
        boolean miss = ServerChunkPushManager.shouldPushFull(layers, 9, 9, DIM);
        assertTrue(miss);
        assertTrue(ServerChunkPushManager.shouldDirectPushWithoutHash(miss, null,
                ServerChunkPushManager.isBloomReady(layers)));
        assertFalse(ServerChunkPushManager.shouldDirectPushWithoutHash(miss, 42L,
                ServerChunkPushManager.isBloomReady(layers)));
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

    @Test
    void directPushStillPairsHashSoClientCanAccountAndDiskHit() {
        assertTrue(ServerChunkPushManager.shouldPairHashWithDirectPush(),
                "Bloom miss 直推必须附带 hash，否则 ROUND2 缓存命中为 0");
    }
}
