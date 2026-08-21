package io.github.limuqy.mc.hassium.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** Loader-neutral configuration metadata shared by every platform backend. */
public final class ConfigSchema {
    private static final List<ConfigEntry<?>> ENTRIES = new ArrayList<>();

    // === 区块核心（chunk.*；CLIENT 21 键）===
    public static final ConfigKey<Boolean> CHUNK_ENABLED = bool("chunk.enabled", ConfigScope.CLIENT, Domain.CHUNK_CORE, true,
            "是否启用区块核心缓存", "Whether to enable the chunk-core cache");
    public static final ConfigKey<Integer> CHUNK_MAX_SIZE_MB = integer("chunk.maxSizeMb", ConfigScope.CLIENT, Domain.CHUNK_CORE, 4096, 64, 1024 * 1024,
            "缓存最大容量（MB；影子端存档容量上限，超限触发热度淘汰）",
            "Max cache size in MB (shadow-world disk cap; excess triggers heat eviction)");
    public static final ConfigKey<Integer> CHUNK_COMPRESSION_LEVEL = integer("chunk.compressionLevel", ConfigScope.CLIENT, Domain.CHUNK_CORE, 3, 1, 22,
            "缓存压缩等级", "Cache ZSTD compression level");
    public static final ConfigKey<Double> CHUNK_HOT_SCORE_THRESHOLD = decimal("chunk.hotScoreThreshold", ConfigScope.CLIENT, Domain.CHUNK_CORE, 0.3, 0.0, 1.0,
            "热点分数阈值（低于此值视为冷区块，清理时优先淘汰）",
            "Heat-score threshold (below = cold; preferred for eviction)");
    public static final ConfigKey<Double> CHUNK_RECENCY_WEIGHT = decimal("chunk.recencyWeight", ConfigScope.CLIENT, Domain.CHUNK_CORE, 0.7, 0.0, 1.0,
            "最近访问权重", "Recency weight in heat score");
    public static final ConfigKey<Double> CHUNK_FREQUENCY_WEIGHT = decimal("chunk.frequencyWeight", ConfigScope.CLIENT, Domain.CHUNK_CORE, 0.3, 0.0, 1.0,
            "访问频率权重", "Visit-frequency weight in heat score");
    public static final ConfigKey<Integer> CHUNK_CLEANUP_INTERVAL_TICKS = integer("chunk.cleanupIntervalTicks", ConfigScope.CLIENT, Domain.CHUNK_CORE, 6000, 20, 72000,
            "清理检查间隔（刻）", "Cleanup check interval in ticks");
    public static final ConfigKey<Integer> CHUNK_TARGET_SIZE_MB = integer("chunk.targetSizeMb", ConfigScope.CLIENT, Domain.CHUNK_CORE, 0, 0, 1024 * 1024,
            "目标缓存大小（MB；0=自动）", "Target cache size in MB (0 = auto)");
    public static final ConfigKey<Integer> CHUNK_MIN_CLEANUP_BATCH_SIZE = integer("chunk.minCleanupBatchSize", ConfigScope.CLIENT, Domain.CHUNK_CORE, 100, 1, 100000,
            "每次最少清理区块数", "Minimum chunks evicted per cleanup pass");
    public static final ConfigKey<Boolean> CHUNK_SECTION_DELTA_ENABLED = bool("chunk.sectionDeltaEnabled", ConfigScope.CLIENT, Domain.CHUNK_CORE, true,
            "是否启用分段增量（GatewayPacketCodec/NetworkCore/DataPlaneClientBundle 活跃消费）",
            "Enable section delta (active in gateway / network core / data plane)");
    public static final ConfigKey<Boolean> CHUNK_JOIN_BOOST_ENABLED = bool("chunk.joinBoostEnabled", ConfigScope.CLIENT, Domain.CHUNK_CORE, true,
            "是否启用进服加速", "Enable join boost");
    public static final ConfigKey<Boolean> CHUNK_VIEW_DISTANCE_EXTENSION_ENABLED = bool("chunk.viewDistanceExtensionEnabled", ConfigScope.CLIENT, Domain.CHUNK_CORE, true,
            "是否启用超视渲染", "Enable beyond-view rendering");
    public static final ConfigKey<Integer> CHUNK_MAX_RENDER_DISTANCE = integer("chunk.maxRenderDistance", ConfigScope.CLIENT, Domain.CHUNK_CORE, 16, 2, 64,
            "超视渲染有效距离上限", "Beyond-view / effective render-distance cap");
    public static final ConfigKey<Integer> CHUNK_OVD_UNLOAD_DELAY_SECS = integer("chunk.ovdUnloadDelaySecs", ConfigScope.CLIENT, Domain.CHUNK_CORE, 5, 0, 60,
            "超视渲染卸载延迟秒数", "Beyond-view unload delay in seconds");
    public static final ConfigKey<Integer> CHUNK_UNLOAD_DELAY_SECS = integer("chunk.unloadDelaySecs", ConfigScope.CLIENT, Domain.CHUNK_CORE, 30, 0, 600,
            "影子端内存区块回收延迟秒数（离开卸载边界后计时，超时落盘并清内存；0=禁用回收）",
            "Shadow in-memory chunk recycle delay in seconds (0 = disable)");
    public static final ConfigKey<Integer> CHUNK_MAX_CHUNKS_PER_FRAME = integer("chunk.maxChunksPerFrame", ConfigScope.CLIENT, Domain.CHUNK_CORE, 6, 1, 512,
            "每 tick 缓存读取生产上限（OVD 入队 + 影子读盘；主线程消费只受时间预算）",
            "Per-tick cache-read production cap (OVD enqueue + shadow disk); consume is time-budget only");
    public static final ConfigKey<Integer> CHUNK_MAIN_THREAD_CHUNK_BUDGET_MS = integer("chunk.mainThreadChunkBudgetMs", ConfigScope.CLIENT, Domain.CHUNK_CORE, 15, 1, 50,
            "主线程 apply 预算（ms）", "Main-thread apply budget in ms");
    public static final ConfigKey<Boolean> CHUNK_HASSIUM_ENGINE_ENABLED = bool("chunk.hassiumEngineEnabled", ConfigScope.CLIENT, Domain.CHUNK_CORE, true,
            "是否启用 Hassium 引擎（默认 true）：进服启动引擎统一算光；失败自动降级。与 chunk.seedGenEnabled 相互独立",
            "Enable Hassium engine (default true): owns lighting; degrades on failure. Independent of seedGenEnabled");
    public static final ConfigKey<Boolean> CHUNK_OVD_LOCAL_GENERATION = bool("chunk.ovdLocalGeneration", ConfigScope.CLIENT, Domain.CHUNK_CORE, false,
            "OVD 本地生成（默认 false）：超视 miss 时按服务端种子本地生成并缓存；需引擎可用",
            "Beyond-view local generation on cache miss (default false); requires engine");
    public static final ConfigKey<Integer> CHUNK_SEED_GEN_THREADS = integer("chunk.seedGenThreads", ConfigScope.CLIENT, Domain.CHUNK_CORE, 2, 0, 64,
            "SeedGen 本地生成线程数（0=禁用本地生成，SeedRef 一律回退全量）",
            "SeedGen local-generation thread count (0 = disable; SeedRef falls back to full chunk)");
    public static final ConfigKey<Boolean> CLIENT_CHUNK_SEED_GEN_ENABLED = bool("chunk.seedGenEnabled", ConfigScope.CLIENT, Domain.CHUNK_CORE, false,
            "是否启用 SeedGen（本地生成 pristine 区块；需双端同版本，默认关）。服务端开启时会下发世界种子",
            "Enable SeedGen (local pristine chunks; both sides same version; default off). Server enablement sends the world seed");

