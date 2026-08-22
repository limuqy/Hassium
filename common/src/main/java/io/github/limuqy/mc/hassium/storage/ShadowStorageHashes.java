package io.github.limuqy.mc.hassium.storage;

import io.github.limuqy.mc.hassium.utils.DimensionKey;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.ChunkPos;

/**
 * 进程内 HashIndex：contentHash + {@code contentDirty}/{@code lightDirty}。
 * <p>
 * 只回答「本地缓存内容是否仍有效」：写盘读文件头、读盘回填、与远程权威 hash
 * 比对。表命中且相等 → 内容可复用（<b>即使 {@code lightDirty}</b>）；不等或
 * {@link #remove} → 缓存失效。{@code lightDirty} 只表示光照需本地续算/重写槽，
 * 不得当成 content miss。
 * <p>
 * {@link #markContentDirty} / {@link #markLightReady} 只改脏位，不拷贝区块。
 * <p>
 * <b>键布局（多维度兼容）</b>：HASHES/FLAGS 两表键统一为
 * {@link DimensionKey#key(String, int, int)} 复合键（高 12 位维度 id + 低 52 位
 * 对称坐标位域）。旧的无维度签名（裸 {@code ChunkPos.asLong} 键）保留并委托到
 * {@link DimensionKey#OVERWORLD}——主世界键值语义与现网一致；过渡期调用方由
 * 后续任务迁移至带 dimension 的重载。跨维同坐标 (x,z) 因维度段不同而互不碰撞。
 */
public final class ShadowStorageHashes {

    private static final int CONTENT_DIRTY = 1;
    private static final int LIGHT_DIRTY = 2;

    private static final ConcurrentHashMap<Long, Long> HASHES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Integer> FLAGS = new ConcurrentHashMap<>();

    private ShadowStorageHashes() {}

    /** 记录区块 hash（任意线程；注入完成时调用）。 */
    public static void put(ChunkPos pos, long hash) {
        put(DimensionKey.OVERWORLD, pos, hash);
    }

    /** 记录指定维度区块 hash（任意线程；注入完成时调用）。 */
    public static void put(String dimension, ChunkPos pos, long hash) {
        HASHES.put(DimensionKey.key(dimension, pos.x, pos.z), hash);
    }

    /** 取区块 hash（无记录返回 null；写盘时调用）。 */
    public static Long get(ChunkPos pos) {
        return get(DimensionKey.OVERWORLD, pos);
    }

    /** 取指定维度区块 hash（无记录返回 null；写盘时调用）。 */
    public static Long get(String dimension, ChunkPos pos) {
        return HASHES.get(DimensionKey.key(dimension, pos.x, pos.z));
    }

    /**
     * 与远程权威 hash 比对：表命中且相等 → {@code Boolean.TRUE}；表有值但不等 →
     * {@code Boolean.FALSE}（不必现算整柱）；表缺失 → {@code null}（调用方再
     * {@code computeSectionHashes}）。忽略脏位。
     */
    public static Boolean matchesRemote(ChunkPos pos, long remoteHash) {
        return matchesRemote(DimensionKey.OVERWORLD, pos, remoteHash);
    }

    /** 指定维度的远程权威 hash 比对，语义同 {@link #matchesRemote(ChunkPos, long)}。 */
    public static Boolean matchesRemote(String dimension, ChunkPos pos, long remoteHash) {
        Long tableHash = HASHES.get(DimensionKey.key(dimension, pos.x, pos.z));
        if (tableHash == null) {
            return null;
        }
        return tableHash.longValue() == remoteHash;
    }

    /** 读盘回填（解压出 hash 后调用）。 */
    public static void put(int chunkX, int chunkZ, long hash) {
        put(DimensionKey.OVERWORLD, chunkX, chunkZ, hash);
    }

    /** 读盘回填（指定维度；解压出 hash 后调用）。 */
    public static void put(String dimension, int chunkX, int chunkZ, long hash) {
        HASHES.put(DimensionKey.key(dimension, chunkX, chunkZ), hash);
    }

    /** 全量区块落地：只标 content 脏，不分配 NBT。 */
    public static void markContentDirty(ChunkPos pos) {
        markContentDirty(DimensionKey.OVERWORLD, pos);
    }

    /** 指定维度全量区块落地：只标 content 脏，不分配 NBT。 */
    public static void markContentDirty(String dimension, ChunkPos pos) {
        markContentDirty(DimensionKey.key(dimension, pos.x, pos.z));
    }

    public static void markContentDirty(long key) {
        FLAGS.merge(key, CONTENT_DIRTY, (a, b) -> a | b);
    }

    /**
     * 全量光收敛：只标 light 脏。R2 contentHash 仍可命中。
     */
    public static void markLightReady(ChunkPos pos) {
        markLightReady(DimensionKey.OVERWORLD, pos);
    }

    /** 指定维度全量光收敛：只标 light 脏。 */
    public static void markLightReady(String dimension, ChunkPos pos) {
        markLightReady(DimensionKey.key(dimension, pos.x, pos.z));
    }

    public static void markLightReady(long key) {
        FLAGS.merge(key, LIGHT_DIRTY, (a, b) -> a | b);
    }

    /** 欠光/超时：同样只标 light 脏，flush 时按当时 {@code isLightCorrect} 写 isLightOn。 */
    public static void markLightDirty(ChunkPos pos) {
        markLightReady(pos);
    }

