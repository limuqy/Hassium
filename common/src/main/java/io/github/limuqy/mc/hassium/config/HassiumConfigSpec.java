package io.github.limuqy.mc.hassium.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

#if MC_VER < MC_1_20_5
import net.minecraftforge.common.ForgeConfigSpec;
#else
import net.neoforged.neoforge.common.ModConfigSpec;
#endif

/**
 * Hassium Forge/NeoForge ConfigSpec 定义（Fabric 经 Forge Config API Port）。
 * <p>
 * CLIENT：客户端缓存与客户端网络应用相关项。<br>
 * COMMON：存储、共享网络、兼容与调试。<br>
 * GUI 显示名/提示走 {@code hassium.configuration.*} 语言键（见 lang/zh_cn.json、en_us.json）；
 * toml 内 comment 仍为简体中文说明。
 */
public final class HassiumConfigSpec {

#if MC_VER < MC_1_20_5
    public static final ForgeConfigSpec CLIENT_SPEC;
    public static final ForgeConfigSpec COMMON_SPEC;
#else
    public static final ModConfigSpec CLIENT_SPEC;
    public static final ModConfigSpec COMMON_SPEC;
#endif

    public static final Client CLIENT;
    public static final Common COMMON;

    static {
#if MC_VER < MC_1_20_5
        ForgeConfigSpec.Builder clientBuilder = new ForgeConfigSpec.Builder();
        ForgeConfigSpec.Builder commonBuilder = new ForgeConfigSpec.Builder();
#else
        ModConfigSpec.Builder clientBuilder = new ModConfigSpec.Builder();
        ModConfigSpec.Builder commonBuilder = new ModConfigSpec.Builder();
#endif
        CLIENT = new Client(clientBuilder);
        COMMON = new Common(commonBuilder);
        CLIENT_SPEC = clientBuilder.build();
        COMMON_SPEC = commonBuilder.build();
    }

    private HassiumConfigSpec() {
    }

    /**
     * 从 Spec 当前值构建运行时快照（服务端未加载 CLIENT 时使用 Spec 默认值）。
     */
    public static HassiumConfig toHassiumConfig() {
        return new HassiumConfig(
                new HassiumConfig.StorageConfig(
                        COMMON.storageEnabled.get(),
                        COMMON.storageMode.get(),
                        COMMON.storageZstdLevel.get()
                ),
                new HassiumConfig.ClientCacheConfig(
                        CLIENT.cacheEnabled.get(),
                        CLIENT.cacheMaxSizeMb.get(),
                        CLIENT.cacheMaxAgeDays.get(),
                        CLIENT.cacheHotScoreThreshold.get(),
                        CLIENT.cacheRecencyWeight.get(),
                        CLIENT.cacheFrequencyWeight.get(),
                        CLIENT.cacheCleanupIntervalTicks.get(),
                        CLIENT.cacheTargetCacheSizeMb.get(),
                        CLIENT.cacheMinCleanupBatchSize.get(),
                        CLIENT.cacheBloomFilterEnabled.get(),
                        CLIENT.cacheBloomFilterExpectedInsertions.get(),
                        CLIENT.cacheBloomFilterFpp.get(),
                        CLIENT.cacheViewDistanceExtensionEnabled.get(),
                        CLIENT.cacheMaxRenderDistance.get(),
                        CLIENT.cacheOvdUnloadDelaySecs.get(),
                        CLIENT.cacheSectionDeltaEnabled.get()
                ),
                new HassiumConfig.NetworkConfig(
                        COMMON.networkEnabled.get(),
                        COMMON.networkCompressionLevel.get(),
                        COMMON.networkMaxChunksPerTick.get(),
                        COMMON.networkGlobalPacketCompression.get(),
                        COMMON.networkGlobalCompressionLevel.get(),
                        COMMON.networkGlobalCompressionThreshold.get(),
                        Set.copyOf(COMMON.networkCompressionBlacklist.get()),
                        COMMON.networkUseContextCompression.get(),
                        COMMON.networkMagiclessZstd.get(),
                        COMMON.networkEnablePacketAggregation.get(),
                        COMMON.networkAggregationMinBatchSize.get(),
                        COMMON.networkAggregationMaxWaitTimeMs.get().longValue(),
                        COMMON.networkAggregationMaxSize.get(),
                        COMMON.networkEnableCompactHeader.get(),
                        COMMON.networkServerChunkPushThreads.get(),
                        CLIENT.networkClientChunkLoadThreads.get(),
                        CLIENT.networkLightStripEnabled.get(),
                        CLIENT.networkBackgroundThreads.get(),
                        CLIENT.networkMaxChunksPerFrame.get(),
                        CLIENT.networkMaxCallbacksPerFrame.get(),
                        COMMON.networkMetricsEnabled.get(),
                        CLIENT.networkMainThreadChunkBudgetMs.get(),
                        CLIENT.networkMaxLightRecomputePerFrame.get(),
                        COMMON.networkDynamicThreadPoolEnabled.get(),
                        COMMON.networkMinPushThreads.get(),
                        COMMON.networkMaxPushThreads.get()
                ),
                new HassiumConfig.CompatConfig(
                        COMMON.compatRequireClientMod.get(),
                        COMMON.compatAutoDowngradeOnError.get()
                ),
                new HassiumConfig.DebugConfig(
                        COMMON.debugMetadataLogging.get(),
                        COMMON.debugDispatcherLogging.get(),
                        COMMON.debugAsyncLogging.get(),
                        COMMON.debugCompressionLogging.get(),
                        COMMON.debugChunkApplyLogging.get(),
                        COMMON.debugNetworkLogging.get(),
                        COMMON.debugCacheLogging.get()
                )
        );
    }

