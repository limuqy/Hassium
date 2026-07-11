package io.github.limuqy.mc.hassium.storage;

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
 * Hassium 统一 Region 文件实现（客户端缓存）
 * <p>
 * 格式：原版 Anvil 外层 + 扩展 MetadataTable（contentHash64）+ 压缩类型 126（ZSTD）
 * <pre>
 * Header (3 sectors, 12288 bytes):
 * ├── Sector 0: Offset Table (1024 ints, 4096 bytes)
 * └── Sector 1-2: Metadata Table (1024 × int64 contentHash, 8192 bytes)
 * Data (Sector 3+):
 * └── [length(4)][type=126][ZSTD compressed NBT/packet bytes]
 * </pre>
 */
public class HassiumRegionFile implements AutoCloseable {

    private static final int SECTOR_SIZE = 4096;
    private static final int METADATA_SECTORS = MetadataTable.METADATA_SECTORS;
    private static final int HEADER_SECTORS = 1 + METADATA_SECTORS; // 3
    private static final int CHUNKS_PER_REGION = 1024;

    private final FileChannel file;
    private final ByteBuffer header;
    private final IntBuffer offsets;
    private final MetadataTable metadataTable;
    private final RegionBitmap usedSectors;
    private final Path filePath;
    private long fileSize;

    public HassiumRegionFile(Path path) throws IOException {
        this.filePath = path;
        boolean isNew = !Files.exists(path);

        this.file = FileChannel.open(path,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);

        this.header = ByteBuffer.allocate(SECTOR_SIZE); // 仅 offset table
        this.offsets = header.asIntBuffer();
        this.offsets.limit(CHUNKS_PER_REGION);

        this.metadataTable = new MetadataTable();
        this.usedSectors = new RegionBitmap();
        usedSectors.force(0, HEADER_SECTORS);

        if (!isNew) {
            file.read(header, 0);
            header.flip();
            metadataTable.readFromFile(file, SECTOR_SIZE);
            this.fileSize = file.size();

            for (int i = 0; i < CHUNKS_PER_REGION; i++) {
                int offset = offsets.get(i);
                if (offset != 0) {
                    int sectorNum = getSectorNumber(offset);
                    int sectorCount = getNumSectors(offset);
                    if (sectorNum >= HEADER_SECTORS && sectorCount > 0
                            && (long) sectorNum * SECTOR_SIZE < fileSize) {
                        usedSectors.force(sectorNum, sectorCount);
                    } else {
                        offsets.put(i, 0);
                    }
                }
            }
        } else {
            this.fileSize = (long) SECTOR_SIZE * HEADER_SECTORS;
            header.clear();
            for (int i = 0; i < SECTOR_SIZE; i++) {
                header.put((byte) 0);
            }
            header.flip();
            file.write(header, 0);
            metadataTable.writeToFile(file, SECTOR_SIZE);
        }
    }

    public synchronized byte[] readChunk(ChunkPos pos) throws IOException {
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
     * 写入区块数据与内容哈希
     *
     * @param pos         区块位置
     * @param chunkData   [compressionType][compressedData]
     * @param contentHash 64-bit 内容指纹
     */
    public synchronized void writeChunk(ChunkPos pos, byte[] chunkData, long contentHash) throws IOException {
        int index = getChunkIndex(pos);
        int oldOffset = offsets.get(index);

        int totalSize = 4 + chunkData.length;
        int sectorsNeeded = (totalSize + SECTOR_SIZE - 1) / SECTOR_SIZE;
        int newSector = usedSectors.allocate(sectorsNeeded);

        ByteBuffer buffer = ByteBuffer.allocate(sectorsNeeded * SECTOR_SIZE);
        buffer.putInt(chunkData.length);
        buffer.put(chunkData);
        buffer.flip();

        file.write(buffer, (long) newSector * SECTOR_SIZE);

        offsets.put(index, packOffset(newSector, sectorsNeeded));
        metadataTable.writeContentHash(index, contentHash);
        writeHeader();

        if (oldOffset != 0) {
            usedSectors.free(getSectorNumber(oldOffset), getNumSectors(oldOffset));
        }

        long newEnd = (long) (newSector + sectorsNeeded) * SECTOR_SIZE;
        if (newEnd > fileSize) {
            fileSize = newEnd;
        }
    }

    /**
     * 快速读取内容哈希；区块不存在返回 0
     */
    public synchronized long readContentHash(ChunkPos pos) {
        int index = getChunkIndex(pos);
        if (offsets.get(index) == 0) {
            return 0L;
        }
        return metadataTable.readContentHash(index);
    }

    public synchronized boolean hasChunk(ChunkPos pos) {
        return offsets.get(getChunkIndex(pos)) != 0;
    }

    public synchronized void deleteChunk(ChunkPos pos) throws IOException {
        int index = getChunkIndex(pos);
        int offset = offsets.get(index);
        if (offset != 0) {
            offsets.put(index, 0);
            metadataTable.clearContentHash(index);
            writeHeader();
            usedSectors.free(getSectorNumber(offset), getNumSectors(offset));
        }
    }

    @Override
    public void close() throws IOException {
        file.force(true);
        file.close();
    }

    public long getFileTimestamp() throws IOException {
        return Files.getLastModifiedTime(filePath).toMillis();
    }

    public Path getFilePath() {
        return filePath;
    }

    public MetadataTable getMetadataTable() {
        return metadataTable;
    }

    private void writeHeader() throws IOException {
        header.clear();
        header.limit(SECTOR_SIZE);
        file.write(header, 0);
        metadataTable.writeToFile(file, SECTOR_SIZE);
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
