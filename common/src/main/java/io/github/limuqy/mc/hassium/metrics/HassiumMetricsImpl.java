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
    private final AtomicLong cacheHitFullChunkCount = new AtomicLong(0);
    private final AtomicLong cacheDeltaSavedBytes = new AtomicLong(0);
    private final AtomicLong cacheDeltaCount = new AtomicLong(0);
    private final AtomicLong cacheShardBytes = new AtomicLong(0);
    private final AtomicLong fullChunkRequestCount = new AtomicLong(0);
    private final AtomicLong fullChunkRequestBytes = new AtomicLong(0);
    private final AtomicLong newFullChunkRequestCount = new AtomicLong(0);
    private final AtomicLong newFullChunkRequestBytes = new AtomicLong(0);
    private final AtomicLong staleFullChunkRequestCount = new AtomicLong(0);
    private final AtomicLong staleFullChunkRequestBytes = new AtomicLong(0);
    /** SeedGen 本地生成（影子服务端）区块数/等价值字节；避免一次全量请求。 */
    private final AtomicLong locallyGeneratedChunkCount = new AtomicLong(0);
    private final AtomicLong locallyGeneratedChunkBytes = new AtomicLong(0);
    /**
     * 客户端实际落地的权威区块计数（按 chunkPos 去重；renderOnly/OVD 不计入）。
     * 冒烟「确有落地」门禁用；缓存命中率分母是 {@link #getClientAppliedChunkBytes()}。
     */
    private final AtomicLong clientAppliedChunkCount = new AtomicLong(0);
    private final java.util.Set<Long> clientAppliedChunkKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * 服务端主动直推（server_push）且客户端实际落地的区块数。
     * 该路径不经过全量请求/缓存命中/本地生成/增量任一入口，
     * 是「客户端应用区块」分母的独立分量（与 {@link #clientAppliedChunkKeys} 同去重键）。
     */
    private final AtomicLong serverPushAppliedCount = new AtomicLong(0);
    /**
     * 网络已推送完整区块、客户端仍改用本地缓存命中的区块（count/bytes）。
     * 对缓存命中率是命中；对流量节省不是「少推」，必须从缓存节省项中扣除，防止
     * 与 {@code vanillaBytesReceived}（已含该完整区块 wire）双重计数。
     */
    private final AtomicLong cacheHitNetworkReplacedCount = new AtomicLong(0);
    private final AtomicLong cacheHitNetworkReplacedBytes = new AtomicLong(0);

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

    private final AtomicLong lightCacheHitCount = new AtomicLong(0);
    private final AtomicLong lightCacheHitBytes = new AtomicLong(0);
    private final AtomicLong lightCacheMissCount = new AtomicLong(0);
    private final AtomicLong lightCacheMissBytes = new AtomicLong(0);
    /** 影子链路光照复用次数（key：light.reuse.shadow.count）。剥光协商（lightComputeSupported=true）
     *  下服务端包不带光 → hasCachedLight 恒 false，直连口径 lightCacheHitCount 不触发；
     *  影子端内存/磁盘缓存命中 + 收敛光直接回传的复用事件独立记账，与直连口径同构但互不合并。 */
    private final AtomicLong lightReuseShadowCount = new AtomicLong(0);
    /** 影子链路光照复用等价字节数（key：light.reuse.shadow.bytes；口径 = ESTIMATED_LIGHT_BYTES/chunk）。 */
    private final AtomicLong lightReuseShadowBytes = new AtomicLong(0);
    private final AtomicLong lightRecomputeTimeNs = new AtomicLong(0);
    /** 后台并行光照重算（ParallelLightEngineImpl solve）总耗时；同步路径恒 0。 */
    private final AtomicLong lightRecomputeBackgroundTimeNs = new AtomicLong(0);
    private final AtomicLong lightDeltaReceivedCount = new AtomicLong(0);
    private final AtomicLong lightVerifyMismatchCount = new AtomicLong(0);
    /** 服务端出站 chunk 包光照数据线格式字节实测（MixinLightDataWrite 累计；剥光时接近 0）。 */
    private final AtomicLong lightDataBytesWritten = new AtomicLong(0);
    private final AtomicLong lightDataWriteCount = new AtomicLong(0);

    // 数据面分流指标（PoC 多通道路由统计；口径 = 服务端发出帧 payload 等价字节数）
    private final AtomicLong bulkFramesPrimary = new AtomicLong(0);
    private final AtomicLong bulkBytesPrimary = new AtomicLong(0);
    private final AtomicLong bulkFramesData = new AtomicLong(0);
    private final AtomicLong bulkBytesData = new AtomicLong(0);
    /** 服务端 send-side per-portIdx bulk 帧/字节计数（与 client receive `perPortFrames/perPortBytes` 对称）。 */
    private final java.util.concurrent.ConcurrentHashMap<Integer, java.util.concurrent.atomic.AtomicLong>
            sendBulkFramesByPort = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Integer, java.util.concurrent.atomic.AtomicLong>
            sendBulkBytesByPort = new java.util.concurrent.ConcurrentHashMap<>();

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
    public long getCacheHitFullChunkCount() {
        return cacheHitFullChunkCount.get();
    }

    @Override
    public long getCacheDeltaSavedBytes() {
        return cacheDeltaSavedBytes.get();
    }

    @Override
    public long getCacheDeltaCount() {
        return cacheDeltaCount.get();
    }

    @Override
    public long getCacheShardBytes() {
        return cacheShardBytes.get();
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
    public long getLocallyGeneratedChunkCount() {
        return locallyGeneratedChunkCount.get();
    }

    @Override
    public long getLocallyGeneratedChunkBytes() {
        return locallyGeneratedChunkBytes.get();
    }

    @Override
    public long getClientAppliedChunkCount() {
        return getFullChunkRequestCount()
                + getCacheHitFullChunkCount()
                + getLocallyGeneratedChunkCount()
                + getCacheDeltaCount()
                + serverPushAppliedCount.get();
    }

    @Override
    public long getServerPushAppliedCount() {
        return serverPushAppliedCount.get();
    }


    @Override
    public long getClientLandedChunkCount() {
        return clientAppliedChunkCount.get();
    }

    @Override
    public long getCacheHitNetworkReplacedCount() {
        return cacheHitNetworkReplacedCount.get();
    }

    @Override
    public long getCacheHitNetworkReplacedBytes() {
        return cacheHitNetworkReplacedBytes.get();
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
    public long getLightCacheHitBytes() {
        return lightCacheHitBytes.get();
    }

    @Override
    public long getLightReuseShadowCount() {
        return lightReuseShadowCount.get();
    }

    @Override
    public long getLightReuseShadowBytes() {
        return lightReuseShadowBytes.get();
    }

    @Override
    public long getLightCacheMissCount() {
        return lightCacheMissCount.get();
    }

    @Override
    public long getLightCacheMissBytes() {
        return lightCacheMissBytes.get();
    }

    @Override
    public long getLightRecomputeTimeNs() {
        return lightRecomputeTimeNs.get();
    }

    @Override
    public long getLightRecomputeBackgroundTimeNs() {
        return lightRecomputeBackgroundTimeNs.get();
    }

    @Override
    public long getLightDeltaReceivedCount() {
        return lightDeltaReceivedCount.get();
    }

    /**
     * 记录服务端出站 chunk 包光照数据线格式字节（MixinLightDataWrite 调用）。
     *
     * @param bytes LightData.write 前后 writerIndex 差值
     */
    public void recordLightDataBytes(int bytes) {
        if (bytes > 0) {
            lightDataBytesWritten.addAndGet(bytes);
            lightDataWriteCount.incrementAndGet();
        }
    }

    /** 出站光照数据实测累计字节数。 */
    public long getLightDataBytesWritten() {
        return lightDataBytesWritten.get();
    }

    /** 出站光照数据实测写入次数（= 携带 LightData 的 chunk 包数）。 */
    public long getLightDataWriteCount() {
        return lightDataWriteCount.get();
    }

    @Override
    public long getLightVerifyMismatchCount() {
        return lightVerifyMismatchCount.get();
    }

    // ===== 数据面分流量指标 =====

    @Override
    public long getBulkFramesPrimary() {
        return bulkFramesPrimary.get();
    }

    @Override
    public long getBulkBytesPrimary() {
        return bulkBytesPrimary.get();
    }

    @Override
    public long getBulkFramesData() {
        return bulkFramesData.get();
    }

    @Override
    public long getBulkBytesData() {
        return bulkBytesData.get();
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
        cacheHitFullChunkCount.set(0);
        clientAppliedChunkCount.set(0);
        clientAppliedChunkKeys.clear();
        serverPushAppliedCount.set(0);
        cacheDeltaSavedBytes.set(0);
        cacheDeltaCount.set(0);
        cacheShardBytes.set(0);
        fullChunkRequestCount.set(0);
        fullChunkRequestBytes.set(0);
        newFullChunkRequestCount.set(0);
        newFullChunkRequestBytes.set(0);
        staleFullChunkRequestCount.set(0);
        staleFullChunkRequestBytes.set(0);
        locallyGeneratedChunkCount.set(0);
        locallyGeneratedChunkBytes.set(0);
        cacheHitNetworkReplacedCount.set(0);
        cacheHitNetworkReplacedBytes.set(0);
        networkBytesSaved.set(0);
        networkCompressTimeNs.set(0);
        networkDecompressTimeNs.set(0);
        vanillaBytesSent.set(0);
        actualBytesSent.set(0);
        vanillaBytesReceived.set(0);
        actualBytesReceived.set(0);
        metadataBytesSent.set(0);
        sendBulkFramesByPort.clear();
        sendBulkBytesByPort.clear();
        metadataBytesReceived.set(0);
        bulkFramesPrimary.set(0);
        bulkBytesPrimary.set(0);
        bulkFramesData.set(0);
        bulkBytesData.set(0);
        dataRequestsSent.set(0);
        dataRequestsReceived.set(0);
        chunksCompressed.set(0);
        chunksDecompressed.set(0);
        lightCacheHitCount.set(0);
        lightCacheHitBytes.set(0);
        lightCacheMissCount.set(0);
        lightCacheMissBytes.set(0);
        lightReuseShadowCount.set(0);
        lightReuseShadowBytes.set(0);
        lightRecomputeTimeNs.set(0);
        lightRecomputeBackgroundTimeNs.set(0);
        lightDeltaReceivedCount.set(0);
        lightVerifyMismatchCount.set(0);
        lightDataBytesWritten.set(0);
        lightDataWriteCount.set(0);
        storageErrors.set(0);
        networkErrors.set(0);
        compressionErrors.set(0);
        sectionDeltaRequestsSent.set(0);
        sectionDeltaChunksReceived.set(0);
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
        // review-fix: T9-32 与 recordCacheLoadEligible 等守卫口径一致，负值/0 不污染 hitBytes
        if (bytes > 0) {
            cacheHitBytes.addAndGet(bytes);
        }
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
        // review-fix: T9-32 与 recordCacheLoadEligible 等守卫口径一致，负值/0 不污染 missBytes
        if (bytes > 0) {
            cacheMissBytes.addAndGet(bytes);
        }
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
        // review-fix: T9-32 与 recordCacheLoadEligible 等守卫口径一致，负值/0 不污染 staleBytes
        if (bytes > 0) {
            cacheStaleBytes.addAndGet(bytes);
        }
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
     * 记录直接从本地缓存加载的完整区块等价值。每调用 1 次计 1 chunk。
     */
    public void recordCacheFullHit(long bytes) {
        cacheHitFullChunkCount.incrementAndGet();
        if (bytes > 0) {
            cacheHitFullChunkBytes.addAndGet(bytes);
        }
    }

    /**
     * 记录成功应用分段增量后避免加载完整区块的字节数。每调用 1 次计 1 delta chunk。
     */
    public void recordCacheDeltaSaved(long bytes) {
        cacheDeltaCount.incrementAndGet();
        if (bytes > 0) {
            cacheDeltaSavedBytes.addAndGet(bytes);
        }
    }

    /**
     * 记录分段增量里变更内容的等价值（分片，从命中分子扣除）。
     * {@code FULL} 整段 / {@code BLOCKS} 按格。
     */
    public void recordCacheShard(long bytes) {
        if (bytes > 0) {
            cacheShardBytes.addAndGet(bytes);
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
     * 记录 SeedGen 本地生成成功的完整区块。每调用 1 次计 1 chunk；bytes 为其等价值
     * （本地生成替代一次全量请求，口径与 {@link NetworkStats#ESTIMATED_CHUNK_BYTES} 一致）。
     */
    public void recordLocallyGeneratedChunk(long bytes) {
        locallyGeneratedChunkCount.incrementAndGet();
        if (bytes > 0) {
            locallyGeneratedChunkBytes.addAndGet(bytes);
        }
    }

    public void recordClientChunkApplied(long chunkPosKey) {
        if (clientAppliedChunkKeys.add(chunkPosKey)) {
            clientAppliedChunkCount.incrementAndGet();
        }
    }

    /**
     * 直推（server_push）落地独立分量：按键去重后自增，并入「客户端应用区块」分母。
     * 直推不经过全量请求/缓存命中/本地生成/增量任一入口，是该分母的第五个独立来源。
     */
    public void recordServerPushApplied(long chunkPosKey) {
        if (clientAppliedChunkKeys.add(chunkPosKey)) {
            clientAppliedChunkCount.incrementAndGet();
            serverPushAppliedCount.incrementAndGet();
        }
    }

    /**
     * 记录「网络已推送完整区块，客户端仍改用本地缓存」的命中。
     * 与 {@link #recordCacheFullHit(long)} 同时调用；流量节省计算会用
     * {@code cacheHitFullChunkCount - cacheHitNetworkReplacedCount} 扣除重叠。
     */
    public void recordCacheFullHitNetworkReplaced(long bytes) {
        cacheHitNetworkReplacedCount.incrementAndGet();
        if (bytes > 0) {
            cacheHitNetworkReplacedBytes.addAndGet(bytes);
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
     * 记录光照缓存命中（缓存含光照数据）。仅累加 count，不计字节；字节用 {@link #recordLightCacheHit(long)}。
     */
    public void recordLightCacheHit() {
        lightCacheHitCount.incrementAndGet();
    }

    /**
     * 记录光照缓存命中及等价字节数（常用于 honors {@code level.getSectionsCount() × 4096}）
     */
    public void recordLightCacheHit(long bytes) {
        lightCacheHitCount.incrementAndGet();
        if (bytes > 0) {
            lightCacheHitBytes.addAndGet(bytes);
        }
    }

    /**
     * 记录影子链路光照复用（剥光协商下服务端包不带光，hasCachedLight 恒 false，直连口径
     * {@link #recordLightCacheHit(long)} 不触发）。影子端内存/磁盘缓存命中 + 收敛光直接回传
     * 的复用事件由本方法独立记账（key：light.reuse.shadow.count / light.reuse.shadow.bytes），
     * 与直连口径同构、互不合并（指标可区分直连/影子口径）。
     *
     * @param bytes 等价字节数（口径与 {@link NetworkStats#ESTIMATED_LIGHT_BYTES} 一致，每 chunk 16KB）
     */
    public void recordLightReuseShadow(long bytes) {
        lightReuseShadowCount.incrementAndGet();
        if (bytes > 0) {
            lightReuseShadowBytes.addAndGet(bytes);
        }
    }

    /**
     * 记录光照缓存未命中（缓存不含光照数据，需重算）。仅累加 count。
     */
    public void recordLightCacheMiss() {
        lightCacheMissCount.incrementAndGet();
    }

    /**
     * 记录光照缓存未命中及等价字节数。
     */
    public void recordLightCacheMiss(long bytes) {
        lightCacheMissCount.incrementAndGet();
        if (bytes > 0) {
            lightCacheMissBytes.addAndGet(bytes);
        }
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
     * 记录后台并行光照重算耗时（主线程外执行；同步路径不调用）
     */
    public void recordLightRecomputeBackgroundTime(long timeNs) {
        if (timeNs > 0) {
            lightRecomputeBackgroundTimeNs.addAndGet(timeNs);
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
     * 记录光照验算差异格数（debug.lightVerify）
     */
    public void recordLightVerifyMismatch(long count) {
        if (count > 0) {
            lightVerifyMismatchCount.addAndGet(count);
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
     * 记录分段增量成功应用：计入 vanilla 等价字节（vanilla 不发 delta，会发整 chunk Zlib wire）
     * + sectionDelta 区块计数；与 actual（管线层 recordWireBytesReceived 累得 SectionDelta
     * 入站 wire ZSTD 字节）口径一致。收到即记会在「apply 失败 → 回退全量」场景把同一区块计两次，
     * 因此仅在 consumeLoop 成功应用后调用。
     * <p>
     * SectionDelta 在 vanilla 等价 = vanilla 不发 delta，只会在 chunk 变化时发完整 chunk packet（含光）
     * 走 Zlib 压缩入站，每 chunk 等价 wire = {@link VanillaZlibEstimator#estimate}(16KB)。
     *
     * @param chunks       成功应用 delta 的区块数
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

    // ===== 数据面分流记录方法 =====

    /**
     * 记录经 Primary 路径发送的 bulk 帧（PoC 多通道路由统计）。
     *
     * @param bytes CompressedChunkData.encode() 输出长度（与 Data 侧口径一致）
     */
    public void recordBulkSentPrimary(long bytes) {
        bulkFramesPrimary.incrementAndGet();
        if (bytes > 0) bulkBytesPrimary.addAndGet(bytes);
    }

    /**
     * 记录经 Data 通道发送的 bulk 帧（tryRouteBulk 写出成功时累加）。
     *
     * @param bytes 同 Primary 侧的 payload 长度（不含 Data 加密帧 nonce/meta）
     */
    public void recordBulkSentData(long bytes) {
        bulkFramesData.incrementAndGet();
        if (bytes > 0) bulkBytesData.addAndGet(bytes);
    }

    /**
     * 记录经 Data 通道某端口发送的 bulk 帧（§14 第 4 步 send-side per-portIdx）。
     * 与 {@link #recordBulkSentData(long)} 同触发点；aggregate 字段已在该方法累加，本方法只补 per-portIdx 维度。
     *
     * @param portIdx 1-based Data 端点序号（与 PlayerChannel.portIdx 一致）
     * @param bytes payload 长度（与 recordBulkSentData 一致口径）
     */
    public void recordBulkSentDataByPort(int portIdx, long bytes) {
        if (portIdx <= 0) return;
        sendBulkFramesByPort.computeIfAbsent(portIdx, k -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
        if (bytes > 0) sendBulkBytesByPort.computeIfAbsent(portIdx, k -> new java.util.concurrent.atomic.AtomicLong()).addAndGet(bytes);
    }

    /** 取 send-side per-portIdx 累计帧数；不存在则 0。 */
    public long getBulkSentFramesByPort(int portIdx) {
        java.util.concurrent.atomic.AtomicLong v = sendBulkFramesByPort.get(portIdx);
        return v == null ? 0L : v.get();
    }

    /** 取 send-side per-portIdx 累计字节数；不存在则 0。 */
    public long getBulkSentBytesByPort(int portIdx) {
        java.util.concurrent.atomic.AtomicLong v = sendBulkBytesByPort.get(portIdx);
        return v == null ? 0L : v.get();
    }

    /** send-side per-portIdx 视图快照（按 key 升序），指标命令文本用。 */
    public java.util.SortedMap<Integer, long[]> snapshotSendPerPort() {
        java.util.TreeMap<Integer, long[]> snap = new java.util.TreeMap<>();
        for (Integer k : sendBulkFramesByPort.keySet()) {
            snap.put(k, new long[]{getBulkSentFramesByPort(k), getBulkSentBytesByPort(k)});
        }
        return snap;
    }

    /**
     * 获取压缩统计信息。
     *
     * <p>映射口径（review-fix: T9-33）：CompressionStats 各字段来自<em>存储路径</em>——
     * totalCompressed/totalUncompressed = 压缩写盘/原版写盘字节（压缩率口径 = 落盘字节比）；
     * compressTimeNs/compressCount = storageWriteTimeNs/storageWriteCount（压缩后写盘，
     * 含磁盘 I/O 与序列化，非纯 codec CPU 耗时）；decompressTimeNs/decompressCount =
     * storageReadTimeNs/storageReadCount（读盘后解压，同样含磁盘 I/O）。
     * 若需纯压缩/解压 CPU 耗时需另拆字段。
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
     *
     * <p>review-fix: T9-34 原版/压缩读取共用同一 storageReadCount（写入同理），
     * 计数单列"存储读取/写入次数"，避免同一次数出现两次误读为重复计数。
     */
    public String toFormattedString() {
        String base = String.format(
                "=== Hassium 性能统计 ===\n" +
                        "存储:\n" +
                        "  原版读取: %d bytes\n" +
                        "  压缩读取: %d bytes\n" +
                        "  原版写入: %d bytes\n" +
                        "  压缩写入: %d bytes\n" +
                        "  存储读取: %d 次\n" +
                        "  存储写入: %d 次\n" +
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
                        "光照:\n" +
                        "  重算耗时: %.1f ms, 后台重算: %.1f ms, 验算差异: %d\n" +
                        "  出站 light 实测: %s（%d 块，平均 %s/块，估算 16KB/块）\n" +
                        "数据面分流:\n" +
                        "  Primary: %d 帧 (%s)\n" +
                        "  Data: %d 帧 (%s)\n" +
                        "  分流比: %s\n" +
                        "  Data per-port send (portIdx -> frames,bytes):\n" +
                        "%s" +
                        "错误:\n" +
                        "  存储: %d\n" +
                        "  网络: %d\n" +
                        "  压缩: %d",
                storageBytesVanillaRead.get(),
                storageBytesCompressedRead.get(),
                storageBytesVanillaWritten.get(),
                storageBytesCompressedWritten.get(),
                storageReadCount.get(),
                storageWriteCount.get(),
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
                lightRecomputeTimeNs.get() / 1_000_000.0,
                lightRecomputeBackgroundTimeNs.get() / 1_000_000.0, lightVerifyMismatchCount.get(),
                MetricsTextFormatter.formatBytes(lightDataBytesWritten.get()), lightDataWriteCount.get(),
                MetricsTextFormatter.formatBytes(lightDataWriteCount.get() == 0 ? 0 : lightDataBytesWritten.get() / lightDataWriteCount.get()),
                bulkFramesPrimary.get(), MetricsTextFormatter.formatBytes(bulkBytesPrimary.get()),
                bulkFramesData.get(), MetricsTextFormatter.formatBytes(bulkBytesData.get()),
                MetricsTextFormatter.formatPercent(getBulkDataSharePercent()),
                formatPerPortSend(),
                storageErrors.get(),
                networkErrors.get(),
                compressionErrors.get()
        );
        return base;
    }

    /** 渲染 send-side per-portIdx 视图为多行文本（每行 4-space 缩进，结尾带 \n）；空视图输出 "    (none)\n"。 */
    private String formatPerPortSend() {
        java.util.SortedMap<Integer, long[]> snap = snapshotSendPerPort();
        if (snap.isEmpty()) return "    (none)\n";
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<Integer, long[]> e : snap.entrySet()) {
            sb.append(String.format("    portIdx=%d: %d 帧 (%s)\n",
                    e.getKey(), e.getValue()[0], MetricsTextFormatter.formatBytes(e.getValue()[1])));
        }
        return sb.toString();
    }

}
