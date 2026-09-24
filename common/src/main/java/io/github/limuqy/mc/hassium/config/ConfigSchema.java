package io.github.limuqy.mc.hassium.config;

import io.github.limuqy.mc.hassium.server.entity.EntityUpdateTiering;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** Loader-neutral configuration metadata shared by every platform backend. */
public final class ConfigSchema {
    private static final List<ConfigEntry<?>> ENTRIES = new ArrayList<>();

    // === 区块核心（chunk.*；CLIENT 15 键）===
    public static final ConfigKey<Boolean> CHUNK_ENABLED = bool("chunk.enabled", ConfigScope.CLIENT, Domain.CHUNK_CORE, true,
            "是否启用区块核心缓存", "Whether to enable the chunk-core cache");
    public static final ConfigKey<Integer> CHUNK_MAX_SIZE_MB = integer("chunk.maxSizeMb", ConfigScope.CLIENT, Domain.CHUNK_CORE, 4096, 64, 1024 * 1024,
            "缓存最大容量（MB；影子端存档容量上限，超限触发热度淘汰）",
            "Max cache size in MB (shadow-world disk cap; excess triggers heat eviction)");
    public static final ConfigKey<Double> CHUNK_HOT_SCORE_THRESHOLD = decimal("chunk.hotScoreThreshold", ConfigScope.CLIENT, Domain.CHUNK_CORE, 0.3, 0.0, 1.0,
            "热点分数阈值（低于此值视为冷 region 文件，清理时优先淘汰）",
            "Heat-score threshold (below = cold region file; preferred for eviction)");
    public static final ConfigKey<Double> CHUNK_RECENCY_WEIGHT = decimal("chunk.recencyWeight", ConfigScope.CLIENT, Domain.CHUNK_CORE, 0.7, 0.0, 1.0,
            "最近访问权重", "Recency weight in heat score");
    public static final ConfigKey<Double> CHUNK_FREQUENCY_WEIGHT = decimal("chunk.frequencyWeight", ConfigScope.CLIENT, Domain.CHUNK_CORE, 0.3, 0.0, 1.0,
            "访问频率权重", "Visit-frequency weight in heat score");
    public static final ConfigKey<Integer> CHUNK_CLEANUP_INTERVAL_TICKS = integer("chunk.cleanupIntervalTicks", ConfigScope.CLIENT, Domain.CHUNK_CORE, 6000, 20, 72000,
            "清理检查间隔（刻）", "Cleanup check interval in ticks");
    public static final ConfigKey<Integer> CHUNK_TARGET_SIZE_MB = integer("chunk.targetSizeMb", ConfigScope.CLIENT, Domain.CHUNK_CORE, 0, 0, 1024 * 1024,
            "目标缓存大小（MB；0=自动）", "Target cache size in MB (0 = auto)");
    public static final ConfigKey<Integer> CHUNK_MIN_CLEANUP_BATCH_SIZE = integer("chunk.minCleanupBatchSize", ConfigScope.CLIENT, Domain.CHUNK_CORE, 100, 1, 100000,
            "每轮最多淘汰的 region 文件数", "Max region files evicted per cleanup pass");
    public static final ConfigKey<Boolean> CHUNK_SECTION_DELTA_ENABLED = bool("chunk.sectionDeltaEnabled", ConfigScope.CLIENT, Domain.CHUNK_CORE, true,
            "是否启用分段增量（服务端规划 + 客户端应用）",
            "Enable section delta (server-side planning + client-side apply)");
    public static final ConfigKey<Integer> CHUNK_MAX_CHUNKS_PER_FRAME = integer("chunk.maxChunksPerFrame", ConfigScope.CLIENT, Domain.CHUNK_CORE, 6, 1, 512,
            "每 tick 缓存读取生产上限（影子入队 + 影子读盘；主线程消费只受时间预算）",
            "Per-tick cache-read production cap (shadow enqueue + shadow disk); consume is time-budget only");
    public static final ConfigKey<Integer> CHUNK_MAIN_THREAD_CHUNK_BUDGET_MS = integer("chunk.mainThreadChunkBudgetMs", ConfigScope.CLIENT, Domain.CHUNK_CORE, 15, 1, 50,
            "主线程 apply 预算（ms）", "Main-thread apply budget in ms");
    public static final ConfigKey<Boolean> CLIENT_CHUNK_SEED_GEN_ENABLED = bool("chunk.seedGenEnabled", ConfigScope.CLIENT, Domain.CHUNK_CORE, false,
            "是否启用 SeedGen（本地生成 pristine 区块；需双端同版本，默认关）。服务端开启时会下发世界种子",
            "Enable SeedGen (local pristine chunks; both sides same version; default off). Server enablement sends the world seed");
    public static final ConfigKey<Boolean> CHUNK_VIEW_DISTANCE_EXTENSION_ENABLED = bool("chunk.viewDistanceExtensionEnabled", ConfigScope.CLIENT, Domain.CHUNK_CORE, true,
            "超视渲染 OVD（影子双窗：clientRD>serverVD 时本地源回填环带）",
            "Beyond-view render OVD (shadow dual-window: local fill when clientRD > serverVD)");
    public static final ConfigKey<Integer> CHUNK_MAX_RENDER_DISTANCE = integer("chunk.maxRenderDistance", ConfigScope.CLIENT, Domain.CHUNK_CORE, 16, 2, 64,
            "超视渲染 effective clientRD 上限",
            "Max effective client render distance for OVD");
    public static final ConfigKey<Integer> CHUNK_LIGHT_HALO_RADIUS = integer("chunk.lightHaloRadius", ConfigScope.CLIENT, Domain.CHUNK_CORE,
            io.github.limuqy.mc.hassium.protocol.ShadowPullRadii.LIGHT_HALO_RADIUS,
            0, io.github.limuqy.mc.hassium.protocol.ShadowPullRadii.MAX_LIGHT_HALO_RADIUS,
            "光照光环半径（环）：影子端在服务端视距之外再多拉/算 N 环，专供权威边界柱补齐 3×3 邻域（对齐原版 ChunkStatus.LIGHT range=1）。"
                    + "光环柱只进影子端算光/缓存。0=关闭（回退到旧语义）。上限 1 = 服务端签发余量（切比雪夫外接盒比半径多 1），再大外环会被服务端拒",
            "Light halo radius in rings: the shadow pulls/computes N extra rings beyond the server view distance so that boundary columns get a complete 3x3 "
                    + "neighborhood (mirrors vanilla ChunkStatus.LIGHT range=1). Halo columns are computed only. 0 disables; max 1 = the server-issued slack "
                    + "(the chebyshev bounding box is one ring wider than the radius)");



