package io.github.limuqy.mc.hassium.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
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
import java.util.Map;
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
                purgeUnknownKeys(cfg, scope);
                cfg.save();
            }
            LOGGER.info("Hassium: Toml 配置已保存 ({})", scope);
        } catch (Exception e) {
            LOGGER.error("Hassium: Toml 配置保存失败", e);
        }
    }

    /**
     * 删除本 scope Schema 之外的残留键（退役键、冒烟误注入的跨 scope 键、旧版字段）。
     * @return 是否删除了至少一个键
     */
    private static boolean purgeUnknownKeys(CommentedConfig cfg, ConfigScope scope) {
        Set<String> known = new LinkedHashSet<>();
        for (ConfigEntry<?> entry : entries(scope)) {
            known.add(entry.path());
        }
        return purgeUnknownKeysRecursive(cfg, "", known);
    }

    private static boolean purgeUnknownKeysRecursive(CommentedConfig parent, String prefix, Set<String> known) {
        if (parent == null) {
            return false;
        }
        boolean purged = false;
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, Object> e : parent.valueMap().entrySet()) {
            String key = e.getKey();
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Object value = e.getValue();
            if (value instanceof CommentedConfig child) {
                if (purgeUnknownKeysRecursive(child, path, known)) {
                    purged = true;
                }
                if (child.valueMap().isEmpty()) {
                    toRemove.add(key);
                    purged = true;
                }
            } else if (!known.contains(path)) {
                toRemove.add(key);
                purged = true;
            }
        }
        for (String key : toRemove) {
            parent.remove(key);
        }
        return purged;
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
        boolean purged = false;
        boolean missing = false;
        boolean orderDiffers = false;
        try (CommentedFileConfig cfg = open(path)) {
            stripUtf8BomIfPresent(path);
            cfg.load();
            List<String> filePaths = new ArrayList<>();
            flatten(cfg, "", filePaths);
            orderDiffers = !filePaths.equals(schemaPaths(scope));
            for (ConfigEntry<?> entry : entries(scope)) {
                Object value = readSchemaValue(cfg, entry);
                if (value != null) {
                    values = withSchemaValue(values, entry, value);
                } else if (cfg.get(entry.path()) == null) {
                    missing = true;
                }
            }
            purged = purgeUnknownKeys(cfg, scope);
            if (purged && !missing && !orderDiffers) {
                cfg.save();
                LOGGER.info("Hassium: 已清理 {} 中的非本 scope 残留键", path);
            }
        } catch (Exception e) {
            LOGGER.warn("Hassium: 读取 {} 失败，使用默认配置", path, e);
            return values;
        }
        if (missing || orderDiffers) {
            // 键缺失或键序与 schema 不符时**整表重写**：在已加载的表上逐个补写，新键会插进旧键之间
            // （旧文件顺序 + 逐键插入），文件看起来"散落一地"（2026-09-15 用户实测）。
            // 从空表按 schema 顺序写完再落盘，结果与首次生成的文件逐行一致；值取自本次读到的快照，
            // 用户改过的值原样保留，只有缺的键补成默认值。
            writeAll(path, scope, values);
            LOGGER.info("Hassium: {} 的配置键{}，已按 schema 顺序整表重写（原有值保持不变）",
                    path, missing ? "有新增" : "顺序与当前版本不一致");
        }
        return values;
    }

    /** 该 scope 的键序（与写文件时一致，决定文件里各键的排列）。 */
    private static List<String> schemaPaths(ConfigScope scope) {
        List<ConfigEntry<?>> entries = entries(scope);
        List<String> paths = new ArrayList<>(entries.size());
        for (ConfigEntry<?> entry : entries) {
            paths.add(entry.path());
        }
        return paths;
    }

    /** 按文件里的实际顺序展开嵌套表为 {@code a.b} 形式的键路径。 */
    private static void flatten(UnmodifiableConfig config, String prefix, List<String> out) {
        for (Map.Entry<String, Object> entry : config.valueMap().entrySet()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof UnmodifiableConfig nested) {
                flatten(nested, path, out);
            } else {
                out.add(path);
            }
        }
    }

    /** 从空表按 schema 顺序写出该 scope 的全部键（与首次生成路径同构）。 */
    private static void writeAll(Path path, ConfigScope scope, ConfigValues values) {
        try (CommentedFileConfig cfg = open(path)) {
            for (ConfigEntry<?> entry : entries(scope)) {
                writeSchemaValue(cfg, entry, values.get(entry.key()));
            }
            cfg.save();
        } catch (Exception e) {
            LOGGER.warn("Hassium: 重写 {} 失败", path, e);
        }
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
        cfg.setComment(entry.path(), entry.comment());
        cfg.set(entry.path(), value);
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
            purgeUnknownKeys(cfg, ConfigScope.CLIENT);
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
            purgeUnknownKeys(cfg, ConfigScope.SERVER);
            cfg.save();
        }
    }

    /** 服务端 chunk.* 键（chunk.lightStrip / chunk.seedGenEnabled；仅专用服 toml）。 */
    private static void writeServerChunk(CommentedConfig cfg, HassiumConfig.ChunkCoreConfig chunk) {
        set(cfg, "chunk.lightStrip", chunk.lightStrip(), "是否启用光照剥离", ConfigScope.SERVER);
        set(cfg, "chunk.seedGenEnabled", chunk.seedGenEnabled(),
                "是否启用 SeedGen（服务端开启下发世界种子；客户端门控开时影子 tracking 触发 vanilla worldgen 本地生成，再 compare-pull；需双端同版本，默认关）。警告：开启会向客户端下发世界种子，等同泄露服务端种子",
                ConfigScope.SERVER);
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
                getInt(cfg, "chunk.lightHaloRadius", d.lightHaloRadius()),
                getInt(cfg, "chunk.maxChunksPerFrame", d.maxChunksPerFrame()),
                getInt(cfg, "chunk.mainThreadChunkBudgetMs", d.mainThreadChunkBudgetMs()),
                getBool(cfg, "chunk.seedGenEnabled", d.seedGenEnabled()),
                getBool(cfg, "chunk.lightStrip", d.lightStrip())
        );
    }

    private static void writeChunkCore(CommentedConfig cfg, HassiumConfig.ChunkCoreConfig c) {
        set(cfg, "chunk.enabled", c.enabled(), "是否启用区块核心缓存", ConfigScope.CLIENT);
        set(cfg, "chunk.maxSizeMb", c.maxSizeMb(), "缓存最大容量", ConfigScope.CLIENT);
        set(cfg, "chunk.hotScoreThreshold", c.hotScoreThreshold(), "热点分数阈值", ConfigScope.CLIENT);
        set(cfg, "chunk.recencyWeight", c.recencyWeight(), "最近访问权重", ConfigScope.CLIENT);
        set(cfg, "chunk.frequencyWeight", c.frequencyWeight(), "访问频率权重", ConfigScope.CLIENT);
        set(cfg, "chunk.cleanupIntervalTicks", c.cleanupIntervalTicks(), "清理检查间隔", ConfigScope.CLIENT);
        set(cfg, "chunk.targetSizeMb", c.targetSizeMb(), "目标缓存大小", ConfigScope.CLIENT);
        set(cfg, "chunk.minCleanupBatchSize", c.minCleanupBatchSize(), "每轮淘汰 region 文件数", ConfigScope.CLIENT);
        set(cfg, "chunk.sectionDeltaEnabled", c.sectionDeltaEnabled(), "启用分段增量", ConfigScope.CLIENT);
        set(cfg, "chunk.viewDistanceExtensionEnabled", c.viewDistanceExtensionEnabled(), "启用超视渲染 OVD", ConfigScope.CLIENT);
        set(cfg, "chunk.maxRenderDistance", c.maxRenderDistance(), "超视渲染 effective clientRD 上限", ConfigScope.CLIENT);
        set(cfg, "chunk.lightHaloRadius", c.lightHaloRadius(),
                "光照光环半径（计算/拉取域 = 服务端视距 + 此值；只算不交付；0=关）", ConfigScope.CLIENT);
        set(cfg, "chunk.maxChunksPerFrame", c.maxChunksPerFrame(), "每帧最大区块数", ConfigScope.CLIENT);
        set(cfg, "chunk.mainThreadChunkBudgetMs", c.mainThreadChunkBudgetMs(), "主线程区块预算", ConfigScope.CLIENT);
        set(cfg, "chunk.seedGenEnabled", c.seedGenEnabled(), "启用 SeedGen", ConfigScope.CLIENT);
    }


    private static HassiumConfig.StorageConfig readStorage(CommentedConfig cfg) {
        var d = HassiumConfig.StorageConfig.DEFAULT;
        return new HassiumConfig.StorageConfig(
                getBool(cfg, "storage.enabled", d.enabled()),
                getInt(cfg, "storage.zstdLevel", d.zstdLevel())
        );
    }

    private static void writeStorage(CommentedConfig cfg, HassiumConfig.StorageConfig s) {
        set(cfg, "storage.enabled", s.enabled(), "是否启用存档压缩（启用前请备份）", ConfigScope.SERVER);
        set(cfg, "storage.zstdLevel", s.zstdLevel(), "存储 ZSTD 压缩等级", ConfigScope.SERVER);
    }

    private static HassiumConfig.MasterCoreConfig readMasterCore(CommentedConfig cfg) {
        var d = HassiumConfig.MasterCoreConfig.DEFAULT;
        return new HassiumConfig.MasterCoreConfig(
                getBool(cfg, "master.enabled", d.enabled()),
                getBool(cfg, "master.enabledOnLan", d.enabledOnLan()),
                getInt(cfg, "master.compressionLevel", d.compressionLevel()),
                getBool(cfg, "master.enablePacketAggregation", d.enablePacketAggregation()),
                getLong(cfg, "master.aggregationMaxWaitTimeMs", d.aggregationMaxWaitTimeMs()),
                getInt(cfg, "master.aggregationMaxSize", d.aggregationMaxSize()),
                getStringSet(cfg, "master.compressionBlacklist", d.compressionBlacklist()),
                getInt(cfg, "master.maxChunksPerTick", d.maxChunksPerTick()),
                getBool(cfg, "master.entityTieredUpdateEnabled", d.entityTieredUpdateEnabled()),
                getString(cfg, "master.entityTierIntervals", d.entityTierIntervals()),
                getString(cfg, "master.entityItemTierIntervals", d.entityItemTierIntervals()),
                getBool(cfg, "master.entityDensityThrottleEnabled", d.entityDensityThrottleEnabled()),
                getString(cfg, "master.entityDensityTierCounts", d.entityDensityTierCounts()),
                getString(cfg, "master.entityDensityTierFactors", d.entityDensityTierFactors()),
                getInt(cfg, "master.entityMaxThrottleFactor", d.entityMaxThrottleFactor()),
                getInt(cfg, "master.entityFrameBudgetPerPlayer", d.entityFrameBudgetPerPlayer()),
                getBool(cfg, "master.entitySmoothPushEnabled", d.entitySmoothPushEnabled())
        );
    }

    private static void writeMasterCore(CommentedConfig cfg, HassiumConfig.MasterCoreConfig n) {
        set(cfg, "master.enabled", n.enabled(), "是否启用服务端网络通道（压缩/聚合/区块推送/实体优化）", ConfigScope.SERVER);
        set(cfg, "master.enabledOnLan", n.enabledOnLan(),
                "局域网主机是否对远程玩家启用 Hassium 网络面（本机 memory 恒原版；storage 仍仅专用服）",
                ConfigScope.SERVER);
        set(cfg, "master.compressionLevel", n.compressionLevel(), "自有通道 ZSTD 等级", ConfigScope.SERVER);
        set(cfg, "master.enablePacketAggregation", n.enablePacketAggregation(),
                "是否启用包聚合；检测到 BandwidthOptimizer 时默认关（批处理/流式压缩与聚合帧冲突），显式 true 恒生效", ConfigScope.SERVER);
        set(cfg, "master.aggregationMaxWaitTimeMs", (int) n.aggregationMaxWaitTimeMs(), "冲刷兜底（ms；tick 尾冲刷为主，超时未冲则强制冲）", ConfigScope.SERVER);
        set(cfg, "master.aggregationMaxSize", n.aggregationMaxSize(), "聚合最大大小（字节）", ConfigScope.SERVER);
        set(cfg, "master.compressionBlacklist", new ArrayList<>(n.compressionBlacklist()), "第三方包压缩/聚合排除（Hassium 控制面已硬编码，与本列表无关）", ConfigScope.SERVER);
        set(cfg, "master.maxChunksPerTick", n.maxChunksPerTick(), "每玩家每 tick 区块下发上限（Pull FULL/DELTA 完成 + 原版整柱；UNCHANGED 另额 32；满 tick ≈ 本值×20/s，仅服务端）", ConfigScope.SERVER);
        set(cfg, "master.entityTieredUpdateEnabled", n.entityTieredUpdateEnabled(), "启用实体分层更新（按观察者距离四挡降频）", ConfigScope.SERVER);
        set(cfg, "master.entityTierIntervals", n.entityTierIntervals(), "实体各档更新间隔（刻），逗号分隔，按 近/中/远/边缘 顺序；挡位边界 = 有效跟踪范围的 25%/50%/75%/100%", ConfigScope.SERVER);
        set(cfg, "master.entityItemTierIntervals", n.entityItemTierIntervals(), "掉落物与经验球的四档更新间隔（刻），逗号分隔、顺序同上，默认 2,4,8,16", ConfigScope.SERVER);
        set(cfg, "master.entityDensityThrottleEnabled", n.entityDensityThrottleEnabled(), "启用实体密度节流（可见实体数超阈值后叠加降频）", ConfigScope.SERVER);
        set(cfg, "master.entityDensityTierCounts", n.entityDensityTierCounts(), "每档热点阈值（逗号分隔，按 近/中/远/边缘 顺序）：实体所在 chunk 活跃实体数 ≥ 阈值时按对应倍率放大", ConfigScope.SERVER);
        set(cfg, "master.entityDensityTierFactors", n.entityDensityTierFactors(), "每档热点倍率（逗号分隔，按 近/中/远/边缘 顺序，支持小数；1.0 = 不放大）", ConfigScope.SERVER);
        set(cfg, "master.entityMaxThrottleFactor", n.entityMaxThrottleFactor(), "最大节流倍率（更新间隔放大上限）", ConfigScope.SERVER);
        set(cfg, "master.entityFrameBudgetPerPlayer", n.entityFrameBudgetPerPlayer(), "每玩家每 tick 实体更新帧预算（0=不限）", ConfigScope.SERVER);
        set(cfg, "master.entitySmoothPushEnabled", n.entitySmoothPushEnabled(), "实体错峰推送（同间隔实体按 UUID 错开发送时刻，总量不变）", ConfigScope.SERVER);
    }

    private static HassiumConfig.CompatConfig readCompat(CommentedConfig cfg) {
        var d = HassiumConfig.CompatConfig.DEFAULT;
        return new HassiumConfig.CompatConfig(
                getBool(cfg, "compat.requireClientMod", d.requireClientMod()),
                getBool(cfg, "compat.autoDowngradeOnError", d.autoDowngradeOnError())
        );
    }

    private static void writeCompat(CommentedConfig cfg, HassiumConfig.CompatConfig c) {
        set(cfg, "compat.requireClientMod", c.requireClientMod(), "是否强制要求客户端安装 Hassium", ConfigScope.SERVER);
        set(cfg, "compat.autoDowngradeOnError", c.autoDowngradeOnError(), "出错时是否自动降级", ConfigScope.SERVER);
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
                getBool(cfg, "debug.lightLogging", d.lightLogging()),
                getBool(cfg, "debug.networkMetricsEnabled", d.networkMetricsEnabled()),
                getBool(cfg, "debug.networkMetricsAutoReset", d.networkMetricsAutoReset()),
                getBool(cfg, "debug.exportAggregatedPackets", d.exportAggregatedPackets())
        );
    }

    private static void writeClientDebug(CommentedConfig cfg, HassiumConfig.DebugConfig d) {
        set(cfg, "debug.metadataLogging", d.metadataLogging(), "元数据调试日志", ConfigScope.CLIENT);
        set(cfg, "debug.dispatcherLogging", d.dispatcherLogging(), "主线程调度调试日志", ConfigScope.CLIENT);
        set(cfg, "debug.asyncLogging", d.asyncLogging(), "异步任务调试日志", ConfigScope.CLIENT);
        set(cfg, "debug.compressionLogging", d.compressionLogging(), "压缩调试日志", ConfigScope.CLIENT);
        set(cfg, "debug.chunkApplyLogging", d.chunkApplyLogging(), "区块 apply 调试日志", ConfigScope.CLIENT);
        set(cfg, "debug.networkLogging", d.networkLogging(), "网络调试日志", ConfigScope.CLIENT);
        set(cfg, "debug.cacheLogging", d.cacheLogging(), "缓存调试日志", ConfigScope.CLIENT);
        set(cfg, "debug.lightVerify", d.lightVerify(), "光照验算与光包落地探针（历史别名，等价 debug.lightLogging）", ConfigScope.CLIENT);
        set(cfg, "debug.lightLogging", d.lightLogging(), "光照调试总开关：算光链路日志 + 引擎/光包探针", ConfigScope.CLIENT);
        set(cfg, "debug.networkMetricsEnabled", d.networkMetricsEnabled(), "客户端网络指标", ConfigScope.CLIENT);
        set(cfg, "debug.networkMetricsAutoReset", d.networkMetricsAutoReset(), "退出服务器时自动复位网络指标", ConfigScope.CLIENT);
    }

    private static void writeServerDebug(CommentedConfig cfg, HassiumConfig.DebugConfig d) {
        set(cfg, "debug.dispatcherLogging", d.dispatcherLogging(), "主线程调度调试日志", ConfigScope.SERVER);
        set(cfg, "debug.asyncLogging", d.asyncLogging(), "异步任务调试日志", ConfigScope.SERVER);
        set(cfg, "debug.compressionLogging", d.compressionLogging(), "压缩调试日志", ConfigScope.SERVER);
        set(cfg, "debug.chunkApplyLogging", d.chunkApplyLogging(), "区块 apply 调试日志", ConfigScope.SERVER);
        set(cfg, "debug.networkLogging", d.networkLogging(), "网络调试日志", ConfigScope.SERVER);
        set(cfg, "debug.exportAggregatedPackets", d.exportAggregatedPackets(), "导出聚合包调试流（S2C 包 + 聚合帧 → logs/hassium-aggregated-packets/*.jsonl；单人也生效）", ConfigScope.SERVER);
    }

    // --- value helpers ---

    /**
     * 写键值与备注。若 path 在 {@link ConfigSchema} 中有对应 {@code scope} 登记，
     * 用该 scope 的双语备注；否则用 {@code fallbackComment}。
     * 双 scope 同名键（seedGenEnabled / debug.*）必须按 scope 取，避免 server 拿到 client 注释。
     */
    private static void set(CommentedConfig cfg, String path, Object value, String fallbackComment) {
        set(cfg, path, value, fallbackComment, null);
    }

    private static void set(CommentedConfig cfg, String path, Object value, String fallbackComment, ConfigScope scope) {
        cfg.setComment(path, schemaCommentOr(path, scope, fallbackComment));
        cfg.set(path, value);
    }

    private static String schemaCommentOr(String path, ConfigScope scope, String fallbackComment) {
        ConfigEntry<?> fallbackEntry = null;
        for (ConfigEntry<?> entry : ConfigSchema.entries()) {
            if (!entry.path().equals(path) || entry.comment() == null || entry.comment().isBlank()) {
                continue;
            }
            if (scope != null && entry.scope() == scope) {
                return entry.comment();
            }
            if (fallbackEntry == null) {
                fallbackEntry = entry;
            }
        }
        if (fallbackEntry != null && scope == null) {
            return fallbackEntry.comment();
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

    private static String getString(CommentedConfig cfg, String path, String def) {
        Object v = cfg.get(path);
        if (v instanceof String s) {
            return s;
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
