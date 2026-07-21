package io.github.limuqy.mc.hassium.client;

import io.github.limuqy.mc.hassium.config.HassiumConfig;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Cloth 配置屏：绑定 {@link HassiumConfig}。
 * Fabric 保存 toml；Forge/NeoForge 写回 ConfigSpec。
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
        var dNet = HassiumConfig.NetworkConfig.DEFAULT;
        var dStorage = HassiumConfig.StorageConfig.DEFAULT;
        var dCompat = HassiumConfig.CompatConfig.DEFAULT;
        var dDebug = HassiumConfig.DebugConfig.DEFAULT;

        ConfigCategory clientCache = builder.getOrCreateCategory(
                Component.translatable("hassium.configuration.clientCache"));
        clientCache.addEntry(bool(entries, "hassium.configuration.clientCache.enabled",
                draft.cacheEnabled, dCache.enabled(), v -> draft.cacheEnabled = v));
        clientCache.addEntry(intRange(entries, "hassium.configuration.clientCache.maxSizeMb",
                draft.cacheMaxSizeMb, dCache.maxSizeMb(), 64, 1024 * 1024, v -> draft.cacheMaxSizeMb = v));
        clientCache.addEntry(intRange(entries, "hassium.configuration.clientCache.maxAgeDays",
                draft.cacheMaxAgeDays, dCache.maxAgeDays(), 1, 3650, v -> draft.cacheMaxAgeDays = v));
        clientCache.addEntry(doubleRange(entries, "hassium.configuration.clientCache.hotScoreThreshold",
                draft.cacheHotScoreThreshold, dCache.hotScoreThreshold(), 0.0, 1.0,
                v -> draft.cacheHotScoreThreshold = v));
        clientCache.addEntry(doubleRange(entries, "hassium.configuration.clientCache.recencyWeight",
                draft.cacheRecencyWeight, dCache.recencyWeight(), 0.0, 1.0, v -> draft.cacheRecencyWeight = v));
        clientCache.addEntry(doubleRange(entries, "hassium.configuration.clientCache.frequencyWeight",
                draft.cacheFrequencyWeight, dCache.frequencyWeight(), 0.0, 1.0, v -> draft.cacheFrequencyWeight = v));
        clientCache.addEntry(intRange(entries, "hassium.configuration.clientCache.cleanupIntervalTicks",
                draft.cacheCleanupIntervalTicks, dCache.cleanupIntervalTicks(), 20, 72000,
                v -> draft.cacheCleanupIntervalTicks = v));
        clientCache.addEntry(intRange(entries, "hassium.configuration.clientCache.targetCacheSizeMb",
                draft.cacheTargetCacheSizeMb, dCache.targetCacheSizeMb(), 0, 1024 * 1024,
                v -> draft.cacheTargetCacheSizeMb = v));
        clientCache.addEntry(intRange(entries, "hassium.configuration.clientCache.minCleanupBatchSize",
                draft.cacheMinCleanupBatchSize, dCache.minCleanupBatchSize(), 1, 100000,
                v -> draft.cacheMinCleanupBatchSize = v));
        clientCache.addEntry(bool(entries, "hassium.configuration.clientCache.bloomFilterEnabled",
                draft.cacheBloomFilterEnabled, dCache.bloomFilterEnabled(), v -> draft.cacheBloomFilterEnabled = v));
        clientCache.addEntry(intRange(entries, "hassium.configuration.clientCache.bloomFilterExpectedInsertions",
                draft.cacheBloomFilterExpectedInsertions, dCache.bloomFilterExpectedInsertions(),
                1000, 50_000_000, v -> draft.cacheBloomFilterExpectedInsertions = v));
        clientCache.addEntry(doubleRange(entries, "hassium.configuration.clientCache.bloomFilterFpp",
                draft.cacheBloomFilterFpp, dCache.bloomFilterFpp(), 0.001, 0.1, v -> draft.cacheBloomFilterFpp = v));
        clientCache.addEntry(bool(entries, "hassium.configuration.clientCache.viewDistanceExtensionEnabled",
                draft.cacheViewDistanceExtensionEnabled, dCache.viewDistanceExtensionEnabled(),
                v -> draft.cacheViewDistanceExtensionEnabled = v));
        clientCache.addEntry(intRange(entries, "hassium.configuration.clientCache.maxRenderDistance",
                draft.cacheMaxRenderDistance, dCache.maxRenderDistance(), 2, 64,
                v -> draft.cacheMaxRenderDistance = v));
        clientCache.addEntry(intRange(entries, "hassium.configuration.clientCache.ovdUnloadDelaySecs",
                draft.cacheOvdUnloadDelaySecs, dCache.ovdUnloadDelaySecs(), 0, 60,
                v -> draft.cacheOvdUnloadDelaySecs = v));
        clientCache.addEntry(bool(entries, "hassium.configuration.clientCache.sectionDeltaEnabled",
                draft.cacheSectionDeltaEnabled, dCache.sectionDeltaEnabled(),
                v -> draft.cacheSectionDeltaEnabled = v));
        clientCache.addEntry(bool(entries, "hassium.configuration.clientCache.joinBoostEnabled",
                draft.cacheJoinBoostEnabled, dCache.joinBoostEnabled(),
                v -> draft.cacheJoinBoostEnabled = v));
        clientCache.addEntry(bool(entries, "hassium.configuration.clientCache.entitySnapshotsEnabled",
                draft.cacheEntitySnapshotsEnabled, dCache.entitySnapshotsEnabled(),
                v -> draft.cacheEntitySnapshotsEnabled = v));

        ConfigCategory clientNetwork = builder.getOrCreateCategory(
                Component.translatable("hassium.configuration.clientNetwork"));
        clientNetwork.addEntry(intRange(entries, "hassium.configuration.clientNetwork.clientChunkLoadThreads",
                draft.clientChunkLoadThreads, dNet.clientChunkLoadThreads(), 1, 64,
                v -> draft.clientChunkLoadThreads = v));
        clientNetwork.addEntry(bool(entries, "hassium.configuration.clientNetwork.lightStripEnabled",
                draft.lightStripEnabled, dNet.lightStripEnabled(), v -> draft.lightStripEnabled = v));
        clientNetwork.addEntry(intRange(entries, "hassium.configuration.clientNetwork.backgroundThreads",
                draft.backgroundThreads, dNet.backgroundThreads(), 1, 64, v -> draft.backgroundThreads = v));
        clientNetwork.addEntry(intRange(entries, "hassium.configuration.clientNetwork.maxChunksPerFrame",
                draft.maxChunksPerFrame, dNet.maxChunksPerFrame(), 1, 512, v -> draft.maxChunksPerFrame = v));
        clientNetwork.addEntry(intRange(entries, "hassium.configuration.clientNetwork.maxCallbacksPerFrame",
                draft.maxCallbacksPerFrame, dNet.maxCallbacksPerFrame(), 1, 512, v -> draft.maxCallbacksPerFrame = v));
        clientNetwork.addEntry(intRange(entries, "hassium.configuration.clientNetwork.mainThreadChunkBudgetMs",
                draft.mainThreadChunkBudgetMs, dNet.mainThreadChunkBudgetMs(), 1, 50,
                v -> draft.mainThreadChunkBudgetMs = v));

        ConfigCategory storage = builder.getOrCreateCategory(
                Component.translatable("hassium.configuration.storage"));
        storage.addEntry(bool(entries, "hassium.configuration.storage.enabled",
                draft.storageEnabled, dStorage.enabled(), v -> draft.storageEnabled = v));
        storage.addEntry(str(entries, "hassium.configuration.storage.mode",
                draft.storageMode, dStorage.mode(), v -> draft.storageMode = v));
        storage.addEntry(intRange(entries, "hassium.configuration.storage.zstdLevel",
                draft.storageZstdLevel, dStorage.zstdLevel(), 1, 22, v -> draft.storageZstdLevel = v));

        ConfigCategory network = builder.getOrCreateCategory(
                Component.translatable("hassium.configuration.network"));
        network.addEntry(bool(entries, "hassium.configuration.network.enabled",
                draft.networkEnabled, dNet.enabled(), v -> draft.networkEnabled = v));
        network.addEntry(intRange(entries, "hassium.configuration.network.compressionLevel",
                draft.compressionLevel, dNet.compressionLevel(), 1, 22, v -> draft.compressionLevel = v));
        network.addEntry(intRange(entries, "hassium.configuration.network.maxChunksPerTick",
                draft.maxChunksPerTick, dNet.maxChunksPerTick(), 1, 256, v -> draft.maxChunksPerTick = v));
        network.addEntry(bool(entries, "hassium.configuration.network.globalPacketCompression",
                draft.globalPacketCompression, dNet.globalPacketCompression(),
                v -> draft.globalPacketCompression = v));
        network.addEntry(intRange(entries, "hassium.configuration.network.globalCompressionLevel",
                draft.globalCompressionLevel, dNet.globalCompressionLevel(), 1, 22,
                v -> draft.globalCompressionLevel = v));
        network.addEntry(intRange(entries, "hassium.configuration.network.globalCompressionThreshold",
                draft.globalCompressionThreshold, dNet.globalCompressionThreshold(), 0, 65536,
                v -> draft.globalCompressionThreshold = v));
        network.addEntry(strList(entries, "hassium.configuration.network.compressionBlacklist",
                draft.compressionBlacklist,
                new ArrayList<>(HassiumConfig.NetworkConfig.DEFAULT_COMPRESSION_BLACKLIST),
                v -> draft.compressionBlacklist = v));
        network.addEntry(bool(entries, "hassium.configuration.network.useContextCompression",
                draft.useContextCompression, dNet.useContextCompression(), v -> draft.useContextCompression = v));
        network.addEntry(bool(entries, "hassium.configuration.network.magiclessZstd",
                draft.magiclessZstd, dNet.magiclessZstd(), v -> draft.magiclessZstd = v));
        network.addEntry(bool(entries, "hassium.configuration.network.enablePacketAggregation",
                draft.enablePacketAggregation, dNet.enablePacketAggregation(),
                v -> draft.enablePacketAggregation = v));
        network.addEntry(intRange(entries, "hassium.configuration.network.aggregationMinBatchSize",
                draft.aggregationMinBatchSize, dNet.aggregationMinBatchSize(), 1, 256,
                v -> draft.aggregationMinBatchSize = v));
        network.addEntry(intRange(entries, "hassium.configuration.network.aggregationMaxWaitTimeMs",
                (int) draft.aggregationMaxWaitTimeMs, (int) dNet.aggregationMaxWaitTimeMs(), 1, 5000,
                v -> draft.aggregationMaxWaitTimeMs = v));
        network.addEntry(intRange(entries, "hassium.configuration.network.aggregationMaxSize",
                draft.aggregationMaxSize, dNet.aggregationMaxSize(), 1024, 8 * 1024 * 1024,
                v -> draft.aggregationMaxSize = v));
        network.addEntry(bool(entries, "hassium.configuration.network.enableCompactHeader",
                draft.enableCompactHeader, dNet.enableCompactHeader(), v -> draft.enableCompactHeader = v));
        network.addEntry(intRange(entries, "hassium.configuration.network.serverChunkPushThreads",
                draft.serverChunkPushThreads, dNet.serverChunkPushThreads(), 1, 64,
                v -> draft.serverChunkPushThreads = v));
        network.addEntry(bool(entries, "hassium.configuration.network.metricsEnabled",
                draft.metricsEnabled, dNet.metricsEnabled(), v -> draft.metricsEnabled = v));
        network.addEntry(bool(entries, "hassium.configuration.network.dynamicThreadPoolEnabled",
                draft.dynamicThreadPoolEnabled, dNet.dynamicThreadPoolEnabled(),
                v -> draft.dynamicThreadPoolEnabled = v));
        network.addEntry(intRange(entries, "hassium.configuration.network.minPushThreads",
                draft.minPushThreads, dNet.minPushThreads(), 1, 64, v -> draft.minPushThreads = v));
        network.addEntry(intRange(entries, "hassium.configuration.network.maxPushThreads",
                draft.maxPushThreads, dNet.maxPushThreads(), 1, 64, v -> draft.maxPushThreads = v));

        ConfigCategory compat = builder.getOrCreateCategory(
                Component.translatable("hassium.configuration.compat"));
        compat.addEntry(bool(entries, "hassium.configuration.compat.requireClientMod",
                draft.requireClientMod, dCompat.requireClientMod(), v -> draft.requireClientMod = v));
        compat.addEntry(bool(entries, "hassium.configuration.compat.autoDowngradeOnError",
                draft.autoDowngradeOnError, dCompat.autoDowngradeOnError(), v -> draft.autoDowngradeOnError = v));

        ConfigCategory debug = builder.getOrCreateCategory(
                Component.translatable("hassium.configuration.debug"));
        debug.addEntry(bool(entries, "hassium.configuration.debug.metadataLogging",
                draft.metadataLogging, dDebug.metadataLogging(), v -> draft.metadataLogging = v));
        debug.addEntry(bool(entries, "hassium.configuration.debug.dispatcherLogging",
                draft.dispatcherLogging, dDebug.dispatcherLogging(), v -> draft.dispatcherLogging = v));
        debug.addEntry(bool(entries, "hassium.configuration.debug.asyncLogging",
                draft.asyncLogging, dDebug.asyncLogging(), v -> draft.asyncLogging = v));
        debug.addEntry(bool(entries, "hassium.configuration.debug.compressionLogging",
                draft.compressionLogging, dDebug.compressionLogging(), v -> draft.compressionLogging = v));
        debug.addEntry(bool(entries, "hassium.configuration.debug.chunkApplyLogging",
                draft.chunkApplyLogging, dDebug.chunkApplyLogging(), v -> draft.chunkApplyLogging = v));
        debug.addEntry(bool(entries, "hassium.configuration.debug.networkLogging",
                draft.networkLogging, dDebug.networkLogging(), v -> draft.networkLogging = v));
        debug.addEntry(bool(entries, "hassium.configuration.debug.cacheLogging",
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

    /** Cloth 编辑用可变草稿。 */
    private static final class Draft {
        boolean cacheEnabled;
        int cacheMaxSizeMb;
        int cacheMaxAgeDays;
        double cacheHotScoreThreshold;
        double cacheRecencyWeight;
        double cacheFrequencyWeight;
        int cacheCleanupIntervalTicks;
        int cacheTargetCacheSizeMb;
        int cacheMinCleanupBatchSize;
        boolean cacheBloomFilterEnabled;
        int cacheBloomFilterExpectedInsertions;
        double cacheBloomFilterFpp;
        // 超视渲染
        boolean cacheViewDistanceExtensionEnabled;
        int cacheMaxRenderDistance;
        int cacheOvdUnloadDelaySecs;
        boolean cacheSectionDeltaEnabled;
        boolean cacheJoinBoostEnabled;
        boolean cacheEntitySnapshotsEnabled;

        int clientChunkLoadThreads;
        boolean lightStripEnabled;
        int backgroundThreads;
        int maxChunksPerFrame;
        int maxCallbacksPerFrame;
        int mainThreadChunkBudgetMs;

        boolean storageEnabled;
        String storageMode;
        int storageZstdLevel;

        boolean networkEnabled;
        int compressionLevel;
        int maxChunksPerTick;
        boolean globalPacketCompression;
        int globalCompressionLevel;
        int globalCompressionThreshold;
        List<String> compressionBlacklist;
        boolean useContextCompression;
        boolean magiclessZstd;
        boolean enablePacketAggregation;
        int aggregationMinBatchSize;
        long aggregationMaxWaitTimeMs;
        int aggregationMaxSize;
        boolean enableCompactHeader;
        int serverChunkPushThreads;
        boolean metricsEnabled;
        boolean dynamicThreadPoolEnabled;
        int minPushThreads;
        int maxPushThreads;

        boolean requireClientMod;
        boolean autoDowngradeOnError;

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
            var net = c.network();
            var storage = c.storage();
            var compat = c.compat();
            var debug = c.debug();

            d.cacheEnabled = cache.enabled();
            d.cacheMaxSizeMb = cache.maxSizeMb();
            d.cacheMaxAgeDays = cache.maxAgeDays();
            d.cacheHotScoreThreshold = cache.hotScoreThreshold();
            d.cacheRecencyWeight = cache.recencyWeight();
            d.cacheFrequencyWeight = cache.frequencyWeight();
            d.cacheCleanupIntervalTicks = cache.cleanupIntervalTicks();
            d.cacheTargetCacheSizeMb = cache.targetCacheSizeMb();
            d.cacheMinCleanupBatchSize = cache.minCleanupBatchSize();
            d.cacheBloomFilterEnabled = cache.bloomFilterEnabled();
            d.cacheBloomFilterExpectedInsertions = cache.bloomFilterExpectedInsertions();
            d.cacheBloomFilterFpp = cache.bloomFilterFpp();
            d.cacheViewDistanceExtensionEnabled = cache.viewDistanceExtensionEnabled();
            d.cacheMaxRenderDistance = cache.maxRenderDistance();
            d.cacheOvdUnloadDelaySecs = cache.ovdUnloadDelaySecs();
            d.cacheSectionDeltaEnabled = cache.sectionDeltaEnabled();
            d.cacheJoinBoostEnabled = cache.joinBoostEnabled();
            d.cacheEntitySnapshotsEnabled = cache.entitySnapshotsEnabled();

            d.clientChunkLoadThreads = net.clientChunkLoadThreads();
            d.lightStripEnabled = net.lightStripEnabled();
            d.backgroundThreads = net.backgroundThreads();
            d.maxChunksPerFrame = net.maxChunksPerFrame();
            d.maxCallbacksPerFrame = net.maxCallbacksPerFrame();
            d.mainThreadChunkBudgetMs = net.mainThreadChunkBudgetMs();

            d.storageEnabled = storage.enabled();
            d.storageMode = storage.mode();
            d.storageZstdLevel = storage.zstdLevel();

            d.networkEnabled = net.enabled();
            d.compressionLevel = net.compressionLevel();
            d.maxChunksPerTick = net.maxChunksPerTick();
            d.globalPacketCompression = net.globalPacketCompression();
            d.globalCompressionLevel = net.globalCompressionLevel();
            d.globalCompressionThreshold = net.globalCompressionThreshold();
            d.compressionBlacklist = new ArrayList<>(net.compressionBlacklist());
            d.useContextCompression = net.useContextCompression();
            d.magiclessZstd = net.magiclessZstd();
            d.enablePacketAggregation = net.enablePacketAggregation();
            d.aggregationMinBatchSize = net.aggregationMinBatchSize();
            d.aggregationMaxWaitTimeMs = net.aggregationMaxWaitTimeMs();
            d.aggregationMaxSize = net.aggregationMaxSize();
            d.enableCompactHeader = net.enableCompactHeader();
            d.serverChunkPushThreads = net.serverChunkPushThreads();
            d.metricsEnabled = net.metricsEnabled();
            d.dynamicThreadPoolEnabled = net.dynamicThreadPoolEnabled();
            d.minPushThreads = net.minPushThreads();
            d.maxPushThreads = net.maxPushThreads();

            d.requireClientMod = compat.requireClientMod();
            d.autoDowngradeOnError = compat.autoDowngradeOnError();

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
            Set<String> blacklist = Set.copyOf(new LinkedHashSet<>(compressionBlacklist));
            return new HassiumConfig(
                    new HassiumConfig.StorageConfig(storageEnabled, storageMode, storageZstdLevel),
                    new HassiumConfig.ClientCacheConfig(
                            cacheEnabled, cacheMaxSizeMb, cacheMaxAgeDays,
                            cacheHotScoreThreshold, cacheRecencyWeight, cacheFrequencyWeight,
                            cacheCleanupIntervalTicks, cacheTargetCacheSizeMb, cacheMinCleanupBatchSize,
                            cacheBloomFilterEnabled, cacheBloomFilterExpectedInsertions, cacheBloomFilterFpp,
                            cacheViewDistanceExtensionEnabled, cacheMaxRenderDistance, cacheOvdUnloadDelaySecs,
                            cacheSectionDeltaEnabled, cacheJoinBoostEnabled, cacheEntitySnapshotsEnabled
                    ),
                    new HassiumConfig.NetworkConfig(
                            networkEnabled, compressionLevel, maxChunksPerTick,
                            globalPacketCompression, globalCompressionLevel, globalCompressionThreshold,
                            blacklist, useContextCompression, magiclessZstd, enablePacketAggregation,
                            aggregationMinBatchSize, aggregationMaxWaitTimeMs, aggregationMaxSize,
                            enableCompactHeader, serverChunkPushThreads, clientChunkLoadThreads,
                            lightStripEnabled, backgroundThreads, maxChunksPerFrame, maxCallbacksPerFrame,
                            metricsEnabled, mainThreadChunkBudgetMs,
                            dynamicThreadPoolEnabled, minPushThreads, maxPushThreads
                    ),
                    new HassiumConfig.CompatConfig(requireClientMod, autoDowngradeOnError),
                    new HassiumConfig.DebugConfig(
                            metadataLogging, dispatcherLogging, asyncLogging, compressionLogging,
                            chunkApplyLogging, networkLogging, cacheLogging
                    )
            );
        }
    }
}
