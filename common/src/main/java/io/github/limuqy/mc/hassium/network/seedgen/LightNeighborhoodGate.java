package io.github.limuqy.mc.hassium.network.seedgen;

import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DimensionKey;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.ChunkPos;

/**
 * 光照齐套门控：对齐原版 {@code ChunkStatus.LIGHT} 的 3×3 邻域依赖语义。
 * <p>
 * 原版 {@code ChunkStatus.LIGHT} 的 {@code range=1 + hasLoadDependencies=true} 要求
 * 切比雪夫距离 ≤1 的邻柱达到 {@code INITIALIZE_LIGHT} 后才跑 LIGHT 任务。本类在影子端
 * 复刻同一语义：注入后不立即算光，等 3×3 邻域齐套后再提交原版
 * {@code initializeLight + lightChunk} 一次算对，消除「缺邻当基岩挡光」的屋檐/洞口残差。
 * <p>
 * <b>齐套判定</b>（对每个 3×3 邻柱）：
 * <ul>
 *   <li>已注入 {@code injectedChunks}（真实或占位）→ 就绪</li>
 *   <li>未注入 → 等待超时（{@link #NEIGHBORHOOD_TIMEOUT_MS}）→ 注入空气空壳占位</li>
 * </ul>
 * 不按权威集合提前占位——权威声明可能尚未到达，视距内邻柱会被误判为权威外。
 * <p>
 * <b>空气空壳语义</b>：全空气 LevelChunk，天光从上方灌入、水平透过（等价原版
 * {@code NEG_INF} 哨兵）——地表屋檐正确，洞穴边缘偏亮（原版视距边缘同样不准，可接受）。
 * <p>
 * 线程模型：{@link #tryPromote} 由 consumeLoop / drainReady 调用（影子消费线程 / 渲染线程），
 * 内部只读 {@code injectedChunks}（并发表），无锁竞争。
 */
public final class LightNeighborhoodGate {

    /** 邻域齐套超时（毫秒）：超时后用空气占位降级算光，宁可边缘不准也不卡死首波。 */
    public static final long NEIGHBORHOOD_TIMEOUT_MS = 3_000L;

    /**
     * 待齐套队列：复合键 → 等待上下文。consumeLoop 注入后入队，齐套后出队提交算光。
     * REPLACE 语义：同柱新投递覆盖旧等待（新数据重新计时）。
     */
    private static final ConcurrentHashMap<Long, AwaitingEntry> awaiting = new ConcurrentHashMap<>();

    private LightNeighborhoodGate() {
    }

    /** 待齐套条目：首次入队时刻 + 该柱的 LightTask 构建所需上下文。 */
    public record AwaitingEntry(long key, String dimension, ChunkPos pos,
                                long enqueuedAtMs, Object context) {
    }

    /**
     * 注册待齐套柱：consumeLoop 注入成功后调用（替代直接 addLightTask）。
     * REPLACE：同柱新投递覆盖旧条目，计时重置。
     */
    public static void enqueue(long key, String dimension, ChunkPos pos, Object context) {
        awaiting.put(key, new AwaitingEntry(key, dimension, pos, System.currentTimeMillis(), context));
        DebugLogger.info(DebugLogger.LogType.CHUNK_APPLY,
                "[LIGHT_GATE] Enqueue ({}, {}) dim={} pending={}",
                pos.x, pos.z, dimension, awaiting.size());
    }

    /** 取消待齐套（区块卸载 / 断连 / 投递被覆盖）。 */
    public static void cancel(long key) {
        awaiting.remove(key);
    }

    /** 清空全部待齐套（断连 / 影子端失败降级）。 */
    public static void clear() {
        awaiting.clear();
    }

    /** 当前待齐套数量（诊断 / 冒烟探针）。 */
    public static int pendingCount() {
        return awaiting.size();
    }

    /**
     * 尝试齐套：检查该柱 3×3 邻域，齐套则出队并返回上下文，否则保留等待返回 null。
     * <p>
     * <b>齐套判定</b>：邻柱必须「已过 INITIALIZE_LIGHT」（空 DataLayer 已安装）才算就绪——
     * 对齐原版生成金字塔：邻柱先到 INITIALIZE_LIGHT，中心柱才跑 LIGHT，
     * 这样中心柱传播时可写入邻柱空层，触发 onLightUpdate → 光桥下发。
     * <p>
     * 副作用：超时后对缺失邻柱注入空气空壳占位。
     *
     * @return 齐套时返回 enqueue 时传入的 context；未齐套 / 条目不存在返回 null
     */
    public static Object tryPromote(ShadowSeedServer server, long key) {
        AwaitingEntry entry = awaiting.get(key);
        if (entry == null) {
            return null;
        }
        String dimension = entry.dimension();
        ChunkPos pos = entry.pos();
        boolean timedOut = System.currentTimeMillis() - entry.enqueuedAtMs() >= NEIGHBORHOOD_TIMEOUT_MS;
        List<ChunkPos> needPlaceholders = new ArrayList<>(8);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue; // 本柱已注入
                }
                int nx = pos.x + dx;
                int nz = pos.z + dz;
                long nKey = DimensionKey.key(dimension, nx, nz);
                if (ShadowLightCompute.isLightInitialized(nKey)) {
                    continue; // 已过 INITIALIZE_LIGHT：就绪
                }
                if (server.injectedChunk(dimension, nx, nz) != null) {
                    // 已注入但未过 INITIALIZE_LIGHT：等初始化完成
                    if (!timedOut) {
                        return null;
                    }
                    // 超时：降级放行（用当前状态算光）
                    continue;
                }
                // 未注入：等超时后占位
                if (!timedOut) {
                    return null;
                }
                needPlaceholders.add(new ChunkPos(nx, nz));
            }
        }
        // 批量注入占位（幂等：已有柱时 injectPlaceholder no-op）
        for (ChunkPos placeholderPos : needPlaceholders) {
            server.injectPlaceholder(dimension, placeholderPos.x, placeholderPos.z);
        }
        if (!needPlaceholders.isEmpty()) {
            DebugLogger.info(DebugLogger.LogType.CHUNK_APPLY,
                    "[LIGHT_GATE] Promote ({}, {}) dim={} with {} air placeholders (timedOut={})",
                    pos.x, pos.z, dimension, needPlaceholders.size(), timedOut);
        } else {
            DebugLogger.info(DebugLogger.LogType.CHUNK_APPLY,
                    "[LIGHT_GATE] Promote ({}, {}) dim={} neighborhood ready (timedOut={})",
                    pos.x, pos.z, dimension, timedOut);
        }
        // 条件移除：并发 cancel / REPLACE 时放弃本次 promote
        if (!awaiting.remove(key, entry)) {
            return null;
        }
        return entry.context();
    }

    /** 待齐套 key 快照（供 consumeLoop 遍历；tryPromote 内部条件移除，安全）。 */
    public static List<Long> snapshotKeys() {
        return new ArrayList<>(awaiting.keySet());
    }

    /**
     * 邻域是否已齐套（只读检查，不注入占位、不出队）。
     * 齐套 = 全部 8 邻柱已注入（不要求 isLightCorrect）。
     * 供诊断 / 探针使用；算光路径用 {@link #tryPromote}。
     */
    public static boolean isNeighborhoodReady(ShadowSeedServer server, String dimension, ChunkPos pos) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int nx = pos.x + dx;
                int nz = pos.z + dz;
                if (server.injectedChunk(dimension, nx, nz) == null) {
                    return false;
                }
            }
        }
        return true;
    }
}
