package io.github.limuqy.mc.hassium.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

#if MC_VER < MC_1_20_2
import net.minecraftforge.common.ForgeConfigSpec;
#else
import net.neoforged.neoforge.common.ModConfigSpec;
#endif

/**
 * Hassium Forge/NeoForge ConfigSpec 定义（Fabric 仅编译期引用；运行时走 Toml）。
 * 1.20.1：ForgeConfigSpec；1.20.2+：ModConfigSpec（NeoForge 原生 / FCAP common-neoforgeapi）。
 * <p>
 * CLIENT：客户端缓存与客户端网络应用相关项。<br>
 * SERVER：存储、服务端网络、兼容与调试。<br>
 * GUI 显示名/提示走 {@code hassium.configuration.*} 语言键（见 lang/zh_cn.json、en_us.json）；
 * toml 内 comment 仍为简体中文说明。
 */
public final class HassiumConfigSpec {

#if MC_VER < MC_1_20_2
    public static final ForgeConfigSpec CLIENT_SPEC;
    public static final ForgeConfigSpec SERVER_SPEC;
#else
    public static final ModConfigSpec CLIENT_SPEC;
    public static final ModConfigSpec SERVER_SPEC;
#endif

    public static final Client CLIENT;
    public static final Server SERVER;

    static {
#if MC_VER < MC_1_20_2
        ForgeConfigSpec.Builder clientBuilder = new ForgeConfigSpec.Builder();
        ForgeConfigSpec.Builder serverBuilder = new ForgeConfigSpec.Builder();
#else
        ModConfigSpec.Builder clientBuilder = new ModConfigSpec.Builder();
        ModConfigSpec.Builder serverBuilder = new ModConfigSpec.Builder();
#endif
        CLIENT = new Client(clientBuilder);
        SERVER = new Server(serverBuilder);
        CLIENT_SPEC = clientBuilder.build();
        SERVER_SPEC = serverBuilder.build();
    }

    private HassiumConfigSpec() {
    }

    /**
     * 从 Spec 当前值构建运行时快照（服务端未加载 CLIENT 时使用 Spec 默认值）。
     */
    public static HassiumConfig toHassiumConfig() {
        return new HassiumConfig(
                new HassiumConfig.StorageConfig(
                        SERVER.storageEnabled.get(),
                        SERVER.storageMode.get(),
                        SERVER.storageZstdLevel.get()
                ),
                new HassiumConfig.ClientCacheConfig(
                        CLIENT.cacheEnabled.get(),
                        CLIENT.cacheMaxSizeMb.get(),
                        CLIENT.cacheCompressionLevel.get(),
                        CLIENT.cacheHotScoreThreshold.get(),
                        CLIENT.cacheRecencyWeight.get(),
                        CLIENT.cacheFrequencyWeight.get(),
                        CLIENT.cacheCleanupIntervalTicks.get(),
                        CLIENT.cacheTargetCacheSizeMb.get(),
                        CLIENT.cacheMinCleanupBatchSize.get(),
                        CLIENT.cacheViewDistanceExtensionEnabled.get(),
                        CLIENT.cacheMaxRenderDistance.get(),
                        CLIENT.cacheOvdUnloadDelaySecs.get(),
                        CLIENT.cacheSectionDeltaEnabled.get(),
                        CLIENT.cacheJoinBoostEnabled.get(),
                        CLIENT.cacheEntitySnapshotsEnabled.get(),
                        CLIENT.cacheLoadThreads.get(),
                        CLIENT.cacheLightCacheEnabled.get(),
                        CLIENT.cacheMaxChunksPerFrame.get(),
                        CLIENT.cacheMainThreadChunkBudgetMs.get()
                ),
                new HassiumConfig.ClientNetworkConfig(
                        CLIENT.networkEnabled.get(),
                        CLIENT.networkMetricsEnabled.get()
                ),
                new HassiumConfig.ServerNetworkConfig(
                        SERVER.networkEnabled.get(),
                        SERVER.networkCompressionLevel.get(),
                        SERVER.networkMagiclessZstd.get(),
                        SERVER.networkGlobalPacketCompression.get(),
                        SERVER.networkGlobalCompressionLevel.get(),
                        SERVER.networkGlobalCompressionThreshold.get(),
                        SERVER.networkUseContextCompression.get(),
                        SERVER.networkEnablePacketAggregation.get(),
                        SERVER.networkAggregationMinBatchSize.get(),
                        SERVER.networkAggregationMaxWaitTimeMs.get().longValue(),
                        SERVER.networkAggregationMaxSize.get(),
                        SERVER.networkEnableCompactHeader.get(),
                        Set.copyOf(SERVER.networkCompressionBlacklist.get()),
                        SERVER.networkMetricsEnabled.get(),
                        SERVER.networkMaxChunksPerTick.get(),
                        SERVER.networkServerChunkPushThreads.get(),
                        SERVER.networkDynamicThreadPoolEnabled.get(),
                        SERVER.networkMinPushThreads.get(),
                        SERVER.networkMaxPushThreads.get(),
                        SERVER.networkLightStrip.get()
                ),
                new HassiumConfig.CompatConfig(
                        SERVER.compatRequireClientMod.get(),
                        SERVER.compatAutoDowngradeOnError.get()
                ),
                new HassiumConfig.DebugConfig(
                        SERVER.debugMetadataLogging.get(),
                        SERVER.debugDispatcherLogging.get(),
                        SERVER.debugAsyncLogging.get(),
                        SERVER.debugCompressionLogging.get(),
                        SERVER.debugChunkApplyLogging.get(),
                        SERVER.debugNetworkLogging.get(),
                        SERVER.debugCacheLogging.get()
                )
        );
    }

