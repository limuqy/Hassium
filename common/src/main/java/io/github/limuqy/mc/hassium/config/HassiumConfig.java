package io.github.limuqy.mc.hassium.config;

import io.github.limuqy.mc.hassium.network.HassiumPacketIds;

import java.util.List;
import java.util.Set;
/**
 * Hassium 配置（运行时快照）。
 * <p>
 * 物理客户端从 client.toml 加载：ChunkCoreConfig + DebugConfig。
 * 专用服从 server.toml 加载：StorageConfig + MasterCoreConfig + CompatConfig + DebugConfig。
 */
public record HassiumConfig(
        StorageConfig storage,
        ChunkCoreConfig chunk,
        MasterCoreConfig master,
        CompatConfig compat,
        DebugConfig debug
) {
    public static final HassiumConfig DEFAULT = new HassiumConfig(
            StorageConfig.DEFAULT,
            ChunkCoreConfig.DEFAULT,
            MasterCoreConfig.DEFAULT,
            CompatConfig.DEFAULT,
            DebugConfig.DEFAULT
    );

    public HassiumConfig withDebug(DebugConfig debug) {
        return new HassiumConfig(storage, chunk, master, compat, debug);
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
     * 区块核心配置（双端；client.toml chunk.* CLIENT 18 键 + 服务端 chunk.lightStrip/chunk.seedGenEnabled）。
     * <p>
     * 吸收原 ClientCacheConfig 全族与 network.seedGen.enabled（双端同名键，按物理端加载）。
     * Bloom filter 参数硬编码（enabled=true, insertions=10000, fpp=0.01）。
     * maxAgeDays 已删除（热度评分隐式覆盖）。
     */
    public record ChunkCoreConfig(
            boolean enabled,
            int maxSizeMb,
            // === 热度清理（影子端容量/热度淘汰：heat.idx 按 region 文件 + 整文件删除）===
            double hotScoreThreshold,
            double recencyWeight,
            double frequencyWeight,
            int cleanupIntervalTicks,
            int targetSizeMb,
            int minCleanupBatchSize,
            // === 分段增量（服务端规划 + 客户端应用，MixinConnection/sectiondelta 链路活跃消费）===
            boolean sectionDeltaEnabled,
            // === 超视渲染 OVD（影子双窗；无延迟卸载）===
            boolean viewDistanceExtensionEnabled,
            int maxRenderDistance,
            boolean ovdLocalGeneration,
            // === 线程与应用（从原 NetworkConfig 吸收的客户端字段）===
            int maxChunksPerFrame,
            int mainThreadChunkBudgetMs,
            // === SeedGen 本地生成线程数（Phase 2；0=禁用本地生成）===
            int seedGenThreads,
            // === SeedGen 总开关（双端同名键；物理端各自加载）===
            boolean seedGenEnabled,
            // === 光照剥离（仅专用服；服务端控制是否发包时剥离 LightData）===
            boolean lightStrip
    ) {
        public static final ChunkCoreConfig DEFAULT = new ChunkCoreConfig(
                true,    // enabled
                4096,    // maxSizeMb
                0.3,     // hotScoreThreshold
                0.7,     // recencyWeight
                0.3,     // frequencyWeight
                6000,    // cleanupIntervalTicks
                0,       // targetSizeMb (auto)
                100,     // minCleanupBatchSize
                true,    // sectionDeltaEnabled
                true,    // viewDistanceExtensionEnabled
                16,      // maxRenderDistance
                false,   // ovdLocalGeneration
                6,       // maxChunksPerFrame
                15,      // mainThreadChunkBudgetMs
                2,       // seedGenThreads
                false,   // seedGenEnabled
                true     // lightStrip（仅服务端消费）
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
     * 主控核心配置（专用服；server.toml master.*）。
     * <p>
     * 服务端网络行为（压缩/聚合/推送）。原网关监听/鉴权/控制面端点/L1 迁移/续流票据/
     * 数据面（dataplane.*）键族已随 2.0.0 网关拓扑退役删除。
     */
    public record MasterCoreConfig(
            boolean enabled,
            int compressionLevel,
            // === 上下文压缩 ===
            boolean useContextCompression,
            // === 包聚合（应用层，MixinConnection 拦截）===
            boolean enablePacketAggregation,
            int aggregationMinBatchSize,
            long aggregationMaxWaitTimeMs,
            int aggregationMaxSize,
            // === 黑名单 ===
            Set<String> compressionBlacklist,
            // === 服务端推送 ===
            int maxChunksPerTick,
            int serverChunkPushThreads
    ) {
        public MasterCoreConfig {
            compressionBlacklist = Set.copyOf(compressionBlacklist);
        }

        // 127.0.0.1 仅供本地开发；公网部署必须配置客户端实际可达的地址。
        public static final Set<String> DEFAULT_COMPRESSION_BLACKLIST = Set.of(
                HassiumPacketIds.HANDSHAKE_S2C,
                HassiumPacketIds.DICTIONARY_SYNC_S2C,
                HassiumPacketIds.INDEX_SYNC_S2C,
                HassiumPacketIds.LIGHT_DELTA_S2C,
                HassiumPacketIds.BLOCK_ENTITY_DATA_S2C,
                HassiumPacketIds.MAIN_CHANNEL,
                HassiumPacketIds.AGGREGATION_S2C
        );

        public static final MasterCoreConfig DEFAULT = new MasterCoreConfig(
                true,              // enabled（服务端网络通道总开关；直连拓扑下登录期握手/聚合均以此为门）
                3,                 // compressionLevel
                true,              // useContextCompression
                true,              // enablePacketAggregation
                4,                 // aggregationMinBatchSize
                50,                // aggregationMaxWaitTimeMs
                256 * 1024,        // aggregationMaxSize
                DEFAULT_COMPRESSION_BLACKLIST,
                5,                 // maxChunksPerTick（Pull FULL/DELTA 完成配额，满 tick ≈ 100/s）
                4                  // serverChunkPushThreads
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
            boolean lightVerify,
            boolean networkMetricsEnabled,
            boolean networkMetricsAutoReset
    ) {
        public static final DebugConfig DEFAULT = new DebugConfig(
                false, false, false, false, false, false, false, false, false, true
        );
    }
}
