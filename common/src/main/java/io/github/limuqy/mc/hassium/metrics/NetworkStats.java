package io.github.limuqy.mc.hassium.metrics;

/**
 * 网络统计轻量门面
 * <p>
 * 所有方法均为静态，通过 volatile boolean 控制开关。
 * 关闭时仅一次 boolean 读取（~5ns），开启时一次 CAS 操作（~15ns）。
 * <p>
 * 单一口径：
 * <ul>
 *   <li>{@code vanillaBytes*} — 仅由应用层（区块 / 聚合子包 / section-delta）写入</li>
 *   <li>{@code actualBytes*} — 仅由 Netty 管线（{@code ZstdContextEncoder} /
 *       {@code SkipAwareZstdEncoder} / {@code ZstdContextDecoder}）的 {@code recordWire*}
 *       写入</li>
 * </ul>
 * 应用层禁止再写 actual，避免双重计数。紧凑包头不单独埋点（体现在聚合 vanilla vs 线缆 actual 之差）。
 * <p>
 * 使用方式：
 * <pre>
 * // 在服务端区块发送后（仅记 vanilla；actual 由管线层 recordWireBytes* 写入）
 * NetworkStats.recordChunkSent(vanillaSize);
 *
 * // 管线编码后记录线缆字节（ZstdContextEncoder.encode 结尾）
 * NetworkStats.recordWireBytesSent(out.writerIndex() - outStart);
 *
 * // 客户端收到压缩区块后（仅记 vanilla；actual 由管线层写入）
 * NetworkStats.recordChunkReceived(compressed.originalSize);
 *
 * // 聚合包编码后记录原版等价总字节（encode 循环累加后调用一次）
 * NetworkStats.recordVanillaBytesSent(vanillaTotal);
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
     * 记录区块发送（仅记原版等价字节 + 区块计数；actual 由管线层 recordWireBytes* 写入）
     *
     * @param vanillaSize 原版等价区块大小（字节；lightStrip 时须用 unstripped 编码大小）
     */
    public static void recordChunkSent(int vanillaSize) {
        if (!enabled) return;
        if (vanillaSize > 0) metrics.recordVanillaBytesSent(vanillaSize);
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
     * 记录收到压缩区块数据（仅记原版等价字节 + 区块计数；actual 由管线层 recordWireBytes* 写入）
     *
     * @param vanillaSize 原版等价区块大小（字节，从包头解码；strip 后）
     */
    public static void recordChunkReceived(int vanillaSize) {
        if (!enabled) return;
        if (vanillaSize > 0) metrics.recordVanillaBytesReceived(vanillaSize);
        metrics.incrementChunksDecompressed();
    }

    /**
     * 线缆出站帧字节（管线 encode 后 out 增量）。
     * 仅应被 {@code ZstdContextEncoder} / {@code SkipAwareZstdEncoder} 调用。
     */
    public static void recordWireBytesSent(int wireBytes) {
        if (!enabled) return;
        if (wireBytes > 0) metrics.recordActualBytesSent(wireBytes);
    }

    /**
     * 线缆入站帧字节（管线 decode 消费的 in 增量）。
     * 仅应被 {@code ZstdContextDecoder} 调用。
     */
    public static void recordWireBytesReceived(int wireBytes) {
        if (!enabled) return;
        if (wireBytes > 0) metrics.recordActualBytesReceived(wireBytes);
    }

    /**
     * 应用层原版等价字节（服务端聚合子包等）。
     * 不含紧凑包头（由 {@link HassiumAggregationPacket} 在 encode 时调用）。
     */
    public static void recordVanillaBytesSent(long bytes) {
        if (!enabled) return;
        if (bytes > 0) metrics.recordVanillaBytesSent(bytes);
    }

    /**
     * 应用层原版等价字节（客户端聚合子包等）。
     * 不含紧凑包头（由 {@link HassiumAggregationPacket} 在 decode 时调用）。
     */
    public static void recordVanillaBytesReceived(long bytes) {
        if (!enabled) return;
        if (bytes > 0) metrics.recordVanillaBytesReceived(bytes);
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
     * 记录已完成 hash 决策的完整区块等价值。
     */
    public static void recordCacheLoadEligible(long bytes) {
        if (!enabled) return;
        metrics.recordCacheLoadEligible(bytes);
    }

    /**
     * 记录直接从本地缓存加载的完整区块等价值。
     */
    public static void recordCacheFullHit(long bytes) {
        if (!enabled) return;
        metrics.recordCacheFullHit(bytes);
    }

    /**
     * 记录成功应用分段增量后避免加载完整区块的字节数。
     */
    public static void recordCacheDeltaSaved(long bytes) {
        if (!enabled) return;
        metrics.recordCacheDeltaSaved(bytes);
    }

    /**
     * 记录已成功发出的完整区块请求及其来源。
     *
     * @param chunkCount       请求的区块数
     * @param bytes            统一完整区块等价值字节数
     * @param staleOrFallback  是否由缓存过期或技术性回退触发
     */
    public static void recordFullChunkRequests(int chunkCount, long bytes, boolean staleOrFallback) {
        if (!enabled) return;
        metrics.recordFullChunkRequests(chunkCount, bytes, staleOrFallback);
    }

    /**
     * 记录发送数据请求（1 次批请求）
     */
    public static void recordDataRequestSent() {
        if (!enabled) return;
        metrics.incrementDataRequestsSent();
    }

    /**
     * 记录发送全量数据请求（按区块数）
     */
    public static void recordDataRequestsSent(int chunkCount) {
        if (!enabled) return;
        metrics.addDataRequestsSent(chunkCount);
    }

    /**
     * 记录发出的分段增量请求（按区块数）
     */
    public static void recordSectionDeltaRequestsSent(int chunkCount) {
        if (!enabled) return;
        metrics.addSectionDeltaRequestsSent(chunkCount);
    }

    /**
     * 记录收到的分段增量（仅记 vanilla + 计数；actual 由管线统一记）。
     *
     * @param chunks       区块数
     * @param vanillaBytes 若走全量时的原版等价字节（估算）
     */
    public static void recordSectionDeltaReceived(int chunks, long vanillaBytes) {
        if (!enabled) return;
        metrics.recordSectionDeltaReceived(chunks, vanillaBytes);
    }

    // ===== 光照缓存埋点 =====

    /**
     * 记录光照缓存命中（缓存含光照数据）
     */
    public static void recordLightCacheHit() {
        if (!enabled) return;
        metrics.recordLightCacheHit();
    }

    /**
     * 记录光照缓存未命中（缓存不含光照数据，需重算）
     */
    public static void recordLightCacheMiss() {
        if (!enabled) return;
        metrics.recordLightCacheMiss();
    }

    /**
     * 记录光照重算耗时
     */
    public static void recordLightRecomputeTime(long timeNs) {
        if (!enabled) return;
        metrics.recordLightRecomputeTime(timeNs);
    }

    /**
     * 记录收到 LightDeltaS2CPacket 条目
     */
    public static void recordLightDeltaReceived(long count) {
        if (!enabled) return;
        metrics.recordLightDeltaReceived(count);
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