    // === 存储域（storage.*；SERVER 2 键）===
    public static final ConfigKey<Boolean> STORAGE_ENABLED = bool("storage.enabled", ConfigScope.SERVER, Domain.STORAGE, false,
            "是否启用存档压缩（默认关；区块核心缓存独立不受影响）",
            "Enable save compression (default off; chunk cache unaffected)");
    public static final ConfigKey<Integer> STORAGE_ZSTD_LEVEL = integer("storage.zstdLevel", ConfigScope.SERVER, Domain.STORAGE, 3, 1, 22,
            "存储 ZSTD 压缩等级", "Storage ZSTD compression level");

    // === 服务端传输面（master.*；SERVER 17 键）===
    public static final ConfigKey<Boolean> MASTER_ENABLED = bool("master.enabled", ConfigScope.SERVER, Domain.MASTER_CORE, true,
            "是否启用服务端网络通道（压缩/聚合/区块推送/实体优化）", "Enable server network channel (compression/aggregation/chunk push/entity optimization)");
    public static final ConfigKey<Boolean> MASTER_ENABLED_ON_LAN = bool("master.enabledOnLan", ConfigScope.SERVER, Domain.MASTER_CORE, false,
            "局域网主机是否对远程玩家启用 Hassium 网络面（握手/聚合/推送/lightStrip 等）。默认关；本机 memory 连接始终原版；storage 仍仅专用服",
            "Enable Hassium network features for remote LAN players on an Open-to-LAN host (handshake/aggregation/push/lightStrip). Default off; host local memory connection stays vanilla; storage remains dedicated-only");
    public static final ConfigKey<Integer> MASTER_COMPRESSION_LEVEL = integer("master.compressionLevel", ConfigScope.SERVER, Domain.MASTER_CORE, 3, 1, 22,
            "自有通道 ZSTD 压缩等级", "Private-channel ZSTD level");
    public static final ConfigKey<Boolean> MASTER_PACKET_AGGREGATION = bool("master.enablePacketAggregation", ConfigScope.SERVER, Domain.MASTER_CORE, true,
            "是否启用包聚合", "Enable packet aggregation");
    public static final ConfigKey<Long> MASTER_AGGREGATION_MAX_WAIT = longValue("master.aggregationMaxWaitTimeMs", ConfigScope.SERVER, Domain.MASTER_CORE, 50L, 1L, 5000L,
            "冲刷兜底：超过该时长（ms）未冲刷则强制冲一次（tick 尾冲刷为主，应对主线程卡顿）",
            "Flush watchdog: force flush if none happened for this many ms (tick-end flush is primary; covers main-thread stalls)");
    public static final ConfigKey<Integer> MASTER_AGGREGATION_MAX_SIZE = integer("master.aggregationMaxSize", ConfigScope.SERVER, Domain.MASTER_CORE, 256 * 1024, 1024, 8 * 1024 * 1024,
            "聚合最大大小", "Aggregation max size (bytes)");
    public static final ConfigKey<List<String>> MASTER_COMPRESSION_BLACKLIST = stringList("master.compressionBlacklist", ConfigScope.SERVER, Domain.MASTER_CORE,
            () -> new ArrayList<>(HassiumConfig.MasterCoreConfig.DEFAULT_COMPRESSION_BLACKLIST),
            "第三方包 ID 的压缩/聚合排除列表（默认空）。Hassium 控制面与独立压缩通道已硬编码排除，改本列表不影响它们",
            "Third-party packet IDs excluded from compression / aggregation (default empty). Hassium control-plane and private channels are always hard-coded excluded; editing this list does not affect them");
    public static final ConfigKey<Integer> MASTER_MAX_CHUNKS_PER_TICK = integer("master.maxChunksPerTick", ConfigScope.SERVER, Domain.MASTER_CORE, 5, 1, 256,
            "每玩家每 tick 区块下发上限：Pull FULL/DELTA 完成 + 原版通道整柱发送（满 tick ≈ 本值×20/s）",
            "Per-player per-tick chunk send cap: Pull FULL/DELTA completions + vanilla whole-chunk path (≈ value×20/s at full tick)");

