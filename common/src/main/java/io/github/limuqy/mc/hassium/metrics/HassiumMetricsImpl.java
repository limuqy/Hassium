package io.github.limuqy.mc.hassium.metrics;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Hassium 性能指标实现
 */
public class HassiumMetricsImpl implements HassiumMetrics {

    // 存储指标
    private final AtomicLong storageBytesVanillaRead = new AtomicLong(0);
    private final AtomicLong storageBytesVanillaWritten = new AtomicLong(0);
    private final AtomicLong storageBytesCompressedRead = new AtomicLong(0);
    private final AtomicLong storageBytesCompressedWritten = new AtomicLong(0);
    private final AtomicLong storageReadCount = new AtomicLong(0);
    private final AtomicLong storageWriteCount = new AtomicLong(0);
    private final AtomicLong storageReadTimeNs = new AtomicLong(0);
    private final AtomicLong storageWriteTimeNs = new AtomicLong(0);

    // 缓存指标
    private final AtomicLong cacheHitCount = new AtomicLong(0);
    private final AtomicLong cacheMissCount = new AtomicLong(0);
    private final AtomicLong cacheStaleCount = new AtomicLong(0);
    private final AtomicLong cacheHitBytes = new AtomicLong(0);
    private final AtomicLong cacheMissBytes = new AtomicLong(0);
    private final AtomicLong cacheStaleBytes = new AtomicLong(0);

    // 客户端缓存加载展示指标（统一使用完整区块等价值）
    private final AtomicLong cacheLoadEligibleBytes = new AtomicLong(0);
    private final AtomicLong cacheHitFullChunkBytes = new AtomicLong(0);
    private final AtomicLong cacheDeltaSavedBytes = new AtomicLong(0);
    private final AtomicLong fullChunkRequestCount = new AtomicLong(0);
    private final AtomicLong fullChunkRequestBytes = new AtomicLong(0);
    private final AtomicLong newFullChunkRequestCount = new AtomicLong(0);
    private final AtomicLong newFullChunkRequestBytes = new AtomicLong(0);
    private final AtomicLong staleFullChunkRequestCount = new AtomicLong(0);
    private final AtomicLong staleFullChunkRequestBytes = new AtomicLong(0);

    // 网络指标
    private final AtomicLong networkBytesSaved = new AtomicLong(0);
    private final AtomicLong networkCompressTimeNs = new AtomicLong(0);
    private final AtomicLong networkDecompressTimeNs = new AtomicLong(0);

    // 网络流量指标
    private final AtomicLong vanillaBytesSent = new AtomicLong(0);
    private final AtomicLong actualBytesSent = new AtomicLong(0);
    private final AtomicLong vanillaBytesReceived = new AtomicLong(0);
    private final AtomicLong actualBytesReceived = new AtomicLong(0);
    private final AtomicLong metadataBytesSent = new AtomicLong(0);
    private final AtomicLong metadataBytesReceived = new AtomicLong(0);
    private final AtomicLong dataRequestsSent = new AtomicLong(0);
    private final AtomicLong dataRequestsReceived = new AtomicLong(0);
    private final AtomicLong chunksCompressed = new AtomicLong(0);
    private final AtomicLong chunksDecompressed = new AtomicLong(0);
    /** 客户端发出的分段增量请求区块数 */
    private final AtomicLong sectionDeltaRequestsSent = new AtomicLong(0);

    /** 客户端收到并计入流量的分段增量区块数 */
    private final AtomicLong sectionDeltaChunksReceived = new AtomicLong(0);

    // 光照缓存指标
    private final AtomicLong lightCacheHitCount = new AtomicLong(0);
    private final AtomicLong lightCacheMissCount = new AtomicLong(0);
    private final AtomicLong lightRecomputeTimeNs = new AtomicLong(0);
    private final AtomicLong lightDeltaReceivedCount = new AtomicLong(0);

    // 错误指标
    private final AtomicLong storageErrors = new AtomicLong(0);
    private final AtomicLong networkErrors = new AtomicLong(0);
    private final AtomicLong compressionErrors = new AtomicLong(0);

    @Override
    public long getStorageBytesVanillaRead() {
        return storageBytesVanillaRead.get();
    }

    @Override
    public long getStorageBytesVanillaWritten() {
        return storageBytesVanillaWritten.get();
    }

    @Override
    public long getStorageBytesCompressedRead() {
        return storageBytesCompressedRead.get();
    }

    @Override
    public long getStorageBytesCompressedWritten() {
        return storageBytesCompressedWritten.get();
    }