    // === 网络核心（net.*；CLIENT 3 键）===
    public static final ConfigKey<Boolean> NET_ENABLED = bool("net.enabled", ConfigScope.CLIENT, Domain.NETWORK_CORE, true,
            "是否启用客户端网络核心（2.0.0 进程内网关与帧连接总开关）",
            "Enable client network core (in-process gateway / frame channel)");
    public static final ConfigKey<Boolean> NET_METRICS_ENABLED = bool("net.metricsEnabled", ConfigScope.CLIENT, Domain.NETWORK_CORE, false,
            "是否启用客户端网络指标", "Enable client network metrics");
    public static final ConfigKey<Boolean> NET_METRICS_AUTO_RESET = bool("net.metricsAutoReset", ConfigScope.CLIENT, Domain.NETWORK_CORE, true,
            "登出服务器时自动重置指标计数", "Auto-reset metrics when leaving a server");

    // === 存储域（storage.*；SERVER 2 键）===
    public static final ConfigKey<Boolean> STORAGE_ENABLED = bool("storage.enabled", ConfigScope.SERVER, Domain.STORAGE, false,
            "是否启用存档压缩（默认关；区块核心缓存独立不受影响）",
            "Enable save compression (default off; chunk cache unaffected)");
    public static final ConfigKey<Integer> STORAGE_ZSTD_LEVEL = integer("storage.zstdLevel", ConfigScope.SERVER, Domain.STORAGE, 3, 1, 22,
            "存储 ZSTD 压缩等级", "Storage ZSTD compression level");