    /**
     * 将运行时快照写回 Spec，并立即持久化到 toml。
     */
    public static void applyFrom(HassiumConfig config) {
        var cache = config.clientCache();
        var net = config.network();
        var storage = config.storage();
        var compat = config.compat();
        var debug = config.debug();

        CLIENT.cacheEnabled.set(cache.enabled());
        CLIENT.cacheMaxSizeMb.set(cache.maxSizeMb());
        CLIENT.cacheMaxAgeDays.set(cache.maxAgeDays());
        CLIENT.cacheHotScoreThreshold.set(cache.hotScoreThreshold());
        CLIENT.cacheRecencyWeight.set(cache.recencyWeight());
        CLIENT.cacheFrequencyWeight.set(cache.frequencyWeight());
        CLIENT.cacheCleanupIntervalTicks.set(cache.cleanupIntervalTicks());
        CLIENT.cacheTargetCacheSizeMb.set(cache.targetCacheSizeMb());
        CLIENT.cacheMinCleanupBatchSize.set(cache.minCleanupBatchSize());
        CLIENT.cacheBloomFilterEnabled.set(cache.bloomFilterEnabled());
        CLIENT.cacheBloomFilterExpectedInsertions.set(cache.bloomFilterExpectedInsertions());
        CLIENT.cacheBloomFilterFpp.set(cache.bloomFilterFpp());
        CLIENT.cacheViewDistanceExtensionEnabled.set(cache.viewDistanceExtensionEnabled());
        CLIENT.cacheMaxRenderDistance.set(cache.maxRenderDistance());
        CLIENT.cacheOvdUnloadDelaySecs.set(cache.ovdUnloadDelaySecs());
        CLIENT.cacheSectionDeltaEnabled.set(cache.sectionDeltaEnabled());
        CLIENT.networkClientChunkLoadThreads.set(net.clientChunkLoadThreads());
        CLIENT.networkLightStripEnabled.set(net.lightStripEnabled());
        CLIENT.networkBackgroundThreads.set(net.backgroundThreads());
        CLIENT.networkMaxChunksPerFrame.set(net.maxChunksPerFrame());
        CLIENT.networkMaxCallbacksPerFrame.set(net.maxCallbacksPerFrame());
        CLIENT.networkMainThreadChunkBudgetMs.set(net.mainThreadChunkBudgetMs());
        CLIENT.networkMaxLightRecomputePerFrame.set(net.maxLightRecomputePerFrame());

        COMMON.storageEnabled.set(storage.enabled());
        COMMON.storageMode.set(storage.mode());
        COMMON.storageZstdLevel.set(storage.zstdLevel());
        COMMON.networkEnabled.set(net.enabled());
        COMMON.networkCompressionLevel.set(net.compressionLevel());
        COMMON.networkMaxChunksPerTick.set(net.maxChunksPerTick());
        COMMON.networkGlobalPacketCompression.set(net.globalPacketCompression());
        COMMON.networkGlobalCompressionLevel.set(net.globalCompressionLevel());
        COMMON.networkGlobalCompressionThreshold.set(net.globalCompressionThreshold());
        COMMON.networkCompressionBlacklist.set(new ArrayList<>(net.compressionBlacklist()));
        COMMON.networkUseContextCompression.set(net.useContextCompression());
        COMMON.networkMagiclessZstd.set(net.magiclessZstd());
        COMMON.networkEnablePacketAggregation.set(net.enablePacketAggregation());
        COMMON.networkAggregationMinBatchSize.set(net.aggregationMinBatchSize());
        COMMON.networkAggregationMaxWaitTimeMs.set((int) net.aggregationMaxWaitTimeMs());
        COMMON.networkAggregationMaxSize.set(net.aggregationMaxSize());
        COMMON.networkEnableCompactHeader.set(net.enableCompactHeader());
        COMMON.networkServerChunkPushThreads.set(net.serverChunkPushThreads());
        COMMON.networkMetricsEnabled.set(net.metricsEnabled());
        COMMON.networkDynamicThreadPoolEnabled.set(net.dynamicThreadPoolEnabled());
        COMMON.networkMinPushThreads.set(net.minPushThreads());
        COMMON.networkMaxPushThreads.set(net.maxPushThreads());
        COMMON.compatRequireClientMod.set(compat.requireClientMod());
        COMMON.compatAutoDowngradeOnError.set(compat.autoDowngradeOnError());
        COMMON.debugMetadataLogging.set(debug.metadataLogging());
        COMMON.debugDispatcherLogging.set(debug.dispatcherLogging());
        COMMON.debugAsyncLogging.set(debug.asyncLogging());
        COMMON.debugCompressionLogging.set(debug.compressionLogging());
        COMMON.debugChunkApplyLogging.set(debug.chunkApplyLogging());
        COMMON.debugNetworkLogging.set(debug.networkLogging());
        COMMON.debugCacheLogging.set(debug.cacheLogging());

        if (CLIENT_SPEC.isLoaded()) {
            CLIENT_SPEC.save();
        }
        if (COMMON_SPEC.isLoaded()) {
            COMMON_SPEC.save();
        }
    }

