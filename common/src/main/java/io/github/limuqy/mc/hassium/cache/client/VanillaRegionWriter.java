package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.Constants;
import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.BitSet;

/**
 * 原版 Anvil Region 文件写入器（2-sector header + type 2 zlib）。
 * <p>
 * 用于 {@link CacheWorldExporter} 把 Hassium 缓存（type 126 ZSTD + 3-sector header）
 * 转码为原版可读的 Region 文件。
 * <p>
 * <b>格式</b>：
 * <ul>
 *   <li>Sector 0: offset table（1024 × int32，高 24 位 sector offset，低 8 位 sector count）</li>
 *   <li>Sector 1: timestamp table（1024 × int32，Unix 秒）</li>
 *   <li>Sector 2+: data chunks，每个 {@code [length(4)][type=2][zlib data]}，按 4096 对齐</li>
 * </ul>
 */
public class VanillaRegionWriter implements AutoCloseable {

    private static final int SECTOR_SIZE = 4096;
    private static final int CHUNKS_PER_REGION = 1024;
    private static final int HEADER_SECTORS = 2;

    private final RandomAccessFile file;
    private final int[] offsets = new int[CHUNKS_PER_REGION];
    private final int[] timestamps = new int[CHUNKS_PER_REGION];
    private final BitSet allocatedSectors = new BitSet();
    private boolean headerDirty = true;

    public VanillaRegionWriter(Path path) throws IOException {
        this.file = new RandomAccessFile(path.toFile(), "rw");
        // 预留 header 2 sectors
        allocatedSectors.set(0, HEADER_SECTORS);
        file.setLength(HEADER_SECTORS * SECTOR_SIZE);
    }

    /**
     * 写入一个 chunk 的 zlib 字节。
     *
     * @param pos        区块坐标
     * @param zlibData   zlib 压缩后的 chunk NBT 字节（不含 length/type 前缀）
     * @throws IOException IO 错误
     */
    public synchronized void writeChunk(ChunkPos pos, byte[] zlibData) throws IOException {
        int index = regionIndex(pos);
        // payload: [length(4)][type=1(2)][zlib data]
        int payloadLen = 4 + 1 + zlibData.length;
        int sectorsNeeded = (payloadLen + SECTOR_SIZE - 1) / SECTOR_SIZE;
        if (sectorsNeeded > 255) {
            throw new IOException("Chunk too large for region: " + payloadLen + " bytes");
        }

        // 释放旧 sector（若已有）
        int oldOffset = offsets[index];
        if (oldOffset != 0) {
            int oldSectors = oldOffset & 0xFF;
            int oldStart = oldOffset >>> 8;
            allocatedSectors.clear(oldStart, oldStart + oldSectors);
        }

        // 分配新 sector
        int startSector = allocateSectors(sectorsNeeded);
        offsets[index] = (startSector << 8) | sectorsNeeded;
        timestamps[index] = (int) (System.currentTimeMillis() / 1000L);

        // 写入数据
        file.seek((long) startSector * SECTOR_SIZE);
        byte[] payload = new byte[payloadLen];
        payload[0] = (byte) ((payloadLen - 4) >>> 24);
        payload[1] = (byte) ((payloadLen - 4) >>> 16);
        payload[2] = (byte) ((payloadLen - 4) >>> 8);
        payload[3] = (byte) (payloadLen - 4);
        payload[4] = 2; // type 2 = zlib
        System.arraycopy(zlibData, 0, payload, 5, zlibData.length);
        file.write(payload);
        // 补齐到 sector 边界
        int pad = sectorsNeeded * SECTOR_SIZE - payloadLen;
        if (pad > 0) {
            file.write(new byte[pad]);
        }
        headerDirty = true;
    }

    /** 在文件中分配连续 N 个 sector，返回起始 sector 号。 */
    private int allocateSectors(int count) {
        int start = HEADER_SECTORS;
        while (true) {
            int found = 0;
            while (found < count && !allocatedSectors.get(start + found)) {
                found++;
            }
            if (found == count) {
                allocatedSectors.set(start, start + count);
                return start;
            }
            start += found + 1;
        }
    }

    /** 把 header（offset + timestamp table）刷盘。 */
    public synchronized void flush() throws IOException {
        if (!headerDirty) return;
        byte[] header = new byte[HEADER_SECTORS * SECTOR_SIZE];
        for (int i = 0; i < CHUNKS_PER_REGION; i++) {
            int off = offsets[i];
            header[i * 4] = (byte) (off >>> 24);
            header[i * 4 + 1] = (byte) (off >>> 16);
            header[i * 4 + 2] = (byte) (off >>> 8);
            header[i * 4 + 3] = (byte) off;
            int ts = timestamps[i];
            int tsBase = CHUNKS_PER_REGION * 4 + i * 4;
            header[tsBase] = (byte) (ts >>> 24);
            header[tsBase + 1] = (byte) (ts >>> 16);
            header[tsBase + 2] = (byte) (ts >>> 8);
            header[tsBase + 3] = (byte) ts;
        }
        file.seek(0);
        file.write(header);
        headerDirty = false;
    }

    private static int regionIndex(ChunkPos pos) {
        return (pos.x & 31) + (pos.z & 31) * 32;
    }

    @Override
    public void close() throws IOException {
        try {
            flush();
        } catch (IOException e) {
            Constants.LOG.warn("Hassium: Failed to flush VanillaRegionWriter header", e);
        }
        file.close();
    }
}
