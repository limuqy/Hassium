package io.github.limuqy.mc.hassium.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import io.github.limuqy.mc.hassium.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Hassium 配置服务
 * <p>
 * 管理配置的加载、保存和热更新。
 */
public class HassiumConfigService {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/Config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE_NAME = Constants.MOD_ID + ".json";

    private static volatile HassiumConfigService instance;

    private volatile HassiumConfig config;
    private volatile Path configDir;
    private volatile boolean isPhysicalClient = false;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final AtomicBoolean networkCompressionEnabled = new AtomicBoolean(true);
    private final AtomicBoolean storageEnabled = new AtomicBoolean(false);
    private final AtomicBoolean configLoaded = new AtomicBoolean(false);

    public HassiumConfigService() {
        this.config = HassiumConfig.DEFAULT;
    }

    public HassiumConfigService(HassiumConfig config) {
        this.config = config;
        this.networkCompressionEnabled.set(config.network().enabled());
        this.storageEnabled.set(config.storage().enabled());
    }

    /**
     * 获取单例实例
     */
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

    /**
     * 设置配置目录
     */
    public void setConfigDir(Path configDir) {
        this.configDir = configDir;
    }

    /**
     * 设置当前运行环境是否为物理客户端
     * <p>
     * 用于保存配置时过滤掉当前环境不相关的配置项。
     */
    public void setPhysicalClient(boolean isPhysicalClient) {
        this.isPhysicalClient = isPhysicalClient;
    }

