package io.github.limuqy.mc.hassium.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** Loader-neutral configuration metadata shared by every platform backend. */
public final class ConfigSchema {
    private static final List<ConfigEntry<?>> ENTRIES = new ArrayList<>();

    public static final ConfigKey<Boolean> CACHE_ENABLED = bool("clientCache.enabled", ConfigScope.CLIENT, true, "是否启用客户端缓存");
    public static final ConfigKey<Integer> CACHE_MAX_SIZE_MB = integer("clientCache.maxSizeMb", ConfigScope.CLIENT, 4096, 64, 1024 * 1024, "缓存最大容量（MB）");
    public static final ConfigKey<Integer> CACHE_COMPRESSION_LEVEL = integer("clientCache.cacheCompressionLevel", ConfigScope.CLIENT, 3, 1, 22, "缓存压缩等级");
    public static final ConfigKey<Double> CACHE_HOT_SCORE_THRESHOLD = decimal("clientCache.hotScoreThreshold", ConfigScope.CLIENT, 0.3, 0.0, 1.0, "热点分数阈值");
    public static final ConfigKey<Double> CACHE_RECENCY_WEIGHT = decimal("clientCache.recencyWeight", ConfigScope.CLIENT, 0.7, 0.0, 1.0, "最近访问权重");
    public static final ConfigKey<Double> CACHE_FREQUENCY_WEIGHT = decimal("clientCache.frequencyWeight", ConfigScope.CLIENT, 0.3, 0.0, 1.0, "访问频率权重");
    public static final ConfigKey<Integer> CACHE_CLEANUP_INTERVAL_TICKS = integer("clientCache.cleanupIntervalTicks", ConfigScope.CLIENT, 6000, 20, 72000, "清理检查间隔（刻）");
    public static final ConfigKey<Integer> CACHE_TARGET_SIZE_MB = integer("clientCache.targetCacheSizeMb", ConfigScope.CLIENT, 0, 0, 1024 * 1024, "目标缓存大小（MB；0=自动）");
    public static final ConfigKey<Integer> CACHE_MIN_CLEANUP_BATCH_SIZE = integer("clientCache.minCleanupBatchSize", ConfigScope.CLIENT, 100, 1, 100000, "每次最少清理区块数");
    public static final ConfigKey<Boolean> CACHE_VIEW_DISTANCE_EXTENSION_ENABLED = bool("clientCache.viewDistanceExtensionEnabled", ConfigScope.CLIENT, true, "是否启用超视渲染");
    public static final ConfigKey<Integer> CACHE_MAX_RENDER_DISTANCE = integer("clientCache.maxRenderDistance", ConfigScope.CLIENT, 32, 2, 64, "超视渲染有效距离上限");
    public static final ConfigKey<Integer> CACHE_OVD_UNLOAD_DELAY_SECS = integer("clientCache.ovdUnloadDelaySecs", ConfigScope.CLIENT, 5, 0, 60, "超视渲染卸载延迟秒数");
    public static final ConfigKey<Boolean> CACHE_SECTION_DELTA_ENABLED = bool("clientCache.sectionDeltaEnabled", ConfigScope.CLIENT, true, "是否启用分段增量");
    public static final ConfigKey<Boolean> CACHE_JOIN_BOOST_ENABLED = bool("clientCache.joinBoostEnabled", ConfigScope.CLIENT, true, "是否启用进服加速");
    public static final ConfigKey<Boolean> CACHE_ENTITY_SNAPSHOTS_ENABLED = bool("clientCache.entitySnapshotsEnabled", ConfigScope.CLIENT, false, "是否保存实体快照");
    public static final ConfigKey<Integer> CACHE_LOAD_THREADS = integer("clientCache.loadThreads", ConfigScope.CLIENT, 10, 1, 64, "客户端区块加载线程数");
    public static final ConfigKey<Boolean> CACHE_LIGHT_CACHE_ENABLED = bool("clientCache.lightCacheEnabled", ConfigScope.CLIENT, true, "是否启用光照缓存");
    public static final ConfigKey<Integer> CACHE_MAX_CHUNKS_PER_FRAME = integer("clientCache.maxChunksPerFrame", ConfigScope.CLIENT, 32, 1, 512, "每帧应用缓存区块硬顶");
    public static final ConfigKey<Integer> CACHE_MAIN_THREAD_BUDGET_MS = integer("clientCache.mainThreadChunkBudgetMs", ConfigScope.CLIENT, 15, 1, 50, "主线程 apply 预算（ms）");
    public static final ConfigKey<Boolean> CACHE_PARALLEL_LIGHT_ENGINE_ENABLED = bool("clientCache.parallelLightEngineEnabled", ConfigScope.CLIENT, true, "是否启用多线程光照引擎（后台并行重算光照；默认开启）");
    public static final ConfigKey<Integer> CACHE_PARALLEL_LIGHT_ENGINE_THREADS = integer("clientCache.parallelLightEngineThreads", ConfigScope.CLIENT, 4, 1, 64, "多线程光照引擎线程数（虚拟线程模式忽略）");
    public static final ConfigKey<Boolean> CLIENT_NETWORK_ENABLED = bool("network.enabled", ConfigScope.CLIENT, true, "是否启用客户端 Hassium 自定义通道");
    public static final ConfigKey<Boolean> CLIENT_NETWORK_METRICS_ENABLED = bool("network.metricsEnabled", ConfigScope.CLIENT, false, "是否启用客户端网络指标");
    public static final ConfigKey<Boolean> CLIENT_NETWORK_METRICS_AUTO_RESET = bool("network.metricsAutoReset", ConfigScope.CLIENT, true, "登出服务器时自动重置指标计数");