    /** 指定维度欠光/超时：同样只标 light 脏。 */
    public static void markLightDirty(String dimension, ChunkPos pos) {
        markLightReady(dimension, pos);
    }

    public static boolean isContentDirty(ChunkPos pos) {
        return isContentDirty(DimensionKey.OVERWORLD, pos);
    }

    /** 指定维度 content 脏位查询。 */
    public static boolean isContentDirty(String dimension, ChunkPos pos) {
        return isContentDirty(DimensionKey.key(dimension, pos.x, pos.z));
    }

    public static boolean isContentDirty(long key) {
        Integer flags = FLAGS.get(key);
        return flags != null && (flags & CONTENT_DIRTY) != 0;
    }

    public static boolean isLightDirty(ChunkPos pos) {
        return isLightDirty(DimensionKey.OVERWORLD, pos);
    }

    /** 指定维度 light 脏位查询。 */
    public static boolean isLightDirty(String dimension, ChunkPos pos) {
        return isLightDirty(DimensionKey.key(dimension, pos.x, pos.z));
    }

    public static boolean isLightDirty(long key) {
        Integer flags = FLAGS.get(key);
        return flags != null && (flags & LIGHT_DIRTY) != 0;
    }

    public static boolean isDirty(long key) {
        Integer flags = FLAGS.get(key);
        return flags != null && flags != 0;
    }

    public static boolean isDirty(ChunkPos pos) {
        return isDirty(DimensionKey.OVERWORLD, pos);
    }

    /** 指定维度任意脏位查询。 */
    public static boolean isDirty(String dimension, ChunkPos pos) {
        return isDirty(DimensionKey.key(dimension, pos.x, pos.z));
    }

    /**
     * 原子认领脏标记：曾脏则清位并返回 true。flush 期间再标脏会重新入 FLAGS。
     *
     * @param key {@link DimensionKey} 复合键
     */
    public static boolean claimDirty(long key) {
        Integer flags = FLAGS.remove(key);
        return flags != null && flags != 0;
    }

    /**
     * 恢复脏标记（flush 放弃路径）。
     *
     * @param key {@link DimensionKey} 复合键
     */
    public static void restoreDirty(long key, boolean content, boolean light) {
        int flags = (content ? CONTENT_DIRTY : 0) | (light ? LIGHT_DIRTY : 0);
        if (flags != 0) {
            FLAGS.merge(key, flags, (a, b) -> a | b);
        }
    }

    /**
     * 全部脏键（含所有维度）。
     *
     * <b>布局变化</b>：返回 {@link DimensionKey} 复合键（高 12 位维度 id + 低 52 位
     * 坐标），不再是裸 {@code ChunkPos.asLong}；用
     * {@link DimensionKey#chunkXOf(long)}/{@link DimensionKey#chunkZOf(long)}
     * 反解坐标、{@link DimensionKey#dimensionOf(long)} 反解维度。
     */
    public static Set<Long> dirtyKeys() {
        Set<Long> keys = new HashSet<>();
        for (var e : FLAGS.entrySet()) {
            if (e.getValue() != null && e.getValue() != 0) {
                keys.add(e.getKey());
            }
        }
        return keys;
    }

    /** 指定维度的脏键子集（复合键布局，见 {@link #dirtyKeys()}）。 */
    public static Set<Long> dirtyKeys(String dimension) {
        Set<Long> keys = new HashSet<>();
        for (var e : FLAGS.entrySet()) {
            if (e.getValue() != null && e.getValue() != 0
                    && dimension.equals(DimensionKey.dimensionOf(e.getKey()))) {
                keys.add(e.getKey());
            }
        }
        return keys;
    }

    /** 删除区块时移除 hash 与脏位（内容失效；防比对误命中）。 */
    public static void remove(ChunkPos pos) {
        remove(DimensionKey.OVERWORLD, pos);
    }

    /** 删除指定维度区块的 hash 与脏位。 */
    public static void remove(String dimension, ChunkPos pos) {
        long key = DimensionKey.key(dimension, pos.x, pos.z);
        HASHES.remove(key);
        FLAGS.remove(key);
    }

    /** 清空 hash 表（影子端装配/关停时调用）。 */
    public static void clear() {
        HASHES.clear();
        FLAGS.clear();
    }

    /**
     * Bloom 上报：表内全部柱坐标（park 后注入表已空，仍靠此避免 ROUND1 直推）。
     *
     * <b>布局变化</b>：返回 {@link DimensionKey} 复合键，不再是裸
     * {@code ChunkPos.asLong}；按维度过滤请用 {@link #hashKeys(String)}，
     * 反解坐标用 {@link DimensionKey#chunkXOf(long)}/{@link DimensionKey#chunkZOf(long)}。
     */
    public static Set<Long> hashKeys() {
        return HASHES.keySet();
    }

    /** Bloom 上报：指定维度的全部柱键（复合键布局，见 {@link #hashKeys()}）。 */
    public static Set<Long> hashKeys(String dimension) {
        Set<Long> keys = new HashSet<>();
        for (Long key : HASHES.keySet()) {
            if (dimension.equals(DimensionKey.dimensionOf(key))) {
                keys.add(key);
            }
        }
        return keys;
    }
}
