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
 * UI 4 类分组（REQ 决策 5）：区块缓存（10 项）/ 渲染与生成（5 项）/ 网络与连接（2 项）/ 调试（8 项）。
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
                    // 服务端字段不在 UI 中：从当前快照原样带回，避免写回时重置 server.toml
                    svc.updateConfig(draft.toConfig(svc.getConfig()));
                    svc.saveConfig();
                });

        ConfigEntryBuilder entries = builder.entryBuilder();
        var dCache = HassiumConfig.ChunkCoreConfig.DEFAULT;
        var dDebug = HassiumConfig.DebugConfig.DEFAULT;

        // === Category 1: 区块缓存（9 项）===
        ConfigCategory chunkCache = builder.getOrCreateCategory(
                Component.translatable("hassium.configuration.category.chunkCache"));
        chunkCache.addEntry(bool(entries, "hassium.configuration.chunk.enabled",
                draft.cacheEnabled, dCache.enabled(), v -> draft.cacheEnabled = v));
        chunkCache.addEntry(intRange(entries, "hassium.configuration.chunk.maxSizeMb",
                draft.cacheMaxSizeMb, dCache.maxSizeMb(), 64, 1024 * 1024, v -> draft.cacheMaxSizeMb = v));
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

        // === Category 2: 渲染、加载与生成（5 项）===
        ConfigCategory rendering = builder.getOrCreateCategory(
                Component.translatable("hassium.configuration.category.rendering"));
        rendering.addEntry(bool(entries, "hassium.configuration.chunk.viewDistanceExtensionEnabled",
                draft.viewDistanceExtensionEnabled, dCache.viewDistanceExtensionEnabled(),
                v -> draft.viewDistanceExtensionEnabled = v));
        rendering.addEntry(intRange(entries, "hassium.configuration.chunk.maxRenderDistance",
                draft.maxRenderDistance, dCache.maxRenderDistance(), 2, 64,
                v -> draft.maxRenderDistance = v));
        rendering.addEntry(intRange(entries, "hassium.configuration.chunk.maxChunksPerFrame",
                draft.maxChunksPerFrame, dCache.maxChunksPerFrame(), 1, 512, v -> draft.maxChunksPerFrame = v));
        rendering.addEntry(intRange(entries, "hassium.configuration.chunk.mainThreadChunkBudgetMs",
                draft.mainThreadChunkBudgetMs, dCache.mainThreadChunkBudgetMs(), 1, 50,
                v -> draft.mainThreadChunkBudgetMs = v));
        rendering.addEntry(bool(entries, "hassium.configuration.chunk.seedGenEnabled",
                draft.seedGenEnabled, dCache.seedGenEnabled(), v -> draft.seedGenEnabled = v));

        // === Category 3: 调试（客户端 10 项）===
        ConfigCategory debugCat = builder.getOrCreateCategory(
                Component.translatable("hassium.configuration.category.debug"));
        debugCat.addEntry(bool(entries, "hassium.configuration.debug.networkMetricsEnabled",
                draft.metricsEnabled, dDebug.networkMetricsEnabled(), v -> draft.metricsEnabled = v));
        debugCat.addEntry(bool(entries, "hassium.configuration.debug.networkMetricsAutoReset",
                draft.metricsAutoReset, dDebug.networkMetricsAutoReset(), v -> draft.metricsAutoReset = v));
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
        boolean cacheEnabled;
        int cacheMaxSizeMb;
        double cacheHotScoreThreshold;
        double cacheRecencyWeight;
        double cacheFrequencyWeight;
        int cacheCleanupIntervalTicks;
        int cacheTargetCacheSizeMb;
        int cacheMinCleanupBatchSize;
        boolean cacheSectionDeltaEnabled;
        boolean viewDistanceExtensionEnabled;
        int maxRenderDistance;
        int maxChunksPerFrame;
        int mainThreadChunkBudgetMs;
        boolean seedGenEnabled;
        boolean metricsEnabled;
        boolean metricsAutoReset;
        boolean metadataLogging;
        boolean dispatcherLogging;
        boolean asyncLogging;
        boolean compressionLogging;
        boolean chunkApplyLogging;
        boolean networkLogging;
        boolean cacheLogging;
        boolean lightVerify;

        static Draft from(HassiumConfig c) {
            Draft d = new Draft();
            var cache = c.chunk();
            var debug = c.debug();
            d.cacheEnabled = cache.enabled();
            d.cacheMaxSizeMb = cache.maxSizeMb();
            d.cacheHotScoreThreshold = cache.hotScoreThreshold();
            d.cacheRecencyWeight = cache.recencyWeight();
            d.cacheFrequencyWeight = cache.frequencyWeight();
            d.cacheCleanupIntervalTicks = cache.cleanupIntervalTicks();
            d.cacheTargetCacheSizeMb = cache.targetSizeMb();
            d.cacheMinCleanupBatchSize = cache.minCleanupBatchSize();
            d.cacheSectionDeltaEnabled = cache.sectionDeltaEnabled();
            d.viewDistanceExtensionEnabled = cache.viewDistanceExtensionEnabled();
            d.maxRenderDistance = cache.maxRenderDistance();
            d.maxChunksPerFrame = cache.maxChunksPerFrame();
            d.mainThreadChunkBudgetMs = cache.mainThreadChunkBudgetMs();
            d.seedGenEnabled = cache.seedGenEnabled();
            d.metricsEnabled = debug.networkMetricsEnabled();
            d.metricsAutoReset = debug.networkMetricsAutoReset();
            d.metadataLogging = debug.metadataLogging();
            d.dispatcherLogging = debug.dispatcherLogging();
            d.asyncLogging = debug.asyncLogging();
            d.compressionLogging = debug.compressionLogging();
            d.chunkApplyLogging = debug.chunkApplyLogging();
            d.networkLogging = debug.networkLogging();
            d.cacheLogging = debug.cacheLogging();
            d.lightVerify = debug.lightVerify();
            return d;
        }

        /** 仅改客户端字段；storage/master/compat 与 lightStrip/服务端 debug 从 base 原样保留。 */
        HassiumConfig toConfig(HassiumConfig base) {
            var baseChunk = base.chunk();
            var baseDebug = base.debug();
            return new HassiumConfig(
                    base.storage(),
                    new HassiumConfig.ChunkCoreConfig(
                            cacheEnabled, cacheMaxSizeMb,
                            cacheHotScoreThreshold, cacheRecencyWeight, cacheFrequencyWeight,
                            cacheCleanupIntervalTicks, cacheTargetCacheSizeMb, cacheMinCleanupBatchSize,
                            cacheSectionDeltaEnabled,
                            viewDistanceExtensionEnabled, maxRenderDistance,
                            maxChunksPerFrame, mainThreadChunkBudgetMs,
                            seedGenEnabled,
                            baseChunk.lightStrip()),
                    base.master(),
                    base.compat(),
                    new HassiumConfig.DebugConfig(
                            metadataLogging, dispatcherLogging, asyncLogging, compressionLogging,
                            chunkApplyLogging, networkLogging, cacheLogging,
                            lightVerify, metricsEnabled, metricsAutoReset));
        }
    }
}