    @Override
    public long getStorageReadCount() {
        return storageReadCount.get();
    }

    @Override
    public long getStorageWriteCount() {
        return storageWriteCount.get();
    }

    @Override
    public long getStorageReadTimeNs() {
        return storageReadTimeNs.get();
    }

    @Override
    public long getStorageWriteTimeNs() {
        return storageWriteTimeNs.get();
    }

    @Override
    public long getCacheHitCount() {
        return cacheHitCount.get();
    }

    @Override
    public long getCacheMissCount() {
        return cacheMissCount.get();
    }

    @Override
    public long getCacheStaleCount() {
        return cacheStaleCount.get();
    }

    @Override
    public long getCacheHitBytes() {
        return cacheHitBytes.get();
    }

    @Override
    public long getCacheMissBytes() {
        return cacheMissBytes.get();
    }

    @Override
    public long getCacheStaleBytes() {
        return cacheStaleBytes.get();
    }

    @Override
    public long getCacheLoadEligibleBytes() {
        return cacheLoadEligibleBytes.get();
    }

    @Override
    public long getCacheHitFullChunkBytes() {
        return cacheHitFullChunkBytes.get();
    }

    @Override
    public long getCacheDeltaSavedBytes() {
        return cacheDeltaSavedBytes.get();
    }

    @Override
    public long getFullChunkRequestCount() {
        return fullChunkRequestCount.get();
    }

    @Override
    public long getFullChunkRequestBytes() {
        return fullChunkRequestBytes.get();
    }

    @Override
    public long getNewFullChunkRequestCount() {
        return newFullChunkRequestCount.get();
    }

    @Override
    public long getStaleFullChunkRequestCount() {
        return staleFullChunkRequestCount.get();
    }

    @Override
    public long getNewFullChunkRequestBytes() {
        return newFullChunkRequestBytes.get();
    }

    @Override
    public long getStaleFullChunkRequestBytes() {
        return staleFullChunkRequestBytes.get();
    }

    @Override
    public long getNetworkBytesSaved() {
        return networkBytesSaved.get();
    }

    @Override
    public long getNetworkCompressTimeNs() {
        return networkCompressTimeNs.get();
    }

    @Override
    public long getNetworkDecompressTimeNs() {
        return networkDecompressTimeNs.get();
    }

    // ===== 网络流量指标 =====

    @Override
    public long getVanillaBytesSent() {
        return vanillaBytesSent.get();
    }

    @Override
    public long getActualBytesSent() {
        return actualBytesSent.get();
    }

    @Override
    public long getVanillaBytesReceived() {
        return vanillaBytesReceived.get();
    }

    @Override
    public long getActualBytesReceived() {
        return actualBytesReceived.get();
    }

    @Override
    public long getMetadataBytesSent() {
        return metadataBytesSent.get();
    }

    @Override
    public long getMetadataBytesReceived() {
        return metadataBytesReceived.get();
    }

    @Override
    public long getDataRequestsSent() {
        return dataRequestsSent.get();
    }

    @Override
    public long getDataRequestsReceived() {
        return dataRequestsReceived.get();
    }

    @Override
    public long getChunksCompressed() {
        return chunksCompressed.get();
    }

    @Override
    public long getChunksDecompressed() {
        return chunksDecompressed.get();
    }

    // ===== 光照缓存指标 =====

    @Override
    public long getLightCacheHitCount() {
        return lightCacheHitCount.get();
    }

    @Override
    public long getLightCacheMissCount() {
        return lightCacheMissCount.get();
    }

    @Override
    public long getLightRecomputeTimeNs() {
        return lightRecomputeTimeNs.get();
    }

    @Override
    public long getLightDeltaReceivedCount() {
        return lightDeltaReceivedCount.get();
    }

    @Override
    public long getStorageErrors() {
        return storageErrors.get();
    }

    @Override
    public long getNetworkErrors() {
        return networkErrors.get();
    }

    @Override
    public long getCompressionErrors() {
        return compressionErrors.get();
    }

