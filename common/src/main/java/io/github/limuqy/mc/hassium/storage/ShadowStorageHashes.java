package io.github.limuqy.mc.hassium.storage;

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
 */
public final class ShadowStorageHashes {

    private static final int CONTENT_DIRTY = 1;
    private static final int LIGHT_DIRTY = 2;

    private static final ConcurrentHashMap<Long, Long> HASHES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Integer> FLAGS = new ConcurrentHashMap<>();

    private ShadowStorageHashes() {}

    /** 记录区块 hash（任意线程；注入完成时调用）。 */
    public static void put(ChunkPos pos, long hash) {
        HASHES.put(ChunkPos.asLong(pos.x, pos.z), hash);
    }

    /** 取区块 hash（无记录返回 null；写盘时调用）。 */
    public static Long get(ChunkPos pos) {
        return HASHES.get(ChunkPos.asLong(pos.x, pos.z));
    }

    /**
     * 与远程权威 hash 比对：表命中且相等 → {@code Boolean.TRUE}；表有值但不等 →
     * {@code Boolean.FALSE}（不必现算整柱）；表缺失 → {@code null}（调用方再
     * {@code computeSectionHashes}）。忽略脏位。
     */
    public static Boolean matchesRemote(ChunkPos pos, long remoteHash) {
        Long tableHash = HASHES.get(ChunkPos.asLong(pos.x, pos.z));
        if (tableHash == null) {
            return null;
        }
        return tableHash.longValue() == remoteHash;
    }

    /** 读盘回填（解压出 hash 后调用）。 */
    public static void put(int chunkX, int chunkZ, long hash) {
        HASHES.put(ChunkPos.asLong(chunkX, chunkZ), hash);
    }

    /** 全量区块落地：只标 content 脏，不分配 NBT。 */
    public static void markContentDirty(ChunkPos pos) {
        markContentDirty(ChunkPos.asLong(pos.x, pos.z));
    }

    public static void markContentDirty(long key) {
        FLAGS.merge(key, CONTENT_DIRTY, (a, b) -> a | b);
    }

    /**
     * 全量光收敛：只标 light 脏。R2 contentHash 仍可命中。
     */
    public static void markLightReady(ChunkPos pos) {
        markLightReady(ChunkPos.asLong(pos.x, pos.z));
    }

    public static void markLightReady(long key) {
        FLAGS.merge(key, LIGHT_DIRTY, (a, b) -> a | b);
    }

    /** 欠光/超时：同样只标 light 脏，flush 时按当时 {@code isLightCorrect} 写 isLightOn。 */
    public static void markLightDirty(ChunkPos pos) {
        markLightReady(pos);
    }

    public static boolean isContentDirty(ChunkPos pos) {
        return isContentDirty(ChunkPos.asLong(pos.x, pos.z));
    }

    public static boolean isContentDirty(long key) {
        Integer flags = FLAGS.get(key);
        return flags != null && (flags & CONTENT_DIRTY) != 0;
    }

    public static boolean isLightDirty(ChunkPos pos) {
        return isLightDirty(ChunkPos.asLong(pos.x, pos.z));
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
        return isDirty(ChunkPos.asLong(pos.x, pos.z));
    }

    /**
     * 原子认领脏标记：曾脏则清位并返回 true。flush 期间再标脏会重新入 FLAGS。
     */
    public static boolean claimDirty(long key) {
        Integer flags = FLAGS.remove(key);
        return flags != null && flags != 0;
    }

    public static void restoreDirty(long key, boolean content, boolean light) {
        int flags = (content ? CONTENT_DIRTY : 0) | (light ? LIGHT_DIRTY : 0);
        if (flags != 0) {
            FLAGS.merge(key, flags, (a, b) -> a | b);
        }
    }

    public static Set<Long> dirtyKeys() {
        Set<Long> keys = new HashSet<>();
        for (var e : FLAGS.entrySet()) {
            if (e.getValue() != null && e.getValue() != 0) {
                keys.add(e.getKey());
            }
        }
        return keys;
    }

    /** 删除区块时移除 hash 与脏位（内容失效；防比对误命中）。 */
    public static void remove(ChunkPos pos) {
        long key = ChunkPos.asLong(pos.x, pos.z);
        HASHES.remove(key);
        FLAGS.remove(key);
    }

    /** 清空 hash 表（影子端装配/关停时调用）。 */
    public static void clear() {
        HASHES.clear();
        FLAGS.clear();
    }

    /** Bloom 上报：表内全部柱坐标（park 后注入表已空，仍靠此避免 ROUND1 直推）。 */
    public static Set<Long> hashKeys() {
        return HASHES.keySet();
    }
}
