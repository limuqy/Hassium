package io.github.limuqy.mc.hassium.cache;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import io.github.limuqy.mc.hassium.config.HassiumConfig;
import io.github.limuqy.mc.hassium.storage.ChunkStorageKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 客户端区块缓存
 * <p>
 * 管理客户端本地的区块缓存，支持容量限制和 LRU 清理策略。
 * 缓存索引持久化到 JSON 文件，启动时自动加载。
 */
public class ClientChunkCache {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/ChunkCache");
    private static final String INDEX_FILE_NAME = "hassium_cache_index.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type INDEX_TYPE = new TypeToken<List<CacheIndexEntry>>() {}.getType();

    private final Path cacheDir;
    private final HassiumConfig config;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private final ConcurrentHashMap<String, CacheEntry> cacheIndex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> accessTimes = new ConcurrentHashMap<>();

    private volatile boolean dirty = false;

    public ClientChunkCache(Path cacheDir, HassiumConfig config) throws IOException {
        this.cacheDir = cacheDir;
        this.config = config;
        initializeCacheDirectory();
    }

    private void initializeCacheDirectory() throws IOException {
        if (!Files.exists(cacheDir)) {
            Files.createDirectories(cacheDir);
        }
        loadCacheIndex();
    }

    private void loadCacheIndex() {
        Path indexFile = cacheDir.resolve(INDEX_FILE_NAME);
        if (!Files.exists(indexFile)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(indexFile)) {
            List<CacheIndexEntry> entries = GSON.fromJson(reader, INDEX_TYPE);
            if (entries == null) {
                return;
            }

            int loaded = 0;
            int pruned = 0;
            for (CacheIndexEntry entry : entries) {
                Path filePath = cacheDir.resolve(entry.relativePath);
                if (!Files.exists(filePath)) {
                    pruned++;
                    continue;
                }

                CacheEntry cacheEntry = new CacheEntry(
                        entry.cacheKey,
                        entry.revision,
                        filePath,
                        entry.size,
                        entry.lastModified
                );
                cacheIndex.put(entry.cacheKey, cacheEntry);
                accessTimes.put(entry.cacheKey, entry.lastAccess);
                loaded++;
            }

            if (pruned > 0) {
                dirty = true;
                saveCacheIndex();
            }

            LOGGER.info("Loaded cache index: {} entries ({} pruned)", loaded, pruned);
        } catch (Exception e) {
            LOGGER.error("Failed to load cache index, starting fresh", e);
            cacheIndex.clear();
            accessTimes.clear();
        }
    }

    private void saveCacheIndex() {
        Path indexFile = cacheDir.resolve(INDEX_FILE_NAME);

        List<CacheIndexEntry> entries = cacheIndex.entrySet().stream()
                .map(e -> {
                    CacheEntry entry = e.getValue();
                    String relativePath = cacheDir.relativize(entry.filePath()).toString();
                    return new CacheIndexEntry(
                            entry.cacheKey(),
                            entry.revision(),
                            relativePath,
                            entry.size(),
                            entry.lastModified(),
                            accessTimes.getOrDefault(entry.cacheKey(), entry.lastModified())
                    );
                })
                .toList();

        try (Writer writer = Files.newBufferedWriter(indexFile)) {
            GSON.toJson(entries, INDEX_TYPE, writer);
            dirty = false;
        } catch (IOException e) {
            LOGGER.error("Failed to save cache index", e);
        }
    }

    public byte[] get(ChunkStorageKey key, long revision) {
        lock.readLock().lock();
        try {
            String cacheKey = key.toCacheKey();
            CacheEntry entry = cacheIndex.get(cacheKey);

            if (entry == null) {
                return null;
            }

            if (entry.revision() != revision) {
                return null;
            }

            accessTimes.put(cacheKey, System.currentTimeMillis());
            dirty = true;

            return readCacheFile(entry.filePath());
        } finally {
            lock.readLock().unlock();
        }
    }

