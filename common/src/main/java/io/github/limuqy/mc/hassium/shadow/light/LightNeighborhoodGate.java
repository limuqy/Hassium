package io.github.limuqy.mc.hassium.shadow.light;

import io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer;

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
 * {@code initializeLight + lightChunk} 一次算对，消除「缺邻当基岩挡光」的屋檐/洞口残差
 * （{@code LightEngine.getState} 对 null chunk 返回 {@code Blocks.BEDROCK}）。
 * <p>
 * <b>齐套判定</b>（对每个 3×3 邻柱）：
 * <ul>
 *   <li>已过 INITIALIZE_LIGHT（{@code ShadowLightCompute.isLightInitPassed}，**单调**）→ 就绪</li>
 *   <li>已注入但尚未过 INITIALIZE_LIGHT → 等（超时后降级放行）</li>
 *   <li>未注入 → 等待超时（{@link #NEIGHBORHOOD_TIMEOUT_MS}）→ <b>不注入占位</b>，直接放行</li>
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
 * <b>线程模型</b>：{@link #tryPromote} 由 consumeLoop / drainReady 调用（影子消费线程 / 渲染线程），
 * 内部只读 {@code injectedChunks}（并发表），无锁竞争。
 */
public final class LightNeighborhoodGate {

    /**
     * 邻域齐套超时（毫秒）：自**邻域最后一次变化**起算（见
     * {@link AwaitingEntry#lastProgressAtMs}）。
     * <p>
     * 2s：正常齐套实测约 1s 内完成。外圈另有 {@link #NEIGHBORHOOD_HARD_TIMEOUT_MS}
     * 与「窗口外邻柱不等待」兜底——否则波前爬坡时 lastProgress 一直被推进，
     * 最外圈永远等不来视距外邻柱 → injectedNotReady（vapack2 R1 实测 67 柱）。
     */
    public static final long NEIGHBORHOOD_TIMEOUT_MS = 2_000L;

    /**
     * 硬超时：自**入队时刻**起算，无论邻域是否仍在变化。
     * 外圈邻柱在权威窗外时永远不会 inject，必须在有限时间内放行本柱 LIGHT。
     */
    public static final long NEIGHBORHOOD_HARD_TIMEOUT_MS = 4_000L;

    /**
     * 待齐套队列：复合键 → 等待上下文。consumeLoop 注入后入队，齐套后出队提交算光。
     * REPLACE 语义：同柱新投递覆盖旧等待（新数据重新计时）。
     */
    private static final ConcurrentHashMap<Long, AwaitingEntry> awaiting = new ConcurrentHashMap<>();

    /** 已打印过阻塞诊断的键（每条目只记一次，避免逐帧刷屏）。随 awaiting 同生命周期。 */
    private static final java.util.Set<Long> blockedLogged = ConcurrentHashMap.newKeySet();

    /** 本会话已齐套放行过的柱（单调；clear 时清空）。交付门：未 Promote 不得整柱打包。 */
    private static final java.util.Set<Long> promoted = ConcurrentHashMap.newKeySet();

    private LightNeighborhoodGate() {
    }

    /**
     * 待齐套条目：入队时刻 + **邻域最近一次进展时刻** + 该柱的 LightTask 构建上下文。
     * <p>
     * {@code lastProgressAtMs} 是本类的超时起算点：每当本柱 8 邻中有柱被注入
     * （{@link #noteNeighborhoodProgress}），就把它推到当前时刻。语义 =
     * 「邻域停了 10s 才认它不会来」，而不是「我入队 10s 就认」——后者在推送爬坡期
     * 会把一批「邻柱马上就到」的柱全部降级。
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
        // REPLACE 保留**首次**入队时刻：publish/光路径会反复 enqueue 同一柱，
        // 若每次重置 enqueuedAtMs/lastProgress，外圈硬超时永远到不了 → injectedNotReady。
        awaiting.compute(key, (k, prev) -> {
            long enqueuedAt = prev != null ? prev.enqueuedAtMs() : now;
            AwaitingEntry entry = new AwaitingEntry(key, dimension, pos, enqueuedAt, context);
            if (prev != null) {
                entry.noteProgress(prev.lastProgressAtMs());
            }
            return entry;
        });
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
        promoted.clear();
    }

    /** 本会话是否已齐套放行（对齐 ChunkStatus.LIGHT range=1 完成后的交付门）。 */
    public static boolean wasPromoted(long key) {
        return promoted.contains(key);
    }

    public static boolean wasPromoted(String dimension, ChunkPos pos) {
        if (dimension == null || pos == null) {
            return false;
        }
        return promoted.contains(DimensionKey.key(dimension, pos.x, pos.z));
    }

    /** 当前待齐套数量（诊断 / 冒烟探针）。 */
    public static int pendingCount() {
        return awaiting.size();
    }

    /** 该柱是否在齐套等待中（防重复 submitPreLight 重置超时钟）。 */
    public static boolean isAwaiting(long key) {
        return awaiting.containsKey(key);
    }

    /**
     * 尝试齐套：检查该柱 3×3 邻域，齐套则出队并返回上下文，否则保留等待返回 null。
     * <p>
     * <b>齐套判定</b>：邻柱必须「已过 INITIALIZE_LIGHT」（空 DataLayer 已安装）才算就绪——
     * 对齐原版生成金字塔：邻柱先到 INITIALIZE_LIGHT，中心柱才跑 LIGHT，
     * 这样中心柱传播时可写入邻柱空层，触发 onLightUpdate → 光桥下发。
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
        // 硬超时：以**首次入队**为基准（enqueue REPLACE 不重置），防止外圈被反复入队拖死
        boolean timedOut = sinceProgressMs >= NEIGHBORHOOD_TIMEOUT_MS
                || waitedMs >= NEIGHBORHOOD_HARD_TIMEOUT_MS;
        // 诊断态：方向序 NW,N,NE,W,E,SW,S,SE；'.'=就绪 'I'=已注入未过 INIT
        // 'M'=窗内未注入 'X'=权威窗外（不会 acquire，不参与等待）
        StringBuilder neighbors = new StringBuilder(8);
        int injectedNotInit = 0;
        int notInjected = 0; // 仅统计**窗内**未注入
        int outsideWindow = 0;
        io.github.limuqy.mc.hassium.shadow.track.ShadowTrackingSession session =
                io.github.limuqy.mc.hassium.shadow.track.ShadowTrackingSession.getInstance();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue; // 本柱已注入
                }
                int nx = pos.x + dx;
                int nz = pos.z + dz;
                long nKey = DimensionKey.key(dimension, nx, nz);
                if (ShadowLightCompute.isLightInitPassed(nKey)) {
                    neighbors.append('.'); // 曾过 INITIALIZE_LIGHT：就绪（单调，不会被 LIGHT 起跑撤掉）
                    continue;
                }
                if (server.injectedChunk(dimension, nx, nz) != null) {
                    neighbors.append('I'); // 已注入但 DataLayer 还没装好
                    injectedNotInit++;
                    continue;
                }
                // 权威交付窗外的邻柱永远不会被 acquire：不等待（原版外圈也会用空邻传播）
                boolean wanted = session == null
                        || session.isAuthorityPullEligible(nx, nz)
                        || io.github.limuqy.mc.hassium.shadow.track.ShadowTrackingSession
                                .isDeliverableToClient(nx, nz);
                if (!wanted) {
                    neighbors.append('X');
                    outsideWindow++;
                    continue;
                }
                neighbors.append('M'); // 窗内未注入：等超时后放行（不再空气占位）
                notInjected++;
            }
        }
        // 窗外缺邻不挡齐套；窗内仍缺则等 soft/hard 超时
        if ((injectedNotInit > 0 || notInjected > 0) && !timedOut) {
            logBlockedOnce(key, dimension, pos, waitedMs, neighbors,
                    injectedNotInit, notInjected + outsideWindow);
            return null;
        }
        if (notInjected > 0 || outsideWindow > 0) {
            DebugLogger.info(DebugLogger.LogType.CHUNK_APPLY,
                    "[LIGHT_GATE] Promote ({}, {}) dim={} missingInWindow={} outsideWindow={} "
                            + "timedOut={} waited={}ms sinceProgress={}ms (no placeholders)",
                    pos.x, pos.z, dimension, notInjected, outsideWindow,
                    timedOut, waitedMs, sinceProgressMs);
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
        promoted.add(key);
        return entry.context();
    }

    /**
     * 门控阻塞诊断（每条目一次）：区分「邻柱未注入」（等不来 → 需要存在性预言机）与
     * 「邻柱已注入但未过 INITIALIZE_LIGHT」（等得到 → 只是慢）。
     */
    private static void logBlockedOnce(long key, String dimension, ChunkPos pos, long waitedMs,
                                       CharSequence neighbors, int injectedNotInit, int notInjected) {
        if (!blockedLogged.add(key)) {
            return;
        }
        DebugLogger.info(DebugLogger.LogType.CHUNK_APPLY,
                "[LIGHT_GATE] Blocked ({}, {}) dim={} waited={}ms neighbors=[{}] "
                        + "injectedNotInit={} notInjected={}",
                pos.x, pos.z, dimension, waitedMs, neighbors,
                injectedNotInit, notInjected);
    }

    /** 待齐套 key 快照（供消费轮遍历；tryPromote 内部条件移除，安全）。 */
    public static List<Long> snapshotKeys() {
        return new ArrayList<>(awaiting.keySet());
    }
}
