package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.config.HassiumConfig;
import net.minecraft.world.level.ChunkPos;

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

    private final ClientHeatIndex heatIndex;
    private final ClientHassiumStorage storage;
    private final String serverId;

    public CacheEvictionManager(ClientHeatIndex heatIndex, ClientHassiumStorage storage) {
        this.heatIndex = heatIndex;
        this.storage = storage;
        this.serverId = storage.getServerId();
    }

    /**
     * 计算区块的热度评分
     *
     * @param accessCount        访问次数
     * @param lastAccessGameTime 最后访问的游戏时间
     * @param currentGameTime    当前游戏时间
     * @return 热度评分 (0.0 - 1.0)
     */
    public static double calculateHotScore(int accessCount, long lastAccessGameTime, long currentGameTime) {
        return calculateHotScore(accessCount, lastAccessGameTime, currentGameTime,
                DEFAULT_RECENCY_WEIGHT, DEFAULT_FREQUENCY_WEIGHT);
    }

    /**
     * 计算区块的热度评分（可配置权重）
     */
    public static double calculateHotScore(int accessCount, long lastAccessGameTime, long currentGameTime,
                                           double recencyWeight, double frequencyWeight) {
        long timeDiff = Math.max(0, currentGameTime - lastAccessGameTime);
        double recencyScore = 1.0 / (1.0 + timeDiff);
        double frequencyScore = 1.0 / (1.0 + accessCount);
        return recencyWeight * recencyScore + frequencyWeight * frequencyScore;
    }

    public boolean shouldCleanup(long currentSizeBytes, HassiumConfig.ClientCacheConfig config) {
        long maxSizeBytes = config.maxCacheSizeBytes();
        if (maxSizeBytes <= 0) {
            return false;
        }
        if (currentSizeBytes > maxSizeBytes) {
            return true;
        }
        long targetSizeBytes = getTargetSizeBytes(config);
        return currentSizeBytes > targetSizeBytes * 0.9;
    }

    public int performCleanup(long currentGameTime, HassiumConfig.ClientCacheConfig config) {
        try {
            ClientHeatIndex.CacheStats stats = heatIndex.getStatsByServer(serverId);
            long currentSizeBytes = stats.totalSize();

            if (!shouldCleanup(currentSizeBytes, config)) {
                return 0;
            }

            long targetSizeBytes = getTargetSizeBytes(config);
            long sizeToFree = currentSizeBytes - targetSizeBytes;

            Constants.LOG.info("Hassium: [CACHE CLEANUP] Triggered: currentSize={}MB, targetSize={}MB, needToFree={}MB",
                    bytesToMB(currentSizeBytes), bytesToMB(targetSizeBytes), bytesToMB(sizeToFree));

            int batchSize = Math.max(config.minCleanupBatchSize(), 100);
            List<ClientHeatIndex.CacheEntryInfo> coldEntries =
                    heatIndex.getColdEntriesByServer(serverId, currentGameTime, batchSize);

            int removedCount = 0;
            long freedBytes = 0;

            for (ClientHeatIndex.CacheEntryInfo entry : coldEntries) {
                if (freedBytes >= sizeToFree) {
                    break;
                }
                if (entry.hotScore() > config.hotScoreThreshold()) {
                    continue;
                }

                // 仅当条目属于当前 storage 的维度时删除磁盘块
                if (!entry.dimension().equals(storage.getDimension())) {
                    heatIndex.deleteEntry(entry.serverId(), entry.chunkX(), entry.chunkZ(), entry.dimension());
                    removedCount++;
                    freedBytes += entry.chunkBytes();
                    continue;
                }

                if (storage.remove(new ChunkPos(entry.chunkX(), entry.chunkZ()))) {
                    removedCount++;
                    freedBytes += entry.chunkBytes();
                    Constants.LOG.debug("Hassium: [CACHE CLEANUP] Removed chunk [{}, {}] (hotScore={}, size={}KB)",
                            entry.chunkX(), entry.chunkZ(),
                            String.format("%.3f", entry.hotScore()),
                            entry.chunkBytes() / 1024);
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

    public int clearDimension(String dimension) {
        try {
            List<ClientHeatIndex.CacheEntryInfo> entries = heatIndex.getAllEntriesByServer(serverId);
            int removedCount = 0;

            for (ClientHeatIndex.CacheEntryInfo entry : entries) {
                if (!entry.dimension().equals(dimension)) {
                    continue;
                }
                if (dimension.equals(storage.getDimension())) {
                    if (storage.remove(new ChunkPos(entry.chunkX(), entry.chunkZ()))) {
                        removedCount++;
                    }
                } else {
                    heatIndex.deleteEntry(entry.serverId(), entry.chunkX(), entry.chunkZ(), entry.dimension());
                    removedCount++;
                }
            }

            heatIndex.deleteByServerAndDimension(serverId, dimension);

            Constants.LOG.info("Hassium: [CACHE CLEANUP] Cleared {} chunks for server {} dimension {}",
                    removedCount, serverId, dimension);
            return removedCount;

        } catch (Exception e) {
            Constants.LOG.error("Hassium: [CACHE CLEANUP] Error clearing server {} dimension {}",
                    serverId, dimension, e);
            return 0;
        }
    }

    public int clearRegion(int regionX, int regionZ, String dimension) {
        try {
            List<ClientHeatIndex.CacheEntryInfo> entries = heatIndex.getAllEntriesByServer(serverId);
            int removedCount = 0;

            for (ClientHeatIndex.CacheEntryInfo entry : entries) {
                if (entry.regionX() != regionX || entry.regionZ() != regionZ
                        || !entry.dimension().equals(dimension)) {
                    continue;
                }
                if (dimension.equals(storage.getDimension())) {
                    if (storage.remove(new ChunkPos(entry.chunkX(), entry.chunkZ()))) {
                        removedCount++;
                    }
                } else {
                    heatIndex.deleteEntry(entry.serverId(), entry.chunkX(), entry.chunkZ(), entry.dimension());
                    removedCount++;
                }
            }

            heatIndex.deleteByServerAndRegion(serverId, regionX, regionZ, dimension);

            Constants.LOG.debug("Hassium: [CACHE CLEANUP] Cleared {} chunks for server {} region [{}, {}]",
                    removedCount, serverId, regionX, regionZ);
            return removedCount;

        } catch (Exception e) {
            Constants.LOG.error("Hassium: [CACHE CLEANUP] Error clearing server {} region [{}, {}]",
                    serverId, regionX, regionZ, e);
            return 0;
        }
    }

    public int clearAll() {
        try {
            int count = heatIndex.getEntryCountByServer(serverId);
            List<ClientHeatIndex.CacheEntryInfo> entries = heatIndex.getAllEntriesByServer(serverId);

            for (ClientHeatIndex.CacheEntryInfo entry : entries) {
                if (entry.dimension().equals(storage.getDimension())) {
                    storage.remove(new ChunkPos(entry.chunkX(), entry.chunkZ()));
                }
            }

            heatIndex.clearByServer(serverId);

            Constants.LOG.info("Hassium: [CACHE CLEANUP] Cleared all cache for server {}: {} chunks", serverId, count);
            return count;

        } catch (Exception e) {
            Constants.LOG.error("Hassium: [CACHE CLEANUP] Error clearing all cache for server {}", serverId, e);
            return 0;
        }
    }

    private long getTargetSizeBytes(HassiumConfig.ClientCacheConfig config) {
        return config.targetCacheSizeBytes();
    }

    private static long bytesToMB(long bytes) {
        return bytes / (1024 * 1024);
    }

    public HotStats getHotStats(long currentGameTime) {
        try {
            List<ClientHeatIndex.CacheEntryInfo> entries = heatIndex.getAllEntriesByServer(serverId);

            int hotCount = 0;
            int warmCount = 0;
            int coldCount = 0;
            double totalHotScore = 0;

            for (ClientHeatIndex.CacheEntryInfo entry : entries) {
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

    public record HotStats(
            int totalChunks,
            int hotChunks,
            int warmChunks,
            int coldChunks,
            double averageHotScore
    ) {
    }
}
