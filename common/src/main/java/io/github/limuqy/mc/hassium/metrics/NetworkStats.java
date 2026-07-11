package io.github.limuqy.mc.hassium.metrics;

/**
 * 网络统计轻量门面
 * <p>
 * 所有方法均为静态，通过 volatile boolean 控制开关。
 * 关闭时仅一次 boolean 读取（~5ns），开启时一次 CAS 操作（~15ns）。
 * <p>
 * 使用方式：
 * <pre>
 * // 在服务端区块发送后
 * NetworkStats.recordChunkSent(originalSize, compressedSize);
 *
 * // 在客户端缓存比对时
 * NetworkStats.recordCacheHit();
 * </pre>
 */
public class NetworkStats {

    private static volatile boolean enabled = true;
    private static final HassiumMetricsImpl metrics = new HassiumMetricsImpl();

    private NetworkStats() {}

    // ===== 开关控制 =====

    /**
     * 设置是否启用指标收集
     */
    public static void setEnabled(boolean enabled) {
        NetworkStats.enabled = enabled;
    }

    /**
     * 获取指标收集是否启用
     */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * 获取底层指标实例
     */
    public static HassiumMetricsImpl getMetrics() {
        return metrics;
    }

    // ===== 服务端埋点 =====

    /**
     * 记录区块发送（压缩前后对比）
     *
     * @param originalSize   原始区块大小（字节）
     * @param compressedSize 压缩后大小（字节）
     */
    public static void recordChunkSent(int originalSize, int compressedSize) {
        if (!enabled) return;
        metrics.recordVanillaBytesSent(originalSize);
        metrics.recordActualBytesSent(compressedSize);
        metrics.incrementChunksCompressed();
    }

    /**
     * 记录元数据发送
     *
     * @param bytes 元数据包大小（字节）
     */
    public static void recordMetadataSent(int bytes) {
        if (!enabled) return;
        metrics.recordMetadataBytesSent(bytes);
    }

    /**
     * 记录收到数据请求
     */
    public static void recordDataRequestReceived() {
        if (!enabled) return;
        metrics.incrementDataRequestsReceived();
    }

    // ===== 客户端埋点 =====

    /**
     * 记录收到压缩区块数据
     *
     * @param originalSize   原始区块大小（字节，从包头解码）
     * @param compressedSize 实际接收的压缩大小（字节）
     */
    public static void recordChunkReceived(int originalSize, int compressedSize) {
        if (!enabled) return;
        metrics.recordVanillaBytesReceived(originalSize);
        metrics.recordActualBytesReceived(compressedSize);
        metrics.incrementChunksDecompressed();
    }

    /**
     * 记录收到元数据
     *
     * @param bytes 元数据包大小（字节）
     */
    public static void recordMetadataReceived(int bytes) {
        if (!enabled) return;
        metrics.recordMetadataBytesReceived(bytes);
    }

    /**
     * 记录缓存命中
     */
    public static void recordCacheHit() {
        if (!enabled) return;
        metrics.recordCacheHit();
    }

    /**
     * 记录缓存命中（带字节数）
     *
     * @param bytes 区块原始大小（字节）
     */
    public static void recordCacheHit(long bytes) {
        if (!enabled) return;
        metrics.recordCacheHit(bytes);
    }

    /**
     * 记录缓存未命中
     */
    public static void recordCacheMiss() {
        if (!enabled) return;
        metrics.recordCacheMiss();
    }

    /**
     * 记录缓存未命中（带字节数）
     *
     * @param bytes 区块原始大小（字节）
     */
    public static void recordCacheMiss(long bytes) {
        if (!enabled) return;
        metrics.recordCacheMiss(bytes);
    }

    /**
     * 记录缓存过期
     */
    public static void recordCacheStale() {
        if (!enabled) return;
        metrics.recordCacheStale();
    }

    /**
     * 记录缓存过期（带字节数）
     *
     * @param bytes 区块原始大小（字节）
     */
    public static void recordCacheStale(long bytes) {
        if (!enabled) return;
        metrics.recordCacheStale(bytes);
    }

    /**
     * 记录发送数据请求
     */
    public static void recordDataRequestSent() {
        if (!enabled) return;
        metrics.incrementDataRequestsSent();
    }

    // ===== 便捷查询 =====

    /**
     * 获取格式化的统计信息
     */
    public static String getFormattedStats() {
        return metrics.toFormattedString();
    }

    /**
     * 重置所有指标
     */
    public static void reset() {
        metrics.reset();
    }
}
