package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
#if MC_VER < MC_1_20_5
import net.minecraft.world.level.chunk.ChunkStatus;
#else
import net.minecraft.world.level.chunk.status.ChunkStatus;
#endif
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端 pristine 区块登记（SeedGen 前置，Phase 1）。
 * <p>
 * pristine 定义：**本会话内**生成完成（status FULL）且此后从未被任何来源修改。
 * <p>
 * 语义约定（写死）：
 * <ul>
 *   <li>仅主世界维度登记（SeedGen 只支持主世界；非主世界恒不命中，静默走全量）。</li>
 *   <li>登记发生在区块生成完成 → 首次推送之间（{@link #markIfPristine} 由推送管线调用）；
 *       worldgen 管线内的放置发生在登记之前，不算「修改」。</li>
 *   <li>登记后的一切 {@code ChunkAccess.setBlockState}（MixinChunkAccess 注入）均视为修改，
 *       从登记移除。玩家破坏/放置、插件 setBlock、随机 tick 方块变化全部经此路径。</li>
 *   <li>不持久化：存档重启后全部区块视为非 pristine（走缓存/全量）。</li>
 * </ul>
 * <p>
 * 误判兜底：Phase 3 的 hash 校验闭环——误判只多一次本地生成+回退，不产生错误画面。
 */
public final class PristineRegistry {

    /**
     * 主世界维度 key（纯 Registry 引用，避免 {@code Level.<clinit>} 在无 bootstrap
     * 单测环境失败——PlayerDataStorage A6 实测约定）。
     */
    static final ResourceKey<Level> OVERWORLD_KEY = ResourceKey.create(
            Registries.DIMENSION, ResourceLocationCompat.create("minecraft:overworld"));

    /** 复合键：(维度, chunkPos)。维度隔离防跨维同坐标误命中；非主世界不登记。 */
    private record Key(ResourceKey<Level> dimension, long chunkPos) {}

    /** key(dimension, chunkPos) -> pristine 标志 */
    private static final ConcurrentHashMap<Key, Boolean> PRISTINE = new ConcurrentHashMap<>();

    private PristineRegistry() {
    }

    /**
     * 判定候选（纯函数，供单测）：status FULL + inhabitedTime==0 + 未修改。
     */
    static boolean isPristineCandidate(boolean statusFull, long inhabitedTime, boolean modified) {
        return statusFull && inhabitedTime == 0 && !modified;
    }

    /**
     * 区块首次推送时登记（幂等：已登记/非候选则忽略）。
     * <p>
     * 调用方须在主线程（或持有区块状态一致性的上下文）调用；
     * 不触发加载——用 4 参 getChunk(nonnull=false) 取现有 chunk，缺失/未 FULL 不登记。
     */
    public static void markIfPristine(Level level, ChunkPos pos) {
        ResourceKey<Level> dimension = level.dimension();
        if (!dimension.equals(OVERWORLD_KEY)) {
            return; // 非主世界：SeedGen 仅主世界，不登记（静默走全量）
        }
        ChunkAccess access = level.getChunk(pos.x, pos.z, ChunkStatus.FULL, false);
        if (!(access instanceof LevelChunk chunk)) {
            return;
        }
        long chunkKey = ChunkPos.asLong(pos.x, pos.z);
        if (isPristineCandidate(chunk
#if MC_VER < MC_1_21_1
                .getStatus()
#else
                .getPersistedStatus()
#endif
                .isOrAfter(ChunkStatus.FULL),
                chunk.getInhabitedTime(), PRISTINE.containsKey(new Key(dimension, chunkKey)))) {
            PRISTINE.put(new Key(dimension, chunkKey), Boolean.TRUE);
        }
    }

    /**
     * 该区块当前是否 pristine（非主世界恒 false：静默走全量）。
     */
    public static boolean isPristine(ResourceKey<Level> dimension, ChunkPos pos) {
        if (!dimension.equals(OVERWORLD_KEY)) {
            return false;
        }
        return PRISTINE.containsKey(new Key(dimension, ChunkPos.asLong(pos.x, pos.z)));
    }

    /**
     * 登记表是否为空（MixinChunkAccess 热路径用：空表直接跳过，零开销）。
     */
    public static boolean isEmpty() {
        return PRISTINE.isEmpty();
    }

    /**
     * 区块被修改：移除登记。幂等。
     */
    public static void onBlockModified(ResourceKey<Level> dimension, ChunkPos pos) {
        PRISTINE.remove(new Key(dimension, ChunkPos.asLong(pos.x, pos.z)));
    }

    /**
     * 测试钩子（package-private）：直接置位登记，模拟 markIfPristine 的登记结果。
     */
    static void markPristineForTest(ResourceKey<Level> dimension, ChunkPos pos) {
        PRISTINE.put(new Key(dimension, ChunkPos.asLong(pos.x, pos.z)), Boolean.TRUE);
    }

    /**
     * 清空（服务端 stop / 会话结束）。
     */
    public static void clear() {
        PRISTINE.clear();
    }
}
