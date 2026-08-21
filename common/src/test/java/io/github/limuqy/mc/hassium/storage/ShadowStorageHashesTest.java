package io.github.limuqy.mc.hassium.storage;

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
        assertTrue(ShadowStorageHashes.claimDirty(ChunkPos.asLong(pos.x, pos.z)));
        assertFalse(ShadowStorageHashes.isDirty(pos));
    }
}