    /**
     * 获取当前配置
     */
    public HassiumConfig getConfig() {
        lock.readLock().lock();
        try {
            return config;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 更新配置
     */
    public void updateConfig(HassiumConfig newConfig) {
        lock.writeLock().lock();
        try {
            this.config = newConfig;
            this.networkCompressionEnabled.set(newConfig.network().enabled());
            this.storageEnabled.set(newConfig.storage().enabled());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 检查 Hassium 自定义通道压缩是否启用
     */
    public boolean isNetworkCompressionEnabled() {
        return networkCompressionEnabled.get();
    }

    /**
     * 启用/禁用 Hassium 自定义通道压缩
     */
    public void setNetworkCompressionEnabled(boolean enabled) {
        networkCompressionEnabled.set(enabled);
    }

    /**
     * 检查存储是否启用
     */
    public boolean isStorageEnabled() {
        return storageEnabled.get();
    }

    /**
     * 启用/禁用存储
     */
    public void setStorageEnabled(boolean enabled) {
        storageEnabled.set(enabled);
    }

    /**
     * 检查客户端缓存是否启用
     */
    public boolean isClientCacheEnabled() {
        return config.clientCache().enabled();
    }

    /**
     * 获取压缩算法
     */
    public String getCompressionAlgorithm() {
        return config.network().compressionAlgorithm();
    }

    /**
     * 获取网络压缩算法
     */
    public static String getNetworkCompressionAlgorithm() {
        return getInstance().getCompressionAlgorithm();
    }

    /**
     * 获取压缩等级
     */
    public int getCompressionLevel() {
        return config.network().compressionLevel();
    }

    /**
     * 获取网络压缩等级
     */
    public static int getNetworkCompressionLevel() {
        return getInstance().getCompressionLevel();
    }

    /**
     * 获取存储压缩算法
     */
    public String getStorageCompressionAlgorithm() {
        return config.storage().storageCompressionAlgorithm();
    }

    /**
     * 获取存储压缩等级
     */
    public int getStorageCompressionLevel() {
        return config.storage().zstdLevel();
    }

    /**
     * 检查是否需要验证校验和
     */
    public boolean isVerifyChecksumEnabled() {
        return config.storage().verifyChecksum();
    }

    /**
     * 检查是否自动降级
     */
    public boolean isAutoDowngradeEnabled() {
        return config.compat().autoDowngradeOnError();
    }

    /**
     * 获取最大缓存大小（MB）
     */
    public int getMaxCacheSizeMb() {
        return config.clientCache().maxSizeMb();
    }

    /**
     * 获取缓存最大年龄（天）
     */
    public int getCacheMaxAgeDays() {
        return config.clientCache().maxAgeDays();
    }

    /**
     * 获取热度阈值
     */
    public double getHotScoreThreshold() {
        return config.clientCache().hotScoreThreshold();
    }

    /**
     * 获取最近访问权重
     */
    public double getRecencyWeight() {
        return config.clientCache().recencyWeight();
    }

    /**
     * 获取访问频率权重
     */
    public double getFrequencyWeight() {
        return config.clientCache().frequencyWeight();
    }

    /**
     * 获取清理检查间隔（ticks）
     */
    public int getCleanupIntervalTicks() {
        return config.clientCache().cleanupIntervalTicks();
    }

    /**
     * 获取目标缓存大小（MB）
     */
    public int getTargetCacheSizeMb() {
        return config.clientCache().targetCacheSizeMb();
    }

    /**
     * 获取目标缓存大小（字节）
     */
    public long getTargetCacheSizeBytes() {
        return config.clientCache().targetCacheSizeBytes();
    }

    /**
     * 获取每次最少清理区块数
     */
    public int getMinCleanupBatchSize() {
        return config.clientCache().minCleanupBatchSize();
    }

    /**
     * 检查是否强制要求客户端 Mod
     */
    public boolean isRequireClientMod() {
        return config.compat().requireClientMod();
    }

    /**
     * 创建配置快照
     */
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

    /**
     * 配置快照
     */
    public record ConfigSnapshot(
            HassiumConfig config,
            boolean networkCompressionEnabled,
            boolean storageEnabled
    ) {
    }

    /**
     * 加载配置文件
     */
    public void loadConfig() {
        if (configDir == null) {
            LOGGER.warn("Hassium: Config directory not set, using default config");
            return;
        }

        Path configFile = configDir.resolve(CONFIG_FILE_NAME);
        if (!Files.exists(configFile)) {
            LOGGER.info("Hassium: Config file not found, creating default config");
            saveConfig();
            return;
        }

        lock.writeLock().lock();
        try {
            // 读取文件内容
            String content = Files.readString(configFile);
            // 使用支持注释的解析器
            HassiumConfig loadedConfig = CommentedJsonWriter.fromJson(content, HassiumConfig.class);
            if (loadedConfig != null) {
                this.config = loadedConfig;
                this.networkCompressionEnabled.set(loadedConfig.network().enabled());
                this.storageEnabled.set(loadedConfig.storage().enabled());
                configLoaded.set(true);
                LOGGER.info("Hassium: Configuration loaded from {}", configFile);
            } else {
                LOGGER.warn("Hassium: Config file was empty, using default config");
            }
        } catch (JsonSyntaxException e) {
            LOGGER.error("Hassium: Invalid config file syntax, using default config", e);
        } catch (IOException e) {
            LOGGER.error("Hassium: Failed to read config file, using default config", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 保存配置文件
     * <p>
     * 根据当前环境（客户端/服务端）过滤掉不相关的配置项后再写入。
     */
    public void saveConfig() {
        if (configDir == null) {
            LOGGER.warn("Hassium: Config directory not set, cannot save config");
            return;
        }

        lock.readLock().lock();
        try {
            Files.createDirectories(configDir);
            Path configFile = configDir.resolve(CONFIG_FILE_NAME);
            // 根据环境过滤不相关的配置项
            Set<String> skipPaths = CommentedJsonWriter.getSkipPaths(isPhysicalClient);
            try (Writer writer = Files.newBufferedWriter(configFile)) {
                String json = CommentedJsonWriter.toJson(config, skipPaths);
                writer.write(json);
                LOGGER.info("Hassium: Configuration saved to {}", configFile);
            }
        } catch (IOException e) {
            LOGGER.error("Hassium: Failed to save config file", e);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 检查配置是否已加载
     */
    public boolean isConfigLoaded() {
        return configLoaded.get();
    }

    /**
     * 重新加载配置
     */
    public void reloadConfig() {
        loadConfig();
    }

    /**
     * 检查全局包压缩是否启用
     */
    public boolean isGlobalPacketCompressionEnabled() {
        return config.network().globalPacketCompression();
    }

    /**
     * 获取全局压缩等级
     */
    public int getGlobalCompressionLevel() {
        return config.network().globalCompressionLevel();
    }

    /**
     * 获取全局压缩阈值
     */
    public int getGlobalCompressionThreshold() {
        return config.network().globalCompressionThreshold();
    }

    /**
     * 获取压缩黑名单
     */
    public Set<String> getCompressionBlacklist() {
        return config.network().compressionBlacklist();
    }

    /**
     * 检查包是否应该压缩
     */
    public boolean isPacketCompressible(String packetType) {
        return !config.network().compressionBlacklist().contains(packetType);
    }

    /**
     * 检查是否启用上下文压缩
     */
    public boolean isUseContextCompression() {
        return config.network().useContextCompression();
    }

    /**
     * 检查是否启用 magicless ZSTD
     */
    public boolean isMagiclessZstd() {
        return config.network().magiclessZstd();
    }

    /**
     * 检查是否启用包聚合
     */
    public boolean isPacketAggregationEnabled() {
        return config.network().enablePacketAggregation();
    }

    /**
     * 获取聚合最小批量大小
     */
    public int getAggregationMinBatchSize() {
        return config.network().aggregationMinBatchSize();
    }

    /**
     * 获取聚合最大等待时间（毫秒）
     */
    public long getAggregationMaxWaitTimeMs() {
        return config.network().aggregationMaxWaitTimeMs();
    }

    /**
     * 获取聚合最大大小（字节）
     */
    public int getAggregationMaxSize() {
        return config.network().aggregationMaxSize();
    }

    /**
     * 检查是否启用紧凑包头
     */
    public boolean isCompactHeaderEnabled() {
        return config.network().enableCompactHeader();
    }

    /**
     * 获取服务端区块推送线程数
     */
    public int getServerChunkPushThreads() {
        return config.network().serverChunkPushThreads();
    }

    /**
     * 获取客户端区块加载线程数
     */
    public int getClientChunkLoadThreads() {
        return config.network().clientChunkLoadThreads();
    }

    /**
     * 检查光照剥离是否启用
     */
    public boolean isLightStripEnabled() {
        return config.network().lightStripEnabled();
    }

    /**
     * 获取后台线程池大小（平台线程模式）
     */
    public int getBackgroundThreads() {
        return config.network().backgroundThreads();
    }

    /**
     * 获取每帧安全硬顶（最多应用缓存区块数）；主限流改用时间预算。
     */
    public int getMaxChunksPerFrame() {
        int value = config.network().maxChunksPerFrame();
        return Math.max(1, value); // 至少为 1，避免为 0 导致无法处理
    }

    /**
     * 获取每帧安全硬顶（最多主线程异步回调数）；主限流改用时间预算。
     */
    public int getMaxCallbacksPerFrame() {
        int value = config.network().maxCallbacksPerFrame();
        return Math.max(1, value); // 至少为 1，避免为 0 导致无法处理
    }

    /**
     * 获取客户端主线程每帧区块应用时间预算（毫秒）。
     * <p>
     * 旧配置缺失该字段时 Gson 会读出 0，回退到默认 3ms。
     */
    public int getMainThreadChunkBudgetMs() {
        int value = config.network().mainThreadChunkBudgetMs();
        if (value <= 0) {
            return 3;
        }
        return Math.min(50, value); // 上限 50ms，避免单帧占满
    }

    /**
     * 检查指标收集是否启用
     */
    public boolean isMetricsEnabled() {
        return config.network().metricsEnabled();
    }

    /**
     * 获取目标 FPS（遗留配置，不再参与限流）
     */
    public int getTargetFPS() {
        int value = config.network().targetFPS();
        return Math.max(1, value); // 至少为 1，避免除零
    }

    /**
     * 获取每帧最多重算光照区块数
     */
    public int getMaxLightRecomputePerFrame() {
        int value = config.network().maxLightRecomputePerFrame();
        return Math.max(1, value); // 至少为 1
    }

    /**
     * 检查动态线程池是否启用
     */
    public boolean isDynamicThreadPoolEnabled() {
        return config.network().dynamicThreadPoolEnabled();
    }

    /**
     * 获取最小推送线程数
     */
    public int getMinPushThreads() {
        int value = config.network().minPushThreads();
        return Math.max(1, value); // 至少为 1
    }

    /**
     * 获取最大推送线程数
     */
    public int getMaxPushThreads() {
        int value = config.network().maxPushThreads();
        int minThreads = getMinPushThreads();
        return Math.max(minThreads, value); // 至少等于最小线程数
    }

    /**
     * 检查 Bloom Filter 是否启用
     */
    public boolean isBloomFilterEnabled() {
        return config.clientCache().bloomFilterEnabled();
    }

    /**
     * 获取 Bloom Filter 预期插入元素数量
     */
    public int getBloomFilterExpectedInsertions() {
        int value = config.clientCache().bloomFilterExpectedInsertions();
        return Math.max(1000, value); // 至少 1000
    }

    /**
     * 获取 Bloom Filter 期望假阳性率
     */
    public double getBloomFilterFpp() {
        double value = config.clientCache().bloomFilterFpp();
        return Math.max(0.001, Math.min(0.1, value)); // 限制在 0.1% 到 10% 之间
    }
}
