package io.github.limuqy.mc.hassium.shadow.track;

import io.github.limuqy.mc.hassium.platform.client.ShadowClientApi;
import io.github.limuqy.mc.hassium.platform.client.ShadowClientBridge;
import io.github.limuqy.mc.hassium.platform.client.TraceOrigin;

import io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer;
import io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry;
import io.github.limuqy.mc.hassium.shadow.server.ShadowWorldgenExecutor;
import io.github.limuqy.mc.hassium.shadow.server.SeedGenLevelCompat;
import io.github.limuqy.mc.hassium.shadow.server.SeedGenExecutor;
import io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate;
import io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute;
import io.github.limuqy.mc.hassium.shadow.light.SmokeChunkTrace;
import io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageHashes;

import io.github.limuqy.mc.hassium.compat.ChunkShapeCompat;
import io.github.limuqy.mc.hassium.compat.LevelCompat;
import io.github.limuqy.mc.hassium.compat.ShadowChunkMapCompat;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.protocol.ShadowPullClient;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DimensionKey;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * 影子 tracking 会话：真实客户端位置/维度发布到影子主循环，由显式波前选柱并发出
 * Compare+Pull。影子端不创建玩家实体，选柱不再依赖 ChunkMap 玩家票。
 * <p>
 * 线程纪律：真实客户端 tick 仅替换 volatile 待同步状态；影子主循环消费状态、推进
 * chunk 系统簿记、生成与 pull。
 */
public final class ShadowTrackingSession {

    private static ShadowClientApi client() {
        return ShadowClientBridge.get();
    }

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
    /**
     * A1-② 进服/移动时权威窗 acquire 驱动间隔（非 WINDOW_PUMP：只负责「无 material 则 pull」，
     * 不做 epoch 补扫、不交付）。真服 pull_mode 下无原版 trackChunk，必须有此驱动。
     */
    private static final long AUTHORITY_ACQUIRE_INTERVAL_MS = 200L;

    /**
     * 权威窗 acquire 时，对「盘上有柱但内存没柱」的柱先读盘物化，再发 compare。
     * <p>
     * 目的：只有物化柱才有**逐段/平面**基线，服务端才可能判 BLOCKS 增量；缺这一步
     * 统一 Compare+Pull 只能带柱级 hash → 每个差异段判整段 FULL → 差异段多到 75% 整柱回退。
     * 1.20.1 靠 {@code ChunkMap.scheduleChunkLoad}（每轮 810~1558 次）天然做到；1.21.1 上
     * 该钩子每轮只触发 76~88 次，必须由本驱动补上。
     * <p>
     * 置 {@code false} 可退回「不物化直接 compare」（= 修复前行为）做 A/B 对照。
     */
    private static final boolean MATERIALIZE_BEFORE_COMPARE = true;
    /** 每拍最多向 Provider 提交 acquire 的格数（在途上限另见 Provider）。 */
    /**
     * 权威 acquire 每轮预算 = 客户端「缓存读取生产」配额 × 每轮客户端 tick 数。
     * <p>
     * <b>2026-09-19（用户拍板）</b>：取消原固定值 {@code AUTHORITY_ACQUIRE_BUDGET = 64}
     * （原话「可以顺便取消 AUTHORITY_ACQUIRE_BUDGET，改为 maxChunksPerFrame × 5(或4)」）。
     * 理由：固定 64/轮与客户端实际消费能力脱钩——客户端读/注入慢时 acquire 白抢（抢来也进不了
     * 客户端），快时又喂不上。现与 {@code chunk.maxChunksPerFrame}（每帧缓存读取生产配额，
     * 见 {@code ClientMainThreadBudget}）绑定：驱动间隔 {@code AUTHORITY_ACQUIRE_INTERVAL_MS}
     * = 200ms ≈ 4~5 个客户端 tick，故乘 {@link #ACQUIRE_BUDGET_TICKS_PER_ROUND}。
     * <p>
     * 调参只需改 {@link #ACQUIRE_BUDGET_TICKS_PER_ROUND}（用户拍板取 4）。
     */
    private static final int ACQUIRE_BUDGET_TICKS_PER_ROUND = 4;

    /** 每轮 acquire 预算（配置不可用时退回旧值 64）。 */
    private static int authorityAcquireBudget() {
        int perFrame;
        try {
            perFrame = HassiumConfigService.getInstance().getMaxChunksPerFrame();
        } catch (Throwable t) {
            return 64;
        }
        return Math.max(1, perFrame) * ACQUIRE_BUDGET_TICKS_PER_ROUND;
    }

    private long lastAuthorityAcquireMs;

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
    private String currentDimension;
    private ChunkPos trackingCenter;
    private long lastChunkTickMs;

    /** 会话落位基准点：首个稳定座位（虚拟玩家放置瞬间的 chunk）。 */
    private ChunkPos homeChunk;

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

    /** 当前影子选柱中心；由客户端发布状态在影子主循环更新。 */
    public ChunkPos trackingCenter() {
        return trackingCenter;
    }

    /**
     * **计算/拉取域**（R4 的 pull 门 + Provider 窗外丢弃 + 齐套门等待窗 + 注入表保留域）：
     * 权威形状（{@code serverVD}）的**切比雪夫膨胀**，膨胀半径 = {@link #lightHaloRadius()}。
     * <p>
     * <b>为什么是膨胀而不是 {@code contains(serverVD + R)}</b>（2026-09-19 S3 修正）：
     * 光环的**唯一职责**是让权威柱的 3×3 全部在场（原版 {@code ChunkStatus.LIGHT} range=1 的
     * 依赖语义）。用「形状环」近似会在形状切角处漏掉邻柱——实测 VD=10/16/20 分别有
     * **8/16/20 个权威柱**的 3×3 戳出域外；用膨胀形式则为 **0**。两者最大切比雪夫半径相同
     * （{@code VD+R+1}），故都贴满服务端签发上限，不增加拒绝面。
     * <p>
     * 会话未就绪（VD 未知 / 无中心）时返回 {@code false}——保守：此时拉不到，也不该判 clean。
     * 「未知时放行（等）」的语义在 {@link #isInNeighborhoodWindow} 里单独处理。
     */
    public boolean isInComputeDomain(int x, int z) {
        if (serverViewDistance <= 0) {
            return false;
        }
        ChunkPos center = deliveryCenter();
        if (center == null) {
            return false;
        }
        return ChunkShapeCompat.containsDilated(center.x, center.z, serverViewDistance,
                lightHaloRadius(), x, z);
    }

