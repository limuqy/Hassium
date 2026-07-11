package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.Constants;
import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 客户端缓存的轻量 region 文件实现
 * <p>
 * 格式与原版 Minecraft .mca 一致：
 * <ul>
 *   <li>Sector 0: offset table (1024 ints, 4096 bytes)</li>
 *   <li>Sector 1+: chunk data (compression type 126 + ZSTD compressed, 4096-byte aligned)</li>
 * </ul>
 * <p>
 * Offset int 格式: {@code sectorOffset << 8 | sectorCount}
 */
public class ClientRegionFile implements AutoCloseable {

    private static final int SECTOR_SIZE = 4096;
    private static final int HEADER_SECTORS = 1; // 只有 offset table，无 timestamp table
    private static final int CHUNKS_PER_REGION = 1024; // 32x32

    private final FileChannel file;
    private final ByteBuffer header;
    private final IntBuffer offsets;
    private final RegionBitmap usedSectors;
    private final Path filePath;
    private long fileSize;

    public ClientRegionFile(Path path) throws IOException {
        this.filePath = path;
        boolean isNew = !Files.exists(path);

        this.file = FileChannel.open(path,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);

        this.header = ByteBuffer.allocate(SECTOR_SIZE);
        this.offsets = header.asIntBuffer();
        this.usedSectors = new RegionBitmap();

        // sector 0 被 header 占用
        usedSectors.force(0, HEADER_SECTORS);

        if (!isNew) {
            // 读取已有 header
            file.read(header, 0);
            header.flip();
            // header 作为 IntBuffer 读取后 position 已变，需要重新获取
            this.offsets.clear();
            this.offsets.limit(CHUNKS_PER_REGION);

            this.fileSize = file.size();

            // 根据 offset table 重建 sector 使用位图
            for (int i = 0; i < CHUNKS_PER_REGION; i++) {
                int offset = offsets.get(i);
                if (offset != 0) {
                    int sectorNum = getSectorNumber(offset);
                    int sectorCount = getNumSectors(offset);
                    if (sectorNum >= HEADER_SECTORS && sectorCount > 0
                            && (long) sectorNum * SECTOR_SIZE < fileSize) {
                        usedSectors.force(sectorNum, sectorCount);
                    } else {
                        offsets.put(i, 0); // 清除无效 entry
                    }
                }
            }
        } else {
            this.fileSize = SECTOR_SIZE; // 新文件只有 header
            // 写入空 header
            header.position(SECTOR_SIZE);
            header.flip();
            file.write(header, 0);
        }
    }

    /**
     * 读取区块的原始字节（包含压缩类型 + 压缩数据）
     *
     * @return 区块数据字节数组，不存在返回 null
     */
    public byte[] readChunk(ChunkPos pos) throws IOException {
        int index = getChunkIndex(pos);
        int offset = offsets.get(index);
        if (offset == 0) {
            return null;
        }

        int sectorNum = getSectorNumber(offset);
        int sectorCount = getNumSectors(offset);
        int dataSize = sectorCount * SECTOR_SIZE;

        ByteBuffer buffer = ByteBuffer.allocate(dataSize);
        int read = file.read(buffer, (long) sectorNum * SECTOR_SIZE);
        if (read <= 5) {
            return null;
        }
        buffer.flip();

        // 前 4 字节是实际数据长度
        int actualLength = buffer.getInt();
        if (actualLength <= 0 || actualLength > dataSize - 4) {
            Constants.LOG.warn("Hassium: Invalid chunk data length {} in region file for {}", actualLength, pos);
            return null;
        }

        byte[] data = new byte[actualLength];
        buffer.get(data);
        return data;
    }

    /**
     * 写入区块数据（包含压缩类型 + 压缩数据）
     */
    public synchronized void writeChunk(ChunkPos pos, byte[] chunkData) throws IOException {
        int index = getChunkIndex(pos);
        int oldOffset = offsets.get(index);

        // 计算需要的 sector 数量（+4 字节长度头）
        int totalSize = 4 + chunkData.length;
        int sectorsNeeded = (totalSize + SECTOR_SIZE - 1) / SECTOR_SIZE;

        // 分配新 sector
        int newSector = usedSectors.allocate(sectorsNeeded);

        // 构造写入缓冲区：[length(4B)][chunkData][padding]
        ByteBuffer buffer = ByteBuffer.allocate(sectorsNeeded * SECTOR_SIZE);
        buffer.putInt(chunkData.length);
        buffer.put(chunkData);
        buffer.flip();

        file.write(buffer, (long) newSector * SECTOR_SIZE);

        // 更新 offset table
        offsets.put(index, packOffset(newSector, sectorsNeeded));
        writeHeader();

        // 释放旧 sector
        if (oldOffset != 0) {
            int oldSector = getSectorNumber(oldOffset);
            int oldCount = getNumSectors(oldOffset);
            usedSectors.free(oldSector, oldCount);
        }

        // 更新文件大小
        long newEnd = (long) (newSector + sectorsNeeded) * SECTOR_SIZE;
        if (newEnd > fileSize) {
            fileSize = newEnd;
        }
    }

    /**
     * 检查区块是否存在
     */
    public boolean hasChunk(ChunkPos pos) {
        return offsets.get(getChunkIndex(pos)) != 0;
    }

    /**
     * 删除区块
     */
    public synchronized void deleteChunk(ChunkPos pos) throws IOException {
        int index = getChunkIndex(pos);
        int offset = offsets.get(index);
        if (offset != 0) {
            offsets.put(index, 0);
            writeHeader();
            usedSectors.free(getSectorNumber(offset), getNumSectors(offset));
        }
    }

    /**
     * 获取文件修改时间（毫秒），用于缓存校验
     */
    public long getFileTimestamp() throws IOException {
        return Files.getLastModifiedTime(filePath).toMillis();
    }

    @Override
    public void close() throws IOException {
        file.force(true);
        file.close();
    }

    private void writeHeader() throws IOException {
        header.clear();
        // IntBuffer 和 ByteBuffer 共享同一个底层数组
        // offsets 的修改已经反映在 header 中
        header.position(0);
        header.limit(SECTOR_SIZE);
        file.write(header, 0);
    }

    private static int getChunkIndex(ChunkPos pos) {
        return (pos.x & 31) + (pos.z & 31) * 32;
    }

    private static int packOffset(int sectorOffset, int sectorCount) {
        return sectorOffset << 8 | sectorCount;
    }

    private static int getSectorNumber(int packed) {
        return packed >> 8 & 0xFFFFFF;
    }

    private static int getNumSectors(int packed) {
        return packed & 0xFF;
    }
}