    public static final ConfigKey<Boolean> STORAGE_ENABLED = bool("storage.enabled", ConfigScope.SERVER, false, "是否启用存档压缩（默认关；客户端缓存独立不受影响）");
    public static final ConfigKey<String> STORAGE_MODE = string("storage.mode", ConfigScope.SERVER, "mirror", "存储模式");
    public static final ConfigKey<Integer> STORAGE_ZSTD_LEVEL = integer("storage.zstdLevel", ConfigScope.SERVER, 3, 1, 22, "存储 ZSTD 压缩等级");
    public static final ConfigKey<Boolean> SERVER_NETWORK_ENABLED = bool("network.enabled", ConfigScope.SERVER, true, "是否启用 Hassium 自定义通道");
    public static final ConfigKey<Integer> NETWORK_COMPRESSION_LEVEL = integer("network.compressionLevel", ConfigScope.SERVER, 3, 1, 22, "自定义通道 ZSTD 压缩等级");
    public static final ConfigKey<Boolean> NETWORK_MAGICLESS_ZSTD = bool("network.magiclessZstd", ConfigScope.SERVER, true, "是否使用无 magic 的 ZSTD");
    public static final ConfigKey<Boolean> NETWORK_GLOBAL_PACKET_COMPRESSION = bool("network.globalPacketCompression", ConfigScope.SERVER, true, "是否启用全局包压缩");
    public static final ConfigKey<Integer> NETWORK_GLOBAL_COMPRESSION_LEVEL = integer("network.globalCompressionLevel", ConfigScope.SERVER, 6, 1, 22, "全局压缩等级");
    public static final ConfigKey<Integer> NETWORK_GLOBAL_COMPRESSION_THRESHOLD = integer("network.globalCompressionThreshold", ConfigScope.SERVER, 256, 0, 65536, "全局压缩阈值");
    public static final ConfigKey<Boolean> NETWORK_USE_CONTEXT_COMPRESSION = bool("network.useContextCompression", ConfigScope.SERVER, true, "是否使用上下文压缩");
    public static final ConfigKey<Boolean> NETWORK_PACKET_AGGREGATION = bool("network.enablePacketAggregation", ConfigScope.SERVER, true, "是否启用包聚合");
    public static final ConfigKey<Integer> NETWORK_AGGREGATION_MIN_BATCH = integer("network.aggregationMinBatchSize", ConfigScope.SERVER, 4, 1, 256, "聚合最小批量");
    public static final ConfigKey<Long> NETWORK_AGGREGATION_MAX_WAIT = longValue("network.aggregationMaxWaitTimeMs", ConfigScope.SERVER, 20L, 1L, 5000L, "聚合最大等待时间（ms）");
    public static final ConfigKey<Integer> NETWORK_AGGREGATION_MAX_SIZE = integer("network.aggregationMaxSize", ConfigScope.SERVER, 256 * 1024, 1024, 8 * 1024 * 1024, "聚合最大大小");
    public static final ConfigKey<Boolean> NETWORK_COMPACT_HEADER = bool("network.enableCompactHeader", ConfigScope.SERVER, true, "是否启用紧凑包头");
    public static final ConfigKey<List<String>> NETWORK_COMPRESSION_BLACKLIST = stringList("network.compressionBlacklist", ConfigScope.SERVER, () -> new ArrayList<>(HassiumConfig.ServerNetworkConfig.DEFAULT_COMPRESSION_BLACKLIST), "压缩/聚合黑名单");
    public static final ConfigKey<Boolean> SERVER_NETWORK_METRICS_ENABLED = bool("network.metricsEnabled", ConfigScope.SERVER, false, "是否启用服务端网络指标");
    public static final ConfigKey<Integer> NETWORK_MAX_CHUNKS_PER_TICK = integer("network.maxChunksPerTick", ConfigScope.SERVER, 32, 1, 256, "每玩家每 tick 推送上限");
    public static final ConfigKey<Integer> NETWORK_SMOOTH_SEND_RATE = integer("network.smoothChunkSendRate", ConfigScope.SERVER, 150, 1, 1000, "服务端每玩家区块平滑发送速率（块/秒，摊平 tick 级脉冲防网络峰值）");
    public static final ConfigKey<Integer> NETWORK_SERVER_PUSH_THREADS = integer("network.serverChunkPushThreads", ConfigScope.SERVER, 8, 1, 64, "服务端推送线程数");
    public static final ConfigKey<Boolean> NETWORK_DYNAMIC_THREADS = bool("network.dynamicThreadPoolEnabled", ConfigScope.SERVER, true, "是否动态调整推送线程");
    public static final ConfigKey<Integer> NETWORK_MIN_PUSH_THREADS = integer("network.minPushThreads", ConfigScope.SERVER, 2, 1, 64, "动态池最小线程数");
    public static final ConfigKey<Integer> NETWORK_MAX_PUSH_THREADS = integer("network.maxPushThreads", ConfigScope.SERVER, 8, 1, 64, "动态池最大线程数");
    public static final ConfigKey<Boolean> NETWORK_LIGHT_STRIP = bool("network.lightStrip", ConfigScope.SERVER, true, "是否启用光照剥离");
    public static final ConfigKey<List<String>> NETWORK_CONTROL_ENDPOINTS = stringList("network.controlReachableEndpoints", ConfigScope.SERVER, List::of, "TCP 重连可达端点");
    public static final ConfigKey<Boolean> DATAPLANE_ENABLED = bool("network.dataPlane.enabled", ConfigScope.SERVER, false, "是否启用 UDP/KCP Data Plane");
    public static final ConfigKey<List<String>> DATAPLANE_UDP_LISTENERS = stringList("network.dataPlane.udpListeners", ConfigScope.SERVER, () -> HassiumConfig.ServerNetworkConfig.DEFAULT.dataPlane().udpListeners().stream().map(DataPlaneEndpointConfig::encodeListener).toList(), "UDP listener 编码列表");
    public static final ConfigKey<Long> DATAPLANE_CONTROL_STALL_MS = longValue("network.dataPlane.controlStallMs", ConfigScope.SERVER, 6000L, 1L, Long.MAX_VALUE, "控制 TCP 静默时间（ms）");
    public static final ConfigKey<Long> DATAPLANE_FAILOVER_EXPIRY_MS = longValue("network.dataPlane.failoverExpiryMs", ConfigScope.SERVER, 30000L, 1L, Long.MAX_VALUE, "failover permit 有效期（ms）");
    public static final ConfigKey<Long> DATAPLANE_RECOVERY_WINDOW_MS = longValue("network.dataPlane.recoveryWindowMs", ConfigScope.SERVER, 60000L, 1L, Long.MAX_VALUE, "候选重连窗口（ms）");
    public static final ConfigKey<Boolean> DATAPLANE_RECOVERY_FREEZE = bool("network.dataPlane.recoveryFreeze", ConfigScope.CLIENT, true, "主控热切恢复期画面定格（false=无感切换：不显示切换 UI，世界继续运行，恢复后回退）");
    public static final ConfigKey<Boolean> COMPAT_REQUIRE_CLIENT_MOD = bool("compat.requireClientMod", ConfigScope.SERVER, false, "是否强制要求客户端安装 Hassium");
    public static final ConfigKey<Boolean> COMPAT_AUTO_DOWNGRADE = bool("compat.autoDowngradeOnError", ConfigScope.SERVER, true, "出错时是否自动降级");

