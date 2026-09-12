package io.github.limuqy.mc.hassium.network.seedgen;

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
    /** 悬置登记队列（worldgen 压制柱；scheduleChunkLoad 钩子线程写入，影子主循环消费）。 */
    private final java.util.Queue<SelectedChunk> pendingSelections =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    private record SelectedChunk(String dimension, int x, int z) {}

    /** 会话落位基准点：首个稳定座位（虚拟玩家放置瞬间的 chunk）。用于补齐起飞后身侧/身后的
     *  滞留环 —— 移动窗口天然不对称，绕该点铺静态「基准光盘」；半径 = 通告视距
     *  （isChunkInRange 同款，≈ 原版可见 1529@VD20），不铺 authority 边距外圈。 */
    private ChunkPos homeChunk;
    /** 基准光盘已布防待铺（ensureVirtualPlayer 落位后置真；单元格耗尽清除）。 */
    private boolean bootGridArmed;
    /** 基准光盘单元格队列（螺旋由近及远；半径 = 当时 resolveViewDistance()）。 */
    private final java.util.ArrayDeque<ChunkPos> bootGridCells = new java.util.ArrayDeque<>();
    /** 相邻两次光盘发射的最小间隔（毫秒）：防百柱级无基线请求同心跳灌入服务端按需装载。 */
    private static final long BOOT_EMIT_MIN_GAP_MS = 25L;
    private long lastBootEmitMs;

    /**
     * 可见形状周期扫描间隔（毫秒）。移动后 vanilla 选柱链（scheduleChunkLoad →
     * onChunkSelected → pendingSelections）会停——悬置 future 卡住或 2ms tick 预算被
     * 卸载耗尽。本扫描不依赖 vanilla，直接枚举虚拟玩家当前可见形状内未注入柱补齐 pull。
     */
    private static final long SWEEP_INTERVAL_MS = 500L;
    /** 单次形状扫描最多入队的缺失柱数（防单泵风暴；剩余下轮续扫）。 */
    private static final int MAX_SWEEP_PER_PUMP = 128;
    /** 客户端 unload 后、影子仍在可见形状内时的重发队列（真实服 Forget 半径可能小于影子 vd）。 */
    private final java.util.concurrent.ConcurrentLinkedQueue<ChunkPos> redeliverQueue =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** 单泵最多本地重发柱数。 */
    private static final int MAX_REDELIVER_PER_PUMP = 32;
    private long lastSweepMs;
    /** 形状扫描 / boot / 悬置选柱共用的在途柱（复合键 → 入队时刻）：已发 pull 未注入。
     *  注入后或超时清除。超时须长于 VD20 冷装填（1529 柱 / 2 tick ≈ 38s），
     *  15s 会把外环未注入柱当丢失再扫一遍。 */
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
            pendingSelections.clear();
            sweepInFlight.clear();
            redeliverQueue.clear();
            lastChunkTickMs = 0;
            lastSweepMs = 0;
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
        drainBootGrid(shadow, MAX_REQUESTS_PER_PUMP);
        drainSelections(shadow, MAX_REQUESTS_PER_PUMP);
        sweepVisibleShape(shadow, now);
        sweepOvdRing(shadow, now);
        drainRedeliver(shadow);
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
                        if (diskTried >= OVD_DISK_BUDGET) {
                            continue;
                        }
                        tryServeOvdLocal(shadow, new SelectedChunk(currentDimension, x, z));
                        diskTried++;
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
        if (!inVanillaVisibleShape(pos.x, pos.z) && !inOvdWindow(pos.x, pos.z)) {
            return;
        }
        if (boundServer.injectedChunk(currentDimension, pos.x, pos.z) == null) {
            return;
        }
        redeliverQueue.add(pos);
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
            if (!inVanillaVisibleShape(pos.x, pos.z) && !inOvdWindow(pos.x, pos.z)) {
                continue;
            }
            if (ShadowLightCompute.hasClientApplyEpoch(currentDimension, pos)) {
                continue; // 本会话已有落地凭据
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
#if MC_VER < MC_1_21_5
            virtualPlayer.absMoveTo(state.x(), state.y(), state.z(), state.yRot(), state.xRot());
#else
            // 1.21.5+：absMoveTo/moveTo(5 参) 移除，teleportTo(3 参)+旋转同语义（绝对位置设置）
            virtualPlayer.teleportTo(state.x(), state.y(), state.z());
            virtualPlayer.setYRot(state.yRot());
            virtualPlayer.setXRot(state.xRot());
#endif
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
        pendingSelections.clear();
        sweepInFlight.clear();
        redeliverQueue.clear();
        ovdCounted.clear();
        ovdMissCounted.clear();
        ovdMissRetryAt.clear();
        lastOvdSweepMs = 0L;
        homeChunk = new ChunkPos((int) state.x() >> 4, (int) state.z() >> 4);
        bootGridArmed = true;
        bootGridCells.clear();
        lastBootEmitMs = 0L;
        lastSweepMs = 0L;
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
            bootGridArmed = true;
            bootGridCells.clear();
            DebugLogger.info(DebugLogger.LogType.ASYNC,
                    "[SHADOW_TRACK] virtual player tracking session started (dimension={}, "
                            + "viewDistance={}, serverRadius={})",
                    currentDimension, viewDistance, serverViewDistance);
        } catch (Throwable t) {
            createFailed = true;
            logCreateFailed(t);
        }
    }

    private int resolveViewDistance() {
        // 权威边距：原版玩家 tracking 半径（ChunkMap.setViewDistance 用 viewDistance+1 构造
        // tracking view）：形状轴深 = vd+2 = 服务端签发上界（AUTHORITY_MARGIN）。
        int authority = serverViewDistance > 0
                ? serverViewDistance + 1
                : DEFAULT_VIEW_DISTANCE + 1;
        // OVD 双窗：effective clientRD > 权威边距时扩窗到 effective（ticket 覆盖环带）。
        // pull 域仍由 inVanillaVisibleShape（serverVD）裁决，扩窗不扩大真服请求。
        int effective = effectiveClientVD;
        if (effective > authority && isOvdConfigActive()) {
            return Math.min(effective, MAX_VIEW_DISTANCE);
        }
        return Math.min(authority, MAX_VIEW_DISTANCE);
    }

    private static boolean isOvdConfigActive() {
        HassiumConfigService cfg = HassiumConfigService.getInstance();
        return cfg.isClientCacheEnabled() && cfg.isViewDistanceExtensionEnabled();
    }

    /** 权威窗：原版可见形状（pull / bootGrid / sweep 唯一几何）。 */
    private boolean inVanillaVisibleShape(int x, int z) {
        if (serverViewDistance <= 0) {
            return true;
        }
        ChunkPos center = virtualPlayer == null ? homeChunk : virtualPlayer.chunkPosition();
        if (center == null) {
            return true;
        }
        return ChunkShapeCompat.contains(center.x, center.z, serverViewDistance, x, z);
    }

    /** OVD 窗：client chebyshev 窗内且权威窗外；本地源服务，禁止 pull。 */
    private boolean inOvdWindow(int x, int z) {
        if (serverViewDistance <= 0 || effectiveClientVD <= serverViewDistance) {
            return false;
        }
        ChunkPos center = virtualPlayer == null ? homeChunk : virtualPlayer.chunkPosition();
        if (center == null) {
            return false;
        }
        int dx = Math.abs(x - center.x);
        int dz = Math.abs(z - center.z);
        return dx <= effectiveClientVD && dz <= effectiveClientVD
                && !ChunkShapeCompat.contains(center.x, center.z, serverViewDistance, x, z);
    }

    /**
     * 服务端半径晚到/变化（登录包 chunkRadius、VD 切换）：影子主循环线程安全应用。
     * {@code setChunkViewDistance} 会走原版 {@code ChunkMap.setViewDistance} 的
     * 全 holder 重跟踪，晚应用可自愈已放置玩家的选柱集合。
     */
    private void applyViewDistanceIfChanged(ShadowSeedServer shadow) {
        int desired = resolveViewDistance();
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
    }

    /**
     * 影子主循环分批发送悬置柱（worldgen 压制，无本地数据）的 pull 请求。
     * 读盘/生成柱不走此路径——它们由 onChunkMaterialized 桥携带基线进比对。
     */
    private void drainSelections(ShadowSeedServer shadow, int maxPerPump) {
        if (pendingSelections.isEmpty()) {
            return;
        }
        java.util.List<ChunkPos> withBaseline = new java.util.ArrayList<>();
        java.util.List<ChunkPos> withoutBaseline = new java.util.ArrayList<>();
        int sent = 0;
        while (sent < maxPerPump && !pendingSelections.isEmpty()) {
            SelectedChunk sel = pendingSelections.poll();
            if (!sel.dimension().equals(currentDimension)) {
                continue; // 已切维度的旧选中柱作废
            }
            // 预过滤中心 = 虚拟玩家实时位置（泵循环内 applyState 已先行移动，跟随真实玩家）；
            // 服务端校验中心同为真实玩家 chunkPosition()（逐请求实时取）。不能用 homeChunk——
            // 那是落座快照，玩家移动超出窗口后会把新区域柱全部错杀（服务端本可签发）。
            // 形状 = 原版可见圆角方形（range = 通告视距 vd，VD20 → 1529）：只拉用户能看到的；
            // 影子 ticket 略宽的角区不在此路径请求，边缘光由邻柱 LightDelta 自愈。
            if (!inVanillaVisibleShape(sel.x(), sel.z())) {
                // OVD 窗：仅本地源，禁止 ShadowPull
                if (inOvdWindow(sel.x(), sel.z())) {
                    tryServeOvdLocal(shadow, sel);
                }
                continue;
            }
            if (shadow.injectedChunk(sel.dimension(), sel.x(), sel.z()) != null) {
                continue; // 已物化（注入/本地生成），无需 pull
            }
            ChunkPos pos = new ChunkPos(sel.x(), sel.z());
            if (!markPullInFlight(sel.dimension(), pos, System.currentTimeMillis())) {
                continue; // boot / sweep 已发出，同柱不再打第二遍
            }
            if (ShadowLightCompute.hasLocalPullBaseline(sel.dimension(), pos)) {
                withBaseline.add(pos);
            } else {
                withoutBaseline.add(pos);
            }
            sent++;
        }
        emitPullGroups(withBaseline, withoutBaseline);
    }

    /**
     * 可见形状周期扫描：移动后 vanilla 选柱链（scheduleChunkLoad → onChunkSelected）会停
     * ——悬置 future 卡住或 2ms tick 预算被卸载耗尽，pendingSelections 不再填充。
     * 本扫描不依赖 vanilla，直接枚举虚拟玩家当前可见形状内「未注入且未在途」的柱补齐 pull。
     * 形状 = isChunkInRange(serverViewDistance)；已注入 / 已请求（{@link #sweepInFlight}）跳过。
     */
    private void sweepVisibleShape(ShadowSeedServer shadow, long nowMs) {
        if (virtualPlayer == null || currentDimension == null || serverViewDistance <= 0) {
            return;
        }
        if (bootGridArmed) {
            // 基准盘还在发射：未 mark 的外环仍在 bootGridCells 里，sweep 会抢发同一批。
            return;
        }
        if (nowMs - lastSweepMs < SWEEP_INTERVAL_MS) {
            return;
        }
        lastSweepMs = nowMs;
        ChunkPos center = virtualPlayer.chunkPosition();
        if (center == null) {
            return;
        }
        // 过期在途清除：pull 响应丢失/服务端拒绝的柱超时后可重试，防形状永久洞
        long expireBefore = nowMs - SWEEP_INFLIGHT_TIMEOUT_MS;
        sweepInFlight.entrySet().removeIf(e -> e.getValue() < expireBefore);
        java.util.List<ChunkPos> withBaseline = new java.util.ArrayList<>();
        java.util.List<ChunkPos> withoutBaseline = new java.util.ArrayList<>();
        int sent = 0;
        int radius = serverViewDistance;
        // 逐环由近及远扫描，优先补齐玩家脚下的洞
        for (int ring = 0; ring <= radius && sent < MAX_SWEEP_PER_PUMP; ring++) {
            int perimeter = ring == 0 ? 1 : 8 * ring;
            for (int i = 0; i < perimeter && sent < MAX_SWEEP_PER_PUMP; i++) {
                int x, z;
                if (ring == 0) {
                    x = center.x;
                    z = center.z;
                } else {
                    int side = i / (2 * ring);
                    int step = i % (2 * ring);
                    switch (side) {
                        case 0 -> { x = center.x - ring; z = center.z - ring + step; }
                        case 1 -> { x = center.x - ring + step; z = center.z + ring; }
                        case 2 -> { x = center.x + ring; z = center.z + ring - step; }
                        default -> { x = center.x + ring - step; z = center.z - ring; }
                    }
                }
                if (!ChunkShapeCompat.contains(center.x, center.z, radius, x, z)) {
                    continue;
                }
                if (shadow.injectedChunk(currentDimension, x, z) != null) {
                    sweepInFlight.remove(DimensionKey.key(currentDimension, x, z));
                    // 已注入但客户端无落地凭据（真实服半径更小导致 Forget，或 tracking 边沿漏发）：
                    // 入重发队列，由 drainRedeliver 限速 publish
                    ChunkPos injectedPos = new ChunkPos(x, z);
                    if (!ShadowLightCompute.hasClientApplyEpoch(currentDimension, injectedPos)
                            && redeliverQueue.size() < MAX_REDELIVER_PER_PUMP * 4) {
                        redeliverQueue.add(injectedPos);
                    }
                    continue;
                }
                ChunkPos pos = new ChunkPos(x, z);
                boolean hasBaseline = ShadowLightCompute.hasLocalPullBaseline(currentDimension, pos);
                if (!hasBaseline && preferLocalGeneration()) {
                    continue; // SeedGen 本地生成，不占在途、不发 pull
                }
                if (!markPullInFlight(currentDimension, pos, nowMs)) {
                    continue;
                }
                if (hasBaseline) {
                    withBaseline.add(pos);
                } else {
                    withoutBaseline.add(pos);
                }
                sent++;
            }
        }
        if (sent > 0) {
            DebugLogger.info(DebugLogger.LogType.NETWORK,
                    "[SHADOW_TRACK] sweep missing={} localGen={} center=({},{}) radius={} (dimension={})",
                    sent, preferLocalGeneration(), center.x, center.z, radius, currentDimension);
        }
        emitPullGroups(withBaseline, withoutBaseline);
    }

    /** 会话静态基准光盘：起步时把身子背后没有窗口追随的滞留环逐环补齐（气球尾部闭环）。 */
    private void drainBootGrid(ShadowSeedServer shadow, int maxPerPump) {
        if (!bootGridArmed) {
            return;
        }
        if (homeChunk == null) {
            bootGridArmed = false;
            return;
        }
        if (bootGridCells.isEmpty()) {
            // 一张盘多次发射：原版圆角方形盘面绕落位点，路径序遍历（反向侧南方的起动冷负荷先前置、
            // 尽量均匀）而不是机械整数螺旋——练习周期太短时北方冷柱容易在场次收束前还没孵化。
            // 形状 = isChunkInRange(serverViewDistance)：与原版可见集合同几何（VD20 → 1529），
            // 不铺 resolveViewDistance()=vd+1 的 authority 边距圈（那会多出 ~136 越形状柱）。
            int radius = Math.max(0, serverViewDistance > 0 ? serverViewDistance : DEFAULT_VIEW_DISTANCE);
            int redeliver = 0;
            for (ChunkPos pos : enumerateDiscBiased(homeChunk.x, homeChunk.z, radius)) {
                if (shadow.injectedChunk(currentDimension, pos.x, pos.z) != null) {
                    // 影子已有柱 ≠ 当前 ClientChunkCache 已有。切维后客户端是空的，
                    // 跳过会只留下圆心偏移的那一圈新格（返主实测 1529→23）。
                    if (!ShadowLightCompute.hasClientApplyEpoch(currentDimension, pos)) {
                        redeliverQueue.add(pos);
                        redeliver++;
                    }
                    continue;
                }
                bootGridCells.add(pos);
            }
            io.github.limuqy.mc.hassium.Constants.LOG.info(
                    "[SHADOW_TRACK] boot grid primed around ({},{}) radius={} cells={} redeliver={} (dimension={})",
                    homeChunk.x, homeChunk.z, radius, bootGridCells.size(), redeliver, currentDimension);
        }
        // 洪峰闸：两次光盘发射之间至少间隔 25ms，避免数百柱的无基线请求在同一心跳涌入服务端按需装载
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastBootEmitMs < BOOT_EMIT_MIN_GAP_MS) {
            return;
        }
        java.util.List<ChunkPos> withBaseline = new java.util.ArrayList<>();
        java.util.List<ChunkPos> withoutBaseline = new java.util.ArrayList<>();
        int sent = 0;
        while (sent < maxPerPump && !bootGridCells.isEmpty()) {
            ChunkPos pos = bootGridCells.pollFirst();
            if (shadow.injectedChunk(currentDimension, pos.x, pos.z) != null) {
                continue;
            }
            boolean hasBaseline = ShadowLightCompute.hasLocalPullBaseline(currentDimension, pos);
            if (!hasBaseline && preferLocalGeneration()) {
                continue; // SeedGen 本地生成，不占在途、不发 pull
            }
            if (!markPullInFlight(currentDimension, pos, nowMs)) {
                continue;
            }
            if (hasBaseline) {
                withBaseline.add(pos);
            } else {
                withoutBaseline.add(pos);
            }
            sent++;
        }
        if (sent > 0) {
            lastBootEmitMs = nowMs;
        }
        if (bootGridCells.isEmpty()) {
            // 整盘发射完毕（本批恰好掏空）：本会话盘面交付结束；断连重连重新落座时再武装。
            // 注意：不能在前面的 prime 块内解除武装——那会在首批发射后杀掉整张盘，
            // 外环柱（西弧/南北滞环）永远不会被请求（bootgrid 系列空洞的根因）。
            bootGridArmed = false;
        }
        emitPullGroups(withBaseline, withoutBaseline);
    }

    /** 逆飞行偏好枚举：先北方后南方交错混合，令背行侧冷柱提前获得按需装载机会。
     *  形状 = 原版可见圆角方形（{@link ChunkShapeCompat}，range = 通告视距 vd），
     *  与 isChunkInRange(vd) 同几何（VD20 → 1529），不铺 authority 边距外圈。 */
    private static java.util.List<ChunkPos> enumerateDiscBiased(int cx, int cz, int range) {
        java.util.List<ChunkPos> northHalf = new java.util.ArrayList<>(range * range);
        java.util.List<ChunkPos> southHalf = new java.util.ArrayList<>(range * range);
        for (int ring = 0; ring <= range + 1; ring++) {
            int perimeter = ring == 0 ? 1 : 8 * ring;
            for (int i = 0; i < perimeter; i++) {
                int x, z;
                if (ring == 0) {
                    x = cx;
                    z = cz;
                } else {
                    int side = i / (2 * ring);
                    int step = i % (2 * ring);
                    switch (side) {
                        case 0 -> { x = cx - ring; z = cz - ring + step; } // 北缘 西→东
                        case 1 -> { x = cx - ring + step; z = cz + ring; } // 东缘 北→南
                        case 2 -> { x = cx + ring; z = cz + ring - step; } // 南缘 东→西
                        default -> { x = cx + ring - step; z = cz - ring; } // 西缘 南→北
                    }
                }
                if (!io.github.limuqy.mc.hassium.compat.ChunkShapeCompat.contains(cx, cz, range, x, z)) {
                    continue; // 原版形状外（角区鬼影）不进盘
                }
                int dr = x - cx;
                int dc = z - cz;
                if (((dr + dc) & 1) == 0) {
                    northHalf.add(new ChunkPos(x, z));
                } else {
                    southHalf.add(new ChunkPos(x, z));
                }
            }
        }
        java.util.List<ChunkPos> mixed = new java.util.ArrayList<>(
                2 * Math.max(northHalf.size(), southHalf.size()));
        int ni = 0, si = 0;
        while (ni < northHalf.size() || si < southHalf.size()) {
            if (ni < northHalf.size()) {
                mixed.add(northHalf.get(ni++));
            }
            if (si < southHalf.size()) {
                mixed.add(southHalf.get(si++));
            }
        }
        return mixed;
    }

    /**
     * OVD 窗本地源：injected / disk。绝不发 ShadowPull。
     * 缺盘柱交给原版 tracking（视距已扩到 client VD，worldgen 本身并行），不再自管 generate 队列。
     */
    private void tryServeOvdLocal(ShadowSeedServer shadow, SelectedChunk sel) {
        if (shadow == null || currentDimension == null) {
            return;
        }
        ChunkPos pos = new ChunkPos(sel.x(), sel.z());
        if (shadow.injectedChunk(sel.dimension(), pos.x, pos.z) != null) {
            return; // 已物化，tracking 边沿会交付
        }
        net.minecraft.world.level.chunk.LevelChunk chunk = shadow.loadFromDisk(sel.dimension(), pos);
        if (chunk == null) {
            long missKey = io.github.limuqy.mc.hassium.utils.DimensionKey
                    .key(sel.dimension(), pos.x, pos.z);
            ovdMissRetryAt.put(missKey, System.currentTimeMillis() + 2_000L);
            recordOvdMissOnce(sel.dimension(), pos);
            return; // 无本地数据：等原版 tracking，不 pull
        }
        boolean diskHit = io.github.limuqy.mc.hassium.storage.ShadowStorageHashes
                .get(sel.dimension(), pos) != null;
        shadow.injectLoadedChunk(sel.dimension(), pos, chunk, SeedGenExecutor.persistAsDirty(diskHit));
        ShadowChunkMapCompat.completeSuspendedLoad(sel.dimension(), pos, chunk);
        // 立即 publish：外环可能不走 tracking 边沿 materialize，只靠 sweep 会漏交付。
        boolean published = ShadowLightCompute.publishOvdCachedChunk(sel.dimension(), pos);
        if (published) {
            recordOvdLoadedOnce(sel.dimension(), pos);
        }
        DebugLogger.info(DebugLogger.LogType.NETWORK,
                "[SHADOW_TRACK] OVD local serve ({}, {}) diskHit={} published={}",
                pos.x, pos.z, diskHit, published);
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

    private void recordOvdMissOnce(String dimension, ChunkPos pos) {
        long key = io.github.limuqy.mc.hassium.utils.DimensionKey.key(dimension, pos.x, pos.z);
        if (ovdMissCounted.add(key)) {
            io.github.limuqy.mc.hassium.metrics.NetworkStats.recordOvdMiss();
        }
    }

    /** 统一的 pull 分组发射（悬置柱 / 基准光盘共用）：有基线走 compare，无基线走权威 FULL。 */
    private void emitPullGroups(java.util.List<ChunkPos> withBaseline, java.util.List<ChunkPos> withoutBaseline) {
        String dimension = currentDimension;
        if (!withBaseline.isEmpty()) {
            DebugLogger.info(DebugLogger.LogType.NETWORK,
                    "[SHADOW_TRACK] compare-pull {} chunks (dimension={})",
                    withBaseline.size(), dimension);
            ShadowPullClient.requestFull(dimension, withBaseline);
        }
        if (!withoutBaseline.isEmpty()) {
            DebugLogger.info(DebugLogger.LogType.NETWORK,
                    "[SHADOW_TRACK] authoritative-full pull {} chunks (dimension={})",
                    withoutBaseline.size(), dimension);
            ShadowPullClient.requestAuthoritativeFull(dimension, withoutBaseline);
        }
    }

    /**
     * boot / 悬置选柱 / 形状扫描共用在途。{@code true} = 本柱尚未在途，调用方应发出 pull。
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

    /**
     * scheduleChunkLoad 影子钩子登记（worldgen 压制柱，无本地数据可悬置）：
     * 由影子主循环泵分批发空基线（或磁盘基线）pull 请求。chunk worker 线程可调。
     */
    public void onChunkSelected(String dimension, int x, int z) {
        pendingSelections.add(new SelectedChunk(dimension, x, z));
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
        boolean diskHit = io.github.limuqy.mc.hassium.storage.ShadowStorageHashes
                .get(dimension, pos) != null;
        // 首注入且无盘 hash = 本会话 vanilla worldgen 本地生成（门控开路径）；
        // 网络 FULL 经 injectChunk 入表后再走本桥时 alreadyMaterialized=true，不会误计。
        boolean localWorldgen = SeedGenExecutor.isFreshLocalWorldgen(alreadyMaterialized, diskHit);
        if (!alreadyMaterialized) {
            shadow.injectLoadedChunk(dimension, pos, chunk,
                    SeedGenExecutor.persistAsDirty(diskHit));
        }
        if (localWorldgen) {
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
            if (!markPullInFlight(dimension, pos, System.currentTimeMillis())) {
                return;
            }
            if (ShadowLightCompute.hasLocalPullBaseline(dimension, pos)) {
                ShadowPullClient.requestFull(dimension, java.util.List.of(pos));
            } else {
                ShadowPullClient.requestAuthoritativeFull(dimension, java.util.List.of(pos));
            }
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
            if (!markPullInFlight(dimension, pos, System.currentTimeMillis())) {
                return;
            }
            if (ShadowLightCompute.hasLocalPullBaseline(dimension, pos)) {
                ShadowPullClient.requestFull(dimension, java.util.List.of(pos));
            } else {
                ShadowPullClient.requestAuthoritativeFull(dimension, java.util.List.of(pos));
            }
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
                if (markPullInFlight(dimension, pos, System.currentTimeMillis())) {
                    ShadowPullClient.requestAuthoritativeFull(dimension, java.util.List.of(pos));
                }
                return;
            }
            if (ShadowLightCompute.tryRequestMiss(dimension, pos)
                    && markPullInFlight(dimension, pos, System.currentTimeMillis())) {
                ShadowPullClient.requestFull(dimension, java.util.List.of(pos));
            }
            return;
        }
        // 本会话网络全量已注入/落地：交付由 SERVER_PUSH/REMOTE_PULL 路径完成。
        // 再 publishCachedChunk 会把同一柱改记成 MEMORY_CACHE 假全命中（R1 1219 假命中根因）。
        if (alreadyMaterialized
                && (ShadowLightCompute.wasNetworkIngress(dimension, pos)
                    || ShadowLightCompute.hasClientApplyEpoch(dimension, pos))) {
            if (ShadowLightCompute.tryRequestMiss(dimension, pos)
                    && markPullInFlight(dimension, pos, System.currentTimeMillis())) {
                ShadowPullClient.requestFull(dimension, java.util.List.of(pos));
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
            if (markPullInFlight(dimension, pos, System.currentTimeMillis())) {
                ShadowPullClient.requestAuthoritativeFull(dimension, java.util.List.of(pos));
            }
            return;
        }
        DebugLogger.info(DebugLogger.LogType.NETWORK,
                "[SHADOW_TRACK] materialized ({}, {}) alreadyMaterialized={} -> publishCached (dimension={})",
                pos.x, pos.z, alreadyMaterialized, dimension);
        // 已本地交付后，可选对真实服 compare 保新鲜；防抖只作用于网络请求
        if (ShadowLightCompute.tryRequestMiss(dimension, pos)
                && markPullInFlight(dimension, pos, System.currentTimeMillis())) {
            ShadowPullClient.requestFull(dimension, java.util.List.of(pos));
        }
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
        s.pendingSelections.clear();
        s.sweepInFlight.clear();
        s.redeliverQueue.clear();
        s.ovdCounted.clear();
        s.ovdMissCounted.clear();
        s.ovdMissRetryAt.clear();
        s.lastOvdSweepMs = 0;
        s.homeChunk = null;
        s.bootGridArmed = false;
        s.bootGridCells.clear();
        s.lastBootEmitMs = 0;
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
