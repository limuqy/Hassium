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
        if (entry.key() == ConfigSchema.MASTER_CONTROL_ENDPOINTS) {
            return readReachableEndpoints(cfg, entry.path(), entry.path(), MAX_CONTROL_ENDPOINTS).stream()
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
        if (entry.key() == ConfigSchema.MASTER_CONTROL_ENDPOINTS) {
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
        writeClient(configRoot.resolve(Constants.CONFIG_CLIENT_FILE), config.chunk(), config.net(), config.master(), config.debug());
    }

    private static HassiumConfig loadClientFile(Path client) throws java.io.IOException {
        HassiumConfig.ChunkCoreConfig chunk = HassiumConfig.ChunkCoreConfig.DEFAULT;
        HassiumConfig.NetCoreConfig net = HassiumConfig.NetCoreConfig.DEFAULT;
        HassiumConfig.MasterCoreConfig master = HassiumConfig.MasterCoreConfig.DEFAULT;
        HassiumConfig.DebugConfig debug = HassiumConfig.DebugConfig.DEFAULT;

        Files.createDirectories(client.getParent());

        if (Files.isRegularFile(client)) {
            try (CommentedFileConfig cfg = open(client)) {
                cfg.load();
                chunk = readChunkCore(cfg);
                net = readNetCore(cfg);
                master = readClientMigrationPolicy(cfg, master);
                debug = readDebug(cfg);
            } catch (Exception e) {
                LOGGER.warn("Hassium: 读取 {} 失败，使用默认客户端配置", client, e);
            }
        } else {
            writeClient(client, chunk, net, master, debug);
        }

        return new HassiumConfig(
                HassiumConfig.StorageConfig.DEFAULT,
                chunk,
                net,
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
        return new HassiumConfig(storage, chunk,
                HassiumConfig.NetCoreConfig.DEFAULT, master, compat, debug);
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
                writeClient(clientPath(), config.chunk(), config.net(), config.master(), config.debug());
            } else {
                writeServer(serverPath(), config.storage(), config.chunk(), config.master(), config.compat(), config.debug());
            }
            LOGGER.info("Hassium: Toml 配置已保存");
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
            HassiumConfig.NetCoreConfig net,
            HassiumConfig.MasterCoreConfig master,
            HassiumConfig.DebugConfig debug
    ) {
        try (CommentedFileConfig cfg = open(path)) {
            writeChunkCore(cfg, chunk);
            writeNetCore(cfg, net);
            writeClientMigrationPolicy(cfg, master);
            writeClientDebug(cfg, debug);
            cfg.save();
        }
    }

    /** 客户端 toml 的 master 迁移策略键（CLIENT scope；缺省回退传入默认）。端点/鉴权由 gateway_info 下发，不读写。 */
    private static HassiumConfig.MasterCoreConfig readClientMigrationPolicy(
            CommentedConfig cfg, HassiumConfig.MasterCoreConfig d
    ) {
        return d.withMigrationPolicy(
                getDouble(cfg, "master.migrationMinTps", d.migrationMinTps()),
                getDouble(cfg, "master.migrationMaxLoadAverage", d.migrationMaxLoadAverage()),
                getString(cfg, "master.migrationMaintenanceWindow", d.migrationMaintenanceWindow()),
                getPositiveLong(cfg, "master.migrationHeartbeatIntervalMs", d.migrationHeartbeatIntervalMs()),
                getPositiveLong(cfg, "master.migrationIdleWindowMs", d.migrationIdleWindowMs()),
                getPositiveLong(cfg, "master.migrationSilentTimeoutMs", d.migrationSilentTimeoutMs()));
    }

    /** 客户端 toml 的 master 迁移策略键（CLIENT scope；仅 migration* 6 键）。 */
    private static void writeClientMigrationPolicy(CommentedConfig cfg, HassiumConfig.MasterCoreConfig master) {
        set(cfg, "master.migrationMinTps", master.migrationMinTps(), "L1 迁移策略：主控 TPS 低于此值触发迁移");
        set(cfg, "master.migrationMaxLoadAverage", master.migrationMaxLoadAverage(), "L1 迁移策略：主控系统负载均值高于此值触发迁移（getSystemLoadAverage 为 -1 视为无信号）");
        set(cfg, "master.migrationMaintenanceWindow", master.migrationMaintenanceWindow(), "L1 迁移策略：维护窗口 \"HH:MM-HH:MM\"（本地时区，含跨午夜）；空串=禁用");
        set(cfg, "master.migrationHeartbeatIntervalMs", master.migrationHeartbeatIntervalMs(), "L1 迁移：应用层 HEARTBEAT 发送周期（ms）");
        set(cfg, "master.migrationIdleWindowMs", master.migrationIdleWindowMs(), "L1 迁移：空闲窗口判定时长（ms；玩家静止 + 区块 hash 稳定，适合迁移的时机）");
        set(cfg, "master.migrationSilentTimeoutMs", master.migrationSilentTimeoutMs(),
                "L1 迁移：outbound 入站静默超时（ms；默认 10s 使失效识别 ≤15s；未配置时回退 master.migrationFaultTimeoutMs 语义）");
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
                getInt(cfg, "chunk.compressionLevel", d.compressionLevel()),
                getDouble(cfg, "chunk.hotScoreThreshold", d.hotScoreThreshold()),
                getDouble(cfg, "chunk.recencyWeight", d.recencyWeight()),
                getDouble(cfg, "chunk.frequencyWeight", d.frequencyWeight()),
                getInt(cfg, "chunk.cleanupIntervalTicks", d.cleanupIntervalTicks()),
                getInt(cfg, "chunk.targetSizeMb", d.targetSizeMb()),
                getInt(cfg, "chunk.minCleanupBatchSize", d.minCleanupBatchSize()),
                getBool(cfg, "chunk.sectionDeltaEnabled", d.sectionDeltaEnabled()),
                getBool(cfg, "chunk.joinBoostEnabled", d.joinBoostEnabled()),
                getBool(cfg, "chunk.viewDistanceExtensionEnabled", d.viewDistanceExtensionEnabled()),
                getInt(cfg, "chunk.maxRenderDistance", d.maxRenderDistance()),
                getInt(cfg, "chunk.ovdUnloadDelaySecs", d.ovdUnloadDelaySecs()),
                getInt(cfg, "chunk.unloadDelaySecs", d.unloadDelaySecs()),
                getInt(cfg, "chunk.maxChunksPerFrame", d.maxChunksPerFrame()),
                getInt(cfg, "chunk.mainThreadChunkBudgetMs", d.mainThreadChunkBudgetMs()),
                getInt(cfg, "chunk.seedGenThreads", d.seedGenThreads()),
                getBool(cfg, "chunk.hassiumEngineEnabled", d.hassiumEngineEnabled()),
                getBool(cfg, "chunk.ovdLocalGeneration", d.ovdLocalGeneration()),
                getBool(cfg, "chunk.seedGenEnabled", d.seedGenEnabled()),
                getBool(cfg, "chunk.lightStrip", d.lightStrip())
        );
    }

    private static void writeChunkCore(CommentedConfig cfg, HassiumConfig.ChunkCoreConfig c) {
        set(cfg, "chunk.enabled", c.enabled(), "是否启用区块核心缓存");
        set(cfg, "chunk.maxSizeMb", c.maxSizeMb(), "缓存最大容量（MB；影子端存档容量上限，超限触发热度淘汰）");
        set(cfg, "chunk.compressionLevel", c.compressionLevel(), "缓存压缩等级");
        set(cfg, "chunk.hotScoreThreshold", c.hotScoreThreshold(), "热点分数阈值");
        set(cfg, "chunk.recencyWeight", c.recencyWeight(), "最近访问权重");
        set(cfg, "chunk.frequencyWeight", c.frequencyWeight(), "访问频率权重");
        set(cfg, "chunk.cleanupIntervalTicks", c.cleanupIntervalTicks(), "清理检查间隔（刻）");
        set(cfg, "chunk.targetSizeMb", c.targetSizeMb(), "目标缓存大小（MB；0=自动）");
        set(cfg, "chunk.minCleanupBatchSize", c.minCleanupBatchSize(), "每次最少清理区块数");
        set(cfg, "chunk.sectionDeltaEnabled", c.sectionDeltaEnabled(),
                "分段增量（GatewayPacketCodec/NetworkCore/DataPlaneClientBundle 活跃消费；默认 true）");
        set(cfg, "chunk.joinBoostEnabled", c.joinBoostEnabled(),
                "进服后短时提高主线程预算加速加载（默认 true）");
        set(cfg, "chunk.viewDistanceExtensionEnabled", c.viewDistanceExtensionEnabled(),
                "是否启用超视渲染（客户端 RD > 服务端视距时本地缓存回填环带）");
        set(cfg, "chunk.maxRenderDistance", c.maxRenderDistance(), "超视渲染 / 有效 RD 上限（Fog/内存约束）");
        set(cfg, "chunk.ovdUnloadDelaySecs", c.ovdUnloadDelaySecs(), "离开超视渲染环带后延迟卸载秒数");
        set(cfg, "chunk.unloadDelaySecs", c.unloadDelaySecs(), "影子端内存区块回收延迟秒数（离开卸载边界后计时，超时落盘并清内存；0=禁用回收）");
        set(cfg, "chunk.maxChunksPerFrame", c.maxChunksPerFrame(), "每帧区块主线程操作硬顶（apply 回调 + OVD 入队 + 影子回传消费共用）");
        set(cfg, "chunk.mainThreadChunkBudgetMs", c.mainThreadChunkBudgetMs(), "主线程 apply 预算（ms）");
        set(cfg, "chunk.hassiumEngineEnabled", c.hassiumEngineEnabled(),
                "是否启用Hassium 引擎（默认 true）：进服启动Hassium 引擎服务端统一承担区块光照计算，客户端不再计算。启动失败自动降级：客户端缓存/超视渲染/SeedGen/Hassium 引擎光照关闭并游戏内提示；false=不启动Hassium 引擎（此时服务端不剥光，光照随包自带）");
        set(cfg, "chunk.ovdLocalGeneration", c.ovdLocalGeneration(),
                "OVD 本地生成（默认 false）：超视渲染区域缓存 miss 时用Hassium 引擎按服务端世界种子本地生成区块并存入本地缓存；无种子（服务端未装 MOD）时自动关闭生成");
        set(cfg, "chunk.seedGenThreads", c.seedGenThreads(),
                "SeedGen 本地生成线程数（固定平台线程池；0=禁用本地生成，SeedRef 一律回退全量）");
        set(cfg, "chunk.seedGenEnabled", c.seedGenEnabled(),
                "是否启用 SeedGen（本地生成 pristine 区块；需双端同版本，默认关）");
    }

    private static HassiumConfig.NetCoreConfig readNetCore(CommentedConfig cfg) {
        var d = HassiumConfig.NetCoreConfig.DEFAULT;
        return new HassiumConfig.NetCoreConfig(
                getBool(cfg, "net.enabled", d.enabled()),
                getBool(cfg, "net.metricsEnabled", d.metricsEnabled()),
                getBool(cfg, "net.metricsAutoReset", d.metricsAutoReset())
        );
    }

    private static void writeNetCore(CommentedConfig cfg, HassiumConfig.NetCoreConfig n) {
        set(cfg, "net.enabled", n.enabled(), "是否启用客户端网络核心（2.0.0 进程内网关与帧连接总开关）");
        set(cfg, "net.metricsEnabled", n.metricsEnabled(), "是否启用指标收集");
        set(cfg, "net.metricsAutoReset", n.metricsAutoReset(), "登出服务器时自动重置指标计数（默认 true）");
    }

    // --- SERVER ---

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
                getBool(cfg, "master.magiclessZstd", d.magiclessZstd()),
                getBool(cfg, "master.globalPacketCompression", d.globalPacketCompression()),
                getInt(cfg, "master.globalCompressionLevel", d.globalCompressionLevel()),
                getInt(cfg, "master.globalCompressionThreshold", d.globalCompressionThreshold()),
                getBool(cfg, "master.useContextCompression", d.useContextCompression()),
                getBool(cfg, "master.enablePacketAggregation", d.enablePacketAggregation()),
                getInt(cfg, "master.aggregationMinBatchSize", d.aggregationMinBatchSize()),
                getLong(cfg, "master.aggregationMaxWaitTimeMs", d.aggregationMaxWaitTimeMs()),
                getInt(cfg, "master.aggregationMaxSize", d.aggregationMaxSize()),
                getBool(cfg, "master.enableCompactHeader", d.enableCompactHeader()),
                getStringSet(cfg, "master.compressionBlacklist", d.compressionBlacklist()),
                getBool(cfg, "master.metricsEnabled", d.metricsEnabled()),
                getInt(cfg, "master.maxChunksPerTick", d.maxChunksPerTick()),
                getInt(cfg, "master.serverChunkPushThreads", d.serverChunkPushThreads()),
                getBool(cfg, "master.dynamicThreadPoolEnabled", d.dynamicThreadPoolEnabled()),
                getInt(cfg, "master.minPushThreads", d.minPushThreads()),
                getInt(cfg, "master.maxPushThreads", d.maxPushThreads()),
                getString(cfg, "master.bindHost", d.bindHost()),
                getString(cfg, "master.authToken", d.authToken()),
                readReachableEndpoints(cfg, "master.controlReachableEndpoints", "master.controlReachableEndpoints",
                        MAX_CONTROL_ENDPOINTS),
                getPositiveLong(cfg, "master.migrationFaultTimeoutMs", d.migrationFaultTimeoutMs()),
                // CLIENT scope 迁移策略键不在 server.toml（物理客户端经 client.toml 加载）→ 默认值
                d.migrationMinTps(),
                d.migrationMaxLoadAverage(),
                d.migrationMaintenanceWindow(),
                d.migrationHeartbeatIntervalMs(),
                d.migrationIdleWindowMs(),
                d.migrationSilentTimeoutMs(),
                getPositiveLong(cfg, "master.migrationPrewarmTtlMs", d.migrationPrewarmTtlMs()),
                getPositiveLong(cfg, "master.resumeTicketTtlMs", d.resumeTicketTtlMs()),
                readDataPlane(cfg, d.dataPlane())
        );
    }

    private static void writeMasterCore(CommentedConfig cfg, HassiumConfig.MasterCoreConfig n) {
        set(cfg, "master.enabled", n.enabled(), "是否启用主控核心网络通道");
        set(cfg, "master.compressionLevel", n.compressionLevel(), "自有通道 ZSTD 等级");
        set(cfg, "master.magiclessZstd", n.magiclessZstd(), "是否使用无 magic 的 ZSTD");
        set(cfg, "master.globalPacketCompression", n.globalPacketCompression(), "是否启用全局 ZSTD");
        set(cfg, "master.globalCompressionLevel", n.globalCompressionLevel(), "全局压缩等级");
        set(cfg, "master.globalCompressionThreshold", n.globalCompressionThreshold(), "全局压缩阈值（字节）");
        set(cfg, "master.useContextCompression", n.useContextCompression(), "是否使用上下文压缩");
        set(cfg, "master.enablePacketAggregation", n.enablePacketAggregation(), "是否启用包聚合");
        set(cfg, "master.aggregationMinBatchSize", n.aggregationMinBatchSize(), "聚合最小批量");
        set(cfg, "master.aggregationMaxWaitTimeMs", (int) n.aggregationMaxWaitTimeMs(), "聚合最大等待（ms）");
        set(cfg, "master.aggregationMaxSize", n.aggregationMaxSize(), "聚合最大大小（字节）");
        set(cfg, "master.enableCompactHeader", n.enableCompactHeader(), "是否启用紧凑包头");
        set(cfg, "master.compressionBlacklist", new ArrayList<>(n.compressionBlacklist()), "压缩/聚合黑名单");
        set(cfg, "master.metricsEnabled", n.metricsEnabled(), "是否启用指标收集");
        set(cfg, "master.maxChunksPerTick", n.maxChunksPerTick(), "每玩家每 tick 提交到后台序列化的区块上限（发送速率 = 本值 × tick 节奏，满 tick ≈ 本值×20/s，仅服务端）");
        set(cfg, "master.serverChunkPushThreads", n.serverChunkPushThreads(), "服务端推送线程数（仅服务端）");
        set(cfg, "master.dynamicThreadPoolEnabled", n.dynamicThreadPoolEnabled(), "是否动态调整推送线程（仅服务端）");
        set(cfg, "master.minPushThreads", n.minPushThreads(), "动态池最小线程数（仅服务端）");
        set(cfg, "master.maxPushThreads", n.maxPushThreads(), "动态池最大线程数（仅服务端）");
        set(cfg, "master.bindHost", n.bindHost(),
                "网关监听 bind host（默认 127.0.0.1 回环；空串=0.0.0.0 全网卡，生产多网卡显式声明）");
        set(cfg, "master.authToken", n.authToken(),
                "网关握手鉴权 token（默认空=不鉴权；非空时客户端握手帧需携带同值，校验失败断开）");
        writeReachableEndpoints(cfg, "master.controlReachableEndpoints", n.controlReachableEndpoints(),
                "网关监听/outbound 端点（网关监听地址源；客户端 outbound 地址源 = 迁移引擎）");
        set(cfg, "master.migrationFaultTimeoutMs", n.migrationFaultTimeoutMs(),
                "L1 迁移故障超时（ms；silentTimeout 未配置时的回退值）");
        set(cfg, "master.migrationPrewarmTtlMs", n.migrationPrewarmTtlMs(),
                "预热会话 TTL（ms；无续流完成的预热物化会话到期清理）");
        set(cfg, "master.resumeTicketTtlMs", n.resumeTicketTtlMs(),
                "续流票据有效期（ms；T2 票据防重放时间窗口，默认 5min；旧格式票据不受限）");
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

    private static void writeClientDebug(CommentedConfig cfg, HassiumConfig.DebugConfig d) {
        set(cfg, "debug.metadataLogging", d.metadataLogging(), "元数据调试日志");
        set(cfg, "debug.dispatcherLogging", d.dispatcherLogging(), "主线程调度调试日志");
        set(cfg, "debug.asyncLogging", d.asyncLogging(), "异步任务调试日志");
        set(cfg, "debug.compressionLogging", d.compressionLogging(), "压缩调试日志");
        set(cfg, "debug.chunkApplyLogging", d.chunkApplyLogging(), "区块 apply 调试日志");
        set(cfg, "debug.networkLogging", d.networkLogging(), "网络调试日志");
        set(cfg, "debug.cacheLogging", d.cacheLogging(), "缓存调试日志");
        set(cfg, "debug.lightVerify", d.lightVerify(), "光照验算（官方引擎对照 BFS 结果）");
    }

    private static void writeServerDebug(CommentedConfig cfg, HassiumConfig.DebugConfig d) {
        set(cfg, "debug.dispatcherLogging", d.dispatcherLogging(), "主线程调度调试日志");
        set(cfg, "debug.asyncLogging", d.asyncLogging(), "异步任务调试日志");
        set(cfg, "debug.compressionLogging", d.compressionLogging(), "压缩调试日志");
        set(cfg, "debug.chunkApplyLogging", d.chunkApplyLogging(), "区块 apply 调试日志");
        set(cfg, "debug.networkLogging", d.networkLogging(), "网络调试日志");
        set(cfg, "debug.dataplaneLogging", d.dataplaneLogging(), "数据面（多通道 Data Plane）热路径日志 — 默认 false 以避免高频刷屏");
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
    /**
     * control 端点上限（与 {@link HassiumConfig.MasterCoreConfig} 构造钳位 4 一致）。
     * 读侧按同一上限归一化：手工编辑 toml 含 5-8 个 control 端点时仅丢端点，
     * 不再读侧放行→构造侧抛异常→整份配置静默回退默认。
     */
    private static final int MAX_CONTROL_ENDPOINTS = 4;
    /** UDP listener reachable 端点上限（与 {@link HassiumConfig.UdpListenerConfig} 钳位 8 一致）。 */
    private static final int MAX_LISTENER_ENDPOINTS = 8;

    /**
     * 读取并归一化 reachable 端点表；超过 {@code maxEntries} 时记 warn 并返回空表（仅丢该端点组）。
     */
    private static List<HassiumConfig.ReachableEndpoint> readReachableEndpoints(
            CommentedConfig cfg, String path, String fieldName, int maxEntries
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
            // review-fix: T6-57 按调用方传入上限归一化（control=4、listener=8），与构造侧钳位一致
            return DataPlaneEndpointConfig.normalizeReachableEndpoints(endpoints, maxEntries, fieldName);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Hassium: 忽略 {}: {}", fieldName, e.getMessage());
            return List.of();
        }
    }

    private static HassiumConfig.DataPlaneConfig readDataPlane(
            CommentedConfig cfg, HassiumConfig.DataPlaneConfig defaults
    ) {
        boolean enabled = getBool(cfg, "dataplane.enabled", defaults.enabled());
        List<HassiumConfig.UdpListenerConfig> listeners = readUdpListeners(cfg);
        if (listeners.isEmpty() && enabled) {
            LOGGER.warn("Hassium: UDP data-plane 没有有效 listener，回退默认 data-plane 配置");
            return defaults;
        }
        try {
            return new HassiumConfig.DataPlaneConfig(enabled, listeners);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Hassium: UDP data-plane 配置无效，回退默认: {}", e.getMessage());
            return defaults;
        }
    }

    private static List<HassiumConfig.UdpListenerConfig> readUdpListeners(CommentedConfig cfg) {
        Object value = cfg.get("dataplane.udpListeners");
        if (!(value instanceof List<?> entries)) {
            return List.of();
        }
        List<HassiumConfig.UdpListenerConfig> listeners = new ArrayList<>();
        for (Object entry : entries) {
            if (!(entry instanceof CommentedConfig listener)) {
                LOGGER.warn("Hassium: 忽略 dataplane.udpListeners 中的非表 listener");
                continue;
            }
            try {
                listeners.add(new HassiumConfig.UdpListenerConfig(
                        getString(listener, "bindHost", ""), getInt(listener, "bindPort", -1),
                        getInt(listener, "weight", -1),
                        readReachableEndpoints(listener, "reachableEndpoints",
                                "dataplane.udpListeners.reachableEndpoints", MAX_LISTENER_ENDPOINTS)));
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
        set(cfg, "dataplane.enabled", dataPlane.enabled(), "是否启用 UDP/KCP Data Plane");
        cfg.remove("dataplane.udpListeners");
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
        cfg.setComment("dataplane.udpListeners", "UDP listener；bind 仅限服务端本机，reachable 用于客户端连接");
        cfg.set("dataplane.udpListeners", listeners);
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
