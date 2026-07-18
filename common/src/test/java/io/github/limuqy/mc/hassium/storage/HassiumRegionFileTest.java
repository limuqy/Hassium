package io.github.limuqy.mc.hassium.storage;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link HassiumRegionFile} 读写往返：锁定 offset / MetadataTable / sector 数据一致性。
 */
class HassiumRegionFileTest {

    @TempDir
    Path tempDir;

    @Test
    void writeThenReadShouldReturnSamePayloadAndHash() throws Exception {
        Path regionPath = tempDir.resolve("r.0.0.mca");
        ChunkPos pos = new ChunkPos(6, -6);
        // 模拟 [type=126][payload]；无需真实 ZSTD
        byte[] chunkData = new byte[64];
        chunkData[0] = (byte) 126;
        for (int i = 1; i < chunkData.length; i++) {
            chunkData[i] = (byte) (i * 7);
        }
        long contentHash = 0xEB26A03008E5726DL;

        try (HassiumRegionFile region = new HassiumRegionFile(regionPath)) {
            region.writeChunk(pos, chunkData, contentHash);
            assertTrue(region.hasChunk(pos));
            assertEquals(contentHash, region.readContentHash(pos));
            byte[] read = region.readChunk(pos);
            assertNotNull(read, "readChunk 在 hash HIT 后不应为 null");
            assertArrayEquals(chunkData, read);
        }

        // 重新打开：内存表与磁盘一致
        try (HassiumRegionFile region = new HassiumRegionFile(regionPath)) {
            assertTrue(region.hasChunk(pos));
            assertEquals(contentHash, region.readContentHash(pos));
            assertArrayEquals(chunkData, region.readChunk(pos));
        }
    }

    @Test
    void missingChunkShouldReturnNullAndZeroHash() throws Exception {
        Path regionPath = tempDir.resolve("r.1.1.mca");
        ChunkPos pos = new ChunkPos(32, 32);
        try (HassiumRegionFile region = new HassiumRegionFile(regionPath)) {
            assertFalse(region.hasChunk(pos));
            assertEquals(0L, region.readContentHash(pos));
            assertNull(region.readChunk(pos));
        }
    }
}
