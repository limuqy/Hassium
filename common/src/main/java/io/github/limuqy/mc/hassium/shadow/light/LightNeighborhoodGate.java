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
 *
 * <p><b>【钉死 · 禁止拆除/旁路】</b>（2026-09-18 用户目视确认）：本门不得删除、不得在
 * {@code startLightBarrier} 未 promote 时直接 {@code lightChunk}。实验「去齐套门以求首包更快」
 * 导致<strong>屋檐/洞口变黑且无后续光更新</strong>；回退后光照恢复正常。缺邻时
 * {@code LightEngine.getState} 对 null chunk 返回 {@code Blocks.BEDROCK}，天光进不了檐下。
 * 冒烟统计 PASS <b>不能</b>证明可拆本门——必须以游戏内屋檐/洞口为准。
 *
 * <p>原版 {@code ChunkStatus.LIGHT} 的 {@code range=1 + hasLoadDependencies=true} 要求
 * 切比雪夫距离 ≤1 的邻柱达到 {@code INITIALIZE_LIGHT} 后才跑 LIGHT 任务。本类在影子端
 * 复刻同一语义：注入后不立即算光，等 3×3 邻域齐套后再提交原版
 * {@code initializeLight + lightChunk} 一次算对，消除「缺邻当基岩挡光」的屋檐/洞口残差。
 * <p>
 * <b>齐套判定</b>（对每个 3×3 邻柱）：
 * <ul>
 *   <li>已过 INITIALIZE_LIGHT（{@code ShadowLightCompute.isLightInitPassed}，**单调**）→ 就绪</li>
 *   <li>已注入但尚未过 INITIALIZE_LIGHT → 等（超时后降级放行）</li>
 *   <li>未注入 → 等待超时（{@link #NEIGHBORHOOD_TIMEOUT_MS}）→ <b>不注入占位</b>，直接放行</li>
 * </ul>
     * 超时统一自「**全局最近一次柱落地**」起算（{@link #lastColumnLandedMs}）：只要系统还在
     * 持续交付新柱就一直等，避免服务端批量生成 / 网络抖动造成的**局部**缓慢被判成永久缺失而降级；
     * 另有 {@link #NEIGHBORHOOD_HARD_TIMEOUT_MS} 绝对兜底防「永久洞」。
     * <p>
     * <b>2026-09-19 修正</b>：该时钟**只在某柱首次入队时**推进（见 {@link #enqueue}）——同柱
     * 重入不是「新柱落地」。此前每次 enqueue 都推进，实测单柱 30s 内重入 149 次（稳定 5 次/s），
     * {@code sinceProgressMs} 恒 &lt; 3s → 软超时永不触发，只能等 30s 硬上限。
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
     * 邻域齐套超时（毫秒）：自「**全局最近一次柱落地**」起算（{@link #lastColumnLandedMs}；
     * 2026-09-19 用户口径，原为「本柱邻域最后一次变化」）。
     * <p>
     * <b>语义</b>：只要系统**还在持续落地新柱**，就认为「不是真的卡死」，等待中的条目一律
     * 重置记时；只有**整条落地流停摆** ≥ 本阈值才降级放行。理由（用户原话）：
     * 「能持续交付说明不是真的卡死了」——避免服务端大批量区块生成 / 单纯网络卡顿造成的
     * **局部**缓慢被误判成缺邻而白白降级（降级柱的光在缺邻方向是错的）。
     * <p>
     * 3s（2026-09-19 用户口径，由 2s 调至 3s）：正常齐套实测约 1s 内完成。
     * 「窗口外邻柱不等待」仍由 {@code outsideWindow} 分类独立兜底
     * （窗外邻柱不参与等待、也不计入缺邻）。
     */
    public static final long NEIGHBORHOOD_TIMEOUT_MS = 3_000L;

    /**
     * **绝对**上限（毫秒）：自**首次入队**起算，与全局落地时钟无关。
     * <p>
     * 它防的不是「缓慢」而是「**永久洞**」：全局记时下，只要别处还在落地，一柱哪怕自己的
     * 窗内邻柱永远不来也会无限等待 → **永不 promote → 永不交付**（连偏暗的柱都没有）。
     * 项目已定「空洞比偏暗柱更显眼」（handoff §0.3），故必须留一条绝对兜底。
     * <p>
     * 30s 刻意远大于任何「批量生成 / 网络抖动」量级：正常飞行下绝不会先触发
     * （先触发的一定是 {@link #NEIGHBORHOOD_TIMEOUT_MS}）。
     */
    public static final long NEIGHBORHOOD_HARD_TIMEOUT_MS = 30_000L;

    /**
     * 待齐套队列：复合键 → 等待上下文。consumeLoop 注入后入队，齐套后出队提交算光。
     * REPLACE 语义：同柱新投递覆盖旧等待（新数据重新计时）。
     */
    private static final ConcurrentHashMap<Long, AwaitingEntry> awaiting = new ConcurrentHashMap<>();

    /**
     * 阻塞诊断节流表：键 → 上次打印时刻（毫秒）。随 awaiting 同生命周期。
     * <p>
     * 原为「每条目只打一次」的 {@code Set}：只能看到**首次**阻塞快照，看不到「等 30s 期间
     * 邻域到底缺什么」，导致 30s 硬上限的成因无法从日志判定。现改为每
     * {@link #BLOCKED_LOG_INTERVAL_MS} 复打一次。
     */
    private static final ConcurrentHashMap<Long, Long> blockedLogged = new ConcurrentHashMap<>();

    /** 单条目阻塞诊断复打间隔（毫秒）。 */
    private static final long BLOCKED_LOG_INTERVAL_MS = 5_000L;

    /**
     * 最近一次「**任意柱落地**」的时刻（注入成功 → {@link #enqueue}）。降级记时的**全局基准**。
     * <p>
     * 见 {@link #NEIGHBORHOOD_TIMEOUT_MS}：它一直在被推进就说明系统仍在交付、不是真的卡死，
     * 等待中的条目不该降级。
     */
    private static final java.util.concurrent.atomic.AtomicLong lastColumnLandedMs =
            new java.util.concurrent.atomic.AtomicLong(0L);

    /** 本会话已齐套放行过的柱（单调；clear 时清空）。交付门：未 Promote 不得整柱打包。 */
    private static final java.util.Set<Long> promoted = ConcurrentHashMap.newKeySet();

    /**
     * 本会话「**完整**齐套放行」过的柱（单调；clear 时清空）。
     * <p>
     * 完整 = promote 时 8 邻**全部**已过 INITIALIZE_LIGHT（{@code notInjected==0 &&
     * injectedNotInit==0 && outsideWindow==0}）。即 promote 那一刻的 3×3 是真齐全的。
     * <p>
     * <b>2026-09-19（S5）把 {@code outsideWindow} 也算进「不完整」</b>：窗外邻柱虽然不是我们
     * 的拉取责任，但它**照样是缺邻**——引擎会把它当基岩挡光，所以这一柱的光在那个方向就是错的。
     * 原版口径与此一致：{@code ChunkStatus.LIGHT} 的 {@code range=1 + hasLoadDependencies}
     * 要求 3×3 全到齐，任务才可能完成；{@code ThreadedLevelLightEngine.lightChunk} 在**入口**
     * 就把 {@code isLightCorrect=false}，只有引擎跑完才置真——缺邻时任务根本不会完成，
     * 落盘自然是 {@code isLightOn=false}，读档时 {@code isLit = status≥LIGHT && isLightCorrect}
     * 为假 → 重跑 LIGHT。我们复用同一形式：**3×3 不齐就不置真**。
     * <p>
     * 直接后果（正是设计要的）：权威柱（计算域内）3×3 必然齐全 → 落盘、可复用；
     * 光环柱（计算域最外圈）外侧邻柱必然缺席 → **永不落盘**，下次进服重算。
     */
    private static final java.util.Set<Long> promotedClean = ConcurrentHashMap.newKeySet();

    /**
     * 全局单调「干净齐套放行」序号（{@link #promotedClean} 的写入序号）。
     * <p>
     * 用途 = 「降级修复 → 通知权威邻居重投」的**时序判据**：只有
     * {@code cleanAt(邻居) < 事件序号} 的权威柱才需要重投——它是在邻柱还是「降级低光」
     * 时算出来的；序号更大的说明它已用上最终光。
     */
    private static final java.util.concurrent.atomic.AtomicLong cleanEpoch =
            new java.util.concurrent.atomic.AtomicLong(0L);

    /** 柱 → 它最近一次干净齐套放行时的 {@link #cleanEpoch} 序号（见该类字段说明）。 */
    private static final ConcurrentHashMap<Long, Long> cleanAt = new ConcurrentHashMap<>();

    private LightNeighborhoodGate() {
    }

    /**
     * 待齐套条目：入队时刻 + 该柱的 LightTask 构建上下文。
     * <p>
     * 超时起算点**不在条目上**：降级记时用全局「最近一次柱落地」基准
     * （{@link #lastColumnLandedMs}），见 {@link #NEIGHBORHOOD_TIMEOUT_MS}。
     * <p>
     * 故不是 record：需要 IDENTITY equals（{@link #tryPromote} 的条件移除要判定
     * 「另一个线程换过条目」而非「值相等」）。
     */
    public static final class AwaitingEntry {

        private final long key;
        private final String dimension;
        private final ChunkPos pos;
        private final long enqueuedAtMs;
        private final Object context;

        AwaitingEntry(long key, String dimension, ChunkPos pos, long enqueuedAtMs, Object context) {
            this.key = key;
            this.dimension = dimension;
            this.pos = pos;
            this.enqueuedAtMs = enqueuedAtMs;
            this.context = context;
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
    }

    /**
     * 注册待齐套柱：consumeLoop 注入成功后调用（替代直接 addLightTask）。
     * REPLACE：同柱新投递覆盖旧条目（保留**首次**入队时刻，见下）。
     * <p>
     * 同时推进**全局**落地时钟 {@link #lastColumnLandedMs}——「有新柱落地」是降级记时的
     * 唯一重置源（见 {@link #NEIGHBORHOOD_TIMEOUT_MS}）。**仅首次入队推进**：同柱重入
     * （rearm → startLightBarrier / S2c 复查 / material 重投）不是「新柱落地」，若也推进，
     * 重入风暴会把 {@code sinceProgressMs} 恒压在 0 → 软超时永不触发。
     */
    public static void enqueue(long key, String dimension, ChunkPos pos, Object context) {
        long now = System.currentTimeMillis();
        // REPLACE 保留**首次**入队时刻：publish/光路径会反复 enqueue 同一柱，
        // 若每次重置 enqueuedAtMs，绝对上限（NEIGHBORHOOD_HARD_TIMEOUT_MS）永远到不了。
        awaiting.compute(key, (k, prev) -> {
            if (prev != null) {
                return new AwaitingEntry(key, dimension, pos, prev.enqueuedAtMs(), context);
            }
            // 全局落地时钟：**只在首次入队**（= 该柱真的新落地）时推进。
            // 实测（2026-09-19 飞行）：单柱 30s 内重入 149 次（稳定 5 次/s），每次都推进会让
            // sinceProgress 恒 < 3s → NEIGHBORHOOD_TIMEOUT_MS 永不触发，只能等 30s 硬上限，
            // 前沿柱首投延迟 +16~20s（见 MEMORY project_light_gate_reenqueue_storm）。
            lastColumnLandedMs.accumulateAndGet(now, Math::max);
            return new AwaitingEntry(key, dimension, pos, now, context);
        });
        DebugLogger.info(DebugLogger.LogType.LIGHT,
                "[LIGHT_GATE] Enqueue ({}, {}) dim={} pending={}",
                pos.x, pos.z, dimension, awaiting.size());
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
        promotedClean.clear();
        cleanAt.clear();
        cleanEpoch.set(0L);
        // 全局落地时钟归零：下一会话「首个柱落地」之前不拿旧会话的时刻当基准。
        lastColumnLandedMs.set(0L);
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

    /** 本会话是否**干净**齐套放行（I2 判据的 self 项；单调）。见 {@link #promotedClean}。 */
    public static boolean wasPromotedClean(long key) {
        return promotedClean.contains(key);
    }

    /**
     * 该坐标是否在「邻域等待窗」内：**计算/拉取域（serverVD + 光环）∪ OVD 环带**。
     * <p>
     * 窗外邻柱永远不会被 acquire，故既不参与等待、也不计入「脏」（原版外圈同样用空邻传播）。
     * <p>
     * <b>2026-09-19（S3）</b>：判据收口到
     * {@code ShadowTrackingSession.isInNeighborhoodWindow}（与 acquire 的
     * {@code isInComputeDomain} 同一真相源）。原实现用
     * `isAuthorityPullEligible || isDeliverableToClient`，其中交付域当时 = serverVD + 4
     * ——门会去等「没人拉」的邻柱，白等 2~4s 后降级放行并把该柱误判成「非权威」（P3）。
     * 会话未就绪时保守放行（等）。
     */
    public static boolean isInNeighborhoodWindow(int x, int z) {
        io.github.limuqy.mc.hassium.shadow.track.ShadowTrackingSession session =
                io.github.limuqy.mc.hassium.shadow.track.ShadowTrackingSession.getInstance();
        if (session == null) {
            return true;
        }
        return session.isInNeighborhoodWindow(x, z);
    }

    /**
     * 3×3 邻域是否已就绪（**窗内口径**，2026-09-19 任务 #37）。
     * <p>
     * 判据 = 「**等待窗内**的 8 邻（{@link #isInNeighborhoodWindow}）全部过了
     * INITIALIZE_LIGHT」。窗外邻柱不参与——它们永远不会被 acquire、也就永远过不了
     * INITIALIZE，若参与判定，重触发条件**永不成立** → S2c 补不回前沿柱
     * （用户实测「靠近也没重新拉取」，见 handoff §「飞行场景的两个空洞」②）。
     * <p>
     * <b>与 {@link #promotedClean} 的口径差异（刻意）</b>：落盘判据（S5/I2）仍把
     * **窗外缺邻**算缺邻——引擎把缺邻当基岩挡光，那种光在那个方向就是错的，不得标
     * 「光照完成」。本函数只回答「邻域还有没有救」：窗外邻柱若能进窗（玩家靠近）自然会
     * 进窗 → 被 acquire → INITIALIZE → 本判据随之变化。
     * <p>
     * <b>为什么不会无限 churn</b>：调用方（{@code recheckPendingAuthoritative}）另加
     * **进展门**——只有 {@link #inWindowReadyNeighborCount} 比登记时**增加**才重算。
     * 本判据单调，单靠它会让「永远无法 clean 的柱（带窗外缺邻）」在
     * finishLight → recheck → re-light → finishLight 之间自激。
     */
    public static boolean isNeighborhoodLightReady(String dimension, ChunkPos pos) {
        if (dimension == null || pos == null) {
            return false;
        }
        int inWindow = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int nx = pos.x + dx;
                int nz = pos.z + dz;
                if (!isInNeighborhoodWindow(nx, nz)) {
                    continue; // 窗外邻柱不参与：永远不会被 acquire，等它没意义
                }
                inWindow++;
                if (!ShadowLightCompute.isLightInitPassed(
                        DimensionKey.key(dimension, nx, nz))) {
                    return false; // 窗内邻柱还没过 INITIALIZE_LIGHT
                }
            }
        }
        return inWindow > 0;
    }

    /**
     * 该柱 3×3 中「**在等待窗内**且已过 INITIALIZE_LIGHT」的邻柱数（0..8）。
     * <p>
     * 用途 = {@code ShadowLightCompute.recheckPendingAuthoritative} 的**进展门**。
     * 本计数单调不减；只有它相对**登记时刻**增加，才说明邻域真有新进展、重算才可能得到
     * 不同结果。缺了这道门，单调的 {@link #isNeighborhoodLightReady} 会让
     * 「带窗外缺邻、永远 clean 不了」的柱无限自激重算。
     */
    public static int inWindowReadyNeighborCount(String dimension, ChunkPos pos) {
        if (dimension == null || pos == null) {
            return 0;
        }
        int ready = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int nx = pos.x + dx;
                int nz = pos.z + dz;
                if (!isInNeighborhoodWindow(nx, nz)) {
                    continue;
                }
                if (ShadowLightCompute.isLightInitPassed(
                        DimensionKey.key(dimension, nx, nz))) {
                    ready++;
                }
            }
        }
        return ready;
    }

    /**
     * 该柱最近一次**干净齐套放行**的序号；未 clean（或已 {@link #rearm} 且尚未重新 clean）
     * 时返回 {@code null}。见 {@link #cleanEpoch}。
     */
    public static Long cleanEpochOf(long key) {
        return cleanAt.get(key);
    }

    /**
     * 该柱 3×3 是否**真齐**：8 邻**全部**在等待窗内 **且** 全过 INITIALIZE_LIGHT。
     * <p>
     * 与 {@link #tryPromote} 的「完整」判据、{@link #promotedClean} **同口径**
     * （{@code notInjected==0 && injectedNotInit==0 && outsideWindow==0}）——
     * 窗外缺邻也算缺邻。
     * <p>
     * 用途 = 「降级修复 → 通知权威邻居重投」的**放弃闸**：不齐的邻居直接跳过（不登记、
     * 不等待），由「另外的缺位触发」（那个缺柱自己将来变 clean 时的事件）再通知它一次。
     */
    public static boolean isNeighborhoodFullyReady(String dimension, ChunkPos pos) {
        if (dimension == null || pos == null) {
            return false;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int nx = pos.x + dx;
                int nz = pos.z + dz;
                if (!isInNeighborhoodWindow(nx, nz)) {
                    return false; // 窗外缺邻也算缺（与 promotedClean 同口径）
                }
                if (!ShadowLightCompute.isLightInitPassed(
                        DimensionKey.key(dimension, nx, nz))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 允许本柱**重新**过齐套门：清掉 {@link #promoted} / {@link #promotedClean} 标记。
     * <p>
     * {@code ShadowLightCompute.startLightBarrier} 以 {@link #wasPromoted} 作为
     * 「已过门、直接跑 LIGHT」的判据。降级放行的柱若邻域后到，必须重新过门才能刷新
     * {@link #promotedClean}（I2 判据）；否则该柱会永久停在「非权威」，被 S2c 反复登记重算。
     */
    public static void rearm(long key) {
        promoted.remove(key);
        promotedClean.remove(key);
        // 「已消费到此刻」：重投期间再收到同批事件不重复触发（见 cleanAt 的时序判据）。
        cleanAt.put(key, cleanEpoch.get());
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
     * 这样中心柱传播时邻柱已有可写入的空层（缺邻时引擎按 Bedrock 挡光 → 屋檐/洞口黑）。
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
        // 降级记时基准 = max(本柱首次入队, 全局最近一次柱落地)：
        // 只要系统还在持续落地新柱，就不认为卡死（见 NEIGHBORHOOD_TIMEOUT_MS）。
        long sinceProgressMs = now - Math.max(entry.enqueuedAtMs(), lastColumnLandedMs.get());
        // 绝对兜底（首次入队起算，与全局时钟无关）：防「别处一直在落地、本柱却永远等不来
        // 邻柱」造成永久洞（见 NEIGHBORHOOD_HARD_TIMEOUT_MS）。
        boolean timedOut = sinceProgressMs >= NEIGHBORHOOD_TIMEOUT_MS
                || waitedMs >= NEIGHBORHOOD_HARD_TIMEOUT_MS;
        // 诊断态：方向序 NW,N,NE,W,E,SW,S,SE；'.'=就绪 'I'=已注入未过 INIT
        // 'M'=窗内未注入 'X'=权威窗外（不会 acquire，不参与等待）
        StringBuilder neighbors = new StringBuilder(8);
        int injectedNotInit = 0;
        int notInjected = 0; // 仅统计**窗内**未注入
        int outsideWindow = 0;
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
                if (!isInNeighborhoodWindow(nx, nz)) {
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
        // 两个分支都必须带 injectedNotInit：它是**唯一**能在 notInjected==0 && outsideWindow==0
        // 时仍阻塞的项。原「neighborhood ready」分支不打它，导致「邻域看起来齐了却等了 30s」
        // 被误读成非门控原因（2026-09-19 飞行诊断实证）。
        if (notInjected > 0 || outsideWindow > 0) {
            DebugLogger.info(DebugLogger.LogType.LIGHT,
                    "[LIGHT_GATE] Promote ({}, {}) dim={} missingInWindow={} outsideWindow={} "
                            + "timedOut={} waited={}ms sinceProgress={}ms (no placeholders) "
                            + "injectedNotInit={}",
                    pos.x, pos.z, dimension, notInjected, outsideWindow,
                    timedOut, waitedMs, sinceProgressMs, injectedNotInit);
        } else {
            DebugLogger.info(DebugLogger.LogType.LIGHT,
                    "[LIGHT_GATE] Promote ({}, {}) dim={} neighborhood ready "
                            + "(timedOut={} waited={}ms sinceProgress={}ms) injectedNotInit={}",
                    pos.x, pos.z, dimension, timedOut, waitedMs, sinceProgressMs, injectedNotInit);
        }
        // 条件移除：并发 cancel / REPLACE 时放弃本次 promote
        if (!awaiting.remove(key, entry)) {
            return null;
        }
        blockedLogged.remove(key);
        promoted.add(key);
        // 完整 = 3×3 真齐全：窗内无缺邻（未注入 / 已注入未过 INITIALIZE）**且**无窗外邻柱。
        // 窗外邻柱也是缺邻（引擎当基岩挡光）→ 该柱的光在那个方向就是错的，不得标「光照完成」。
        if (notInjected == 0 && injectedNotInit == 0 && outsideWindow == 0) {
            promotedClean.add(key);
            cleanAt.put(key, cleanEpoch.incrementAndGet()); // 记下本次 clean 的序号（时序判据）
        }
        return entry.context();
    }

    /**
     * 门控阻塞诊断（每 {@link #BLOCKED_LOG_INTERVAL_MS} 一次）：区分「邻柱未注入」（等不来 →
     * 需要存在性预言机）与「邻柱已注入但未过 INITIALIZE_LIGHT」（等得到 → 只是慢）。
     * <p>
     * 并发下最坏多打一两条，诊断用可接受。
     */
    private static void logBlockedOnce(long key, String dimension, ChunkPos pos, long waitedMs,
                                       CharSequence neighbors, int injectedNotInit, int notInjected) {
        long now = System.currentTimeMillis();
        Long last = blockedLogged.get(key);
        if (last != null && now - last < BLOCKED_LOG_INTERVAL_MS) {
            return;
        }
        blockedLogged.put(key, now);
        DebugLogger.info(DebugLogger.LogType.LIGHT,
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
