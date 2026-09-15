package io.github.limuqy.mc.hassium.network.entity;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实体密度索引（热点降帧的输入）：每 tick 统计「chunk → 该 chunk 内发生 {@code sendChanges} 的实体数」。
 * <p>
 * 为什么在闸门侧计数，而不是 mixin {@code ChunkMap.addEntity/removeEntity}：
 * <ul>
 *   <li>{@code ChunkMap} 的实体按 entityId 索引（{@code Int2ObjectMap}），没有 per-chunk 计数；
 *       实体跨 chunk 移动时增量维护会失真，必须再 hook 移动路径。</li>
 *   <li>{@code sendChanges} 只在「section 变化 或 chunk 处于 entity-ticking-range」时被调用，
 *       恰好等于「本 tick 真的可能产生实体包」的实体集合——直接计数即所需密度，
 *       且天然排除远处不活跃实体。</li>
 * </ul>
 * 判定读的是**上一 tick** 的计数（双缓冲翻页），使同一 tick 内的先后顺序不影响结果；
 * 对「热点」这种启发式信号，1 tick 陈旧完全够用。
 * <p>
 * 每个 level 一份独立状态，只被 tick 该 level 的线程读写；外层 Map 用 {@code ConcurrentHashMap}
 * 兜底影子端线程同时 tick 的情形。
 */
public final class EntityDensityIndex {

    private static final Map<Object, State> STATES = new ConcurrentHashMap<>();

    private EntityDensityIndex() {
    }

    /** 记录一个实体本 tick 落在 (chunkX, chunkZ)。 */
    public static void observe(Object level, int chunkX, int chunkZ) {
        if (level == null) {
            return;
        }
        state(level).current.addTo(chunkKey(chunkX, chunkZ), 1);
    }

    /** 上一 tick 落在该 chunk 的实体数；未知 chunk 返回 0。 */
    public static int count(Object level, int chunkX, int chunkZ) {
        if (level == null) {
            return 0;
        }
        State state = STATES.get(level);
        return state == null ? 0 : state.previous.getOrDefault(chunkKey(chunkX, chunkZ), 0);
    }

    /** 翻页：本 tick 的计数写入新缓冲，读缓冲变为上一 tick 的结果。由服务端 tick 起点调用。 */
    public static void rotate() {
        for (State state : STATES.values()) {
            state.rotate();
        }
    }

    /** 清空全部状态（服务器停止/重连时调用，避免 level 对象泄漏）。 */
    public static void clear() {
        STATES.clear();
    }

    private static State state(Object level) {
        State state = STATES.get(level);
        return state != null ? state : STATES.computeIfAbsent(level, ignored -> new State());
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkZ << 32) | (chunkX & 0xFFFFFFFFL);
    }

    private static final class State {
        private Long2IntOpenHashMap previous = new Long2IntOpenHashMap();
        private Long2IntOpenHashMap current = new Long2IntOpenHashMap();

        private void rotate() {
            Long2IntOpenHashMap swap = previous;
            previous = current;
            current = swap;
            current.clear();
        }
    }
}
