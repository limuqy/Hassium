package io.github.limuqy.mc.hassium.config;

import io.github.limuqy.mc.hassium.network.HassiumPacketIds;

import java.util.List;
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
    public HassiumConfig withClientNetwork(ClientNetworkConfig clientNetwork) {
        return new HassiumConfig(storage, clientCache, clientNetwork, serverNetwork, compat, debug);
    }

    public HassiumConfig withDebug(DebugConfig debug) {
        return new HassiumConfig(storage, clientCache, clientNetwork, serverNetwork, compat, debug);
    }

    /**
     * 存储配置（仅专用服；server.toml storage.*）
     */
    public record StorageConfig(
            boolean enabled,
            String mode,
            int zstdLevel
    ) {
        public static final StorageConfig DEFAULT = new StorageConfig(false, "mirror", 3);
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
            int mainThreadChunkBudgetMs,
            // === 光照计算引擎（默认官方引擎；接入 Promethium 后可选并行）===
            boolean parallelLightEngineEnabled,
            int parallelLightEngineThreads,
            // === 光照重算模式（默认同步双帧缓冲；false=异步预算消费）===
            boolean lightSyncMode
    ) {
        public static final ClientCacheConfig DEFAULT = new ClientCacheConfig(
                true,    // enabled
                4096,    // maxSizeMb
                3,       // cacheCompressionLevel
                0.3,     // hotScoreThreshold
                0.7,     // recencyWeight
                0.3,     // frequencyWeight
                6000,    // cleanupIntervalTicks
                0,       // targetCacheSizeMb (auto)
                100,     // minCleanupBatchSize
                true,    // viewDistanceExtensionEnabled
                16,      // maxRenderDistance
                5,       // ovdUnloadDelaySecs
                true,    // sectionDeltaEnabled
                true,    // joinBoostEnabled
                true,    // entitySnapshotsEnabled
                4,       // loadThreads
                true,    // lightCacheEnabled
                6,       // maxChunksPerFrame
                15,      // mainThreadChunkBudgetMs
                false,   // parallelLightEngineEnabled（默认官方引擎，光照经统一异步缓冲队列预算消费）
                4,       // parallelLightEngineThreads
                true     // lightSyncMode（默认同步双帧缓冲：本帧收集、下一帧尾阻塞全量落地；false=异步预算消费）
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
            boolean metricsEnabled,
            boolean metricsAutoReset,
            boolean recoveryFreeze,
            boolean seedGenEnabled
    ) {
        public static final ClientNetworkConfig DEFAULT = new ClientNetworkConfig(true, false, true, true, false);
    }

    /**
     * 客户端可达地址。bind 地址绝不使用本类型，避免 wildcard 被下发给客户端。
     */
    public record ReachableEndpoint(String host, int port, int priority) {
        public ReachableEndpoint {
            host = DataPlaneEndpointConfig.validateReachableHost(host, "reachable endpoint");
            DataPlaneEndpointConfig.validatePort(port, "reachable endpoint");
            DataPlaneEndpointConfig.validateNonNegative(priority, "reachable endpoint priority");
        }
    }

    /**
     * 一个本地 UDP socket 与其按优先级尝试的客户端可达地址。
     */
    public record UdpListenerConfig(String bindHost, int bindPort, int weight,
                                    List<ReachableEndpoint> reachableEndpoints) {
        public UdpListenerConfig {
            bindHost = DataPlaneEndpointConfig.validateBindHost(bindHost);
            DataPlaneEndpointConfig.validatePort(bindPort, "UDP bind port");
            DataPlaneEndpointConfig.validateNonNegative(weight, "UDP listener weight");
            reachableEndpoints = DataPlaneEndpointConfig.normalizeReachableEndpoints(
                    reachableEndpoints, 8, "UDP listener reachableEndpoints");
        }
    }

    /**
     * UDP data plane 与控制面恢复共用的服务端配置快照。
     */
    public record DataPlaneConfig(boolean enabled, List<UdpListenerConfig> udpListeners,
                                  long controlStallMs, long failoverExpiryMs, long recoveryWindowMs) {
        public DataPlaneConfig {
            udpListeners = DataPlaneEndpointConfig.normalizeUdpListeners(enabled, udpListeners);
            DataPlaneEndpointConfig.validatePositive(controlStallMs, "controlStallMs");
            DataPlaneEndpointConfig.validatePositive(failoverExpiryMs, "failoverExpiryMs");
            DataPlaneEndpointConfig.validatePositive(recoveryWindowMs, "recoveryWindowMs");
        }
    }

    /**
     * 服务端网络配置（仅专用服；server.toml network.*）
     * <p>
     * 包含共享网络行为（压缩/聚合）和服务端专属推送设置。
     */
    public record ServerNetworkConfig(
            boolean enabled,
            int compressionLevel,
            boolean magiclessZstd,
            // === 全局包压缩（管线级 ZSTD 替换 Zlib）===
            boolean globalPacketCompression,
            int globalCompressionLevel,
            int globalCompressionThreshold,
            // === 上下文压缩 ===
            boolean useContextCompression,
            // === 包聚合（应用层，MixinConnection 拦截）===
            boolean enablePacketAggregation,
            int aggregationMinBatchSize,
            long aggregationMaxWaitTimeMs,
            int aggregationMaxSize,
            // === 紧凑包头（聚合包内部 VarInt 索引）===
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
            boolean lightStrip,
            // === SeedGen（服务端对 pristine 区块发 SeedRef 替代区块数据）===
            boolean seedGenEnabled,
            // === 控制面重连与 UDP data plane ===
            List<ReachableEndpoint> controlReachableEndpoints,
            DataPlaneConfig dataPlane
    ) {
        public ServerNetworkConfig {
            compressionBlacklist = Set.copyOf(compressionBlacklist);
            controlReachableEndpoints = DataPlaneEndpointConfig.normalizeReachableEndpoints(
                    controlReachableEndpoints, 4, "control reachable endpoints");
        }
        /**
         * 保持现有配置后端的构造签名；端点持久化将在相应 adapter 中显式接入。
         */
        public ServerNetworkConfig(boolean enabled, int compressionLevel, boolean magiclessZstd,
                                   boolean globalPacketCompression, int globalCompressionLevel,
                                   int globalCompressionThreshold, boolean useContextCompression,
                                   boolean enablePacketAggregation, int aggregationMinBatchSize,
                                   long aggregationMaxWaitTimeMs, int aggregationMaxSize,
                                   boolean enableCompactHeader, Set<String> compressionBlacklist,
                                   boolean metricsEnabled, int maxChunksPerTick,
                                   int serverChunkPushThreads, boolean dynamicThreadPoolEnabled,
                                   int minPushThreads, int maxPushThreads, boolean lightStrip) {
            this(enabled, compressionLevel, magiclessZstd, globalPacketCompression,
                    globalCompressionLevel, globalCompressionThreshold, useContextCompression,
                    enablePacketAggregation, aggregationMinBatchSize, aggregationMaxWaitTimeMs,
                    aggregationMaxSize, enableCompactHeader, compressionBlacklist, metricsEnabled,
                    maxChunksPerTick, serverChunkPushThreads, dynamicThreadPoolEnabled,
                    minPushThreads, maxPushThreads, lightStrip, false, List.of(), DEFAULT_DATA_PLANE);
        }

        // 127.0.0.1 仅供本地开发；公网部署必须配置客户端实际可达的地址。
        private static final DataPlaneConfig DEFAULT_DATA_PLANE = new DataPlaneConfig(
                false,
                List.of(new UdpListenerConfig(
                        "0.0.0.0", 25565, 100,
                        List.of(new ReachableEndpoint("127.0.0.1", 25565, 100)))),
                6_000L,
                30_000L,
                60_000L
        );
        public static final Set<String> DEFAULT_COMPRESSION_BLACKLIST = Set.of(
                HassiumPacketIds.CHUNK_PAYLOAD_S2C,
                HassiumPacketIds.SECTION_DELTA_S2C,
                HassiumPacketIds.HANDSHAKE_S2C,
                HassiumPacketIds.DICTIONARY_SYNC_S2C,
                HassiumPacketIds.INDEX_SYNC_S2C,
                HassiumPacketIds.CHUNK_HASH_S2C,
                HassiumPacketIds.LIGHT_DELTA_S2C,
                HassiumPacketIds.BLOCK_ENTITY_DATA_S2C,
                HassiumPacketIds.MAIN_CHANNEL,
                HassiumPacketIds.AGGREGATION_S2C
        );

        public static final ServerNetworkConfig DEFAULT = new ServerNetworkConfig(
                true,              // enabled
                3,                 // compressionLevel
                true,              // magiclessZstd
                true,              // globalPacketCompression
                3,                 // globalCompressionLevel（实测流畅档：压缩等级 3 平衡 CPU 与省带宽；默认低等级减少高负载下主线程/推送线程 CPU 争用）
                256,               // globalCompressionThreshold
                true,              // useContextCompression
                true,              // enablePacketAggregation
                4,                 // aggregationMinBatchSize
                20,                // aggregationMaxWaitTimeMs
                256 * 1024,        // aggregationMaxSize
                true,              // enableCompactHeader
                DEFAULT_COMPRESSION_BLACKLIST,
                false,              // metricsEnabled
                4,                 // maxChunksPerTick（满 tick ≈ 80/s；发送速率 = 本值 × tick 节奏，掉刻自然降速保护主线程）
                2,                 // serverChunkPushThreads
                true,              // dynamicThreadPoolEnabled
                2,                 // minPushThreads
                8,                 // maxPushThreads
                true,              // lightStrip
                false,             // seedGenEnabled（默认关；Phase 3 hash 闭环完成后才允许开）
                List.of(),         // controlReachableEndpoints
                DEFAULT_DATA_PLANE
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
            boolean cacheLogging,
            boolean dataplaneLogging,
            boolean lightVerify
    ) {
        public static final DebugConfig DEFAULT = new DebugConfig(
                false, false, false, false, false, false, false, false, false
        );
    }
}
