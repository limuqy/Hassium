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
 * 物理客户端：{@code hassium/hassium-client.toml}（chunk + net + 客户端 debug）<br>
 * 专用服：{@code hassium/hassium-server.toml}（storage + master + compat + 服务端 debug）
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
            stripUtf8BomIfPresent(path);
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

    /**
     * 剥离 UTF-8 BOM（EF BB BF）：PowerShell 等工具写出的 toml 带 BOM 时，
     * night-config 解析直接抛 ParsingException（"Invalid bare key: ﻿[…"），
     * 整份配置回落默认。读前自愈式剥除并留痕。
     */
    private static void stripUtf8BomIfPresent(Path path) throws java.io.IOException {
        byte[] all = Files.readAllBytes(path);
        byte[] stripped = strippedOfUtf8Bom(all);
        if (stripped != all) {
            Files.write(path, stripped);
            LOGGER.info("Hassium: Stripped UTF-8 BOM from {} (night-config cannot parse it)", path);
        }
    }

    /** BOM 剥离纯函数测试缝：带 BOM 返回新数组（去头 3 字节），否则原样返回同一引用。 */
    static byte[] strippedOfUtf8Bom(byte[] data) {
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF
                && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            return java.util.Arrays.copyOfRange(data, 3, data.length);
        }
        return data;
    }

    private static List<ConfigEntry<?>> entries(ConfigScope scope) {
        return scope == ConfigScope.CLIENT ? ConfigSchema.clientEntries() : ConfigSchema.serverEntries();
    }

    private static Object readSchemaValue(CommentedConfig cfg, ConfigEntry<?> entry) {
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
        set(cfg, entry.path(), value, entry.comment());
    }


    private static HassiumConfig loadClient() throws java.io.IOException {
        return loadClientFile(clientPath());
    }
    static HassiumConfig loadClient(Path configRoot) throws java.io.IOException {
        return loadClientFile(configRoot.resolve(Constants.CONFIG_CLIENT_FILE));
    }

    static void saveClient(Path configRoot, HassiumConfig config) {
        try {
            Files.createDirectories(configRoot.resolve(Constants.CONFIG_CLIENT_FILE).getParent());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("无法创建临时客户端配置目录", e);
        }
        writeClient(configRoot.resolve(Constants.CONFIG_CLIENT_FILE), config.chunk(), config.debug());
    }

    private static HassiumConfig loadClientFile(Path client) throws java.io.IOException {
        HassiumConfig.ChunkCoreConfig chunk = HassiumConfig.ChunkCoreConfig.DEFAULT;
        HassiumConfig.MasterCoreConfig master = HassiumConfig.MasterCoreConfig.DEFAULT;
        HassiumConfig.DebugConfig debug = HassiumConfig.DebugConfig.DEFAULT;

        Files.createDirectories(client.getParent());

        if (Files.isRegularFile(client)) {
            try (CommentedFileConfig cfg = open(client)) {
                cfg.load();
                chunk = readChunkCore(cfg);
                debug = readDebug(cfg);
            } catch (Exception e) {
                LOGGER.warn("Hassium: 读取 {} 失败，使用默认客户端配置", client, e);
            }
        } else {
            writeClient(client, chunk, debug);
        }

        return new HassiumConfig(
                HassiumConfig.StorageConfig.DEFAULT,
                chunk,
                master,
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
        writeServer(configRoot.resolve(Constants.CONFIG_SERVER_FILE), config.storage(), config.chunk(), config.master(),
                config.compat(), config.debug());
    }

    private static HassiumConfig loadServerFile(Path server) throws java.io.IOException {
        HassiumConfig.StorageConfig storage = HassiumConfig.StorageConfig.DEFAULT;
        HassiumConfig.ChunkCoreConfig chunk = HassiumConfig.ChunkCoreConfig.DEFAULT;
        HassiumConfig.MasterCoreConfig master = HassiumConfig.MasterCoreConfig.DEFAULT;
        HassiumConfig.CompatConfig compat = HassiumConfig.CompatConfig.DEFAULT;
        HassiumConfig.DebugConfig debug = HassiumConfig.DebugConfig.DEFAULT;

        Files.createDirectories(server.getParent());
        if (Files.isRegularFile(server)) {
            try (CommentedFileConfig cfg = open(server)) {
                cfg.load();
                storage = readStorage(cfg);
                chunk = readChunkCore(cfg);
                master = readMasterCore(cfg);
                compat = readCompat(cfg);
                debug = readDebug(cfg);
            } catch (Exception e) {
                LOGGER.warn("Hassium: 读取 {} 失败，使用默认服务端配置", server, e);
            }
        } else {
            writeServer(server, storage, chunk, master, compat, debug);
        }
        return new HassiumConfig(storage, chunk, master, compat, debug);
    }
    public static void save(HassiumConfig config) {
        try {
            boolean physicalClient = Services.PLATFORM.isPhysicalClient();
            Files.createDirectories(
                    physicalClient ? clientPath().getParent() : serverPath().getParent()
            );
            if (physicalClient) {
                writeClient(clientPath(), config.chunk(), config.debug());
            } else {
                writeServer(serverPath(), config.storage(), config.chunk(), config.master(), config.compat(), config.debug());
            }
        } catch (Exception e) {
            LOGGER.error("Hassium: Toml 配置保存失败", e);
        }
    }

    // --- IO helpers ---

    private static CommentedFileConfig open(Path path) {
        return CommentedFileConfig.builder(path)
                // review-fix: T6-56 核验结论：NightConfig 3.6.7 的 .sync() = save() 阻塞式落盘
                // （非「每次 set 整文件写盘」——那是 .autosave()；已核对 3.6.7 sources javadoc 与
                // WriteSyncFileConfig/ConfigWrapper 字节码）。set() 仅改内存，write*/saveValues 末尾
                // cfg.save() 单次同步整文件落盘，审查所述「一次保存 20+ 次重写」不成立。
                // 维持 .sync()：移除会使 save() 异步化，写失败脱离调用方 try/catch（静默丢失），属回归。
                .sync()
                .preserveInsertionOrder()
                .writingMode(WritingMode.REPLACE)
                .build();
    }

    private static void writeClient(
            Path path,
            HassiumConfig.ChunkCoreConfig chunk,
            HassiumConfig.DebugConfig debug
    ) {
        try (CommentedFileConfig cfg = open(path)) {
            writeChunkCore(cfg, chunk);
            writeClientDebug(cfg, debug);
            cfg.save();
        }
    }

    private static void writeServer(
            Path path,
            HassiumConfig.StorageConfig storage,
            HassiumConfig.ChunkCoreConfig chunk,
            HassiumConfig.MasterCoreConfig master,
            HassiumConfig.CompatConfig compat,
            HassiumConfig.DebugConfig debug
    ) {
        try (CommentedFileConfig cfg = open(path)) {
            writeStorage(cfg, storage);
            writeServerChunk(cfg, chunk);
            writeMasterCore(cfg, master);
            writeCompat(cfg, compat);
            writeServerDebug(cfg, debug);
            cfg.save();
        }
    }

    /** 服务端 chunk.* 键（chunk.lightStrip / chunk.seedGenEnabled；仅专用服 toml）。 */
    private static void writeServerChunk(CommentedConfig cfg, HassiumConfig.ChunkCoreConfig chunk) {
        set(cfg, "chunk.lightStrip", chunk.lightStrip(), "是否启用光照剥离");
        set(cfg, "chunk.seedGenEnabled", chunk.seedGenEnabled(),
                "是否启用 SeedGen（服务端对 pristine 区块发 SeedRef 替代区块数据；客户端本地生成，hash 校验兜底；需双端同版本，默认关）");
    }

    // --- CLIENT ---

    private static HassiumConfig.ChunkCoreConfig readChunkCore(CommentedConfig cfg) {
        var d = HassiumConfig.ChunkCoreConfig.DEFAULT;
        return new HassiumConfig.ChunkCoreConfig(
                getBool(cfg, "chunk.enabled", d.enabled()),
                getInt(cfg, "chunk.maxSizeMb", d.maxSizeMb()),
                getDouble(cfg, "chunk.hotScoreThreshold", d.hotScoreThreshold()),
                getDouble(cfg, "chunk.recencyWeight", d.recencyWeight()),
                getDouble(cfg, "chunk.frequencyWeight", d.frequencyWeight()),
                getInt(cfg, "chunk.cleanupIntervalTicks", d.cleanupIntervalTicks()),
                getInt(cfg, "chunk.targetSizeMb", d.targetSizeMb()),
                getInt(cfg, "chunk.minCleanupBatchSize", d.minCleanupBatchSize()),
                getBool(cfg, "chunk.sectionDeltaEnabled", d.sectionDeltaEnabled()),
                getBool(cfg, "chunk.viewDistanceExtensionEnabled", d.viewDistanceExtensionEnabled()),
                getInt(cfg, "chunk.maxRenderDistance", d.maxRenderDistance()),
                getBool(cfg, "chunk.ovdLocalGeneration", d.ovdLocalGeneration()),
                getInt(cfg, "chunk.maxChunksPerFrame", d.maxChunksPerFrame()),
                getInt(cfg, "chunk.mainThreadChunkBudgetMs", d.mainThreadChunkBudgetMs()),
                getInt(cfg, "chunk.seedGenThreads", d.seedGenThreads()),
                getBool(cfg, "chunk.seedGenEnabled", d.seedGenEnabled()),
                getBool(cfg, "chunk.lightStrip", d.lightStrip())
        );
    }

    private static void writeChunkCore(CommentedConfig cfg, HassiumConfig.ChunkCoreConfig c) {
        set(cfg, "chunk.enabled", c.enabled(), "是否启用区块核心缓存");
        set(cfg, "chunk.maxSizeMb", c.maxSizeMb(), "缓存最大容量");
        set(cfg, "chunk.hotScoreThreshold", c.hotScoreThreshold(), "热点分数阈值");
        set(cfg, "chunk.recencyWeight", c.recencyWeight(), "最近访问权重");
        set(cfg, "chunk.frequencyWeight", c.frequencyWeight(), "访问频率权重");
        set(cfg, "chunk.cleanupIntervalTicks", c.cleanupIntervalTicks(), "清理检查间隔");
        set(cfg, "chunk.targetSizeMb", c.targetSizeMb(), "目标缓存大小");
        set(cfg, "chunk.minCleanupBatchSize", c.minCleanupBatchSize(), "每轮淘汰 region 文件数");
        set(cfg, "chunk.sectionDeltaEnabled", c.sectionDeltaEnabled(), "启用分段增量");
        set(cfg, "chunk.viewDistanceExtensionEnabled", c.viewDistanceExtensionEnabled(), "启用超视渲染 OVD");
        set(cfg, "chunk.maxRenderDistance", c.maxRenderDistance(), "超视渲染 effective clientRD 上限");
        set(cfg, "chunk.ovdLocalGeneration", c.ovdLocalGeneration(), "OVD 窗缓存 miss 本地生成");
        set(cfg, "chunk.maxChunksPerFrame", c.maxChunksPerFrame(), "每帧最大区块数");
        set(cfg, "chunk.mainThreadChunkBudgetMs", c.mainThreadChunkBudgetMs(), "主线程区块预算");
        set(cfg, "chunk.seedGenThreads", c.seedGenThreads(), "SeedGen 线程数");
        set(cfg, "chunk.seedGenEnabled", c.seedGenEnabled(), "启用 SeedGen");
        set(cfg, "chunk.lightStrip", c.lightStrip(), "启用服务端光照剥离");
    }


    private static HassiumConfig.StorageConfig readStorage(CommentedConfig cfg) {
        var d = HassiumConfig.StorageConfig.DEFAULT;
        return new HassiumConfig.StorageConfig(
                getBool(cfg, "storage.enabled", d.enabled()),
                getInt(cfg, "storage.zstdLevel", d.zstdLevel())
        );
    }

    private static void writeStorage(CommentedConfig cfg, HassiumConfig.StorageConfig s) {
        set(cfg, "storage.enabled", s.enabled(), "是否启用存档压缩（启用前请备份）");
        set(cfg, "storage.zstdLevel", s.zstdLevel(), "存储 ZSTD 压缩等级");
    }

    private static HassiumConfig.MasterCoreConfig readMasterCore(CommentedConfig cfg) {
        var d = HassiumConfig.MasterCoreConfig.DEFAULT;
        return new HassiumConfig.MasterCoreConfig(
                getBool(cfg, "master.enabled", d.enabled()),
                getInt(cfg, "master.compressionLevel", d.compressionLevel()),
                getBool(cfg, "master.useContextCompression", d.useContextCompression()),
                getBool(cfg, "master.enablePacketAggregation", d.enablePacketAggregation()),
                getInt(cfg, "master.aggregationMinBatchSize", d.aggregationMinBatchSize()),
                getLong(cfg, "master.aggregationMaxWaitTimeMs", d.aggregationMaxWaitTimeMs()),
                getInt(cfg, "master.aggregationMaxSize", d.aggregationMaxSize()),
                getStringSet(cfg, "master.compressionBlacklist", d.compressionBlacklist()),
                getInt(cfg, "master.maxChunksPerTick", d.maxChunksPerTick()),
                getInt(cfg, "master.serverChunkPushThreads", d.serverChunkPushThreads())
        );
    }

    private static void writeMasterCore(CommentedConfig cfg, HassiumConfig.MasterCoreConfig n) {
        set(cfg, "master.enabled", n.enabled(), "是否启用主控核心网络通道");
        set(cfg, "master.compressionLevel", n.compressionLevel(), "自有通道 ZSTD 等级");
        set(cfg, "master.useContextCompression", n.useContextCompression(), "是否使用上下文压缩");
        set(cfg, "master.enablePacketAggregation", n.enablePacketAggregation(), "是否启用包聚合");
        set(cfg, "master.aggregationMinBatchSize", n.aggregationMinBatchSize(), "聚合最小批量");
        set(cfg, "master.aggregationMaxWaitTimeMs", (int) n.aggregationMaxWaitTimeMs(), "聚合最大等待（ms）");
        set(cfg, "master.aggregationMaxSize", n.aggregationMaxSize(), "聚合最大大小（字节）");
        set(cfg, "master.compressionBlacklist", new ArrayList<>(n.compressionBlacklist()), "压缩/聚合黑名单");
        set(cfg, "master.maxChunksPerTick", n.maxChunksPerTick(), "每玩家每 tick 提交到后台序列化的区块上限（发送速率 = 本值 × tick 节奏，满 tick ≈ 本值×20/s，仅服务端）");
        set(cfg, "master.serverChunkPushThreads", n.serverChunkPushThreads(), "服务端区块推送线程数（encode/hash/ZSTD 固定后台池，仅服务端）");
        // legacy 键清理：网关监听/鉴权/控制面端点/L1 迁移/续流票据/数据面已随网关拓扑退役；
        // 管线级全局包压缩（globalPacketCompression/globalCompressionLevel/globalCompressionThreshold/magiclessZstd）
        // 已随直连拓扑退役（通道压缩由聚合字典 ZSTD + 区块推送自有压缩承担）
        // ovdUnloadDelaySecs：延迟卸载取消，双窗 OVD 不恢复
        cfg.remove("chunk.ovdUnloadDelaySecs");
        cfg.remove("master.dynamicThreadPoolEnabled");
        cfg.remove("master.minPushThreads");
        cfg.remove("master.maxPushThreads");
        cfg.remove("master.magiclessZstd");
        cfg.remove("master.globalPacketCompression");
        cfg.remove("master.globalCompressionLevel");
        cfg.remove("master.globalCompressionThreshold");
        cfg.remove("master.bindHost");
        cfg.remove("master.authToken");
        cfg.remove("master.controlReachableEndpoints");
        cfg.remove("master.migrationFaultTimeoutMs");
        cfg.remove("master.migrationPrewarmTtlMs");
        cfg.remove("master.resumeTicketTtlMs");
        cfg.remove("master.migrationMinTps");
        cfg.remove("master.migrationMaxLoadAverage");
        cfg.remove("master.migrationMaintenanceWindow");
        cfg.remove("master.migrationHeartbeatIntervalMs");
        cfg.remove("master.migrationIdleWindowMs");
        cfg.remove("master.migrationSilentTimeoutMs");
        cfg.remove("dataplane.enabled");
        cfg.remove("dataplane.udpListeners");
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
                getBool(cfg, "debug.lightVerify", d.lightVerify()),
                getBool(cfg, "debug.networkMetricsEnabled", d.networkMetricsEnabled()),
                getBool(cfg, "debug.networkMetricsAutoReset", d.networkMetricsAutoReset())
        );
    }

    private static void writeClientDebug(CommentedConfig cfg, HassiumConfig.DebugConfig d) {
        set(cfg, "debug.metadataLogging", d.metadataLogging(), "元数据调试日志");
        set(cfg, "debug.dispatcherLogging", d.dispatcherLogging(), "主线程调度调试日志");
        set(cfg, "debug.asyncLogging", d.asyncLogging(), "异步任务调试日志");
        set(cfg, "debug.compressionLogging", d.compressionLogging(), "压缩调试日志");
        set(cfg, "debug.chunkApplyLogging", d.chunkApplyLogging(), "区块 apply 调试日志");
        set(cfg, "debug.networkLogging", d.networkLogging(), "网络调试日志");
        set(cfg, "debug.cacheLogging", d.cacheLogging(), "缓存调试日志");
        set(cfg, "debug.lightVerify", d.lightVerify(), "光照验算与光包落地探针（CHUNK_PROBE source=light）");
        set(cfg, "debug.networkMetricsEnabled", d.networkMetricsEnabled(), "客户端网络指标");
        set(cfg, "debug.networkMetricsAutoReset", d.networkMetricsAutoReset(), "退出服务器时自动复位网络指标");
    }

    private static void writeServerDebug(CommentedConfig cfg, HassiumConfig.DebugConfig d) {
        set(cfg, "debug.dispatcherLogging", d.dispatcherLogging(), "主线程调度调试日志");
        set(cfg, "debug.asyncLogging", d.asyncLogging(), "异步任务调试日志");
        set(cfg, "debug.compressionLogging", d.compressionLogging(), "压缩调试日志");
        set(cfg, "debug.chunkApplyLogging", d.chunkApplyLogging(), "区块 apply 调试日志");
        set(cfg, "debug.networkLogging", d.networkLogging(), "网络调试日志");
    }

    // --- value helpers ---

    /**
     * 写键值与备注。若 path 在 {@link ConfigSchema} 中有登记，优先用 schema 双语备注
     * （中文一行 / 英文一行）；否则用 {@code fallbackComment}。
     */
    private static void set(CommentedConfig cfg, String path, Object value, String fallbackComment) {
        cfg.setComment(path, schemaCommentOr(path, fallbackComment));
        cfg.set(path, value);
    }

    private static String schemaCommentOr(String path, String fallbackComment) {
        for (ConfigEntry<?> entry : ConfigSchema.entries()) {
            if (entry.path().equals(path) && entry.comment() != null && !entry.comment().isBlank()) {
                return entry.comment();
            }
        }
        return fallbackComment;
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
}