    // === 主控核心（master.*；SERVER）===
    public static final ConfigKey<Boolean> MASTER_ENABLED = bool("master.enabled", ConfigScope.SERVER, Domain.MASTER_CORE, true,
            "是否启用主控核心网络通道", "Enable master-core network channel");
    public static final ConfigKey<Integer> MASTER_COMPRESSION_LEVEL = integer("master.compressionLevel", ConfigScope.SERVER, Domain.MASTER_CORE, 3, 1, 22,
            "自有通道 ZSTD 压缩等级", "Private-channel ZSTD level");
    public static final ConfigKey<Boolean> MASTER_MAGICLESS_ZSTD = bool("master.magiclessZstd", ConfigScope.SERVER, Domain.MASTER_CORE, true,
            "是否使用无 magic 的 ZSTD", "Use magicless ZSTD");
    public static final ConfigKey<Boolean> MASTER_GLOBAL_PACKET_COMPRESSION = bool("master.globalPacketCompression", ConfigScope.SERVER, Domain.MASTER_CORE, true,
            "是否启用全局包压缩", "Enable global packet compression");
    public static final ConfigKey<Integer> MASTER_GLOBAL_COMPRESSION_LEVEL = integer("master.globalCompressionLevel", ConfigScope.SERVER, Domain.MASTER_CORE, 3, 1, 22,
            "全局压缩等级", "Global compression level");
    public static final ConfigKey<Integer> MASTER_GLOBAL_COMPRESSION_THRESHOLD = integer("master.globalCompressionThreshold", ConfigScope.SERVER, Domain.MASTER_CORE, 256, 0, 65536,
            "全局压缩阈值", "Global compression threshold (bytes)");
    public static final ConfigKey<Boolean> MASTER_USE_CONTEXT_COMPRESSION = bool("master.useContextCompression", ConfigScope.SERVER, Domain.MASTER_CORE, true,
            "是否使用上下文压缩", "Use context compression");
    public static final ConfigKey<Boolean> MASTER_PACKET_AGGREGATION = bool("master.enablePacketAggregation", ConfigScope.SERVER, Domain.MASTER_CORE, true,
            "是否启用包聚合", "Enable packet aggregation");
    public static final ConfigKey<Integer> MASTER_AGGREGATION_MIN_BATCH = integer("master.aggregationMinBatchSize", ConfigScope.SERVER, Domain.MASTER_CORE, 4, 1, 256,
            "聚合最小批量", "Aggregation minimum batch size");
    public static final ConfigKey<Long> MASTER_AGGREGATION_MAX_WAIT = longValue("master.aggregationMaxWaitTimeMs", ConfigScope.SERVER, Domain.MASTER_CORE, 20L, 1L, 5000L,
            "聚合最大等待时间（ms）", "Aggregation max wait time (ms)");
    public static final ConfigKey<Integer> MASTER_AGGREGATION_MAX_SIZE = integer("master.aggregationMaxSize", ConfigScope.SERVER, Domain.MASTER_CORE, 256 * 1024, 1024, 8 * 1024 * 1024,
            "聚合最大大小", "Aggregation max size (bytes)");
    public static final ConfigKey<Boolean> MASTER_COMPACT_HEADER = bool("master.enableCompactHeader", ConfigScope.SERVER, Domain.MASTER_CORE, true,
            "是否启用紧凑包头", "Enable compact packet headers");
    public static final ConfigKey<List<String>> MASTER_COMPRESSION_BLACKLIST = stringList("master.compressionBlacklist", ConfigScope.SERVER, Domain.MASTER_CORE,
            () -> new ArrayList<>(HassiumConfig.MasterCoreConfig.DEFAULT_COMPRESSION_BLACKLIST),
            "压缩/聚合黑名单", "Compression / aggregation blacklist");
    public static final ConfigKey<Boolean> MASTER_METRICS_ENABLED = bool("master.metricsEnabled", ConfigScope.SERVER, Domain.MASTER_CORE, false,
            "是否启用主控网络指标", "Enable master network metrics");
    public static final ConfigKey<Integer> MASTER_MAX_CHUNKS_PER_TICK = integer("master.maxChunksPerTick", ConfigScope.SERVER, Domain.MASTER_CORE, 4, 1, 256,
            "每玩家每 tick 提交到后台序列化的区块上限（满 tick ≈ 本值×20/s）",
            "Per-player per-tick chunk submit cap (≈ value×20/s at full tick)");
    public static final ConfigKey<Integer> MASTER_SERVER_PUSH_THREADS = integer("master.serverChunkPushThreads", ConfigScope.SERVER, Domain.MASTER_CORE, 4, 1, 64,
            "服务端区块推送线程数（encode/hash/ZSTD 固定后台池）",
            "Server chunk-push thread count (fixed encode/hash/ZSTD pool)");
    public static final ConfigKey<List<String>> MASTER_CONTROL_ENDPOINTS = stringList("master.controlReachableEndpoints", ConfigScope.SERVER, Domain.MASTER_CORE, List::of,
            "网关监听端点（服务端绑定/下发；客户端经 gateway_info 同步，无需本地配置）",
            "Gateway listen endpoints (server bind/publish; clients get them via gateway_info)");

