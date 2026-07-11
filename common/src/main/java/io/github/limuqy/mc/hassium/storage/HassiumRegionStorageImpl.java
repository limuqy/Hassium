package io.github.limuqy.mc.hassium.storage;

import io.github.limuqy.mc.hassium.config.HassiumConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hassium Region 存储实现
 * <p>
 * 提供基于 ZSTD 字典压缩的区块存储，支持多种存储模式。
 * 压缩类型：126
 * 格式：与原版一致，只替换压缩算法。
 */
public class HassiumRegionStorageImpl implements HassiumRegionStorage {

    private final StorageMode mode;
    private final Path storagePath;
    private final HassiumConfig config;
    private final ChunkPayloadCodec codec;

    // 统计信息
    private final AtomicLong totalReads = new AtomicLong(0);
    private final AtomicLong totalWrites = new AtomicLong(0);
    private final AtomicLong bytesRead = new AtomicLong(0);
    private final AtomicLong bytesWritten = new AtomicLong(0);
    private final AtomicLong vanillaBytesRead = new AtomicLong(0);
    private final AtomicLong vanillaBytesWritten = new AtomicLong(0);
    private final AtomicLong hassiumBytesRead = new AtomicLong(0);
    private final AtomicLong hassiumBytesWritten = new AtomicLong(0);
    private final AtomicLong compressionErrors = new AtomicLong(0);
    private final AtomicLong storageErrors = new AtomicLong(0);

    public HassiumRegionStorageImpl(Path storagePath, HassiumConfig config, StorageMode mode) {
        this.storagePath = storagePath;
        this.config = config;
        this.mode = mode;

        // 创建 payload 编解码器
        this.codec = new ChunkPayloadCodec(config.storage().zstdLevel());
    }

    @Override
    public Optional<ChunkPayload> read(ChunkStorageKey key) throws StorageException {
        totalReads.incrementAndGet();

        try {
            // 根据存储模式读取数据
            return switch (mode) {
                case READONLY_VANILLA -> readVanilla(key);
                case MIRROR -> readMirror(key);
                case HASSIUM_ONLY -> readHassium(key);
            };
        } catch (Exception e) {
            storageErrors.incrementAndGet();
            throw new StorageException("Failed to read chunk: " + key, e);
        }
    }

    @Override
    public void write(ChunkStorageKey key, ChunkPayload payload, ChunkStorageMetadata metadata) throws StorageException {
        totalWrites.incrementAndGet();

        try {
            // 根据存储模式写入数据
            switch (mode) {
                case READONLY_VANILLA -> throw new StorageException(
                        "Cannot write in READONLY_VANILLA mode");
                case MIRROR -> writeMirror(key, payload, metadata);
                case HASSIUM_ONLY -> writeHassium(key, payload, metadata);
            }
        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            storageErrors.incrementAndGet();
            throw new StorageException("Failed to write chunk: " + key, e);
        }
    }

    @Override
    public boolean canRead(ChunkStorageKey key) {
        // 根据模式检查是否可读
        return switch (mode) {
            case READONLY_VANILLA, MIRROR -> true; // 可以读取原版数据
            case HASSIUM_ONLY -> checkHassiumExists(key);
        };
    }

    @Override
    public StorageMode mode() {
        return mode;
    }

    @Override
    public StorageStats getStats() {
        return new StorageStats(
                totalReads.get(),
                totalWrites.get(),
                bytesRead.get(),
                bytesWritten.get(),
                vanillaBytesRead.get(),
                vanillaBytesWritten.get(),
                hassiumBytesRead.get(),
                hassiumBytesWritten.get(),
                compressionErrors.get(),
                storageErrors.get()
        );
    }

    @Override
    public void close() throws IOException {
        // 释放资源
        // 刷新统计信息
    }

    /**
     * 读取原版格式数据
     */
    private Optional<ChunkPayload> readVanilla(ChunkStorageKey key) {
        // 从原版 Region 文件读取
        vanillaBytesRead.incrementAndGet();
        return Optional.empty(); // 实际实现需要读取 Region 文件
    }

    /**
     * 读取 Mirror 模式数据
     * <p>
     * 优先读取 Hassium 格式，如果不存在则读取原版格式
     */
    private Optional<ChunkPayload> readMirror(ChunkStorageKey key) {
        // 先尝试读取 Hassium 格式
        if (checkHassiumExists(key)) {
            return readHassium(key);
        }

        // 回退到原版格式
        return readVanilla(key);
    }

    /**
     * 读取 Hassium 格式数据
     */
    private Optional<ChunkPayload> readHassium(ChunkStorageKey key) {
        // 从 Hassium 存储读取
        hassiumBytesRead.incrementAndGet();

        // 实际实现需要：
        // 1. 从 Region 文件读取 chunk data
        // 2. 使用 ChunkPayloadCodec 解码
        // 3. 返回 ChunkPayload

        return Optional.empty(); // 占位
    }

    /**
     * 写入 Mirror 模式数据
     * <p>
     * 同时写入原版格式和 Hassium 格式
     */
    private void writeMirror(ChunkStorageKey key, ChunkPayload payload, ChunkStorageMetadata metadata) throws StorageException {
        // 写入原版格式
        writeVanilla(key, payload, metadata);

        // 写入 Hassium 格式
        writeHassium(key, payload, metadata);
    }

    /**
     * 写入 Hassium 格式数据
     */
    private void writeHassium(ChunkStorageKey key, ChunkPayload payload, ChunkStorageMetadata metadata) throws StorageException {
        try {
            // 使用 ChunkPayloadCodec 编码
            EncodedChunkPayload encoded = codec.encode(payload);

            // 写入文件
            // 实际实现需要写入存储
            hassiumBytesWritten.addAndGet(encoded.encodedSize());

        } catch (ChunkPayloadCodec.PayloadCodecException e) {
            compressionErrors.incrementAndGet();
            throw new StorageException("Compression failed", e);
        }
    }

    /**
     * 写入原版格式数据
     */
    private void writeVanilla(ChunkStorageKey key, ChunkPayload payload, ChunkStorageMetadata metadata) {
        // 写入原版 Region 文件
        vanillaBytesWritten.addAndGet(payload.data().length);
    }

    /**
     * 检查 Hassium 格式数据是否存在
     */
    private boolean checkHassiumExists(ChunkStorageKey key) {
        // 检查是否存在压缩类型 126 的数据
        return false;
    }
}