    public void put(ChunkStorageKey key, long revision, byte[] data) {
        lock.writeLock().lock();
        try {
            String cacheKey = key.toCacheKey();

            ensureCapacity();

            Path filePath = generateCacheFilePath(key);

            Files.createDirectories(filePath.getParent());
            writeCacheFile(filePath, data);

            CacheEntry entry = new CacheEntry(
                    cacheKey,
                    revision,
                    filePath,
                    data.length,
                    System.currentTimeMillis()
            );
            cacheIndex.put(cacheKey, entry);
            accessTimes.put(cacheKey, System.currentTimeMillis());

            saveCacheIndex();
        } catch (IOException e) {
            LOGGER.error("Failed to write cache for {}", key.toCacheKey(), e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean contains(ChunkStorageKey key, long revision) {
        lock.readLock().lock();
        try {
            String cacheKey = key.toCacheKey();
            CacheEntry entry = cacheIndex.get(cacheKey);
            return entry != null && entry.revision() == revision;
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean remove(ChunkStorageKey key) {
        lock.writeLock().lock();
        try {
            String cacheKey = key.toCacheKey();
            CacheEntry entry = cacheIndex.remove(cacheKey);
            accessTimes.remove(cacheKey);

            if (entry != null) {
                try {
                    Files.deleteIfExists(entry.filePath());
                } catch (IOException e) {
                    LOGGER.warn("Failed to delete cache file: {}", entry.filePath());
                }
                saveCacheIndex();
                return true;
            }
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int clearDimension(String dimension) {
        lock.writeLock().lock();
        try {
            int count = 0;
            var iterator = cacheIndex.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (entry.getKey().startsWith(dimension + "/")) {
                    CacheEntry removed = entry.getValue();
                    iterator.remove();
                    accessTimes.remove(entry.getKey());
                    try {
                        Files.deleteIfExists(removed.filePath());
                    } catch (IOException e) {
                        LOGGER.warn("Failed to delete cache file: {}", removed.filePath());
                    }
                    count++;
                }
            }
            if (count > 0) {
                saveCacheIndex();
            }
            return count;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void clear() {
        lock.writeLock().lock();
        try {
            for (CacheEntry entry : cacheIndex.values()) {
                try {
                    Files.deleteIfExists(entry.filePath());
                } catch (IOException e) {
                    LOGGER.warn("Failed to delete cache file: {}", entry.filePath());
                }
            }

            cacheIndex.clear();
            accessTimes.clear();
            saveCacheIndex();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 刷新脏索引到磁盘（定期调用或在关闭时调用）
     */
    public void flush() {
        if (dirty) {
            lock.readLock().lock();
            try {
                saveCacheIndex();
            } finally {
                lock.readLock().unlock();
            }
        }
    }

    /**
     * 清理过期条目
     */
    public int pruneExpired() {
        lock.writeLock().lock();
        try {
            long maxAgeMillis = (long) config.clientCache().maxAgeDays() * 24 * 60 * 60 * 1000;
            long cutoff = System.currentTimeMillis() - maxAgeMillis;

            int count = 0;
            var iterator = cacheIndex.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                long lastAccess = accessTimes.getOrDefault(entry.getKey(), 0L);
                if (lastAccess < cutoff) {
                    CacheEntry removed = entry.getValue();
                    iterator.remove();
                    accessTimes.remove(entry.getKey());
                    try {
                        Files.deleteIfExists(removed.filePath());
                    } catch (IOException e) {
                        LOGGER.warn("Failed to delete expired cache file: {}", removed.filePath());
                    }
                    count++;
                }
            }

            if (count > 0) {
                saveCacheIndex();
                LOGGER.info("Pruned {} expired cache entries", count);
            }
            return count;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void ensureCapacity() {
        long maxSize = config.clientCache().maxCacheSizeBytes();
        if (maxSize <= 0) {
            return;
        }

        long currentSize = cacheIndex.values().stream()
                .mapToLong(CacheEntry::size)
                .sum();

        while (currentSize > maxSize && !cacheIndex.isEmpty()) {
            String oldestKey = accessTimes.entrySet().stream()
                    .min(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);

            if (oldestKey != null) {
                CacheEntry removed = cacheIndex.remove(oldestKey);
                accessTimes.remove(oldestKey);
                if (removed != null) {
                    currentSize -= removed.size();
                    try {
                        Files.deleteIfExists(removed.filePath());
                    } catch (IOException e) {
                        LOGGER.warn("Failed to delete evicted cache file: {}", removed.filePath());
                    }
                }
            } else {
                break;
            }
        }
    }

    private Path generateCacheFilePath(ChunkStorageKey key) {
        // dimension/regionX_regionZ/chunkX_chunkZ.bin
        String dimDir = key.dimension().replace(":", "_");
        String regionDir = String.format("r.%d.%d", key.regionX(), key.regionZ());
        String fileName = String.format("c.%d.%d.bin", key.chunkX(), key.chunkZ());
        return cacheDir.resolve(dimDir).resolve(regionDir).resolve(fileName);
    }

    private byte[] readCacheFile(Path filePath) {
        try {
            return Files.readAllBytes(filePath);
        } catch (IOException e) {
            LOGGER.warn("Failed to read cache file: {}", filePath);
            return null;
        }
    }

    private void writeCacheFile(Path filePath, byte[] data) throws IOException {
        Files.write(filePath, data);
    }

    public CacheStats getStats() {
        lock.readLock().lock();
        try {
            long totalSize = cacheIndex.values().stream()
                    .mapToLong(CacheEntry::size)
                    .sum();

            return new CacheStats(
                    cacheIndex.size(),
                    totalSize
            );
        } finally {
            lock.readLock().unlock();
        }
    }

    public record CacheEntry(
            String cacheKey,
            long revision,
            Path filePath,
            long size,
            long lastModified
    ) {
    }

    public record CacheStats(
            int entryCount,
            long totalSize
    ) {
    }

    /**
     * 索引文件中的序列化条目
     */
    private record CacheIndexEntry(
            String cacheKey,
            long revision,
            String relativePath,
            long size,
            long lastModified,
            long lastAccess
    ) {
    }
}
