package io.github.limuqy.mc.hassium.server;

import io.github.limuqy.mc.hassium.utils.DimensionKey;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.level.ChunkPos;

/**
 * 服务端 per-chunk 权威内容 hash 缓存（权威边沿 enter 通知用）。
 * <p>
 * 与历史方案的差别：老方案是 <b>per-player</b> hash 表（随玩家会话、按玩家重复存）；
 * 这里是<b>共享 per-chunk 缓存</b>——跨玩家复用，失效点收敛到「方块变更」一处。
 * <p>
 * 使用约定（调用方 = {@link ChunkAuthorityNotifier} 与 {@code ServerChunkPushManager}）：
 * <ul>
 *   <li>通知组包时 {@link #get} 命中 → 直接附带；未命中 → 在预算内现算后 {@link #put}（best-effort）。</li>
 *   <li>方块变更路径调 {@link #invalidate}（内容已变，旧 hash 必须失效，否则客户端会误判「零请求命中」）。</li>
 *   <li>区块卸载<b>不</b>失效：内容 hash 与加载态无关，卸载后重载内容相同则仍可用。</li>
 * </ul>
 * 容量上限 + LRU（访问序）：超出上限淘汰最久未使用柱，防服务端内存无界。
 */
public final class ChunkAuthorityHashes {

    /** 柱数上限（每柱 8 字节 payload + 表开销）。按 VD20 多玩家窗口量级留足余量。 */
    private static final int MAX_ENTRIES = 65_536;

    /** key = {@link DimensionKey} 复合键；accessOrder=true 实现 LRU。 */
    private static final Map<Long, Long> CACHE = new LinkedHashMap<>(1024, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Long> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private static final AtomicLong HITS = new AtomicLong();
    private static final AtomicLong MISSES = new AtomicLong();
    private static final AtomicLong PUTS = new AtomicLong();
    private static final AtomicLong INVALIDATIONS = new AtomicLong();

    /**
     * 表非空快照。给方块变更这类**热路径**一个免锁短路：权威缓存通常为空
     * （未协商 / 影子端 / 单人本地），此时每次 {@code setBlockState} 都去取维度 id、
     * 抢 {@link #CACHE} 的锁是纯浪费。写入侧维护，读侧只读。
     */
    private static volatile boolean nonEmpty;

    private ChunkAuthorityHashes() {
    }

    /** 表内是否有条目（免锁）。热路径据此决定是否走 {@link #invalidate}。 */
    public static boolean hasEntries() {
        return nonEmpty;
    }

    /** 取权威 hash；无缓存返回 {@code null}。 */
    public static Long get(String dimension, ChunkPos pos) {
        if (dimension == null || pos == null) {
            return null;
        }
        Long hash = getByKey(DimensionKey.key(dimension, pos.x, pos.z));
        if (hash == null) {
            MISSES.incrementAndGet();
        } else {
            HITS.incrementAndGet();
        }
        return hash;
    }

    /** 取指定维度/坐标的权威 hash（无缓存返回 {@code null}，不计命中/未命中统计）。 */
    public static Long peek(String dimension, int chunkX, int chunkZ) {
        if (dimension == null) {
            return null;
        }
        synchronized (CACHE) {
            return CACHE.get(DimensionKey.key(dimension, chunkX, chunkZ));
        }
    }

    /** 写入权威 hash（现算完成后调用）。{@code hash == 0} 视为未知，不入表。 */
    public static void put(String dimension, ChunkPos pos, long hash) {
        if (dimension == null || pos == null || hash == 0L) {
            return;
        }
        putByKey(DimensionKey.key(dimension, pos.x, pos.z), hash);
    }

    /** 方块变更失效（旧的权威 hash 已不再代表内容）。 */
    public static void invalidate(String dimension, ChunkPos pos) {
        if (dimension == null || pos == null) {
            return;
        }
        invalidate(dimension, pos.x, pos.z);
    }

    /** 方块变更失效（指定维度/坐标）。 */
    public static void invalidate(String dimension, int chunkX, int chunkZ) {
        if (dimension == null) {
            return;
        }
        boolean removed;
        synchronized (CACHE) {
            removed = CACHE.remove(DimensionKey.key(dimension, chunkX, chunkZ)) != null;
            if (removed) {
                nonEmpty = !CACHE.isEmpty();
            }
        }
        if (removed) {
            INVALIDATIONS.incrementAndGet();
        }
    }

    /** 会话边界清理（服务端关停 / 世界卸载）。 */
    public static void clear() {
        synchronized (CACHE) {
            CACHE.clear();
            nonEmpty = false;
        }
    }

    public static int size() {
        synchronized (CACHE) {
            return CACHE.size();
        }
    }

    public static long hitCount() {
        return HITS.get();
    }

    public static long missCount() {
        return MISSES.get();
    }

    public static long putCount() {
        return PUTS.get();
    }

    public static long invalidationCount() {
        return INVALIDATIONS.get();
    }

    /** 观测复位（冒烟/探针用）。 */
    public static void resetStats() {
        HITS.set(0);
        MISSES.set(0);
        PUTS.set(0);
        INVALIDATIONS.set(0);
    }

    private static Long getByKey(long key) {
        synchronized (CACHE) {
            return CACHE.get(key);
        }
    }

    private static void putByKey(long key, long hash) {
        synchronized (CACHE) {
            CACHE.put(key, hash);
            nonEmpty = true;
        }
        PUTS.incrementAndGet();
    }
}
