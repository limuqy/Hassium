package io.github.limuqy.mc.hassium.metrics;

/**
 * Hassium 性能指标接口。
 * <p>
 * 客户端统计四行（区块缓存 / 区块加载 / 光照缓存 / 流量节省）的
 * <b>语义真相源、展示公式、锚点表</b>见 {@link MetricsSemantics}。
 * 出问题先对照那里区分「计算逻辑错」还是「取值锚点错」。
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
     * <p>
     * {@link MetricsSemantics} §1 全命中：影子端读取 + 服务端 UNCHANGED。
     */
    long getCacheHitFullChunkBytes();

    /**
     * 获取直接从本地缓存加载完整区块的区块数（与 {@link #getCacheHitFullChunkBytes()} 同步累加）。
     * <p>
     * {@link MetricsSemantics} §1 全命中计数。
     */
    long getCacheHitFullChunkCount();

    /**
     * 获取成功应用分段增量后计入部分命中的完整区块等价值字节数。
     * <p>
     * {@link MetricsSemantics} §1 部分命中/增量：DELTA 合并成功。
     */
    long getCacheDeltaSavedBytes();

    /**
     * 获取分段增量命中区块数（与 {@link #getCacheDeltaSavedBytes()} 同步累加）。
     * <p>
     * {@link MetricsSemantics} §1 部分命中计数。
     */
    long getCacheDeltaCount();

    /**
     * 增量（变更内容）等价值字节：从部分命中分子中扣除，只把「未变内容」算进命中。
     * {@code FULL} 按整段，{@code BLOCKS} 按列表格数 / 4096 折一段。
     */
    long getCacheShardBytes();

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
     * <p>
     * {@link MetricsSemantics} §2：authoritative-full（SERVER_PUSH）分量。
     * 展示「新增」= 本值 + stale + serverPush（与过期不互斥）。
     */
    long getNewFullChunkRequestCount();

    /**
     * 获取由缓存过期或技术性回退导致的完整区块请求数。
     * <p>
     * {@link MetricsSemantics} §2：compare-pull FULL（REMOTE_PULL）。
     * 展示「过期」= 本值，是「新增」的子集标注，不是互斥分桶。
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

    /**
     * 获取 SeedGen 本地生成（影子服务端）区块数（无需向服务端请求）。
     * <p>
     * {@link MetricsSemantics} §2 本地：SeedGen，字节 = 整柱等价，不是 DELTA。
     */
    long getLocallyGeneratedChunkCount();

    /** OVD 窗本地源成功服务柱数（disk/inject/gen；不进缓存命中分母）。 */
    long getOvdLoadedCount();

    /** OVD 窗本地源缺失柱数（无盘无生成，空置不 pull）。 */
    long getOvdMissCount();

    /**
     * 获取 SeedGen 本地生成区块等价值字节数（与 {@link #getLocallyGeneratedChunkCount()} 同步累加）。
     */
    long getLocallyGeneratedChunkBytes();

    /**
     * 客户端应用来源柱数（冒烟结构门禁 / 区块加载对照）：
     * {@code 全量请求 + 缓存全命中 + SeedGen 本地生成 + 分段增量 + 服务端直推 + OVD 本地源服务}。
     * 直推（server_push）与 OVD（超视环带，**不算缓存命中**）为独立分量。
     * 本值为**来源事件和**，应 ≥ {@link #getClientLandedChunkCount()}（权威唯一落地）。
     * 缓存命中率分母用 {@link #getClientAppliedChunkBytes()}，不含本地生成与 OVD。
     */
    default long getClientAppliedChunkCount() {
        return getFullChunkRequestCount()
                + getCacheHitFullChunkCount()
                + getLocallyGeneratedChunkCount()
                + getCacheDeltaCount()
                + getServerPushAppliedCount()
                + getOvdLoadedCount();
    }

    /** 服务端直推且实际落地的区块数（独立分母分量；reset 清零）。 */
    long getServerPushAppliedCount();

    /**
     * 客户端应用内容等价值字节（缓存命中率分母）：
     * 全量请求 + 缓存全命中 + 分段增量基线柱 + 服务端直推。不含 SeedGen 本地生成。
     */
    default long getClientAppliedChunkBytes() {
        return getFullChunkRequestBytes()
                + getCacheHitFullChunkBytes()
                + getCacheDeltaSavedBytes()
                + getServerPushAppliedCount() * NetworkStats.ESTIMATED_CHUNK_BYTES;
    }

    /**
     * 部分命中：本地区块缓存作基线的分段增量柱（完整区块等价值）。不含本地生成。
     */
    default long getCachePartialHitBytes() {
        return getCacheDeltaSavedBytes();
    }

    default long getCachePartialHitCount() {
        return getCacheDeltaCount();
    }

    /**
     * 获取客户端实际落地的**权威**区块数（按区块位置去重）。
     * <p>
     * <b>口径</b>：会话内成功写入 {@code ClientChunkCache} 的**非 OVD**唯一坐标——
     * 网络 FULL、缓存整柱重交付、分段增量、server_push；<b>OVD/renderOnly 不计</b>。
     * OVD 进 {@link #getClientAppliedChunkCount()} 来源和与 {@link #getOvdLoadedCount()}，
     * 不进本值、不进缓存命中。
     * <p>
     * 与 {@link #getLandedTotalCount()} 同值；冒烟「确有区块落地」门禁用它。
     * {@link #getClientAppliedChunkCount()}（来源和）应 ≥ 本值。
     */
    long getClientLandedChunkCount();

    /**
     * 唯一落地总数（{@code landedTotal}）：与 {@link #getClientLandedChunkCount()} 同值。
     * <p>
     * 专供指标阅读/报告使用的显式名称，强调「含 cacheHit 重交付、按坐标去重」。
     */
    default long getLandedTotalCount() {
        return getClientLandedChunkCount();
    }

    /**
     * 获取「网络已推送完整区块，客户端仍改用本地缓存」的区块数。
     * <p>
     * 这类区块对缓存命中率而言是命中（数据源 = 本地缓存），但对流量节省而言没有减少
     * 服务端推送（数据包已真实到线），不能与 {@link #getCacheHitFullChunkCount()} 一起
     * 再计入无 MOD 应收流量，否则会双重计数。
     */
    long getCacheHitNetworkReplacedCount();

    /**
     * 获取「网络已推送完整区块，客户端仍改用本地缓存」的完整区块等价值字节数。
     */
    long getCacheHitNetworkReplacedBytes();

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

    // ===== 光照缓存指标 =====

    /**
     * 获取缓存含光照数据的区块数（is_light_on=1）
     */
    long getLightCacheHitCount();

    /**
     * 获取缓存含光照数据的等价字节数（每 chunk N sections × 4096B 累计）
     */
    long getLightCacheHitBytes();

    /**
     * 获取影子链路光照复用次数（key：light.reuse.shadow.count）。
     * <p>
     * {@link MetricsSemantics} §3 命中：影子端读取已收敛光照（区块缓存全命中且
     * isLightCorrect；或 OVD/renderOnly）。剥光协商下直连 lightCacheHitCount 恒 0。
     */
    long getLightReuseShadowCount();

    /**
     * 获取影子链路光照复用等价字节数（key：light.reuse.shadow.bytes；
     * 口径与 {@code NetworkStats.ESTIMATED_LIGHT_BYTES} 一致，每 chunk 16KB）
     */
    long getLightReuseShadowBytes();

    /**
     * 获取缓存不含光照数据需重算的区块数（is_light_on=0）
     * <p>
     * {@link MetricsSemantics} §3 重算：所有需算光场景（FULL/DELTA/欠光续算）。
     */
    long getLightCacheMissCount();

    /**
     * 获取缓存不含光照数据的等价字节数
     */
    long getLightCacheMissBytes();

    /**
     * 获取光照重算总耗时（纳秒）
     */
    long getLightRecomputeTimeNs();

    /**
     * 获取后台并行光照重算总耗时（纳秒；同步路径恒 0）
     */
    long getLightRecomputeBackgroundTimeNs();

    /**
     * 获取被计入 {@link #getLightRecomputeBackgroundTimeNs()} 的重算任务数。
     * <p>
     * 「平均每柱重算耗时」= 后台总耗时 / 本计数。与 {@code lightCacheMissCount}（按柱口径）
     * 可能不等——同柱可提交多次重算任务，故单列一个任务口径的计数。
     */
    long getLightRecomputeCount();

    /**
     * 获取光照验算差异格数（debug.lightVerify 开启时 BFS vs 官方引擎）
     */
    long getLightVerifyMismatchCount();

    // ===== 数据面分流指标 =====

    /** 经 Primary 路径发送的 bulk 帧总数。 */
    long getBulkFramesPrimary();

    /** 经 Primary 路径发送的 bulk 帧累计字节数。 */
    long getBulkBytesPrimary();

    /** 经 Data 通道发送的 bulk 帧总数。 */
    long getBulkFramesData();

    /** 经 Data 通道发送的 bulk 帧累计字节数。 */
    long getBulkBytesData();

    /** §14 第 4 步：send-side per-portIdx 累计帧数（1-based 端点序号）；不存在则 0。 */
    long getBulkSentFramesByPort(int portIdx);

    /** §14 第 4 步：send-side per-portIdx 累计字节数；不存在则 0。 */
    long getBulkSentBytesByPort(int portIdx);
    /** Data 通道分流比例（share/exclusive 模式下 Data 帧占总帧数的百分比；PoC 关注 Primary vs Data 走向）。 */
    // review-fix: T9-35 原 impl 覆盖与接口 default 逐行重复，已删除 impl 覆盖；此为唯一实现
    default double getBulkDataSharePercent() {
        long total = getBulkFramesPrimary() + getBulkFramesData();
        if (total == 0) return 0.0;
        return (double) getBulkFramesData() / total * 100.0;
    }

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
     * 有效命中内容字节：{@code 全命中 + 部分命中 - 增量}。
     * 全命中 = {@link #getCacheHitFullChunkBytes()}（磁盘/内存 contentHash 整柱复用，**权威窗**；
     * OVD 超视环带 renderOnly 回放 **不计入** 缓存命中）；
     * 部分命中 = {@link #getCachePartialHitBytes()}（缓存柱作基线的分段增量）；
     * 增量 = {@link #getCacheShardBytes()}（FULL 整段 / BLOCKS 按格折算）。
     * SeedGen 本地生成不算缓存命中，只在「区块加载 / 本地」展示。
     */
    default long getEffectiveCacheHitBytes() {
        return Math.max(0L,
                getCacheHitFullChunkBytes() + getCachePartialHitBytes() - getCacheShardBytes());
    }

    /**
     * 有效命中柱数（展示用）：全命中柱。部分命中与增量按字节扣减，不在柱数上互抵。
     */
    default long getEffectiveCacheHitCount() {
        return getCacheHitFullChunkCount();
    }

    /**
     * 缓存命中率，统一按内容等价值字节：
     * {@code (全命中 + 部分命中 - 增量) / 应用}。
     */
    default double getEffectiveCacheHitRate() {
        long appliedBytes = getClientAppliedChunkBytes();
        if (appliedBytes <= 0L) {
            return 0.0;
        }
        long hitBytes = Math.min(getEffectiveCacheHitBytes(), appliedBytes);
        return (double) hitBytes / appliedBytes;
    }

    /**
     * 无 MOD 时要接收的数据量（原版 Zlib 等价 wire）。
     * <p>
     * {@link MetricsSemantics} §4 noMod 公式与锚点。
     * <p>
     * {@code 数据包 + 本地重算 + 客户端缓存 + 光照}：
     * <ul>
     *   <li>数据包：{@link #getVanillaBytesReceived()}（实际推送的全量区块 + 分段增量
     *       的全量等价 wire，已由各接收埋点累计）</li>
     *   <li>本地重算：SeedGen 本地生成替代的全量区块 wire</li>
     *   <li>客户端缓存：真正少推的缓存全命中 wire（扣除「网络已推送但仍改用本地缓存」
     *       的重叠部分，避免双重计数）</li>
     *   <li>光照：直连光照命中 + 影子复用 + 本地重算光照的等价 wire</li>
     * </ul>
     * 超视渲染（OVD）不计入：无 MOD 时服务端本来也不会推送 serverVD 之外的区块。
     */
    default long getNoModReceiveBytes() {
        long chunkWireEstimate = VanillaZlibEstimator.estimate((int) NetworkStats.ESTIMATED_CHUNK_BYTES);
        long lightWireEstimate = VanillaZlibEstimator.estimate((int) NetworkStats.ESTIMATED_LIGHT_BYTES);
        long avoidedCacheHits = Math.max(0L,
                getCacheHitFullChunkCount() - getCacheHitNetworkReplacedCount());
        long lightTotal = getLightCacheHitCount() + getLightReuseShadowCount() + getLightCacheMissCount();
        return getVanillaBytesReceived()
                + chunkWireEstimate * getLocallyGeneratedChunkCount()
                + chunkWireEstimate * avoidedCacheHits
                + lightWireEstimate * lightTotal;
    }

    /**
     * 流量节省 = 服务端实际推送的数据量 / 无 MOD 时要接收的数据量（百分比，越小越省）。
     * <p>
     * 注意：这是「实际流量占无 MOD 原版流量的比例」，不是节省掉的百分比；
     * 已节省百分比 = {@code 100% - 本值}。
     */
    default double getTrafficSavingsPercent() {
        long noModReceive = getNoModReceiveBytes();
        if (noModReceive <= 0) return 0.0;
        return (double) getActualBytesReceived() / noModReceive * 100.0;
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
     * 计算光照缓存命中率。
     * <p>
     * 口径对齐 {@link #getCacheHitRate()}：按内容等价值字节计
     * （直连命中 + 影子复用）/（命中 + 本地重算）。剥光协商下直连命中
     * （{@link #getLightCacheHitCount()}）恒 0，实际复用由影子链路
     * （{@link #getLightReuseShadowBytes()}）承担；本地重算（光标脏缓存命中）
     * 计入 {@link #getLightCacheMissBytes()}。
     * 所有区块均由影子端原版光照链路提供，不再区分 OVD/renderOnly 柱。
     */
    default double getLightCacheHitRate() {
        long hitBytes = getLightCacheHitBytes() + getLightReuseShadowBytes();
        long totalBytes = hitBytes + getLightCacheMissBytes();
        if (totalBytes == 0) {
            // 无字节数据时回退到按次数计算
            long hit = getLightCacheHitCount() + getLightReuseShadowCount();
            long total = hit + getLightCacheMissCount();
            if (total == 0) return 0.0;
            return (double) hit / total;
        }
        return (double) hitBytes / totalBytes;
    }

    /**
     * 获取光照重算耗时（毫秒）
     */
    default double getLightRecomputeTimeMs() {
        return getLightRecomputeTimeNs() / 1_000_000.0;
    }

    /**
     * 获取后台并行光照重算耗时（毫秒）
     */
    default double getLightRecomputeBackgroundTimeMs() {
        return getLightRecomputeBackgroundTimeNs() / 1_000_000.0;
    }

    /**
     * 重置所有指标
     */
    void reset();
}
