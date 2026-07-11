package io.github.limuqy.mc.hassium.storage;

import io.github.limuqy.mc.hassium.Constants;
import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

/**
 * 区块内容哈希元数据表（客户端缓存 / HassiumRegionFile）
 * <p>
 * 用于快速读取 contentHash，无需解压区块数据。
 * <pre>
 * 每个 entry 8 bytes：
 * └── contentHash: int64（区块地形内容指纹）
 *
 * 1024 entries × 8 bytes = 8192 bytes（2 sectors）
 * </pre>
 */
public class MetadataTable {

    public static final int METADATA_ENTRY_SIZE = 8;
    public static final int ENTRIES_PER_REGION = 1024;
    public static final int TABLE_SIZE = ENTRIES_PER_REGION * METADATA_ENTRY_SIZE; // 8192
    public static final int METADATA_SECTORS = TABLE_SIZE / 4096; // 2

    private final ByteBuffer table;

    public MetadataTable() {
        this.table = ByteBuffer.allocate(TABLE_SIZE);
        this.table.order(ByteOrder.BIG_ENDIAN);
    }

    public void readFromFile(FileChannel file, long fileOffset) throws IOException {
        ByteBuffer temp = ByteBuffer.allocate(TABLE_SIZE);
        int read = file.read(temp, fileOffset);
        if (read < TABLE_SIZE) {
            // 旧文件或截断：保持全 0
            table.clear();
            for (int i = 0; i < TABLE_SIZE; i++) {
                table.put((byte) 0);
            }
            table.position(0);
            return;
        }
        temp.flip();
        table.clear();
        table.put(temp);
        table.position(0);
    }

    public void writeToFile(FileChannel file, long fileOffset) throws IOException {
        table.position(0);
        table.limit(TABLE_SIZE);
        file.write(table, fileOffset);
        table.limit(TABLE_SIZE);
    }

    /**
     * 读取内容哈希；不存在或未写入时返回 0。
     */
    public long readContentHash(int index) {
        if (index < 0 || index >= ENTRIES_PER_REGION) {
            return 0L;
        }
        return table.getLong(index * METADATA_ENTRY_SIZE);
    }

    public long readContentHash(ChunkPos pos) {
        return readContentHash(getChunkIndex(pos));
    }

    /**
     * 写入内容哈希。若 hash 为 0，翻最低位避免与空槽混淆（空槽以 offset table 为准）。
     */
    public void writeContentHash(int index, long contentHash) {
        if (index < 0 || index >= ENTRIES_PER_REGION) {
            Constants.LOG.warn("Hassium: Invalid metadata index {}", index);
            return;
        }
        long hash = contentHash == 0L ? 1L : contentHash;
        table.putLong(index * METADATA_ENTRY_SIZE, hash);
    }

    public void writeContentHash(ChunkPos pos, long contentHash) {
        writeContentHash(getChunkIndex(pos), contentHash);
    }

    public void clearContentHash(int index) {
        if (index < 0 || index >= ENTRIES_PER_REGION) {
            return;
        }
        table.putLong(index * METADATA_ENTRY_SIZE, 0L);
    }

    public void clearContentHash(ChunkPos pos) {
        clearContentHash(getChunkIndex(pos));
    }

    public void clear() {
        table.clear();
        for (int i = 0; i < TABLE_SIZE; i++) {
            table.put((byte) 0);
        }
        table.position(0);
    }

    public static int getChunkIndex(ChunkPos pos) {
        return (pos.x & 31) + (pos.z & 31) * 32;
    }
}