    /** 计算/拉取域枚举用的切比雪夫外接盒半径（方形循环半径）。 */
    public int computeDomainBoxRadius() {
        return ChunkShapeCompat.dilatedBoundingRadius(serverViewDistance, lightHaloRadius());
    }

    /**
     * 光照光环半径（环）：配置 {@code chunk.lightHaloRadius}，钳到
     * {@code [0, ShadowPullRadii.MAX_LIGHT_HALO_RADIUS]}（上限 = 服务端签发余量
     * {@code AUTHORITY_MARGIN − 1}；膨胀形状的最大切比雪夫半径 = {@code serverVD + R + 1}，
     * 超了外环会被服务端 RANGE 拒）。
     */
    public static int lightHaloRadius() {
        int r;
        try {
            r = HassiumConfigService.getInstance().getConfig().chunk().lightHaloRadius();
        } catch (Throwable t) {
            return io.github.limuqy.mc.hassium.protocol.ShadowPullRadii.LIGHT_HALO_RADIUS;
        }
        return Math.max(0, Math.min(r, io.github.limuqy.mc.hassium.protocol.ShadowPullRadii.MAX_LIGHT_HALO_RADIUS));
    }

    /**
     * 计算/拉取域半径的**口径说明**（不再是一个「半径」，保留名字只为诊断/日志可读）：
     * 返回 {@code serverVD + lightHaloRadius()}。
     * <p>
     * 注意它**不等于**任何几何谓词的半径——真正的谓词是
     * {@link #isInComputeDomain}（形状的切比雪夫膨胀），枚举盒是
     * {@link #computeDomainBoxRadius()}。这里只用于日志文案。
     */
    public int authorityRange() {
        return serverViewDistance + lightHaloRadius();
    }

    /**
     * 齐套门「该邻柱在等待窗内吗」判据：**计算/拉取域 ∪ OVD 环带**。
     * <p>
     * 窗内 = 我们真的会把它拉上来（或本地源能供）→ 值得等；窗外 = 永远不会到，
     * 等待只会白等 2~4s 再降级放行（结果一样，但把该柱误判成「非权威」）。
     * <p>
     * <b>2026-09-19（S3）修正</b>：原判据用 `isDeliverableToClient`（当时 = serverVD + 4），
     * 而 acquire 只填到 serverVD —— 「交付域」不是「拉取承诺」，用交付域当等待窗是 P3 的
     * 本体。现统一为 {@link #isInComputeDomain}。
     * <p>
     * VD / 中心未知时**保守放行**（视为窗内 → 等）：此时既拉不到也不该把柱判成 clean。
     */
    public boolean isInNeighborhoodWindow(int x, int z) {
        if (serverViewDistance <= 0) {
            return true;
        }
        ChunkPos center = deliveryCenter();
        if (center == null) {
            return true;
        }
        return isInComputeDomain(x, z) || inOvdWindow(x, z);
    }

    /** 距离平方（排序键；坐标差用 long 防溢出）。 */
    static long distanceSquared(int x, int z, int cx, int cz) {
        long dx = (long) x - cx;
        long dz = (long) z - cz;
        return dx * dx + dz * dz;
    }

    /**
     * 3×3 域 id：把平面按 {@code floorDiv(·, 3)} **平铺**成互不重叠的 3×3 域。
     * <p>
     * 选平铺（而非「以每个待办柱为中心的 3×3」）的两个理由：
     * ① 平铺天然无重叠 → §1.5 的「跨域重叠去重」变成结构性成立，不需要去重集合；
     * ② 锚点固定在世界原点，不随玩家移动漂移 → 域分组在移动中稳定，不会每走一格重排。
     */
    static long domainId(int x, int z) {
        return ((long) Math.floorDiv(x, 3) << 32) ^ (Math.floorDiv(z, 3) & 0xFFFFFFFFL);
    }

    /**
     * 【S4】3×3 域分组比较器：**相同域为一组**，组内按距离升序；**组间按各域最近柱距离**升序。
     * <p>
     * 目的（用户拍板）：「主要让服务端不会东投一柱，西投一柱」——同域柱连成一片投递。
     * 输入 {@code all} 必须是**待办集合**（每轮按当前中心重扫得到），不是「扩散球」：
     * 见 handoff §4 风险 a。组的「最近柱距离」取自 {@code all} 本身，故比较器只对该集合有效。
     */
    static java.util.Comparator<ChunkPos> domainComparator(java.util.Collection<ChunkPos> all,
                                                          int cx, int cz) {
        java.util.Map<Long, Long> domainNearest = new java.util.HashMap<>(all.size() * 2);
        for (ChunkPos p : all) {
            domainNearest.merge(domainId(p.x, p.z), distanceSquared(p.x, p.z, cx, cz), Math::min);
        }
        return java.util.Comparator
                .comparingLong((ChunkPos p) -> domainNearest.getOrDefault(domainId(p.x, p.z), Long.MAX_VALUE))
                .thenComparingLong(p -> distanceSquared(p.x, p.z, cx, cz));
    }

    /** 【S4】按 {@link #domainComparator} 就地排序待办柱列表。 */
    static void sortByDomain(java.util.List<ChunkPos> positions, int cx, int cz) {
        if (positions == null || positions.size() < 2) {
            return;
        }
        positions.sort(domainComparator(positions, cx, cz));
    }

