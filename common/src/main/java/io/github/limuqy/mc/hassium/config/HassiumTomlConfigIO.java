package io.github.limuqy.mc.hassium.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.io.WritingMode;
import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.platform.Services;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Fabric 自管 toml 读写（键路径对齐 {@link HassiumConfigSpec}，兼容旧 FCAP 生成文件）。
 * <p>
 * CLIENT（仅物理客户端）：{@code hassium/hassium-client.toml}（clientCache + 客户端 network）<br>
 * COMMON（客户端与专用服）：{@code hassium/hassium-common.toml}（storage / network / compat / debug）
 */
public final class HassiumTomlConfigIO {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/Config");

    private HassiumTomlConfigIO() {
    }

    public static Path clientPath() {
        return Services.PLATFORM.getConfigDirectory().resolve(Constants.CONFIG_CLIENT_FILE);
    }

    public static Path commonPath() {
        return Services.PLATFORM.getConfigDirectory().resolve(Constants.CONFIG_COMMON_FILE);
    }

    /**
     * 按物理端加载：专用服只读写 common；物理客户端读写 client + common。
     * 缺文件则写入默认；损坏时回退默认并打 warn。
     */
    public static HassiumConfig load() {
        try {
            boolean physicalClient = Services.PLATFORM.isPhysicalClient();
            Path common = commonPath();
            Files.createDirectories(common.getParent());

            HassiumConfig.ClientCacheConfig cache = HassiumConfig.ClientCacheConfig.DEFAULT;
            ClientNet clientNet = ClientNet.DEFAULT;
            HassiumConfig.StorageConfig storage = HassiumConfig.StorageConfig.DEFAULT;
            CommonNet commonNet = CommonNet.DEFAULT;
            HassiumConfig.CompatConfig compat = HassiumConfig.CompatConfig.DEFAULT;
            HassiumConfig.DebugConfig debug = HassiumConfig.DebugConfig.DEFAULT;

            if (physicalClient) {
                Path client = clientPath();
                if (Files.isRegularFile(client)) {
                    try (CommentedFileConfig cfg = open(client)) {
                        cfg.load();
                        cache = readClientCache(cfg);
                        clientNet = readClientNetwork(cfg);
                    } catch (Exception e) {
                        LOGGER.warn("Hassium: 读取 {} 失败，使用默认客户端配置", client, e);
                    }
                } else {
                    writeClient(client, cache, clientNet);
                }
            }

            if (Files.isRegularFile(common)) {
                try (CommentedFileConfig cfg = open(common)) {
                    cfg.load();
                    storage = readStorage(cfg);
                    commonNet = readCommonNetwork(cfg);
                    compat = readCompat(cfg);
                    debug = readDebug(cfg);
                } catch (Exception e) {
                    LOGGER.warn("Hassium: 读取 {} 失败，使用默认 common 配置", common, e);
                }
            } else {
                writeCommon(common, storage, commonNet, compat, debug);
            }

            return merge(storage, cache, clientNet, commonNet, compat, debug);
        } catch (Exception e) {
            LOGGER.error("Hassium: Toml 配置加载失败，使用内置默认", e);
            return HassiumConfig.DEFAULT;
        }
    }

    /**
     * 按物理端保存：专用服只写 common；物理客户端写 client + common。
     */
    public static void save(HassiumConfig config) {
        try {
            boolean physicalClient = Services.PLATFORM.isPhysicalClient();
            Path common = commonPath();
            Files.createDirectories(common.getParent());
            if (physicalClient) {
                writeClient(clientPath(), config.clientCache(), ClientNet.from(config.network()));
            }
            writeCommon(common, config.storage(), CommonNet.from(config.network()),
                    config.compat(), config.debug());
            LOGGER.info("Hassium: Toml 配置已保存");
        } catch (Exception e) {
            LOGGER.error("Hassium: Toml 配置保存失败", e);
        }
    }

