package io.github.limuqy.mc.hassium.network.seedgen;

import io.github.limuqy.mc.hassium.compat.ChunkShapeCompat;
import io.github.limuqy.mc.hassium.concurrent.ChunkDistancePriority;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DimensionKey;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * 影子端选柱的**本地整方形票驱动**（P5 第 2 步）。
 * <p>
 * 动机：虚拟玩家今天唯一不可替代的贡献是「按原版 tracking 铺一张票方形」。既然
 * {@code resolveViewDistance()} 给出的装载几何**本身就是纯本地函数**（中心 = 客户端当前位置，
 * range = {@code min(max(serverVD+1, effectiveClientVD), 32)}），这张方形可以本地枚举出来直接出票，
 * 不必借道一个假 {@code ServerPlayer} 的 player tracking。
 * <p>
 * <b>为什么是「一张方形」而不是「权威窗 + OVD 圈两套票」</b>：OVD 从来不是独立机制，它就是
 * 同一张方形在 {@code serverVD} 之外的那一环（range 取的是 {@code max}）。历史上「服务端出票 +
 * OVD 独立出票」之所以割裂，是因为那两套票**中心不同、节奏不同、优先级不同**，错位的那一圈
 * 起了缝。本类刻意只维护**一张**方形：同中心（沿用会话的唯一位置真相源）、同形状
 * （{@link ChunkShapeCompat} 原版谓词，禁止自绘）、同节奏（几何一变就整体对账），因此缝无处可生。
 * <p>
 * <b>对账式而非事件队列</b>：目标集合由（维度, 中心, range）唯一决定，每次几何变化就
 * 「补齐缺失、撤掉越界」，天然幂等、天然可续（每次只做限量 ops，下次继续对账），也不需要
 * 会话代数戳——不存在"排队中的旧登记"这回事。
 * <p>
 * <b>顺序复刻 vanilla</b>：增票**由近及远**（{@link ChunkDistancePriority}），复刻原版的距离填充
 * 涟漪；撤票**由远及近**，且固定在增票之后——先增后删才能在边界抖动时不出现瞬时空洞。
 * <p>
 * 线程纪律：{@link #consumeOnShadowLoop} 必须在影子主循环上调用（vanilla {@code DistanceManager}
 * 单线程）；{@link #requestClear} 任意线程可调（只置一个标志）。
 */
public final class ShadowTicketDriver {

    /**
     * P5「接管」总开关——<b>唯一开关</b>，驱动出票与钝化 tracking 选柱恒等联动。
     * <p>
     * 教训：这两件事原先各有一个布尔量，我曾把 {@code ENABLED=false} 配着 {@code NEUTRALIZE_TRACKING=true}
     * 跑完整整一轮冒烟——结果是"驱动没跑、tracking 也没钝化"，测出来的其实是纯基线，我却当成环带设计的
     * 验证读了一轮（`[SHADOW_TICKET]` 连无条件打印的首绑行都是 0 条，是当时唯一在场的铁证）。故现在
     * 收成单开关：要么整体接管（驱动出票 + 钝化 tracking），要么整体关闭，不存在配错的中间态。
     * <p>
     * 2026-09-13 第五轮后默认开启：classic + dimension + seedgen 接管矩阵已 PASS
     * （见 {@code docs/handoff/handoff-2026-09-13-authority-edge-p5-verdict.md}）。
     */
    private static final boolean P5_TAKEOVER = true;

    /** 驱动总开关（恒等于 {@link #P5_TAKEOVER}）。 */
    static final boolean ENABLED = P5_TAKEOVER;

    /**
     * 正交实验开关（恒等于 {@link #P5_TAKEOVER}）：把影子 ChunkMap 的 tracking 半径压到最小，
     * 使 **OVD 环带**的装载只能由本驱动提供。
     * <p>
     * 隔离强度：权威窗的装载**本来就不靠票**（`drainBootGrid` / `sweepVisibleShape` 两条直接发 pull
     * 的驱动覆盖，实测 `1.21.1_fabric_I_otmove4` 的 R1 仅 256 张在售票即交付 1520 柱），所以本开关的
     * 净效果是精确地"只掐 OVD 环带缺盘柱的票源"——那恰好是本驱动要证明能替代的那一环。
     */
    static final boolean NEUTRALIZE_TRACKING = P5_TAKEOVER;

    /** 钝化后的 tracking 视距（1 = 玩家所在柱的 3x3；不取 0 以避开退化值）。 */
    static final int NEUTRALIZED_VIEW_DISTANCE = 1;

    /** 出票上限（OVD 环带正常规模 ≤ 数千）。 */
    private static final int MAX_TICKETS = 4096;

    /**
     * 单次对账的增/撤票预算（几何大变时跨拍续做）。
     * <p>
     * 目标集合是 OVD 环带，正常规模几十到数百张（VD10 + clientRD16 → 球差 ≈ 648）。每张
     * {@code FORCED} 票是 level 31（FULL），故预算就是突发宽度：一拍塞几百张会在单次影子主循环
     * 迭代里触发大量同步装载/生成。实测 512/拍在 1.21.1 移动场景出现运行期原生终止
     * （{@code 0xCFFFFFFF}，无 Java 痕迹），故收到 64/拍——与 vanilla 逐 tick 铺开的节奏同量级。
     */
    private static final int MAX_ADDS_PER_PUMP = 64;
    private static final int MAX_REMOVES_PER_PUMP = 256;

    /** 已出票集合（仅影子主循环线程读写）。 */
    private static final Set<Long> ticketed = new HashSet<>();

    /**
     * 声明驱动的本地生成候选（③，2026-09-13）：{@code ChunkAuthorityClient.resolve} 无基线 + SeedGen
     * 门控开时注册（Render 线程写），影子主循环消费投递 {@code generateChunkAsync} 显式触发 vanilla
     * worldgen —— 接管态（tracking 钝化）下这是权威窗内本地生成的唯一触发源（FORCED 票在影子端
     * 不被 ChunkMap tick 消化，见 2026-09-13 实证）。生产态（tracking 未钝化）下与 tracking 并存无害
     * （ChunkMap 对同一柱只生成一次，onChunkMaterialized 幂等）。
     */
    private static final java.util.Set<Long> pendingLocalGen =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 本地生成中（已投递 generateChunkAsync，防重复投递；物化/失败后移除）。 */
    private static final java.util.Set<Long> localGenInFlight =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 本地生成失败（超时/异常）：该柱本次会话回退网络 FULL（resolve 直出 AUTHORITATIVE_PULL）。 */
    private static final java.util.Set<Long> localGenFailed =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 每拍投递上限（小池 + 影子主循环单线程消化物化，过大会把主循环占满）。 */
    private static final int MAX_LOCAL_GEN_PER_PUMP = 24;
    /** 生成中在途上限（超出暂停投递，等物化/失败腾位）。 */
    private static final int MAX_LOCAL_GEN_INFLIGHT = 64;

    /** 待清账（会话边界；影子主循环执行撤票）。 */
    private static final AtomicBoolean pendingClear = new AtomicBoolean();

    /** 本驱动对应的影子实例（实例更换即账本作废：旧票随旧世界销毁）。 */
    private static ShadowSeedServer boundShadow;

    /** 上次对账的几何（仅影子主循环线程读写）：全同则跳过枚举。 */
    private static String lastDimension;
    private static int lastCenterX = Integer.MIN_VALUE;
    private static int lastCenterZ = Integer.MIN_VALUE;
    private static int lastAuthorityRange = -1;
    private static int lastClientRadius = -1;

    private ShadowTicketDriver() {
    }

    /** 试验是否在「钝化 tracking 选柱」模式（供会话侧决定给 ChunkMap 下发多大视距）。 */
    public static boolean trackingSelectionNeutralized() {
        return ENABLED && NEUTRALIZE_TRACKING;
    }

    /**
     * 声明驱动本地生成候选（③）：{@code resolve} 无基线 + SeedGen 门控开时注册。
     * 任意线程可调；去重由 {@code pendingLocalGen} / {@code localGenInFlight} 短路。
     */
    public static void registerLocalGeneration(String dimension, ChunkPos pos) {
        if (dimension == null || pos == null) {
            return;
        }
        long key = DimensionKey.key(dimension, pos.x, pos.z);
        if (localGenFailed.contains(key)) {
            return; // 已判本地生成失败：回退网络，不重复注册
        }
        pendingLocalGen.add(key);
    }

    /** 本地生成失败标记查询（{@code resolve} 回退网络 FULL 用）。 */
    public static boolean isLocalGenFailed(String dimension, ChunkPos pos) {
        return dimension != null && pos != null
                && localGenFailed.contains(DimensionKey.key(dimension, pos.x, pos.z));
    }

    /**
     * 影子主循环消费本地生成候选：投递 {@link ShadowSeedServer#generateChunkAsync} 显式触发
     * vanilla worldgen（worker 线程执行，主循环回调 {@code onChunkMaterialized} 物化桥 —— 绕过
     * 1.20.1 {@code playerLoadedChunk} 的 tracking 依赖）。已注入 / 生成中 / 失败柱短路。
     */
    private static void consumeLocalGeneration(ShadowSeedServer shadow) {
        if (pendingLocalGen.isEmpty()) {
            return;
        }
        int ops = 0;
        for (Long key : pendingLocalGen) {
            if (ops >= MAX_LOCAL_GEN_PER_PUMP || localGenInFlight.size() >= MAX_LOCAL_GEN_INFLIGHT) {
                break;
            }
            String dimension = DimensionKey.dimensionOf(key);
            ServerLevel level = dimension == null ? null : shadow.level(dimension);
            if (level == null) {
                pendingLocalGen.remove(key);
                continue;
            }
            ChunkPos pos = new ChunkPos(DimensionKey.chunkXOf(key), DimensionKey.chunkZOf(key));
            if (shadow.injectedChunk(dimension, pos.x, pos.z) != null) {
                pendingLocalGen.remove(key); // 其它路径已注入：无需生成
                continue;
            }
            if (!pendingLocalGen.remove(key)) {
                continue;
            }
            localGenInFlight.add(key);
            ops++;
            final String dim = dimension;
            shadow.generateChunkAsync(dimension, pos, (d, chunk) -> {
                localGenInFlight.remove(key);
                if (chunk == null) {
                    // 失败必须立刻网络兜底：resolve 只在声明到达时跑一次，等下一次 enter 可能永不来
                    //（接管态 tracking 3x3 + 让位门抑制自绘 pull，失败柱会永久空洞——test2 实证 151 失败仅 1 次 auth-full）。
                    localGenFailed.add(key);
                    DebugLogger.warn(DebugLogger.LogType.NETWORK,
                            "[SHADOW_TICKET] local gen failed ({}, {}) -> network FULL (dimension={})",
                            pos.x, pos.z, dim);
                    io.github.limuqy.mc.hassium.network.ShadowPullClient.requestAuthoritativeFull(
                            dim, java.util.List.of(pos));
                    return;
                }
                io.github.limuqy.mc.hassium.network.seedgen.ShadowTrackingSession.getInstance()
                        .onChunkMaterialized(dim, pos, chunk);
            });
        }
        if (ops > 0) {
            DebugLogger.info(DebugLogger.LogType.NETWORK,
                    "[SHADOW_TICKET] local-gen dispatched {} (pending={} inFlight={})",
                    ops, pendingLocalGen.size(), localGenInFlight.size());
        }
    }

    /** 已出票柱数（诊断）。 */
    public static int ticketCount() {
        return ticketed.size();
    }

    /** 会话边界（{@code ShadowTrackingSession.reset} 调用）：请影子主循环撤票清账。任意线程可调。 */
    public static void requestClear() {
        if (ENABLED) {
            pendingClear.set(true);
        }
    }

    /**
     * 影子主循环对账入口。
     *
     * <p>目标集合 = <b>OVD 环带</b>（{@link ChunkShapeCompat#inOvdBand}，与 {@code inOvdWindow} 同一判据）：
     * client 半径切比雪夫窗内、且不在 authority 形状内。之所以只票这一环：
     * <ul>
     *   <li>权威窗内的装载由 `drainBootGrid` / `sweepVisibleShape` 两条**直接发 pull** 的驱动承担
     *       （实测 R1 仅 256 张在售票即交付 1520 柱），不需要票；</li>
     *   <li>OVD 环带里**有盘/已注入**的柱由 `tryServeOvdLocal` 本地源服务，也不需要票；</li>
     *   <li>只有环带里**缺盘**的柱，原代码明确"交给原版 tracking"（注释见 `tryServeOvdLocal`），
     *       那是唯一真正依赖票的区间。</li>
     * </ul>
     * 故本驱动就是给这一环补票源——不多不少，且与 OVD 的见方判据同源，不需自绘几何。
     *
     * @param authorityRange 权威可视形状半径（{@code serverViewDistance}，与 {@code inOvdWindow} 同参）
     * @param clientRadius   client 有效视距（{@code effectiveClientVD}，与 {@code inOvdWindow} 同参）
     */
    public static void consumeOnShadowLoop(ShadowSeedServer shadow, String dimension,
                                           int centerX, int centerZ,
                                           int authorityRange, int clientRadius) {
        if (!ENABLED || shadow == null || dimension == null
                || authorityRange < 0 || clientRadius < 0) {
            return;
        }
        if (shadow != boundShadow) {
            // 实例重建（换服 / 重建影子世界）：旧世界的票随实例销毁，账本直接作废
            boolean first = boundShadow == null;
            boundShadow = shadow;
            ticketed.clear();
            pendingClear.set(false);
            lastDimension = null;
            DebugLogger.info(DebugLogger.LogType.NETWORK,
                    "[SHADOW_TICKET] {} instance (bookkeeping dropped)",
                    first ? "bound" : "re-bound to new");
            return;
        }
        if (pendingClear.compareAndSet(true, false)) {
            pendingLocalGen.clear();
            removeAll(shadow);
            return; // 清账这一拍不再对账，避免同拍又铺回来
        }
        consumeLocalGeneration(shadow);
        if (dimension.equals(lastDimension) && centerX == lastCenterX
                && centerZ == lastCenterZ && authorityRange == lastAuthorityRange
                && clientRadius == lastClientRadius) {
            return; // 几何未变：无事可做（移动时才跨 chunk，故这里是热路径的免枚举短路）
        }
        lastDimension = dimension;
        lastCenterX = centerX;
        lastCenterZ = centerZ;
        lastAuthorityRange = authorityRange;
        lastClientRadius = clientRadius;
        reconcile(shadow, dimension, centerX, centerZ, authorityRange, clientRadius);
    }

    /** 一次对账：枚举 OVD 环带 → 先增（近→远）→ 后删（远→近），两段各自限量。 */
    private static void reconcile(ShadowSeedServer shadow, String dimension,
                                  int centerX, int centerZ,
                                  int authorityRange, int clientRadius) {
        ServerLevel level = shadow.level(dimension);
        if (level == null) {
            return;
        }
        // 1) 目标集合：OVD 环带（与 inOvdWindow 同判据，禁止自绘）
        Set<Long> desired = new HashSet<>();
        List<ChunkPos> missing = new ArrayList<>();
        if (clientRadius > authorityRange) {
            for (int x = centerX - clientRadius; x <= centerX + clientRadius; x++) {
                for (int z = centerZ - clientRadius; z <= centerZ + clientRadius; z++) {
                    if (!ChunkShapeCompat.inOvdBand(centerX, centerZ, authorityRange,
                            clientRadius, x, z)) {
                        continue;
                    }
                    long key = DimensionKey.key(dimension, x, z);
                    desired.add(key);
                    if (!ticketed.contains(key)) {
                        missing.add(new ChunkPos(x, z));
                    }
                }
            }
        }
        // 2) 先增：由近及远，复刻 vanilla 的距离填充涟漪
        int added = 0;
        if (!missing.isEmpty()) {
            missing.sort(java.util.Comparator.comparingDouble(
                    p -> ChunkDistancePriority.authoritativeFromCenter(p, centerX, centerZ)));
            int ops = 0;
            for (ChunkPos pos : missing) {
                if (ops >= MAX_ADDS_PER_PUMP || ticketed.size() >= MAX_TICKETS) {
                    break;
                }
                if (addTicket(level, DimensionKey.key(dimension, pos.x, pos.z), pos)) {
                    ops++;
                    added++;
                }
            }
        }
        // 3) 后删：由远及近；先增后删保证边界抖动时不出现瞬时空洞
        List<Long> stale = null;
        for (Long key : ticketed) {
            if (dimension.equals(DimensionKey.dimensionOf(key)) && !desired.contains(key)) {
                if (stale == null) {
                    stale = new ArrayList<>();
                }
                stale.add(key);
            }
        }
        int removed = 0;
        if (stale != null) {
            stale.sort(java.util.Comparator.comparingDouble(
                    (Long key) -> -ChunkDistancePriority.authoritativeFromCenter(
                            new ChunkPos(DimensionKey.chunkXOf(key), DimensionKey.chunkZOf(key)),
                            centerX, centerZ)));
            int ops = 0;
            for (Long key : stale) {
                if (ops >= MAX_REMOVES_PER_PUMP) {
                    break;
                }
                if (removeTicket(level, key,
                        new ChunkPos(DimensionKey.chunkXOf(key), DimensionKey.chunkZOf(key)))) {
                    ops++;
                    removed++;
                }
            }
        }
        // 诊断（P5-2 归因用）：几何变化时的增/撤与在手量。几何变化只发生在跨 chunk 或视距变更，
        // 故频率天然有界；增/撤都可能是"本拍限量、下拍续做"的部分值。
        if (added > 0 || removed > 0 || desired.size() != ticketed.size()) {
            DebugLogger.info(DebugLogger.LogType.NETWORK,
                    "[SHADOW_TICKET] reconcile dim={} center=({}, {}) band={}..{} +{} -{} live={} desired={}",
                    dimension, centerX, centerZ, authorityRange, clientRadius,
                    added, removed, ticketed.size(), desired.size());
        }
    }

    private static boolean addTicket(ServerLevel level, long key, ChunkPos pos) {
        if (ticketed.contains(key)) {
            return false;
        }
        try {
#if MC_VER < MC_1_21_5
            level.getChunkSource().addRegionTicket(
                    net.minecraft.server.level.TicketType.FORCED, pos, 0, pos);
#else
            ((io.github.limuqy.mc.hassium.mixin.ServerChunkCacheAccessor) (Object) level.getChunkSource())
                    .hassium$getTicketStorage()
                    .addTicketWithRadius(net.minecraft.server.level.TicketType.FORCED, pos, 0);
#endif
            ticketed.add(key);
            return true;
        } catch (Throwable t) {
            DebugLogger.warn(DebugLogger.LogType.NETWORK, "[SHADOW_TICKET] add ticket failed ({}, {})",
                    pos.x, pos.z, t);
            return false;
        }
    }

    private static boolean removeTicket(ServerLevel level, long key, ChunkPos pos) {
        if (!ticketed.remove(key)) {
            return false;
        }
        try {
#if MC_VER < MC_1_21_5
            level.getChunkSource().removeRegionTicket(
                    net.minecraft.server.level.TicketType.FORCED, pos, 0, pos);
#else
            ((io.github.limuqy.mc.hassium.mixin.ServerChunkCacheAccessor) (Object) level.getChunkSource())
                    .hassium$getTicketStorage()
                    .removeTicketWithRadius(net.minecraft.server.level.TicketType.FORCED, pos, 0);
#endif
        } catch (Throwable ignored) {
            // world 已停：票随实例销毁
        }
        return true;
    }

    private static void removeAll(ShadowSeedServer shadow) {
        int removed = 0;
        for (Long key : new ArrayList<>(ticketed)) {
            String dimension = DimensionKey.dimensionOf(key);
            ServerLevel level = dimension == null ? null : shadow.level(dimension);
            if (level == null) {
                ticketed.remove(key);
                continue;
            }
            if (removeTicket(level, key,
                    new ChunkPos(DimensionKey.chunkXOf(key), DimensionKey.chunkZOf(key)))) {
                removed++;
            }
        }
        ticketed.clear();
        localGenInFlight.clear();
        localGenFailed.clear();
        lastDimension = null;
        DebugLogger.info(DebugLogger.LogType.NETWORK,
                "[SHADOW_TICKET] cleared {} selection tickets at session boundary", removed);
    }
}

