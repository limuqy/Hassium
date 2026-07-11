package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.config.HassiumConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 缓存清理管理器
 * <p>
 * 基于热度评分的缓存清理策略，支持：
 * - LRU（最近最少使用）
 * - 热度加权（访问频率 + 最后访问时间）
 * - 可配置的清理阈值和目标大小
 */
public class CacheEvictionManager {

    private static final double DEFAULT_RECENCY_WEIGHT = 0.7;
    private static final double DEFAULT_FREQUENCY_WEIGHT = 0.3;
    private static final double DEFAULT_HOT_SCORE_THRESHOLD = 0.3;

    private final ClientCacheDatabase database;
    private final ClientHassiumStorage storage;
    private final String serverId;

    public CacheEvictionManager(ClientCacheDatabase database, ClientHassiumStorage storage) {
        this.database = database;
        this.storage = storage;
        this.serverId = storage.getServerId();
    }

    /**
     * 计算区块的热度评分
     *
     * @param accessCount      访问次数
     * @param lastAccessGameTime 最后访问的游戏时间
     * @param currentGameTime  当前游戏时间
     * @return 热度评分 (0.0 - 1.0)
     */
    public static double calculateHotScore(int accessCount, long lastAccessGameTime, long currentGameTime) {
        return calculateHotScore(accessCount, lastAccessGameTime, currentGameTime,
                DEFAULT_RECENCY_WEIGHT, DEFAULT_FREQUENCY_WEIGHT);
    }

    /**
     * 计算区块的热度评分（可配置权重）
     *
     * @param accessCount      访问次数
     * @param lastAccessGameTime 最后访问的游戏时间
     * @param currentGameTime  当前游戏时间
     * @param recencyWeight    最近访问权重
     * @param frequencyWeight  访问频率权重
     * @return 热度评分 (0.0 - 1.0)
     */
    public static double calculateHotScore(int accessCount, long lastAccessGameTime, long currentGameTime,
                                           double recencyWeight, double frequencyWeight) {
        // 时间差（游戏 ticks），确保非负
        long timeDiff = Math.max(0, currentGameTime - lastAccessGameTime);

        // 最近访问评分：时间越近，评分越高
        double recencyScore = 1.0 / (1.0 + timeDiff);

        // 频率评分：访问次数越多，评分越高
        double frequencyScore = 1.0 / (1.0 + accessCount);

        // 加权计算
        return recencyWeight * recencyScore + frequencyWeight * frequencyScore;
    }

    /**
     * 判断是否需要执行清理
     *
     * @param currentSizeBytes 当前缓存大小（字节）
     * @param config           缓存配置
     * @return 是否需要清理
     */
    public boolean shouldCleanup(long currentSizeBytes, HassiumConfig.ClientCacheConfig config) {
        long maxSizeBytes = config.maxCacheSizeBytes();
        if (maxSizeBytes <= 0) {
            return false;
        }

        // 当前大小超过最大限制
        if (currentSizeBytes > maxSizeBytes) {
            return true;
        }

        // 当前大小超过目标大小的 90%（提前清理，避免频繁触发）
        long targetSizeBytes = getTargetSizeBytes(config);
        return currentSizeBytes > targetSizeBytes * 0.9;
    }

    /**
     * 执行缓存清理
     *
     * @param currentGameTime 当前游戏时间
     * @param config          缓存配置
     * @return 清理的区块数量
     */
    public int performCleanup(long currentGameTime, HassiumConfig.ClientCacheConfig config) {
        try {
            ClientCacheDatabase.CacheStats stats = database.getStatsByServer(serverId);
            long currentSizeBytes = stats.totalSize();

            if (!shouldCleanup(currentSizeBytes, config)) {
                return 0;
            }

            long targetSizeBytes = getTargetSizeBytes(config);
            long sizeToFree = currentSizeBytes - targetSizeBytes;

            Constants.LOG.info("Hassium: [CACHE CLEANUP] Triggered: currentSize={}MB, targetSize={}MB, needToFree={}MB",
                    bytesToMB(currentSizeBytes), bytesToMB(targetSizeBytes), bytesToMB(sizeToFree));

            // 获取冷区块列表
            int batchSize = Math.max(config.minCleanupBatchSize(), 100);
            List<ClientCacheDatabase.CacheEntryInfo> coldEntries = database.getColdEntriesByServer(serverId, currentGameTime, batchSize);

            int removedCount = 0;
            long freedBytes = 0;

            for (ClientCacheDatabase.CacheEntryInfo entry : coldEntries) {
                // 检查是否已经释放足够的空间
                if (freedBytes >= sizeToFree) {
                    break;
                }

                // 检查热度阈值：只清理冷区块
                if (entry.hotScore() > config.hotScoreThreshold()) {
                    // 热度过高的区块不清理
                    continue;
                }

                // 删除缓存文件
                if (deleteCacheFile(entry)) {
                    // 从数据库删除记录
                    database.deleteEntry(entry.serverId(), entry.chunkX(), entry.chunkZ(), entry.dimension());
                    removedCount++;
                    freedBytes += entry.fileSize();

                    Constants.LOG.debug("Hassium: [CACHE CLEANUP] Removed chunk [{}, {}] (hotScore={}, size={}KB)",
                            entry.chunkX(), entry.chunkZ(),
                            String.format("%.3f", entry.hotScore()),
                            entry.fileSize() / 1024);
                }
            }

            Constants.LOG.info("Hassium: [CACHE CLEANUP] Complete: removed {} chunks, freed {}MB",
                    removedCount, bytesToMB(freedBytes));

            return removedCount;

        } catch (Exception e) {
            Constants.LOG.error("Hassium: [CACHE CLEANUP] Error during cleanup", e);
            return 0;
        }
    }