    private static CommentedFileConfig open(Path path) {
        return CommentedFileConfig.builder(path)
                .sync()
                .writingMode(WritingMode.REPLACE)
                .build();
    }

    private static void writeClient(Path path, HassiumConfig.ClientCacheConfig cache, ClientNet net) {
        try (CommentedFileConfig cfg = open(path)) {
            if (Files.isRegularFile(path)) {
                cfg.load();
            }
            writeClientCache(cfg, cache);
            writeClientNetwork(cfg, net);
            cfg.save();
        }
    }

    private static void writeCommon(
            Path path,
            HassiumConfig.StorageConfig storage,
            CommonNet net,
            HassiumConfig.CompatConfig compat,
            HassiumConfig.DebugConfig debug
    ) {
        try (CommentedFileConfig cfg = open(path)) {
            if (Files.isRegularFile(path)) {
                cfg.load();
            }
            writeStorage(cfg, storage);
            writeCommonNetwork(cfg, net);
            writeCompat(cfg, compat);
            writeDebug(cfg, debug);
            cfg.save();
        }
    }

    private static HassiumConfig merge(
            HassiumConfig.StorageConfig storage,
            HassiumConfig.ClientCacheConfig cache,
            ClientNet clientNet,
            CommonNet commonNet,
            HassiumConfig.CompatConfig compat,
            HassiumConfig.DebugConfig debug
    ) {
        return new HassiumConfig(
                storage,
                cache,
                new HassiumConfig.NetworkConfig(
                        commonNet.enabled,
                        commonNet.compressionLevel,
                        commonNet.maxChunksPerTick,
                        commonNet.globalPacketCompression,
                        commonNet.globalCompressionLevel,
                        commonNet.globalCompressionThreshold,
                        commonNet.compressionBlacklist,
                        commonNet.useContextCompression,
                        commonNet.magiclessZstd,
                        commonNet.enablePacketAggregation,
                        commonNet.aggregationMinBatchSize,
                        commonNet.aggregationMaxWaitTimeMs,
                        commonNet.aggregationMaxSize,
                        commonNet.enableCompactHeader,
                        commonNet.serverChunkPushThreads,
                        clientNet.clientChunkLoadThreads,
                        clientNet.lightStripEnabled,
                        clientNet.backgroundThreads,
                        clientNet.maxChunksPerFrame,
                        clientNet.maxCallbacksPerFrame,
                        commonNet.metricsEnabled,
                        clientNet.mainThreadChunkBudgetMs,
                        clientNet.maxLightRecomputePerFrame,
                        commonNet.dynamicThreadPoolEnabled,
                        commonNet.minPushThreads,
                        commonNet.maxPushThreads
                ),
                compat,
                debug
        );
    }

    // --- CLIENT ---

    private static HassiumConfig.ClientCacheConfig readClientCache(CommentedConfig cfg) {
        var d = HassiumConfig.ClientCacheConfig.DEFAULT;
        return new HassiumConfig.ClientCacheConfig(
                getBool(cfg, "clientCache.enabled", d.enabled()),
                getInt(cfg, "clientCache.maxSizeMb", d.maxSizeMb()),
                getInt(cfg, "clientCache.maxAgeDays", d.maxAgeDays()),
                getDouble(cfg, "clientCache.hotScoreThreshold", d.hotScoreThreshold()),
                getDouble(cfg, "clientCache.recencyWeight", d.recencyWeight()),
                getDouble(cfg, "clientCache.frequencyWeight", d.frequencyWeight()),
                getInt(cfg, "clientCache.cleanupIntervalTicks", d.cleanupIntervalTicks()),
                getInt(cfg, "clientCache.targetCacheSizeMb", d.targetCacheSizeMb()),
                getInt(cfg, "clientCache.minCleanupBatchSize", d.minCleanupBatchSize()),
                getBool(cfg, "clientCache.bloomFilterEnabled", d.bloomFilterEnabled()),
                getInt(cfg, "clientCache.bloomFilterExpectedInsertions", d.bloomFilterExpectedInsertions()),
                getDouble(cfg, "clientCache.bloomFilterFpp", d.bloomFilterFpp()),
                getBool(cfg, "clientCache.viewDistanceExtensionEnabled", d.viewDistanceExtensionEnabled()),
                getInt(cfg, "clientCache.maxRenderDistance", d.maxRenderDistance()),
                getInt(cfg, "clientCache.ovdUnloadDelaySecs", d.ovdUnloadDelaySecs())
        );
    }

