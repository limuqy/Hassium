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
 * Fabric 自管 toml 读写（二文件模型）。
 * <p>
 * 物理客户端：{@code hassium/hassium-client.toml}（clientCache + clientNetwork + debug）<br>
 * 专用服：{@code hassium/hassium-server.toml}（storage + serverNetwork + compat + debug）
 */
public final class FabricTomlConfigIO {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/Config");

    private FabricTomlConfigIO() {
    }

    public static Path clientPath() {
        return Services.PLATFORM.getConfigDirectory().resolve(Constants.CONFIG_CLIENT_FILE);
    }

    public static Path serverPath() {
        return Services.PLATFORM.getConfigDirectory().resolve(Constants.CONFIG_SERVER_FILE);
    }

    /**
     * 按物理端加载：物理客户端读 client.toml；专用服读 server.toml。
     * 缺文件则写入默认；损坏时回退默认并打 warn。
     */
    public static HassiumConfig load() {
        try {
            boolean physicalClient = Services.PLATFORM.isPhysicalClient();
            if (physicalClient) {
                return loadClient();
            } else {
                return loadServer();
            }
        } catch (Exception e) {
            LOGGER.error("Hassium: Toml 配置加载失败，使用内置默认", e);
            return HassiumConfig.DEFAULT;
        }
    }

    /** Loads a complete loader-neutral snapshot from the physical client/server TOML file. */
    public static ConfigValues loadValues() {
        return loadValues(Services.PLATFORM.isPhysicalClient() ? ConfigScope.CLIENT : ConfigScope.SERVER);
    }

    /** Loads the snapshot entries for {@code scope} from the matching client/server TOML file. */
    public static ConfigValues loadValues(ConfigScope scope) {
        ConfigValues values = ConfigValues.defaults(ConfigSchema.entries());
        try {
            Path path = scope == ConfigScope.CLIENT ? clientPath() : serverPath();
            return readValuesFile(path, scope, values);
        } catch (Exception e) {
            LOGGER.error("Hassium: TOML 配置快照加载失败，使用默认值", e);
            return values;
        }
    }

    /** Saves the supplied snapshot to the TOML file for the current physical side. */
    public static void saveValues(ConfigValues values) {
        saveValues(values, Services.PLATFORM.isPhysicalClient() ? ConfigScope.CLIENT : ConfigScope.SERVER);
    }

    /** Saves the snapshot entries for {@code scope} to the matching client/server TOML file. */
    public static void saveValues(ConfigValues values, ConfigScope scope) {
        Path path = scope == ConfigScope.CLIENT ? clientPath() : serverPath();
        try {
            Files.createDirectories(path.getParent());
            try (CommentedFileConfig cfg = open(path)) {
                for (ConfigEntry<?> entry : entries(scope)) {
                    writeSchemaValue(cfg, entry, values.get(entry.key()));
                }
                cfg.save();
            }
            LOGGER.info("Hassium: Toml 配置已保存 ({})", scope);
        } catch (Exception e) {
            LOGGER.error("Hassium: Toml 配置保存失败", e);
        }
    }

