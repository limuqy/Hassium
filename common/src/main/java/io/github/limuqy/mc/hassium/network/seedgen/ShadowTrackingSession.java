package io.github.limuqy.mc.hassium.network.seedgen;

import io.github.limuqy.mc.hassium.compat.ChunkShapeCompat;
import io.github.limuqy.mc.hassium.compat.LevelCompat;
import io.github.limuqy.mc.hassium.compat.ShadowPlayerCompat;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.ShadowPullClient;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
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
    /** 形状扫描在途柱（复合键 → 入队时刻）：已发 pull 未注入；注入后清除。防重复入队。
     *  超时清除——pull 响应丢失/服务端拒绝时柱会永久卡在在途集导致形状出现永久洞。 */
    private static final long SWEEP_INFLIGHT_TIMEOUT_MS = 15_000L;
    private final java.util.Map<Long, Long> sweepInFlight =
            new java.util.concurrent.ConcurrentHashMap<>();

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
            if (level != null) {
                try {
                    ShadowPlayerCompat.tickChunkSystem(level,
                            System.nanoTime() + CHUNK_TICK_BUDGET_NANOS);
                } catch (Throwable t) {
                    DebugLogger.warn(DebugLogger.LogType.ASYNC,
                            "[SHADOW_TRACK] chunk system tick failed", t);
                }
            }
        }
        drainBootGrid(shadow, MAX_REQUESTS_PER_PUMP);
        drainSelections(shadow, MAX_REQUESTS_PER_PUMP);
        sweepVisibleShape(shadow, now);
        drainRedeliver(shadow);
    }

    /**
     * 真实客户端 unload 时调用（任意线程）：清光桥凭据；若影子仍在可见形状内且有数据，
     * 入重发队列。对齐原版语义——服务端仍把你算在 tracking 窗内就必须有数据；
     * 真实服 Forget 半径可能小于影子 vd，不能等 tracking 边沿。
     */
    public void onClientChunkUnloaded(ChunkPos pos) {
        ShadowLightCompute.onClientChunkUnloaded(pos);
        if (pos == null || boundServer == null || currentDimension == null) {
            return;
        }
        if (!inVanillaVisibleShape(pos.x, pos.z)) {
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
            if (!inVanillaVisibleShape(pos.x, pos.z)) {
                continue;
            }
            if (ShadowLightCompute.hasClientApplyEpoch(currentDimension, pos)) {
                continue; // 已有落地凭据
            }
            // 本会话网络路径已在途/已记账：redeliver 不得再记成缓存全命中
            if (ShadowLightCompute.wasNetworkIngress(currentDimension, pos)) {
                continue;
            }
            if (ShadowLightCompute.publishCachedChunk(currentDimension, pos)) {
                DebugLogger.info(DebugLogger.LogType.NETWORK,
                        "[SHADOW_TRACK] redeliver ({}, {}) -> publishCached (dimension={})",
                        pos.x, pos.z, currentDimension);
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
            return; // 维度未装配（自定义维度）：影子端跳过，保持原 tracking 降级
        }
        boolean dimensionChanged = !state.dimension().equals(currentDimension);
        ChunkPos newChunk = new ChunkPos((int) state.x() >> 4, (int) state.z() >> 4);
        if (dimensionChanged) {
            try {
                ShadowPlayerCompat.teleportVirtualPlayer(virtualPlayer, target,
                        state.x(), state.y(), state.z(), state.yRot(), state.xRot());
                currentDimension = state.dimension();
                DebugLogger.info(DebugLogger.LogType.ASYNC,
                        "[SHADOW_TRACK] dimension switch -> {}", currentDimension);
            } catch (Throwable t) {
                DebugLogger.warn(DebugLogger.LogType.ASYNC,
                        "[SHADOW_TRACK] dimension teleport failed -> {}",
                        t, state.dimension());
            }
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

    /** 创建虚拟玩家并进入 tracking（仅影子线程；失败置 createFailed 会话降级）。 */
    private void ensureVirtualPlayer(ShadowSeedServer shadow, PendingState state) {
        ServerLevel level = shadow.level(state.dimension());
        if (level == null) {
            io.github.limuqy.mc.hassium.Constants.LOG.warn(
                    "[SHADOW_TRACK] shadow level not assembled for {}; session deferred", state.dimension());
            return;
        }
        io.github.limuqy.mc.hassium.Constants.LOG.info(
                "[SHADOW_TRACK] creating virtual player (dimension={}, serverRadius={})",
                state.dimension(), serverViewDistance);
        try {
            int viewDistance = resolveViewDistance();
            ShadowPlayerCompat.setChunkViewDistance(level, viewDistance);
            appliedViewDistance = viewDistance;
            net.minecraft.network.Connection connection = ShadowPlayerCompat.createConnectionStub();
            ServerPlayer player = ShadowPlayerCompat.createVirtualPlayer(shadow, level);
            ShadowPlayerCompat.placePlayer(shadow, connection, player);
#if MC_VER < MC_1_21_5
            player.absMoveTo(state.x(), state.y(), state.z(), state.yRot(), state.xRot());
#else
            player.teleportTo(state.x(), state.y(), state.z());
            player.setYRot(state.yRot());
            player.setXRot(state.xRot());
#endif
            ShadowPlayerCompat.moveVirtualPlayer(player);
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
        // 原版玩家 tracking 半径（ChunkMap.setViewDistance 用 viewDistance+1 构造 tracking
        // view）：形状轴深 = vd+2，恰好 = 服务端签发上界（ShadowPullHandler maxDistance =
        // vd + AUTHORITY_MARGIN）。光照邻域不再额外外扩——邻柱后到时 LightDelta
        // 自愈链路（collectLightUpdate → drainLightMasks）修正边缘光。
        int radius = serverViewDistance > 0
                ? serverViewDistance + 1
                : DEFAULT_VIEW_DISTANCE + 1;
        return Math.min(radius, MAX_VIEW_DISTANCE);
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
     * 原版可见形状内？pull 域只拉用户能看到的柱（§0.4）：range = 通告视距 vd，
     * 谓词 = {@code isChunkInRange}（VD20 → 1529）。影子 ticket 可略宽（vd+1）供
     * 算光邻域，但越形状柱不向真实客户端 compare-pull；边缘光靠邻柱后到自愈。
     * 中心 = 虚拟玩家实时位（半径未知时放行，交给服务端校验）。
     */
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
                continue;
            }
            if (shadow.injectedChunk(sel.dimension(), sel.x(), sel.z()) != null) {
                continue; // 已物化（注入/本地生成），无需 pull
            }
            ChunkPos pos = new ChunkPos(sel.x(), sel.z());
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
     * 形状 = isChunkInRange(serverViewDistance)；已注入 / 已请求（tryRequestMiss 已登记）跳过。
     */
    private void sweepVisibleShape(ShadowSeedServer shadow, long nowMs) {
        if (virtualPlayer == null || currentDimension == null || serverViewDistance <= 0) {
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
                    sweepInFlight.remove(io.github.limuqy.mc.hassium.utils.DimensionKey
                            .key(currentDimension, x, z));
                    // 已注入但客户端无落地凭据（真实服半径更小导致 Forget，或 tracking 边沿漏发）：
                    // 入重发队列，由 drainRedeliver 限速 publish
                    ChunkPos injectedPos = new ChunkPos(x, z);
                    if (!ShadowLightCompute.hasClientApplyEpoch(currentDimension, injectedPos)
                            && redeliverQueue.size() < MAX_REDELIVER_PER_PUMP * 4) {
                        redeliverQueue.add(injectedPos);
                    }
                    continue;
                }
                // 在途防抖：已发 pull 未注入的柱不重复入队（超时后可重入）
                long key = io.github.limuqy.mc.hassium.utils.DimensionKey
                        .key(currentDimension, x, z);
                if (sweepInFlight.putIfAbsent(key, nowMs) != null) {
                    continue;
                }
                ChunkPos pos = new ChunkPos(x, z);
                if (ShadowLightCompute.hasLocalPullBaseline(currentDimension, pos)) {
                    withBaseline.add(pos);
                } else {
                    withoutBaseline.add(pos);
                }
                sent++;
            }
        }
        if (sent > 0) {
            DebugLogger.info(DebugLogger.LogType.NETWORK,
                    "[SHADOW_TRACK] sweep missing={} center=({},{}) radius={} (dimension={})",
                    sent, center.x, center.z, radius, currentDimension);
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
            for (ChunkPos pos : enumerateDiscBiased(homeChunk.x, homeChunk.z, radius)) {
                if (shadow.injectedChunk(currentDimension, pos.x, pos.z) != null) {
                    continue; // 已有本地数据（注入/读盘/已生成），无需请求
                }
                bootGridCells.add(pos);
            }
            DebugLogger.info(DebugLogger.LogType.NETWORK,
                    "[SHADOW_TRACK] boot grid primed around ({},{}) radius={} cells={} (dimension={})",
                    homeChunk.x, homeChunk.z, radius, bootGridCells.size(), currentDimension);
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
            if (ShadowLightCompute.hasLocalPullBaseline(currentDimension, pos)) {
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
     * scheduleChunkLoad 影子钩子登记（worldgen 压制柱，无本地数据可悬置）：
     * 由影子主循环泵分批发空基线（或磁盘基线）pull 请求。chunk worker 线程可调。
     */
    public void onChunkSelected(String dimension, int x, int z) {
        pendingSelections.add(new SelectedChunk(dimension, x, z));
    }

    /**
     * 原版链产出桥 = **tracking 进范围边沿**（§6.0）：`playerLoadedChunk` HEAD 派发。
     * 注入影子表形成本地基线后，**必向真实客户端交付**（等价原版 trackChunk），
     * 不得用会话防抖挡交付。compare-pull 仅用于对真实服务端的新鲜度/省带宽。
     * 影子主循环线程调用。
     */
    public void onChunkMaterialized(String dimension, ChunkPos pos,
                                    net.minecraft.world.level.chunk.LevelChunk chunk) {
        ShadowSeedServer shadow = boundServer;
        if (shadow == null || dimension == null || pos == null || chunk == null) {
            return;
        }
        boolean alreadyMaterialized = shadow.injectedChunk(dimension, pos.x, pos.z) != null;
        // 磁盘命中柱的 hash 在 scheduleChunkLoad 读盘时由 MixinRegionFile 回填；
        // 生成柱无 hash → dirty（saveAll 落盘）。1.20.1 无 getPersistedStatus，按 hash 判别。
        boolean diskHit = io.github.limuqy.mc.hassium.storage.ShadowStorageHashes
                .get(dimension, pos) != null;
        if (!alreadyMaterialized) {
            shadow.injectLoadedChunk(dimension, pos, chunk, !diskHit);
        }
        // 影子 ticket 可能物化原版可见形状外的角区柱（setChunkViewDistance=vd+1）：
        // 仍注入影子表供算光邻域，但不向真实客户端交付（§6.2 只推用户能看到的）。
        if (!inVanillaVisibleShape(pos.x, pos.z)) {
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
            if (ShadowLightCompute.hasLocalPullBaseline(dimension, pos)) {
                ShadowPullClient.requestFull(dimension, java.util.List.of(pos));
            } else {
                ShadowPullClient.requestAuthoritativeFull(dimension, java.util.List.of(pos));
            }
            return;
        }
        // 本会话网络全量已注入/落地：交付由 SERVER_PUSH/REMOTE_PULL 路径完成。
        // 再 publishCachedChunk 会把同一柱改记成 MEMORY_CACHE 假全命中（R1 1219 假命中根因）。
        if (alreadyMaterialized
                && (ShadowLightCompute.wasNetworkIngress(dimension, pos)
                    || ShadowLightCompute.hasClientApplyEpoch(dimension, pos))) {
            if (ShadowLightCompute.tryRequestMiss(dimension, pos)) {
                ShadowPullClient.requestFull(dimension, java.util.List.of(pos));
            }
            return;
        }
        // 进边沿必交付：等价原版 trackChunk（仅真正的本地基线：盘上命中 / 上一会话缓存）
        boolean published = ShadowLightCompute.publishCachedChunk(dimension, pos);
        if (!published) {
            DebugLogger.info(DebugLogger.LogType.NETWORK,
                    "[SHADOW_TRACK] materialized ({}, {}) publish-failed -> pull (dimension={})",
                    pos.x, pos.z, dimension);
            ShadowLightCompute.clearRequestMiss(dimension, pos);
            ShadowPullClient.requestAuthoritativeFull(dimension, java.util.List.of(pos));
            return;
        }
        DebugLogger.info(DebugLogger.LogType.NETWORK,
                "[SHADOW_TRACK] materialized ({}, {}) alreadyMaterialized={} -> publishCached (dimension={})",
                pos.x, pos.z, alreadyMaterialized, dimension);
        // 已本地交付后，可选对真实服 compare 保新鲜；防抖只作用于网络请求
        if (ShadowLightCompute.tryRequestMiss(dimension, pos)) {
            ShadowPullClient.requestFull(dimension, java.util.List.of(pos));
        }
    }

    /**
     * 网络全量已由 {@code enqueueInjectedForLight} 入 generated（保留网络来源）。
     * 本方法只清形状扫描在途登记，<b>不得</b>再 publishCachedChunk——否则同一柱
     * 来源被覆盖成 MEMORY_CACHE，R1 首进被误记成缓存全命中。
     */
    public void onNetworkChunkQueued(String dimension, ChunkPos pos) {
        if (pos == null) {
            return;
        }
        sweepInFlight.remove(io.github.limuqy.mc.hassium.utils.DimensionKey
                .key(dimension == null ? currentDimension : dimension, pos.x, pos.z));
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
        sweepInFlight.remove(io.github.limuqy.mc.hassium.utils.DimensionKey
                .key(dimension, pos.x, pos.z));
        if (!inVanillaVisibleShape(pos.x, pos.z)) {
            return;
        }
        DebugLogger.info(DebugLogger.LogType.NETWORK,
                "[SHADOW_TRACK] pull-injected ({}, {}) -> publishCached (dimension={})",
                pos.x, pos.z, dimension);
        if (!ShadowLightCompute.publishCachedChunk(dimension, pos)) {
            // 注入表有柱但不可物化（异常）：再拉一次权威全量
            ShadowLightCompute.clearRequestMiss(dimension, pos);
            ShadowPullClient.requestAuthoritativeFull(dimension, java.util.List.of(pos));
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
        s.appliedViewDistance = -1;
        s.boundServer = null;
        s.virtualPlayer = null;
        s.currentDimension = null;
        s.createFailed = false;
        s.pendingSelections.clear();
        s.sweepInFlight.clear();
        s.redeliverQueue.clear();
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