    public static final class Client {
#if MC_VER < MC_1_20_5
        public final ForgeConfigSpec.BooleanValue cacheEnabled;
        public final ForgeConfigSpec.IntValue cacheMaxSizeMb;
        public final ForgeConfigSpec.IntValue cacheMaxAgeDays;
        public final ForgeConfigSpec.DoubleValue cacheHotScoreThreshold;
        public final ForgeConfigSpec.DoubleValue cacheRecencyWeight;
        public final ForgeConfigSpec.DoubleValue cacheFrequencyWeight;
        public final ForgeConfigSpec.IntValue cacheCleanupIntervalTicks;
        public final ForgeConfigSpec.IntValue cacheTargetCacheSizeMb;
        public final ForgeConfigSpec.IntValue cacheMinCleanupBatchSize;
        public final ForgeConfigSpec.BooleanValue cacheBloomFilterEnabled;
        public final ForgeConfigSpec.IntValue cacheBloomFilterExpectedInsertions;
        public final ForgeConfigSpec.DoubleValue cacheBloomFilterFpp;

        // 超视渲染配置
        public final ForgeConfigSpec.BooleanValue cacheViewDistanceExtensionEnabled;
        public final ForgeConfigSpec.IntValue cacheMaxRenderDistance;
        public final ForgeConfigSpec.IntValue cacheOvdUnloadDelaySecs;
        public final ForgeConfigSpec.BooleanValue cacheSectionDeltaEnabled;

