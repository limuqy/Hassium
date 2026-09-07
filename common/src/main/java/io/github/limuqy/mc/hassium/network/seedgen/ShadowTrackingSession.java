package io.github.limuqy.mc.hassium.network.seedgen;

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
     *  滞留环 —— 移动窗口天然不对称，绕该点为半径 22 的静态「基准光盘」恢复老推送时代
     *  达成过的完整圆心柱形（1268 vs 1517 的缺口正是朝反向运动的半边圆环，见 handover §0）。 */
    private ChunkPos homeChunk;
    /** 基准光盘已布防待铺（ensureVirtualPlayer 落位后置真；单元格耗尽清除）。 */
    private boolean bootGridArmed;
    /** 基准光盘单元格队列（螺旋由近及远；半径 = 当时 resolveViewDistance()）。 */
    private final java.util.ArrayDeque<ChunkPos> bootGridCells = new java.util.ArrayDeque<>();
    /** 相邻两次光盘发射的最小间隔（毫秒）：防百柱级无基线请求同心跳灌入服务端按需装载。 */
    private static final long BOOT_EMIT_MIN_GAP_MS = 25L;
    private long lastBootEmitMs;

    /** 修复补给池扫描周期：boot 首轮一次性燃烧完毕后，每隔此间隔从池中回充仍未落地的列。 */
    private static final long REPAIR_INTERVAL_NS = 2_000_000_000L;
    /** 每次修复扫描最多回充发射队列的格子数（≈ 48/2s = 24/s 补给流量，
     *  与服务端 FORCED-demand 按需装载预算同量级，不会重现 pull9 风暴量级）。 */
    private static final int MAX_REPAIR_PER_SWEEP = 521;
    /** 修复补给池（与基准光盘同源的欧氏半径圆柱清单）：已取得基线/注入的列在被扫描时
     *  永久退休；尚未落地者在发射间隙低频回充 bootGridCells 排队（同一 25ms 闸限流）。 */
    private final java.util.ArrayList<ChunkPos> repairPool = new java.util.ArrayList<>();
    private long lastRepairSweepNs;

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
            virtualPlayer.absMoveTo(state.x(), state.y(), state.z(), state.yRot(), state.xRot());
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
            player.absMoveTo(state.x(), state.y(), state.z(), state.yRot(), state.xRot());
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
        // 服务端权威半径 + 外扩边距（与各 loader ShadowPullHandler maxDistance =
        // 真实视距 + ShadowPullRadii.AUTHORITY_MARGIN 对齐）：确保阴影选择窗口 ⊇ 可签发
        // 环形请求，身前身后对称补偿（身侧/身后滞留环也能被选择到）。
        int radius = serverViewDistance > 0
                ? serverViewDistance + io.github.limuqy.mc.hassium.network.ShadowPullRadii.AUTHORITY_MARGIN
                : DEFAULT_VIEW_DISTANCE + io.github.limuqy.mc.hassium.network.ShadowPullRadii.AUTHORITY_MARGIN;
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
            ChunkPos pos = new ChunkPos(sel.x(), sel.z());
            if (shadow.injectedChunk(sel.dimension(), sel.x(), sel.z()) != null) {
                continue; // 已物化（注入/本地生成），无需 pull
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
            // 一张盘多次发射：chebyshev 直径盘面绕落位点，路径序遍历（反向侧南方的起动冷负荷先前置、
            // 尽量均匀）而不是机械整数螺旋——练习周期太短时北方冷柱容易在场次收束前还没孵化。
            int radius = resolveViewDistance();
            for (ChunkPos pos : enumerateDiscBiased(homeChunk.x, homeChunk.z, radius)) {
                if (shadow.injectedChunk(currentDimension, pos.x, pos.z) != null) {
                    continue; // 已有本地数据（注入/读盘/已生成），无需请求
                }
                bootGridCells.add(pos);
            }
            DebugLogger.info(DebugLogger.LogType.NETWORK,
                    "[SHADOW_TRACK] boot grid primed around ({},{}) radius={} cells={} (dimension={})",
                    homeChunk.x, homeChunk.z, radius, bootGridCells.size(), currentDimension);
            bootGridArmed = false;
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
        emitPullGroups(withBaseline, withoutBaseline);
    }

    /** 逆飞行偏好枚举：先北方后南方交错混合，令背行侧冷柱提前获得按需装载机会。
     *  只枚举欧氏半径内（整圆柱，历史 VD20=1517 同类），正方形四角的鬼影不生成。 */
    private static java.util.List<ChunkPos> enumerateDiscBiased(int cx, int cz, int radius) {
        java.util.List<ChunkPos> northHalf = new java.util.ArrayList<>(radius * radius);
        java.util.List<ChunkPos> southHalf = new java.util.ArrayList<>(radius * radius);
        for (int ring = 0; ring <= radius; ring++) {
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
                int dr = x - cx;
                int dc = z - cz;
                if (dr * dr + dc * dc > radius * radius) {
                    continue; // 直角四角裁掉，恢复历史整圆柱几何
                }
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
     * 原版链产出桥（§6 节点 E/G → I）：读盘命中柱 / 本地生成柱注入影子表形成本地
     * 基线，携带基线发统一比对请求；服务端裁决 UNCHANGED/DELTA/FULL 后经既有响应
     * 路径落地（UNCHANGED → publishCachedChunk 发布注入物 / FULL → 服务端数据覆盖）。
     * 影子主循环线程（playerLoadedChunk 派发）调用；tryRequestMiss 会话级防抖。
     */
    public void onChunkMaterialized(String dimension, ChunkPos pos,
                                    net.minecraft.world.level.chunk.LevelChunk chunk) {
        ShadowSeedServer shadow = boundServer;
        if (shadow == null || dimension == null || pos == null || chunk == null) {
            return;
        }
        boolean alreadyMaterialized = shadow.injectedChunk(dimension, pos.x, pos.z) != null;
        if (!alreadyMaterialized) {
            // 磁盘命中柱的 hash 在 scheduleChunkLoad 读盘时由 MixinRegionFile 回填；
            // 生成柱无 hash → dirty（saveAll 落盘）。1.20.1 无 getPersistedStatus，按 hash 判别。
            boolean diskHit = io.github.limuqy.mc.hassium.storage.ShadowStorageHashes
                    .get(dimension, pos) != null;
            shadow.injectLoadedChunk(dimension, pos, chunk, !diskHit);
        }
        // 已物化柱（R2 复用影子世界）同样要发比对：影子世界有 ≠ 真实客户端新世界有，
        // UNCHANGED → publishCachedChunk 即完成向真实客户端的重交付。
        if (ShadowLightCompute.tryRequestMiss(dimension, pos)) {
            DebugLogger.info(DebugLogger.LogType.NETWORK,
                    "[SHADOW_TRACK] materialized ({}, {}) alreadyMaterialized={} -> compare-pull (dimension={})",
                    pos.x, pos.z, alreadyMaterialized, dimension);
            ShadowPullClient.requestFull(dimension, java.util.List.of(pos));
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
        s.homeChunk = null;
        s.bootGridArmed = false;
        s.bootGridCells.clear();
        s.lastBootEmitMs = 0;
        s.repairPool.clear();
        s.lastRepairSweepNs = 0;
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
