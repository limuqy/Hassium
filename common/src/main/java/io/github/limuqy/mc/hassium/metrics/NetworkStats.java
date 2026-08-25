package io.github.limuqy.mc.hassium.metrics;

import io.github.limuqy.mc.hassium.network.sectiondelta.SectionPlaneSyndrome;

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

    private static volatile boolean enabled = false;
    private static final HassiumMetricsImpl metrics = new HassiumMetricsImpl();

    /**
     * 光照缓存等价值字节估算常量（与 {@code ClientMetadataHandler.ESTIMATED_CHUNK_BYTES} 同口径 16KB/chunk）。
     * 仅用于 stats 显示对齐区块缓存字节数量级，避免 {@code sectionsCount × 4096B} 让光照字段显示为区块 2-3 倍。
     * 不参与命中率计算（光照命中率走 count 比对）。
     */
    public static final long ESTIMATED_LIGHT_BYTES = 16 * 1024L;

    /**
     * 区块平均大小估算常量（16KB），用于缓存命中率/带宽节省按内容折算的统一口径。
     * 与 {@code ClientMetadataHandler.ESTIMATED_CHUNK_BYTES} 同源；OVD/数据通道等所有
     * 「vanilla 等价」字节估算都汇到这个常量，确保 bandwidth saving 单一来源。
     */
    public static final long ESTIMATED_CHUNK_BYTES = 16 * 1024L;

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

    // ===== 数据面分流埋点 =====

    /**
     * 记录经 Primary 路径发送的 bulk 帧（PoC 多通道路由统计）。
     *
     * @param bytes CompressedChunkData.encode() 输出长度
     */
    public static void recordBulkSentPrimary(long bytes) {
        if (!enabled) return;
        metrics.recordBulkSentPrimary(bytes);
    }

    /**
     * 记录经 Data 通道发送的 bulk 帧（tryRouteBulk 写出成功时累加）。
     *
     * @param bytes 同 Primary 侧口径的 payload 长度
     */
    public static void recordBulkSentData(long bytes) {
        if (!enabled) return;
        metrics.recordBulkSentData(bytes);
    }

    /**
     * 记录经 Data 通道某端口发送的 bulk 帧（§14 第 4 步 send-side per-portIdx）。
     * 与 {@link #recordBulkSentData(long)} 同触发点，仅补 per-portIdx 维度。
     *
     * @param portIdx 1-based Data 端点序号
     * @param bytes   payload 长度（与 recordBulkSentData 同口径）
     */
    public static void recordBulkSentDataByPort(int portIdx, long bytes) {
        if (!enabled) return;
        metrics.recordBulkSentDataByPort(portIdx, bytes);
    }

    /** 取 send-side per-portIdx 累计帧数；不存在则 0。 */
    public static long getBulkSentFramesByPort(int portIdx) {
        return enabled ? metrics.getBulkSentFramesByPort(portIdx) : 0L;
    }

    /** 取 send-side per-portIdx 累计字节数；不存在则 0。 */
    public static long getBulkSentBytesByPort(int portIdx) {
        return enabled ? metrics.getBulkSentBytesByPort(portIdx) : 0L;
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
     * 记录 SeedGen 本地生成成功的完整区块（影子服务端生成，未向服务端请求）。
     *
     * @param bytes 等价值字节（口径与 {@link #ESTIMATED_CHUNK_BYTES} 一致）
     */
    public static void recordLocallyGeneratedChunk(long bytes) {
        if (!enabled) return;
        metrics.recordLocallyGeneratedChunk(bytes);
    }

    /**
     * 记录一个权威区块已成功应用到客户端世界（按区块坐标去重；renderOnly 不计入）。
     * 供缓存命中率分母「客户端应用区块」使用。
     */
    public static void recordChunkApplied(int chunkX, int chunkZ) {
        if (!enabled) return;
        metrics.recordClientChunkApplied(net.minecraft.world.level.ChunkPos.asLong(chunkX, chunkZ));
    }

    /**
     * 记录服务端直推（server_push）且实际落地的权威区块。
     * 直推不经过全量请求/缓存命中/本地生成/增量任一分母入口，独立计数并入分母。
     */
    public static void recordServerPushApplied(int chunkX, int chunkZ) {
        if (!enabled) return;
        metrics.recordServerPushApplied(net.minecraft.world.level.ChunkPos.asLong(chunkX, chunkZ));
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
     * <p>Primary：{@code ZstdContextDecoder}；Data：{@code DataPlaneClientBundle#onBulkArrived}
     * （UDP 应用帧 payload）。应用层（{@code ClientChunkHandler} 等）禁止再写，避免双重计数。
     * 保证多通道场景下 {@code actualBytesReceived} 覆盖 Primary + Data 两路 actual wire 字节,
     * 让 {@code getReceiveBandwidthSavingPercent} 公式 (vanilla - actual) / vanilla 不再漏算。
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
     * 记录「网络已推送完整区块，客户端仍改用本地缓存」的命中（与
     * {@link #recordCacheFullHit(long)} 同触发点补充记账）。流量节省计算时扣除，
     * 防止与已计入 {@code vanillaBytesReceived} 的完整区块 wire 双重计数。
     */
    public static void recordCacheFullHitNetworkReplaced(long bytes) {
        if (!enabled) return;
        metrics.recordCacheFullHitNetworkReplaced(bytes);
    }

    /**
     * 记录成功应用分段增量后避免加载完整区块的字节数。
     */
    public static void recordCacheDeltaSaved(long bytes) {
        if (!enabled) return;
        metrics.recordCacheDeltaSaved(bytes);
    }

    /**
     * 记录分段增量里变更内容的等价值（分片，从缓存命中分子扣除）。
     * {@code FULL} 段按整段 4096 格；{@code BLOCKS} 按列表格数。
     */
    public static void recordCacheShard(long bytes) {
        if (!enabled) return;
        metrics.recordCacheShard(bytes);
    }

    /**
     * 变更格子折成完整区块等价值：{@code 16KB × changedCells / (sectionCount × 4096)}。
     */
    public static long shardEquivBytes(long changedCells, int sectionCount) {
        if (changedCells <= 0L || sectionCount <= 0) {
            return 0L;
        }
        long denom = (long) sectionCount * SectionPlaneSyndrome.CELLS;
        long cells = Math.min(changedCells, denom);
        return ESTIMATED_CHUNK_BYTES * cells / denom;
    }

    /**
     * 全部按 {@code FULL} 段计（每段 4096 格）：{@code 16KB × changed / sections}。
     */
    public static long shardEquivBytes(int changedSections, int sectionCount) {
        if (changedSections <= 0 || sectionCount <= 0) {
            return 0L;
        }
        int n = Math.min(changedSections, sectionCount);
        return shardEquivBytes((long) n * SectionPlaneSyndrome.CELLS, sectionCount);
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
     * 记录成功应用的分段增量（仅记 vanilla 等价 + 计数；actual 由管线层 recordWireBytesReceived 统一记）。
     * 收到即记会在「apply 失败 → 回退全量」场景重复计入同一区块，因此调用点 = 成功应用后。
     *
     * @param chunks       成功应用的区块数
     * @param vanillaBytes 若走全量时的原版等价字节（估算 = vanilla Zlib 全量推等价 wire）
     */
    public static void recordSectionDeltaReceived(int chunks, long vanillaBytes) {
        if (!enabled) return;
        metrics.recordSectionDeltaReceived(chunks, vanillaBytes);
    }

    // ===== 光照缓存埋点 =====

    /**
     * 记录光照缓存命中（仅累加 count）。
     */
    public static void recordLightCacheHit() {
        if (!enabled) return;
        metrics.recordLightCacheHit();
    }

    /**
     * 记录光照缓存命中及等价字节数。每 chunk 通常 = level.getSectionsCount() × 4096B。
     */
    public static void recordLightCacheHit(long bytes) {
        if (!enabled) return;
        metrics.recordLightCacheHit(bytes);
    }

    /**
     * 记录影子链路光照复用（key：{@code light.reuse.shadow.count} / {@code light.reuse.shadow.bytes}）。
     * 剥光协商（lightComputeSupported=true）后服务端包不带光 → hasCachedLight 恒 false，
     * 直连口径 {@link #recordLightCacheHit(long)} 不触发（P2 指标死区根因）；影子端
     * 内存/磁盘缓存命中 + 收敛光直接回传的复用事件由本方法独立记账，
     * 与直连口径同构、互不合并，使剥光模式下指标可区分直连/影子。
     *
     * @param bytes 等价字节数（口径与 {@link #ESTIMATED_LIGHT_BYTES} 一致，每 chunk 16KB）
     */
    public static void recordLightReuseShadow(long bytes) {
        if (!enabled) return;
        metrics.recordLightReuseShadow(bytes);
    }

    /**
     * 影子链路光照复用次数（key：{@code light.reuse.shadow.count}）。
     */
    public static long getLightReuseShadowCount() {
        return enabled ? metrics.getLightReuseShadowCount() : 0L;
    }

    /**
     * 影子链路光照复用等价字节数（key：{@code light.reuse.shadow.bytes}）。
     */
    public static long getLightReuseShadowBytes() {
        return enabled ? metrics.getLightReuseShadowBytes() : 0L;
    }

    /**
     * 记录光照缓存未命中（仅累加 count）。
     */
    public static void recordLightCacheMiss() {
        if (!enabled) return;
        metrics.recordLightCacheMiss();
    }

    /**
     * 记录光照缓存未命中及等价字节数。
     */
    public static void recordLightCacheMiss(long bytes) {
        if (!enabled) return;
        metrics.recordLightCacheMiss(bytes);
    }

    /**
     * 记录光照重算耗时
     */
    public static void recordLightRecomputeTime(long timeNs) {
        if (!enabled) return;
        metrics.recordLightRecomputeTime(timeNs);
    }

    /**
     * 记录后台并行光照重算耗时
     */
    public static void recordLightRecomputeBackgroundTime(long timeNs) {
        if (!enabled) return;
        metrics.recordLightRecomputeBackgroundTime(timeNs);
    }

    /**
     * 记录收到 LightDeltaS2CPacket 条目
     */
    public static void recordLightDeltaReceived(long count) {
        if (!enabled) return;
        metrics.recordLightDeltaReceived(count);
    }

    /**
     * 记录光照验算差异格数（debug.lightVerify）
     */
    public static void recordLightVerifyMismatch(long count) {
        if (!enabled) return;
        metrics.recordLightVerifyMismatch(count);
    }

    /**
     * 记录服务端出站 chunk 包光照数据线格式字节实测
     * （{@code ClientboundLightUpdatePacketData.write} 前后 writerIndex 差值）。
     * 仅服务端 encode 路径调用；用于校准 {@link #ESTIMATED_LIGHT_BYTES} 估算。
     *
     * @param bytes LightData.write 实际写出的字节数（剥光时空 BitSet 接近 0）
     */
    public static void recordLightDataBytes(int bytes) {
        if (!enabled) return;
        metrics.recordLightDataBytes(bytes);
    }

    /**
     * 服务端出站光照数据实测累计字节数。
     */
    public static long getLightDataBytesWritten() {
        return enabled ? metrics.getLightDataBytesWritten() : 0L;
    }

    /**
     * 服务端出站光照数据实测写入次数（= 携带 LightData 的 chunk 包数）。
     */
    public static long getLightDataWriteCount() {
        return enabled ? metrics.getLightDataWriteCount() : 0L;
    }

    // ===== 便捷查询 =====

    /**
     * 获取格式化的统计信息
     */
    public static String getFormattedStats() {
        // review-fix: T9-38 metrics 关闭时短路，避免大 String.format 空跑（与本文件其他 getter enabled ? x : 0 模式一致）
        return enabled ? metrics.toFormattedString() : "";
    }

    /**
     * 重置所有指标
     */
    public static void reset() {
        metrics.reset();
    }
}
