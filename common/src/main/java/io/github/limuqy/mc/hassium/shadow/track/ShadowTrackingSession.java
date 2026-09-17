package io.github.limuqy.mc.hassium.shadow.track;

import io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer;
import io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry;
import io.github.limuqy.mc.hassium.shadow.server.ShadowWorldgenExecutor;
import io.github.limuqy.mc.hassium.shadow.server.SeedGenLevelCompat;
import io.github.limuqy.mc.hassium.shadow.server.SeedGenExecutor;
import io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute;
import io.github.limuqy.mc.hassium.shadow.light.SmokeChunkTrace;
import io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageHashes;

import io.github.limuqy.mc.hassium.compat.ChunkShapeCompat;
import io.github.limuqy.mc.hassium.compat.LevelCompat;
import io.github.limuqy.mc.hassium.compat.ShadowChunkMapCompat;
import io.github.limuqy.mc.hassium.compat.ShadowPlayerCompat;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.ShadowPullClient;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DimensionKey;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

/**
 * 影子虚拟玩家 tracking 会话：§6 目标态的采集决策者。
 * <p>
 * 真实客户端位置/维度单向同步给影子 {@code ShadowSeedServer} 内唯一虚拟
 * {@link ServerPlayer}（GameTestServer 配方 + EmbeddedChannel 连接桩）；原版
 * {@code ChunkMap} 玩家 tracking 决定选柱。选中柱经 {@code scheduleChunkLoad}
 * 影子钩子登记本会话，由影子主循环泵按批发出统一 Compare+Pull：
 * <ul>
 *   <li>有本地基线（磁盘 hash / 注入柱）→ {@link ShadowPullClient#requestFull} 比对请求；</li>
 *   <li>无基线 → {@link ShadowPullClient#requestAuthoritativeFull} 空基线请求（服务端必答 FULL）。</li>
 * </ul>
 * 线程纪律：真实客户端 tick（主线程）只发布 volatile 待同步状态；虚拟玩家创建 /
 * 移动 / chunk 系统簿记 / pull 请求全部在影子主循环线程执行（与 vanilla 单线程
 * ChunkMap 语义一致）。
 */
public final class ShadowTrackingSession {

    private static final ShadowTrackingSession INSTANCE = new ShadowTrackingSession();

    /** 半径上限：与服务端 ShadowPullRequestValidator 的 maxDistance（真实视距+1）对齐。 */
    private static final int MAX_VIEW_DISTANCE = 32;
    private static final int DEFAULT_VIEW_DISTANCE = 10;
    /** 影子主循环内 chunk 系统簿记驱动周期（vanilla 20Hz 同频）。 */
    private static final long CHUNK_TICK_INTERVAL_MS = 50L;
    /** 单次簿记 tick 的卸载/清理预算。 */
    private static final long CHUNK_TICK_BUDGET_NANOS = 2_000_000L;
    /** 一次泵最多发出的 pull 请求柱数（防首帧风暴；剩余下轮续发）。 */
    private static final int MAX_REQUESTS_PER_PUMP = 128;
    /** 票窗补全泵间隔与单泵预算（ServerVD 窗内 completeness，非窗外 bootGrid）。 */
    private static final long WINDOW_PUMP_INTERVAL_MS = 200L;
    private static final int WINDOW_PUMP_BUDGET = 64;
    private long lastWindowPumpMs;

    /** 客户端 tick 发布的待同步状态（volatile 整体换引用，无锁）。 */
    private record PendingState(String dimension, double x, double y, double z,
                                float yRot, float xRot, boolean present) {}

    private volatile PendingState pending;
    /** 服务端下发的 chunk cache 半径（ClientboundSetChunkCacheRadiusPacket 捕获；-1 = 未知）。 */
    private volatile int serverViewDistance = -1;
    /** 客户端发布的 effective clientRD（OVD 开时 = min(滑块, maxRenderDistance)；否则 = serverVD）。 */
    private volatile int effectiveClientVD = -1;

    /** 影子主循环线程持有的会话状态（仅影子线程读写）。 */
    private ShadowSeedServer boundServer;
    private ServerPlayer virtualPlayer;
    private String currentDimension;
    private boolean createFailed;
    private long lastChunkTickMs;

    /** 会话落位基准点：首个稳定座位（虚拟玩家放置瞬间的 chunk）。 */
    private ChunkPos homeChunk;
    private final java.util.concurrent.ConcurrentLinkedQueue<ChunkPos> redeliverQueue =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** 单泵最多本地重发柱数。 */
    private static final int MAX_REDELIVER_PER_PUMP = 32;

    /**
     * 注入表回收：保留域 = 可见形状 ∪ OVD 圈（即 {@code max(serverVD, clientVD)} 几何）。
     * 柱离开保留域并超过 {@link #RECLAIM_GRACE_MS} 后交 {@link ShadowSeedServer#unloadChunk}
     * （先 flush 落盘再摘表）；{@code ShadowStorageHashes} 保留作后续比对基线。
     * <p>
     * 背景：客户端卸载不得拆影子表（会造永久洞，见 {@code 935b9ed}），但影子表因此长期只增；
     * 服务端权威外的柱既收不到更新也不能当权威交付，必须回收以维持内存有界。
     */
    private static final long RECLAIM_INTERVAL_MS = 1000L;
    /** 离开保留域后的宽限（吸收边界抖动 / 来回移动）。 */
    public static final long RECLAIM_GRACE_MS = 6000L;
    /** 单轮最多回收柱数。 */
    private static final int MAX_RECLAIM_PER_PASS = 64;
    /** key(复合键) -> 首次观察到离开保留域的毫秒时刻。 */
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> outsideSinceMs =
            new java.util.concurrent.ConcurrentHashMap<>();
    private long lastReclaimMs;

