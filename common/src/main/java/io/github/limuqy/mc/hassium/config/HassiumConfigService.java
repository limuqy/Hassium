package io.github.limuqy.mc.hassium.config;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Hassium 配置服务。
 * <p>
 * Forge/NeoForge：真相源为 {@link HassiumConfigSpec}；Fabric：{@link HassiumTomlConfigIO}。
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
    }

    public HassiumConfigService(HassiumConfig config) {
        this.config = config;
        this.networkCompressionEnabled.set(config.network().enabled());
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

    /** Fabric：从 toml 加载并启用 toml 后端。 */
    public void loadFromToml() {
        lock.writeLock().lock();
        try {
            this.tomlBackend.set(true);
            HassiumConfig loaded = HassiumTomlConfigIO.load();
            this.config = loaded;
            this.networkCompressionEnabled.set(loaded.network().enabled());
            this.storageEnabled.set(loaded.storage().enabled());
            this.configLoaded.set(true);
            NetworkStats.setEnabled(loaded.network().metricsEnabled());
            LOGGER.info("Hassium: Configuration loaded from Toml");
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to load Toml configuration", e);
            this.config = HassiumConfig.DEFAULT;
            this.networkCompressionEnabled.set(true);
            this.storageEnabled.set(true);
            this.configLoaded.set(true);
            this.tomlBackend.set(true);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Fabric：将当前快照写入 toml。 */
    public void saveToToml() {
        lock.readLock().lock();
        HassiumConfig snapshot;
        try {
            snapshot = config;
        } finally {
            lock.readLock().unlock();
        }
        HassiumTomlConfigIO.save(snapshot);
    }

    public boolean isTomlBackend() {
        return tomlBackend.get();
    }

    /**
     * 从 ConfigSpec 同步快照与门闩（ModConfig load/reload 与初始化时调用）。
     * Fabric toml 后端下为空操作。
     */
    public void syncFromSpec() {
        if (tomlBackend.get()) {
            return;
        }
        lock.writeLock().lock();
        try {
            HassiumConfig loaded = HassiumConfigSpec.toHassiumConfig();
            this.config = loaded;
            this.networkCompressionEnabled.set(loaded.network().enabled());
            this.storageEnabled.set(loaded.storage().enabled());
            this.configLoaded.set(true);
            NetworkStats.setEnabled(loaded.network().metricsEnabled());
            LOGGER.info("Hassium: Configuration synced from ConfigSpec");
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to sync configuration from ConfigSpec", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * @deprecated 使用 {@link #syncFromSpec()} / {@link #loadFromToml()}。
     */
    @Deprecated
    public void loadConfig() {
        if (tomlBackend.get()) {
            loadFromToml();
        } else {
            syncFromSpec();
        }
    }

    /**
     * 持久化当前快照：Fabric 写 toml；Forge/NeoForge 写回 Spec。
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
            HassiumConfigSpec.applyFrom(snapshot);
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to persist configuration to ConfigSpec", e);
        }
    }

    /**
     * @deprecated 不再使用自定义配置目录。
     */
    @Deprecated
    public void setConfigDir(java.nio.file.Path configDir) {
        // no-op
    }

    /**
     * @deprecated 客户端/服务端由 CLIENT/COMMON Spec 分文件隔离。
     */
    @Deprecated
    public void setPhysicalClient(boolean isPhysicalClient) {
        // no-op
    }

    public void reloadConfig() {
        if (tomlBackend.get()) {
            loadFromToml();
        } else {
            syncFromSpec();
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
            this.networkCompressionEnabled.set(newConfig.network().enabled());
            this.storageEnabled.set(newConfig.storage().enabled());
            NetworkStats.setEnabled(newConfig.network().metricsEnabled());
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

    public boolean isStorageEnabled() {
        return storageEnabled.get();
    }

    public void setStorageEnabled(boolean enabled) {
        storageEnabled.set(enabled);
    }

    public boolean isClientCacheEnabled() {
        return config.clientCache().enabled();
    }

    /**
     * 网络压缩算法（固定 ZSTD，无其它实现可选）。
     */
    public String getCompressionAlgorithm() {
        return Constants.NETWORK_COMPRESSION_ALGORITHM;
    }

    public static String getNetworkCompressionAlgorithm() {
        return getInstance().getCompressionAlgorithm();
    }

    public int getCompressionLevel() {
        return config.network().compressionLevel();
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

    public int getMaxCacheSizeMb() {
        return config.clientCache().maxSizeMb();
    }

    public int getCacheMaxAgeDays() {
        return config.clientCache().maxAgeDays();
    }

    public double getHotScoreThreshold() {
        return config.clientCache().hotScoreThreshold();
    }

    public double getRecencyWeight() {
        return config.clientCache().recencyWeight();
    }

    public double getFrequencyWeight() {
        return config.clientCache().frequencyWeight();
    }

    public int getCleanupIntervalTicks() {
        return config.clientCache().cleanupIntervalTicks();
    }

    public int getTargetCacheSizeMb() {
        return config.clientCache().resolvedTargetCacheSizeMb();
    }

    public long getTargetCacheSizeBytes() {
        return config.clientCache().targetCacheSizeBytes();
    }

    public int getMinCleanupBatchSize() {
        return config.clientCache().minCleanupBatchSize();
    }

    public boolean isRequireClientMod() {
        return config.compat().requireClientMod();
    }

    public ConfigSnapshot createSnapshot() {
        lock.readLock().lock();
        try {
            return new ConfigSnapshot(
                    config,
                    networkCompressionEnabled.get(),
                    storageEnabled.get()
            );
        } finally {
            lock.readLock().unlock();
        }
    }

    public record ConfigSnapshot(
            HassiumConfig config,
            boolean networkCompressionEnabled,
            boolean storageEnabled
    ) {
    }

    public boolean isConfigLoaded() {
        return configLoaded.get();
    }

    public boolean isGlobalPacketCompressionEnabled() {
        return config.network().globalPacketCompression();
    }

    public int getGlobalCompressionLevel() {
        return config.network().globalCompressionLevel();
    }

    public int getGlobalCompressionThreshold() {
        return config.network().globalCompressionThreshold();
    }

    public Set<String> getCompressionBlacklist() {
        return config.network().compressionBlacklist();
    }

    public boolean isPacketCompressible(String packetType) {
        return !config.network().compressionBlacklist().contains(packetType);
    }

    public boolean isUseContextCompression() {
        return config.network().useContextCompression();
    }

    public boolean isMagiclessZstd() {
        return config.network().magiclessZstd();
    }

    public boolean isPacketAggregationEnabled() {
        return config.network().enablePacketAggregation();
    }

    public int getAggregationMinBatchSize() {
        return config.network().aggregationMinBatchSize();
    }

    public long getAggregationMaxWaitTimeMs() {
        return config.network().aggregationMaxWaitTimeMs();
    }

    public int getAggregationMaxSize() {
        return config.network().aggregationMaxSize();
    }

    public boolean isCompactHeaderEnabled() {
        return config.network().enableCompactHeader();
    }

    public int getServerChunkPushThreads() {
        return config.network().serverChunkPushThreads();
    }

    public int getClientChunkLoadThreads() {
        return config.network().clientChunkLoadThreads();
    }

    public boolean isLightStripEnabled() {
        return config.network().lightStripEnabled();
    }

    public int getBackgroundThreads() {
        return config.network().backgroundThreads();
    }

    public int getMaxChunksPerFrame() {
        return Math.max(1, config.network().maxChunksPerFrame());
    }

    public int getMaxCallbacksPerFrame() {
        return Math.max(1, config.network().maxCallbacksPerFrame());
    }

    public int getMainThreadChunkBudgetMs() {
        int value = config.network().mainThreadChunkBudgetMs();
        if (value <= 0) {
            return 3;
        }
        return Math.min(50, value);
    }

    public boolean isMetricsEnabled() {
        return config.network().metricsEnabled();
    }

    public int getMaxLightRecomputePerFrame() {
        return Math.max(1, config.network().maxLightRecomputePerFrame());
    }

    public boolean isDynamicThreadPoolEnabled() {
        return config.network().dynamicThreadPoolEnabled();
    }

    public int getMinPushThreads() {
        return Math.max(1, config.network().minPushThreads());
    }

    public int getMaxPushThreads() {
        int value = config.network().maxPushThreads();
        return Math.max(getMinPushThreads(), value);
    }

    public boolean isBloomFilterEnabled() {
        return config.clientCache().bloomFilterEnabled();
    }

    public int getBloomFilterExpectedInsertions() {
        return Math.max(1000, config.clientCache().bloomFilterExpectedInsertions());
    }

    public double getBloomFilterFpp() {
        double value = config.clientCache().bloomFilterFpp();
        return Math.max(0.001, Math.min(0.1, value));
    }

    /** 是否启用超视渲染；仍依赖 clientCache.enabled */
    public boolean isViewDistanceExtensionEnabled() {
        return config.clientCache().viewDistanceExtensionEnabled();
    }

    /** 渲染距离上限（Fog/内存约束） */
    public int getMaxRenderDistance() {
        return Math.max(2, config.clientCache().maxRenderDistance());
    }

    /** 离开超视渲染环带后延迟卸载秒数（0=同步卸载） */
    public int getOvdUnloadDelaySecs() {
        return Math.max(0, config.clientCache().ovdUnloadDelaySecs());
    }

    /**
     * MISMATCH 是否走分段增量（仍依赖 {@link #isClientCacheEnabled()}）。
     * <p>
     * 默认 true：开启时仅请求变更分段并合并本地缓存；关闭时与全量请求路径一致。
     */
    public boolean isSectionDeltaEnabled() {
        return isClientCacheEnabled() && config.clientCache().sectionDeltaEnabled();
    }
}
