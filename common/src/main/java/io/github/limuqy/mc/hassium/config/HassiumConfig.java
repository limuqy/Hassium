package io.github.limuqy.mc.hassium.config;

import io.github.limuqy.mc.hassium.server.entity.EntityUpdateTiering;

import java.util.List;
import java.util.Set;
/**
 * Hassium 配置（运行时快照）。
 * <p>
 * 物理客户端：client.toml（CLIENT）+ server.toml（SERVER）双文件合并——
 * 客户端行为 + 集成服务器/局域网用的服务端侧配置。
 * 专用服：server.toml（SERVER）。
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
     * 区块核心配置（双端；client.toml chunk.* CLIENT 15 键 + 服务端 chunk.lightStrip/chunk.seedGenEnabled）。
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
            // === 光照光环（计算/拉取域 = serverVD + 此值；只算不交付；0=关）===
            int lightHaloRadius,
            // === 线程与应用（从原 NetworkConfig 吸收的客户端字段）===
            int maxChunksPerFrame,
            int mainThreadChunkBudgetMs,
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
                io.github.limuqy.mc.hassium.protocol.ShadowPullRadii.LIGHT_HALO_RADIUS, // lightHaloRadius
                6,       // maxChunksPerFrame
                15,      // mainThreadChunkBudgetMs
                false,   // seedGenEnabled
                true     // lightStrip（仅服务端消费；核心省带宽，默认开）
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
     * 主控核心配置（server.toml master.*）。
     * <p>
     * 服务端网络行为（压缩/聚合/推送）。{@code enabled} 驱动专用服；
     * {@code enabledOnLan} 仅在集成服已开局域网时对远程玩家生效（本机 memory 恒原版）。
     * <p>
     * {@code entity*} 一组为实体网络优化配置面：分层更新（按观察者距离四挡降频，刻间隔递增）
     * 与实体密度节流（单玩家可见实体数超阈值后叠加降频、受最大倍率约束）可独立开关，
     * {@code entityFrameBudgetPerPlayer} 为每玩家每 tick 的实体更新帧预算（0 = 不限）；
     * 物品流（掉落物/经验球）不复用 {@code entityTierInterval*}，另取 {@code entityItemTierInterval*}
     * 一张表（距离分挡共用），以免被这两类实体原版 20 刻的空闲节拍压平。
     */
    public record MasterCoreConfig(
            boolean enabled,
            boolean enabledOnLan,
            int compressionLevel,
            // === 包聚合（应用层，MixinConnection 拦截）===
            boolean enablePacketAggregation,
            /** 冲刷兜底：超过该时长未冲刷则强制冲一次（tick 尾冲刷为主，本值仅应对主线程卡顿）。 */
            long aggregationMaxWaitTimeMs,
            int aggregationMaxSize,
            // === 黑名单 ===
            Set<String> compressionBlacklist,
            // === 服务端推送 ===
            int maxChunksPerTick,
            // === 实体网络优化（master.entity*）===
            boolean entityTieredUpdateEnabled,
            String entityTierIntervals,
            String entityItemTierIntervals,
            boolean entityDensityThrottleEnabled,
            // 热点分档：实体所在 chunk 的活跃实体数 ≥ 该档阈值 ⇒ 该档实体间隔 × 该档倍率（支持小数）
            String entityDensityTierCounts,
            String entityDensityTierFactors,
            int entityMaxThrottleFactor,
            int entityFrameBudgetPerPlayer,
            boolean entitySmoothPushEnabled
    ) {
        public MasterCoreConfig {
            compressionBlacklist = Set.copyOf(compressionBlacklist);
        }

        /**
         * 用户可配置的压缩/聚合黑名单默认值。
         * <p>
         * 默认为空：Hassium 控制面 / 独立压缩通道（main、dictionary_sync、index_sync、
         * light_delta、aggregation、play_init、shadow_pull 等）由
         * {@code PacketCompressionBlacklist.HARDCODED_BLACKLIST} 永久排除，与本列表无关。
         * 本列表仅用于第三方包 ID 的逃生排除。
         */
        public static final Set<String> DEFAULT_COMPRESSION_BLACKLIST = Set.of();

        public static final MasterCoreConfig DEFAULT = new MasterCoreConfig(
                true,              // enabled（专用服网络通道总开关）
                false,             // enabledOnLan（集成服开局域网后对远程玩家的网络面，默认关）
                3,                 // compressionLevel
                !io.github.limuqy.mc.hassium.compat.mods.ModCompatFlags.bandwidthOptimizer(),
                                   // enablePacketAggregation（检测到 BandwidthOptimizer 时默认关；显式 true 恒生效）
                50,                // aggregationMaxWaitTimeMs（冲刷兜底）
                256 * 1024,        // aggregationMaxSize
                DEFAULT_COMPRESSION_BLACKLIST,
                5,                 // maxChunksPerTick（Pull FULL/DELTA + 原版整柱，满 tick ≈ 100/s）
                true,              // entityTieredUpdateEnabled（实体分层更新总开关）
                EntityUpdateTiering.DEFAULT_ENTITY_INTERVALS,     // entityTierIntervals（近/中/远/边缘）
                EntityUpdateTiering.DEFAULT_ITEM_INTERVALS,       // entityItemTierIntervals（物品流独立表）
                true,              // entityDensityThrottleEnabled（实体密度节流总开关）
                EntityUpdateTiering.DEFAULT_DENSITY_COUNTS,       // entityDensityTierCounts（每档热点阈值）
                EntityUpdateTiering.DEFAULT_DENSITY_FACTORS,      // entityDensityTierFactors（每档热点倍率，支持小数）
                5,                 // entityMaxThrottleFactor（密度×压力倍率总上限）
                256,               // entityFrameBudgetPerPlayer（每玩家每 tick 实体包预算；0=不限）
                true               // entitySmoothPushEnabled（UUID 相位错峰，总量不变）
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
            boolean lightLogging,
            boolean networkMetricsEnabled,
            boolean networkMetricsAutoReset,
            boolean exportAggregatedPackets
    ) {
        public static final DebugConfig DEFAULT = new DebugConfig(
                false, false, false, false, false, false, false, false, false, false, true, false
        );
    }
}