        public final ForgeConfigSpec.IntValue networkClientChunkLoadThreads;
        public final ForgeConfigSpec.BooleanValue networkLightStripEnabled;
        public final ForgeConfigSpec.IntValue networkBackgroundThreads;
        public final ForgeConfigSpec.IntValue networkMaxChunksPerFrame;
        public final ForgeConfigSpec.IntValue networkMaxCallbacksPerFrame;
        public final ForgeConfigSpec.IntValue networkMainThreadChunkBudgetMs;
        public final ForgeConfigSpec.IntValue networkMaxLightRecomputePerFrame;

        Client(ForgeConfigSpec.Builder builder) {
#else
        public final ModConfigSpec.BooleanValue cacheEnabled;
        public final ModConfigSpec.IntValue cacheMaxSizeMb;
        public final ModConfigSpec.IntValue cacheMaxAgeDays;
        public final ModConfigSpec.DoubleValue cacheHotScoreThreshold;
        public final ModConfigSpec.DoubleValue cacheRecencyWeight;
        public final ModConfigSpec.DoubleValue cacheFrequencyWeight;
        public final ModConfigSpec.IntValue cacheCleanupIntervalTicks;
        public final ModConfigSpec.IntValue cacheTargetCacheSizeMb;
        public final ModConfigSpec.IntValue cacheMinCleanupBatchSize;
        public final ModConfigSpec.BooleanValue cacheBloomFilterEnabled;
        public final ModConfigSpec.IntValue cacheBloomFilterExpectedInsertions;
        public final ModConfigSpec.DoubleValue cacheBloomFilterFpp;

        // 超视渲染配置
        public final ModConfigSpec.BooleanValue cacheViewDistanceExtensionEnabled;
        public final ModConfigSpec.IntValue cacheMaxRenderDistance;
        public final ModConfigSpec.IntValue cacheOvdUnloadDelaySecs;
        public final ModConfigSpec.BooleanValue cacheSectionDeltaEnabled;

        public final ModConfigSpec.IntValue networkClientChunkLoadThreads;
        public final ModConfigSpec.BooleanValue networkLightStripEnabled;
        public final ModConfigSpec.IntValue networkBackgroundThreads;
        public final ModConfigSpec.IntValue networkMaxChunksPerFrame;
        public final ModConfigSpec.IntValue networkMaxCallbacksPerFrame;
        public final ModConfigSpec.IntValue networkMainThreadChunkBudgetMs;
        public final ModConfigSpec.IntValue networkMaxLightRecomputePerFrame;

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
                    .comment("缓存最大容量（MB；默认 2048 = 2GB）")
                    .translation("hassium.configuration.clientCache.maxSizeMb")
                    .defineInRange("maxSizeMb", 2048, 64, 1024 * 1024);
            cacheMaxAgeDays = builder
                    .comment("缓存过期天数（超过未访问可被清理；默认 30）")
                    .translation("hassium.configuration.clientCache.maxAgeDays")
                    .defineInRange("maxAgeDays", 30, 1, 3650);
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
            cacheBloomFilterEnabled = builder
                    .comment("=== Bloom Filter 预筛 ===")
                    .comment("是否启用 Bloom Filter 预筛（减少无效 .mca 读取；默认 true）")
                    .translation("hassium.configuration.clientCache.bloomFilterEnabled")
                    .define("bloomFilterEnabled", true);
            cacheBloomFilterExpectedInsertions = builder
                    .comment("Bloom Filter 预期元素数量（影响内存；默认 10000）")
                    .translation("hassium.configuration.clientCache.bloomFilterExpectedInsertions")
                    .defineInRange("bloomFilterExpectedInsertions", 10000, 1000, 50_000_000);
            cacheBloomFilterFpp = builder
                    .comment("Bloom Filter 期望假阳性率（0.01 = 1%；默认 0.01）")
                    .translation("hassium.configuration.clientCache.bloomFilterFpp")
                    .defineInRange("bloomFilterFpp", 0.01, 0.001, 0.1);
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
            builder.pop();

            builder.comment("客户端网络与主线程应用相关配置")
                    .translation("hassium.configuration.clientNetwork")
                    .push("network");
            networkClientChunkLoadThreads = builder
                    .comment("=== 客户端线程与光照 ===")
                    .comment("客户端区块加载线程数（默认 10）")
                    .translation("hassium.configuration.clientNetwork.clientChunkLoadThreads")
                    .defineInRange("clientChunkLoadThreads", 10, 1, 64);
            networkLightStripEnabled = builder
                    .comment("是否启用光照剥离：发包去掉 LightData，由客户端本地重算。"
                            + "出现光照异常时可关闭。详见 docs/mod-compat.md（默认 true）")
                    .translation("hassium.configuration.clientNetwork.lightStripEnabled")
                    .define("lightStripEnabled", true);
            networkBackgroundThreads = builder
                    .comment("客户端后台线程池大小（默认 8）")
                    .translation("hassium.configuration.clientNetwork.backgroundThreads")
                    .defineInRange("backgroundThreads", 8, 1, 64);
            networkMaxChunksPerFrame = builder
                    .comment("=== 主线程限流 ===")
                    .comment("每帧应用缓存区块的安全硬顶（主限流为时间预算；默认 32）")
                    .translation("hassium.configuration.clientNetwork.maxChunksPerFrame")
                    .defineInRange("maxChunksPerFrame", 32, 1, 512);
            networkMaxCallbacksPerFrame = builder
                    .comment("每帧主线程异步回调安全硬顶（默认 32）")
                    .translation("hassium.configuration.clientNetwork.maxCallbacksPerFrame")
                    .defineInRange("maxCallbacksPerFrame", 32, 1, 512);
            networkMainThreadChunkBudgetMs = builder
                    .comment("每帧主线程应用区块的时间预算（毫秒；默认 3；进服 JoinBoost 期间可临时提高）")
                    .translation("hassium.configuration.clientNetwork.mainThreadChunkBudgetMs")
                    .defineInRange("mainThreadChunkBudgetMs", 3, 1, 50);
            networkMaxLightRecomputePerFrame = builder
                    .comment("每帧最多重算光照的区块数（默认 10）")
                    .translation("hassium.configuration.clientNetwork.maxLightRecomputePerFrame")
                    .defineInRange("maxLightRecomputePerFrame", 10, 1, 256);
            builder.pop();
        }
    }