    // === 网关监听与鉴权（仅 SERVER）===
    public static final ConfigKey<String> MASTER_BIND_HOST = string("master.bindHost", ConfigScope.SERVER, Domain.MASTER_CORE, "127.0.0.1",
            "网关监听 bind host（默认 127.0.0.1 回环；空串=0.0.0.0 全网卡）",
            "Gateway bind host (default 127.0.0.1; empty = 0.0.0.0)");
    public static final ConfigKey<String> MASTER_AUTH_TOKEN = string("master.authToken", ConfigScope.SERVER, Domain.MASTER_CORE, "",
            "网关握手鉴权 token（默认空=不鉴权；经 gateway_info 下发客户端）",
            "Gateway handshake auth token (empty = off; published via gateway_info)");

    public static final ConfigKey<Long> MASTER_MIGRATION_FAULT_TIMEOUT_MS = longValue("master.migrationFaultTimeoutMs", ConfigScope.SERVER, Domain.MASTER_CORE, 60000L, 1L, Long.MAX_VALUE,
            "L1 迁移故障超时（ms；silentTimeout 未配置时回退本值）",
            "L1 migration fault timeout (ms; fallback when silentTimeout unset)");

    // === L1 迁移策略（CLIENT 仅此 6 键）===
    public static final ConfigKey<Double> MASTER_MIGRATION_MIN_TPS = decimal("master.migrationMinTps", ConfigScope.CLIENT, Domain.MASTER_CORE, 15.0, 0.1, 100.0,
            "L1 迁移策略：主控 TPS 低于此值触发迁移",
            "L1 migration: trigger when master TPS drops below this");
    public static final ConfigKey<Double> MASTER_MIGRATION_MAX_LOAD_AVERAGE = decimal("master.migrationMaxLoadAverage", ConfigScope.CLIENT, Domain.MASTER_CORE, 4.0, 0.1, 100.0,
            "L1 迁移策略：主控系统负载均值高于此值触发迁移（getSystemLoadAverage 为 -1 视为无信号）",
            "L1 migration: trigger when system load average exceeds this (-1 = no signal)");
    public static final ConfigKey<String> MASTER_MIGRATION_MAINTENANCE_WINDOW = string("master.migrationMaintenanceWindow", ConfigScope.CLIENT, Domain.MASTER_CORE, "",
            "L1 迁移策略：维护窗口 \"HH:MM-HH:MM\"（本地时区，含跨午夜）；空串=禁用",
            "L1 migration: maintenance window \"HH:MM-HH:MM\" (local tz; empty = off)");
    public static final ConfigKey<Long> MASTER_MIGRATION_HEARTBEAT_INTERVAL_MS = longValue("master.migrationHeartbeatIntervalMs", ConfigScope.CLIENT, Domain.MASTER_CORE, 5000L, 100L, 60000L,
            "L1 迁移：应用层 HEARTBEAT 发送周期（ms）",
            "L1 migration: application HEARTBEAT interval (ms)");
    public static final ConfigKey<Long> MASTER_MIGRATION_IDLE_WINDOW_MS = longValue("master.migrationIdleWindowMs", ConfigScope.CLIENT, Domain.MASTER_CORE, 10000L, 1000L, 600000L,
            "L1 迁移：空闲窗口判定时长（ms；玩家静止 + 区块 hash 稳定）",
            "L1 migration: idle-window duration (ms; player still + stable chunk hashes)");
    public static final ConfigKey<Long> MASTER_MIGRATION_SILENT_TIMEOUT_MS = longValue("master.migrationSilentTimeoutMs", ConfigScope.CLIENT, Domain.MASTER_CORE, 10000L, 1000L, 600000L,
            "L1 迁移：outbound 入站静默超时（ms；默认 10s；未配置时回退 migrationFaultTimeoutMs）",
            "L1 migration: inbound silence timeout (ms; default 10s; falls back to faultTimeout)");