    /**
     * 将运行时快照写回 Spec，并立即持久化到 toml。
     */
    public static void applyFrom(HassiumConfig config) {
        var cache = config.clientCache();
        var clientNet = config.clientNetwork();
        var serverNet = config.serverNetwork();
        var storage = config.storage();
        var compat = config.compat();
        var debug = config.debug();

        // ---- CLIENT: clientCache.* ----
        CLIENT.cacheEnabled.set(cache.enabled());
        CLIENT.cacheMaxSizeMb.set(cache.maxSizeMb());
        CLIENT.cacheCompressionLevel.set(cache.cacheCompressionLevel());
        CLIENT.cacheHotScoreThreshold.set(cache.hotScoreThreshold());
        CLIENT.cacheRecencyWeight.set(cache.recencyWeight());
        CLIENT.cacheFrequencyWeight.set(cache.frequencyWeight());
        CLIENT.cacheCleanupIntervalTicks.set(cache.cleanupIntervalTicks());
        CLIENT.cacheTargetCacheSizeMb.set(cache.targetCacheSizeMb());
        CLIENT.cacheMinCleanupBatchSize.set(cache.minCleanupBatchSize());
        CLIENT.cacheViewDistanceExtensionEnabled.set(cache.viewDistanceExtensionEnabled());
        CLIENT.cacheMaxRenderDistance.set(cache.maxRenderDistance());
        CLIENT.cacheOvdUnloadDelaySecs.set(cache.ovdUnloadDelaySecs());
        CLIENT.cacheSectionDeltaEnabled.set(cache.sectionDeltaEnabled());
        CLIENT.cacheJoinBoostEnabled.set(cache.joinBoostEnabled());
        CLIENT.cacheEntitySnapshotsEnabled.set(cache.entitySnapshotsEnabled());
        CLIENT.cacheLoadThreads.set(cache.loadThreads());
        CLIENT.cacheLightCacheEnabled.set(cache.lightCacheEnabled());
        CLIENT.cacheMaxChunksPerFrame.set(cache.maxChunksPerFrame());
        CLIENT.cacheMainThreadChunkBudgetMs.set(cache.mainThreadChunkBudgetMs());

        // ---- CLIENT: network.* ----
        CLIENT.networkEnabled.set(clientNet.enabled());
        CLIENT.networkMetricsEnabled.set(clientNet.metricsEnabled());

        // ---- SERVER: storage.* ----
        SERVER.storageEnabled.set(storage.enabled());
        SERVER.storageMode.set(storage.mode());
        SERVER.storageZstdLevel.set(storage.zstdLevel());

        // ---- SERVER: network.* ----
        SERVER.networkEnabled.set(serverNet.enabled());
        SERVER.networkCompressionLevel.set(serverNet.compressionLevel());
        SERVER.networkMagiclessZstd.set(serverNet.magiclessZstd());
        SERVER.networkGlobalPacketCompression.set(serverNet.globalPacketCompression());
        SERVER.networkGlobalCompressionLevel.set(serverNet.globalCompressionLevel());
        SERVER.networkGlobalCompressionThreshold.set(serverNet.globalCompressionThreshold());
        SERVER.networkUseContextCompression.set(serverNet.useContextCompression());
        SERVER.networkEnablePacketAggregation.set(serverNet.enablePacketAggregation());
        SERVER.networkAggregationMinBatchSize.set(serverNet.aggregationMinBatchSize());
        SERVER.networkAggregationMaxWaitTimeMs.set((int) serverNet.aggregationMaxWaitTimeMs());
        SERVER.networkAggregationMaxSize.set(serverNet.aggregationMaxSize());
        SERVER.networkEnableCompactHeader.set(serverNet.enableCompactHeader());
        SERVER.networkCompressionBlacklist.set(new ArrayList<>(serverNet.compressionBlacklist()));
        SERVER.networkMetricsEnabled.set(serverNet.metricsEnabled());
        SERVER.networkMaxChunksPerTick.set(serverNet.maxChunksPerTick());
        SERVER.networkServerChunkPushThreads.set(serverNet.serverChunkPushThreads());
        SERVER.networkDynamicThreadPoolEnabled.set(serverNet.dynamicThreadPoolEnabled());
        SERVER.networkMinPushThreads.set(serverNet.minPushThreads());
        SERVER.networkMaxPushThreads.set(serverNet.maxPushThreads());
        SERVER.networkLightStrip.set(serverNet.lightStrip());

        // ---- SERVER: compat.* ----
        SERVER.compatRequireClientMod.set(compat.requireClientMod());
        SERVER.compatAutoDowngradeOnError.set(compat.autoDowngradeOnError());

        // ---- SERVER: debug.* ----
        SERVER.debugMetadataLogging.set(debug.metadataLogging());
        SERVER.debugDispatcherLogging.set(debug.dispatcherLogging());
        SERVER.debugAsyncLogging.set(debug.asyncLogging());
        SERVER.debugCompressionLogging.set(debug.compressionLogging());
        SERVER.debugChunkApplyLogging.set(debug.chunkApplyLogging());
        SERVER.debugNetworkLogging.set(debug.networkLogging());
        SERVER.debugCacheLogging.set(debug.cacheLogging());

        if (CLIENT_SPEC.isLoaded()) {
            CLIENT_SPEC.save();
        }
        if (SERVER_SPEC.isLoaded()) {
            SERVER_SPEC.save();
        }
    }