    private static void writeClientCache(CommentedConfig cfg, HassiumConfig.ClientCacheConfig c) {
        set(cfg, "clientCache.enabled", c.enabled(), "是否启用客户端缓存");
        set(cfg, "clientCache.maxSizeMb", c.maxSizeMb(), "缓存最大容量（MB）");
        set(cfg, "clientCache.maxAgeDays", c.maxAgeDays(), "缓存过期天数");
        set(cfg, "clientCache.hotScoreThreshold", c.hotScoreThreshold(), "热点分数阈值");
        set(cfg, "clientCache.recencyWeight", c.recencyWeight(), "最近访问权重");
        set(cfg, "clientCache.frequencyWeight", c.frequencyWeight(), "访问频率权重");
        set(cfg, "clientCache.cleanupIntervalTicks", c.cleanupIntervalTicks(), "清理检查间隔（刻）");
        set(cfg, "clientCache.targetCacheSizeMb", c.targetCacheSizeMb(), "目标缓存大小（MB；0=自动）");
        set(cfg, "clientCache.minCleanupBatchSize", c.minCleanupBatchSize(), "每次最少清理区块数");
        set(cfg, "clientCache.bloomFilterEnabled", c.bloomFilterEnabled(), "是否启用 Bloom Filter");
        set(cfg, "clientCache.bloomFilterExpectedInsertions", c.bloomFilterExpectedInsertions(), "Bloom 预期元素数");
        set(cfg, "clientCache.bloomFilterFpp", c.bloomFilterFpp(), "Bloom 期望假阳性率");
        set(cfg, "clientCache.viewDistanceExtensionEnabled", c.viewDistanceExtensionEnabled(), "是否启用视距外显示（OVD）");
        set(cfg, "clientCache.maxRenderDistance", c.maxRenderDistance(), "渲染距离上限（Fog/内存约束）");
        set(cfg, "clientCache.ovdUnloadDelaySecs", c.ovdUnloadDelaySecs(), "离开环带后延迟卸载秒数");
    }

    private static ClientNet readClientNetwork(CommentedConfig cfg) {
        var d = ClientNet.DEFAULT;
        return new ClientNet(
                getInt(cfg, "network.clientChunkLoadThreads", d.clientChunkLoadThreads),
                getBool(cfg, "network.lightStripEnabled", d.lightStripEnabled),
                getInt(cfg, "network.backgroundThreads", d.backgroundThreads),
                getInt(cfg, "network.maxChunksPerFrame", d.maxChunksPerFrame),
                getInt(cfg, "network.maxCallbacksPerFrame", d.maxCallbacksPerFrame),
                getInt(cfg, "network.mainThreadChunkBudgetMs", d.mainThreadChunkBudgetMs),
                getInt(cfg, "network.maxLightRecomputePerFrame", d.maxLightRecomputePerFrame)
        );
    }