    @Override
    public void reset() {
        storageBytesVanillaRead.set(0);
        storageBytesVanillaWritten.set(0);
        storageBytesCompressedRead.set(0);
        storageBytesCompressedWritten.set(0);
        storageReadCount.set(0);
        storageWriteCount.set(0);
        storageReadTimeNs.set(0);
        storageWriteTimeNs.set(0);
        cacheHitCount.set(0);
        cacheMissCount.set(0);
        cacheStaleCount.set(0);
        cacheHitBytes.set(0);
        cacheMissBytes.set(0);
        cacheStaleBytes.set(0);
        cacheLoadEligibleBytes.set(0);
        cacheHitFullChunkBytes.set(0);
        cacheDeltaSavedBytes.set(0);
        fullChunkRequestCount.set(0);
        fullChunkRequestBytes.set(0);
        newFullChunkRequestCount.set(0);
        newFullChunkRequestBytes.set(0);
        staleFullChunkRequestCount.set(0);
        staleFullChunkRequestBytes.set(0);
        networkBytesSaved.set(0);
        networkCompressTimeNs.set(0);
        networkDecompressTimeNs.set(0);
        vanillaBytesSent.set(0);
        actualBytesSent.set(0);
        vanillaBytesReceived.set(0);
        actualBytesReceived.set(0);
        metadataBytesSent.set(0);
        metadataBytesReceived.set(0);
        dataRequestsSent.set(0);
        dataRequestsReceived.set(0);
        chunksCompressed.set(0);
        chunksDecompressed.set(0);
        sectionDeltaRequestsSent.set(0);
        sectionDeltaChunksReceived.set(0);
        lightCacheHitCount.set(0);
        lightCacheMissCount.set(0);
        lightRecomputeTimeNs.set(0);
        lightDeltaReceivedCount.set(0);
        storageErrors.set(0);
        networkErrors.set(0);
        compressionErrors.set(0);
    }

    // ===== 记录方法 =====

    /**
     * 记录存储读取
     */
    public void recordStorageRead(long bytes, long timeNs) {
        storageBytesVanillaRead.addAndGet(bytes);
        storageReadCount.incrementAndGet();
        storageReadTimeNs.addAndGet(timeNs);
    }

    /**
     * 记录压缩存储读取
     */
    public void recordCompressedStorageRead(long bytes, long timeNs) {
        storageBytesCompressedRead.addAndGet(bytes);
        storageReadCount.incrementAndGet();
        storageReadTimeNs.addAndGet(timeNs);
    }

    /**
     * 记录存储写入
     */
    public void recordStorageWrite(long bytes, long timeNs) {
        storageBytesVanillaWritten.addAndGet(bytes);
        storageWriteCount.incrementAndGet();
        storageWriteTimeNs.addAndGet(timeNs);
    }

    /**
     * 记录压缩存储写入
     */
    public void recordCompressedStorageWrite(long bytes, long timeNs) {
        storageBytesCompressedWritten.addAndGet(bytes);
        storageWriteCount.incrementAndGet();
        storageWriteTimeNs.addAndGet(timeNs);
    }

    /**
     * 记录缓存命中
     */
    public void recordCacheHit() {
        cacheHitCount.incrementAndGet();
    }

    /**
     * 记录缓存命中（带字节数）
     */
    public void recordCacheHit(long bytes) {
        cacheHitCount.incrementAndGet();
        cacheHitBytes.addAndGet(bytes);
    }

    /**
     * 记录缓存未命中
     */
    public void recordCacheMiss() {
        cacheMissCount.incrementAndGet();
    }

    /**
     * 记录缓存未命中（带字节数）
     */
    public void recordCacheMiss(long bytes) {
        cacheMissCount.incrementAndGet();
        cacheMissBytes.addAndGet(bytes);
    }

    /**
     * 记录缓存过期
     */
    public void recordCacheStale() {
        cacheStaleCount.incrementAndGet();
    }

    /**
     * 记录缓存过期（带字节数）
     */
    public void recordCacheStale(long bytes) {
        cacheStaleCount.incrementAndGet();
        cacheStaleBytes.addAndGet(bytes);
    }

    /**
     * 记录已完成 hash 决策的完整区块等价值。
     */
    public void recordCacheLoadEligible(long bytes) {
        if (bytes > 0) {
            cacheLoadEligibleBytes.addAndGet(bytes);
        }
    }

    /**
     * 记录直接从本地缓存加载的完整区块等价值。
     */
    public void recordCacheFullHit(long bytes) {
        if (bytes > 0) {
            cacheHitFullChunkBytes.addAndGet(bytes);
        }
    }

    /**
     * 记录成功应用分段增量后避免加载完整区块的字节数。
     */
    public void recordCacheDeltaSaved(long bytes) {
        if (bytes > 0) {
            cacheDeltaSavedBytes.addAndGet(bytes);
        }
    }