    /**
     * 注入表回收专用调度器（独立守护线程）。
     * <p>
     * <b>不得在影子主循环内调用</b> {@link ShadowSeedServer#unloadChunk}：其
     * {@code flushColumn} 会等待影子主循环 → 主循环内调用即自死锁（实测 R2 挂死 /
     * teardown 悬挂）。本调度器从外部线程驱动回收，主循环只登记候选。
     */
    private static final java.util.concurrent.ScheduledExecutorService RECLAIM_TIMER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "hassium-shadow-reclaim");
                t.setDaemon(true);
                return t;
            });
    private static volatile java.util.concurrent.ScheduledFuture<?> reclaimTask;

    /** 首次登记 leave 时启动回收调度（幂等）。 */
    private static void ensureReclaimTimer() {        if (reclaimTask != null) {
            return;
        }
        synchronized (ShadowTrackingSession.class) {
            if (reclaimTask != null) {
                return;
            }
            reclaimTask = RECLAIM_TIMER.scheduleAtFixedRate(() -> {
                ShadowTrackingSession session = INSTANCE;
                ShadowSeedServer server = session.boundServer;
                if (server == null) {
                    return;
                }
                try {
                    session.reclaimOutOfRetainSet(server, System.currentTimeMillis());
                } catch (Throwable t) {
                    DebugLogger.warn(DebugLogger.LogType.ASYNC, "[SHADOW_TRACK] reclaim failed", t);
                }
            }, RECLAIM_INTERVAL_MS, RECLAIM_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }
    /** 形状扫描 / 悬置选柱共用的在途柱（复合键 → 入队时刻）：已发 pull 未注入。 */
    private static final long SWEEP_INFLIGHT_TIMEOUT_MS = 60_000L;
    private final java.util.Map<Long, Long> sweepInFlight =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 本会话已计 OVD 的坐标（防 materialize/sweep 双计；reset 清空）。 */
    private final java.util.Set<Long> ovdCounted = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 本会话已计 OVD miss 的坐标（retry 不再累加「缺失」）。 */
    private final java.util.Set<Long> ovdMissCounted = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** OVD miss 冷却（key → 下次允许重试 epoch ms；避免空盘格每 500ms 重读）。 */
    private final java.util.Map<Long, Long> ovdMissRetryAt =
            new java.util.concurrent.ConcurrentHashMap<>();
    private long lastOvdSweepMs;

    /** 待移除虚拟玩家（{@link #reset} 在客户端主线程调用；vanilla remove 须留给影子主循环）。 */
    private record PendingRemoval(ShadowSeedServer server, ServerPlayer player) {}

    private volatile PendingRemoval pendingRemoval;
    /** 已应用到影子 ChunkMap 的视距（-1 = 未应用；半径晚到/变化时由影子主循环补应用）。 */
    private volatile int appliedViewDistance = -1;

    private ShadowTrackingSession() {}

    public static ShadowTrackingSession getInstance() {
        return INSTANCE;
    }

    /** 一次性诊断（Constants.LOG 不受 debug 门控；定位会话为何未启动）。 */
    private static volatile boolean diagLogged;

    private static void diagOnce(String message, Object... args) {
        if (!diagLogged) {
            diagLogged = true;
            io.github.limuqy.mc.hassium.Constants.LOG.info("[SHADOW_TRACK] " + message, args);
        }
    }

    /** 客户端 tick（真实主线程）：仅发布状态，不触碰影子世界。 */
    public static void onClientTick(Minecraft mc) {
        if (mc == null || mc.player == null || mc.level == null) {
            return;
        }
        if (!HassiumConfigService.getInstance().isHassiumEngineEnabled()) {
            return;
        }
        ShadowServerRegistry registry = ShadowServerRegistry.getInstance();
        if (registry.isFailed() || registry.get() == null) {
            diagOnce("onClientTick skip: shadowServer null={} failed={}",
                    registry.get() == null, registry.isFailed());
            return;
        }
        String dimension = LevelCompat.getDimensionId(mc.level);
        diagOnce("onClientTick publish dim={} shadowReady={}", dimension, registry.get() != null);
        INSTANCE.pending = new PendingState(dimension,
                mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                mc.player.getYRot(), mc.player.getXRot(), true);
    }

    /** 服务端 chunk cache 半径捕获（{@code ClientboundSetChunkCacheRadiusPacket}）。 */
    public static void setServerViewDistance(int radius) {
        if (radius > 0) {
            INSTANCE.serverViewDistance = radius;
        }
    }

    /**
     * 权威 pull 门（R4）：**优先用真实客户端玩家区块**（真服 tracking 的中心），
     * 会话未就绪（VD 未知 / 无中心）时禁止 pull。与 {@link #inVanillaVisibleShape}
     * 的交付门语义分离：后者 center 未知时放行；这里保守，且不得用滞后的虚拟玩家中心
     * （R2 join 期 VP 可能仍在 (0,0)，会把玩家脚下窗内柱误判窗外）。
     */
    public boolean isAuthorityPullEligible(int x, int z) {
        if (serverViewDistance <= 0) {
            return false;
        }
        ChunkPos center = deliveryCenter();
        if (center == null) {
            return false;
        }
        return ChunkShapeCompat.contains(center.x, center.z, serverViewDistance, x, z);
    }

    /**
     * 交付/trace/pull 几何中心：真实客户端玩家区块优先（与 ClientChunkCache / 真服
     * tracking 同轴），缺失时退回虚拟玩家 / homeChunk。
     */
    public static ChunkPos deliveryCenter() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.player != null) {
                return new ChunkPos(mc.player.getBlockX() >> 4, mc.player.getBlockZ() >> 4);
            }
        } catch (Throwable ignored) {
            // 无客户端环境（单测/服务端线程）：退回影子会话中心
        }
        ShadowTrackingSession s = INSTANCE;
        if (s == null) {
            return null;
        }
        if (s.virtualPlayer != null) {
            ChunkPos vp = s.virtualPlayer.chunkPosition();
            if (vp != null) {
                return vp;
            }
        }
        return s.homeChunk;
    }

    /** 当前服务端通告视距（未知 -1）。 */
    public static int serverViewDistance() {
        return INSTANCE.serverViewDistance;
    }

    /** 客户端 tick 发布 effective clientRD（OVD 双窗）。 */
    public static void publishEffectiveClientVD(int radius) {
        if (radius > 0) {
            INSTANCE.effectiveClientVD = radius;
        }
    }

    public static int effectiveClientVD() {
        return INSTANCE.effectiveClientVD;
    }

    /** 诊断：虚拟玩家是否已创建（主循环心跳用）。 */
    public boolean hasVirtualPlayer() {
        return virtualPlayer != null;
    }

    /** 诊断：当前跟踪维度（主循环心跳用）。 */
    public String currentDimension() {
        return currentDimension;
    }

    /** 影子主循环泵读取的当前跟踪维度 level（无会话/未跟踪返回 null）。 */
    public static ServerLevel trackedLevel() {
        ShadowTrackingSession s = INSTANCE;
        if (s.virtualPlayer == null || s.boundServer == null) {
            return null;
        }
        return s.boundServer.level(s.currentDimension);
    }

    /**
     * 影子主循环每轮调用：消费待同步状态 + 节流驱动 chunk 系统簿记 + 分批发 pull 请求。
     * 仅影子主循环线程可调。
     */
    public void consumeOnShadowLoop() {
        drainPendingRemoval();
        ShadowSeedServer shadow = ShadowServerRegistry.getInstance().get();
        if (shadow != boundServer) {
            // 实例被重建（换服/seed 重建）：会话失效，随下一次同步重建
            boundServer = shadow;
            virtualPlayer = null;
            currentDimension = null;
            createFailed = false;
            sweepInFlight.clear();
            redeliverQueue.clear();
            lastChunkTickMs = 0;
        }
        if (shadow == null || createFailed) {
            return;
        }
        PendingState state = pending;
        pending = null;
        if (state != null) {
            applyState(shadow, state);
        }
        if (virtualPlayer == null) {
            return;
        }
        applyViewDistanceIfChanged(shadow);
        // P5 票驱动已默认关闭（S0 专用服模型）
        ChunkPos ticketCenter = virtualPlayer.chunkPosition();
        ShadowTicketDriver.consumeOnShadowLoop(shadow, currentDimension,
                ticketCenter.x, ticketCenter.z, serverViewDistance, effectiveClientVD);
        long now = System.currentTimeMillis();
        if (now - lastChunkTickMs >= CHUNK_TICK_INTERVAL_MS) {
            lastChunkTickMs = now;
            ServerLevel level = shadow.level(currentDimension);
            // 客户端退出窗口守卫（同 SeedGenLevelCompat.shutdown 的 skipSave 语义）：
            // vanilla Stopping! → Util.shutdownExecutors() 关停共享 ioPool 后，
            // tickChunkSystem → ChunkMap.processUnloads → saveChunksEagerly → ChunkMap.save
            // 会对半死池提交任务，vanilla 内部吞掉 RejectedExecutionException 后记
            // "Failed to save chunk x,z" ERROR（污染 LogAudit 门禁，无害但吵）。
            // JVM 即将退出，存档无后续消费者，停止簿记是安全的。
            if (level != null && !ShadowWorldgenExecutor.isTerminated()
                    && !io.github.limuqy.mc.hassium.compat.ShadowServerCompat.isSharedIoPoolShutdown()) {
                try {
                    ShadowPlayerCompat.tickChunkSystem(level,
                            System.nanoTime() + CHUNK_TICK_BUDGET_NANOS);
                } catch (java.util.concurrent.RejectedExecutionException ignored) {
                    // 关停窗口：worldgen 池已拒绝新任务
                } catch (Throwable t) {
                    DebugLogger.warn(DebugLogger.LogType.ASYNC,
                            "[SHADOW_TRACK] chunk system tick failed", t);
                }
            }
        }
        ShadowPlayerCompat.flushVirtualPlayerChunks(virtualPlayer);
        sweepOvdRing(shadow, now);
        drainRedeliver(shadow);
        drainTrackingWindowCompleteness(shadow, now);
        // P3（注入表回收）不在此处调用：ShadowSeedServer.unloadChunk 的 flushColumn 会等待
        // 影子主循环 → 主循环内调用即自死锁（实测 R2 挂死 / teardown 悬挂）。候选由
        // onClientChunkUnloaded 登记，回收由独立的 hassium-shadow-reclaim 线程驱动。
    }

    /**
     * 影子票窗（ServerVD）补全：客户端无落地凭据 → 已注入 publish / 未注入 Provider.acquire。
     * 只扫当前窗，不自绘窗外几何（不是 bootGrid）。
     */
    private void drainTrackingWindowCompleteness(ShadowSeedServer shadow, long nowMs) {
        if (shadow == null || virtualPlayer == null || currentDimension == null
                || serverViewDistance <= 0) {
            return;
        }
        if (nowMs - lastWindowPumpMs < WINDOW_PUMP_INTERVAL_MS) {
            return;
        }
        lastWindowPumpMs = nowMs;
        ChunkPos center = virtualPlayer.chunkPosition();
        if (center == null) {
            return;
        }
        int served = 0;
        int acquired = 0;
        for (int x = center.x - serverViewDistance; x <= center.x + serverViewDistance; x++) {
            for (int z = center.z - serverViewDistance; z <= center.z + serverViewDistance; z++) {
                if (served + acquired >= WINDOW_PUMP_BUDGET) {
                    break;
                }
                if (!ChunkShapeCompat.contains(center.x, center.z, serverViewDistance, x, z)) {
                    continue;
                }
                ChunkPos pos = new ChunkPos(x, z);
                if (ShadowLightCompute.hasClientApplyEpoch(currentDimension, pos)) {
                    continue;
                }
                net.minecraft.world.level.chunk.LevelChunk injected =
                        shadow.injectedChunk(currentDimension, x, z);
                if (injected != null && !shadow.isPlaceholder(currentDimension, x, z)) {
                    if (ShadowChunkDeliver.deliverLocal(currentDimension, pos, false, false)) {
                        served++;
                    }
                    continue;
                }
                VanillaAlignedChunkProvider.getInstance().acquire(
                        currentDimension, pos, ShadowChunkProvider.AcquireReason.TRACKING);
                acquired++;
            }
        }
        if (served + acquired > 0) {
            DebugLogger.info(DebugLogger.LogType.NETWORK,
                    "[SHADOW_TRACK] window-complete center=({},{}) vd={} served={} acquired={}",
                    center.x, center.z, serverViewDistance, served, acquired);
        }
    }

    /**
     * 回收判定（纯函数，L0 可测）：离开已超宽限 + 非在途 + 客户端未重新持有 → 可回收。
     * <p>
     * 任一不满足即不得回收。调用点先用宽限分支保留标记，再用本判定决定是否撤销标记。
     */
    public static boolean reclaimEligible(long outsideSinceMs, long nowMs, boolean inFlight, boolean clientHolds) {
        return outsideSinceMs > 0L && nowMs - outsideSinceMs >= RECLAIM_GRACE_MS
                && !inFlight && !clientHolds;
    }

    /**
     * TODO(P3)：由独立异步执行器（非影子主循环）定期调用；见 consumeOnShadowLoop 注记。
     * 回收「客户端已明确离开」的注入柱（节流 + 限量）。
     * <p>
     * <b>触发源必须是客户端 leave（真服 Forget）</b>，不得按影子端自绘几何推断——
     * 几何驱动会摘掉客户端尚未交付的柱（实测 R1 交付 1636→585）并在断连 teardown 期
     * flush 卡死。这里只处理 {@link #outsideSinceMs} 里已登记的柱（由
     * {@code onClientChunkUnloaded} 写入），宽限 {@link #RECLAIM_GRACE_MS} 后仍未回来
     * 且不在途、未重新交付者，交 {@link ShadowSeedServer#unloadChunk} 回收。
     */
    private void reclaimOutOfRetainSet(ShadowSeedServer shadow, long nowMs) {
        if (shadow == null || outsideSinceMs.isEmpty()) {
            return;
        }
        if (io.github.limuqy.mc.hassium.shadow.server.ShadowWorldgenExecutor.isTerminated()
                || io.github.limuqy.mc.hassium.compat.ShadowServerCompat.isSharedIoPoolShutdown()) {
            return; // 关停窗口：flush 会卡在已停止的主循环上（实测 teardown 悬挂）
        }
        if (nowMs - lastReclaimMs < RECLAIM_INTERVAL_MS) {
            return;
        }
        lastReclaimMs = nowMs;
        int reclaimed = 0;
        for (java.util.Map.Entry<Long, Long> entry : outsideSinceMs.entrySet()) {
            if (reclaimed >= MAX_RECLAIM_PER_PASS) {
                break;
            }
            long key = entry.getKey();
            long since = entry.getValue();
            if (nowMs - since < RECLAIM_GRACE_MS) {
                continue;
            }
            String dimension = DimensionKey.dimensionOf(key);
            if (dimension == null) {
                outsideSinceMs.remove(key);
                continue;
            }
            int x = DimensionKey.chunkXOf(key);
            int z = DimensionKey.chunkZOf(key);
            var chunk = shadow.injectedChunk(dimension, x, z);
            if (chunk == null) {
                outsideSinceMs.remove(key);
                continue;
            }
            if (!reclaimEligible(since, nowMs,
                    ShadowLightCompute.isAuthoritativeIngressInFlight(key),
                    ShadowLightCompute.hasClientApplyEpoch(dimension, new ChunkPos(x, z)))) {
                // 在途 / 客户端本会话又拿到了：撤销离开标记，不回收
                outsideSinceMs.remove(key);
                continue;
            }
            if (ShadowColumnStore.flushAndEvict(dimension, new ChunkPos(x, z), chunk)) {
                outsideSinceMs.remove(key);
                reclaimed++;
            }
        }
    }

    /**
     * OVD 环带周期扫描：park 复用 / 短路已注入柱可能不走悬置选柱路径，
     * 这里补齐「已注入未交付」的 publish 与「未注入」的本地盘/生成服务。
     * <p>
     * 只扫环带（chebyshev ∈ (serverVD, clientVD]），内环优先；
     * 盘读与 publish 分开计预算，避免权威方阵空转扫 33²。
     */
    private static final int OVD_DISK_BUDGET = 64;
    private static final int OVD_PUBLISH_BUDGET = 64;
    private static final long OVD_SWEEP_INTERVAL_MS = 100L;

    private void sweepOvdRing(ShadowSeedServer shadow, long nowMs) {
        if (shadow == null || virtualPlayer == null || currentDimension == null) {
            return;
        }
        // 客户端已切维、tracking 未 reseat：禁止把旧维 OVD 柱推进新 ClientLevel（脚下闪主世界）。
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.level != null) {
                String clientDim = io.github.limuqy.mc.hassium.compat.LevelCompat
                        .getDimensionId(mc.level);
                if (clientDim != null && !clientDim.equals(currentDimension)) {
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
        int serverVD = serverViewDistance;
        int clientVD = effectiveClientVD;
        if (serverVD <= 0 || clientVD <= serverVD) {
            return;
        }
        if (nowMs - lastOvdSweepMs < OVD_SWEEP_INTERVAL_MS) {
            return;
        }
        lastOvdSweepMs = nowMs;
        ChunkPos center = virtualPlayer.chunkPosition();
        int diskTried = 0;
        int published = 0;
        int windowCells = 0;
        int notInjected = 0;
        // 内环优先：按 chebyshev 周长枚举**方环**（四条边），不能用 |dx|+|dz| 菱形——
        // 菱形会漏掉每环四角，外环 13–16 的角柱永远进不了 OVD（window 偏小、disk 恒 0）。
        outer:
        for (int cheb = 1; cheb <= clientVD; cheb++) {
            for (int side = 0; side < 4; side++) {
                for (int t = -cheb; t <= cheb; t++) {
                    if (diskTried >= OVD_DISK_BUDGET && published >= OVD_PUBLISH_BUDGET) {
                        break outer;
                    }
                    int dx;
                    int dz;
                    switch (side) {
                        case 0 -> { dx = t; dz = cheb; }   // N
                        case 1 -> { dx = t; dz = -cheb; }  // S
                        case 2 -> { dx = cheb; dz = t; }   // E
                        default -> { dx = -cheb; dz = t; } // W
                    }
                    int x = center.x + dx;
                    int z = center.z + dz;
                    if (!inOvdWindow(x, z)) {
                        continue;
                    }
                    windowCells++;
                    long key = io.github.limuqy.mc.hassium.utils.DimensionKey
                            .key(currentDimension, x, z);
                    if (ovdCounted.contains(key)) {
                        continue;
                    }
                    Long missAt = ovdMissRetryAt.get(key);
                    if (missAt != null && nowMs < missAt) {
                        continue;
                    }
                    ChunkPos pos = new ChunkPos(x, z);
                    var injected = shadow.injectedChunk(currentDimension, x, z);
                    if (injected != null) {
                        if (published >= OVD_PUBLISH_BUDGET) {
                            continue;
                        }
                        if (ShadowLightCompute.hasClientApplyEpoch(currentDimension, pos)) {
                            recordOvdLoadedOnce(currentDimension, pos);
                            continue;
                        }
                        if (ShadowLightCompute.publishOvdCachedChunk(currentDimension, pos)) {
                            recordOvdLoadedOnce(currentDimension, pos);
                            published++;
                        }
                    } else {
                        notInjected++;
                        // OVD 冻结 / tryServeOvdLocal 已删：缺盘柱不在此泵
                    }
                }
            }
        }
        if (diskTried > 0 || published > 0) {
            DebugLogger.info(DebugLogger.LogType.NETWORK,
                    "[SHADOW_TRACK] OVD sweep disk={} publish={} window={} missing={} center=({},{}) ring={}..{} (dimension={})",
                    diskTried, published, windowCells, notInjected,
                    center.x, center.z, serverVD + 1, clientVD, currentDimension);
        }
    }

    /**
     * 真实客户端 unload 时调用（任意线程）：清光桥凭据；若影子仍在可见形状内且有数据，
     * 入重发队列。对齐原版语义——服务端仍把你算在 tracking 窗内就必须有数据；
     * 真实服 Forget 半径可能小于影子 vd，不能等 tracking 边沿。
     */
    public void onClientChunkUnloaded(ChunkPos pos) {
        if (pos == null || boundServer == null || currentDimension == null) {
            return;
        }
        long key = DimensionKey.key(currentDimension, pos.x, pos.z);
        boolean stillWanted = inVanillaVisibleShape(pos.x, pos.z) || inOvdWindow(pos.x, pos.z);
        if (boundServer.injectedChunk(currentDimension, pos.x, pos.z) == null) {
            outsideSinceMs.remove(key);
            return;
        }
        if (stillWanted) {
            // 客户端仍需要（窗内）：入重发队列，撤销离开标记
            outsideSinceMs.remove(key);
            redeliverQueue.add(pos);
            return;
        }
        // 客户端卸载且不在窗内：登记离开时刻，宽限后由 reclaimOutOfRetainSet 回收注入表。
        // 触发源是真实客户端 leave（真服 Forget），不按影子端自绘几何推断。
        outsideSinceMs.putIfAbsent(key, System.currentTimeMillis());
        ensureReclaimTimer();
    }

    /** 影子主循环：把「窗内但客户端已无」的柱本地重发（等价原版 trackChunk）。 */
    private void drainRedeliver(ShadowSeedServer shadow) {
        int sent = 0;
        ChunkPos pos;
        while (sent < MAX_REDELIVER_PER_PUMP && (pos = redeliverQueue.poll()) != null) {
            if (shadow == null || currentDimension == null) {
                continue;
            }
            if (shadow.injectedChunk(currentDimension, pos.x, pos.z) == null) {
                continue;
            }
            // 空气空壳占位柱不得 redeliver：它是光照齐套的临时占位，不是真实数据。
            if (shadow.isPlaceholder(currentDimension, pos.x, pos.z)) {
                continue;
            }
            if (!inVanillaVisibleShape(pos.x, pos.z) && !inOvdWindow(pos.x, pos.z)) {
                continue;
            }
            if (ShadowLightCompute.hasClientApplyEpoch(currentDimension, pos)) {
                continue; // 本会话已有落地凭据
            }
            if (ShadowLightCompute.isLocalRequeueInFlight(currentDimension, pos)) {
                continue; // 本地复用已排队（generated/inflightLight），不重发维持队列抖动
            }
            // 本会话网络路径已在途/已记账：redeliver 不得再记成缓存全命中
            if (ShadowLightCompute.wasNetworkIngress(currentDimension, pos)
                    && ShadowLightCompute.hasClientApplyEpoch(currentDimension, pos)) {
                continue;
            }
            boolean ovd = !inVanillaVisibleShape(pos.x, pos.z) && inOvdWindow(pos.x, pos.z);
            boolean ok = ovd
                    ? ShadowLightCompute.publishOvdCachedChunk(currentDimension, pos)
                    : ShadowLightCompute.publishCachedChunk(currentDimension, pos);
            if (ok) {
                if (ovd) {
                    recordOvdLoadedOnce(currentDimension, pos);
                }
                DebugLogger.info(DebugLogger.LogType.NETWORK,
                        "[SHADOW_TRACK] redeliver ({}, {}) -> publishCached ovd={} (dimension={})",
                        pos.x, pos.z, ovd, currentDimension);
                sent++;
            }
        }
    }

    private void applyState(ShadowSeedServer shadow, PendingState state) {
        if (!state.present() || state.dimension() == null) {
            return;
        }
        if (virtualPlayer == null) {
            ensureVirtualPlayer(shadow, state);
            if (virtualPlayer == null) {
                return;
            }
        }
        ServerLevel target = shadow.level(state.dimension());
        if (target == null) {
            io.github.limuqy.mc.hassium.Constants.LOG.warn(
                    "[SHADOW_TRACK] shadow level not assembled for {}; keep tracking {}",
                    state.dimension(), currentDimension);
            return;
        }
        boolean dimensionChanged = !state.dimension().equals(currentDimension);
        ChunkPos newChunk = new ChunkPos((int) state.x() >> 4, (int) state.z() >> 4);
        if (dimensionChanged) {
            io.github.limuqy.mc.hassium.Constants.LOG.info(
                    "[SHADOW_TRACK] reseating virtual player {} -> {}",
                    currentDimension, state.dimension());
            reseatVirtualPlayer(shadow, state);
            return;
        }
        ChunkPos lastChunk = virtualPlayer.chunkPosition();
        if (newChunk.x != lastChunk.x || newChunk.z != lastChunk.z) {
            // forge/neoforge：Entity.setPosRaw 在 isAddedToWorld 时会同步 level.getChunk(FULL)，
            // 目标柱未加载即抛 "Should always be able to create a chunk!"。P5 接管把 tracking
            // 压到 3x3 后跨 chunk 移动更容易踩空——跳过本拍，下一 pending 在柱就绪后重试。
            try {
#if MC_VER < MC_1_21_5
                virtualPlayer.absMoveTo(state.x(), state.y(), state.z(), state.yRot(), state.xRot());
#else
                // 1.21.5+：absMoveTo/moveTo(5 参) 移除，teleportTo(3 参)+旋转同语义（绝对位置设置）
                virtualPlayer.teleportTo(state.x(), state.y(), state.z());
                virtualPlayer.setYRot(state.yRot());
                virtualPlayer.setXRot(state.xRot());
#endif
            } catch (Throwable t) {
                DebugLogger.warn(DebugLogger.LogType.NETWORK,
                        "[SHADOW_TRACK] virtual player move deferred ({}, {}) targetChunk=({},{}) (dimension={})",
                        state.x(), state.z(), newChunk.x, newChunk.z, state.dimension(), t);
                return;
            }
            try {
                ShadowPlayerCompat.moveVirtualPlayer(virtualPlayer);
            } catch (Throwable t) {
                DebugLogger.warn(DebugLogger.LogType.ASYNC, "[SHADOW_TRACK] move failed", t);
            }
        }
    }

    /**
     * 切维后旧维度的选柱/在途 pull/boot 盘面全部作废，按新落点重新铺权威光盘。
     * 探针 trace 也按维度会话重置，避免返主世界读到进服那一轮的 stale overworld 计数。
     */
    private void onTrackingDimensionChanged(PendingState state) {
        sweepInFlight.clear();
        redeliverQueue.clear();
        ovdCounted.clear();
        ovdMissCounted.clear();
        ovdMissRetryAt.clear();
        lastOvdSweepMs = 0L;
        homeChunk = new ChunkPos((int) state.x() >> 4, (int) state.z() >> 4);
        lastChunkTickMs = 0L;
        appliedViewDistance = -1;
        SmokeChunkTrace.reset();
        io.github.limuqy.mc.hassium.cache.client.ChunkMeshCompileLog.reset();
        ShadowLightCompute.onClientDimensionChanged();
        ShadowPullClient.onClientDimensionChanged();
        io.github.limuqy.mc.hassium.network.ClientChunkHandler.onDimensionChanged();
    }

    /** teleport 失败时拆掉旧虚拟玩家，按 {@link #ensureVirtualPlayer} 在目标维度重坐。 */
    private void reseatVirtualPlayer(ShadowSeedServer shadow, PendingState state) {
        ServerPlayer old = virtualPlayer;
        virtualPlayer = null;
        currentDimension = null;
        if (old != null) {
            try {
                ShadowPlayerCompat.removeVirtualPlayer(shadow, old);
            } catch (Throwable t) {
                io.github.limuqy.mc.hassium.Constants.LOG.warn(
                        "[SHADOW_TRACK] old virtual player remove failed", t);
            }
        }
        onTrackingDimensionChanged(state);
        ensureVirtualPlayer(shadow, state);
    }

    /** 创建虚拟玩家并进入 tracking（仅影子线程；失败置 createFailed 会话降级）。 */
    private void ensureVirtualPlayer(ShadowSeedServer shadow, PendingState state) {
        ServerLevel level = shadow.level(state.dimension());
        if (level == null) {
            io.github.limuqy.mc.hassium.Constants.LOG.warn(
                    "[SHADOW_TRACK] shadow level not assembled for {}; session deferred", state.dimension());
            return;
        }
        io.github.limuqy.mc.hassium.Constants.LOG.info(
                "[SHADOW_TRACK] creating virtual player (dimension={}, serverRadius={}, effectiveClientVD={})",
                state.dimension(), serverViewDistance, effectiveClientVD);
        try {
            int viewDistance = resolveViewDistance();
            ShadowPlayerCompat.setChunkViewDistance(level, viewDistance);
            appliedViewDistance = viewDistance;
            net.minecraft.network.Connection connection = ShadowPlayerCompat.createConnectionStub();
            ServerPlayer player = ShadowPlayerCompat.createVirtualPlayer(shadow, level);
            // 先同步位置再加入世界（不走移动 API）：forge patch 的 Entity.setPosRaw 在
            // isAddedToWorld() && !isClientSide 时会同步 level.getChunk(FULL) 等待 chunk
            // future，影子端该柱未生成会死锁影子主循环；未加入世界时该分支跳过，
            // fabric 原版路径无此调用，行为不变。
            player.setPosRaw(state.x(), state.y(), state.z());
            player.setYRot(state.yRot());
            player.setXRot(state.xRot());
            ShadowPlayerCompat.placePlayer(shadow, connection, player);
            // 1.21.6+ placeNewPlayer 的 snapTo 会用共享出生角覆盖朝向（位置已由
            // adjustSpawnLocation 覆盖保持）。朝向不参与 tracking，仅作状态保真；
            // setYRot/setXRot 不触发 chunk 加载，加入世界后重申安全。
            player.setYRot(state.yRot());
            player.setXRot(state.xRot());
            try {
                ShadowPlayerCompat.moveVirtualPlayer(player);
            } catch (Throwable t) {
                io.github.limuqy.mc.hassium.Constants.LOG.warn(
                        "[SHADOW_TRACK] initial move failed", t);
            }
            virtualPlayer = player;
            currentDimension = state.dimension();
            // 基准点取虚拟玩家坐下的一刻：此后任何旅行都以它为轴心铺静态盘面
            homeChunk = new ChunkPos(player.chunkPosition().x, player.chunkPosition().z);
            DebugLogger.info(DebugLogger.LogType.ASYNC,
                    "[SHADOW_TRACK] virtual player tracking session started (dimension={}, "
                            + "viewDistance={}, serverRadius={}) S1-ticket-only",
                    currentDimension, viewDistance, serverViewDistance);
        } catch (Throwable t) {
            createFailed = true;
            logCreateFailed(t);
        }
    }

    private int resolveViewDistance() {
        // S1：影子票半径 = ServerVD（不加载完整 chebyshev 角区）
        int base = serverViewDistance > 0 ? serverViewDistance : DEFAULT_VIEW_DISTANCE;
        return Math.min(base, MAX_VIEW_DISTANCE);
    }

    private static boolean isOvdConfigActive() {
        HassiumConfigService cfg = HassiumConfigService.getInstance();
        return cfg.isClientCacheEnabled() && cfg.isViewDistanceExtensionEnabled();
    }

    /** 权威窗：原版可见形状（pull / bootGrid / sweep 唯一几何）。中心与真服 tracking 同轴。 */
    private boolean inVanillaVisibleShape(int x, int z) {
        if (serverViewDistance <= 0) {
            return true;
        }
        ChunkPos center = deliveryCenter();
        if (center == null) {
            return true;
        }
        return ChunkShapeCompat.contains(center.x, center.z, serverViewDistance, x, z);
    }

    /** 交付/trace 候选：权威可见形状 ∪ OVD（OVD 冻结时仅权威形状）。 */
    public static boolean isDeliverableToClient(int x, int z) {
        ShadowTrackingSession s = INSTANCE;
        if (s == null) {
            return true;
        }
        return s.inVanillaVisibleShape(x, z) || s.inOvdWindow(x, z);
    }

    /** OVD 窗：client chebyshev 窗内且权威窗外；本地源服务，禁止 pull。 */
    private boolean inOvdWindow(int x, int z) {
        if (serverViewDistance <= 0 || effectiveClientVD <= serverViewDistance) {
            return false;
        }
        ChunkPos center = deliveryCenter();
        if (center == null) {
            return false;
        }
        return ChunkShapeCompat.inOvdBand(center.x, center.z, serverViewDistance,
                effectiveClientVD, x, z);
    }

    /**
     * 服务端半径晚到/变化（登录包 chunkRadius、VD 切换）：影子主循环线程安全应用。
     * {@code setChunkViewDistance} 会走原版 {@code ChunkMap.setViewDistance} 的
     * 全 holder 重跟踪，晚应用可自愈已放置玩家的选柱集合。
     */
    private void applyViewDistanceIfChanged(ShadowSeedServer shadow) {
        int desired = resolveViewDistance();
        // P5 接管臂（实验性，默认关闭）：只钝化「ChunkMap 的 tracking 半径」，不钝化驱动的
        // 方形半径（驱动照旧拿 resolveViewDistance() 的真值）。这样压小 tracking 不再连坐
        // 饿死 OVD 环——整张方形仍由驱动提供，缺口若有就是驱动本身的问题。
        if (ShadowTicketDriver.trackingSelectionNeutralized()) {
            desired = ShadowTicketDriver.NEUTRALIZED_VIEW_DISTANCE;
        }
        if (desired == appliedViewDistance) {
            return;
        }
        ServerLevel level = shadow.level(currentDimension);
        if (level == null) {
            return;
        }
        appliedViewDistance = desired;
        try {
            ShadowPlayerCompat.setChunkViewDistance(level, desired);
            DebugLogger.info(DebugLogger.LogType.ASYNC,
                    "[SHADOW_TRACK] shadow view distance applied (viewDistance={}, serverRadius={})",
                    desired, serverViewDistance);
        } catch (Throwable t) {
            DebugLogger.warn(DebugLogger.LogType.ASYNC, "[SHADOW_TRACK] view distance apply failed", t);
        }
        // R4：VD 应用后按当前权威窗扫描注入表，窗外柱入 reclaim（原版票掉出→save→离内存）。
        enqueueOutOfWindowInjectedForReclaim(shadow);
    }

    /**
     * 原版生命周期（R4）：保留域外的注入柱登记离开时刻，由 reclaim 线程
     * {@link ShadowColumnStore#flushAndEvict}（type126 先落盘再摘表）。
     * <b>不得</b>在影子主循环内直接 unload（flush 会等主循环 → 自死锁）。
     */
    private void enqueueOutOfWindowInjectedForReclaim(ShadowSeedServer shadow) {
        if (shadow == null || virtualPlayer == null || currentDimension == null
                || serverViewDistance <= 0) {
            return;
        }
        ChunkPos center = deliveryCenter();
        if (center == null) {
            return;
        }
        int enrolled = 0;
        long now = System.currentTimeMillis();
        for (Long key : shadow.injectedKeys()) {
            if (key == null || !currentDimension.equals(DimensionKey.dimensionOf(key))) {
                continue;
            }
            int x = DimensionKey.chunkXOf(key);
            int z = DimensionKey.chunkZOf(key);
            if (ChunkShapeCompat.contains(center.x, center.z, serverViewDistance, x, z)
                    || inOvdWindow(x, z)) {
                continue;
            }
            if (outsideSinceMs.putIfAbsent(key, now) == null) {
                enrolled++;
            }
        }
        if (enrolled > 0) {
            ensureReclaimTimer();
            DebugLogger.info(DebugLogger.LogType.NETWORK,
                    "[SHADOW_TRACK] enqueue out-of-window reclaim count={} center=({},{}) vd={} (dimension={})",
                    enrolled, center.x, center.z, serverViewDistance, currentDimension);
        }
    }

    /** OVD 环带扫描（冻结：仅统计，不 pull / 不 tryServeOvdLocal）。 */


    /**
     * 本地生成优先（§6 供给路径）：门控开时 boot/sweep 不对无基线柱发
     * authoritative-full，交给影子 tracking 触发 vanilla worldgen（真实种子）。
     * 有基线柱仍走 compare-pull。
     */
    private static boolean preferLocalGeneration() {
        return SeedGenExecutor.getInstance().isGenerationGateOpen();
    }

    /** OVD 已加载计数（会话内按坐标去重，防 materialize+sweep 双计）。 */
    private void recordOvdLoadedOnce(String dimension, ChunkPos pos) {
        long key = io.github.limuqy.mc.hassium.utils.DimensionKey.key(dimension, pos.x, pos.z);
        if (ovdCounted.add(key)) {
            io.github.limuqy.mc.hassium.metrics.NetworkStats.recordOvdLoaded();
        }
    }

    private void recordOvdMissOnce(String dimension, ChunkPos pos) {
        long key = io.github.limuqy.mc.hassium.utils.DimensionKey.key(dimension, pos.x, pos.z);
        if (ovdMissCounted.add(key)) {
            io.github.limuqy.mc.hassium.metrics.NetworkStats.recordOvdMiss();
        }
    }

    /** 统一 pull 分组（§3.2）：经 ShadowChunkAcquire；无权威让位门。 */
    private void emitPullGroups(java.util.List<ChunkPos> withBaseline, java.util.List<ChunkPos> withoutBaseline) {
        ShadowChunkAcquire.emitPullBatches(currentDimension, withBaseline, withoutBaseline);
    }

    /**
     * boot / 悬置选柱 / 形状扫描 / Provider 共用在途。{@code true} = 本柱尚未在途。
     */
    private boolean markPullInFlight(String dimension, ChunkPos pos, long nowMs) {
        if (pos == null) {
            return false;
        }
        String dim = dimension != null ? dimension : currentDimension;
        if (dim == null) {
            return false;
        }
        return sweepInFlight.putIfAbsent(DimensionKey.key(dim, pos.x, pos.z), nowMs) == null;
    }

    public boolean markPullInFlightForAcquire(String dimension, ChunkPos pos, long nowMs) {
        return markPullInFlight(dimension, pos, nowMs);
    }

    public void clearPullInFlight(String dimension, ChunkPos pos) {
        if (pos == null) {
            return;
        }
        String dim = dimension != null ? dimension : currentDimension;
        if (dim == null) {
            return;
        }
        sweepInFlight.remove(DimensionKey.key(dim, pos.x, pos.z));
    }

    public boolean isPullInFlight(String dimension, ChunkPos pos) {
        if (pos == null) {
            return false;
        }
        String dim = dimension != null ? dimension : currentDimension;
        if (dim == null) {
            return false;
        }
        return sweepInFlight.containsKey(DimensionKey.key(dim, pos.x, pos.z));
    }


    /**
     * 原版链产出桥 = **tracking 进范围边沿**（§6.0）：1.20.1 {@code playerLoadedChunk}
     * HEAD；1.21+ {@code ChunkMap.onChunkReadyToSend}（该版已无 playerLoadedChunk，
     * 且影子主循环不跑 {@code sendNextChunks}）。
     * 新柱必须 dirty 落盘（没落就是缓存基线丢失）。权威窗内本地 worldgen：先
     * compare-pull，UNCHANGED / DELTA / FULL 落地后再算光，避免权威微变二次算光。
     * 磁盘/注入基线不得再 worldgen。OVD 无权威比对，persist 后 publishOvd。
     * 影子主循环线程调用。
     */
    public void onChunkMaterialized(String dimension, ChunkPos pos,
                                    net.minecraft.world.level.chunk.LevelChunk chunk) {
        ShadowSeedServer shadow = boundServer;
        if (shadow == null) {
            // onChunkReadyToSend 可能在 pollTask 里早于本圈 consume 绑定会话
            shadow = ShadowServerRegistry.getInstance().get();
        }
        if (shadow == null || dimension == null || pos == null || chunk == null) {
            return;
        }
        boolean alreadyMaterialized = shadow.injectedChunk(dimension, pos.x, pos.z) != null
                && !shadow.isPlaceholder(dimension, pos.x, pos.z);
        // 磁盘命中柱的 hash 在 scheduleChunkLoad 读盘时由 MixinRegionFile 回填；
        // 生成柱无 hash → dirty（saveAll 落盘）。1.20.1 无 getPersistedStatus，按 hash 判别。
        boolean diskHit = io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageHashes
                .get(dimension, pos) != null;
        // 首注入且无盘 hash = 本会话 vanilla worldgen 本地生成（门控开路径）；
        // 网络 FULL 经 injectChunk 入表后再走本桥时 alreadyMaterialized=true，不会误计。
        boolean localWorldgen = SeedGenExecutor.isFreshLocalWorldgen(alreadyMaterialized, diskHit);
        if (!alreadyMaterialized) {
            shadow.injectLoadedChunk(dimension, pos, chunk,
                    SeedGenExecutor.persistAsDirty(diskHit));
        }
        if (localWorldgen) {
            io.github.limuqy.mc.hassium.shadow.light.SmokeChunkTrace
                    .recordWorldgenEnd(dimension, pos);
            io.github.limuqy.mc.hassium.metrics.NetworkStats.recordLocallyGeneratedChunk(
                    io.github.limuqy.mc.hassium.metrics.NetworkStats.ESTIMATED_CHUNK_BYTES);
            DebugLogger.info(DebugLogger.LogType.NETWORK,
                    "[SHADOW_TRACK] local worldgen materialized ({}, {}) (dimension={})",
                    pos.x, pos.z, dimension);
        }
        // 影子 ticket 可能物化原版可见形状外的角区柱 / OVD 环带。
        // OVD 窗：客户端 ClientChunkCache 重连后是空的——必须重新 publish。
        // 不得复用权威路径的「已有 networkIngress / applyEpoch 则跳过」门：
        // 那些凭据属于上一世界/上一会话，留着会让 R2 环带静默空洞（G1 ovdLoaded=0）。
        if (!inVanillaVisibleShape(pos.x, pos.z)) {
            if (inOvdWindow(pos.x, pos.z)) {
                boolean published = ShadowLightCompute.publishOvdCachedChunk(dimension, pos);
                if (published) {
                    recordOvdLoadedOnce(dimension, pos);
                }
                DebugLogger.info(DebugLogger.LogType.NETWORK,
                        "[SHADOW_TRACK] OVD materialized ({}, {}) -> publish {} (dimension={})",
                        pos.x, pos.z, published, dimension);
            }
            return;
        }
        if (SeedGenExecutor.deferLightUntilAuthority(localWorldgen, true)) {
            DebugLogger.info(DebugLogger.LogType.NETWORK,
                    "[SHADOW_TRACK] local worldgen ({}, {}) persist then compare-before-light (dimension={})",
                    pos.x, pos.z, dimension);
            requestPullEligible(dimension, pos, false);
            return;
        }
        // 空占位/无盘命中的首注入：没有可发布的基线，先 pull 真实数据（禁止把空气柱推给客户端）
        boolean hasLocalBaseline = alreadyMaterialized
                || diskHit
                || ShadowLightCompute.hasLocalPullBaseline(dimension, pos);
        if (!hasLocalBaseline) {
            DebugLogger.info(DebugLogger.LogType.NETWORK,
                    "[SHADOW_TRACK] materialized ({}, {}) no-local-baseline -> pull (dimension={})",
                    pos.x, pos.z, dimension);
            requestPullEligible(dimension, pos, false);
            return;
        }
        // 本会话客户端尚未落地（重连后新 ClientChunkCache）：必须重新 publish。
        // 不得因 wasNetworkIngress（上一会话/generated 残留）改走 pull 而丢交付——
        // 实测 park 复用时 R1 1529 柱只回放 775，移动新区也不出现。
        if (alreadyMaterialized && !ShadowLightCompute.hasClientApplyEpoch(dimension, pos)) {
            boolean published = ShadowLightCompute.publishCachedChunk(dimension, pos);
            if (!published) {
                DebugLogger.info(DebugLogger.LogType.NETWORK,
                        "[SHADOW_TRACK] materialized ({}, {}) redeliver-publish-failed -> pull (dimension={})",
                        pos.x, pos.z, dimension);
                ShadowLightCompute.clearRequestMiss(dimension, pos);
                requestPullEligible(dimension, pos, true);
                return;
            }
            if (ShadowLightCompute.tryRequestMiss(dimension, pos)) {
                requestPullEligible(dimension, pos, false);
            }
            return;
        }
        // 本会话网络全量已注入/落地：交付由 SERVER_PUSH/REMOTE_PULL 路径完成。
        // 再 publishCachedChunk 会把同一柱改记成 MEMORY_CACHE 假全命中（R1 1219 假命中根因）。
        if (alreadyMaterialized
                && (ShadowLightCompute.wasNetworkIngress(dimension, pos)
                    || ShadowLightCompute.hasClientApplyEpoch(dimension, pos))) {
            if (ShadowLightCompute.tryRequestMiss(dimension, pos)) {
                requestPullEligible(dimension, pos, false);
            }
            return;
        }
        // 进边沿必交付：盘上命中 / 上一会话缓存。本地 worldgen 已在上面 compare-before-light 返回。
        boolean published = ShadowLightCompute.publishCachedChunk(dimension, pos, localWorldgen, false);
        if (!published) {
            DebugLogger.info(DebugLogger.LogType.NETWORK,
                    "[SHADOW_TRACK] materialized ({}, {}) publish-failed -> pull (dimension={})",
                    pos.x, pos.z, dimension);
            ShadowLightCompute.clearRequestMiss(dimension, pos);
            requestPullEligible(dimension, pos, true);
            return;
        }
        DebugLogger.info(DebugLogger.LogType.NETWORK,
                "[SHADOW_TRACK] materialized ({}, {}) alreadyMaterialized={} -> publishCached (dimension={})",
                pos.x, pos.z, alreadyMaterialized, dimension);
        // 已本地交付后，可选对真实服 compare 保新鲜；防抖只作用于网络请求
        if (ShadowLightCompute.tryRequestMiss(dimension, pos)) {
            requestPullEligible(dimension, pos, false);
        }
    }

    /**
     * R4：仅在权威窗内且会话就绪时向真服 pull。
     *
     * @param authoritative true = 无基线/发布失败后的权威 FULL；false = 有基线 compare（FULL 优先）
     */
    private boolean requestPullEligible(String dimension, ChunkPos pos, boolean authoritative) {
        if (pos == null || dimension == null) {
            return false;
        }
        if (!isAuthorityPullEligible(pos.x, pos.z)) {
            DebugLogger.info(DebugLogger.LogType.NETWORK,
                    "[SHADOW_TRACK] skip pull outside authority window ({}, {}) dim={} auth={}",
                    pos.x, pos.z, dimension, authoritative);
            return false;
        }
        if (!markPullInFlight(dimension, pos, System.currentTimeMillis())) {
            return false;
        }
        if (!authoritative && ShadowLightCompute.hasLocalPullBaseline(dimension, pos)) {
            ShadowPullClient.requestFull(dimension, java.util.List.of(pos));
        } else {
            ShadowPullClient.requestAuthoritativeFull(dimension, java.util.List.of(pos));
        }
        return true;
    }

    /**
     * 网络全量已由 {@code enqueueInjectedForLight} 入 generated（保留网络来源）。
     * 不得在此清 {@link #sweepInFlight}：入队瞬间注入表对扫描线程还不可见，
     * 清在途会让下一拍 sweep 把同一柱再请求一遍。在途由 {@link #onPullInjected}、
     * 扫描遇到已注入柱、或 60s 超时清除。<b>不得</b>再 publishCachedChunk。
     */
    public void onNetworkChunkQueued(String dimension, ChunkPos pos) {
        if (pos == null || dimension == null) {
            return;
        }
        // inflight 保留到注入可见；见方法 javadoc
    }

    /**
     * pull FULL 注入后触发交付：数据已在影子表，走与 tracking 进边沿相同的
     * publish 路径。`completeSuspendedLoad` 可能无悬置 future（holder 已完成），
     * `playerLoadedChunk` 桥不会触发时靠本路径兜底。
     * 任意线程可调（pull 响应落地路径在主线程）。
     */
    public void onPullInjected(String dimension, ChunkPos pos) {
        if (boundServer == null || dimension == null || pos == null) {
            return;
        }
        // 形状扫描在途登记清除：柱已注入，下轮扫描不会再拉
        sweepInFlight.remove(DimensionKey.key(dimension, pos.x, pos.z));
        if (!inVanillaVisibleShape(pos.x, pos.z)) {
            return;
        }
        DebugLogger.info(DebugLogger.LogType.NETWORK,
                "[SHADOW_TRACK] pull-injected ({}, {}) -> publishCached (dimension={})",
                pos.x, pos.z, dimension);
        if (!ShadowLightCompute.publishCachedChunk(dimension, pos)) {
            // 注入表有柱但不可物化（异常）：再拉一次权威全量
            ShadowLightCompute.clearRequestMiss(dimension, pos);
            if (markPullInFlight(dimension, pos, System.currentTimeMillis())) {
                ShadowPullClient.requestAuthoritativeFull(dimension, java.util.List.of(pos));
            }
        }
    }

    private static void logCreateFailed(Throwable t) {
        io.github.limuqy.mc.hassium.Constants.LOG.warn(
                "Hassium: shadow virtual player tracking session failed; "
                        + "falling back to server-push selection", t);
    }

    /** 断连/关停/新连接清理：清会话引用；旧虚拟玩家的 vanilla 登出式移除
     *  （实体/玩家票/ChunkMap 簿记/playerdata）交由影子主循环线程执行——
     *  本方法在客户端主线程调用，与影子簿记并发移除会自毁主循环。
     *  R2 复用 park 实例时若不移出，旧玩家仍持有全部 tracking → 新玩家无 in-range
     *  转换 → 桥不触发 → 真实客户端新世界黑洞。任意线程可调。 */
    public static void reset() {
        ShadowTrackingSession s = INSTANCE;
        ShadowSeedServer server = s.boundServer;
        ServerPlayer player = s.virtualPlayer;
        if (server != null && player != null) {
            s.pendingRemoval = new PendingRemoval(server, player);
        }
        s.pending = null;
        s.serverViewDistance = -1;
        s.effectiveClientVD = -1;
        s.appliedViewDistance = -1;
        // boundServer 置空后，影子主循环的「实例变化」分支（consumeOnShadowLoop 顶部）
        // 会在主循环线程上清掉 virtualPlayer/currentDimension —— 不得在此（客户端主线程
        // onLogin）直接置空：主循环簿记/选柱扫描途中读到「玩家非空、维度已空」的半清状态
        // 会 NPE（1.21.11 fabric R2 实证 DimensionKey.key(null,…) → [SHADOW_LOOP] crashed、
        // R2 applied=0）。其余字段保持同步写入：serverViewDistance 等由随后的 R2 视距更新
        // 覆盖，异步清理会把它抹掉（R2 OVD 窗不开 → ovdLoaded=0）。
        s.boundServer = null;
        s.createFailed = false;
        s.sweepInFlight.clear();
        s.redeliverQueue.clear();
        s.outsideSinceMs.clear();
        // Provider 在途 future / 悬置 load 必须随会话清空：R2 join 旧 future 会让
        // scheduleChunkLoad 悬置 holder 永不完成（R2 landed 只有 71 的根因之一）。
        VanillaAlignedChunkProvider.clearAll();
        ShadowChunkMapCompat.clearSuspendedLoads();
        io.github.limuqy.mc.hassium.network.ShadowPullClient.clearPullFailure(null, null);
        ShadowTicketDriver.requestClear();
        s.ovdCounted.clear();
        s.ovdMissCounted.clear();
        s.ovdMissRetryAt.clear();
        s.lastOvdSweepMs = 0;
        s.homeChunk = null;
        s.lastChunkTickMs = 0;
    }

    /** 影子主循环执行 vanilla 登出式移除；实例已被关停/重建则跳过（旧 world 随实例丢弃）。 */
    private void drainPendingRemoval() {
        PendingRemoval removal = pendingRemoval;
        if (removal == null) {
            return;
        }
        pendingRemoval = null;
        if (ShadowServerRegistry.getInstance().get() != removal.server()) {
            return;
        }
        try {
            ShadowPlayerCompat.removeVirtualPlayer(removal.server(), removal.player());
            io.github.limuqy.mc.hassium.Constants.LOG.info(
                    "[SHADOW_TRACK] virtual player removed from shadow world");
        } catch (Throwable t) {
            io.github.limuqy.mc.hassium.Constants.LOG.warn(
                    "[SHADOW_TRACK] virtual player removal failed", t);
        }
    }
}