    /**
     * 清理指定服务器和维度的所有缓存
     *
     * @param dimension 维度名称
     * @return 清理的区块数量
     */
    public int clearDimension(String dimension) {
        try {
            List<ClientCacheDatabase.CacheEntryInfo> entries = database.getAllEntriesByServer(serverId);
            int removedCount = 0;

            for (ClientCacheDatabase.CacheEntryInfo entry : entries) {
                if (entry.dimension().equals(dimension)) {
                    if (deleteCacheFile(entry)) {
                        removedCount++;
                    }
                }
            }

            database.deleteByServerAndDimension(serverId, dimension);

            Constants.LOG.info("Hassium: [CACHE CLEANUP] Cleared {} chunks for server {} dimension {}",
                    removedCount, serverId, dimension);
            return removedCount;

        } catch (Exception e) {
            Constants.LOG.error("Hassium: [CACHE CLEANUP] Error clearing server {} dimension {}",
                    serverId, dimension, e);
            return 0;
        }
    }

    /**
     * 清理指定服务器和 region 的所有缓存
     *
     * @param regionX   region X 坐标
     * @param regionZ   region Z 坐标
     * @param dimension 维度名称
     * @return 清理的区块数量
     */
    public int clearRegion(int regionX, int regionZ, String dimension) {
        try {
            List<ClientCacheDatabase.CacheEntryInfo> entries = database.getAllEntriesByServer(serverId);
            int removedCount = 0;

            for (ClientCacheDatabase.CacheEntryInfo entry : entries) {
                if (entry.regionX() == regionX && entry.regionZ() == regionZ && entry.dimension().equals(dimension)) {
                    if (deleteCacheFile(entry)) {
                        removedCount++;
                    }
                }
            }

            database.deleteByServerAndRegion(serverId, regionX, regionZ, dimension);

            Constants.LOG.debug("Hassium: [CACHE CLEANUP] Cleared {} chunks for server {} region [{}, {}]",
                    removedCount, serverId, regionX, regionZ);
            return removedCount;

        } catch (Exception e) {
            Constants.LOG.error("Hassium: [CACHE CLEANUP] Error clearing server {} region [{}, {}]",
                    serverId, regionX, regionZ, e);
            return 0;
        }
    }

    /**
     * 清空指定服务器的所有缓存
     *
     * @return 清理的区块数量
     */
    public int clearAll() {
        try {
            int count = database.getEntryCountByServer(serverId);
            List<ClientCacheDatabase.CacheEntryInfo> entries = database.getAllEntriesByServer(serverId);

            for (ClientCacheDatabase.CacheEntryInfo entry : entries) {
                deleteCacheFile(entry);
            }

            database.clearByServer(serverId);

            Constants.LOG.info("Hassium: [CACHE CLEANUP] Cleared all cache for server {}: {} chunks", serverId, count);
            return count;

        } catch (Exception e) {
            Constants.LOG.error("Hassium: [CACHE CLEANUP] Error clearing all cache for server {}", serverId, e);
            return 0;
        }
    }

    /**
     * 删除缓存文件
     */
    private boolean deleteCacheFile(ClientCacheDatabase.CacheEntryInfo entry) {
        try {
            // 使用相对于缓存根目录的路径
            Path cacheRoot = storage.getCacheRoot();
            Path filePath = cacheRoot.resolve(entry.filePath());
            if (Files.exists(filePath)) {
                Files.delete(filePath);
                return true;
            }
            return false;
        } catch (IOException e) {
            Constants.LOG.warn("Hassium: [CACHE CLEANUP] Failed to delete cache file: {}", entry.filePath(), e);
            return false;
        }
    }

    /**
     * 获取目标缓存大小（字节）
     */
    private long getTargetSizeBytes(HassiumConfig.ClientCacheConfig config) {
        long maxSizeBytes = config.maxCacheSizeBytes();
        if (config.targetCacheSizeMb() > 0) {
            return (long) config.targetCacheSizeMb() * 1024 * 1024;
        }
        // 默认目标大小为最大大小的 80%
        return (long) (maxSizeBytes * 0.8);
    }

    /**
     * 字节转 MB
     */
    private static long bytesToMB(long bytes) {
        return bytes / (1024 * 1024);
    }

    /**
     * 获取热度统计信息
     */
    public HotStats getHotStats(long currentGameTime) {
        try {
            List<ClientCacheDatabase.CacheEntryInfo> entries = database.getAllEntriesByServer(serverId);

            int hotCount = 0;
            int warmCount = 0;
            int coldCount = 0;
            double totalHotScore = 0;

            for (ClientCacheDatabase.CacheEntryInfo entry : entries) {
                double hotScore = calculateHotScore(
                        entry.accessCount(),
                        entry.lastAccessGameTime(),
                        currentGameTime
                );

                if (hotScore > 0.7) {
                    hotCount++;
                } else if (hotScore > 0.3) {
                    warmCount++;
                } else {
                    coldCount++;
                }
                totalHotScore += hotScore;
            }

            double avgHotScore = entries.isEmpty() ? 0 : totalHotScore / entries.size();

            return new HotStats(entries.size(), hotCount, warmCount, coldCount, avgHotScore);

        } catch (Exception e) {
            Constants.LOG.error("Hassium: Failed to get hot stats for server {}", serverId, e);
            return new HotStats(0, 0, 0, 0, 0);
        }
    }

    /**
     * 热度统计信息
     */
    public record HotStats(
            int totalChunks,
            int hotChunks,
            int warmChunks,
            int coldChunks,
            double averageHotScore
    ) {
    }
}