    /**
     * 记录已成功发出的完整区块请求。
     */
    public void recordFullChunkRequests(long chunkCount, long bytes, boolean staleOrFallback) {
        if (chunkCount <= 0 || bytes <= 0) {
            return;
        }
        fullChunkRequestCount.addAndGet(chunkCount);
        fullChunkRequestBytes.addAndGet(bytes);
        if (staleOrFallback) {
            staleFullChunkRequestCount.addAndGet(chunkCount);
            staleFullChunkRequestBytes.addAndGet(bytes);
        } else {
            newFullChunkRequestCount.addAndGet(chunkCount);
            newFullChunkRequestBytes.addAndGet(bytes);
        }
    }

    /**
     * 记录网络节省
     */
    public void recordNetworkSaved(long bytes) {
        networkBytesSaved.addAndGet(bytes);
    }

    /**
     * 记录网络压缩
     */
    public void recordNetworkCompress(long timeNs) {
        networkCompressTimeNs.addAndGet(timeNs);
    }

    /**
     * 记录网络解压
     */
    public void recordNetworkDecompress(long timeNs) {
        networkDecompressTimeNs.addAndGet(timeNs);
    }

    // ===== 光照缓存记录方法 =====

    /**
     * 记录光照缓存命中（缓存含光照数据）
     */
    public void recordLightCacheHit() {
        lightCacheHitCount.incrementAndGet();
    }

    /**
     * 记录光照缓存未命中（缓存不含光照数据，需重算）
     */
    public void recordLightCacheMiss() {
        lightCacheMissCount.incrementAndGet();
    }

    /**
     * 记录光照重算耗时
     */
    public void recordLightRecomputeTime(long timeNs) {
        if (timeNs > 0) {
            lightRecomputeTimeNs.addAndGet(timeNs);
        }
    }

    /**
     * 记录收到 LightDeltaS2CPacket 条目
     */
    public void recordLightDeltaReceived(long count) {
        if (count > 0) {
            lightDeltaReceivedCount.addAndGet(count);
        }
    }

    /**
     * 记录存储错误
     */
    public void recordStorageError() {
        storageErrors.incrementAndGet();
    }

    /**
     * 记录网络错误
     */
    public void recordNetworkError() {
        networkErrors.incrementAndGet();
    }

    /**
     * 记录压缩错误
     */
    public void recordCompressionError() {
        compressionErrors.incrementAndGet();
    }

    // ===== 网络流量记录方法 =====

    /**
     * 记录服务端发送的原版等价字节数
     */
    public void recordVanillaBytesSent(long bytes) {
        vanillaBytesSent.addAndGet(bytes);
    }

    /**
     * 记录服务端实际发送的字节数
     */
    public void recordActualBytesSent(long bytes) {
        actualBytesSent.addAndGet(bytes);
    }

    /**
     * 记录客户端接收的原版等价字节数
     */
    public void recordVanillaBytesReceived(long bytes) {
        vanillaBytesReceived.addAndGet(bytes);
    }

    /**
     * 记录客户端实际接收的字节数
     */
    public void recordActualBytesReceived(long bytes) {
        actualBytesReceived.addAndGet(bytes);
    }

    /**
     * 记录元数据发送字节数
     */
    public void recordMetadataBytesSent(long bytes) {
        metadataBytesSent.addAndGet(bytes);
    }

    /**
     * 记录元数据接收字节数
     */
    public void recordMetadataBytesReceived(long bytes) {
        metadataBytesReceived.addAndGet(bytes);
    }

    /**
     * 记录数据请求发送次数
     */
    public void incrementDataRequestsSent() {
        dataRequestsSent.incrementAndGet();
    }

    /**
     * 记录数据请求发送（按区块数累加）
     */
    public void addDataRequestsSent(long count) {
        if (count > 0) {
            dataRequestsSent.addAndGet(count);
        }
    }

    /**
     * 记录数据请求接收次数
     */
    public void incrementDataRequestsReceived() {
        dataRequestsReceived.incrementAndGet();
    }

    /**
     * 记录分段增量请求（按区块数）
     */
    public void addSectionDeltaRequestsSent(long count) {
        if (count > 0) {
            sectionDeltaRequestsSent.addAndGet(count);
        }
    }

    /**
     * 记录分段增量接收：计入 vanilla 字节 + sectionDelta 区块计数；禁止写 actualBytesReceived。
     *
     * @param chunks       收到 delta 的区块数
     * @param vanillaBytes 若走全量时的原版等价字节（估算）
     */
    public void recordSectionDeltaReceived(long chunks, long vanillaBytes) {
        if (chunks > 0) {
            sectionDeltaChunksReceived.addAndGet(chunks);
        }
        if (vanillaBytes > 0) {
            vanillaBytesReceived.addAndGet(vanillaBytes);
        }
        // 禁止再写 actualBytesReceived（管线层 recordWireBytesReceived 统一写）
    }