    // === 区块核心（chunk.*；SERVER 2 键）===
    public static final ConfigKey<Boolean> SERVER_CHUNK_SEED_GEN_ENABLED = bool("chunk.seedGenEnabled", ConfigScope.SERVER, Domain.CHUNK_CORE, false,
            "是否启用 SeedGen（对 pristine 区块发 SeedRef；需双端同版本，默认关）。警告：开启会向客户端下发世界种子，等同泄露服务端种子",
            "Enable SeedGen (SeedRef for pristine chunks; default off). WARNING: this leaks the server world seed to clients");
    public static final ConfigKey<Boolean> CHUNK_LIGHT_STRIP = bool("chunk.lightStrip", ConfigScope.SERVER, Domain.CHUNK_CORE, true,
            "是否启用光照剥离", "Enable light stripping");

    // === 数据面（dataplane.*；SERVER 2 键）===
    public static final ConfigKey<Boolean> DATAPLANE_ENABLED = bool("dataplane.enabled", ConfigScope.SERVER, Domain.DATAPLANE, false,
            "是否启用 UDP/KCP Data Plane", "Enable UDP/KCP Data Plane");
    public static final ConfigKey<List<String>> DATAPLANE_UDP_LISTENERS = stringList("dataplane.udpListeners", ConfigScope.SERVER, Domain.DATAPLANE,
            () -> HassiumConfig.MasterCoreConfig.DEFAULT.dataPlane().udpListeners().stream().map(DataPlaneEndpointConfig::encodeListener).toList(),
            "UDP listener 编码列表", "Encoded UDP listener list");