    // ─────────────────────────────────────────────────────
    //  CLIENT inner class
    // ─────────────────────────────────────────────────────

    public static final class Client {
#if MC_VER < MC_1_20_2
        // ---- clientCache.* ----
        public final ForgeConfigSpec.BooleanValue cacheEnabled;
        public final ForgeConfigSpec.IntValue cacheMaxSizeMb;
        public final ForgeConfigSpec.IntValue cacheCompressionLevel;
        public final ForgeConfigSpec.DoubleValue cacheHotScoreThreshold;
        public final ForgeConfigSpec.DoubleValue cacheRecencyWeight;
        public final ForgeConfigSpec.DoubleValue cacheFrequencyWeight;
        public final ForgeConfigSpec.IntValue cacheCleanupIntervalTicks;
        public final ForgeConfigSpec.IntValue cacheTargetCacheSizeMb;
        public final ForgeConfigSpec.IntValue cacheMinCleanupBatchSize;
        // 超视渲染配置
        public final ForgeConfigSpec.BooleanValue cacheViewDistanceExtensionEnabled;
        public final ForgeConfigSpec.IntValue cacheMaxRenderDistance;
        public final ForgeConfigSpec.IntValue cacheOvdUnloadDelaySecs;
        // 分段增量 / JoinBoost / 实体快照
        public final ForgeConfigSpec.BooleanValue cacheSectionDeltaEnabled;
        public final ForgeConfigSpec.BooleanValue cacheJoinBoostEnabled;
        public final ForgeConfigSpec.BooleanValue cacheEntitySnapshotsEnabled;
        // 从原 NetworkConfig 吸收的客户端字段
        public final ForgeConfigSpec.IntValue cacheLoadThreads;
        public final ForgeConfigSpec.BooleanValue cacheLightCacheEnabled;
        public final ForgeConfigSpec.IntValue cacheMaxChunksPerFrame;
        public final ForgeConfigSpec.IntValue cacheMainThreadChunkBudgetMs;

        // ---- network.* ----
        public final ForgeConfigSpec.BooleanValue networkEnabled;
        public final ForgeConfigSpec.BooleanValue networkMetricsEnabled;