    public static final ConfigKey<Boolean> CLIENT_DEBUG_METADATA = bool("debug.metadataLogging", ConfigScope.CLIENT, false, "元数据调试日志");
    public static final ConfigKey<Boolean> CLIENT_DEBUG_DISPATCHER = bool("debug.dispatcherLogging", ConfigScope.CLIENT, false, "主线程调度调试日志");
    public static final ConfigKey<Boolean> CLIENT_DEBUG_ASYNC = bool("debug.asyncLogging", ConfigScope.CLIENT, false, "异步调试日志");
    public static final ConfigKey<Boolean> CLIENT_DEBUG_COMPRESSION = bool("debug.compressionLogging", ConfigScope.CLIENT, false, "压缩调试日志");
    public static final ConfigKey<Boolean> CLIENT_DEBUG_CHUNK_APPLY = bool("debug.chunkApplyLogging", ConfigScope.CLIENT, false, "区块 apply 调试日志");
    public static final ConfigKey<Boolean> CLIENT_DEBUG_NETWORK = bool("debug.networkLogging", ConfigScope.CLIENT, false, "网络调试日志");
    public static final ConfigKey<Boolean> CLIENT_DEBUG_CACHE = bool("debug.cacheLogging", ConfigScope.CLIENT, false, "缓存调试日志");
    public static final ConfigKey<Boolean> CLIENT_DEBUG_DATAPLANE = bool("debug.dataplaneLogging", ConfigScope.CLIENT, false, "数据面调试日志");
    public static final ConfigKey<Boolean> CLIENT_DEBUG_LIGHT_VERIFY = bool("debug.lightVerify", ConfigScope.CLIENT, false, "光照验算（官方引擎对照 BFS 结果）");
    public static final ConfigKey<Boolean> SERVER_DEBUG_METADATA = bool("debug.metadataLogging", ConfigScope.SERVER, false, "元数据调试日志");
    public static final ConfigKey<Boolean> SERVER_DEBUG_DISPATCHER = bool("debug.dispatcherLogging", ConfigScope.SERVER, false, "主线程调度调试日志");
    public static final ConfigKey<Boolean> SERVER_DEBUG_ASYNC = bool("debug.asyncLogging", ConfigScope.SERVER, false, "异步调试日志");
    public static final ConfigKey<Boolean> SERVER_DEBUG_COMPRESSION = bool("debug.compressionLogging", ConfigScope.SERVER, false, "压缩调试日志");
    public static final ConfigKey<Boolean> SERVER_DEBUG_CHUNK_APPLY = bool("debug.chunkApplyLogging", ConfigScope.SERVER, false, "区块 apply 调试日志");
    public static final ConfigKey<Boolean> SERVER_DEBUG_NETWORK = bool("debug.networkLogging", ConfigScope.SERVER, false, "网络调试日志");
    public static final ConfigKey<Boolean> SERVER_DEBUG_CACHE = bool("debug.cacheLogging", ConfigScope.SERVER, false, "缓存调试日志");
    public static final ConfigKey<Boolean> SERVER_DEBUG_DATAPLANE = bool("debug.dataplaneLogging", ConfigScope.SERVER, false, "数据面调试日志");
    public static final ConfigKey<Boolean> SERVER_DEBUG_LIGHT_VERIFY = bool("debug.lightVerify", ConfigScope.SERVER, false, "光照验算（官方引擎对照 BFS 结果）");