    private static void writeClientNetwork(CommentedConfig cfg, ClientNet n) {
        set(cfg, "network.clientChunkLoadThreads", n.clientChunkLoadThreads, "客户端区块加载线程数");
        set(cfg, "network.lightStripEnabled", n.lightStripEnabled, "是否启用光照剥离");
        set(cfg, "network.backgroundThreads", n.backgroundThreads, "客户端后台线程池大小");
        set(cfg, "network.maxChunksPerFrame", n.maxChunksPerFrame, "每帧应用缓存区块硬顶");
        set(cfg, "network.maxCallbacksPerFrame", n.maxCallbacksPerFrame, "每帧异步回调硬顶");
        set(cfg, "network.mainThreadChunkBudgetMs", n.mainThreadChunkBudgetMs, "主线程 apply 预算（ms）");
        set(cfg, "network.maxLightRecomputePerFrame", n.maxLightRecomputePerFrame, "每帧最多重算光照区块数");
    }

    // --- COMMON ---

    private static HassiumConfig.StorageConfig readStorage(CommentedConfig cfg) {
        var d = HassiumConfig.StorageConfig.DEFAULT;
        return new HassiumConfig.StorageConfig(
                getBool(cfg, "storage.enabled", d.enabled()),
                getString(cfg, "storage.mode", d.mode()),
                getInt(cfg, "storage.zstdLevel", d.zstdLevel())
        );
    }

    private static void writeStorage(CommentedConfig cfg, HassiumConfig.StorageConfig s) {
        set(cfg, "storage.enabled", s.enabled(), "是否启用存档压缩（启用前请备份）");
        set(cfg, "storage.mode", s.mode(), "存储模式：mirror / readonly_vanilla / hassium_only");
        set(cfg, "storage.zstdLevel", s.zstdLevel(), "存储 ZSTD 压缩等级");
    }

    private static CommonNet readCommonNetwork(CommentedConfig cfg) {
        var d = CommonNet.DEFAULT;
        return new CommonNet(
                getBool(cfg, "network.enabled", d.enabled),
                getInt(cfg, "network.compressionLevel", d.compressionLevel),
                getInt(cfg, "network.maxChunksPerTick", d.maxChunksPerTick),
                getBool(cfg, "network.globalPacketCompression", d.globalPacketCompression),
                getInt(cfg, "network.globalCompressionLevel", d.globalCompressionLevel),
                getInt(cfg, "network.globalCompressionThreshold", d.globalCompressionThreshold),
                getStringSet(cfg, "network.compressionBlacklist", d.compressionBlacklist),
                getBool(cfg, "network.useContextCompression", d.useContextCompression),
                getBool(cfg, "network.magiclessZstd", d.magiclessZstd),
                getBool(cfg, "network.enablePacketAggregation", d.enablePacketAggregation),
                getInt(cfg, "network.aggregationMinBatchSize", d.aggregationMinBatchSize),
                getLong(cfg, "network.aggregationMaxWaitTimeMs", d.aggregationMaxWaitTimeMs),
                getInt(cfg, "network.aggregationMaxSize", d.aggregationMaxSize),
                getBool(cfg, "network.enableCompactHeader", d.enableCompactHeader),
                getInt(cfg, "network.serverChunkPushThreads", d.serverChunkPushThreads),
                getBool(cfg, "network.metricsEnabled", d.metricsEnabled),
                getBool(cfg, "network.dynamicThreadPoolEnabled", d.dynamicThreadPoolEnabled),
                getInt(cfg, "network.minPushThreads", d.minPushThreads),
                getInt(cfg, "network.maxPushThreads", d.maxPushThreads)
        );
    }