        Client(ForgeConfigSpec.Builder builder) {
#else
        // ---- clientCache.* ----
        public final ModConfigSpec.BooleanValue cacheEnabled;
        public final ModConfigSpec.IntValue cacheMaxSizeMb;
        public final ModConfigSpec.IntValue cacheCompressionLevel;
        public final ModConfigSpec.DoubleValue cacheHotScoreThreshold;
        public final ModConfigSpec.DoubleValue cacheRecencyWeight;
        public final ModConfigSpec.DoubleValue cacheFrequencyWeight;
        public final ModConfigSpec.IntValue cacheCleanupIntervalTicks;
        public final ModConfigSpec.IntValue cacheTargetCacheSizeMb;
        public final ModConfigSpec.IntValue cacheMinCleanupBatchSize;
        // 超视渲染配置
        public final ModConfigSpec.BooleanValue cacheViewDistanceExtensionEnabled;
        public final ModConfigSpec.IntValue cacheMaxRenderDistance;
        public final ModConfigSpec.IntValue cacheOvdUnloadDelaySecs;
        // 分段增量 / JoinBoost / 实体快照
        public final ModConfigSpec.BooleanValue cacheSectionDeltaEnabled;
        public final ModConfigSpec.BooleanValue cacheJoinBoostEnabled;
        public final ModConfigSpec.BooleanValue cacheEntitySnapshotsEnabled;
        // 从原 NetworkConfig 吸收的客户端字段
        public final ModConfigSpec.IntValue cacheLoadThreads;
        public final ModConfigSpec.BooleanValue cacheLightCacheEnabled;
        public final ModConfigSpec.IntValue cacheMaxChunksPerFrame;
        public final ModConfigSpec.IntValue cacheMainThreadChunkBudgetMs;

        // ---- network.* ----
        public final ModConfigSpec.BooleanValue networkEnabled;
        public final ModConfigSpec.BooleanValue networkMetricsEnabled;

        Client(ModConfigSpec.Builder builder) {
#endif
            builder.comment("客户端区块缓存配置（仅客户端生效）")
                    .translation("hassium.configuration.clientCache")
                    .push("clientCache");
            cacheEnabled = builder
                    .comment("=== 基础 ===")
                    .comment("是否启用客户端缓存（命中本地 Region 可减少重复区块传输；默认 true）")
                    .translation("hassium.configuration.clientCache.enabled")
                    .define("enabled", true);
            cacheMaxSizeMb = builder
                    .comment("缓存最大容量（MB；默认 4096 = 4GB）")
                    .translation("hassium.configuration.clientCache.maxSizeMb")
                    .defineInRange("maxSizeMb", 4096, 64, 1024 * 1024);
            cacheCompressionLevel = builder
                    .comment("客户端缓存 ZSTD 压缩等级（1–22；默认 9，越高压缩比越好但越慢）")
                    .translation("hassium.configuration.clientCache.compressionLevel")
                    .defineInRange("compressionLevel", 9, 1, 22);
            cacheHotScoreThreshold = builder
                    .comment("=== 热度清理 ===")
                    .comment("热点分数阈值：低于此值视为冷区块，清理时优先淘汰（默认 0.3）")
                    .translation("hassium.configuration.clientCache.hotScoreThreshold")
                    .defineInRange("hotScoreThreshold", 0.3, 0.0, 1.0);
            cacheRecencyWeight = builder
                    .comment("最近访问权重（与 frequencyWeight 配合计算热度；默认 0.7）")
                    .translation("hassium.configuration.clientCache.recencyWeight")
                    .defineInRange("recencyWeight", 0.7, 0.0, 1.0);
            cacheFrequencyWeight = builder
                    .comment("访问频率权重（默认 0.3）")
                    .translation("hassium.configuration.clientCache.frequencyWeight")
                    .defineInRange("frequencyWeight", 0.3, 0.0, 1.0);
            cacheCleanupIntervalTicks = builder
                    .comment("清理检查间隔（游戏刻；默认 6000 ≈ 5 分钟）")
                    .translation("hassium.configuration.clientCache.cleanupIntervalTicks")
                    .defineInRange("cleanupIntervalTicks", 6000, 20, 72000);
            cacheTargetCacheSizeMb = builder
                    .comment("目标缓存大小（MB；0 = 自动为 maxSizeMb×0.8；清理时压到此目标）")
                    .translation("hassium.configuration.clientCache.targetCacheSizeMb")
                    .defineInRange("targetCacheSizeMb", 0, 0, 1024 * 1024);
            cacheMinCleanupBatchSize = builder
                    .comment("每次最少清理区块数（默认 100）")
                    .translation("hassium.configuration.clientCache.minCleanupBatchSize")
                    .defineInRange("minCleanupBatchSize", 100, 1, 100000);
            cacheViewDistanceExtensionEnabled = builder
                    .comment("=== 超视渲染 ===")
                    .comment("是否启用超视渲染：客户端 RD > 服务端视距时，用本地缓存回填环带仅渲染。"
                            + "依赖 clientCache.enabled。与 Bobby 互斥，勿同装。默认 true")
                    .translation("hassium.configuration.clientCache.viewDistanceExtensionEnabled")
                    .define("viewDistanceExtensionEnabled", true);
            cacheMaxRenderDistance = builder
                    .comment("超视渲染 / 有效 RD 上限（Fog/内存约束；vanilla 滑块上限 32，默认 32）。"
                            + "RD>32 时需手动编辑 options.txt；Fog Mixin 据此钳制雾距")
                    .translation("hassium.configuration.clientCache.maxRenderDistance")
                    .defineInRange("maxRenderDistance", 32, 2, 64);
            cacheOvdUnloadDelaySecs = builder
                    .comment("离开超视渲染环带后延迟卸载秒数（避免快速移动反复加载/卸载；默认 5；0=同步卸载）")
                    .translation("hassium.configuration.clientCache.ovdUnloadDelaySecs")
                    .defineInRange("ovdUnloadDelaySecs", 5, 0, 60);
            cacheSectionDeltaEnabled = builder
                    .comment("=== 分段增量 ===")
                    .comment("缓存哈希不一致（MISMATCH）时是否走分段增量："
                            + "仅请求变更的分段（16×16×16）并合并本地缓存，而非整块全量。"
                            + "关闭时与未命中一样全量请求。依赖 clientCache.enabled。默认 true。"
                            + "详见 docs/disk-nbt-cache.md / docs/chunk-cache.md")
                    .translation("hassium.configuration.clientCache.sectionDeltaEnabled")
                    .define("sectionDeltaEnabled", true);
            cacheJoinBoostEnabled = builder
                    .comment("=== JoinBoost ===")
                    .comment("进服后短时（10秒）提高主线程预算加速区块加载，预算从 30ms 线性退坡到 mainThreadChunkBudgetMs。"
                            + "关闭后进服不提速，但避免高负载时的节奏波动。默认 true。")
                    .translation("hassium.configuration.clientCache.joinBoostEnabled")
                    .define("joinBoostEnabled", true);
            cacheEntitySnapshotsEnabled = builder
                    .comment("=== 实体快照 ===")
                    .comment("区块卸载时保存非玩家实体快照到独立 entities 目录。默认 false。")
                    .translation("hassium.configuration.clientCache.entitySnapshotsEnabled")
                    .define("entitySnapshotsEnabled", false);
            cacheLoadThreads = builder
                    .comment("=== 线程与渲染限流 ===")
                    .comment("客户端区块加载线程数（默认 10）")
                    .translation("hassium.configuration.clientCache.loadThreads")
                    .defineInRange("loadThreads", 10, 1, 64);
            cacheLightCacheEnabled = builder
                    .comment("是否启用光照缓存：首次加载重算后存储光照数据，缓存命中时直接应用。"
                            + "出现光照异常时可关闭。详见 docs/mod-compat.md（默认 true）")
                    .translation("hassium.configuration.clientCache.lightCacheEnabled")
                    .define("lightCacheEnabled", true);
            cacheMaxChunksPerFrame = builder
                    .comment("每帧应用缓存区块的安全硬顶（主限流为时间预算；默认 32）")
                    .translation("hassium.configuration.clientCache.maxChunksPerFrame")
                    .defineInRange("maxChunksPerFrame", 32, 1, 512);
            cacheMainThreadChunkBudgetMs = builder
                    .comment("每帧主线程应用区块的时间预算（毫秒；默认 15；进服 JoinBoost 期间可临时提高）")
                    .translation("hassium.configuration.clientCache.mainThreadChunkBudgetMs")
                    .defineInRange("mainThreadChunkBudgetMs", 15, 1, 50);
            builder.pop();

            builder.comment("客户端网络配置")
                    .translation("hassium.configuration.clientNetwork")
                    .push("network");
            networkEnabled = builder
                    .comment("是否启用客户端 Hassium 自定义通道（chunkHash / hassium:* 推送）。"
                            + "关闭后回退原版区块包。默认 true。【改后建议重连】")
                    .translation("hassium.configuration.clientNetwork.enabled")
                    .define("enabled", true);
            networkMetricsEnabled = builder
                    .comment("是否启用客户端网络指标收集（默认 false）")
                    .translation("hassium.configuration.clientNetwork.metricsEnabled")
                    .define("metricsEnabled", false);
            builder.pop();
        }
    }

