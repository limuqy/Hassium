package io.github.limuqy.mc.hassium.storage;

import io.github.limuqy.mc.hassium.utils.DimensionKey;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.ChunkPos;

/**
 * 进程内 HashIndex：contentHash + {@code contentDirty}/{@code lightDirty}/
 * {@code mutation}/{@code lightReady}/{@code persisted}。
 * <p>
 * 只回答「本地缓存内容是否仍有效」：写盘读文件头、读盘回填、与远程权威 hash
 * 比对。表命中且相等 → 内容可复用（<b>即使 {@code lightDirty}</b>）；不等或
 * {@link #remove} → 缓存失效。{@code lightDirty} 只表示光照需本地续算/重写槽，
 * 不得当成 content miss。
 * <p>
 * 落盘职责拆分：
 * <ul>
 *   <li>首次注入（未 {@code persisted}）→ 等光照收敛 {@link #markLightReady} 后立即入存储队列</li>
 *   <li>已落盘后再改（BE / 方块 / 增量）→ {@code mutation}，由定时刷新与退出 flush 刷盘</li>
 *   <li>{@link #markLightDirty} 只标欠光，不入队</li>
 * </ul>
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
    private static final int MUTATION = 4;
    private static final int PERSISTED = 8;
    private static final int LIGHT_READY = 16;
    private static final int DIRTY_MASK = CONTENT_DIRTY | LIGHT_DIRTY | MUTATION | LIGHT_READY;

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

    /** 全量区块落地：只标 content 脏，不分配 NBT。已落盘柱同时标 mutation。 */
    public static void markContentDirty(ChunkPos pos) {
        markContentDirty(DimensionKey.OVERWORLD, pos);
    }

    /** 指定维度全量区块落地：只标 content 脏，不分配 NBT。 */
    public static void markContentDirty(String dimension, ChunkPos pos) {
        markContentDirty(DimensionKey.key(dimension, pos.x, pos.z));
    }

    public static void markContentDirty(long key) {
        FLAGS.merge(key, CONTENT_DIRTY, (a, b) -> {
            int next = a | CONTENT_DIRTY;
            if ((a & PERSISTED) != 0) {
                next |= MUTATION;
            }
            return next;
        });
    }

    /**
     * 全量光收敛：标 light 脏 + lightReady。调用方应立即入存储队列。
     * R2 contentHash 仍可命中。
     */
    public static void markLightReady(ChunkPos pos) {
        markLightReady(DimensionKey.OVERWORLD, pos);
    }

    /** 指定维度全量光收敛：标 light 脏 + lightReady。 */
    public static void markLightReady(String dimension, ChunkPos pos) {
        markLightReady(DimensionKey.key(dimension, pos.x, pos.z));
    }

    public static void markLightReady(long key) {
        FLAGS.merge(key, LIGHT_DIRTY | LIGHT_READY, (a, b) -> a | b);
    }

    /** 欠光/超时：只标 light 脏，清 lightReady，不入存储队列。 */
    public static void markLightDirty(ChunkPos pos) {
        markLightDirty(DimensionKey.OVERWORLD, pos);
    }

    /** 指定维度欠光/超时：只标 light 脏，清 lightReady。 */
    public static void markLightDirty(String dimension, ChunkPos pos) {
        markLightDirty(DimensionKey.key(dimension, pos.x, pos.z));
    }

    public static void markLightDirty(long key) {
        FLAGS.merge(key, LIGHT_DIRTY, (a, b) -> (a | LIGHT_DIRTY) & ~LIGHT_READY);
    }

    /** 读盘命中 / 写盘成功：该柱已有磁盘副本。后续 content 脏视为中途变更。 */
    public static void markPersisted(long key) {
        FLAGS.merge(key, PERSISTED, (a, b) -> a | PERSISTED);
    }

    public static void markPersisted(String dimension, ChunkPos pos) {
        markPersisted(DimensionKey.key(dimension, pos.x, pos.z));
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

    public static boolean isMutation(long key) {
        Integer flags = FLAGS.get(key);
        return flags != null && (flags & MUTATION) != 0;
    }

    public static boolean isLightReady(long key) {
        Integer flags = FLAGS.get(key);
        return flags != null && (flags & LIGHT_READY) != 0;
    }

    public static boolean isPersisted(long key) {
        Integer flags = FLAGS.get(key);
        return flags != null && (flags & PERSISTED) != 0;
    }

    public static boolean isDirty(long key) {
        Integer flags = FLAGS.get(key);
        return flags != null && (flags & DIRTY_MASK) != 0;
    }

    public static boolean isDirty(ChunkPos pos) {
        return isDirty(DimensionKey.OVERWORLD, pos);
    }

    /** 指定维度任意脏位查询。 */
    public static boolean isDirty(String dimension, ChunkPos pos) {
        return isDirty(DimensionKey.key(dimension, pos.x, pos.z));
    }

    /**
     * 原子认领脏标记：曾脏则清脏位（保留 persisted）并返回 true。
     * flush 期间再标脏会重新入 FLAGS。
     *
     * @param key {@link DimensionKey} 复合键
     */
    public static boolean claimDirty(long key) {
        final boolean[] wasDirty = {false};
        FLAGS.compute(key, (k, v) -> {
            if (v == null) {
                return null;
            }
            if ((v & DIRTY_MASK) == 0) {
                return v;
            }
            wasDirty[0] = true;
            int keep = v & PERSISTED;
            return keep == 0 ? null : keep;
        });
        return wasDirty[0];
    }

    /**
     * 恢复脏标记（flush 放弃路径）。
     *
     * @param key {@link DimensionKey} 复合键
     */
    public static void restoreDirty(long key, boolean content, boolean light) {
        restoreDirty(key, content, light, false, false);
    }

    public static void restoreDirty(long key, boolean content, boolean light,
                                    boolean mutation, boolean lightReady) {
        int flags = (content ? CONTENT_DIRTY : 0)
                | (light ? LIGHT_DIRTY : 0)
                | (mutation ? MUTATION : 0)
                | (lightReady ? LIGHT_READY : 0);
        if (flags != 0) {
            FLAGS.merge(key, flags, (a, b) -> a | b);
        }
    }

    /**
     * 全部脏键（含所有维度）。不含仅 persisted、无脏位的柱。
     *
     * <b>布局变化</b>：返回 {@link DimensionKey} 复合键（高 12 位维度 id + 低 52 位
     * 坐标），不再是裸 {@code ChunkPos.asLong}；用
     * {@link DimensionKey#chunkXOf(long)}/{@link DimensionKey#chunkZOf(long)}
     * 反解坐标、{@link DimensionKey#dimensionOf(long)} 反解维度。
     */
    public static Set<Long> dirtyKeys() {
        return keysWith(DIRTY_MASK, null);
    }

    /** 指定维度的脏键子集（复合键布局，见 {@link #dirtyKeys()}）。 */
    public static Set<Long> dirtyKeys(String dimension) {
        return keysWith(DIRTY_MASK, dimension);
    }

    /** 指定维度的中途变更键（已落盘后再改）。 */
    public static Set<Long> mutationKeys(String dimension) {
        return keysWith(MUTATION, dimension);
    }

    /** 指定维度的光收敛待入队键。 */
    public static Set<Long> lightReadyKeys(String dimension) {
        return keysWith(LIGHT_READY, dimension);
    }

    private static Set<Long> keysWith(int mask, String dimension) {
        Set<Long> keys = new HashSet<>();
        for (var e : FLAGS.entrySet()) {
            if (e.getValue() == null || (e.getValue() & mask) == 0) {
                continue;
            }
            if (dimension != null && !dimension.equals(DimensionKey.dimensionOf(e.getKey()))) {
                continue;
            }
            keys.add(e.getKey());
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
