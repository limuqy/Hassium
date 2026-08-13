package io.github.limuqy.mc.hassium.storage;

import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.ChunkPos;

/**
 * 进程内 chunkHash 桥：影子端注入侧 ↔ 存储层（{@code MixinRegionFile}）。
 * <p>
 * 影子端注入区块时（{@code ShadowSeedServer.injectChunk}）计算 contentHash 写入本表；
 * 存储写盘（type 126 payload 头带 hash）时读取；读盘时回填（重连后从磁盘恢复
 * hash 表，供 R2 与远程权威 hash 比对）。专用服务器后续若要在保存路径维护
 * chunkHash，同样可经本表桥接。
 * <p>
 * 光照标脏（{@link #markLightDirty}）：保存时若全局光照未收敛（converge 超时，
 * 落盘的是欠光数据），标记脏；R2 读盘 hash 命中后查标脏——干净 → 跳过光照重算
 * 直接打包（存档即收敛光），脏 → 必须走注入清光重算链（不能直接打包欠光数据）。
 * 默认未标记 = 干净（保存时收敛是常态，超时是少数）。
 * <p>
 * 生命周期：影子端装配时清空，关停时清空（进程内单会话有效）。
 */
public final class ShadowStorageHashes {

    private static final ConcurrentHashMap<Long, Long> HASHES = new ConcurrentHashMap<>();
    /** 光照标脏表：key = ChunkPos.asLong，true = 落盘光未收敛（欠光）。 */
    private static final ConcurrentHashMap<Long, Boolean> LIGHT_DIRTY = new ConcurrentHashMap<>();

    private ShadowStorageHashes() {}

    /** 记录区块 hash（任意线程；注入完成时调用）。 */
    public static void put(ChunkPos pos, long hash) {
        HASHES.put(ChunkPos.asLong(pos.x, pos.z), hash);
    }

    /** 取区块 hash（无记录返回 null；写盘时调用）。 */
    public static Long get(ChunkPos pos) {
        return HASHES.get(ChunkPos.asLong(pos.x, pos.z));
    }

    /** 读盘回填（解压出 hash 后调用）。 */
    public static void put(int chunkX, int chunkZ, long hash) {
        HASHES.put(ChunkPos.asLong(chunkX, chunkZ), hash);
    }

    /** 删除区块时移除 hash 记录（影子端缓存清理调用；防清理后内存比对误命中）。 */
    public static void remove(ChunkPos pos) {
        HASHES.remove(ChunkPos.asLong(pos.x, pos.z));
    }

    /** 标记区块落盘光照状态（pushReady 时按收敛状态调用；默认未标记 = 干净）。 */
    public static void markLightDirty(ChunkPos pos, boolean dirty) {
        long key = ChunkPos.asLong(pos.x, pos.z);
        if (dirty) {
            LIGHT_DIRTY.put(key, Boolean.TRUE);
        } else {
            LIGHT_DIRTY.remove(key);
        }
    }

    /** 落盘光照是否标脏（未标记 = 干净，可跳过光照重算直接打包）。 */
    public static boolean isLightDirty(ChunkPos pos) {
        return Boolean.TRUE.equals(LIGHT_DIRTY.get(ChunkPos.asLong(pos.x, pos.z)));
    }

    /** 光照标脏表是否非空（帧尾收敛检查的零开销短路门；无标脏不查引擎）。 */
    public static boolean hasLightDirty() {
        return !LIGHT_DIRTY.isEmpty();
    }

    /** 清空全部光照标脏。仅全局光照确认收敛后调用（{@code ShadowSeedServer.isLightConverged()}
     * 排空 = 所有标脏柱光均已收敛为干净光，R2 读盘可跳过重算直接复用）；
     * 未收敛时调用会误把欠光数据当干净光，严禁。 */
    public static void clearLightDirty() {
        LIGHT_DIRTY.clear();
    }

    /** 清空 hash 表（影子端装配/关停时调用）。光照标脏表不随 hash 清空：
     * 欠光标脏必须跨影子端关停保留（进程内），R2 读盘命中继承 R1 的欠光状态；
     * 装配新会话时默认干净（表从零起），无残留串扰。 */
    public static void clear() {
        HASHES.clear();
    }
}
