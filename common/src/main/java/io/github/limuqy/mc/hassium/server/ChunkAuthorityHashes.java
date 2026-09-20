package io.github.limuqy.mc.hassium.server;

import io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil;
import io.github.limuqy.mc.hassium.utils.DimensionKey;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.level.ChunkPos;

/**
 * 服务端 per-chunk 权威内容 hash 缓存（**逐段** hash 数组；柱级 hash 现组合）。
 * <p>
 * 与历史方案的差别：老方案是 <b>per-player</b> hash 表（随玩家会话、按玩家重复存）；
 * 这里是<b>共享 per-chunk 缓存</b>——跨玩家复用，失效点收敛到「方块变更」一处。
 * <p>
 * <b>为什么存逐段数组而不是柱级 hash</b>：两条消费路径都需要 <b>逐段</b> hash——
 * {@code ServerChunkPushManager.classifyPull} 用 `sectionHashList` 做 `sectionsMatch`
 * 判据并**随每个响应回带**（UNCHANGED/DELTA/FULL 都带），{@code ChunkAuthorityNotifier}
 * 要柱级 hash 但同样是从逐段数组组合而来。贵的那一步是
 * {@link ChunkContentHashUtil#computeSectionHashes} 的**逐位置扫描**（24 段 × 16³ =
 * 98_304 次 {@code getBlockState}+{@code Block.getId}）；只缓存柱级 hash 会把它省不掉
 * （仍要算逐段数组），缓存逐段数组才真正省下来。柱级 hash 由
 * {@link ChunkContentHashUtil#combineSectionHashesFromArray} 现组合（24 次写哈希流）。
 * <p>
 * 使用约定（调用方 = {@link ChunkAuthorityNotifier} 与 {@code ServerChunkPushManager}）：
 * <ul>
 *   <li>{@link #getSections} 命中 → 直接复用；未命中 → 现算 {@link #putSections}。</li>
 *   <li>方块变更路径调 {@link #invalidate}（内容已变，旧 hash 必须失效，否则客户端会误判
 *       「零请求命中」并保留旧内容——**静默内容错误**）。钩子见 {@code MixinLevelChunk}：
 *       {@code LevelChunk#setBlockState} 的 RETURN，vanilla 全部方块变更的收口。</li>
 *   <li>区块卸载<b>不</b>失效：内容 hash 与加载态无关，卸载后重载内容相同则仍可用。</li>
 *   <li>会话边界（服务端关停）调 {@link #clear}：键是 {@code (维度, x, z)}，不跨世界复用。</li>
 * </ul>
 * <p>
 * <b>失效覆盖的不变量</b>：写入侧只由**真服**调用点填充，失效钩子也只在真服生效
 * （{@code MixinLevelChunk} 显式跳过影子端）。{@link #putSections} 里再兜一层
 * 影子端拒写，把这条不变量从「假设」变成「强制」——代价是一次 volatile 读，
 * 换来的是「影子端绝不会拿到一份永不被失效的条目」。
 * <p>
 * 容量上限 + LRU（访问序）：超出上限淘汰最久未使用柱，防服务端内存无界。
 * 每条约 208 B（24 段 long[] + 表开销），{@link #MAX_ENTRIES} 满时约 14 MB。
 */
public final class ChunkAuthorityHashes {

    /** 柱数上限（每柱 24 段 long = 192 B payload + 表开销）。按 VD20 多玩家窗口量级留足余量。 */
    private static final int MAX_ENTRIES = 65_536;

    /** key = {@link DimensionKey} 复合键；value = 逐段 hash 数组（长度 = max(非空段索引)+1，空段为 0）。 */
    private static final Map<Long, long[]> CACHE = new LinkedHashMap<>(1024, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, long[]> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private static final AtomicLong HITS = new AtomicLong();
    private static final AtomicLong MISSES = new AtomicLong();
    private static final AtomicLong PUTS = new AtomicLong();
    private static final AtomicLong INVALIDATIONS = new AtomicLong();
    private static final AtomicLong SHADOW_REJECTS = new AtomicLong();

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

    /**
     * 取该柱的**逐段** hash 数组（返回副本，调用方可自由持有）。
     * <p>
     * {@code null} = 无缓存（需现算）；长度 0 = 该柱全空气（合法条目，柱级 hash 为 1）。
     */
    public static long[] getSections(String dimension, ChunkPos pos) {
        if (dimension == null || pos == null) {
            return null;
        }
        long[] stored = getByKey(DimensionKey.key(dimension, pos.x, pos.z));
        if (stored == null) {
            MISSES.incrementAndGet();
            return null;
        }
        HITS.incrementAndGet();
        return stored.clone();
    }

    /** 取该柱的柱级 hash（由逐段数组现组合）；无缓存返回 {@code null}。 */
    public static Long get(String dimension, ChunkPos pos) {
        long[] sections = getSections(dimension, pos);
        return sections == null ? null : ChunkContentHashUtil.combineSectionHashesFromArray(sections);
    }

    /**
     * 取指定维度/坐标的柱级 hash（无缓存返回 {@code null}，不计命中/未命中统计）。
     */
    public static Long peek(String dimension, int chunkX, int chunkZ) {
        if (dimension == null) {
            return null;
        }
        long[] stored;
        synchronized (CACHE) {
            stored = CACHE.get(DimensionKey.key(dimension, chunkX, chunkZ));
        }
        return stored == null ? null : ChunkContentHashUtil.combineSectionHashesFromArray(stored);
    }

    /**
     * 写入逐段 hash 数组（内部拷贝）。
     * <p>
     * {@code sectionHashes == null} 视为未知，不入表（长度 0 = 全空气，是合法值，照收）。
     * 影子端拒写（见类注释的不变量），计入 {@link #shadowRejectCount()}。
     */
    public static void putSections(String dimension, ChunkPos pos, long[] sectionHashes) {
        if (dimension == null || pos == null || sectionHashes == null) {
            return;
        }
        if (io.github.limuqy.mc.hassium.server.RuntimeServerContext.isShadowServerContext()) {
            SHADOW_REJECTS.incrementAndGet();
            return;
        }
        putByKey(DimensionKey.key(dimension, pos.x, pos.z), sectionHashes.clone());
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

    /** 会话边界清理（服务端关停 / 世界卸载）。键是 {@code (维度, x, z)}，不得跨世界复用。 */
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

    /** 影子端拒写次数（>0 说明不变量被违反，值得报警）。 */
    public static long shadowRejectCount() {
        return SHADOW_REJECTS.get();
    }

    /** 单行统计（服务端关停日志用）。 */
    public static String statsLine() {
        return "size=" + size() + " hit=" + hitCount() + " miss=" + missCount()
                + " put=" + putCount() + " invalidate=" + invalidationCount()
                + " shadowReject=" + shadowRejectCount();
    }

    /** 观测复位（冒烟/探针用）。 */
    public static void resetStats() {
        HITS.set(0);
        MISSES.set(0);
        PUTS.set(0);
        INVALIDATIONS.set(0);
        SHADOW_REJECTS.set(0);
    }

    private static long[] getByKey(long key) {
        synchronized (CACHE) {
            return CACHE.get(key);
        }
    }

    private static void putByKey(long key, long[] sectionHashes) {
        synchronized (CACHE) {
            CACHE.put(key, sectionHashes);
            nonEmpty = true;
        }
        PUTS.incrementAndGet();
    }
}