    // ─────────────────────────────────────────────────────
    //  SERVER inner class（存储 + 网络 + 兼容 + 调试）
    // ─────────────────────────────────────────────────────

    public static final class Server {
#if MC_VER < MC_1_20_2
        // ---- storage.* ----
        public final ForgeConfigSpec.BooleanValue storageEnabled;
        public final ForgeConfigSpec.ConfigValue<String> storageMode;
        public final ForgeConfigSpec.IntValue storageZstdLevel;

        // ---- network.* ----
        public final ForgeConfigSpec.BooleanValue networkEnabled;
        public final ForgeConfigSpec.IntValue networkCompressionLevel;
        public final ForgeConfigSpec.BooleanValue networkMagiclessZstd;
        public final ForgeConfigSpec.BooleanValue networkGlobalPacketCompression;
        public final ForgeConfigSpec.IntValue networkGlobalCompressionLevel;
        public final ForgeConfigSpec.IntValue networkGlobalCompressionThreshold;
        public final ForgeConfigSpec.BooleanValue networkUseContextCompression;
        public final ForgeConfigSpec.BooleanValue networkEnablePacketAggregation;
        public final ForgeConfigSpec.IntValue networkAggregationMinBatchSize;
        public final ForgeConfigSpec.IntValue networkAggregationMaxWaitTimeMs;
        public final ForgeConfigSpec.IntValue networkAggregationMaxSize;
        public final ForgeConfigSpec.BooleanValue networkEnableCompactHeader;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> networkCompressionBlacklist;
        public final ForgeConfigSpec.BooleanValue networkMetricsEnabled;
        public final ForgeConfigSpec.IntValue networkMaxChunksPerTick;
        public final ForgeConfigSpec.IntValue networkServerChunkPushThreads;
        public final ForgeConfigSpec.BooleanValue networkDynamicThreadPoolEnabled;
        public final ForgeConfigSpec.IntValue networkMinPushThreads;
        public final ForgeConfigSpec.IntValue networkMaxPushThreads;
        public final ForgeConfigSpec.BooleanValue networkLightStrip;