    // === 实体网络优化（master.entity*；SERVER 键族；分档表统一逗号分隔，减少配置量）===
    public static final ConfigKey<Boolean> MASTER_ENTITY_TIERED_UPDATE = bool("master.entityTieredUpdateEnabled", ConfigScope.SERVER, Domain.MASTER_CORE, true,
            "是否按玩家距离分四档降频下发实体更新（离得越远更新越稀）。默认开；关闭后距离档表失效，密度/压力/错峰仍可独立生效",
            "Enable distance-tiered entity updates (entities further away are updated less often). Enabled by default; turning it off disables only the distance tables — density, pressure and smooth-push remain independent");
    public static final ConfigKey<String> MASTER_ENTITY_TIER_INTERVALS = string("master.entityTierIntervals", ConfigScope.SERVER, Domain.MASTER_CORE, EntityUpdateTiering.DEFAULT_ENTITY_INTERVALS,
            "四个距离档的实体更新间隔（刻），用逗号分隔，依次为 近/中/远/边缘；挡位边界是实体跟踪范围的 25%/50%/75%。默认 3,4,6,10（越远越稀）。数字要大不要小，须非递减；写 0 或留空用默认值。最终生效间隔（乘上密度/压力倍率后）建议 ≤ 20 刻：客户端平滑窗口上限 20 刻，超出会回退 3 刻窗口、画面走-停",
            "Entity update interval in ticks for the four distance tiers, comma-separated, ordered near/mid/far/edge (tier boundaries at 25%/50%/75% of the tracking range). Default 3,4,6,10. Values must be non-decreasing; 0 or blank means default. Keep the final effective interval (after density/pressure multipliers) at 20 ticks or below: the client smoothing window caps at 20, beyond which it falls back to a 3-tick window and movement stutters");
    public static final ConfigKey<String> MASTER_ENTITY_ITEM_TIER_INTERVALS = string("master.entityItemTierIntervals", ConfigScope.SERVER, Domain.MASTER_CORE, EntityUpdateTiering.DEFAULT_ITEM_INTERVALS,
            "掉落物与经验球的四档更新间隔（刻），逗号分隔、顺序同上，默认 2,4,8,16。物品数量多、带宽吃紧时可以把它们调稀；贴近玩家的掉落物建议不超过 3 刻，否则看起来会一跳一跳",
            "Update interval in ticks for dropped items and experience orbs across the same four tiers, default 2,4,8,16. Raise these values when many items are on the ground; keep the near value at 3 ticks or lower so items next to the player still move smoothly");
    public static final ConfigKey<Boolean> MASTER_ENTITY_DENSITY_THROTTLE = bool("master.entityDensityThrottleEnabled", ConfigScope.SERVER, Domain.MASTER_CORE, true,
            "是否启用区块热点降频：某个区块里实体过于密集时，对其中实体进一步加大更新间隔。默认开",
            "Enable hotspot throttling: when too many entities pile up in one chunk, their update interval is stretched further. Enabled by default");
    public static final ConfigKey<String> MASTER_ENTITY_DENSITY_TIER_COUNTS = string("master.entityDensityTierCounts", ConfigScope.SERVER, Domain.MASTER_CORE, EntityUpdateTiering.DEFAULT_DENSITY_COUNTS,
            "每档热点阈值：实体所在区块的活跃实体数达到该值时，该档的间隔按对应倍率放大。逗号分隔按 近/中/远/边缘，默认 10,20,32,64，写 0 或留空用默认值",
            "Per-tier hotspot threshold: once the number of active entities in the entity's own chunk reaches this value, that tier's interval is multiplied by the matching factor. Comma-separated, near/mid/far/edge; default 10,20,32,64");
    public static final ConfigKey<String> MASTER_ENTITY_DENSITY_TIER_FACTORS = string("master.entityDensityTierFactors", ConfigScope.SERVER, Domain.MASTER_CORE, EntityUpdateTiering.DEFAULT_DENSITY_FACTORS,
            "每档热点倍率：达到上面阈值后间隔乘多少倍，逗号分隔按 近/中/远/边缘，默认 1.5,2.0,3.0,4.0（1.0 = 该档不放大）。支持小数；小于 1 按 1 处理；乘上压力倍率后再受 entityMaxThrottleFactor 限制",
            "Per-tier hotspot multiplier applied once the threshold above is reached, comma-separated, near/mid/far/edge; default 1.5,2.0,3.0,4.0 (1.0 means no change). Decimals allowed; values below 1 are treated as 1");
    public static final ConfigKey<Integer> MASTER_ENTITY_MAX_THROTTLE_FACTOR = integer("master.entityMaxThrottleFactor", ConfigScope.SERVER, Domain.MASTER_CORE, 5, 1, 16,
            "热点倍率与压力倍率相乘后的总上限（默认 5），用来兜住最坏情况；调大 = 密集时降得更狠",
            "Upper bound on the product of the hotspot factor and the pressure factor (default 5). Raise it to throttle harder in crowded areas");
    public static final ConfigKey<Integer> MASTER_ENTITY_FRAME_BUDGET_PER_PLAYER = integer("master.entityFrameBudgetPerPlayer", ConfigScope.SERVER, Domain.MASTER_CORE, 256, 0, 100000,
            "每个玩家每 tick 期望收到的实体更新包数（默认 256）。某个玩家持续超过这个量时，他视野内的实体更新会自动变稀，避免卡顿；0 = 不做这个自动限制",
            "Expected entity update packets per player per tick (default 256). When a player keeps exceeding it, entities in their view are updated less often to avoid lag; 0 = no automatic limit");
    public static final ConfigKey<Boolean> MASTER_ENTITY_SMOOTH_PUSH = bool("master.entitySmoothPushEnabled", ConfigScope.SERVER, Domain.MASTER_CORE, true,
            "实体错峰推送：同一更新间隔的实体按 UUID 稳定错开发送时刻，3 刻总量不变但不再齐发尖峰。默认开；关闭后退回原版齐发",
            "Entity smooth push: entities sharing the same update interval are phase-staggered by UUID so total volume over an interval is unchanged but the per-tick spike is flattened. Enabled by default");