    private static void writeCommonNetwork(CommentedConfig cfg, CommonNet n) {
        set(cfg, "network.enabled", n.enabled, "是否启用 Hassium 自定义通道");
        set(cfg, "network.compressionLevel", n.compressionLevel, "自定义通道 ZSTD 等级");
        set(cfg, "network.maxChunksPerTick", n.maxChunksPerTick, "每玩家每 tick 推送上限");
        set(cfg, "network.globalPacketCompression", n.globalPacketCompression, "是否启用全局 ZSTD");
        set(cfg, "network.globalCompressionLevel", n.globalCompressionLevel, "全局压缩等级");
        set(cfg, "network.globalCompressionThreshold", n.globalCompressionThreshold, "全局压缩阈值（字节）");
        set(cfg, "network.compressionBlacklist", new ArrayList<>(n.compressionBlacklist), "压缩/聚合黑名单");
        set(cfg, "network.useContextCompression", n.useContextCompression, "是否使用上下文压缩");
        set(cfg, "network.magiclessZstd", n.magiclessZstd, "是否使用无 magic 的 ZSTD");
        set(cfg, "network.enablePacketAggregation", n.enablePacketAggregation, "是否启用包聚合");
        set(cfg, "network.aggregationMinBatchSize", n.aggregationMinBatchSize, "聚合最小批量");
        set(cfg, "network.aggregationMaxWaitTimeMs", (int) n.aggregationMaxWaitTimeMs, "聚合最大等待（ms）");
        set(cfg, "network.aggregationMaxSize", n.aggregationMaxSize, "聚合最大大小（字节）");
        set(cfg, "network.enableCompactHeader", n.enableCompactHeader, "是否启用紧凑包头");
        set(cfg, "network.serverChunkPushThreads", n.serverChunkPushThreads, "服务端推送线程数");
        set(cfg, "network.metricsEnabled", n.metricsEnabled, "是否启用指标收集");
        set(cfg, "network.dynamicThreadPoolEnabled", n.dynamicThreadPoolEnabled, "是否动态调整推送线程");
        set(cfg, "network.minPushThreads", n.minPushThreads, "动态池最小线程数");
        set(cfg, "network.maxPushThreads", n.maxPushThreads, "动态池最大线程数");
    }

    private static HassiumConfig.CompatConfig readCompat(CommentedConfig cfg) {
        var d = HassiumConfig.CompatConfig.DEFAULT;
        return new HassiumConfig.CompatConfig(
                getBool(cfg, "compat.requireClientMod", d.requireClientMod()),
                getBool(cfg, "compat.autoDowngradeOnError", d.autoDowngradeOnError())
        );
    }

    private static void writeCompat(CommentedConfig cfg, HassiumConfig.CompatConfig c) {
        set(cfg, "compat.requireClientMod", c.requireClientMod(), "是否强制客户端安装 Hassium");
        set(cfg, "compat.autoDowngradeOnError", c.autoDowngradeOnError(), "出错时是否自动降级");
    }

    private static HassiumConfig.DebugConfig readDebug(CommentedConfig cfg) {
        var d = HassiumConfig.DebugConfig.DEFAULT;
        return new HassiumConfig.DebugConfig(
                getBool(cfg, "debug.metadataLogging", d.metadataLogging()),
                getBool(cfg, "debug.dispatcherLogging", d.dispatcherLogging()),
                getBool(cfg, "debug.asyncLogging", d.asyncLogging()),
                getBool(cfg, "debug.compressionLogging", d.compressionLogging()),
                getBool(cfg, "debug.chunkApplyLogging", d.chunkApplyLogging()),
                getBool(cfg, "debug.networkLogging", d.networkLogging()),
                getBool(cfg, "debug.cacheLogging", d.cacheLogging())
        );
    }

    private static void writeDebug(CommentedConfig cfg, HassiumConfig.DebugConfig d) {
        set(cfg, "debug.metadataLogging", d.metadataLogging(), "元数据调试日志");
        set(cfg, "debug.dispatcherLogging", d.dispatcherLogging(), "主线程调度调试日志");
        set(cfg, "debug.asyncLogging", d.asyncLogging(), "异步任务调试日志");
        set(cfg, "debug.compressionLogging", d.compressionLogging(), "压缩调试日志");
        set(cfg, "debug.chunkApplyLogging", d.chunkApplyLogging(), "区块 apply 调试日志");
        set(cfg, "debug.networkLogging", d.networkLogging(), "网络调试日志");
        set(cfg, "debug.cacheLogging", d.cacheLogging(), "缓存调试日志");
    }

    // --- helpers ---

