package io.github.limuqy.mc.hassium.client;

import io.github.limuqy.mc.hassium.config.HassiumConfig;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Cloth 配置屏：绑定 {@link HassiumConfig}。
 * Fabric 保存 toml；Forge/NeoForge 写回 ConfigSpec。
 * <p>
 * 仅显示客户端字段；服务端字段不出现 GUI 中，toConfig() 用 DEFAULT 填充。
 * <p>
 * UI 4 类分组（REQ 决策 5）：区块缓存（11 项）/ 渲染与生成（10 项）/ 网络与连接（3 项）/ 调试（9 项）。
 */
public final class HassiumClothConfigScreen {

    private HassiumClothConfigScreen() {
    }

    public static Screen create(Screen parent) {
        HassiumConfig base = HassiumConfigService.getInstance().getConfig();
        Draft draft = Draft.from(base);

        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable("hassium.configuration.title", "Hassium"))
                .setSavingRunnable(() -> {
                    HassiumConfigService svc = HassiumConfigService.getInstance();
                    svc.updateConfig(draft.toConfig());
                    svc.saveConfig();
                });

        ConfigEntryBuilder entries = builder.entryBuilder();
        var dCache = HassiumConfig.ChunkCoreConfig.DEFAULT;
        var dNet = HassiumConfig.NetCoreConfig.DEFAULT;
        var dDebug = HassiumConfig.DebugConfig.DEFAULT;

        // === Category 1: 区块缓存（11 项）===
        ConfigCategory chunkCache = builder.getOrCreateCategory(
                Component.translatable("hassium.configuration.category.chunkCache"));
        chunkCache.addEntry(bool(entries, "hassium.configuration.chunk.enabled",
                draft.cacheEnabled, dCache.enabled(), v -> draft.cacheEnabled = v));
        chunkCache.addEntry(intRange(entries, "hassium.configuration.chunk.maxSizeMb",
                draft.cacheMaxSizeMb, dCache.maxSizeMb(), 64, 1024 * 1024, v -> draft.cacheMaxSizeMb = v));
        chunkCache.addEntry(intRange(entries, "hassium.configuration.chunk.compressionLevel",
                draft.cacheCompressionLevel, dCache.compressionLevel(), 1, 22,
                v -> draft.cacheCompressionLevel = v));
        chunkCache.addEntry(doubleRange(entries, "hassium.configuration.chunk.hotScoreThreshold",
                draft.cacheHotScoreThreshold, dCache.hotScoreThreshold(), 0.0, 1.0,
                v -> draft.cacheHotScoreThreshold = v));
        chunkCache.addEntry(doubleRange(entries, "hassium.configuration.chunk.recencyWeight",
                draft.cacheRecencyWeight, dCache.recencyWeight(), 0.0, 1.0, v -> draft.cacheRecencyWeight = v));
        chunkCache.addEntry(doubleRange(entries, "hassium.configuration.chunk.frequencyWeight",
                draft.cacheFrequencyWeight, dCache.frequencyWeight(), 0.0, 1.0, v -> draft.cacheFrequencyWeight = v));
        chunkCache.addEntry(intRange(entries, "hassium.configuration.chunk.cleanupIntervalTicks",
                draft.cacheCleanupIntervalTicks, dCache.cleanupIntervalTicks(), 20, 72000,
                v -> draft.cacheCleanupIntervalTicks = v));
        chunkCache.addEntry(intRange(entries, "hassium.configuration.chunk.targetSizeMb",
                draft.cacheTargetCacheSizeMb, dCache.targetSizeMb(), 0, 1024 * 1024,
                v -> draft.cacheTargetCacheSizeMb = v));
        chunkCache.addEntry(intRange(entries, "hassium.configuration.chunk.minCleanupBatchSize",
                draft.cacheMinCleanupBatchSize, dCache.minCleanupBatchSize(), 1, 100000,
                v -> draft.cacheMinCleanupBatchSize = v));
        chunkCache.addEntry(bool(entries, "hassium.configuration.chunk.sectionDeltaEnabled",
                draft.cacheSectionDeltaEnabled, dCache.sectionDeltaEnabled(),
                v -> draft.cacheSectionDeltaEnabled = v));
        chunkCache.addEntry(bool(entries, "hassium.configuration.chunk.joinBoostEnabled",
                draft.cacheJoinBoostEnabled, dCache.joinBoostEnabled(),
                v -> draft.cacheJoinBoostEnabled = v));