    static {
        validateUniquePaths();
    }

    private ConfigSchema() {
    }

    public static List<ConfigEntry<?>> entries() {
        return List.copyOf(ENTRIES);
    }

    public static List<ConfigEntry<?>> clientEntries() {
        return entries(ConfigScope.CLIENT);
    }

    public static List<ConfigEntry<?>> serverEntries() {
        return entries(ConfigScope.SERVER);
    }

    private static List<ConfigEntry<?>> entries(ConfigScope scope) {
        return ENTRIES.stream().filter(entry -> entry.scope() == scope).toList();
    }

    private static void validateUniquePaths() {
        for (ConfigScope scope : ConfigScope.values()) {
            Set<String> paths = new java.util.HashSet<>();
            for (ConfigEntry<?> entry : entries(scope)) {
                if (!paths.add(entry.path())) {
                    throw new IllegalStateException("Duplicate configuration path: " + scope + '/' + entry.path());
                }
            }
        }
    }

    private static ConfigKey<Boolean> bool(String path, ConfigScope scope, boolean defaultValue, String comment) {
        return add(path, scope, ConfigType.BOOLEAN, defaultValue, null, null, comment, Boolean.class);
    }

    private static ConfigKey<Integer> integer(String path, ConfigScope scope, int defaultValue, int min, int max, String comment) {
        return add(path, scope, ConfigType.INT, defaultValue, min, max, comment, Integer.class);
    }

