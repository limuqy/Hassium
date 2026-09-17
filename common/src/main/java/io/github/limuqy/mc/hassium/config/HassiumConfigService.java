package io.github.limuqy.mc.hassium.config;

import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import io.github.limuqy.mc.hassium.platform.Services;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Hassium 配置服务。
 * <p>
 * 配置后端由 {@link io.github.limuqy.mc.hassium.platform.Services#CONFIG} 提供；Fabric 使用 {@link FabricTomlConfigIO}。
 * 本类维护运行时快照与热路径门闩。
 */
public class HassiumConfigService {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/Config");

    private static volatile HassiumConfigService instance;

    private volatile HassiumConfig config;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final AtomicBoolean networkCompressionEnabled = new AtomicBoolean(true);
    private final AtomicBoolean storageEnabled = new AtomicBoolean(true);
    private final AtomicBoolean configLoaded = new AtomicBoolean(false);
    /** Fabric toml 后端：为 true 时禁止 {@link #syncFromSpec()}（避免加载 Spec）。 */
    private final AtomicBoolean tomlBackend = new AtomicBoolean(false);

    public HassiumConfigService() {
        this.config = HassiumConfig.DEFAULT;
        // review-fix: T6-55 默认构造同步 DEFAULT 门闩——storageEnabled 不再初值 true，
        // 消除 loadFromToml 之前窗口内 MixinRegionFile 写盘门闩按 true 的语义矛盾
        this.storageEnabled.set(HassiumConfig.DEFAULT.storage().enabled());
    }

    public HassiumConfigService(HassiumConfig config) {
        this.config = config;
        this.networkCompressionEnabled.set(resolveNetworkEnabled(config));
        this.storageEnabled.set(config.storage().enabled());
    }

    public static HassiumConfigService getInstance() {
        if (instance == null) {
            synchronized (HassiumConfigService.class) {
                if (instance == null) {
                    instance = new HassiumConfigService();
                }
            }
        }
        return instance;
    }

    /** Fabric：经 schema 后端从 toml 加载并启用 toml 后端。物理客户端双文件合并。 */
    public void loadFromToml() {
        lock.writeLock().lock();
        try {
            this.tomlBackend.set(true);
            applyLoaded(loadSnapshotFromBackend());
            LOGGER.info("Hassium: Configuration loaded from Toml");
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to load Toml configuration", e);
            applyLoaded(HassiumConfig.DEFAULT);
            this.tomlBackend.set(true);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 经后端加载当前物理端快照。
     * <p>
     * 物理客户端：CLIENT + SERVER 双文件合并（server.toml 供集成服务器/局域网读取；
     * 单人时服务端侧键有独立可配面）；专用服：仅 SERVER。
     */
    private static HassiumConfig loadSnapshotFromBackend() {
        boolean physicalClient = io.github.limuqy.mc.hassium.platform.Services.PLATFORM.isPhysicalClient();
        if (physicalClient) {
            ConfigValues clientValues = Services.CONFIG.load(io.github.limuqy.mc.hassium.config.ConfigScope.CLIENT);
            ConfigValues serverValues = Services.CONFIG.load(io.github.limuqy.mc.hassium.config.ConfigScope.SERVER);
            return ConfigSnapshotAdapter.fromMerged(clientValues, serverValues);
        }
        return ConfigSnapshotAdapter.fromValues(
                Services.CONFIG.load(io.github.limuqy.mc.hassium.config.ConfigScope.SERVER), false);
    }

    /** Fabric：经 schema 后端将当前快照写入 toml。物理客户端写双文件。 */
    public void saveToToml() {
        lock.readLock().lock();
        HassiumConfig snapshot;
        try {
            snapshot = config;
        } finally {
            lock.readLock().unlock();
        }
        ConfigValues values = ConfigSnapshotAdapter.toValues(snapshot);
        if (io.github.limuqy.mc.hassium.platform.Services.PLATFORM.isPhysicalClient()) {
            Services.CONFIG.save(io.github.limuqy.mc.hassium.config.ConfigScope.CLIENT, values);
            Services.CONFIG.save(io.github.limuqy.mc.hassium.config.ConfigScope.SERVER, values);
        } else {
            Services.CONFIG.save(io.github.limuqy.mc.hassium.config.ConfigScope.SERVER, values);
        }
    }

    public boolean isTomlBackend() {
        return tomlBackend.get();
    }

    /**
     * 从 ConfigSpec 同步快照与门闩（ModConfig load/reload 与初始化时调用）。
     * Fabric toml 后端下为空操作。物理客户端合并 CLIENT + SERVER 两份 spec。
     */
    public void syncFromSpec() {
        if (tomlBackend.get()) {
            return;
        }
        lock.writeLock().lock();
        try {
            applyLoaded(loadSnapshotFromBackend());
        } catch (Exception e) {
            if (!configLoaded.get()) {
                applyLoaded(HassiumConfig.DEFAULT);
            }
            LOGGER.debug("Hassium: Config backend sync skipped: {}", e.toString());
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void applyLoaded(HassiumConfig loaded) {
        this.config = loaded;
        this.networkCompressionEnabled.set(resolveNetworkEnabled(loaded));
        this.storageEnabled.set(loaded.storage().enabled());
        this.configLoaded.set(true);
        NetworkStats.setEnabled(resolveMetricsEnabled(loaded));
    }

    /**
     * 持久化当前快照：Fabric 写 toml；Forge/NeoForge 写回 Spec。
     * 物理客户端写 CLIENT + SERVER 两份（server 侧供集成服务器/局域网）。
     */
    public void saveConfig() {
        if (tomlBackend.get()) {
            saveToToml();
            return;
        }
        lock.readLock().lock();
        HassiumConfig snapshot;
        try {
            snapshot = config;
        } finally {
            lock.readLock().unlock();
        }
        try {
            ConfigValues values = ConfigSnapshotAdapter.toValues(snapshot);
            if (io.github.limuqy.mc.hassium.platform.Services.PLATFORM.isPhysicalClient()) {
                Services.CONFIG.save(io.github.limuqy.mc.hassium.config.ConfigScope.CLIENT, values);
                Services.CONFIG.save(io.github.limuqy.mc.hassium.config.ConfigScope.SERVER, values);
            } else {
                Services.CONFIG.save(io.github.limuqy.mc.hassium.config.ConfigScope.SERVER, values);
            }
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to persist configuration", e);
        }
    }

    public HassiumConfig getConfig() {
        lock.readLock().lock();
        try {
            return config;
        } finally {
            lock.readLock().unlock();
        }
    }

    public void updateConfig(HassiumConfig newConfig) {
        lock.writeLock().lock();
        try {
            this.config = newConfig;
            this.networkCompressionEnabled.set(resolveNetworkEnabled(newConfig));
            this.storageEnabled.set(newConfig.storage().enabled());
            NetworkStats.setEnabled(resolveMetricsEnabled(newConfig));
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean isNetworkCompressionEnabled() {
        return networkCompressionEnabled.get();
    }

    public void setNetworkCompressionEnabled(boolean enabled) {
        networkCompressionEnabled.set(enabled);
    }

    /**
     * 主世界 type-126 存档压缩：仅专用服务器生效。
     * <p>
     * 单人/局域网（物理客户端上的集成服务器）保持原版格式；影子端世界走
     * {@code shadow} 标志的独立路径，不受本门闩约束。
     */
    public boolean isStorageEnabled() {
        return storageEnabled.get()
                && io.github.limuqy.mc.hassium.server.RuntimeServerContext.isDedicatedServerContext();
    }

    public boolean isClientCacheEnabled() {
        return config.chunk().enabled();
    }

    /** 影子端配置开关（默认 true）。false 时客户端缓存/超视渲染/SeedGen/影子光照全 gate 关闭。 */
    public boolean isHassiumEngineEnabled() {
        return config.chunk().enabled();
    }

    /** 分段增量：MISMATCH 时补变更方块，过多则整段/整块（影子端消费）。 */
    public boolean isSectionDeltaEnabled() {
        return config.chunk().sectionDeltaEnabled();
    }

    /** 超视渲染 OVD 总开关（影子双窗）。 */
    public boolean isViewDistanceExtensionEnabled() {
        return config.chunk().viewDistanceExtensionEnabled();
    }

    /** 超视渲染 effective clientRD 上限。 */
    public int getMaxRenderDistance() {
        return config.chunk().maxRenderDistance();
    }

    /** 客户端功能 gate；仅在 Hassium 能力握手完成后开放 legacy fallback。 */
    public boolean isClientFeatureGateOpen() {
        if (!isHassiumEngineEnabled()) {
            return false;
        }
        io.github.limuqy.mc.hassium.client.ClientChunkPipeline pipeline =
                io.github.limuqy.mc.hassium.client.ClientChunkPipeline.getInstance();
        return pipeline.isHassiumHandshakeDone() && !pipeline.isShadowServerFailed();
    }
    public int getCompressionLevel() {
        return config.master().compressionLevel();
    }

    public static int getNetworkCompressionLevel() {
        return getInstance().getCompressionLevel();
    }


    public int getStorageCompressionLevel() {
        return config.storage().zstdLevel();
    }

    public boolean isAutoDowngradeEnabled() {
        return config.compat().autoDowngradeOnError();
    }

    public int getCleanupIntervalTicks() {
        return config.chunk().cleanupIntervalTicks();
    }

    public boolean isMasterEnabled() {
        return config.master().enabled();
    }

    /** 局域网主机对远程玩家启用网络面（仅集成服 + isPublished 时由 ServerNetworkGate 消费）。 */
    public boolean isMasterEnabledOnLan() {
        return config.master().enabledOnLan();
    }

    public boolean isRequireClientMod() {
        return config.compat().requireClientMod();
    }

    public boolean isConfigLoaded() {
        return configLoaded.get();
    }

    public Set<String> getCompressionBlacklist() {
        return config.master().compressionBlacklist();
    }

    public boolean isPacketCompressible(String packetType) {
        return !config.master().compressionBlacklist().contains(packetType);
    }

    public boolean isPacketAggregationEnabled() {
        return config.master().enablePacketAggregation();
    }

    public long getAggregationMaxWaitTimeMs() {
        return config.master().aggregationMaxWaitTimeMs();
    }

    public int getAggregationMaxSize() {
        return config.master().aggregationMaxSize();
    }

    public boolean isServerLightStrip() {
        return config.chunk().lightStrip();
    }

    public int getMaxChunksPerFrame() {
        return Math.max(1, config.chunk().maxChunksPerFrame());
    }

    /** 光照验算（官方引擎对照 BFS 结果；默认关）。 */
    public boolean isLightVerifyEnabled() {
        return config.debug().lightVerify();
    }

    public int getMainThreadChunkBudgetMs() {
        int value = config.chunk().mainThreadChunkBudgetMs();
        if (value <= 0) {
            return 15;
        }
        return Math.min(50, value);
    }

    /**
     * 是否启用指标收集：冒烟测试属性强开，否则读 {@code debug.networkMetricsEnabled}。
     */
    public boolean isMetricsEnabled() {
        return resolveMetricsEnabled(config);
    }

    /**
     * 登出服务器时是否自动重置指标计数（仅客户端字段）。
     */
    public boolean isMetricsAutoResetEnabled() {
        return config.debug().networkMetricsAutoReset();
    }

    /** 是否启用 SeedGen（服务端开启下发世界种子；客户端门控开时影子 tracking 触发 vanilla worldgen 本地生成，再 compare-pull；默认关）。 */
    public boolean isSeedGenEnabled() {
        return config.chunk().seedGenEnabled();
    }

    /** 客户端是否启用 SeedGen（上报握手能力 + 本地生成开关；默认关）。 */
    public boolean isClientSeedGenEnabled() {
        return config.chunk().seedGenEnabled();
    }

    /** 是否启用 JoinBoost（进服后短时提高主线程预算加速加载） */
    public boolean isJoinBoostEnabled() {
        return config.chunk().enabled();
    }

    /** 实体分层更新总开关（按观察者距离四挡降频；本键族的总闸）。 */
    public boolean isEntityTieredUpdateEnabled() {
        return config.master().entityTieredUpdateEnabled();
    }

    /** 实体各档更新间隔（刻），逗号分隔，按 近/中/远/边缘 顺序。 */
    public String getEntityTierIntervals() {
        return config.master().entityTierIntervals();
    }

    /** 物品流（掉落物/经验球）各档更新间隔（刻），逗号分隔，顺序同上。 */
    public String getEntityItemTierIntervals() {
        return config.master().entityItemTierIntervals();
    }

    /** 实体密度节流总开关（实体所在 chunk 活跃实体数达该档阈值后按该档倍率放大间隔）。 */
    public boolean isEntityDensityThrottleEnabled() {
        return config.master().entityDensityThrottleEnabled();
    }

    /** 每档热点阈值（逗号分隔，近/中/远/边缘）。 */
    public String getEntityDensityTierCounts() {
        return config.master().entityDensityTierCounts();
    }

    /** 每档热点倍率（逗号分隔，近/中/远/边缘；支持小数）。 */
    public String getEntityDensityTierFactors() {
        return config.master().entityDensityTierFactors();
    }

    /** 最大节流倍率（密度倍率 × 压力倍率的总上限）。 */
    public int getEntityMaxThrottleFactor() {
        return config.master().entityMaxThrottleFactor();
    }

    /** 每玩家每 tick 实体更新帧预算（0 = 不限）。 */
    public int getEntityFrameBudgetPerPlayer() {
        return config.master().entityFrameBudgetPerPlayer();
    }

    /** 实体错峰推送：同 interval 实体按 UUID 错开发送时刻，总量不变。 */
    public boolean isEntitySmoothPushEnabled() {
        return config.master().entitySmoothPushEnabled();
    }

    // --- internal helpers ---
    private static boolean resolveNetworkEnabled(HassiumConfig cfg) {
        try {
            return Services.PLATFORM.isPhysicalClient()
                    ? cfg.chunk().enabled()
                    : cfg.master().enabled();
        } catch (Throwable ignored) {
            return cfg.master().enabled();
        }
    }


    /**
     * 根据物理端解析 metricsEnabled：
     * 冒烟测试 {@code hassium.smokeTest=true} 或 {@code hassium.serverSmokeTest=true} 时强开；
     * 否则读 {@code debug.networkMetricsEnabled}。
     */
    private static boolean resolveMetricsEnabled(HassiumConfig cfg) {
        if (Boolean.parseBoolean(System.getProperty("hassium.smokeTest", "false"))
                || Boolean.parseBoolean(System.getProperty("hassium.serverSmokeTest", "false"))) {
            return true;
        }
        return cfg.debug().networkMetricsEnabled();
    }
}