    private static void set(CommentedConfig cfg, String path, Object value, String comment) {
        cfg.setComment(path, comment);
        cfg.set(path, value);
    }

    private static boolean getBool(CommentedConfig cfg, String path, boolean def) {
        Object v = cfg.get(path);
        if (v instanceof Boolean b) {
            return b;
        }
        return def;
    }

    private static int getInt(CommentedConfig cfg, String path, int def) {
        Object v = cfg.get(path);
        if (v instanceof Number n) {
            return n.intValue();
        }
        return def;
    }

    private static long getLong(CommentedConfig cfg, String path, long def) {
        Object v = cfg.get(path);
        if (v instanceof Number n) {
            return n.longValue();
        }
        return def;
    }

    private static double getDouble(CommentedConfig cfg, String path, double def) {
        Object v = cfg.get(path);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        return def;
    }

    private static String getString(CommentedConfig cfg, String path, String def) {
        Object v = cfg.get(path);
        if (v instanceof String s) {
            return s;
        }
        return def;
    }

    private static Set<String> getStringSet(CommentedConfig cfg, String path, Set<String> def) {
        Object v = cfg.get(path);
        if (v instanceof List<?> list) {
            LinkedHashSet<String> out = new LinkedHashSet<>();
            for (Object o : list) {
                if (o instanceof String s) {
                    out.add(s);
                }
            }
            return Set.copyOf(out);
        }
        return def;
    }

    private record ClientNet(
            int clientChunkLoadThreads,
            boolean lightStripEnabled,
            int backgroundThreads,
            int maxChunksPerFrame,
            int maxCallbacksPerFrame,
            int mainThreadChunkBudgetMs,
            int maxLightRecomputePerFrame
    ) {
        static final ClientNet DEFAULT = from(HassiumConfig.NetworkConfig.DEFAULT);

        static ClientNet from(HassiumConfig.NetworkConfig n) {
            return new ClientNet(
                    n.clientChunkLoadThreads(),
                    n.lightStripEnabled(),
                    n.backgroundThreads(),
                    n.maxChunksPerFrame(),
                    n.maxCallbacksPerFrame(),
                    n.mainThreadChunkBudgetMs(),
                    n.maxLightRecomputePerFrame()
            );
        }
    }

    private record CommonNet(
            boolean enabled,
            int compressionLevel,
            int maxChunksPerTick,
            boolean globalPacketCompression,
            int globalCompressionLevel,
            int globalCompressionThreshold,
            Set<String> compressionBlacklist,
            boolean useContextCompression,
            boolean magiclessZstd,
            boolean enablePacketAggregation,
            int aggregationMinBatchSize,
            long aggregationMaxWaitTimeMs,
            int aggregationMaxSize,
            boolean enableCompactHeader,
            int serverChunkPushThreads,
            boolean metricsEnabled,
            boolean dynamicThreadPoolEnabled,
            int minPushThreads,
            int maxPushThreads
    ) {
        static final CommonNet DEFAULT = from(HassiumConfig.NetworkConfig.DEFAULT);

        static CommonNet from(HassiumConfig.NetworkConfig n) {
            return new CommonNet(
                    n.enabled(),
                    n.compressionLevel(),
                    n.maxChunksPerTick(),
                    n.globalPacketCompression(),
                    n.globalCompressionLevel(),
                    n.globalCompressionThreshold(),
                    n.compressionBlacklist(),
                    n.useContextCompression(),
                    n.magiclessZstd(),
                    n.enablePacketAggregation(),
                    n.aggregationMinBatchSize(),
                    n.aggregationMaxWaitTimeMs(),
                    n.aggregationMaxSize(),
                    n.enableCompactHeader(),
                    n.serverChunkPushThreads(),
                    n.metricsEnabled(),
                    n.dynamicThreadPoolEnabled(),
                    n.minPushThreads(),
                    n.maxPushThreads()
            );
        }
    }
}
