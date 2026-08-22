package io.github.limuqy.mc.hassium.storage;

import io.github.limuqy.mc.hassium.utils.DimensionKey;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进程内 hash 表比对：命中相等 / 命中不等 / 缺失（缺失才允许调用方现算）。
 */
class ShadowStorageHashesTest {

    @AfterEach
    void tearDown() {
        ShadowStorageHashes.clear();
    }

    @Test
    @DisplayName("表命中且相等 → true，不必现算")
    void matchesRemote_tableHitEqual() {
        ChunkPos pos = new ChunkPos(3, 7);
        ShadowStorageHashes.put(pos, 0xABCDEFL);
        assertEquals(Boolean.TRUE, ShadowStorageHashes.matchesRemote(pos, 0xABCDEFL));
    }

    @Test
    @DisplayName("表有值但不等 → false，不必现算")
    void matchesRemote_tableHitMismatch() {
        ChunkPos pos = new ChunkPos(1, 2);
        ShadowStorageHashes.put(pos, 11L);
        assertEquals(Boolean.FALSE, ShadowStorageHashes.matchesRemote(pos, 22L));
        assertEquals(11L, ShadowStorageHashes.get(pos));
    }

    @Test
    @DisplayName("表缺失 → null，调用方再现算")
    void matchesRemote_tableMissing() {
        ChunkPos pos = new ChunkPos(8, 9);
        assertNull(ShadowStorageHashes.get(pos));
        assertNull(ShadowStorageHashes.matchesRemote(pos, 99L));
    }

    @Test
    @DisplayName("读盘回填 put(x,z) 与 ChunkPos 查询同一槽")
    void matchesRemote_regionFilePutOverload() {
        ShadowStorageHashes.put(4, 5, 42L);
        assertTrue(ShadowStorageHashes.matchesRemote(new ChunkPos(4, 5), 42L));
        assertFalse(ShadowStorageHashes.matchesRemote(new ChunkPos(4, 5), 41L));
    }

    @Test
    @DisplayName("lightDirty 不改变 content hash 命中")
    void lightDirtyStillMatchesRemote() {
        ChunkPos pos = new ChunkPos(9, 9);
        ShadowStorageHashes.put(pos, 77L);
        ShadowStorageHashes.markLightReady(pos);
        assertTrue(ShadowStorageHashes.isLightDirty(pos));
        assertEquals(Boolean.TRUE, ShadowStorageHashes.matchesRemote(pos, 77L));
    }

    @Test
    @DisplayName("markContentDirty 只改脏位")
    void markContentDirtyIsFlagOnly() {
        ChunkPos pos = new ChunkPos(0, 1);
        ShadowStorageHashes.markContentDirty(pos);
        assertTrue(ShadowStorageHashes.isContentDirty(pos));
        assertNull(ShadowStorageHashes.get(pos));
        assertTrue(ShadowStorageHashes.claimDirty(
                DimensionKey.key(DimensionKey.OVERWORLD, pos.x, pos.z)), "旧裸键布局已废弃：脏键为复合键");
        assertFalse(ShadowStorageHashes.isDirty(pos));
    }

    @Test
    @DisplayName("跨维同坐标：overworld/nether 各自 put 不同 hash 互不干扰")
    void crossDimensionSameCoordsDoNotCollide() {
        ChunkPos pos = new ChunkPos(-123, 456);
        ShadowStorageHashes.put(DimensionKey.OVERWORLD, pos, 0xAAL);
        ShadowStorageHashes.put(DimensionKey.NETHER, pos, 0xBB1L);
        assertEquals(0xAAL, ShadowStorageHashes.get(DimensionKey.OVERWORLD, pos));
        assertEquals(0xBB1L, ShadowStorageHashes.get(DimensionKey.NETHER, pos));
        assertNull(ShadowStorageHashes.get(DimensionKey.END, pos));
        assertEquals(Boolean.TRUE, ShadowStorageHashes.matchesRemote(DimensionKey.OVERWORLD, pos, 0xAAL));
        assertEquals(Boolean.FALSE, ShadowStorageHashes.matchesRemote(DimensionKey.NETHER, pos, 0xAAL));

        long owKey = DimensionKey.key(DimensionKey.OVERWORLD, pos.x, pos.z);
        long neKey = DimensionKey.key(DimensionKey.NETHER, pos.x, pos.z);
        assertTrue(owKey != neKey);
        assertEquals(pos.x, DimensionKey.chunkXOf(owKey));
        assertEquals(pos.z, DimensionKey.chunkZOf(neKey));
    }

    @Test
    @DisplayName("脏位跨维隔离：同坐标 overworld 脏不影响 nether")
    void crossDimensionDirtyFlagsIsolated() {
        ChunkPos pos = new ChunkPos(7, -8);
        ShadowStorageHashes.put(DimensionKey.OVERWORLD, pos, 1L);
        ShadowStorageHashes.markContentDirty(DimensionKey.OVERWORLD, pos);
        assertFalse(ShadowStorageHashes.isDirty(DimensionKey.NETHER, pos));
        assertTrue(ShadowStorageHashes.isContentDirty(DimensionKey.OVERWORLD, pos));

        long owKey = DimensionKey.key(DimensionKey.OVERWORLD, pos.x, pos.z);
        assertTrue(ShadowStorageHashes.claimDirty(owKey));
        long neKey = DimensionKey.key(DimensionKey.NETHER, pos.x, pos.z);
        assertFalse(ShadowStorageHashes.claimDirty(neKey));

        // remove 只删本维度，另一维度数据保留
        ChunkPos shared = new ChunkPos(3, 4);
        ShadowStorageHashes.put(DimensionKey.OVERWORLD, shared, 10L);
        ShadowStorageHashes.put(DimensionKey.NETHER, shared, 20L);
        ShadowStorageHashes.remove(DimensionKey.NETHER, shared);
        assertEquals(10L, ShadowStorageHashes.get(DimensionKey.OVERWORLD, shared));
        assertNull(ShadowStorageHashes.get(DimensionKey.NETHER, shared));
    }
}