        // === Category 2: 渲染与生成（10 项）===
        ConfigCategory rendering = builder.getOrCreateCategory(
                Component.translatable("hassium.configuration.category.rendering"));
        rendering.addEntry(bool(entries, "hassium.configuration.chunk.viewDistanceExtensionEnabled",
                draft.cacheViewDistanceExtensionEnabled, dCache.viewDistanceExtensionEnabled(),
                v -> draft.cacheViewDistanceExtensionEnabled = v));
        rendering.addEntry(intRange(entries, "hassium.configuration.chunk.maxRenderDistance",
                draft.cacheMaxRenderDistance, dCache.maxRenderDistance(), 2, 64,
                v -> draft.cacheMaxRenderDistance = v));
        rendering.addEntry(intRange(entries, "hassium.configuration.chunk.ovdUnloadDelaySecs",
                draft.cacheOvdUnloadDelaySecs, dCache.ovdUnloadDelaySecs(), 0, 60,
                v -> draft.cacheOvdUnloadDelaySecs = v));
        rendering.addEntry(intRange(entries, "hassium.configuration.chunk.unloadDelaySecs",
                draft.cacheUnloadDelaySecs, dCache.unloadDelaySecs(), 0, 600,
                v -> draft.cacheUnloadDelaySecs = v));
        rendering.addEntry(intRange(entries, "hassium.configuration.chunk.maxChunksPerFrame",
                draft.maxChunksPerFrame, dCache.maxChunksPerFrame(), 1, 512, v -> draft.maxChunksPerFrame = v));
        rendering.addEntry(intRange(entries, "hassium.configuration.chunk.mainThreadChunkBudgetMs",
                draft.mainThreadChunkBudgetMs, dCache.mainThreadChunkBudgetMs(), 1, 50,
                v -> draft.mainThreadChunkBudgetMs = v));
        rendering.addEntry(bool(entries, "hassium.configuration.chunk.hassiumEngineEnabled",
                draft.hassiumEngineEnabled, dCache.hassiumEngineEnabled(),
                v -> draft.hassiumEngineEnabled = v));
        rendering.addEntry(bool(entries, "hassium.configuration.chunk.ovdLocalGeneration",
                draft.ovdLocalGeneration, dCache.ovdLocalGeneration(),
                v -> draft.ovdLocalGeneration = v));
        rendering.addEntry(intRange(entries, "hassium.configuration.chunk.seedGenThreads",
                draft.seedGenThreads, dCache.seedGenThreads(), 0, 64,
                v -> draft.seedGenThreads = v));
        rendering.addEntry(bool(entries, "hassium.configuration.chunk.seedGenEnabled",
                draft.seedGenEnabled, dCache.seedGenEnabled(), v -> draft.seedGenEnabled = v));

        // === Category 3: 网络与连接（3 项 + L1 迁移策略 6 项）===
        ConfigCategory networkCat = builder.getOrCreateCategory(
                Component.translatable("hassium.configuration.category.network"));
        networkCat.addEntry(bool(entries, "hassium.configuration.net.enabled",
                draft.networkEnabled, dNet.enabled(), v -> draft.networkEnabled = v));
        networkCat.addEntry(bool(entries, "hassium.configuration.net.metricsEnabled",
                draft.metricsEnabled, dNet.metricsEnabled(), v -> draft.metricsEnabled = v));
        networkCat.addEntry(bool(entries, "hassium.configuration.net.metricsAutoReset",
                draft.metricsAutoReset, dNet.metricsAutoReset(), v -> draft.metricsAutoReset = v));