    public static final class Common {
#if MC_VER < MC_1_20_5
        public final ForgeConfigSpec.BooleanValue storageEnabled;
        public final ForgeConfigSpec.ConfigValue<String> storageMode;
        public final ForgeConfigSpec.IntValue storageZstdLevel;

        public final ForgeConfigSpec.BooleanValue networkEnabled;
        public final ForgeConfigSpec.IntValue networkCompressionLevel;
        public final ForgeConfigSpec.IntValue networkMaxChunksPerTick;
        public final ForgeConfigSpec.BooleanValue networkGlobalPacketCompression;
        public final ForgeConfigSpec.IntValue networkGlobalCompressionLevel;
        public final ForgeConfigSpec.IntValue networkGlobalCompressionThreshold;
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> networkCompressionBlacklist;
        public final ForgeConfigSpec.BooleanValue networkUseContextCompression;
        public final ForgeConfigSpec.BooleanValue networkMagiclessZstd;
        public final ForgeConfigSpec.BooleanValue networkEnablePacketAggregation;
        public final ForgeConfigSpec.IntValue networkAggregationMinBatchSize;
        public final ForgeConfigSpec.IntValue networkAggregationMaxWaitTimeMs;
        public final ForgeConfigSpec.IntValue networkAggregationMaxSize;
        public final ForgeConfigSpec.BooleanValue networkEnableCompactHeader;
        public final ForgeConfigSpec.IntValue networkServerChunkPushThreads;
        public final ForgeConfigSpec.BooleanValue networkMetricsEnabled;
        public final ForgeConfigSpec.BooleanValue networkDynamicThreadPoolEnabled;
        public final ForgeConfigSpec.IntValue networkMinPushThreads;
        public final ForgeConfigSpec.IntValue networkMaxPushThreads;