    private static ConfigKey<Long> longValue(String path, ConfigScope scope, long defaultValue, long min, long max, String comment) {
        return add(path, scope, ConfigType.LONG, defaultValue, min, max, comment, Long.class);
    }

    private static ConfigKey<Double> decimal(String path, ConfigScope scope, double defaultValue, double min, double max, String comment) {
        return add(path, scope, ConfigType.DOUBLE, defaultValue, min, max, comment, Double.class);
    }

    private static ConfigKey<String> string(String path, ConfigScope scope, String defaultValue, String comment) {
        return add(path, scope, ConfigType.STRING, defaultValue, null, null, comment, String.class);
    }

    private static ConfigKey<List<String>> stringList(String path, ConfigScope scope, Supplier<List<String>> defaultSupplier, String comment) {
        return add(path, scope, ConfigType.STRING_LIST, List.copyOf(defaultSupplier.get()), null, null, comment, List.class);
    }

    private static <T> ConfigKey<T> add(String path, ConfigScope scope, ConfigType type, T defaultValue,
                                        Number min, Number max, String comment, Class<?> valueType) {
        ConfigKey<T> key = new ConfigKey<>(path, scope, valueType);
        ENTRIES.add(new ConfigEntry<>(key, path, scope, type, defaultValue, min, max, comment,
                "hassium.configuration." + path));
        return key;
    }
}