        // L1 迁移策略（master.migration* CLIENT scope 键；迁移引擎/快速失效参数）
        var dMaster = HassiumConfig.MasterCoreConfig.DEFAULT;
        networkCat.addEntry(doubleRange(entries, "hassium.configuration.master.migrationMinTps",
                draft.migrationMinTps, dMaster.migrationMinTps(), 0.1, 100.0,
                v -> draft.migrationMinTps = v));
        networkCat.addEntry(doubleRange(entries, "hassium.configuration.master.migrationMaxLoadAverage",
                draft.migrationMaxLoadAverage, dMaster.migrationMaxLoadAverage(), 0.1, 100.0,
                v -> draft.migrationMaxLoadAverage = v));
        networkCat.addEntry(str(entries, "hassium.configuration.master.migrationMaintenanceWindow",
                draft.migrationMaintenanceWindow, dMaster.migrationMaintenanceWindow(),
                v -> draft.migrationMaintenanceWindow = v));
        networkCat.addEntry(intRange(entries, "hassium.configuration.master.migrationHeartbeatIntervalMs",
                (int) draft.migrationHeartbeatIntervalMs, (int) dMaster.migrationHeartbeatIntervalMs(),
                100, 60000, v -> draft.migrationHeartbeatIntervalMs = v));
        networkCat.addEntry(intRange(entries, "hassium.configuration.master.migrationIdleWindowMs",
                (int) draft.migrationIdleWindowMs, (int) dMaster.migrationIdleWindowMs(),
                1000, 600000, v -> draft.migrationIdleWindowMs = v));
        networkCat.addEntry(intRange(entries, "hassium.configuration.master.migrationSilentTimeoutMs",
                (int) draft.migrationSilentTimeoutMs, (int) dMaster.migrationSilentTimeoutMs(),
                1000, 600000, v -> draft.migrationSilentTimeoutMs = v));

