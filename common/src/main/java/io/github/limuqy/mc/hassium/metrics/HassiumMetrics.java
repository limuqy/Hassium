package io.github.limuqy.mc.hassium.metrics;

/**
 * Hassium 性能指标接口
 */
public interface HassiumMetrics {

    // ===== 存储指标 =====

    /**
     * 获取原版存储读取字节数
     */
    long getStorageBytesVanillaRead();

    /**
     * 获取原版存储写入字节数
     */
    long getStorageBytesVanillaWritten();

    /**
     * 获取压缩存储读取字节数
     */
    long getStorageBytesCompressedRead();

    /**
     * 获取压缩存储写入字节数
     */
    long getStorageBytesCompressedWritten();

    /**
     * 获取存储读取次数
     */
    long getStorageReadCount();

    /**
     * 获取存储写入次数
     */
    long getStorageWriteCount();

    /**
     * 获取存储读取耗时（纳秒）
     */
    long getStorageReadTimeNs();

    /**
     * 获取存储写入耗时（纳秒）
     */
    long getStorageWriteTimeNs();

    // ===== 缓存指标 =====

    /**
     * 获取缓存命中次数
     */
    long getCacheHitCount();

    /**
     * 获取缓存未命中次数
     */
    long getCacheMissCount();

    /**
     * 获取缓存刷新次数
     */
    long getCacheStaleCount();

    /**
     * 获取缓存命中字节数
     */
    long getCacheHitBytes();

    /**
     * 获取缓存未命中字节数
     */
    long getCacheMissBytes();

    /**
     * 获取缓存过期字节数
     */
    long getCacheStaleBytes();

    /**
     * 获取已完成 hash 决策的完整区块等价值字节数。
     */
    long getCacheLoadEligibleBytes();

    /**
     * 获取直接从本地缓存加载的完整区块等价值字节数。
     */
    long getCacheHitFullChunkBytes();

    /**
     * 获取成功应用分段增量后避免加载完整区块的字节数。
     */
    long getCacheDeltaSavedBytes();

    /**
     * 获取客户端成功发出的完整区块请求数。
     */
    long getFullChunkRequestCount();

    /**
     * 获取客户端成功发出的完整区块请求等价值字节数。
     */
    long getFullChunkRequestBytes();

    /**
     * 获取由无本地缓存导致的完整区块请求数。
     */
    long getNewFullChunkRequestCount();

    /**
     * 获取由缓存过期或技术性回退导致的完整区块请求数。
     */
    long getStaleFullChunkRequestCount();

    /**
     * 获取由无本地缓存导致的完整区块请求等价值字节数。
     */
    long getNewFullChunkRequestBytes();

    /**
     * 获取由缓存过期或技术性回退导致的完整区块请求等价值字节数。
     */
    long getStaleFullChunkRequestBytes();

    // ===== 网络指标 =====

    /**
     * 获取网络节省字节数
     */
    long getNetworkBytesSaved();

    /**
     * 获取网络压缩耗时（纳秒）
     */
    long getNetworkCompressTimeNs();

    /**
     * 获取网络解压耗时（纳秒）
     */
    long getNetworkDecompressTimeNs();

    // ===== 网络流量指标 =====

    /**
     * 获取服务端发送的原版等价字节数（压缩前）
     */
    long getVanillaBytesSent();

    /**
     * 获取服务端实际发送的字节数（压缩后）
     */
    long getActualBytesSent();

    /**
     * 获取客户端接收的原版等价字节数（解压后）
     */
    long getVanillaBytesReceived();

    /**
     * 获取客户端实际接收的字节数（压缩前）
     */
    long getActualBytesReceived();

    /**
     * 获取元数据发送字节数
     */
    long getMetadataBytesSent();

    /**
     * 获取元数据接收字节数
     */
    long getMetadataBytesReceived();

    /**
     * 获取数据请求发送次数
     */
    long getDataRequestsSent();

    /**
     * 获取数据请求接收次数
     */
    long getDataRequestsReceived();

    /**
     * 获取压缩的区块数
     */
    long getChunksCompressed();

    /**
     * 获取解压的区块数
     */
    long getChunksDecompressed();

    // ===== 错误指标 =====

    /**
     * 获取存储错误次数
     */
    long getStorageErrors();

    /**
     * 获取网络错误次数
     */
    long getNetworkErrors();

    /**
     * 获取压缩错误次数
     */
    long getCompressionErrors();

    // ===== 统计方法 =====

    /**
     * 计算压缩率
     */
    default double getCompressionRatio() {
        long vanilla = getStorageBytesVanillaWritten();
        if (vanilla == 0) return 1.0;
        return (double) getStorageBytesCompressedWritten() / vanilla;
    }

    /**
     * 计算缓存命中率（按内容字节数）
     */
    default double getCacheHitRate() {
        long hitBytes = getCacheHitBytes();
        long totalBytes = hitBytes + getCacheMissBytes() + getCacheStaleBytes();
        if (totalBytes == 0) {
            // 无字节数据时回退到按次数计算
            long total = getCacheHitCount() + getCacheMissCount() + getCacheStaleCount();
            if (total == 0) return 0.0;
            return (double) getCacheHitCount() / total;
        }
        return (double) hitBytes / totalBytes;
    }

    /**
     * 计算有效缓存命中字节数。
     */
    default long getEffectiveCacheHitBytes() {
        return getCacheHitFullChunkBytes() + getCacheDeltaSavedBytes();
    }

    /**
     * 计算有效缓存命中率（按避免加载完整区块的等价值字节数）。
     */
    default double getEffectiveCacheHitRate() {
        long eligibleBytes = getCacheLoadEligibleBytes();
        if (eligibleBytes <= 0) return 0.0;
        return (double) getEffectiveCacheHitBytes() / eligibleBytes;
    }

    /**
     * 计算发送端带宽节省率
     */
    default double getSendBandwidthSavingPercent() {
        long vanilla = getVanillaBytesSent();
        if (vanilla == 0) return 0.0;
        return (double) (vanilla - getActualBytesSent()) / vanilla * 100.0;
    }

    /**
     * 计算接收端带宽节省率
     */
    default double getReceiveBandwidthSavingPercent() {
        long vanilla = getVanillaBytesReceived();
        if (vanilla == 0) return 0.0;
        return (double) (vanilla - getActualBytesReceived()) / vanilla * 100.0;
    }

    /**
     * 计算网络压缩比
     */
    default double getNetworkCompressionRatio() {
        long actual = getActualBytesSent();
        if (actual == 0) return 0.0;
        return (double) getVanillaBytesSent() / actual;
    }

    /**
     * 计算元数据开销占比（相对于原版发送量）
     */
    default double getMetadataOverheadPercent() {
        long vanilla = getVanillaBytesSent();
        if (vanilla == 0) return 0.0;
        return (double) getMetadataBytesSent() / vanilla * 100.0;
    }

    /**
     * 重置所有指标
     */
    void reset();
}