    // === 兼容性 / 续流 TTL（SERVER）===
    public static final ConfigKey<Long> MASTER_MIGRATION_PREWARM_TTL_MS = longValue("master.migrationPrewarmTtlMs", ConfigScope.SERVER, Domain.MASTER_CORE, 60000L, 1000L, Long.MAX_VALUE,
            "预热会话 TTL（ms；无续流完成的预热物化会话到期清理）",
            "Prewarm session TTL (ms; expire unfinished prewarm materializations)");
    public static final ConfigKey<Long> MASTER_RESUME_TICKET_TTL_MS = longValue("master.resumeTicketTtlMs", ConfigScope.SERVER, Domain.MASTER_CORE, 300000L, 1000L, Long.MAX_VALUE,
            "续流票据有效期（ms；T2 防重放时间窗口，默认 5min）",
            "Resume-ticket TTL (ms; anti-replay window, default 5 min)");
    public static final ConfigKey<Boolean> COMPAT_REQUIRE_CLIENT_MOD = bool("compat.requireClientMod", ConfigScope.SERVER, Domain.COMPAT, false,
            "是否强制要求客户端安装 Hassium", "Require the Hassium client mod");
    public static final ConfigKey<Boolean> COMPAT_AUTO_DOWNGRADE = bool("compat.autoDowngradeOnError", ConfigScope.SERVER, Domain.COMPAT, true,
            "出错时是否自动降级", "Auto-downgrade on error");

    // === 调试（debug.*；CLIENT 8 键：元数据/缓存/光照验算为客户端专属；数据面不在此端）===
    public static final ConfigKey<Boolean> CLIENT_DEBUG_METADATA = bool("debug.metadataLogging", ConfigScope.CLIENT, Domain.DEBUG, false,
            "元数据调试日志", "Metadata debug logging");
    public static final ConfigKey<Boolean> CLIENT_DEBUG_DISPATCHER = bool("debug.dispatcherLogging", ConfigScope.CLIENT, Domain.DEBUG, false,
            "主线程调度调试日志", "Main-thread dispatcher debug logging");
    public static final ConfigKey<Boolean> CLIENT_DEBUG_ASYNC = bool("debug.asyncLogging", ConfigScope.CLIENT, Domain.DEBUG, false,
            "异步调试日志", "Async-task debug logging");
    public static final ConfigKey<Boolean> CLIENT_DEBUG_COMPRESSION = bool("debug.compressionLogging", ConfigScope.CLIENT, Domain.DEBUG, false,
            "压缩调试日志", "Compression debug logging");
    public static final ConfigKey<Boolean> CLIENT_DEBUG_CHUNK_APPLY = bool("debug.chunkApplyLogging", ConfigScope.CLIENT, Domain.DEBUG, false,
            "区块 apply 调试日志", "Chunk-apply debug logging");
    public static final ConfigKey<Boolean> CLIENT_DEBUG_NETWORK = bool("debug.networkLogging", ConfigScope.CLIENT, Domain.DEBUG, false,
            "网络调试日志", "Network debug logging");
    public static final ConfigKey<Boolean> CLIENT_DEBUG_CACHE = bool("debug.cacheLogging", ConfigScope.CLIENT, Domain.DEBUG, false,
            "缓存调试日志", "Cache debug logging");
    public static final ConfigKey<Boolean> CLIENT_DEBUG_LIGHT_VERIFY = bool("debug.lightVerify", ConfigScope.CLIENT, Domain.DEBUG, false,
            "光照验算（官方引擎对照 BFS 结果）", "Light verification (compare against official engine BFS)");