    private static ConfigValues readValuesFile(Path path, ConfigScope scope, ConfigValues values)
            throws java.io.IOException {
        Files.createDirectories(path.getParent());
        if (!Files.isRegularFile(path)) {
            try (CommentedFileConfig cfg = open(path)) {
                for (ConfigEntry<?> entry : entries(scope)) {
                    writeSchemaValue(cfg, entry, entry.defaultValue());
                }
                cfg.save();
            }
            return values;
        }
        try (CommentedFileConfig cfg = open(path)) {
            cfg.load();
            for (ConfigEntry<?> entry : entries(scope)) {
                Object value = readSchemaValue(cfg, entry);
                if (value != null) {
                    values = withSchemaValue(values, entry, value);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Hassium: 读取 {} 失败，使用默认配置", path, e);
        }
        return values;
    }

    private static List<ConfigEntry<?>> entries(ConfigScope scope) {
        return scope == ConfigScope.CLIENT ? ConfigSchema.clientEntries() : ConfigSchema.serverEntries();
    }

    private static Object readSchemaValue(CommentedConfig cfg, ConfigEntry<?> entry) {
        if (entry.key() == ConfigSchema.NETWORK_CONTROL_ENDPOINTS) {
            return readReachableEndpoints(cfg, entry.path(), entry.path()).stream()
                    .map(DataPlaneEndpointConfig::encodeReachable).toList();
        }
        if (entry.key() == ConfigSchema.DATAPLANE_UDP_LISTENERS) {
            return readUdpListeners(cfg).stream().map(DataPlaneEndpointConfig::encodeListener).toList();
        }
        return coerceSchemaValue(cfg.get(entry.path()), entry);
    }

    private static Object coerceSchemaValue(Object raw, ConfigEntry<?> entry) {
        if (raw == null) return null;
        Object value;
        switch (entry.type()) {
            case BOOLEAN -> value = raw instanceof Boolean ? raw : null;
            case STRING -> value = raw instanceof String ? raw : null;
            case STRING_LIST -> {
                if (!(raw instanceof List<?> list)) return null;
                List<String> strings = new ArrayList<>();
                for (Object item : list) {
                    if (!(item instanceof String string)) return null;
                    strings.add(string);
                }
                value = List.copyOf(strings);
            }
            case INT -> value = raw instanceof Number number ? number.intValue() : null;
            case LONG -> value = raw instanceof Number number ? number.longValue() : null;
            case DOUBLE -> value = raw instanceof Number number ? number.doubleValue() : null;
            default -> throw new IllegalStateException("Unsupported configuration type: " + entry.type());
        }
        if (value == null || !inRange(value, entry)) return null;
        return value;
    }

    private static boolean inRange(Object value, ConfigEntry<?> entry) {
        if (!(value instanceof Number number) || entry.min() == null || entry.max() == null) return true;
        double numeric = number.doubleValue();
        return numeric >= entry.min().doubleValue() && numeric <= entry.max().doubleValue();
    }

    @SuppressWarnings("unchecked")
    private static <T> ConfigValues withSchemaValue(ConfigValues values, ConfigEntry<T> entry, Object value) {
        return values.with(entry.key(), (T) value);
    }

    @SuppressWarnings("unchecked")
    private static void writeSchemaValue(CommentedConfig cfg, ConfigEntry<?> entry, Object value) {
        if (entry.key() == ConfigSchema.NETWORK_CONTROL_ENDPOINTS) {
            writeReachableEndpoints(cfg, entry.path(), ((List<String>) value).stream()
                    .map(DataPlaneEndpointConfig::decodeReachable).toList(), entry.comment());
        } else if (entry.key() == ConfigSchema.DATAPLANE_UDP_LISTENERS) {
            cfg.remove(entry.path());
            List<CommentedConfig> listeners = new ArrayList<>();
            for (String encoded : (List<String>) value) {
                HassiumConfig.UdpListenerConfig listener = DataPlaneEndpointConfig.decodeListener(encoded);
                CommentedConfig table = cfg.createSubConfig();
                table.set("bindHost", listener.bindHost());
                table.set("bindPort", listener.bindPort());
                table.set("weight", listener.weight());
                writeReachableEndpoints(table, "reachableEndpoints", listener.reachableEndpoints(), entry.comment());
                listeners.add(table);
            }
            cfg.setComment(entry.path(), entry.comment());
            cfg.set(entry.path(), listeners);
        } else {
            set(cfg, entry.path(), value, entry.comment());
        }
    }


    private static HassiumConfig loadClient() throws java.io.IOException {
        HassiumConfig.ClientCacheConfig cache = HassiumConfig.ClientCacheConfig.DEFAULT;
        HassiumConfig.ClientNetworkConfig clientNet = HassiumConfig.ClientNetworkConfig.DEFAULT;
        HassiumConfig.DebugConfig debug = HassiumConfig.DebugConfig.DEFAULT;

        Path client = clientPath();
        Files.createDirectories(client.getParent());

        if (Files.isRegularFile(client)) {
            try (CommentedFileConfig cfg = open(client)) {
                cfg.load();
                cache = readClientCache(cfg);
                clientNet = readClientNetwork(cfg);
                debug = readDebug(cfg);
            } catch (Exception e) {
                LOGGER.warn("Hassium: 读取 {} 失败，使用默认客户端配置", client, e);
            }
        } else {
            writeClient(client, cache, clientNet, debug);
        }

        return new HassiumConfig(
                HassiumConfig.StorageConfig.DEFAULT,
                cache,
                clientNet,
                HassiumConfig.ServerNetworkConfig.DEFAULT,
                HassiumConfig.CompatConfig.DEFAULT,
                debug
        );
    }

    private static HassiumConfig loadServer() throws java.io.IOException {
        return loadServerFile(serverPath());
    }
    static HassiumConfig loadServer(Path configRoot) throws java.io.IOException {
        return loadServerFile(configRoot.resolve(Constants.CONFIG_SERVER_FILE));
    }

    static void saveServer(Path configRoot, HassiumConfig config) {
        try {
            Files.createDirectories(configRoot.resolve(Constants.CONFIG_SERVER_FILE).getParent());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("无法创建临时服务端配置目录", e);
        }
        writeServer(configRoot.resolve(Constants.CONFIG_SERVER_FILE), config.storage(), config.serverNetwork(),
                config.compat(), config.debug());
    }

    private static HassiumConfig loadServerFile(Path server) throws java.io.IOException {
        HassiumConfig.StorageConfig storage = HassiumConfig.StorageConfig.DEFAULT;
        HassiumConfig.ServerNetworkConfig serverNet = HassiumConfig.ServerNetworkConfig.DEFAULT;
        HassiumConfig.CompatConfig compat = HassiumConfig.CompatConfig.DEFAULT;
        HassiumConfig.DebugConfig debug = HassiumConfig.DebugConfig.DEFAULT;

        Files.createDirectories(server.getParent());
        if (Files.isRegularFile(server)) {
            try (CommentedFileConfig cfg = open(server)) {
                cfg.load();
                storage = readStorage(cfg);
                serverNet = readServerNetwork(cfg);
                compat = readCompat(cfg);
                debug = readDebug(cfg);
            } catch (Exception e) {
                LOGGER.warn("Hassium: 读取 {} 失败，使用默认服务端配置", server, e);
            }
        } else {
            writeServer(server, storage, serverNet, compat, debug);
        }
        return new HassiumConfig(storage, HassiumConfig.ClientCacheConfig.DEFAULT,
                HassiumConfig.ClientNetworkConfig.DEFAULT, serverNet, compat, debug);
    }

    /**
     * 按物理端保存：物理客户端写 client.toml；专用服写 server.toml。
     */
    public static void save(HassiumConfig config) {
        try {
            boolean physicalClient = Services.PLATFORM.isPhysicalClient();
            Files.createDirectories(
                    physicalClient ? clientPath().getParent() : serverPath().getParent()
            );
            if (physicalClient) {
                writeClient(clientPath(), config.clientCache(), config.clientNetwork(), config.debug());
            } else {
                writeServer(serverPath(), config.storage(), config.serverNetwork(), config.compat(), config.debug());
            }
            LOGGER.info("Hassium: Toml 配置已保存");
        } catch (Exception e) {
            LOGGER.error("Hassium: Toml 配置保存失败", e);
        }
    }

    // --- IO helpers ---

    private static CommentedFileConfig open(Path path) {
        return CommentedFileConfig.builder(path)
                .sync()
                .preserveInsertionOrder()
                .writingMode(WritingMode.REPLACE)
                .build();
    }

    private static void writeClient(
            Path path,
            HassiumConfig.ClientCacheConfig cache,
            HassiumConfig.ClientNetworkConfig net,
            HassiumConfig.DebugConfig debug
    ) {
        try (CommentedFileConfig cfg = open(path)) {
            writeClientCache(cfg, cache);
            writeClientNetwork(cfg, net);
            writeDebug(cfg, debug);
            cfg.save();
        }
    }

    private static void writeServer(
            Path path,
            HassiumConfig.StorageConfig storage,
            HassiumConfig.ServerNetworkConfig net,
            HassiumConfig.CompatConfig compat,
            HassiumConfig.DebugConfig debug
    ) {
        try (CommentedFileConfig cfg = open(path)) {
            writeStorage(cfg, storage);
            writeServerNetwork(cfg, net);
            writeCompat(cfg, compat);
            writeDebug(cfg, debug);
            cfg.save();
        }
    }

    // --- CLIENT ---

    private static HassiumConfig.ClientCacheConfig readClientCache(CommentedConfig cfg) {
        var d = HassiumConfig.ClientCacheConfig.DEFAULT;
        return new HassiumConfig.ClientCacheConfig(
                getBool(cfg, "clientCache.enabled", d.enabled()),
                getInt(cfg, "clientCache.maxSizeMb", d.maxSizeMb()),
                getInt(cfg, "clientCache.cacheCompressionLevel", d.cacheCompressionLevel()),
                getDouble(cfg, "clientCache.hotScoreThreshold", d.hotScoreThreshold()),
                getDouble(cfg, "clientCache.recencyWeight", d.recencyWeight()),
                getDouble(cfg, "clientCache.frequencyWeight", d.frequencyWeight()),
                getInt(cfg, "clientCache.cleanupIntervalTicks", d.cleanupIntervalTicks()),
                getInt(cfg, "clientCache.targetCacheSizeMb", d.targetCacheSizeMb()),
                getInt(cfg, "clientCache.minCleanupBatchSize", d.minCleanupBatchSize()),
                getBool(cfg, "clientCache.viewDistanceExtensionEnabled", d.viewDistanceExtensionEnabled()),
                getInt(cfg, "clientCache.maxRenderDistance", d.maxRenderDistance()),
                getInt(cfg, "clientCache.ovdUnloadDelaySecs", d.ovdUnloadDelaySecs()),
                getBool(cfg, "clientCache.sectionDeltaEnabled", d.sectionDeltaEnabled()),
                getBool(cfg, "clientCache.joinBoostEnabled", d.joinBoostEnabled()),
                getBool(cfg, "clientCache.entitySnapshotsEnabled", d.entitySnapshotsEnabled()),
                getInt(cfg, "clientCache.loadThreads", d.loadThreads()),
                getBool(cfg, "clientCache.lightCacheEnabled", d.lightCacheEnabled()),
                getInt(cfg, "clientCache.maxChunksPerFrame", d.maxChunksPerFrame()),
                getInt(cfg, "clientCache.mainThreadChunkBudgetMs", d.mainThreadChunkBudgetMs()),
                getBool(cfg, "clientCache.parallelLightEngineEnabled", d.parallelLightEngineEnabled()),
                getInt(cfg, "clientCache.parallelLightEngineThreads", d.parallelLightEngineThreads())
        );
    }

    private static void writeClientCache(CommentedConfig cfg, HassiumConfig.ClientCacheConfig c) {
        set(cfg, "clientCache.enabled", c.enabled(), "是否启用客户端缓存");
        set(cfg, "clientCache.maxSizeMb", c.maxSizeMb(), "缓存最大容量（MB）");
        set(cfg, "clientCache.cacheCompressionLevel", c.cacheCompressionLevel(), "缓存压缩等级");
        set(cfg, "clientCache.hotScoreThreshold", c.hotScoreThreshold(), "热点分数阈值");
        set(cfg, "clientCache.recencyWeight", c.recencyWeight(), "最近访问权重");
        set(cfg, "clientCache.frequencyWeight", c.frequencyWeight(), "访问频率权重");
        set(cfg, "clientCache.cleanupIntervalTicks", c.cleanupIntervalTicks(), "清理检查间隔（刻）");
        set(cfg, "clientCache.targetCacheSizeMb", c.targetCacheSizeMb(), "目标缓存大小（MB；0=自动）");
        set(cfg, "clientCache.minCleanupBatchSize", c.minCleanupBatchSize(), "每次最少清理区块数");
        set(cfg, "clientCache.viewDistanceExtensionEnabled", c.viewDistanceExtensionEnabled(),
                "是否启用超视渲染（客户端 RD > 服务端视距时本地缓存回填环带）");
        set(cfg, "clientCache.maxRenderDistance", c.maxRenderDistance(), "超视渲染 / 有效 RD 上限（Fog/内存约束）");
        set(cfg, "clientCache.ovdUnloadDelaySecs", c.ovdUnloadDelaySecs(), "离开超视渲染环带后延迟卸载秒数");
        set(cfg, "clientCache.sectionDeltaEnabled", c.sectionDeltaEnabled(),
                "缓存过期时是否走分段增量（默认 true；依赖 clientCache.enabled）");
        set(cfg, "clientCache.joinBoostEnabled", c.joinBoostEnabled(),
                "进服后短时提高主线程预算加速加载（默认 true）");
        set(cfg, "clientCache.entitySnapshotsEnabled", c.entitySnapshotsEnabled(),
                "区块卸载时保存非玩家实体快照（默认 false）");
        set(cfg, "clientCache.loadThreads", c.loadThreads(), "客户端区块加载线程数");
        set(cfg, "clientCache.lightCacheEnabled", c.lightCacheEnabled(), "是否启用光照缓存");
        set(cfg, "clientCache.maxChunksPerFrame", c.maxChunksPerFrame(), "每帧应用缓存区块硬顶");
        set(cfg, "clientCache.mainThreadChunkBudgetMs", c.mainThreadChunkBudgetMs(), "主线程 apply 预算（ms）");
        set(cfg, "clientCache.parallelLightEngineEnabled", c.parallelLightEngineEnabled(), "是否启用多线程光照引擎（后台并行重算光照；默认开启）");
        set(cfg, "clientCache.parallelLightEngineThreads", c.parallelLightEngineThreads(), "多线程光照引擎线程数（虚拟线程模式忽略）");
    }

    private static HassiumConfig.ClientNetworkConfig readClientNetwork(CommentedConfig cfg) {
        var d = HassiumConfig.ClientNetworkConfig.DEFAULT;
        return new HassiumConfig.ClientNetworkConfig(
                getBool(cfg, "network.enabled", d.enabled()),
                getBool(cfg, "network.metricsEnabled", d.metricsEnabled()),
                getBool(cfg, "network.metricsAutoReset", d.metricsAutoReset()),
                getBool(cfg, "network.dataPlane.recoveryFreeze", d.recoveryFreeze())
        );
    }

    private static void writeClientNetwork(CommentedConfig cfg, HassiumConfig.ClientNetworkConfig n) {
        set(cfg, "network.enabled", n.enabled(), "是否启用 Hassium 自定义通道");
        set(cfg, "network.metricsEnabled", n.metricsEnabled(), "是否启用指标收集");
        set(cfg, "network.metricsAutoReset", n.metricsAutoReset(), "登出服务器时自动重置指标计数（默认 true）");
        set(cfg, "network.dataPlane.recoveryFreeze", n.recoveryFreeze(),
                "主控热切恢复期画面定格（默认 true；false=无感切换：不显示切换 UI，世界继续运行，恢复后位置/方块回退）");
    }

    // --- SERVER ---

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

    private static HassiumConfig.ServerNetworkConfig readServerNetwork(CommentedConfig cfg) {
        var d = HassiumConfig.ServerNetworkConfig.DEFAULT;
        return new HassiumConfig.ServerNetworkConfig(
                getBool(cfg, "network.enabled", d.enabled()),
                getInt(cfg, "network.compressionLevel", d.compressionLevel()),
                getBool(cfg, "network.magiclessZstd", d.magiclessZstd()),
                getBool(cfg, "network.globalPacketCompression", d.globalPacketCompression()),
                getInt(cfg, "network.globalCompressionLevel", d.globalCompressionLevel()),
                getInt(cfg, "network.globalCompressionThreshold", d.globalCompressionThreshold()),
                getBool(cfg, "network.useContextCompression", d.useContextCompression()),
                getBool(cfg, "network.enablePacketAggregation", d.enablePacketAggregation()),
                getInt(cfg, "network.aggregationMinBatchSize", d.aggregationMinBatchSize()),
                getLong(cfg, "network.aggregationMaxWaitTimeMs", d.aggregationMaxWaitTimeMs()),
                getInt(cfg, "network.aggregationMaxSize", d.aggregationMaxSize()),
                getBool(cfg, "network.enableCompactHeader", d.enableCompactHeader()),
                getStringSet(cfg, "network.compressionBlacklist", d.compressionBlacklist()),
                getBool(cfg, "network.metricsEnabled", d.metricsEnabled()),
                getInt(cfg, "network.maxChunksPerTick", d.maxChunksPerTick()),
                getInt(cfg, "network.smoothChunkSendRate", d.smoothChunkSendRate()),
                getInt(cfg, "network.serverChunkPushThreads", d.serverChunkPushThreads()),
                getBool(cfg, "network.dynamicThreadPoolEnabled", d.dynamicThreadPoolEnabled()),
                getInt(cfg, "network.minPushThreads", d.minPushThreads()),
                getInt(cfg, "network.maxPushThreads", d.maxPushThreads()),
                getBool(cfg, "network.lightStrip", d.lightStrip()),
                readReachableEndpoints(cfg, "network.controlReachableEndpoints", "network.controlReachableEndpoints"),
                readDataPlane(cfg, d.dataPlane())
        );
    }

    private static void writeServerNetwork(CommentedConfig cfg, HassiumConfig.ServerNetworkConfig n) {
        set(cfg, "network.enabled", n.enabled(), "是否启用 Hassium 自定义通道");
        set(cfg, "network.compressionLevel", n.compressionLevel(), "自定义通道 ZSTD 等级");
        set(cfg, "network.magiclessZstd", n.magiclessZstd(), "是否使用无 magic 的 ZSTD");
        set(cfg, "network.globalPacketCompression", n.globalPacketCompression(), "是否启用全局 ZSTD");
        set(cfg, "network.globalCompressionLevel", n.globalCompressionLevel(), "全局压缩等级");
        set(cfg, "network.globalCompressionThreshold", n.globalCompressionThreshold(), "全局压缩阈值（字节）");
        set(cfg, "network.useContextCompression", n.useContextCompression(), "是否使用上下文压缩");
        set(cfg, "network.enablePacketAggregation", n.enablePacketAggregation(), "是否启用包聚合");
        set(cfg, "network.aggregationMinBatchSize", n.aggregationMinBatchSize(), "聚合最小批量");
        set(cfg, "network.aggregationMaxWaitTimeMs", (int) n.aggregationMaxWaitTimeMs(), "聚合最大等待（ms）");
        set(cfg, "network.aggregationMaxSize", n.aggregationMaxSize(), "聚合最大大小（字节）");
        set(cfg, "network.enableCompactHeader", n.enableCompactHeader(), "是否启用紧凑包头");
        set(cfg, "network.compressionBlacklist", new ArrayList<>(n.compressionBlacklist()), "压缩/聚合黑名单");
        set(cfg, "network.metricsEnabled", n.metricsEnabled(), "是否启用指标收集");
        set(cfg, "network.maxChunksPerTick", n.maxChunksPerTick(), "每玩家每 tick 推送上限（仅服务端）");
        set(cfg, "network.smoothChunkSendRate", n.smoothChunkSendRate(), "每玩家区块平滑发送速率（块/秒，仅服务端）");
        set(cfg, "network.serverChunkPushThreads", n.serverChunkPushThreads(), "服务端推送线程数（仅服务端）");
        set(cfg, "network.dynamicThreadPoolEnabled", n.dynamicThreadPoolEnabled(), "是否动态调整推送线程（仅服务端）");
        set(cfg, "network.minPushThreads", n.minPushThreads(), "动态池最小线程数（仅服务端）");
        set(cfg, "network.maxPushThreads", n.maxPushThreads(), "动态池最大线程数（仅服务端）");
        set(cfg, "network.lightStrip", n.lightStrip(), "是否启用光照剥离");
        writeReachableEndpoints(cfg, "network.controlReachableEndpoints", n.controlReachableEndpoints(),
                "TCP 重连可达端点；仅用于客户端重连候选");
        writeDataPlane(cfg, n.dataPlane());
    }

    private static HassiumConfig.CompatConfig readCompat(CommentedConfig cfg) {
        var d = HassiumConfig.CompatConfig.DEFAULT;
        return new HassiumConfig.CompatConfig(
                getBool(cfg, "compat.requireClientMod", d.requireClientMod()),
                getBool(cfg, "compat.autoDowngradeOnError", d.autoDowngradeOnError())
        );
    }

    private static void writeCompat(CommentedConfig cfg, HassiumConfig.CompatConfig c) {
        set(cfg, "compat.requireClientMod", c.requireClientMod(), "是否强制要求客户端安装 Hassium");
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
                getBool(cfg, "debug.cacheLogging", d.cacheLogging()),
                getBool(cfg, "debug.dataplaneLogging", d.dataplaneLogging()),
                getBool(cfg, "debug.lightVerify", d.lightVerify())
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
        set(cfg, "debug.dataplaneLogging", d.dataplaneLogging(), "数据面（多通道 Data Plane）热路径日志 — 默认 false 以避免高频刷屏");
        set(cfg, "debug.lightVerify", d.lightVerify(), "光照验算（官方引擎对照 BFS 结果）");
    }

    // --- value helpers ---

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
    private static List<HassiumConfig.ReachableEndpoint> readReachableEndpoints(
            CommentedConfig cfg, String path, String fieldName
    ) {
        Object value = cfg.get(path);
        if (!(value instanceof List<?> entries)) {
            return List.of();
        }
        List<HassiumConfig.ReachableEndpoint> endpoints = new ArrayList<>();
        for (Object entry : entries) {
            if (!(entry instanceof CommentedConfig endpoint)) {
                LOGGER.warn("Hassium: 忽略 {} 中的非表端点", fieldName);
                continue;
            }
            try {
                endpoints.add(new HassiumConfig.ReachableEndpoint(
                        getString(endpoint, "host", ""), getInt(endpoint, "port", -1),
                        getInt(endpoint, "priority", -1)));
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Hassium: 忽略 {} 中的无效端点: {}", fieldName, e.getMessage());
            }
        }
        try {
            return DataPlaneEndpointConfig.normalizeReachableEndpoints(endpoints, 8, fieldName);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Hassium: 忽略 {}: {}", fieldName, e.getMessage());
            return List.of();
        }
    }

    private static HassiumConfig.DataPlaneConfig readDataPlane(
            CommentedConfig cfg, HassiumConfig.DataPlaneConfig defaults
    ) {
        boolean enabled = getBool(cfg, "network.dataPlane.enabled", defaults.enabled());
        long controlStallMs = getPositiveLong(cfg, "network.dataPlane.controlStallMs", defaults.controlStallMs());
        long failoverExpiryMs = getPositiveLong(cfg, "network.dataPlane.failoverExpiryMs", defaults.failoverExpiryMs());
        long recoveryWindowMs = getPositiveLong(cfg, "network.dataPlane.recoveryWindowMs", defaults.recoveryWindowMs());
        List<HassiumConfig.UdpListenerConfig> listeners = readUdpListeners(cfg);
        if (listeners.isEmpty() && enabled) {
            LOGGER.warn("Hassium: UDP data-plane 没有有效 listener，回退默认 data-plane 配置");
            return defaults;
        }
        try {
            return new HassiumConfig.DataPlaneConfig(enabled, listeners, controlStallMs, failoverExpiryMs, recoveryWindowMs);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Hassium: UDP data-plane 配置无效，回退默认: {}", e.getMessage());
            return defaults;
        }
    }

    private static List<HassiumConfig.UdpListenerConfig> readUdpListeners(CommentedConfig cfg) {
        Object value = cfg.get("network.dataPlane.udpListeners");
        if (!(value instanceof List<?> entries)) {
            return List.of();
        }
        List<HassiumConfig.UdpListenerConfig> listeners = new ArrayList<>();
        for (Object entry : entries) {
            if (!(entry instanceof CommentedConfig listener)) {
                LOGGER.warn("Hassium: 忽略 network.dataPlane.udpListeners 中的非表 listener");
                continue;
            }
            try {
                listeners.add(new HassiumConfig.UdpListenerConfig(
                        getString(listener, "bindHost", ""), getInt(listener, "bindPort", -1),
                        getInt(listener, "weight", -1),
                        readReachableEndpoints(listener, "reachableEndpoints",
                                "network.dataPlane.udpListeners.reachableEndpoints")));
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Hassium: 忽略无效 UDP listener: {}", e.getMessage());
            }
        }
        return listeners;
    }

    private static long getPositiveLong(CommentedConfig cfg, String path, long defaults) {
        long value = getLong(cfg, path, defaults);
        return value > 0 ? value : defaults;
    }

    private static void writeDataPlane(CommentedConfig cfg, HassiumConfig.DataPlaneConfig dataPlane) {
        set(cfg, "network.dataPlane.enabled", dataPlane.enabled(), "是否启用 UDP/KCP Data Plane");
        set(cfg, "network.dataPlane.controlStallMs", dataPlane.controlStallMs(), "控制 TCP 静默多久后允许申请 failover（ms）");
        set(cfg, "network.dataPlane.failoverExpiryMs", dataPlane.failoverExpiryMs(), "服务端 failover permit 有效期（ms）");
        set(cfg, "network.dataPlane.recoveryWindowMs", dataPlane.recoveryWindowMs(), "客户端候选重连窗口（ms）");
        cfg.remove("network.dataPlane.udpListeners");
        List<CommentedConfig> listeners = new ArrayList<>();
        for (HassiumConfig.UdpListenerConfig listener : dataPlane.udpListeners()) {
            CommentedConfig table = cfg.createSubConfig();
            table.set("bindHost", listener.bindHost());
            table.set("bindPort", listener.bindPort());
            table.set("weight", listener.weight());
            writeReachableEndpoints(table, "reachableEndpoints", listener.reachableEndpoints(),
                    "客户端可达 UDP 端点；bindHost 绝不下发");
            listeners.add(table);
        }
        cfg.setComment("network.dataPlane.udpListeners", "UDP listener；bind 仅限服务端本机，reachable 用于客户端连接");
        cfg.set("network.dataPlane.udpListeners", listeners);
    }

    private static void writeReachableEndpoints(
            CommentedConfig cfg, String path, List<HassiumConfig.ReachableEndpoint> endpoints, String comment
    ) {
        cfg.remove(path);
        List<CommentedConfig> tables = new ArrayList<>();
        for (HassiumConfig.ReachableEndpoint endpoint : endpoints) {
            CommentedConfig table = cfg.createSubConfig();
            table.set("host", endpoint.host());
            table.set("port", endpoint.port());
            table.set("priority", endpoint.priority());
            tables.add(table);
        }
        cfg.setComment(path, comment);
        cfg.set(path, tables);
    }
}
