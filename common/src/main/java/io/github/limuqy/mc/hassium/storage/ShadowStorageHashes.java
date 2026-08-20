package io.github.limuqy.mc.hassium.storage;

import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.ChunkPos;

/**
 * 进程内 contentHash 桥：影子端注入侧 ↔ 存储层（{@code MixinRegionFile}）。
 * <p>
 * 只回答「本地缓存内容是否仍有效」：写盘读文件头、读盘回填、与远程权威 hash
 * 比对。表命中且相等 → 内容可复用；不等或 {@link #remove} → 缓存失效，走直推/增量。
 * 读盘柱已由 MixinRegionFile 写入本表，{@code injectLoadedChunk} 不得再
 * {@code computeSectionHashes}。
 * <p>
 * 光照不进本表。影子端与原版相同：{@code ChunkAccess.isLightCorrect()}（NBT
 * {@code isLightOn}）决定 LIGHT 是否续算；引擎队列在同一会话内继续传播。
 * <p>
 * 生命周期：影子端装配/关停时清空（进程内单会话；读盘会再回填）。
 */
public final class ShadowStorageHashes {

    private static final ConcurrentHashMap<Long, Long> HASHES = new ConcurrentHashMap<>();

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
     * {@code computeSectionHashes}）。
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

    /** 删除区块时移除 hash 记录（影子端缓存清理 / 内容失效；防比对误命中）。 */
    public static void remove(ChunkPos pos) {
        HASHES.remove(ChunkPos.asLong(pos.x, pos.z));
    }

    /** 清空 hash 表（影子端装配/关停时调用）。 */
    public static void clear() {
        HASHES.clear();
    }
}
