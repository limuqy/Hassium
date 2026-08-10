package io.github.limuqy.mc.hassium.config;

import io.github.limuqy.mc.hassium.network.HassiumPacketIds;

import java.util.List;
import java.util.Set;
/**
 * Hassium 配置（运行时快照）。
 * <p>
 * 物理客户端从 client.toml 加载：ChunkCoreConfig + NetCoreConfig + DebugConfig。
 * 专用服从 server.toml 加载：StorageConfig + MasterCoreConfig + CompatConfig + DebugConfig。
 */
public record HassiumConfig(
        StorageConfig storage,
        ChunkCoreConfig chunk,
        NetCoreConfig net,
        MasterCoreConfig master,
        CompatConfig compat,
        DebugConfig debug
) {
    public static final HassiumConfig DEFAULT = new HassiumConfig(
            StorageConfig.DEFAULT,
            ChunkCoreConfig.DEFAULT,
            NetCoreConfig.DEFAULT,
            MasterCoreConfig.DEFAULT,
            CompatConfig.DEFAULT,
            DebugConfig.DEFAULT
    );
    public HassiumConfig withNet(NetCoreConfig net) {
        return new HassiumConfig(storage, chunk, net, master, compat, debug);
    }

    public HassiumConfig withDebug(DebugConfig debug) {
        return new HassiumConfig(storage, chunk, net, master, compat, debug);
    }

    /**
     * 存储配置（仅专用服；server.toml storage.*）。
     * <p>
     * 存储模式固定为内部 mirror（原 storage.mode 键已删，REQ 决策 2/B）。
     */
    public record StorageConfig(
            boolean enabled,
            int zstdLevel
    ) {
        public static final StorageConfig DEFAULT = new StorageConfig(false, 3);
    }

    /**
     * 区块核心配置（双端；client.toml chunk.* CLIENT 21 键 + 服务端 chunk.lightStrip/chunk.seedGenEnabled）。
     * <p>
     * 吸收原 ClientCacheConfig 全族与 network.seedGen.enabled（双端同名键，按物理端加载）。
     * Bloom filter 参数硬编码（enabled=true, insertions=10000, fpp=0.01）。
     * maxAgeDays 已删除（热度评分隐式覆盖）。
     */
    public record ChunkCoreConfig(
            boolean enabled,
            int maxSizeMb,
            int compressionLevel,
            // === 热度清理（影子端容量/热度淘汰：heat.idx 热度索引 + 逐柱删除，saveAll 不再只写不删）===
            double hotScoreThreshold,
            double recencyWeight,
            double frequencyWeight,
            int cleanupIntervalTicks,
            int targetSizeMb,
            int minCleanupBatchSize,
            // === 分段增量（GatewayPacketCodec/NetworkCore/DataPlaneClientBundle 活跃消费）===
            boolean sectionDeltaEnabled,
            // === JoinBoost ===
            boolean joinBoostEnabled,
            // === 超视渲染 ===
            boolean viewDistanceExtensionEnabled,
            int maxRenderDistance,
            int ovdUnloadDelaySecs,
            // === 影子端内存区块回收（离开卸载边界后计时，超时落盘并清内存；0=禁用回收）===
            int unloadDelaySecs,
            // === 线程与应用（从原 NetworkConfig 吸收的客户端字段）===
            int maxChunksPerFrame,
            int mainThreadChunkBudgetMs,
            // === SeedGen 本地生成线程数（Phase 2；0=禁用本地生成）===
            int seedGenThreads,
            // === 影子端（非网络向功能总开关；默认 true）===
            boolean hassiumEngineEnabled,
            // === OVD 本地生成（默认 false；miss 时影子端按世界种子本地生成 + 存缓存）===
            boolean ovdLocalGeneration,
            // === SeedGen 总开关（双端同名键；物理端各自加载）===
            boolean seedGenEnabled,
            // === 光照剥离（仅专用服；服务端控制是否发包时剥离 LightData）===
            boolean lightStrip
    ) {
        public static final ChunkCoreConfig DEFAULT = new ChunkCoreConfig(
                true,    // enabled
                4096,    // maxSizeMb
                3,       // compressionLevel
                0.3,     // hotScoreThreshold
                0.7,     // recencyWeight
                0.3,     // frequencyWeight
                6000,    // cleanupIntervalTicks
                0,       // targetSizeMb (auto)
                100,     // minCleanupBatchSize
                true,    // sectionDeltaEnabled
                true,    // joinBoostEnabled
                true,    // viewDistanceExtensionEnabled
                16,      // maxRenderDistance
                5,       // ovdUnloadDelaySecs
                30,      // unloadDelaySecs（影子端内存区块回收延迟；0=禁用回收）
                6,       // maxChunksPerFrame
                15,      // mainThreadChunkBudgetMs
                2,       // seedGenThreads（本地生成线程数；0=禁用）
                true,    // hassiumEngineEnabled（默认 true：进服启动影子端承担光照计算；失败降级关闭缓存/OVD/SeedGen）
                false,   // ovdLocalGeneration（默认 false：OVD miss 时影子端本地生成 + 存缓存）
                false,   // seedGenEnabled（默认关；需双端同版本）
                true     // lightStrip（仅服务端消费；物理客户端默认值不参与加载）
        );

        public long maxCacheSizeBytes() {
            return (long) maxSizeMb * 1024 * 1024;
        }

        public int resolvedTargetCacheSizeMb() {
            return targetSizeMb > 0 ? targetSizeMb : (int) (maxSizeMb * 0.8);
        }

        public long targetCacheSizeBytes() {
            return (long) resolvedTargetCacheSizeMb() * 1024 * 1024;
        }
    }

    /**
     * 网络核心配置（仅物理客户端；client.toml net.*）。
     */
    public record NetCoreConfig(
            boolean enabled,
            boolean metricsEnabled,
            boolean metricsAutoReset
    ) {
        public static final NetCoreConfig DEFAULT = new NetCoreConfig(true, false, true);
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
     * UDP 数据面配置（仅专用服；server.toml dataplane.*）。
     */
    public record DataPlaneConfig(boolean enabled, List<UdpListenerConfig> udpListeners) {
        public DataPlaneConfig {
            udpListeners = DataPlaneEndpointConfig.normalizeUdpListeners(enabled, udpListeners);
        }
    }

    /**
     * 主控核心配置（专用服；server.toml master.* + dataplane.*）。
     * <p>
     * 服务端网络行为（压缩/聚合/推送/端点）与 L1 迁移故障超时；
     * 数据面键（dataplane.enabled/udpListeners）经 {@link DataPlaneConfig} 挂载。
     * <p>
     * L1 迁移策略参数（migrationMinTps 等）为 CLIENT scope 键：客户端 MigrationEngine
     * 消费，物理客户端经 client.toml 加载（服务端加载时取默认值不参与）；唯一例外是
     * migrationPrewarmTtlMs（SERVER scope，B 侧预热会话清理，T4 消费）。
     */
    public record MasterCoreConfig(
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
            // === 网关监听与鉴权（D-M2：默认回环绑定 + 可选握手鉴权）===
            String bindHost,
            String authToken,
            // === 控制面端点与 L1 迁移 ===
            List<ReachableEndpoint> controlReachableEndpoints,
            long migrationFaultTimeoutMs,
            // === L1 迁移策略（CLIENT scope 键；客户端经 client.toml 加载）===
            double migrationMinTps,
            double migrationMaxLoadAverage,
            String migrationMaintenanceWindow,
            long migrationHeartbeatIntervalMs,
            long migrationIdleWindowMs,
            long migrationSilentTimeoutMs,
            // === 预热会话 TTL（SERVER scope；T4 交付键，只实现+getter）===
            long migrationPrewarmTtlMs,
            // === 续流票据有效期（SERVER scope；T2 防重放时间窗口，服务端校验消费）===
            long resumeTicketTtlMs,
            DataPlaneConfig dataPlane
    ) {
        public MasterCoreConfig {
            compressionBlacklist = Set.copyOf(compressionBlacklist);
            controlReachableEndpoints = DataPlaneEndpointConfig.normalizeReachableEndpoints(
                    controlReachableEndpoints, 4, "control reachable endpoints");
            DataPlaneEndpointConfig.validatePositive(migrationFaultTimeoutMs, "migrationFaultTimeoutMs");
            if (!(migrationMinTps > 0)) {
                throw new IllegalArgumentException("migrationMinTps must be positive");
            }
            if (!(migrationMaxLoadAverage > 0)) {
                throw new IllegalArgumentException("migrationMaxLoadAverage must be positive");
            }
            DataPlaneEndpointConfig.validatePositive(migrationHeartbeatIntervalMs, "migrationHeartbeatIntervalMs");
            DataPlaneEndpointConfig.validatePositive(migrationIdleWindowMs, "migrationIdleWindowMs");
            DataPlaneEndpointConfig.validatePositive(migrationSilentTimeoutMs, "migrationSilentTimeoutMs");
            DataPlaneEndpointConfig.validatePositive(migrationPrewarmTtlMs, "migrationPrewarmTtlMs");
            DataPlaneEndpointConfig.validatePositive(resumeTicketTtlMs, "resumeTicketTtlMs");
        }

        /**
         * 便捷构造：保留既有主控字段，仅替换 L1 迁移策略参数（cloth-ui 保存路径用；
         * 其余迁移字段保持本实例值）。
         */
        public MasterCoreConfig withMigrationPolicy(double minTps, double maxLoadAverage, String maintenanceWindow,
                                                    long heartbeatIntervalMs, long idleWindowMs, long silentTimeoutMs) {
            return new MasterCoreConfig(enabled, compressionLevel, magiclessZstd,
                    globalPacketCompression, globalCompressionLevel, globalCompressionThreshold,
                    useContextCompression, enablePacketAggregation, aggregationMinBatchSize,
                    aggregationMaxWaitTimeMs, aggregationMaxSize, enableCompactHeader, compressionBlacklist,
                    metricsEnabled, maxChunksPerTick, serverChunkPushThreads, dynamicThreadPoolEnabled,
                    minPushThreads, maxPushThreads, bindHost, authToken, controlReachableEndpoints, migrationFaultTimeoutMs,
                    minTps, maxLoadAverage, maintenanceWindow, heartbeatIntervalMs, idleWindowMs,
                    silentTimeoutMs, migrationPrewarmTtlMs, resumeTicketTtlMs, dataPlane);
        }

        /**
         * 便捷构造：仅替换网关监听/鉴权字段（D-M2 客户端加载 client.toml 的 master.authToken 用；
         * 其余字段保持本实例值）。
         */
        public MasterCoreConfig withGatewayAuth(String bindHost, String authToken) {
            return new MasterCoreConfig(enabled, compressionLevel, magiclessZstd,
                    globalPacketCompression, globalCompressionLevel, globalCompressionThreshold,
                    useContextCompression, enablePacketAggregation, aggregationMinBatchSize,
                    aggregationMaxWaitTimeMs, aggregationMaxSize, enableCompactHeader, compressionBlacklist,
                    metricsEnabled, maxChunksPerTick, serverChunkPushThreads, dynamicThreadPoolEnabled,
                    minPushThreads, maxPushThreads, bindHost, authToken, controlReachableEndpoints, migrationFaultTimeoutMs,
                    migrationMinTps, migrationMaxLoadAverage, migrationMaintenanceWindow, migrationHeartbeatIntervalMs,
                    migrationIdleWindowMs, migrationSilentTimeoutMs, migrationPrewarmTtlMs, resumeTicketTtlMs, dataPlane);
        }

        // 127.0.0.1 仅供本地开发；公网部署必须配置客户端实际可达的地址。
        private static final DataPlaneConfig DEFAULT_DATA_PLANE = new DataPlaneConfig(
                false,
                List.of(new UdpListenerConfig(
                        "0.0.0.0", 25565, 100,
                        List.of(new ReachableEndpoint("127.0.0.1", 25565, 100))))
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

        public static final MasterCoreConfig DEFAULT = new MasterCoreConfig(
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
                5,                 // maxChunksPerTick（schema 为准；满 tick ≈ 100/s；发送速率 = 本值 × tick 节奏，掉刻自然降速保护主线程）
                2,                 // serverChunkPushThreads
                true,              // dynamicThreadPoolEnabled
                2,                 // minPushThreads
                8,                 // maxPushThreads
                "127.0.0.1",       // bindHost（D-M2 默认回环绑定；空串=0.0.0.0 全网卡，生产多网卡显式声明）
                "",                // authToken（D-M2 默认空=不鉴权）
                List.of(),         // controlReachableEndpoints
                60_000L,           // migrationFaultTimeoutMs（L1 迁移故障静默超时；silentTimeout 未配置时的回退值）
                15.0,              // migrationMinTps
                4.0,               // migrationMaxLoadAverage
                "",                // migrationMaintenanceWindow（空串=禁用）
                5000L,             // migrationHeartbeatIntervalMs
                10000L,            // migrationIdleWindowMs
                10000L,            // migrationSilentTimeoutMs（默认 10s：失效识别 ≤15s；显式配置时优先于 faultTimeout）
                60_000L,           // migrationPrewarmTtlMs（预热会话 TTL；T4 消费）
                300_000L,          // resumeTicketTtlMs（续流票据有效期；T2 防重放时间窗口，默认 5min）
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