    // === 区块核心（chunk.*；SERVER 2 键）===
    public static final ConfigKey<Boolean> SERVER_CHUNK_SEED_GEN_ENABLED = bool("chunk.seedGenEnabled", ConfigScope.SERVER, Domain.CHUNK_CORE, false,
            "是否启用 SeedGen（服务端开启下发世界种子；客户端门控开时影子 tracking 触发 vanilla worldgen 本地生成，再 compare-pull；需双端同版本，默认关）。警告：开启会向客户端下发世界种子，等同泄露服务端种子",
            "Enable SeedGen (server sends world seed; client gates local worldgen via shadow tracking then compare-pull; both sides same version; default off). WARNING: this leaks the server world seed to clients");
    public static final ConfigKey<Boolean> CHUNK_LIGHT_STRIP = bool("chunk.lightStrip", ConfigScope.SERVER, Domain.CHUNK_CORE, true,
            "是否启用光照剥离（核心省带宽：真服剥光，影子端算光后打官方柱包；默认开）",
            "Enable light stripping (core bandwidth saver: server strips light, shadow packs official chunk packets; default on)");

    // === 兼容性（SERVER）===
    public static final ConfigKey<Boolean> COMPAT_REQUIRE_CLIENT_MOD = bool("compat.requireClientMod", ConfigScope.SERVER, Domain.COMPAT, false,
            "是否强制要求客户端安装 Hassium", "Require the Hassium client mod");
    public static final ConfigKey<Boolean> COMPAT_AUTO_DOWNGRADE = bool("compat.autoDowngradeOnError", ConfigScope.SERVER, Domain.COMPAT, true,
            "出错时是否自动降级", "Auto-downgrade on error");

