package io.github.limuqy.mc.hassium.config;

import java.util.Set;

/**
 * Hassium 配置
 */
public record HassiumConfig(
        StorageConfig storage,
        ClientCacheConfig clientCache,
        NetworkConfig network,
        CompatConfig compat,
        DebugConfig debug
) {
    /**
     * 默认配置
     */
    public static final HassiumConfig DEFAULT = new HassiumConfig(
            StorageConfig.DEFAULT,
            ClientCacheConfig.DEFAULT,
            NetworkConfig.DEFAULT,
            CompatConfig.DEFAULT,
            DebugConfig.DEFAULT
    );

    /**
     * 存储配置
     */
    public record StorageConfig(
            boolean enabled,
            String mode,
            int zstdLevel,
            String zstdDictionaryId,
            String storageCompressionAlgorithm,
            boolean verifyChecksum
    ) {
        public static final StorageConfig DEFAULT = new StorageConfig(
                true,           // enabled: 默认开启
                "mirror",        // mode: 镜像模式
                9,               // zstdLevel: 默认等级 9
                "hassium-dictionary",      // zstdDictionaryId: 使用内置字典
                "hassium:zstd",  // storageCompressionAlgorithm: 存储压缩算法
                true            // verifyChecksum: 校验和
        );
    }

    /**
     * 客户端缓存配置
     */
    public record ClientCacheConfig(
            boolean enabled,
            int maxSizeMb,
            int maxAgeDays,
            // === 热度清理相关配置 ===
            double hotScoreThreshold,
            double recencyWeight,
            double frequencyWeight,
            int cleanupIntervalTicks,
            int targetCacheSizeMb,
            int minCleanupBatchSize,
            // === Bloom Filter 配置 ===
            boolean bloomFilterEnabled,
            int bloomFilterExpectedInsertions,
            double bloomFilterFpp
    ) {
        public static final ClientCacheConfig DEFAULT = new ClientCacheConfig(
                true,   // enabled: 默认启用
                2048,   // maxSizeMb: 2GB
                30,     // maxAgeDays: 30天
                // === 热度清理默认配置 ===
                0.3,    // hotScoreThreshold: 热度阈值，低于此值视为冷区块
                0.7,    // recencyWeight: 最近访问权重
                0.3,    // frequencyWeight: 访问频率权重
                6000,   // cleanupIntervalTicks: 清理检查间隔 ticks (5分钟)
                0,      // targetCacheSizeMb: 目标缓存大小 (0=自动计算为maxSizeMb*0.8)
                100,    // minCleanupBatchSize: 每次最少清理区块数
                // === Bloom Filter 默认配置 ===
                true,   // bloomFilterEnabled: 默认启用 Bloom Filter 预筛
                10000,  // bloomFilterExpectedInsertions: 预期插入元素数量
                0.01    // bloomFilterFpp: 期望假阳性率 1%
        );

        /**
         * 获取最大缓存大小（字节）
         */
        public long maxCacheSizeBytes() {
            return (long) maxSizeMb * 1024 * 1024;
        }

        /**
         * 获取目标缓存大小（MB）
         */
        public int targetCacheSizeMb() {
            return targetCacheSizeMb > 0 ? targetCacheSizeMb : (int) (maxSizeMb * 0.8);
        }

        /**
         * 获取目标缓存大小（字节）
         */
        public long targetCacheSizeBytes() {
            return (long) targetCacheSizeMb() * 1024 * 1024;
        }
    }

    /**
     * 网络配置
     */
    public record NetworkConfig(
            boolean enabled,
            String compressionAlgorithm,
            int compressionLevel,
            int maxChunksPerTick,
            boolean globalPacketCompression,
            int globalCompressionLevel,
            int globalCompressionThreshold,
            Set<String> compressionBlacklist,
            boolean useContextCompression,
            boolean magiclessZstd,
            boolean enablePacketAggregation,
            int aggregationMinBatchSize,
            long aggregationMaxWaitTimeMs,
            int aggregationMaxSize,
            boolean enableCompactHeader,
            int serverChunkPushThreads,
            int clientChunkLoadThreads,
            boolean lightStripEnabled,
            int backgroundThreads,
            int maxChunksPerFrame,
            int maxCallbacksPerFrame,
            // === 指标监控配置 ===
            boolean metricsEnabled,
            // === 自适应吞吐配置 ===
            int targetFPS,
            int maxLightRecomputePerFrame,
            // === 动态线程池配置 ===
            boolean dynamicThreadPoolEnabled,
            int minPushThreads,
            int maxPushThreads
    ) {
        /**
         * 默认压缩黑名单（硬编码，始终排除）
         */
        public static final Set<String> DEFAULT_COMPRESSION_BLACKLIST = Set.of(
                "hassium:chunk_payload_s2c"
        );

        public static final NetworkConfig DEFAULT = new NetworkConfig(
                true,              // enabled: 默认启用
                "hassium:zstd",    // compressionAlgorithm: ZSTD
                3,                 // compressionLevel: 默认等级 3（速度优先）
                10,                // maxChunksPerTick: 每个玩家每 tick 最多发送 10 个区块
                true,              // globalPacketCompression: 默认启用全局包压缩（用 ZSTD 替换原版 Zlib）
                3,                 // globalCompressionLevel: 默认等级 3
                256,               // globalCompressionThreshold: 与原版一致
                DEFAULT_COMPRESSION_BLACKLIST,  // compressionBlacklist: 默认黑名单
                true,              // useContextCompression: 默认启用上下文压缩
                true,              // magiclessZstd: 默认启用 magicless 模式
                true,              // enablePacketAggregation: 默认启用包聚合
                4,                 // aggregationMinBatchSize: 最小批量大小
                20,                // aggregationMaxWaitTimeMs: 最大等待 20ms
                256 * 1024,        // aggregationMaxSize: 最大聚合大小 256KB
                true,              // enableCompactHeader: 默认启用紧凑包头（仅在聚合包内部使用）
                8,                 // serverChunkPushThreads: 服务端推送线程数
                10,                 // clientChunkLoadThreads: 客户端加载线程数
                true,              // lightStripEnabled: 默认启用光照剥离
                8,                 // backgroundThreads: 后台线程池大小（平台线程模式）
                10,                // maxChunksPerFrame: 每帧最多应用缓存区块数
                10,                 // maxCallbacksPerFrame: 每帧最多主线程异步回调数
                // === 指标监控默认配置 ===
                true,              // metricsEnabled: 默认启用指标收集
                // === 自适应吞吐默认配置 ===
                60,                // targetFPS: 目标 FPS，默认 60
                4,                 // maxLightRecomputePerFrame: 每帧最多重算光照区块数
                // === 动态线程池默认配置 ===
                true,              // dynamicThreadPoolEnabled: 默认启用动态线程池
                2,                 // minPushThreads: 最小推送线程数
                8                  // maxPushThreads: 最大推送线程数
        );
    }

    /**
     * 兼容性配置
     */
    public record CompatConfig(
            boolean requireClientMod,
            boolean autoDowngradeOnError
    ) {
        public static final CompatConfig DEFAULT = new CompatConfig(
                false,       // requireClientMod: 不强制要求客户端
                true         // autoDowngradeOnError: 错误时自动降级
        );
    }

    /**
     * 调试配置
     */
    public record DebugConfig(
            boolean metadataLogging,
            boolean dispatcherLogging,
            boolean asyncLogging,
            boolean compressionLogging,
            boolean chunkApplyLogging,
            boolean networkLogging,
            boolean cacheLogging
    ) {
        public static final DebugConfig DEFAULT = new DebugConfig(
                false,  // metadataLogging: 元数据相关日志（CLIENT_METADATA, COMPARE_METADATA, APPLY_METADATA）
                false,  // dispatcherLogging: 主线程调度器日志（MAIN_DISPATCHER）
                false,  // asyncLogging: 异步任务日志（ASYNC）
                false,  // compressionLogging: 压缩/解压日志（HANDLE_COMPRESSED）
                false,  // chunkApplyLogging: 区块应用日志（APPLY_CHUNK）
                false,  // networkLogging: 网络传输日志（SEND_CHUNK, RECEIVED）
                false   // cacheLogging: 缓存操作日志（CACHE_LOAD, CACHE_APPLY）
        );
    }
}