        // ---- compat.* ----
        public final ForgeConfigSpec.BooleanValue compatRequireClientMod;
        public final ForgeConfigSpec.BooleanValue compatAutoDowngradeOnError;

        // ---- debug.* ----
        public final ForgeConfigSpec.BooleanValue debugMetadataLogging;
        public final ForgeConfigSpec.BooleanValue debugDispatcherLogging;
        public final ForgeConfigSpec.BooleanValue debugAsyncLogging;
        public final ForgeConfigSpec.BooleanValue debugCompressionLogging;
        public final ForgeConfigSpec.BooleanValue debugChunkApplyLogging;
        public final ForgeConfigSpec.BooleanValue debugNetworkLogging;
        public final ForgeConfigSpec.BooleanValue debugCacheLogging;

        Server(ForgeConfigSpec.Builder builder) {
#else
        // ---- storage.* ----
        public final ModConfigSpec.BooleanValue storageEnabled;
        public final ModConfigSpec.ConfigValue<String> storageMode;
        public final ModConfigSpec.IntValue storageZstdLevel;

        // ---- network.* ----
        public final ModConfigSpec.BooleanValue networkEnabled;
        public final ModConfigSpec.IntValue networkCompressionLevel;
        public final ModConfigSpec.BooleanValue networkMagiclessZstd;
        public final ModConfigSpec.BooleanValue networkGlobalPacketCompression;
        public final ModConfigSpec.IntValue networkGlobalCompressionLevel;
        public final ModConfigSpec.IntValue networkGlobalCompressionThreshold;
        public final ModConfigSpec.BooleanValue networkUseContextCompression;
        public final ModConfigSpec.BooleanValue networkEnablePacketAggregation;
        public final ModConfigSpec.IntValue networkAggregationMinBatchSize;
        public final ModConfigSpec.IntValue networkAggregationMaxWaitTimeMs;
        public final ModConfigSpec.IntValue networkAggregationMaxSize;
        public final ModConfigSpec.BooleanValue networkEnableCompactHeader;
        public final ModConfigSpec.ConfigValue<List<? extends String>> networkCompressionBlacklist;
        public final ModConfigSpec.BooleanValue networkMetricsEnabled;
        public final ModConfigSpec.IntValue networkMaxChunksPerTick;
        public final ModConfigSpec.IntValue networkServerChunkPushThreads;
        public final ModConfigSpec.BooleanValue networkDynamicThreadPoolEnabled;
        public final ModConfigSpec.IntValue networkMinPushThreads;
        public final ModConfigSpec.IntValue networkMaxPushThreads;
        public final ModConfigSpec.BooleanValue networkLightStrip;

        // ---- compat.* ----
        public final ModConfigSpec.BooleanValue compatRequireClientMod;
        public final ModConfigSpec.BooleanValue compatAutoDowngradeOnError;

        // ---- debug.* ----
        public final ModConfigSpec.BooleanValue debugMetadataLogging;
        public final ModConfigSpec.BooleanValue debugDispatcherLogging;
        public final ModConfigSpec.BooleanValue debugAsyncLogging;
        public final ModConfigSpec.BooleanValue debugCompressionLogging;
        public final ModConfigSpec.BooleanValue debugChunkApplyLogging;
        public final ModConfigSpec.BooleanValue debugNetworkLogging;
        public final ModConfigSpec.BooleanValue debugCacheLogging;

        Server(ModConfigSpec.Builder builder) {
#endif
            // ====== storage ======
            builder.comment("区块存储配置（改存档压缩格式；【高风险】启用前请备份世界）")
                    .translation("hassium.configuration.storage")
                    .push("storage");
            storageEnabled = builder
                    .comment("【高风险】是否启用 Hassium 存档压缩（type 126 / ZSTD+字典）。"
                            + "默认 true。会改变区块载荷格式，启用前务必备份世界。")
                    .translation("hassium.configuration.storage.enabled")
                    .define("enabled", true);
            storageMode = builder
                    .comment("存储模式：mirror=镜像原版, readonly_vanilla=只读原版, hassium_only=仅 Hassium（默认 mirror）")
                    .translation("hassium.configuration.storage.mode")
                    .define("mode", "mirror");
            storageZstdLevel = builder
                    .comment("存储 ZSTD 压缩等级（1–22；默认 9，越高压缩比越好但越慢）")
                    .translation("hassium.configuration.storage.zstdLevel")
                    .defineInRange("zstdLevel", 9, 1, 22);
            builder.pop();

            // ====== network ======
            builder.comment("网络与区块推送配置（服务端共享项 + 服务端专属推送）")
                    .translation("hassium.configuration.network")
                    .push("network");
            networkEnabled = builder
                    .comment("=== 基础 ===")
                    .comment("是否启用 Hassium 自定义通道（chunkHash / hassium:* 推送）。"
                            + "关闭后回退原版区块包。默认 true。【改后建议重连/重启】")
                    .translation("hassium.configuration.network.enabled")
                    .define("enabled", true);
            networkCompressionLevel = builder
                    .comment("自定义通道 ZSTD 压缩等级（默认 3，速度优先；算法固定为 hassium:zstd）")
                    .translation("hassium.configuration.network.compressionLevel")
                    .defineInRange("compressionLevel", 3, 1, 22);
            networkMagiclessZstd = builder
                    .comment("是否使用无 magic 的 ZSTD 帧格式（默认 true）")
                    .translation("hassium.configuration.network.magiclessZstd")
                    .define("magiclessZstd", true);
            networkGlobalPacketCompression = builder
                    .comment("=== 全局包压缩（替换原版 Zlib）[实验性] ===")
                    .comment("【高风险/实验性】是否用 ZSTD 替换原版 Zlib 全局包压缩（影响几乎所有数据包）。"
                            + "与同类压缩/Via 同进程叠用可能冲突。默认 true。【改后建议重启】")
                    .translation("hassium.configuration.network.globalPacketCompression")
                    .define("globalPacketCompression", true);
            networkGlobalCompressionLevel = builder
                    .comment("全局包压缩等级 [实验性]（默认 3）")
                    .translation("hassium.configuration.network.globalCompressionLevel")
                    .defineInRange("globalCompressionLevel", 3, 1, 22);
            networkGlobalCompressionThreshold = builder
                    .comment("全局压缩阈值（字节；小于此值不压；默认 256，与原版一致）[实验性]")
                    .translation("hassium.configuration.network.globalCompressionThreshold")
                    .defineInRange("globalCompressionThreshold", 256, 0, 65536);
            networkUseContextCompression = builder
                    .comment("=== 上下文压缩 ===")
                    .comment("是否使用上下文压缩（默认 true）")
                    .translation("hassium.configuration.network.useContextCompression")
                    .define("useContextCompression", true);
            networkEnablePacketAggregation = builder
                    .comment("=== 包聚合 [实验性] ===")
                    .comment("【高风险/兼容逃生】是否启用包聚合。与第三方自定义通道冲突时可关闭。"
                            + "详见 docs/mod-compat.md（默认 true）")
                    .translation("hassium.configuration.network.enablePacketAggregation")
                    .define("enablePacketAggregation", true);
            networkAggregationMinBatchSize = builder
                    .comment("聚合最小批量大小 [实验性]（默认 4）")
                    .translation("hassium.configuration.network.aggregationMinBatchSize")
                    .defineInRange("aggregationMinBatchSize", 4, 1, 256);
            networkAggregationMaxWaitTimeMs = builder
                    .comment("聚合最大等待时间（毫秒；默认 20）[实验性]")
                    .translation("hassium.configuration.network.aggregationMaxWaitTimeMs")
                    .defineInRange("aggregationMaxWaitTimeMs", 20, 1, 5000);
            networkAggregationMaxSize = builder
                    .comment("聚合最大大小（字节；默认 262144 = 256KB）[实验性]")
                    .translation("hassium.configuration.network.aggregationMaxSize")
                    .defineInRange("aggregationMaxSize", 256 * 1024, 1024, 8 * 1024 * 1024);
            networkEnableCompactHeader = builder
                    .comment("是否启用紧凑包头（主要用于聚合包内部；默认 true）[实验性]")
                    .translation("hassium.configuration.network.enableCompactHeader")
                    .define("enableCompactHeader", true);
            networkCompressionBlacklist = builder
                    .comment("压缩/聚合黑名单：包 ID 或命名空间前缀字符串列表。"
                            + "示例：distant_horizons:xxx、某伴生 mod 的 namespace:path。"
                            + "第三方通道被聚合拖慢时可加入。详见 docs/mod-compat.md")
                    .translation("hassium.configuration.network.compressionBlacklist")
                    .defineList("compressionBlacklist",
                            () -> new ArrayList<>(HassiumConfig.ServerNetworkConfig.DEFAULT_COMPRESSION_BLACKLIST),
                            o -> o instanceof String);
            networkMetricsEnabled = builder
                    .comment("=== 指标 ===")
                    .comment("是否启用网络指标收集（流量、缓存命中等；默认 false）")
                    .translation("hassium.configuration.network.metricsEnabled")
                    .define("metricsEnabled", false);
            networkMaxChunksPerTick = builder
                    .comment("=== 服务端推送 ===")
                    .comment("每玩家每 server tick 最多序列化/推送区块数（默认 32；仅服务端）")
                    .translation("hassium.configuration.network.maxChunksPerTick")
                    .defineInRange("maxChunksPerTick", 32, 1, 256);
            networkServerChunkPushThreads = builder
                    .comment("服务端区块推送线程数（动态池关闭时的基准；默认 8；仅服务端）")
                    .translation("hassium.configuration.network.serverChunkPushThreads")
                    .defineInRange("serverChunkPushThreads", 8, 1, 64);
            networkDynamicThreadPoolEnabled = builder
                    .comment("=== 动态线程池 ===")
                    .comment("是否按队列负载动态调整推送线程数（默认 true；仅服务端）")
                    .translation("hassium.configuration.network.dynamicThreadPoolEnabled")
                    .define("dynamicThreadPoolEnabled", true);
            networkMinPushThreads = builder
                    .comment("动态线程池最小推送线程数（默认 2；仅服务端）")
                    .translation("hassium.configuration.network.minPushThreads")
                    .defineInRange("minPushThreads", 2, 1, 64);
            networkMaxPushThreads = builder
                    .comment("动态线程池最大推送线程数（默认 8；仅服务端）")
                    .translation("hassium.configuration.network.maxPushThreads")
                    .defineInRange("maxPushThreads", 8, 1, 64);
            networkLightStrip = builder
                    .comment("光照剥离：服务端控制是否发包时剥离 LightData（默认 true）")
                    .translation("hassium.configuration.network.lightStrip")
                    .define("lightStrip", true);
            builder.pop();

            // ====== compat ======
            builder.comment("兼容性配置")
                    .translation("hassium.configuration.compat")
                    .push("compat");
            compatRequireClientMod = builder
                    .comment("是否强制要求客户端安装 Hassium。"
                            + "false（默认）时无模组客户端仍可进服并走原版包。详见 docs/mod-compat.md")
                    .translation("hassium.configuration.compat.requireClientMod")
                    .define("requireClientMod", false);
            compatAutoDowngradeOnError = builder
                    .comment("出错时是否自动降级到原版行为（默认 true）")
                    .translation("hassium.configuration.compat.autoDowngradeOnError")
                    .define("autoDowngradeOnError", true);
            builder.pop();

            // ====== debug ======
            builder.comment("调试日志（【仅排障】开启后热路径会大量刷屏，生产环境请保持 false）")
                    .translation("hassium.configuration.debug")
                    .push("debug");
            debugMetadataLogging = builder
                    .comment("元数据日志：chunkHash 接收/比对/应用（默认 false）")
                    .translation("hassium.configuration.debug.metadataLogging")
                    .define("metadataLogging", false);
            debugDispatcherLogging = builder
                    .comment("主线程调度器日志：回调队列（默认 false）")
                    .translation("hassium.configuration.debug.dispatcherLogging")
                    .define("dispatcherLogging", false);
            debugAsyncLogging = builder
                    .comment("异步任务日志（默认 false）")
                    .translation("hassium.configuration.debug.asyncLogging")
                    .define("asyncLogging", false);
            debugCompressionLogging = builder
                    .comment("压缩/解压日志（默认 false）")
                    .translation("hassium.configuration.debug.compressionLogging")
                    .define("compressionLogging", false);
            debugChunkApplyLogging = builder
                    .comment("区块 apply 到世界日志（默认 false）")
                    .translation("hassium.configuration.debug.chunkApplyLogging")
                    .define("chunkApplyLogging", false);
            debugNetworkLogging = builder
                    .comment("网络收发日志（默认 false）")
                    .translation("hassium.configuration.debug.networkLogging")
                    .define("networkLogging", false);
            debugCacheLogging = builder
                    .comment("缓存读写日志（默认 false）")
                    .translation("hassium.configuration.debug.cacheLogging")
                    .define("cacheLogging", false);
            builder.pop();
        }
    }
}