    /** 交付/trace/pull 几何中心：真实玩家优先；无客户端时退回会话中心。 */
    public static ChunkPos deliveryCenter() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.player != null) {
                return new ChunkPos(mc.player.getBlockX() >> 4, mc.player.getBlockZ() >> 4);
            }
        } catch (Throwable ignored) {
            // 无客户端环境：退回会话中心
        }
        return INSTANCE.trackingCenter;
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

    /** 诊断：真实客户端状态是否已由影子循环消费。 */
    public boolean hasTrackingState() {
        return trackingCenter != null;
    }

    /** 诊断：当前跟踪维度（主循环心跳用）。 */
    public String currentDimension() {
        return currentDimension;
    }

    /**
     * 影子主循环每轮调用：消费待同步状态 + 节流驱动 chunk 系统簿记 + 分批发 pull 请求。
     * 仅影子主循环线程可调。
     */
    public void consumeOnShadowLoop() {
        ShadowSeedServer shadow = ShadowServerRegistry.getInstance().get();
        if (shadow != boundServer) {
            boundServer = shadow;
            currentDimension = null;
            trackingCenter = null;
            sweepInFlight.clear();
            lastChunkTickMs = 0;
        }
        if (shadow == null) {
            return;
        }
        PendingState state = pending;
        pending = null;
        if (state != null) {
            applyState(shadow, state);
        }
        if (currentDimension == null || trackingCenter == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastChunkTickMs >= CHUNK_TICK_INTERVAL_MS) {
            lastChunkTickMs = now;
            ServerLevel level = shadow.level(currentDimension);
            if (level != null && !ShadowWorldgenExecutor.isTerminated()
                    && !io.github.limuqy.mc.hassium.compat.ShadowServerCompat.isSharedIoPoolShutdown()) {
                long deadlineNanos = System.nanoTime() + CHUNK_TICK_BUDGET_NANOS;
                try {
                    // 【2026-09-22】tick 会经 ChunkMap.tick → poiManager.tick 写 POI 的非并发结构
                    // （见 ShadowPoiGate 类注释）。解码/序列化现已**调度到本主循环线程**执行，
                    // 与本 tick 天然同线程 ⇒ 结构性串行，不再需要「拿不到闸就跳过本拍」。
                    io.github.limuqy.mc.hassium.compat.ShadowPoiGate.runIfIdle(() ->
                            ((net.minecraft.server.level.ServerChunkCache) level.getChunkSource()).tick(
                                    () -> System.nanoTime() < deadlineNanos, false));
                } catch (java.util.concurrent.RejectedExecutionException ignored) {
                    // 关停窗口：worldgen 池已拒绝新任务
                } catch (Throwable t) {
                    DebugLogger.warn(DebugLogger.LogType.ASYNC,
                            "[SHADOW_TRACK] chunk system tick failed", t);
                }
            }
        }
        sweepOvdRing(shadow, now);
        drainAuthorityAcquires(shadow, now);
        VanillaAlignedChunkProvider.drainPendingPulls();
    }

    /**
     * A1-② 驱动：权威窗内「无 inject material」的柱向真服 acquire/pull（近→远）。
     * 有 material 的柱不在本方法交付（由 materialize/pull 响应/官方桥交付）。
     * <p>
     * <b>2026-09-19（S3/S4）</b>：
     * <ul>
     *   <li>半径 = {@link #authorityRange()}（权威域 + 光照光环）——光环柱一并拉上来算光，
     *       让权威边界柱的 3×3 齐套（原实现只填到 serverVD）。</li>
     *   <li>排序改为 {@link #sortByDomain}（3×3 域分组：同域连片投递，组间按最近柱距离）。</li>
     *   <li><b>每轮按当前中心重扫整个窗口</b>——这是「待办集合」而非「扩散球」，
     *       玩家连续移动/TP/切维度都天然免疫（handoff §4 风险 a）。不得改成固定种子扩散。</li>
     * </ul>
     */
    private void drainAuthorityAcquires(ShadowSeedServer shadow, long nowMs) {
        if (shadow == null || trackingCenter == null || currentDimension == null
                || serverViewDistance <= 0) {
            return;
        }
        if (nowMs - lastAuthorityAcquireMs < AUTHORITY_ACQUIRE_INTERVAL_MS) {
            return;
        }
        lastAuthorityAcquireMs = nowMs;
        ChunkPos center = trackingCenter;
        // 枚举盒 = 膨胀形状的切比雪夫外接盒（原版 updatePlayerStatus 同款「方形循环 + 形状过滤」）：
        // 轴向 |d|=VD+1 的柱属于权威形状，但 inOvdBand 把它们排除在环带之外 → 必须在本驱动枚举，
        // 否则两侧都不交付。实测 serverVD=10/clientVD=16：漏掉 44 柱 → 客户端在权威窗外一圈出现封闭虚空。
        int box = computeDomainBoxRadius();
        java.util.List<ChunkPos> enter = new java.util.ArrayList<>();
        for (int x = center.x - box; x <= center.x + box; x++) {
            for (int z = center.z - box; z <= center.z + box; z++) {
                if (!isInComputeDomain(x, z)) {
                    continue;
                }
                enter.add(new ChunkPos(x, z));
            }
        }
        final int cx = center.x;
        final int cz = center.z;
        sortByDomain(enter, cx, cz);
        final int budget = authorityAcquireBudget();
        int submitted = 0;
        int skippedMaterial = 0;
        int published = 0;
        // 【预算语义（2026-09-19 用户口径：「不要按完成轮次来算预算，每轮都从0开始」）】
        // 预算**只约束本轮「新 acquire」**，每轮从 0 计。两条与旧实现的关键差异：
        // ① 不再用 `submitted + published`：`published` 是**交付**计数，会让近处已 material 柱的
        //    交付吃光预算，把远处非 material 柱的 acquire 饿死——实测 `submitted=0` 占 181/206 轮，
        //    1341 个远处柱一轮都没被 acquire → 它们的邻柱永不到齐 → 齐套门降级（降级 promote 主因）。
        // ② 不再 `break`：预算用尽后仍走完整个列表（material 分支的交付/compare 照常），
        //    只对「新 acquire」封顶。同组（3×3 域）要发完：组内超预算也把本组 acquire 完，
        //    避免半组分裂（半组会让该组各柱都缺几个邻柱 → 同样降级）。
        long lastAcquireDomain = Long.MIN_VALUE;
        int materialized = 0;
        for (ChunkPos pos : enter) {
            net.minecraft.world.level.chunk.LevelChunk injected =
                    shadow.injectedChunk(currentDimension, pos.x, pos.z);
            // 【先物化、再比较（2026-09-20）】——恢复方块级 BLOCKS 增量的关键一步。
            // 1.20.1 上权威窗柱在 compare 前就被物化了（`ChunkMap.scheduleChunkLoad` 每轮
            // 810~1558 次 → `accountLightAtScheduleLoad` 读盘 → injectLoadedChunk），于是
            // `localPullEntry` 拿得到**逐段 hash + 平面 hash**，服务端才能判 BLOCKS 增量
            //（历史报告 `classic-matrix-smoke-report-2026-08-29.md`：1.20.1 R2
            // `fullChunkRequestCount=1` + 分段增量正常发送）。1.21.1 上 scheduleChunkLoad
            // 每轮只触发 76~88 次，权威窗柱在 compare 时还没有活柱 ⇒ 只能带**柱级** hash ⇒
            // `SectionDeltaPlanner.planSection` 在客户端无有效平面时把每个差异段判 `Kind.FULL`，
            // 差异段占比到 75% 再整柱回退 ⇒ R2 退化成 257 个整柱 FULL。
            // 这里把同一步补在 compare 之前（同一入口、幂等、按本轮预算节流）；物化后本柱走
            // 下面的 material 分支：`SeedGenCompareGate.mark` → compare（带逐段/平面）→
            // 响应落地后才允许交付，不存在「先投陈旧内容」的窗口。
            if (MATERIALIZE_BEFORE_COMPARE && injected == null && materialized < budget) {
                ShadowLightCompute.accountLightAtScheduleLoad(currentDimension, pos);
                injected = shadow.injectedChunk(currentDimension, pos.x, pos.z);
                if (injected != null) {
                    materialized++;
                }
            }
            boolean material = injected != null;
            if (material) {
                skippedMaterial++;
                // 已 compare 成功：允许响应侧/补投交付；禁止再 mark（会把已在光管线的柱钉死）。
                // 【2026-09-20（用户拍板）】本会话网络已付过账（authoritative FULL/DELTA 落地）
                // = 数据已权威，与 isConfirmed 同语义：直接交付，**不再发 compare**
                // （compare 只用于已有基线判权威；R1 冷场实测 567 次多余 compare + 567 次 UNCHANGED）。
                boolean authoritativeLocal = io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate
                        .isConfirmed(currentDimension, pos)
                        || ShadowLightCompute.wasNetworkIngressAccounted(currentDimension, pos);
                if (authoritativeLocal) {
                    if (!ShadowLightCompute.hasClientApplyEpoch(currentDimension, pos)
                            && isDeliverableToClient(pos.x, pos.z)) {
                        if (ShadowChunkDeliver.deliverLocal(currentDimension, pos, false, false)) {
                            published++;
                        }
                    }
                    continue;
                }
                // 仍在等 compare：不盲投
                if (io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate
                        .isAwaiting(currentDimension, pos)) {
                    continue;
                }
                // 本地基线尚未 compare：mark + 发请求（先清陈旧在途再发）
                if (!ShadowLightCompute.hasClientApplyEpoch(currentDimension, pos)
                        && isDeliverableToClient(pos.x, pos.z)) {
                    io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate
                            .mark(currentDimension, pos);
                    clearPullInFlight(currentDimension, pos);
                    boolean baseline = ShadowLightCompute.hasLocalPullBaseline(currentDimension, pos);
                    if (!requestPullEligible(currentDimension, pos, false)) {
                        requestPullEligible(currentDimension, pos, !baseline);
                    }
                }
                continue;
            }
            if (SeedGenExecutor.getInstance().isGenerationGateOpen()
                    && !ShadowLightCompute.hasLocalPullBaseline(currentDimension, pos)) {
                if (!SeedGenCompareGate.isAwaiting(currentDimension, pos)
                        && submitLocalGeneration(shadow, currentDimension, pos, nowMs)) {
                    submitted++;
                }
                continue;
            }
            // 预算只约束「本轮新 acquire」；同组（3×3 域）发完才换组。
            long domain = domainId(pos.x, pos.z);
            if (submitted >= budget && domain != lastAcquireDomain) {
                continue;
            }
            lastAcquireDomain = domain;
            VanillaAlignedChunkProvider.getInstance().acquire(
                    currentDimension, pos, ShadowChunkProvider.AcquireReason.TRACKING);
            submitted++;
        }
        if (submitted > 0 || published > 0 || materialized > 0) {
            DebugLogger.info(DebugLogger.LogType.NETWORK,
                    "[SHADOW_TRACK] authority-acquire center=({},{}) vd={} halo={} submitted={} published={} material={} materializedFromDisk={} (dimension={})",
                    center.x, center.z, serverViewDistance, lightHaloRadius(), submitted, published,
                    skippedMaterial, materialized, currentDimension);
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
        if (shadow == null || trackingCenter == null || currentDimension == null) {
            return;
        }
        // 客户端已切维、tracking 未 reseat：禁止把旧维 OVD 柱推进新 ClientLevel（脚下闪主世界）。
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.level == null) {
                // 加载屏 / 切维窗口：无客户端世界 → 下方 `clientHasChunk` 恒假，会把整圈环带
                // 误判成「客户端已丢柱」而摘除落地凭据。此刻交付也无处可落，直接跳过本拍。
                return;
            }
            if (mc != null) {
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
        ChunkPos center = trackingCenter;
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
                    Long missAt = ovdMissRetryAt.get(key);
                    if (missAt != null && nowMs < missAt) {
                        continue;
                    }
                    ChunkPos pos = new ChunkPos(x, z);
                    boolean clientHas = ShadowLightCompute.clientHasChunk(x, z);
                    // 【2026-09-22 修复：改渲染距离 → OVD 环带永久虚空】
                    // `ovdCounted` 是**指标去重集**（`recordOvdLoadedOnce` 的守卫），**不得**单独当控制流门：
                    // 客户端改视距时 vanilla `ClientChunkCache.updateViewRadius` 重建 Storage，
                    // 范围外的柱被**静默丢弃且不触发 `ClientLevel.unload`**（1.20.1 反编译已核）
                    // → 坐标已不在客户端，落地凭据却残留。旧口径 `if (ovdCounted.contains(key)) continue;`
                    // 会让同一坐标「离开再回来」后**本会话永不补**；滑块来回一次 = 整圈环带同时进入该状态
                    // （1.21.1+ 更宽：服务端 tracking 随滑块收缩，`serverVD` 变小后环带整体换位）。
                    // 现改为**活判据**：客户端确实持有、或该柱已在影子光管线里（generated / inflight /
                    // 齐套门等待中）才跳过——两者都是自清除的活状态，不再有「本会话永不补」。
                    if (ovdCounted.contains(key)
                            && (clientHas
                                || ShadowLightCompute.isLocalRequeueInFlight(currentDimension, pos))) {
                        continue;
                    }
                    var injected = shadow.injectedChunk(currentDimension, x, z);
                    boolean epoch = ShadowLightCompute.hasClientApplyEpoch(currentDimension, pos);
                    // OVD_PATH：逐跳诊断（inject/epoch/clientHas/publish）——缺柱归因用
                    boolean watch = isOvdWatchCoord(x, z);
                    // 凭据残留（epoch 真但客户端已无柱）：先摘除，否则下游
                    // （权威 material 分支 / `ShadowVanillaLightPipeline` 的「已落地则跳过」）会继续挡同一柱。
                    if (epoch && !clientHas) {
                        ShadowLightCompute.invalidateClientApplyEpoch(currentDimension, pos);
                        epoch = false;
                        if (watch) {
                            DebugLogger.info(DebugLogger.LogType.NETWORK,
                                    "[OVD_PATH] sweep stale-epoch invalidated ({}, {}) "
                                            + "— client lost the column (render-distance change), re-serving",
                                    x, z);
                        }
                    }
                    if (injected != null) {
                        if (published >= OVD_PUBLISH_BUDGET) {
                            if (watch) {
                                DebugLogger.info(DebugLogger.LogType.NETWORK,
                                        "[OVD_PATH] sweep budget-skip ({}, {}) inj=Y epoch={} clientHas={}",
                                        x, z, epoch, clientHas);
                            }
                            continue;
                        }
                        if (epoch) {
                            recordOvdLoadedOnce(currentDimension, pos);
                            continue;
                        }
                        boolean pub = ShadowLightCompute.publishOvdCachedChunk(currentDimension, pos);
                        if (pub) {
                            recordOvdLoadedOnce(currentDimension, pos);
                            published++;
                        }
                        if (watch) {
                            DebugLogger.info(DebugLogger.LogType.NETWORK,
                                    "[OVD_PATH] sweep publish ({}, {}) inj=Y epoch={} clientHas={} pub={} radiusLogFollows",
                                    x, z, epoch, clientHas, pub);
                        }
                    } else {
                        notInjected++;
                        // OVD 盘读的**唯一判据 = 当前有没有块**（injected 非空即走上面的 publish，
                        // 不盘读）——不需要任何「光环」标记（2026-09-19 用户口径：
                        // 「OVD 读取跳过光环块其实只需要判断当前是否有块就行了，光环块是个概念，
                        // 不需要专门标记」）。所以本分支只在「无块」时读盘。
                        if (watch) {
                            DebugLogger.info(DebugLogger.LogType.NETWORK,
                                    "[OVD_PATH] sweep no-inject ({}, {}) epoch={} clientHas={} — try local disk",
                                    x, z, epoch, clientHas);
                        }
                        // ticket2 时代路径：缺 inject 读影子盘（type126），不 pull
                        if (diskTried < OVD_DISK_BUDGET) {
                            tryServeOvdLocal(shadow, currentDimension, x, z);
                            diskTried++;
                        }
                    }
                }
            }
        }
        if (diskTried > 0 || published > 0 || notInjected > 0) {
            int radius = -1;
            try {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                if (mc != null && mc.level != null) {
                    radius = io.github.limuqy.mc.hassium.client.OvdClientLifecycle.effectiveClientVD(mc);
                }
            } catch (Throwable ignored) {
            }
            DebugLogger.info(DebugLogger.LogType.NETWORK,
                    "[SHADOW_TRACK] OVD sweep disk={} publish={} window={} missing={} center=({},{}) ring={}..{} clientRadius={} (dimension={})",
                    diskTried, published, windowCells, notInjected,
                    center.x, center.z, serverVD, clientVD, radius, currentDimension);
        }
    }

    /**
     * OVD 诊断观察坐标：cheb==serverVD+2 的环中点。
     * <p>
     * 取 +2 而非 +1：{@code serverVD+1} 环只有对角部分落在环带内，四边中段属于权威形状
     * （被 {@code inOvdWindow} 排除），环带 sweep 永远不会枚举到；{@code serverVD+2} 才是环带里
     * 第一条完整环。权威侧 {@code serverVD+1} 中段的缺口不走本观察点，由
     * {@code TRACE_ENCLOSED_HOLE} 门禁与 {@code drainAuthorityAcquires} 的
     * {@code submitted/published/material} 计数把守（2026-09-18 根因即该环）。
     */
    private static boolean isOvdWatchCoord(int x, int z) {
        ShadowTrackingSession s = INSTANCE;
        if (s == null || s.serverViewDistance <= 0) {
            return false;
        }
        ChunkPos center = deliveryCenter();
        if (center == null) {
            return false;
        }
        int r = s.serverViewDistance + 2;
        int dx = x - center.x;
        int dz = z - center.z;
        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
            return false;
        }
        // 仅四边中段（非角）：|dx|<r 且 |dz|==r，或反过来
        return (Math.abs(dz) == r && Math.abs(dx) <= r / 2)
                || (Math.abs(dx) == r && Math.abs(dz) <= r / 2);
    }

    /**
     * OVD 本地源（ticket2 影子驱动路径）：inject 为空时读影子 type126 盘。
     * 无盘记 miss + 冷却；有盘 inject + renderOnly publish。禁止 pull / ovdLocalGeneration。
     */
    private void tryServeOvdLocal(ShadowSeedServer shadow, String dimension, int x, int z) {
        if (shadow == null || dimension == null) {
            return;
        }
        ChunkPos pos = new ChunkPos(x, z);
        if (shadow.injectedChunk(dimension, x, z) != null) {
            return;
        }
        net.minecraft.world.level.chunk.LevelChunk chunk = shadow.loadFromDisk(dimension, pos);
        if (chunk == null) {
            ovdMissRetryAt.put(DimensionKey.key(dimension, x, z), System.currentTimeMillis() + 2_000L);
            ShadowChunkMapCompat.failSuspendedLoad(dimension, pos);
            if (recordOvdMissOnce(dimension, pos)) {
                DebugLogger.info(DebugLogger.LogType.NETWORK,
                        "[OVD_PATH] disk-miss ({}, {}) dim={}", x, z, dimension);
            }
            return;
        }
        boolean diskHit = io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageHashes
                .get(dimension, pos) != null;
        shadow.injectLoadedChunk(dimension, pos, chunk, !diskHit);
        ShadowChunkMapCompat.completeSuspendedLoad(dimension, pos, chunk);
        boolean pub = ShadowLightCompute.publishOvdCachedChunk(dimension, pos);
        DebugLogger.info(DebugLogger.LogType.NETWORK,
                "[OVD_PATH] disk-serve ({}, {}) diskHit={} pub={} clientHas={} dim={}",
                x, z, diskHit, pub, ShadowLightCompute.clientHasChunk(x, z), dimension);
        if (pub) {
            recordOvdLoadedOnce(dimension, pos);
        }
    }

    /**
     * 真实客户端 unload（任意线程）。A3：客户端缓存不反驱影子选柱。
     * 窗内只清离开标记；窗外登记后由 reclaim 摘 inject（等价原版 Forget 后服务端卸）。
     */
    public void onClientChunkUnloaded(ChunkPos pos) {
        onClientChunkUnloaded(pos, null);
    }

    /**
     * @param dimension 正在 unload 的 ClientLevel 维 id；null 回落 currentDimension。
     *                  切维后旧世界 unload 不能读已 reseat 的 currentDimension（inject/回收键会打错维）。
     */
    public void onClientChunkUnloaded(ChunkPos pos, String dimension) {
        if (pos == null || boundServer == null) {
            return;
        }
        String dim = dimension != null ? dimension : currentDimension;
        if (dim == null) {
            return;
        }
        long key = DimensionKey.key(dim, pos.x, pos.z);
        // 非当前维：玩家已离开，旧维 OVD/权威几何不适用 → 按离开登记 reclaim
        boolean sameDim = dim.equals(currentDimension);
        boolean stillWanted = sameDim
                && (inVanillaVisibleShape(pos.x, pos.z) || inOvdWindow(pos.x, pos.z));
        if (boundServer.injectedChunk(dim, pos.x, pos.z) == null) {
            outsideSinceMs.remove(key);
            return;
        }
        if (stillWanted) {
            outsideSinceMs.remove(key);
            return;
        }
        outsideSinceMs.putIfAbsent(key, System.currentTimeMillis());
        ensureReclaimTimer();
    }

    private void applyState(ShadowSeedServer shadow, PendingState state) {
        if (!state.present() || state.dimension() == null || shadow.level(state.dimension()) == null) {
            return;
        }
        boolean dimensionChanged = !state.dimension().equals(currentDimension);
        currentDimension = state.dimension();
        trackingCenter = new ChunkPos((int) state.x() >> 4, (int) state.z() >> 4);
        if (dimensionChanged) {
            onTrackingDimensionChanged(state);
        }
    }

    /** 切维后清理旧维度的选柱、在途 pull 与探针状态。 */
    private void onTrackingDimensionChanged(PendingState state) {
        sweepInFlight.clear();
        ovdCounted.clear();
        ovdMissCounted.clear();
        ovdMissRetryAt.clear();
        lastOvdSweepMs = 0L;
        homeChunk = new ChunkPos((int) state.x() >> 4, (int) state.z() >> 4);
        lastChunkTickMs = 0L;
        SmokeChunkTrace.reset();
        client().resetMeshCompileLog();
        ShadowLightCompute.onClientDimensionChanged();
        ShadowPullClient.onClientDimensionChanged();
        client().onDimensionChanged();
    }

    /**
     * 权威窗 = **交付窗**（原版可见形状，{@code serverVD}；pull / bootGrid / sweep / 交付唯一几何）。
     * 中心与真服 tracking 同轴。会话未就绪时放行（保守：不因未知而漏投）。
     */
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

    /**
     * 交付/trace 候选：**权威窗 ∪ OVD 环带**（B6）。
     * <p>
     * <b>2026-09-19（S3b，用户拍板）</b>：权威侧 = 原版可见形状 {@code serverVD}，
     * **不再有 {@code +DELIVER_VIEW_MARGIN_CHUNKS} 余量**。理由：余量让「计算域比交付域窄」
     * 时多出来的环（光环）也能进客户端视野——实测 R1 交付恰好 = {@code |shape(serverVD+光环)|}，
     * 即光环被交付了；而光环柱的 3×3 天生不完整（它的外侧邻柱在计算域外）→ 那正是
     * 「外圈不完整光块进视野」。收到 {@code serverVD} 后：**交付集 = 权威集**，
     * 光环柱只算不交付；OVD 环带仍按 {@link #inOvdWindow} 独立放行（与二期 OVD 兼容）。
     */
    public static boolean isDeliverableToClient(int x, int z) {
        ShadowTrackingSession s = INSTANCE;
        if (s == null) {
            return true;
        }
        return s.inVanillaVisibleShape(x, z) || s.inOvdWindow(x, z);
    }

    /**
     * 【S6 推送门（**原版口径**，2026-09-19）】交付窗内 **且「本会话 LIGHT 步已完成」**。
     * <p>
     * <b>原版判据（1.20.1 mojmap，已核源码）</b>：推送触发点是
     * {@code ChunkMap.prepareTickingChunk}（`ChunkMap.java:726`）
     * {@code getChunkRangeFuture(holder, 1, s -> ChunkStatus.FULL)} ——
     * **该柱及其 range=1 邻域（3×3）全部达到 {@code ChunkStatus.FULL}** 才发给玩家；
     * 发包处（`ChunkMap.playerLoadedChunk:1256-1258`）**不看光**。
     * 而状态链 `LIGHT`（`ChunkStatus.java:141`，range=1）→ `SPAWN`(:149) → `FULL`(:156)，
     * 故「到 FULL」**蕴含本会话 LIGHT 步已完成**。光只在「复用还是重算」处出现：
     * {@code isLighted(c) = status>=LIGHT && c.isLightCorrect()}（`ChunkStatus.java:251`）被
     * `initializeLight`/`lightChunk`(:200-211) 用来决定跳过还是重跑——**盘上 {@code isLightOn=false}
     * 的柱，原版会在本会话重跑 LIGHT、跑完置真，然后才可能到 FULL**。
     * ⟹ {@code isLightCorrect} 只是伴生结果，不是判据；两者在 vanilla 等价，是因为 vanilla
     * 不允许 flag=false 的柱以 FULL 状态存在。
     * <p>
     * <b>Hassium 对应物 = 两个谓词的并集</b>：
     * <ul>
     *   <li>{@code lightCorrect} —— REUSE / 盘上已完整的柱（**不进齐套门**，`promotedClean` 恒假）；</li>
     *   <li>{@link ShadowLightCompute#lightRanThisSession}（= 本会话 LIGHT 步跑完过）——
     *       **2026-09-19 任务 #36 起取代 `isColumnLightAuthoritative`**。</li>
     * </ul>
     * <b>为什么从 `isColumnLightAuthoritative` 换掉（handoff §「飞行场景的两个空洞」①）</b>：
     * `promotedClean` 要求 `outsideWindow==0`，而飞行中邻域窗随玩家移动 → **前沿柱
     * promote 时向外邻柱在窗外 → 永远不 clean → 永久洞**（实测飞行日志：`Promote ... 
     * outsideWindow=3 timedOut=false` 后 `pushReady blocked lightIncomplete`）。
     * vanilla 不这样：它的门 `getChunkRangeFuture(holder,1,FULL)` 是**可满足**的，所以最终照推。
     * `lightRan` 是「LIGHT 跑完」这个**事实**，降级放行的柱同样为真 → 前沿柱照推。
     * 代价（用户已接受）：前沿柱会带「向外一侧缺邻」的光进客户端，靠 S2c 重算重交付覆盖。
     * <p>
     * 本重载要求调用方**拿得到柱**；拿不到柱时不要调用（状态未知 ≠ 未完成，侧查注入表会假阴性）。
     */
    public static boolean isPushableToClient(String dimension, ChunkPos pos, boolean lightCorrect) {
        if (pos == null || !isDeliverableToClient(pos.x, pos.z)) {
            return false;
        }
        return lightCorrect
                || io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute
                        .lightRanThisSession(dimension, pos);
    }

    /** 诊断用：光照门两个判据的取值（`lc=` / `ran=`）。 */
    public static String lightGateDetail(String dimension, ChunkPos pos) {
        net.minecraft.world.level.chunk.LevelChunk chunk = injectedChunkOrNull(dimension, pos);
        return (chunk == null ? "noInject" : "lc=" + chunk.isLightCorrect())
                + " ran=" + io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute
                        .lightRanThisSession(dimension, pos);
    }

    /** 影子端注入柱（无则 null）；查表失败一律返回 null，不抛。 */
    private static net.minecraft.world.level.chunk.LevelChunk injectedChunkOrNull(
            String dimension, ChunkPos pos) {
        if (dimension == null || pos == null) {
            return null;
        }
        ShadowSeedServer server = ShadowServerRegistry.getInstance().get();
        return server == null ? null : server.injectedChunk(dimension, pos.x, pos.z);
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
     * 原版生命周期（R4）：保留域外的注入柱登记离开时刻，由 reclaim 线程
     * {@link ShadowColumnStore#flushAndEvict}（type126 先落盘再摘表）。
     * <b>不得</b>在影子主循环内直接 unload（flush 会等主循环 → 自死锁）。
     */
    private void enqueueOutOfWindowInjectedForReclaim(ShadowSeedServer shadow) {
        if (shadow == null || trackingCenter == null || currentDimension == null
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
            // 保留域含光照光环：光环柱是权威边界柱 3×3 的一部分，过早回收会让边界柱重新降级。
            if (isInComputeDomain(x, z) || inOvdWindow(x, z)) {
                continue;
            }
            if (outsideSinceMs.putIfAbsent(key, now) == null) {
                enrolled++;
            }
        }
        if (enrolled > 0) {
            ensureReclaimTimer();
            DebugLogger.info(DebugLogger.LogType.NETWORK,
                    "[SHADOW_TRACK] enqueue out-of-window reclaim count={} center=({},{}) vd={} halo={} (dimension={})",
                    enrolled, center.x, center.z, serverViewDistance, lightHaloRadius(), currentDimension);
        }
    }

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

    /** 本会话首次记录该柱 OVD 缺盘；返回 true = 首次（调用方据此按需打日志，避免 2s 冷却期内刷屏）。 */
    private boolean recordOvdMissOnce(String dimension, ChunkPos pos) {
        long key = io.github.limuqy.mc.hassium.utils.DimensionKey.key(dimension, pos.x, pos.z);
        if (!ovdMissCounted.add(key)) {
            return false;
        }
        io.github.limuqy.mc.hassium.metrics.NetworkStats.recordOvdMiss();
        return true;
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
        boolean alreadyMaterialized = shadow.injectedChunk(dimension, pos.x, pos.z) != null;
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
                if (isOvdWatchCoord(pos.x, pos.z)) {
                    DebugLogger.info(DebugLogger.LogType.NETWORK,
                            "[OVD_PATH] materialize ({}, {}) pub={} clientHas={} (dimension={})",
                            pos.x, pos.z, published,
                            ShadowLightCompute.clientHasChunk(pos.x, pos.z), dimension);
                }
                DebugLogger.info(DebugLogger.LogType.NETWORK,
                        "[SHADOW_TRACK] OVD materialized ({}, {}) -> publish {} (dimension={})",
                        pos.x, pos.z, published, dimension);
            }
            return;
        }
        // A1-③：seedGen 需先与真服 compare，再由响应侧权威柱交付
        if (SeedGenExecutor.deferLightUntilAuthority(localWorldgen, true)) {
            io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate.mark(dimension, pos);
            DebugLogger.info(DebugLogger.LogType.NETWORK,
                    "[SHADOW_TRACK] seedGen materialize ({}, {}) -> compare-before-deliver (dimension={})",
                    pos.x, pos.z, dimension);
            if (!requestPullEligible(dimension, pos, false)) {
                // 有基线 compare 发不出去时仍要权威应答；标记保持，禁止本地盲交付
                requestPullEligible(dimension, pos, true);
            } else {
                DebugLogger.info(DebugLogger.LogType.NETWORK,
                        "[SHADOW_TRACK] seedGen compare requested ({}, {}) (dimension={})",
                        pos.x, pos.z, dimension);
            }
            return;
        }
        if (io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate
                .isAwaiting(dimension, pos)) {
            return;
        }
        if (!isDeliverableToClient(pos.x, pos.z)) {
            return;
        }
        net.minecraft.world.level.chunk.LevelChunk material =
                shadow.injectedChunk(dimension, pos.x, pos.z);
        boolean deliverableMaterial = material != null;
        if (!deliverableMaterial) {
            // A1-②：无本地可交付基线 → 权威 FULL
            DebugLogger.info(DebugLogger.LogType.NETWORK,
                    "[SHADOW_TRACK] materialized ({}, {}) no-material -> pull FULL (dimension={})",
                    pos.x, pos.z, dimension);
            requestPullEligible(dimension, pos, true);
            return;
        }
        // 权威窗本地基线（盘缓存 / 内存注入 / seedGen）一律先 compare，禁止抢先交付。
        // 交付门：mark 之后 publishCached / offerReady / 官方包桥一律被拒；
        // 响应侧 UNCHANGED / DELTA / FULL 落地成功才 clear() 放行。
        // 顺序必须"先 mark 再请求"：反过来会被「响应先到 → clear 空表 → 再 mark」钉死。
        // 网络 FULL 响应侧 injectChunk 会 clear，本分支对已 clear 的权威注入再 compare 作为保鲜。
        // 【2026-09-20（用户拍板）】但**本会话网络已付过账**的柱例外：authoritative FULL/DELTA
        // 落地的数据本身就是权威的 → compare 属于多余往返（R1 冷场实测 567 次）。
        // compare 的语义只应是「已有基线（盘缓存 / 本地生成 / 外圈进内圈）判断是否权威」。
        // 这类柱的交付由 tracking 泵的 authoritativeLocal 分支负责，此处直接返回。
        if (ShadowLightCompute.wasNetworkIngressAccounted(dimension, pos)) {
            return;
        }
        {
            io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate.mark(dimension, pos);
            if (requestPullEligible(dimension, pos, false)
                    || requestPullEligible(dimension, pos, true)) {
                DebugLogger.info(DebugLogger.LogType.NETWORK,
                        "[SHADOW_TRACK] materialized ({}, {}) -> compare-before-deliver "
                                + "(dimension={}) cacheBaseline={} localWorldgen={}",
                        pos.x, pos.z, dimension, diskHit, localWorldgen);
            } else {
                DebugLogger.warn(DebugLogger.LogType.NETWORK,
                        "[SHADOW_TRACK] HOLE: ({}, {}) local baseline without authority request "
                                + "(dimension={}) — left undelivered",
                        pos.x, pos.z, dimension);
            }
            return;
        }
    }

    private boolean submitLocalGeneration(ShadowSeedServer shadow, String dimension, ChunkPos pos,
                                          long nowMs) {
        if (shadow.generationInFlight() >= ShadowSeedServer.generationMaxInFlight()
                || !markPullInFlight(dimension, pos, nowMs)) {
            return false;
        }
        shadow.generateChunkAsync(dimension, pos, (generatedDimension, chunk) -> {
            if (chunk == null) {
                clearPullInFlight(generatedDimension, pos);
                SeedGenCompareGate.clear(generatedDimension, pos);
                VanillaAlignedChunkProvider.failAcquire(generatedDimension, pos);
                // 生成失败 ⇒ 权威回退属重试，先释放 compare 去重登记（否则该柱一次生成失败就永不回退）
                ShadowPullClient.clearCompareRequested(generatedDimension, pos);
                requestPullEligible(generatedDimension, pos, true);
                return;
            }
            // 【2026-09-21 顺序修正】生成的在途语义到此结束：`sweepInFlight` 是**生成与 pull
            // 共用**的一张表，而下面 onChunkMaterialized 里要立刻发 compare —— 它自己也走
            // `markPullInFlight`。若这里不先释放，onChunkMaterialized 的两次 requestPullEligible
            // 都会因「已在途」而返回 false ⇒ 一个请求都发不出去，而 SeedGenCompareGate.mark 已经
            // 置了 AWAITING（该闸无超时/无自愈）⇒ 泵的 `isAwaiting → continue` 与交付闸
            // 永久跳过本柱 = **玩家脚下中心空洞**（实测 seedGen 场 `seedGen compare requested`=0）。
            clearPullInFlight(generatedDimension, pos);
            onChunkMaterialized(generatedDimension, pos, chunk);
            VanillaAlignedChunkProvider.completeAcquire(generatedDimension, pos, chunk);
        });
        return true;
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
        if (!isInComputeDomain(pos.x, pos.z)) {
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
     * 重入必 compare：本地有基线且客户端无落地凭据时，向真服发 compare-pull，
     * 禁止盲 {@code publishCached} 当缓存命中。记账：
     * UNCHANGED → 响应侧 publishCached → accountCacheFullHit；FULL/DELTA 走既有网络落地。
     *
     * @return true = compare 已发出/在途（调用方不得再本地盲交付）
     */
    public boolean tryReentryCompare(String dimension, ChunkPos pos, String reason) {
        if (dimension == null || pos == null) {
            return false;
        }
        if (!ShadowLightCompute.isReentryPendingCompare(dimension, pos)) {
            return false;
        }
        boolean baseline = ShadowLightCompute.hasLocalPullBaseline(dimension, pos);
        if (!ShadowLightCompute.tryRequestMiss(dimension, pos)) {
            // 本会话已登记请求：在途/响应处理中视为已由 compare 接管
            return isPullInFlight(dimension, pos);
        }
        if (!requestPullEligible(dimension, pos, !baseline)) {
            ShadowLightCompute.clearRequestMiss(dimension, pos);
            return false;
        }
        DebugLogger.info(DebugLogger.LogType.NETWORK,
                "[SHADOW_TRACK] reentry-compare ({}, {}) dim={} baseline={} reason={}",
                pos.x, pos.z, dimension, baseline, reason);
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
            // 拉回时已出权威窗：清 AWAITING，交给 OVD 本地源（禁止再挂 compare）
            io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate.clear(dimension, pos);
            return;
        }
        DebugLogger.info(DebugLogger.LogType.NETWORK,
                "[SHADOW_TRACK] pull-injected ({}, {}) -> publishCached (dimension={})",
                pos.x, pos.z, dimension);
        if (!ShadowLightCompute.publishCachedChunk(dimension, pos)) {
            // 注入表有柱但不可物化（异常）：再拉一次权威全量
            ShadowLightCompute.clearRequestMiss(dimension, pos);
            if (markPullInFlight(dimension, pos, System.currentTimeMillis())) {
                // 物化失败 ⇒ 重拉属重试，先释放 compare 去重登记
                ShadowPullClient.clearCompareRequested(dimension, pos);
                ShadowPullClient.requestAuthoritativeFull(dimension, java.util.List.of(pos));
            }
        }
    }

    /** 断连、关停或新连接时清会话引用与未完成 acquire。任意线程可调。 */
    public static void reset() {
        ShadowTrackingSession s = INSTANCE;
        s.pending = null;
        s.serverViewDistance = -1;
        s.effectiveClientVD = -1;
        s.boundServer = null;
        s.currentDimension = null;
        s.trackingCenter = null;
        s.sweepInFlight.clear();
        s.outsideSinceMs.clear();
        VanillaAlignedChunkProvider.clearAll();
        ShadowChunkMapCompat.clearSuspendedLoads();
        io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate.clearAll();
        io.github.limuqy.mc.hassium.protocol.ShadowPullClient.clearPullFailure(null, null);
        s.ovdCounted.clear();
        s.ovdMissCounted.clear();
        s.ovdMissRetryAt.clear();
        s.lastOvdSweepMs = 0;
        s.homeChunk = null;
        s.lastChunkTickMs = 0;
    }

}
