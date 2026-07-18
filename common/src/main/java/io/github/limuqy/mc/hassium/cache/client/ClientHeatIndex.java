package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.Constants;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 客户端缓存热度 / LRU 索引（全局共享）。
 * <p>
 * 与 section 哈希存储分离；命中路径不读本文件。热度数据可丢，偶尔丢失不影响功能。
 * <p>
 * 文件：{@code config/hassium/heat.idx}
 */
public class ClientHeatIndex implements Closeable {

    private static final int MAGIC = 0x48454154; // "HEAT"
    private static final short VERSION = 1;
    private static final String FILE_NAME = "heat.idx";

    private final Path filePath;
    private final ConcurrentHashMap<String, HeatEntry> entries = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<Runnable> writeQueue = new LinkedBlockingQueue<>();
    private final Thread writerThread;
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private volatile boolean closed = false;

    public ClientHeatIndex(Path configDir) throws IOException {
        Files.createDirectories(configDir);
        this.filePath = configDir.resolve(FILE_NAME);
        load();
        writerThread = new Thread(this::drainLoop, "Hassium-HeatIndex-Writer");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    private static String key(String serverId, int chunkX, int chunkZ, String dimension) {
        return serverId + '\0' + dimension + '\0' + chunkX + '\0' + chunkZ;
    }

    /**
     * 插入或更新条目（异步）。{@code chunkBytes} 为单块压缩大小。
     */
    public void upsert(CacheEntryInfo entry) {
        submitWrite(() -> {
            String k = key(entry.serverId(), entry.chunkX(), entry.chunkZ(), entry.dimension());
            HeatEntry existing = entries.get(k);
            int accessCount = entry.accessCount();
            long lastAccess = entry.lastAccessGameTime();
            if (existing != null) {
                accessCount = Math.max(accessCount, existing.accessCount);
                if (lastAccess < existing.lastAccessGameTime) {
                    lastAccess = existing.lastAccessGameTime;
                }
            }
            entries.put(k, new HeatEntry(
                    entry.serverId(),
                    entry.chunkX(),
                    entry.chunkZ(),
                    entry.dimension(),
                    entry.regionX(),
                    entry.regionZ(),
                    accessCount,
                    lastAccess,
                    entry.chunkBytes()
            ));
            dirty.set(true);
        });
    }

    public void updateAccess(String serverId, int chunkX, int chunkZ, String dimension, long currentGameTime) {
        submitWrite(() -> {
            String k = key(serverId, chunkX, chunkZ, dimension);
            HeatEntry existing = entries.get(k);
            if (existing == null) {
                return;
            }
            entries.put(k, new HeatEntry(
                    existing.serverId,
                    existing.chunkX,
                    existing.chunkZ,
                    existing.dimension,
                    existing.regionX,
                    existing.regionZ,
                    existing.accessCount + 1,
                    currentGameTime,
                    existing.chunkBytes
            ));
            dirty.set(true);
        });
    }

    public void deleteEntry(String serverId, int chunkX, int chunkZ, String dimension) {
        submitWrite(() -> {
            if (entries.remove(key(serverId, chunkX, chunkZ, dimension)) != null) {
                dirty.set(true);
            }
        });
    }

    public void deleteByServerAndDimension(String serverId, String dimension) {
        submitWrite(() -> {
            boolean changed = entries.entrySet().removeIf(e ->
                    e.getValue().serverId.equals(serverId) && e.getValue().dimension.equals(dimension));
            if (changed) {
                dirty.set(true);
            }
        });
    }

    public void deleteByServerAndRegion(String serverId, int regionX, int regionZ, String dimension) {
        submitWrite(() -> {
            boolean changed = entries.entrySet().removeIf(e -> {
                HeatEntry v = e.getValue();
                return v.serverId.equals(serverId)
                        && v.regionX == regionX
                        && v.regionZ == regionZ
                        && v.dimension.equals(dimension);
            });
            if (changed) {
                dirty.set(true);
            }
        });
    }

    public void clearByServer(String serverId) {
        submitWrite(() -> {
            boolean changed = entries.entrySet().removeIf(e -> e.getValue().serverId.equals(serverId));
            if (changed) {
                dirty.set(true);
            }
        });
    }

    public List<CacheEntryInfo> getAllEntriesByServer(String serverId) {
        List<CacheEntryInfo> result = new ArrayList<>();
        for (HeatEntry e : entries.values()) {
            if (e.serverId.equals(serverId)) {
                result.add(e.toInfo(0.0));
            }
        }
        return result;
    }

    public List<CacheEntryInfo> getColdEntriesByServer(String serverId, long currentGameTime, int limit) {
        List<CacheEntryInfo> result = new ArrayList<>();
        for (HeatEntry e : entries.values()) {
            if (!e.serverId.equals(serverId)) {
                continue;
            }
            double score = CacheEvictionManager.calculateHotScore(
                    e.accessCount, e.lastAccessGameTime, currentGameTime);
            result.add(e.toInfo(score));
        }
        result.sort(Comparator.comparingDouble(CacheEntryInfo::hotScore));
        if (result.size() > limit) {
            return result.subList(0, limit);
        }
        return result;
    }

    public CacheStats getStatsByServer(String serverId) {
        int count = 0;
        long total = 0;
        for (HeatEntry e : entries.values()) {
            if (e.serverId.equals(serverId)) {
                count++;
                total += e.chunkBytes;
            }
        }
        return new CacheStats(count, total);
    }

    public int getEntryCountByServer(String serverId) {
        int count = 0;
        for (HeatEntry e : entries.values()) {
            if (e.serverId.equals(serverId)) {
                count++;
            }
        }
        return count;
    }

    private void submitWrite(Runnable task) {
        if (closed) {
            return;
        }
        writeQueue.offer(task);
    }

    private void drainLoop() {
        while (!closed || !writeQueue.isEmpty()) {
            try {
                Runnable task = writeQueue.poll(500, TimeUnit.MILLISECONDS);
                if (task != null) {
                    task.run();
                } else if (dirty.get()) {
                    flush();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                Constants.LOG.warn("Hassium: Heat index write task failed", e);
            }
        }
        Runnable remaining;
        while ((remaining = writeQueue.poll()) != null) {
            try {
                remaining.run();
            } catch (Exception e) {
                Constants.LOG.warn("Hassium: Heat index write task failed during shutdown", e);
            }
        }
        flush();
    }

    private void load() {
        if (!Files.exists(filePath)) {
            return;
        }
        try {
            byte[] all = Files.readAllBytes(filePath);
            if (all.length < 10) {
                Constants.LOG.warn("Hassium: heat.idx too small, rebuilding");
                return;
            }
            ByteBuffer buf = ByteBuffer.wrap(all).order(ByteOrder.BIG_ENDIAN);
            int magic = buf.getInt();
            short version = buf.getShort();
            if (magic != MAGIC || version != VERSION) {
                Constants.LOG.warn("Hassium: heat.idx magic/version mismatch, rebuilding");
                return;
            }
            int count = buf.getInt();
            if (count < 0 || count > 5_000_000) {
                Constants.LOG.warn("Hassium: heat.idx corrupt count={}, rebuilding", count);
                return;
            }
            for (int i = 0; i < count; i++) {
                String serverId = readUtf(buf);
                String dimension = readUtf(buf);
                // 定长字段：chunkX/Z + regionX/Z + accessCount + lastAccess + chunkBytes = 36
                if (serverId == null || dimension == null || buf.remaining() < 36) {
                    Constants.LOG.warn("Hassium: heat.idx truncated at entry {}", i);
                    break;
                }
                int chunkX = buf.getInt();
                int chunkZ = buf.getInt();
                int regionX = buf.getInt();
                int regionZ = buf.getInt();
                int accessCount = buf.getInt();
                long lastAccess = buf.getLong();
                long chunkBytes = buf.getLong();
                HeatEntry entry = new HeatEntry(serverId, chunkX, chunkZ, dimension,
                        regionX, regionZ, accessCount, lastAccess, chunkBytes);
                entries.put(key(serverId, chunkX, chunkZ, dimension), entry);
            }
            Constants.LOG.info("Hassium: Loaded {} heat index entries from {}", entries.size(), filePath);
        } catch (Exception e) {
            Constants.LOG.warn("Hassium: Failed to load heat.idx, starting empty", e);
            entries.clear();
        }
    }

    private void flush() {
        if (!dirty.compareAndSet(true, false)) {
            return;
        }
        Path tmp = filePath.resolveSibling(FILE_NAME + ".tmp");
        try {
            List<HeatEntry> snapshot = new ArrayList<>(entries.values());
            // header 10 + 每条：utf(serverId)+utf(dimension)+36 定长
            int size = 10;
            for (HeatEntry e : snapshot) {
                size += utfLen(e.serverId) + utfLen(e.dimension) + 36;
            }
            ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
            buf.putInt(MAGIC);
            buf.putShort(VERSION);
            buf.putInt(snapshot.size());
            for (HeatEntry e : snapshot) {
                writeUtf(buf, e.serverId);
                writeUtf(buf, e.dimension);
                buf.putInt(e.chunkX);
                buf.putInt(e.chunkZ);
                buf.putInt(e.regionX);
                buf.putInt(e.regionZ);
                buf.putInt(e.accessCount);
                buf.putLong(e.lastAccessGameTime);
                buf.putLong(e.chunkBytes);
            }
            buf.flip();
            byte[] data = new byte[buf.remaining()];
            buf.get(data);
            Files.write(tmp, data);
            try {
                Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            dirty.set(true);
            Constants.LOG.warn("Hassium: Failed to flush heat.idx", e);
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // ignore
            }
        }
    }

    private static int utfLen(String s) {
        return 2 + s.getBytes(StandardCharsets.UTF_8).length;
    }

    private static void writeUtf(ByteBuffer buf, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        buf.putShort((short) bytes.length);
        buf.put(bytes);
    }

    private static String readUtf(ByteBuffer buf) {
        if (buf.remaining() < 2) {
            return null;
        }
        int len = Short.toUnsignedInt(buf.getShort());
        if (len > 4096 || buf.remaining() < len) {
            return null;
        }
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws IOException {
        closed = true;
        try {
            writerThread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        flush();
    }

    private record HeatEntry(
            String serverId,
            int chunkX,
            int chunkZ,
            String dimension,
            int regionX,
            int regionZ,
            int accessCount,
            long lastAccessGameTime,
            long chunkBytes
    ) {
        CacheEntryInfo toInfo(double hotScore) {
            return new CacheEntryInfo(serverId, chunkX, chunkZ, dimension,
                    regionX, regionZ, accessCount, lastAccessGameTime, chunkBytes, hotScore);
        }
    }

    /**
     * 热度索引条目（无 section 哈希、无 filePath）。
     */
    public record CacheEntryInfo(
            String serverId,
            int chunkX,
            int chunkZ,
            String dimension,
            int regionX,
            int regionZ,
            int accessCount,
            long lastAccessGameTime,
            long chunkBytes,
            double hotScore
    ) {
        public CacheEntryInfo withHotScore(double hotScore) {
            return new CacheEntryInfo(serverId, chunkX, chunkZ, dimension,
                    regionX, regionZ, accessCount, lastAccessGameTime, chunkBytes, hotScore);
        }
    }

    public record CacheStats(int entryCount, long totalSize) {}
}