    public long getSectionDeltaRequestsSent() {
        return sectionDeltaRequestsSent.get();
    }

    public long getSectionDeltaChunksReceived() {
        return sectionDeltaChunksReceived.get();
    }

    /**
     * 记录压缩的区块数
     */
    public void incrementChunksCompressed() {
        chunksCompressed.incrementAndGet();
    }

    /**
     * 记录解压的区块数
     */
    public void incrementChunksDecompressed() {
        chunksDecompressed.incrementAndGet();
    }

    /**
     * 获取压缩统计信息
     */
    public CompressionStats getCompressionStats() {
        return new CompressionStats(
                storageBytesCompressedWritten.get(),
                storageBytesVanillaWritten.get(),
                storageWriteTimeNs.get(),
                storageReadTimeNs.get(),
                storageWriteCount.get(),
                storageReadCount.get(),
                compressionErrors.get()
        );
    }

    /**
     * 获取格式化的统计信息
     */
    public String toFormattedString() {
        return String.format(
                "=== Hassium 性能统计 ===\n" +
                        "存储:\n" +
                        "  原版读取: %d bytes (%d 次)\n" +
                        "  原版写入: %d bytes (%d 次)\n" +
                        "  压缩读取: %d bytes (%d 次)\n" +
                        "  压缩写入: %d bytes (%d 次)\n" +
                        "  压缩率: %s\n" +
                        "缓存:\n" +
                        "  命中: %d 次 (%s)\n" +
                        "  未命中: %d 次 (%s)\n" +
                        "  过期: %d 次 (%s)\n" +
                        "  命中率: %s (按大小)\n" +
                        "网络:\n" +
                        "  节省: %s\n" +
                        "  发送: %s (原版 %s) — 节省 %s\n" +
                        "  接收: %s (原版 %s) — 节省 %s\n" +
                        "  压缩比: %s\n" +
                        "  元数据: 发送 %s, 接收 %s\n" +
                        "  数据请求: 发送 %d, 接收 %d\n" +
                        "  分段增量: 请求 %d, 接收 %d\n" +
                        "  区块: 压缩 %d, 解压 %d\n" +
                        "错误:\n" +
                        "  存储: %d\n" +
                        "  网络: %d\n" +
                        "  压缩: %d",
                storageBytesVanillaRead.get(), storageReadCount.get(),
                storageBytesVanillaWritten.get(), storageWriteCount.get(),
                storageBytesCompressedRead.get(), storageReadCount.get(),
                storageBytesCompressedWritten.get(), storageWriteCount.get(),
                MetricsTextFormatter.formatPercent(getCompressionRatio() * 100.0),
                cacheHitCount.get(), MetricsTextFormatter.formatBytes(cacheHitBytes.get()),
                cacheMissCount.get(), MetricsTextFormatter.formatBytes(cacheMissBytes.get()),
                cacheStaleCount.get(), MetricsTextFormatter.formatBytes(cacheStaleBytes.get()),
                MetricsTextFormatter.formatPercent(getCacheHitRate() * 100.0),
                MetricsTextFormatter.formatBytes(networkBytesSaved.get()),
                MetricsTextFormatter.formatBytes(actualBytesSent.get()), MetricsTextFormatter.formatBytes(vanillaBytesSent.get()), MetricsTextFormatter.formatPercent(getSendBandwidthSavingPercent()),
                MetricsTextFormatter.formatBytes(actualBytesReceived.get()), MetricsTextFormatter.formatBytes(vanillaBytesReceived.get()), MetricsTextFormatter.formatPercent(getReceiveBandwidthSavingPercent()),
                MetricsTextFormatter.formatCompressionRatio(vanillaBytesSent.get(), actualBytesSent.get()),
                MetricsTextFormatter.formatBytes(metadataBytesSent.get()), MetricsTextFormatter.formatBytes(metadataBytesReceived.get()),
                dataRequestsSent.get(), dataRequestsReceived.get(),
                sectionDeltaRequestsSent.get(), sectionDeltaChunksReceived.get(),
                chunksCompressed.get(), chunksDecompressed.get(),
                storageErrors.get(),
                networkErrors.get(),
                compressionErrors.get()
        );
    }
}