        // === Category 4: 调试（9 项）===
        ConfigCategory debugCat = builder.getOrCreateCategory(
                Component.translatable("hassium.configuration.category.debug"));
        debugCat.addEntry(bool(entries, "hassium.configuration.debug.metadataLogging",
                draft.metadataLogging, dDebug.metadataLogging(), v -> draft.metadataLogging = v));
        debugCat.addEntry(bool(entries, "hassium.configuration.debug.dispatcherLogging",
                draft.dispatcherLogging, dDebug.dispatcherLogging(), v -> draft.dispatcherLogging = v));
        debugCat.addEntry(bool(entries, "hassium.configuration.debug.asyncLogging",
                draft.asyncLogging, dDebug.asyncLogging(), v -> draft.asyncLogging = v));
        debugCat.addEntry(bool(entries, "hassium.configuration.debug.compressionLogging",
                draft.compressionLogging, dDebug.compressionLogging(), v -> draft.compressionLogging = v));
        debugCat.addEntry(bool(entries, "hassium.configuration.debug.chunkApplyLogging",
                draft.chunkApplyLogging, dDebug.chunkApplyLogging(), v -> draft.chunkApplyLogging = v));
        debugCat.addEntry(bool(entries, "hassium.configuration.debug.networkLogging",
                draft.networkLogging, dDebug.networkLogging(), v -> draft.networkLogging = v));
        debugCat.addEntry(bool(entries, "hassium.configuration.debug.cacheLogging",
                draft.cacheLogging, dDebug.cacheLogging(), v -> draft.cacheLogging = v));
        debugCat.addEntry(bool(entries, "hassium.configuration.debug.dataplaneLogging",
                draft.dataplaneLogging, dDebug.dataplaneLogging(), v -> draft.dataplaneLogging = v));
        debugCat.addEntry(bool(entries, "hassium.configuration.debug.lightVerify",
                draft.lightVerify, dDebug.lightVerify(), v -> draft.lightVerify = v));
        return builder.build();
    }

    private static me.shedaniel.clothconfig2.api.AbstractConfigListEntry<?> bool(
            ConfigEntryBuilder entries, String key, boolean value, boolean def, Consumer<Boolean> save) {
        return entries.startBooleanToggle(Component.translatable(key), value)
                .setDefaultValue(def)
                .setTooltip(Component.translatable(key + ".tooltip"))
                .setSaveConsumer(save)
                .build();
    }

    private static me.shedaniel.clothconfig2.api.AbstractConfigListEntry<?> intRange(
            ConfigEntryBuilder entries, String key, int value, int def, int min, int max, Consumer<Integer> save) {
        return entries.startIntField(Component.translatable(key), value)
                .setDefaultValue(def)
                .setMin(min).setMax(max)
                .setTooltip(Component.translatable(key + ".tooltip"))
                .setSaveConsumer(save)
                .build();
    }

    private static me.shedaniel.clothconfig2.api.AbstractConfigListEntry<?> doubleRange(
            ConfigEntryBuilder entries, String key, double value, double def, double min, double max,
            Consumer<Double> save) {
        return entries.startDoubleField(Component.translatable(key), value)
                .setDefaultValue(def)
                .setMin(min).setMax(max)
                .setTooltip(Component.translatable(key + ".tooltip"))
                .setSaveConsumer(save)
                .build();
    }

    private static me.shedaniel.clothconfig2.api.AbstractConfigListEntry<?> str(
            ConfigEntryBuilder entries, String key, String value, String def, Consumer<String> save) {
        return entries.startStrField(Component.translatable(key), value)
                .setDefaultValue(def)
                .setTooltip(Component.translatable(key + ".tooltip"))
                .setSaveConsumer(save)
                .build();
    }

    private static me.shedaniel.clothconfig2.api.AbstractConfigListEntry<?> strList(
            ConfigEntryBuilder entries, String key, List<String> value, List<String> def,
            Consumer<List<String>> save) {
        return entries.startStrList(Component.translatable(key), new ArrayList<>(value))
                .setDefaultValue(new ArrayList<>(def))
                .setTooltip(Component.translatable(key + ".tooltip"))
                .setSaveConsumer(list -> save.accept(new ArrayList<>(list)))
                .build();
    }

    /** Cloth 编辑用可变草稿（仅客户端字段）。 */
    private static final class Draft {
        // 客户端缓存基础
        boolean cacheEnabled;
        int cacheMaxSizeMb;
        int cacheCompressionLevel;
        double cacheHotScoreThreshold;
        double cacheRecencyWeight;
        double cacheFrequencyWeight;
        int cacheCleanupIntervalTicks;
        int cacheTargetCacheSizeMb;
        int cacheMinCleanupBatchSize;
        // 超视渲染与分段增量
        boolean cacheViewDistanceExtensionEnabled;
        int cacheMaxRenderDistance;
        int cacheOvdUnloadDelaySecs;
        boolean cacheSectionDeltaEnabled;
        boolean cacheJoinBoostEnabled;
        // 影子端内存区块回收延迟
        int cacheUnloadDelaySecs;
        // 线程与应用
        int maxChunksPerFrame;
        int mainThreadChunkBudgetMs;
        // SeedGen 本地生成
        int seedGenThreads;
        // Hassium 引擎（非网络向功能总开关）
        boolean hassiumEngineEnabled;
        // OVD 本地生成（miss 时Hassium 引擎本地生成 + 存缓存）
        boolean ovdLocalGeneration;
        // 网络开关
        boolean networkEnabled;
        boolean metricsEnabled;
        boolean metricsAutoReset;
        boolean seedGenEnabled;
        // L1 迁移策略（master.migration* CLIENT scope 键）
        double migrationMinTps;
        double migrationMaxLoadAverage;
        String migrationMaintenanceWindow;
        long migrationHeartbeatIntervalMs;
        long migrationIdleWindowMs;
        long migrationSilentTimeoutMs;
        // 调试
        boolean metadataLogging;
        boolean dispatcherLogging;
        boolean asyncLogging;
        boolean compressionLogging;
        boolean chunkApplyLogging;
        boolean networkLogging;
        boolean cacheLogging;
        boolean dataplaneLogging;
        boolean lightVerify;

        static Draft from(HassiumConfig c) {
            Draft d = new Draft();
            var cache = c.chunk();
            var net = c.net();
            var debug = c.debug();

            d.cacheEnabled = cache.enabled();
            d.cacheMaxSizeMb = cache.maxSizeMb();
            d.cacheCompressionLevel = cache.compressionLevel();
            d.cacheHotScoreThreshold = cache.hotScoreThreshold();
            d.cacheRecencyWeight = cache.recencyWeight();
            d.cacheFrequencyWeight = cache.frequencyWeight();
            d.cacheCleanupIntervalTicks = cache.cleanupIntervalTicks();
            d.cacheTargetCacheSizeMb = cache.targetSizeMb();
            d.cacheMinCleanupBatchSize = cache.minCleanupBatchSize();
            d.cacheViewDistanceExtensionEnabled = cache.viewDistanceExtensionEnabled();
            d.cacheMaxRenderDistance = cache.maxRenderDistance();
            d.cacheOvdUnloadDelaySecs = cache.ovdUnloadDelaySecs();
            d.cacheSectionDeltaEnabled = cache.sectionDeltaEnabled();
            d.cacheJoinBoostEnabled = cache.joinBoostEnabled();
            d.cacheUnloadDelaySecs = cache.unloadDelaySecs();
            d.maxChunksPerFrame = cache.maxChunksPerFrame();
            d.mainThreadChunkBudgetMs = cache.mainThreadChunkBudgetMs();
            d.seedGenThreads = cache.seedGenThreads();
            d.hassiumEngineEnabled = cache.hassiumEngineEnabled();
            d.ovdLocalGeneration = cache.ovdLocalGeneration();

            d.networkEnabled = net.enabled();
            d.metricsEnabled = net.metricsEnabled();
            d.metricsAutoReset = net.metricsAutoReset();
            d.seedGenEnabled = cache.seedGenEnabled();

            var master = c.master();
            d.migrationMinTps = master.migrationMinTps();
            d.migrationMaxLoadAverage = master.migrationMaxLoadAverage();
            d.migrationMaintenanceWindow = master.migrationMaintenanceWindow();
            d.migrationHeartbeatIntervalMs = master.migrationHeartbeatIntervalMs();
            d.migrationIdleWindowMs = master.migrationIdleWindowMs();
            d.migrationSilentTimeoutMs = master.migrationSilentTimeoutMs();

            d.metadataLogging = debug.metadataLogging();
            d.dispatcherLogging = debug.dispatcherLogging();
            d.asyncLogging = debug.asyncLogging();
            d.compressionLogging = debug.compressionLogging();
            d.chunkApplyLogging = debug.chunkApplyLogging();
            d.networkLogging = debug.networkLogging();
            d.cacheLogging = debug.cacheLogging();
            d.dataplaneLogging = debug.dataplaneLogging();
            d.lightVerify = debug.lightVerify();
            return d;
        }

        HassiumConfig toConfig() {
            return new HassiumConfig(
                    HassiumConfig.StorageConfig.DEFAULT,
                    new HassiumConfig.ChunkCoreConfig(
                            cacheEnabled, cacheMaxSizeMb, cacheCompressionLevel,
                            cacheHotScoreThreshold, cacheRecencyWeight, cacheFrequencyWeight,
                            cacheCleanupIntervalTicks, cacheTargetCacheSizeMb, cacheMinCleanupBatchSize,
                            cacheSectionDeltaEnabled, cacheJoinBoostEnabled,
                            cacheViewDistanceExtensionEnabled, cacheMaxRenderDistance, cacheOvdUnloadDelaySecs,
                            cacheUnloadDelaySecs, maxChunksPerFrame, mainThreadChunkBudgetMs,
                            seedGenThreads, hassiumEngineEnabled, ovdLocalGeneration,
                            seedGenEnabled,
                            HassiumConfig.ChunkCoreConfig.DEFAULT.lightStrip()
                    ),
                    new HassiumConfig.NetCoreConfig(networkEnabled, metricsEnabled, metricsAutoReset),
                    HassiumConfig.MasterCoreConfig.DEFAULT.withMigrationPolicy(
                            migrationMinTps, migrationMaxLoadAverage, migrationMaintenanceWindow,
                            migrationHeartbeatIntervalMs, migrationIdleWindowMs, migrationSilentTimeoutMs),
                    HassiumConfig.CompatConfig.DEFAULT,
                    new HassiumConfig.DebugConfig(
                            metadataLogging, dispatcherLogging, asyncLogging, compressionLogging,
                            chunkApplyLogging, networkLogging, cacheLogging, dataplaneLogging, lightVerify
                    )
            );
        }
    }
}
