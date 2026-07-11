package io.github.limuqy.mc.hassium.storage;

import java.io.IOException;
import java.util.Optional;

/**
 * Hassium Region 存储接口
 * <p>
 * 负责提供区块读写入口，不直接暴露底层文件细节。
 */
public interface HassiumRegionStorage {

    /**
     * 读取区块数据
     *
     * @param key 区块键
     * @return 区块 payload，如果不存在则返回空
     * @throws StorageException 存储异常
     */
    Optional<ChunkPayload> read(ChunkStorageKey key) throws StorageException;

    /**
     * 写入区块数据
     *
     * @param key      区块键
     * @param payload  区块 payload
     * @param metadata 存储元数据
     * @throws StorageException 存储异常
     */
    void write(ChunkStorageKey key, ChunkPayload payload, ChunkStorageMetadata metadata) throws StorageException;

    /**
     * 检查是否可以读取指定区块
     *
     * @param key 区块键
     * @return 如果可以读取返回 true
     */
    boolean canRead(ChunkStorageKey key);

    /**
     * 获取当前存储模式
     */
    StorageMode mode();

    /**
     * 获取存储统计信息
     */
    StorageStats getStats();

    /**
     * 关闭存储，释放资源
     */
    void close() throws IOException;

    /**
     * 存储统计信息
     */
    record StorageStats(
            long totalReads,
            long totalWrites,
            long bytesRead,
            long bytesWritten,
            long vanillaBytesRead,
            long vanillaBytesWritten,
            long hassiumBytesRead,
            long hassiumBytesWritten,
            long compressionErrors,
            long storageErrors
    ) {
        /**
         * 计算压缩率
         */
        public double compressionRatio() {
            if (vanillaBytesWritten == 0) return 1.0;
            return (double) hassiumBytesWritten / vanillaBytesWritten;
        }
    }
}