    // === 调试（debug.*；CLIENT 11 键：元数据/缓存/光照/网络指标为客户端专属）===
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
            "光照验算与光包落地探针（历史别名）", "Legacy alias of debug.lightLogging");
    /** 光照调试总开关：算光链路日志（[SHADOW_LIGHT]/[LIGHT_GATE]）+ 引擎/光包探针 + lightProbe 计数块。 */
    public static final ConfigKey<Boolean> CLIENT_DEBUG_LIGHT = bool("debug.lightLogging", ConfigScope.CLIENT, Domain.DEBUG, false,
            "光照调试（算光链路日志 + 探针）", "Light debug logging (light pipeline logs + probes)");
    public static final ConfigKey<Boolean> CLIENT_DEBUG_NETWORK_METRICS = bool("debug.networkMetricsEnabled", ConfigScope.CLIENT, Domain.DEBUG, false,
            "是否启用客户端网络指标", "Enable client network metrics");
    public static final ConfigKey<Boolean> CLIENT_DEBUG_NETWORK_METRICS_AUTO_RESET = bool("debug.networkMetricsAutoReset", ConfigScope.CLIENT, Domain.DEBUG, true,
            "登出服务器时自动重置网络指标", "Auto-reset network metrics when leaving a server");

    // === 调试（debug.*；SERVER 6 键：不含元数据/缓存/光照/网络指标）===
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
    /** 导出点在握手/聚合 gating 之前（MixinConnection send 拦截 + flushBatch 聚合帧），单人集成服 memory 连接同样生效。 */
    public static final ConfigKey<Boolean> SERVER_DEBUG_EXPORT_AGGREGATED_PACKETS = bool("debug.exportAggregatedPackets", ConfigScope.SERVER, Domain.DEBUG, false,
            "导出聚合包调试流：所有经过服务端发包拦截点的 S2C 包（含编码后的聚合帧）逐行写入 logs/hassium-aggregated-packets/*.jsonl（payload 为 base64）。单人/LAN 集成服同样生效；每包写盘，性能开销大，仅诊断用",
            "Export aggregated-packet debug stream: every S2C packet passing the server send intercept (incl. encoded aggregation frames) is appended to logs/hassium-aggregated-packets/*.jsonl (payload base64). Works on singleplayer / integrated servers too; writes on every packet — diagnostics only");

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
