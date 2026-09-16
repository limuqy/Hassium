package io.github.limuqy.mc.hassium.network.seedgen;

import io.github.limuqy.mc.hassium.network.ChunkAuthorityClient;
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
 *   <li>已过 INITIALIZE_LIGHT（{@code ShadowLightCompute.isLightInitPassed}，**单调**）→ 就绪</li>
 *   <li>已注入但尚未过 INITIALIZE_LIGHT → 等（超时后降级放行）</li>
 *   <li>未注入 → 等待超时（{@link #NEIGHBORHOOD_TIMEOUT_MS}）→ 注入空气空壳占位</li>
 * </ul>
 * 超时统一自「**邻域最后一次变化**」起算（见 {@link AwaitingEntry#lastProgressAtMs}）：
 * 邻域还在长就一直等，避免推送爬坡期把「马上就到」的邻柱判成永久缺失而降级。
 * <p>
 * 不按权威集合提前占位——权威声明可能尚未到达，视距内邻柱会被误判为权威外。
 * <p>
 * <b>推进方式</b>：无独立事件源。判据是单调集合，consumeLoop / drainReady 每轮/每帧扫描
 * 即可在邻柱就绪后立刻提升（帧节拍 ≈16ms，远小于任何有效等待）；这样提升动作仍只发生在
 * 既有线程（消费线程 / 渲染线程），不把 vanilla chunk/light 引擎的调用面扩到 worldgen 线程池。
 * <p>
 * <b>空气空壳语义</b>：全空气 LevelChunk，天光从上方灌入、水平透过（等价原版
 * {@code NEG_INF} 哨兵）——地表屋檐正确，洞穴边缘偏亮（原版视距边缘同样不准，可接受）。
 * <p>
 * <b>线程模型</b>：{@link #tryPromote} 由 consumeLoop / drainReady 调用（影子消费线程 / 渲染线程），
 * 内部只读 {@code injectedChunks}（并发表），无锁竞争。
 */
public final class LightNeighborhoodGate {

    /**
     * 邻域齐套超时（毫秒）：自**邻域最后一次变化**起算（见
     * {@link AwaitingEntry#lastProgressAtMs}），超时后对缺失邻柱注入空气占位降级算光，
     * 宁可边缘不准也不卡死首波。
     * <p>
     * 起算点为何不是「本柱入队」：推送爬坡期（前几秒只到几十柱）本柱的邻柱还在路上，
     * 从入队起算会让这批「马上就到」的柱在 3s 后全部降级占位——实测 forge 锚点因此
     * 在视距内（r≤8）产生 33 个空气占位柱。改挂邻域进展后，只要邻域还在长就继续等，
     * 只有邻域**停了** 3s 才认「不会来」。
     * <p>
     * 该超时**不是**主要推进路径——正常流下邻柱在 1 帧内就绪并立即提升。
     */
    public static final long NEIGHBORHOOD_TIMEOUT_MS = 3_000L;

    /**
     * 待齐套队列：复合键 → 等待上下文。consumeLoop 注入后入队，齐套后出队提交算光。
     * REPLACE 语义：同柱新投递覆盖旧等待（新数据重新计时）。
     */
    private static final ConcurrentHashMap<Long, AwaitingEntry> awaiting = new ConcurrentHashMap<>();

    /** 已打印过阻塞诊断的键（每条目只记一次，避免逐帧刷屏）。随 awaiting 同生命周期。 */
    private static final java.util.Set<Long> blockedLogged = ConcurrentHashMap.newKeySet();

    private LightNeighborhoodGate() {
    }

    /**
     * 待齐套条目：入队时刻 + **邻域最近一次进展时刻** + 该柱的 LightTask 构建上下文。
     * <p>
     * {@code lastProgressAtMs} 是本类的超时起算点：每当本柱 8 邻中有柱被注入
     * （{@link #noteNeighborhoodProgress}），就把它推到当前时刻。语义 =
     * 「邻域停了 3s 才认它不会来」，而不是「我入队 3s 就认」——后者在推送爬坡期
     * 会把一批「邻柱马上就到」的柱全部降级成空气占位。
     * <p>
     * 故不是 record：需要可变字段（IDENTITY equals 也顺带让 {@link #tryPromote} 的条件移除
     * 更精确——那是「另一个线程换过条目」而非「值相等」的判定）。
     */
    public static final class AwaitingEntry {

        private final long key;
        private final String dimension;
        private final ChunkPos pos;
        private final long enqueuedAtMs;
        private final Object context;
        private final java.util.concurrent.atomic.AtomicLong lastProgressAtMs;

        AwaitingEntry(long key, String dimension, ChunkPos pos, long enqueuedAtMs, Object context) {
            this.key = key;
            this.dimension = dimension;
            this.pos = pos;
            this.enqueuedAtMs = enqueuedAtMs;
            this.context = context;
            this.lastProgressAtMs = new java.util.concurrent.atomic.AtomicLong(enqueuedAtMs);
        }

        public long key() {
            return key;
        }

        public String dimension() {
            return dimension;
        }

        public ChunkPos pos() {
            return pos;
        }

        public long enqueuedAtMs() {
            return enqueuedAtMs;
        }

        public Object context() {
            return context;
        }

        /** 邻域最近一次进展时刻（严格不小于 {@link #enqueuedAtMs}）。 */
        long lastProgressAtMs() {
            return lastProgressAtMs.get();
        }

        /** 邻域有新柱到达：推进超时起算点（单调，取 max，并发安全）。 */
        void noteProgress(long now) {
            lastProgressAtMs.accumulateAndGet(now, Math::max);
        }
    }

    /**
     * 注册待齐套柱：consumeLoop 注入成功后调用（替代直接 addLightTask）。
     * REPLACE：同柱新投递覆盖旧条目，计时重置。
     * <p>
     * 同时把 8 邻的等待时钟推到现在——「我这一格到了」对邻柱就是「邻域又长了一块」。
     */
    public static void enqueue(long key, String dimension, ChunkPos pos, Object context) {
        long now = System.currentTimeMillis();
        awaiting.put(key, new AwaitingEntry(key, dimension, pos, now, context));
        noteNeighborhoodProgress(dimension, pos, now);
        DebugLogger.info(DebugLogger.LogType.CHUNK_APPLY,
                "[LIGHT_GATE] Enqueue ({}, {}) dim={} pending={}",
                pos.x, pos.z, dimension, awaiting.size());
    }

    /** 把 {@code pos} 的 8 邻等待时钟推到 {@code now}（缺席的邻居不是本函数的关注点）。 */
    private static void noteNeighborhoodProgress(String dimension, ChunkPos pos, long now) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                AwaitingEntry neighbour =
                        awaiting.get(DimensionKey.key(dimension, pos.x + dx, pos.z + dz));
                if (neighbour != null) {
                    neighbour.noteProgress(now);
                }
            }
        }
    }

    /** 取消待齐套（区块卸载 / 断连 / 投递被覆盖）。 */
    public static void cancel(long key) {
        awaiting.remove(key);
        blockedLogged.remove(key);
    }

    /** 清空全部待齐套（断连 / 影子端失败降级）。 */
    public static void clear() {
        awaiting.clear();
        blockedLogged.clear();
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
        long now = System.currentTimeMillis();
        long waitedMs = now - entry.enqueuedAtMs();
        // 超时自「邻域最后一次变化」起算：邻柱还在陆续到达就不降级（见 AwaitingEntry）。
        long sinceProgressMs = now - Math.max(entry.enqueuedAtMs(), entry.lastProgressAtMs());
        boolean timedOut = sinceProgressMs >= NEIGHBORHOOD_TIMEOUT_MS;
        List<ChunkPos> needPlaceholders = new ArrayList<>(8);
        // 诊断态：方向序 NW,N,NE,W,E,SW,S,SE；'.'=就绪 'I'=已注入未过 INIT 'M'=未注入
        StringBuilder neighbors = new StringBuilder(8);
        int injectedNotInit = 0;
        int notInjected = 0;
        int notAuthoritative = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue; // 本柱已注入
                }
                int nx = pos.x + dx;
                int nz = pos.z + dz;
                long nKey = DimensionKey.key(dimension, nx, nz);
                if (!ChunkAuthorityClient.isAuthoritative(dimension, nx, nz)) {
                    notAuthoritative++;
                }
                if (ShadowLightCompute.isLightInitPassed(nKey)) {
                    neighbors.append('.'); // 曾过 INITIALIZE_LIGHT：就绪（单调，不会被 LIGHT 起跑撤掉）
                    continue;
                }
                if (server.injectedChunk(dimension, nx, nz) != null) {
                    neighbors.append('I'); // 已注入但 DataLayer 还没装好
                    injectedNotInit++;
                    continue;
                }
                neighbors.append('M'); // 未注入：只能等超时后占位
                notInjected++;
                if (timedOut) {
                    needPlaceholders.add(new ChunkPos(nx, nz));
                }
            }
        }
        if ((injectedNotInit > 0 || notInjected > 0) && !timedOut) {
            logBlockedOnce(key, dimension, pos, waitedMs, neighbors,
                    injectedNotInit, notInjected, notAuthoritative);
            return null;
        }
        // 批量注入占位（幂等：已有柱时 injectPlaceholder no-op）
        for (ChunkPos placeholderPos : needPlaceholders) {
            server.injectPlaceholder(dimension, placeholderPos.x, placeholderPos.z);
        }
        if (!needPlaceholders.isEmpty()) {
            DebugLogger.info(DebugLogger.LogType.CHUNK_APPLY,
                    "[LIGHT_GATE] Promote ({}, {}) dim={} with {} air placeholders "
                            + "(timedOut={} waited={}ms sinceProgress={}ms)",
                    pos.x, pos.z, dimension, needPlaceholders.size(), timedOut,
                    waitedMs, sinceProgressMs);
        } else {
            DebugLogger.info(DebugLogger.LogType.CHUNK_APPLY,
                    "[LIGHT_GATE] Promote ({}, {}) dim={} neighborhood ready "
                            + "(timedOut={} waited={}ms sinceProgress={}ms)",
                    pos.x, pos.z, dimension, timedOut, waitedMs, sinceProgressMs);
        }
        // 条件移除：并发 cancel / REPLACE 时放弃本次 promote
        if (!awaiting.remove(key, entry)) {
            return null;
        }
        blockedLogged.remove(key);
        return entry.context();
    }

    /**
     * 门控阻塞诊断（每条目一次）：区分「邻柱未注入」（等不来 → 需要存在性预言机）与
     * 「邻柱已注入但未过 INITIALIZE_LIGHT」（等得到 → 只是慢），并附权威声明覆盖情况。
     */
    private static void logBlockedOnce(long key, String dimension, ChunkPos pos, long waitedMs,
                                       CharSequence neighbors, int injectedNotInit, int notInjected,
                                       int notAuthoritative) {
        if (!blockedLogged.add(key)) {
            return;
        }
        DebugLogger.info(DebugLogger.LogType.CHUNK_APPLY,
                "[LIGHT_GATE] Blocked ({}, {}) dim={} waited={}ms neighbors=[{}] "
                        + "injectedNotInit={} notInjected={} notAuthoritative={}",
                pos.x, pos.z, dimension, waitedMs, neighbors,
                injectedNotInit, notInjected, notAuthoritative);
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