        public final ForgeConfigSpec.BooleanValue compatRequireClientMod;
        public final ForgeConfigSpec.BooleanValue compatAutoDowngradeOnError;

        public final ForgeConfigSpec.BooleanValue debugMetadataLogging;
        public final ForgeConfigSpec.BooleanValue debugDispatcherLogging;
        public final ForgeConfigSpec.BooleanValue debugAsyncLogging;
        public final ForgeConfigSpec.BooleanValue debugCompressionLogging;
        public final ForgeConfigSpec.BooleanValue debugChunkApplyLogging;
        public final ForgeConfigSpec.BooleanValue debugNetworkLogging;
        public final ForgeConfigSpec.BooleanValue debugCacheLogging;

        Common(ForgeConfigSpec.Builder builder) {
#else
        public final ModConfigSpec.BooleanValue storageEnabled;
        public final ModConfigSpec.ConfigValue<String> storageMode;
        public final ModConfigSpec.IntValue storageZstdLevel;

        public final ModConfigSpec.BooleanValue networkEnabled;
        public final ModConfigSpec.IntValue networkCompressionLevel;
        public final ModConfigSpec.IntValue networkMaxChunksPerTick;
        public final ModConfigSpec.BooleanValue networkGlobalPacketCompression;
        public final ModConfigSpec.IntValue networkGlobalCompressionLevel;
        public final ModConfigSpec.IntValue networkGlobalCompressionThreshold;
        public final ModConfigSpec.ConfigValue<List<? extends String>> networkCompressionBlacklist;
        public final ModConfigSpec.BooleanValue networkUseContextCompression;
        public final ModConfigSpec.BooleanValue networkMagiclessZstd;
        public final ModConfigSpec.BooleanValue networkEnablePacketAggregation;
        public final ModConfigSpec.IntValue networkAggregationMinBatchSize;
        public final ModConfigSpec.IntValue networkAggregationMaxWaitTimeMs;
        public final ModConfigSpec.IntValue networkAggregationMaxSize;
        public final ModConfigSpec.BooleanValue networkEnableCompactHeader;
        public final ModConfigSpec.IntValue networkServerChunkPushThreads;
        public final ModConfigSpec.BooleanValue networkMetricsEnabled;
        public final ModConfigSpec.BooleanValue networkDynamicThreadPoolEnabled;
        public final ModConfigSpec.IntValue networkMinPushThreads;
        public final ModConfigSpec.IntValue networkMaxPushThreads;

        public final ModConfigSpec.BooleanValue compatRequireClientMod;
        public final ModConfigSpec.BooleanValue compatAutoDowngradeOnError;

        public final ModConfigSpec.BooleanValue debugMetadataLogging;
        public final ModConfigSpec.BooleanValue debugDispatcherLogging;
        public final ModConfigSpec.BooleanValue debugAsyncLogging;
        public final ModConfigSpec.BooleanValue debugCompressionLogging;
        public final ModConfigSpec.BooleanValue debugChunkApplyLogging;
        public final ModConfigSpec.BooleanValue debugNetworkLogging;
        public final ModConfigSpec.BooleanValue debugCacheLogging;

        Common(ModConfigSpec.Builder builder) {
#endif
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

            builder.comment("网络与区块推送配置（双端共享项；部分仅服务端生效）")
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
            networkMaxChunksPerTick = builder
                    .comment("每玩家每 server tick 最多序列化/推送区块数（默认 10；仅服务端）")
                    .translation("hassium.configuration.network.maxChunksPerTick")
                    .defineInRange("maxChunksPerTick", 10, 1, 256);
            networkGlobalPacketCompression = builder
                    .comment("=== 全局包压缩（替换原版 Zlib） ===")
                    .comment("【高风险】是否用 ZSTD 替换原版 Zlib 全局包压缩（影响几乎所有数据包）。"
                            + "与同类压缩/Via 同进程叠用可能冲突。默认 true。【改后建议重启】")
                    .translation("hassium.configuration.network.globalPacketCompression")
                    .define("globalPacketCompression", true);
            networkGlobalCompressionLevel = builder
                    .comment("全局包压缩等级（默认 3）")
                    .translation("hassium.configuration.network.globalCompressionLevel")
                    .defineInRange("globalCompressionLevel", 3, 1, 22);
            networkGlobalCompressionThreshold = builder
                    .comment("全局压缩阈值（字节；小于此值不压；默认 256，与原版一致）")
                    .translation("hassium.configuration.network.globalCompressionThreshold")
                    .defineInRange("globalCompressionThreshold", 256, 0, 65536);
            networkCompressionBlacklist = builder
                    .comment("压缩/聚合黑名单：包 ID 或命名空间前缀字符串列表。"
                            + "示例：distant_horizons:xxx、某伴生 mod 的 namespace:path。"
                            + "第三方通道被聚合拖慢时可加入。详见 docs/mod-compat.md")
                    .translation("hassium.configuration.network.compressionBlacklist")
                    .defineList("compressionBlacklist",
                            () -> new ArrayList<>(HassiumConfig.NetworkConfig.DEFAULT_COMPRESSION_BLACKLIST),
                            o -> o instanceof String);
            networkUseContextCompression = builder
                    .comment("=== 上下文压缩 ===")
                    .comment("是否使用上下文压缩（默认 true）")
                    .translation("hassium.configuration.network.useContextCompression")
                    .define("useContextCompression", true);
            networkMagiclessZstd = builder
                    .comment("是否使用无 magic 的 ZSTD 帧格式（默认 true）")
                    .translation("hassium.configuration.network.magiclessZstd")
                    .define("magiclessZstd", true);
            networkEnablePacketAggregation = builder
                    .comment("=== 包聚合 ===")
                    .comment("【高风险/兼容逃生】是否启用包聚合。与第三方自定义通道冲突时可关闭。"
                            + "详见 docs/mod-compat.md（默认 true）")
                    .translation("hassium.configuration.network.enablePacketAggregation")
                    .define("enablePacketAggregation", true);
            networkAggregationMinBatchSize = builder
                    .comment("聚合最小批量大小（默认 4）")
                    .translation("hassium.configuration.network.aggregationMinBatchSize")
                    .defineInRange("aggregationMinBatchSize", 4, 1, 256);
            networkAggregationMaxWaitTimeMs = builder
                    .comment("聚合最大等待时间（毫秒；默认 20）")
                    .translation("hassium.configuration.network.aggregationMaxWaitTimeMs")
                    .defineInRange("aggregationMaxWaitTimeMs", 20, 1, 5000);
            networkAggregationMaxSize = builder
                    .comment("聚合最大大小（字节；默认 262144 = 256KB）")
                    .translation("hassium.configuration.network.aggregationMaxSize")
                    .defineInRange("aggregationMaxSize", 256 * 1024, 1024, 8 * 1024 * 1024);
            networkEnableCompactHeader = builder
                    .comment("是否启用紧凑包头（主要用于聚合包内部；默认 true）")
                    .translation("hassium.configuration.network.enableCompactHeader")
                    .define("enableCompactHeader", true);
            networkServerChunkPushThreads = builder
                    .comment("=== 服务端推送线程 ===")
                    .comment("服务端区块推送线程数（动态池关闭时的基准；默认 8；仅服务端）")
                    .translation("hassium.configuration.network.serverChunkPushThreads")
                    .defineInRange("serverChunkPushThreads", 8, 1, 64);
            networkMetricsEnabled = builder
                    .comment("=== 指标 ===")
                    .comment("是否启用网络指标收集（流量、缓存命中等；默认 true）")
                    .translation("hassium.configuration.network.metricsEnabled")
                    .define("metricsEnabled", true);
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
            builder.pop();

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