    // === 调试（debug.*；SERVER 6 键：数据面为服务端专属；不含元数据/缓存/光照验算）===
    public static final ConfigKey<Boolean> SERVER_DEBUG_DISPATCHER = bool("debug.dispatcherLogging", ConfigScope.SERVER, Domain.DEBUG, false,
            "主线程调度调试日志", "Main-thread dispatcher debug logging");
    public static final ConfigKey<Boolean> SERVER_DEBUG_ASYNC = bool("debug.asyncLogging", ConfigScope.SERVER, Domain.DEBUG, false,
            "异步调试日志", "Async-task debug logging");
    public static final ConfigKey<Boolean> SERVER_DEBUG_COMPRESSION = bool("debug.compressionLogging", ConfigScope.SERVER, Domain.DEBUG, false,
            "压缩调试日志", "Compression debug logging");
    public static final ConfigKey<Boolean> SERVER_DEBUG_CHUNK_APPLY = bool("debug.chunkApplyLogging", ConfigScope.SERVER, Domain.DEBUG, false,
            "区块 apply 调试日志", "Chunk-apply debug logging");
    public static final ConfigKey<Boolean> SERVER_DEBUG_NETWORK = bool("debug.networkLogging", ConfigScope.SERVER, Domain.DEBUG, false,
            "网络调试日志", "Network debug logging");
    public static final ConfigKey<Boolean> SERVER_DEBUG_DATAPLANE = bool("debug.dataplaneLogging", ConfigScope.SERVER, Domain.DEBUG, false,
            "数据面调试日志", "Data-plane debug logging");

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

    private static ConfigKey<Boolean> bool(String path, ConfigScope scope, Domain domain, boolean defaultValue,
                                           String commentZh, String commentEn) {
        return add(path, scope, domain, ConfigType.BOOLEAN, defaultValue, null, null, commentZh, commentEn, Boolean.class);
    }

    private static ConfigKey<Integer> integer(String path, ConfigScope scope, Domain domain, int defaultValue, int min, int max,
                                              String commentZh, String commentEn) {
        return add(path, scope, domain, ConfigType.INT, defaultValue, min, max, commentZh, commentEn, Integer.class);
    }

    private static ConfigKey<Long> longValue(String path, ConfigScope scope, Domain domain, long defaultValue, long min, long max,
                                             String commentZh, String commentEn) {
        return add(path, scope, domain, ConfigType.LONG, defaultValue, min, max, commentZh, commentEn, Long.class);
    }

    private static ConfigKey<Double> decimal(String path, ConfigScope scope, Domain domain, double defaultValue, double min, double max,
                                             String commentZh, String commentEn) {
        return add(path, scope, domain, ConfigType.DOUBLE, defaultValue, min, max, commentZh, commentEn, Double.class);
    }

    private static ConfigKey<String> string(String path, ConfigScope scope, Domain domain, String defaultValue,
                                            String commentZh, String commentEn) {
        return add(path, scope, domain, ConfigType.STRING, defaultValue, null, null, commentZh, commentEn, String.class);
    }

    private static ConfigKey<List<String>> stringList(String path, ConfigScope scope, Domain domain,
                                                      Supplier<List<String>> defaultSupplier,
                                                      String commentZh, String commentEn) {
        return add(path, scope, domain, ConfigType.STRING_LIST, List.copyOf(defaultSupplier.get()), null, null,
                commentZh, commentEn, List.class);
    }

    private static <T> ConfigKey<T> add(String path, ConfigScope scope, Domain domain, ConfigType type, T defaultValue,
                                        Number min, Number max, String commentZh, String commentEn, Class<?> valueType) {
        ConfigKey<T> key = new ConfigKey<>(path, scope, valueType);
        ENTRIES.add(new ConfigEntry<>(key, path, scope, domain, type, defaultValue, min, max,
                ConfigComments.bilingual(commentZh, commentEn),
                "hassium.configuration." + path));
        return key;
    }
}
