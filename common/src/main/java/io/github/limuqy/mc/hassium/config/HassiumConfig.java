package io.github.limuqy.mc.hassium.config;

import io.github.limuqy.mc.hassium.network.HassiumPacketIds;
import java.util.Set;

/**
 * Hassium 配置（运行时快照）。
 * <p>
 * 物理客户端从 client.toml 加载：ClientCacheConfig + ClientNetworkConfig + DebugConfig。
 * 专用服从 server.toml 加载：StorageConfig + ServerNetworkConfig + CompatConfig + DebugConfig。
 */
public record HassiumConfig(
        StorageConfig storage,
        ClientCacheConfig clientCache,
        ClientNetworkConfig clientNetwork,
        ServerNetworkConfig serverNetwork,
        CompatConfig compat,
        DebugConfig debug
) {
    public static final HassiumConfig DEFAULT = new HassiumConfig(
            StorageConfig.DEFAULT,
            ClientCacheConfig.DEFAULT,
            ClientNetworkConfig.DEFAULT,
            ServerNetworkConfig.DEFAULT,
            CompatConfig.DEFAULT,
            DebugConfig.DEFAULT
    );

    /**
     * 存储配置（仅专用服；server.toml storage.*）
     */
    public record StorageConfig(
            boolean enabled,
            String mode,
            int zstdLevel
    ) {
        public static final StorageConfig DEFAULT = new StorageConfig(true, "mirror", 9);
    }

    /**
     * 客户端缓存配置（仅物理客户端；client.toml clientCache.*）
     * <p>
     * 吸收了原 NetworkConfig 中客户端专属字段：loadThreads、lightCacheEnabled（原 lightStrip）、maxChunksPerFrame、mainThreadChunkBudgetMs。
     * Bloom filter 参数硬编码（enabled=true, insertions=10000, fpp=0.01）。
     * maxAgeDays 已删除（热度评分隐式覆盖）。
     */
    public record ClientCacheConfig(
            boolean enabled,
            int maxSizeMb,
            int cacheCompressionLevel,
            // === 热度清理 ===
            double hotScoreThreshold,
            double recencyWeight,
            double frequencyWeight,
            int cleanupIntervalTicks,
            int targetCacheSizeMb,
            int minCleanupBatchSize,
            // === 超视渲染 ===
            boolean viewDistanceExtensionEnabled,
            int maxRenderDistance,
            int ovdUnloadDelaySecs,
            // === 分段增量 ===
            boolean sectionDeltaEnabled,
            // === JoinBoost ===
            boolean joinBoostEnabled,
            // === 实体快照 ===
            boolean entitySnapshotsEnabled,
            // === 从原 NetworkConfig 吸收的客户端字段 ===
            int loadThreads,
            boolean lightCacheEnabled,
            int maxChunksPerFrame,
            int mainThreadChunkBudgetMs
    ) {
        public static final ClientCacheConfig DEFAULT = new ClientCacheConfig(
                true,    // enabled
                4096,    // maxSizeMb
                9,       // cacheCompressionLevel
                0.3,     // hotScoreThreshold
                0.7,     // recencyWeight
                0.3,     // frequencyWeight
                6000,    // cleanupIntervalTicks
                0,       // targetCacheSizeMb (auto)
                100,     // minCleanupBatchSize
                true,    // viewDistanceExtensionEnabled
                32,      // maxRenderDistance
                5,       // ovdUnloadDelaySecs
                true,    // sectionDeltaEnabled
                true,    // joinBoostEnabled
                false,   // entitySnapshotsEnabled
                10,      // loadThreads
                true,    // lightCacheEnabled
                32,      // maxChunksPerFrame
                10       // mainThreadChunkBudgetMs
        );

        public long maxCacheSizeBytes() {
            return (long) maxSizeMb * 1024 * 1024;
        }

        public int resolvedTargetCacheSizeMb() {
            return targetCacheSizeMb > 0 ? targetCacheSizeMb : (int) (maxSizeMb * 0.8);
        }

        public long targetCacheSizeBytes() {
            return (long) resolvedTargetCacheSizeMb() * 1024 * 1024;
        }
    }

    /**
     * 客户端网络配置（仅物理客户端；client.toml network.*）
     */
    public record ClientNetworkConfig(
            boolean enabled,
            boolean metricsEnabled
    ) {
        public static final ClientNetworkConfig DEFAULT = new ClientNetworkConfig(true, true);
    }

    /**
     * 服务端网络配置（仅专用服；server.toml network.*）
     * <p>
     * 包含共享网络行为（压缩/聚合）和服务端专属推送设置。
     * 标记"实验性"的字段当前未生效（ZstdPipelineSwitcher.switchToZstd 无调用者）。
     */
    public record ServerNetworkConfig(
            boolean enabled,
            int compressionLevel,
            boolean magiclessZstd,
            // === 实验性：全局包压缩（管线未安装）===
            boolean globalPacketCompression,
            int globalCompressionLevel,
            int globalCompressionThreshold,
            // === 上下文压缩 ===
            boolean useContextCompression,
            // === 实验性：包聚合（管线未安装）===
            boolean enablePacketAggregation,
            int aggregationMinBatchSize,
            long aggregationMaxWaitTimeMs,
            int aggregationMaxSize,
            // === 实验性：紧凑包头（管线未安装）===
            boolean enableCompactHeader,
            // === 黑名单 ===
            Set<String> compressionBlacklist,
            // === 指标 ===
            boolean metricsEnabled,
            // === 服务端推送 ===
            int maxChunksPerTick,
            int serverChunkPushThreads,
            boolean dynamicThreadPoolEnabled,
            int minPushThreads,
            int maxPushThreads,
            // === 光照剥离（服务端控制是否发包时剥离 LightData）===
            boolean lightStrip
    ) {
        public static final Set<String> DEFAULT_COMPRESSION_BLACKLIST = Set.of(
                HassiumPacketIds.CHUNK_PAYLOAD_S2C,
                HassiumPacketIds.SECTION_DELTA_S2C,
                "hassium:main",
                "hassium:aggregation"
        );

        public static final ServerNetworkConfig DEFAULT = new ServerNetworkConfig(
                true,              // enabled
                3,                 // compressionLevel
                true,              // magiclessZstd
                true,              // globalPacketCompression (experimental)
                3,                 // globalCompressionLevel (experimental)
                256,               // globalCompressionThreshold (experimental)
                true,              // useContextCompression
                true,              // enablePacketAggregation (experimental)
                4,                 // aggregationMinBatchSize (experimental)
                20,                // aggregationMaxWaitTimeMs (experimental)
                256 * 1024,        // aggregationMaxSize (experimental)
                true,              // enableCompactHeader (experimental)
                DEFAULT_COMPRESSION_BLACKLIST,
                true,              // metricsEnabled
                32,                // maxChunksPerTick
                8,                 // serverChunkPushThreads
                true,              // dynamicThreadPoolEnabled
                2,                 // minPushThreads
                8,                 // maxPushThreads
                true               // lightStrip
        );
    }

    /**
     * 兼容性配置（仅专用服；server.toml compat.*）
     */
    public record CompatConfig(
            boolean requireClientMod,
            boolean autoDowngradeOnError
    ) {
        public static final CompatConfig DEFAULT = new CompatConfig(false, true);
    }

    /**
     * 调试配置（双端各自 toml debug.*）
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
                false, false, false, false, false, false, false
        );
    }
}
