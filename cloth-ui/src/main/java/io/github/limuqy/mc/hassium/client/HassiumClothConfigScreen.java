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
        var dCache = HassiumConfig.ClientCacheConfig.DEFAULT;
        var dClientNet = HassiumConfig.ClientNetworkConfig.DEFAULT;
        var dDebug = HassiumConfig.DebugConfig.DEFAULT;

        // === Category 1: 缓存（11 项）===
        ConfigCategory cache = builder.getOrCreateCategory(
                Component.translatable("hassium.configuration.category.cache"));
        cache.addEntry(bool(entries, "hassium.configuration.clientCache.enabled",
                draft.cacheEnabled, dCache.enabled(), v -> draft.cacheEnabled = v));
        cache.addEntry(intRange(entries, "hassium.configuration.clientCache.maxSizeMb",
                draft.cacheMaxSizeMb, dCache.maxSizeMb(), 64, 1024 * 1024, v -> draft.cacheMaxSizeMb = v));
        cache.addEntry(intRange(entries, "hassium.configuration.clientCache.cacheCompressionLevel",
                draft.cacheCompressionLevel, dCache.cacheCompressionLevel(), 1, 22,
                v -> draft.cacheCompressionLevel = v));
        cache.addEntry(bool(entries, "hassium.configuration.clientCache.sectionDeltaEnabled",
                draft.cacheSectionDeltaEnabled, dCache.sectionDeltaEnabled(),
                v -> draft.cacheSectionDeltaEnabled = v));
        cache.addEntry(bool(entries, "hassium.configuration.network.enabled",
                draft.networkEnabled, dClientNet.enabled(), v -> draft.networkEnabled = v));
        cache.addEntry(doubleRange(entries, "hassium.configuration.clientCache.hotScoreThreshold",
                draft.cacheHotScoreThreshold, dCache.hotScoreThreshold(), 0.0, 1.0,
                v -> draft.cacheHotScoreThreshold = v));
        cache.addEntry(doubleRange(entries, "hassium.configuration.clientCache.recencyWeight",
                draft.cacheRecencyWeight, dCache.recencyWeight(), 0.0, 1.0, v -> draft.cacheRecencyWeight = v));
        cache.addEntry(doubleRange(entries, "hassium.configuration.clientCache.frequencyWeight",
                draft.cacheFrequencyWeight, dCache.frequencyWeight(), 0.0, 1.0, v -> draft.cacheFrequencyWeight = v));
        cache.addEntry(intRange(entries, "hassium.configuration.clientCache.cleanupIntervalTicks",
                draft.cacheCleanupIntervalTicks, dCache.cleanupIntervalTicks(), 20, 72000,
                v -> draft.cacheCleanupIntervalTicks = v));
        cache.addEntry(intRange(entries, "hassium.configuration.clientCache.targetCacheSizeMb",
                draft.cacheTargetCacheSizeMb, dCache.targetCacheSizeMb(), 0, 1024 * 1024,
                v -> draft.cacheTargetCacheSizeMb = v));
        cache.addEntry(intRange(entries, "hassium.configuration.clientCache.minCleanupBatchSize",
                draft.cacheMinCleanupBatchSize, dCache.minCleanupBatchSize(), 1, 100000,
                v -> draft.cacheMinCleanupBatchSize = v));
        cache.addEntry(bool(entries, "hassium.configuration.clientCache.entitySnapshotsEnabled",
                draft.cacheEntitySnapshotsEnabled, dCache.entitySnapshotsEnabled(),
                v -> draft.cacheEntitySnapshotsEnabled = v));

        // === Category 2: 渲染（9 项）===
        ConfigCategory render = builder.getOrCreateCategory(
                Component.translatable("hassium.configuration.category.render"));
        render.addEntry(bool(entries, "hassium.configuration.clientCache.viewDistanceExtensionEnabled",
                draft.cacheViewDistanceExtensionEnabled, dCache.viewDistanceExtensionEnabled(),
                v -> draft.cacheViewDistanceExtensionEnabled = v));
        render.addEntry(intRange(entries, "hassium.configuration.clientCache.maxRenderDistance",
                draft.cacheMaxRenderDistance, dCache.maxRenderDistance(), 2, 64,
                v -> draft.cacheMaxRenderDistance = v));
        render.addEntry(intRange(entries, "hassium.configuration.clientCache.ovdUnloadDelaySecs",
                draft.cacheOvdUnloadDelaySecs, dCache.ovdUnloadDelaySecs(), 0, 60,
                v -> draft.cacheOvdUnloadDelaySecs = v));
        render.addEntry(bool(entries, "hassium.configuration.clientCache.joinBoostEnabled",
                draft.cacheJoinBoostEnabled, dCache.joinBoostEnabled(),
                v -> draft.cacheJoinBoostEnabled = v));
        render.addEntry(intRange(entries, "hassium.configuration.clientCache.loadThreads",
                draft.loadThreads, dCache.loadThreads(), 1, 64,
                v -> draft.loadThreads = v));
        render.addEntry(bool(entries, "hassium.configuration.clientCache.lightStrip",
                draft.lightStrip, dCache.lightStrip(), v -> draft.lightStrip = v));
        render.addEntry(intRange(entries, "hassium.configuration.clientCache.maxChunksPerFrame",
                draft.maxChunksPerFrame, dCache.maxChunksPerFrame(), 1, 512, v -> draft.maxChunksPerFrame = v));
        render.addEntry(intRange(entries, "hassium.configuration.clientCache.mainThreadChunkBudgetMs",
                draft.mainThreadChunkBudgetMs, dCache.mainThreadChunkBudgetMs(), 1, 50,
                v -> draft.mainThreadChunkBudgetMs = v));
        render.addEntry(bool(entries, "hassium.configuration.network.metricsEnabled",
                draft.metricsEnabled, dClientNet.metricsEnabled(), v -> draft.metricsEnabled = v));

        // === Category 3: 调试（7 项）===
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
        boolean cacheEntitySnapshotsEnabled;
        // 线程与应用
        int loadThreads;
        boolean lightStrip;
        int maxChunksPerFrame;
        int mainThreadChunkBudgetMs;
        // 网络开关
        boolean networkEnabled;
        boolean metricsEnabled;
        // 调试
        boolean metadataLogging;
        boolean dispatcherLogging;
        boolean asyncLogging;
        boolean compressionLogging;
        boolean chunkApplyLogging;
        boolean networkLogging;
        boolean cacheLogging;

        static Draft from(HassiumConfig c) {
            Draft d = new Draft();
            var cache = c.clientCache();
            var clientNet = c.clientNetwork();
            var debug = c.debug();

            d.cacheEnabled = cache.enabled();
            d.cacheMaxSizeMb = cache.maxSizeMb();
            d.cacheCompressionLevel = cache.cacheCompressionLevel();
            d.cacheHotScoreThreshold = cache.hotScoreThreshold();
            d.cacheRecencyWeight = cache.recencyWeight();
            d.cacheFrequencyWeight = cache.frequencyWeight();
            d.cacheCleanupIntervalTicks = cache.cleanupIntervalTicks();
            d.cacheTargetCacheSizeMb = cache.targetCacheSizeMb();
            d.cacheMinCleanupBatchSize = cache.minCleanupBatchSize();
            d.cacheViewDistanceExtensionEnabled = cache.viewDistanceExtensionEnabled();
            d.cacheMaxRenderDistance = cache.maxRenderDistance();
            d.cacheOvdUnloadDelaySecs = cache.ovdUnloadDelaySecs();
            d.cacheSectionDeltaEnabled = cache.sectionDeltaEnabled();
            d.cacheJoinBoostEnabled = cache.joinBoostEnabled();
            d.cacheEntitySnapshotsEnabled = cache.entitySnapshotsEnabled();
            d.loadThreads = cache.loadThreads();
            d.lightStrip = cache.lightStrip();
            d.maxChunksPerFrame = cache.maxChunksPerFrame();
            d.mainThreadChunkBudgetMs = cache.mainThreadChunkBudgetMs();

            d.networkEnabled = clientNet.enabled();
            d.metricsEnabled = clientNet.metricsEnabled();

            d.metadataLogging = debug.metadataLogging();
            d.dispatcherLogging = debug.dispatcherLogging();
            d.asyncLogging = debug.asyncLogging();
            d.compressionLogging = debug.compressionLogging();
            d.chunkApplyLogging = debug.chunkApplyLogging();
            d.networkLogging = debug.networkLogging();
            d.cacheLogging = debug.cacheLogging();
            return d;
        }

        HassiumConfig toConfig() {
            return new HassiumConfig(
                    HassiumConfig.StorageConfig.DEFAULT,
                    new HassiumConfig.ClientCacheConfig(
                            cacheEnabled, cacheMaxSizeMb, cacheCompressionLevel,
                            cacheHotScoreThreshold, cacheRecencyWeight, cacheFrequencyWeight,
                            cacheCleanupIntervalTicks, cacheTargetCacheSizeMb, cacheMinCleanupBatchSize,
                            cacheViewDistanceExtensionEnabled, cacheMaxRenderDistance, cacheOvdUnloadDelaySecs,
                            cacheSectionDeltaEnabled, cacheJoinBoostEnabled, cacheEntitySnapshotsEnabled,
                            loadThreads, lightStrip, maxChunksPerFrame, mainThreadChunkBudgetMs
                    ),
                    new HassiumConfig.ClientNetworkConfig(networkEnabled, metricsEnabled),
                    HassiumConfig.ServerNetworkConfig.DEFAULT,
                    HassiumConfig.CompatConfig.DEFAULT,
                    new HassiumConfig.DebugConfig(
                            metadataLogging, dispatcherLogging, asyncLogging, compressionLogging,
                            chunkApplyLogging, networkLogging, cacheLogging
                    )
            );
        }
    }
}
