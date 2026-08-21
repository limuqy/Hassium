package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil;
import io.github.limuqy.mc.hassium.compat.LevelChunkSectionCompat;

import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import io.github.limuqy.mc.hassium.metrics.VanillaZlibEstimator;
import io.github.limuqy.mc.hassium.platform.Services;
import io.github.limuqy.mc.hassium.compat.PlayerCompat;
import io.github.limuqy.mc.hassium.compat.RegistryCompat;
import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;
import io.github.limuqy.mc.hassium.server.RuntimeServerContext;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.TickMonitor;
import io.github.limuqy.mc.hassium.network.core.outbound.ChunkApplyAck;
import io.github.limuqy.mc.hassium.network.gateway.GatewayPlayerSession;
import io.github.limuqy.mc.hassium.network.gateway.GatewayServer;
import io.github.limuqy.mc.hassium.network.sectiondelta.SectionDeltaPlanner;
import io.github.limuqy.mc.hassium.network.sectiondelta.SectionDeltaSnapshot;
import io.github.limuqy.mc.hassium.network.sectiondelta.SectionPlaneSyndrome;
import io.github.limuqy.mc.hassium.utils.DebugLogger.LogType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
#if MC_VER < MC_1_21_11
import net.minecraft.resources.ResourceLocation;
#else
import net.minecraft.resources.Identifier;
#endif
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * 服务端区块推送管理器
 * <p>
 * 职责：
 * 1. 发送 chunkHash 元数据给客户端
 * 2. 管理区块数据请求队列：主线程序列化，线程池异步压缩发送
 * 3. 短窗口批量发送 ChunkHash，降低进服包风暴
 * 4. 缓存拦截时已构建的区块包字节，miss 全量时复用（兼容反透视等改包 mod）
 */
public class ServerChunkPushManager {

    private static final ServerChunkPushManager INSTANCE = new ServerChunkPushManager();

    /** 每玩家已准备包字节缓存上限，防止永不 miss 时泄漏 */
    private static final int MAX_PREPARED_PER_PLAYER = 384;
    /** 每玩家 authoritative data queue 与 admission pending 的共同硬上限。 */
    private static final int MAX_DATA_QUEUE_PER_PLAYER = ChunkAdmissionController.MAX_PENDING_PER_PLAYER;


    /**
     * 握手后 resync 分批补发：每 tick 最多处理的区块数。
     * 避免一次性提交数百个 submitMetadataTaskFromChunk 卡住主线程，
     * 且减缓客户端 ChunkDataRequest 风暴导致 readyQueue 堆积。
     */
    private static final int RESYNC_PER_TICK = 32;

    /**
     * Bloom hit 只发 hash：不受 admission 窗口约束（hash 不占 pending/inFlight）。
     * 与 resync 同量级，避免 R2 有缓存时仍按 maxChunksPerTick=5 滴灌导致空窗。
     */
    static final int HASH_SENDS_PER_TICK = 32;

    /**
     * 每玩家区块数据请求队列。源头（1.20.1 pending drain / 1.20.2+ {@code sendNextChunks}）
     * 已按 {@code maxChunksPerTick} 定额，这里只做 FIFO 衔接 admission，不再按距离重排。
     */
    private final Map<UUID, FifoChunkQueue> dataQueues = new ConcurrentHashMap<>();

    /**
     * 每玩家是否正在本 tick 序列化（防重复 drain）
     */
    private final Map<UUID, AtomicBoolean> processingFlags = new ConcurrentHashMap<>();

    /** 每玩家 authoritative full/SeedGen 投递的准入状态；ACK 经主线程泵入。 */
    private final Map<UUID, ChunkAdmissionController> admissionControllers = new ConcurrentHashMap<>();

    /** 单调时钟超时；到期后仅当前仍在 tracking view 的 key 才重新 admission。 */

    /** delivery id → 原始任务，仅供超时后在仍 tracking 时重新入队。 */
    private final Map<UUID, Map<Long, DataRequestTask>> inFlightTasks = new ConcurrentHashMap<>();
    /**
     * 未 ACK 投递超时。30s 时 ROUND1 在 apply/ACK 停后会空窗 ~25s 才恢复；
     * 8s 足够覆盖影子算光 RTT，又不会把卡死窗口拖成可见停顿。
     */
    private static final long DELIVERY_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(8L);

    /**
     * 每玩家待发送的 chunkHash 批次
     */
    private final Map<UUID, PendingHashBatch> hashBatches = new ConcurrentHashMap<>();

    /**
     * 握手后 resync 待补发队列：playerId → 待补发 entry 队列。
     * resyncTrackedChunks 入队，onServerTick 每 tick 最多补发 RESYNC_PER_TICK 个，
     * 避免一次性提交数百个任务卡住主线程。
     */
    private final Map<UUID, Deque<ResyncEntry>> pendingResync = new ConcurrentHashMap<>();

    /** resync 待补发条目 */
    private record ResyncEntry(ChunkPos pos, String dimension) {}

    /**
     * 源头定额与压缩/握手无关：专用服一律按 {@code master.maxChunksPerTick} 滴灌。
     * 压缩只决定 drain 时走原版包还是 Hassium 元数据。
     */
    public static boolean shouldPaceChunkSends() {
        return RuntimeServerContext.isDedicatedServerContext();
    }

    /**
     * 1.20.1：仿 {@code PlayerChunkSender} pending。trackChunk 只登记，tick 近距定额出队。
     * 1.20.2+ 由原版 PlayerChunkSender 定额 sendChunk，不用本表。
     */
    private final Map<UUID, Set<Long>> pendingSends = new ConcurrentHashMap<>();
    private final Map<UUID, String> pendingSendDimension = new ConcurrentHashMap<>();

    /**
     * 客户端明确要全量数据（C2S miss / 入队被挤出）的待发集。只走
     * {@link #enqueueDirectPush}，admission 满则留到下 tick，禁止当成功丢掉。
     * 与 {@link #pendingSends} 分离：后者在 Bloom 未就绪时只发 hash 就会出队。
     */
    private final Map<UUID, Set<Long>> pendingFullSends = new ConcurrentHashMap<>();
    private final Map<UUID, String> pendingFullDimension = new ConcurrentHashMap<>();

    /**
     * 每玩家 SeedGen 能力（握手 C2S 上报 seedGenSupported；默认 false）。
     */
    private final Map<UUID, Boolean> playerSeedGenSupported = new ConcurrentHashMap<>();

    /**
     * SeedGen 自愈熔断：客户端对 pristine 区块请求全量数据达到阈值后，
     * 判定本会话 SeedGen 本地生成与服务器世界gen不一致（如跨版本/数据包差异），
     * 对该玩家停发 SeedRef，改走全量推送，避免 mismatch 风暴打爆数据队列。
     */
    private final Set<UUID> seedGenDisabledPlayers = ConcurrentHashMap.newKeySet();

    /** 每玩家 pristine 全量回退计数（仅客户端请求路径计数，直推不计）。 */
    private final Map<UUID, Integer> seedGenFallbackCounts = new ConcurrentHashMap<>();

    /** 触发 SeedGen 熔断的 pristine 全量请求数。 */
    private static final int SEED_GEN_DISABLE_THRESHOLD = 16;

    /**
     * 每玩家光照计算能力（握手 C2S 上报 lightComputeSupported = 客户端 hassiumEngineEnabled）。
     * 服务端据此决定是否剥光：客户端声明可本地/影子端算光才剥（stripLightIfConfigured gate）。
     */
    private final Map<UUID, Boolean> playerLightComputeSupported = new ConcurrentHashMap<>();

    /**
     * 每玩家影子端存档布隆位图层（客户端握手上报；bloom hit → 只发 hash 让影子端比对）。
     */
    private final Map<UUID, PlayerBloomLayers> bloomLayers = new ConcurrentHashMap<>();

    /**
     * Bloom 未命中柱的本会话已直推 contentHash（按玩家）。Bloom 已有的柱不登记、不查表。
     * 走近再次 trackChunk 时 Bloom 仍空，hash 相同则只发 hash，避免无 Bloom 时重复整柱直推。
     */
    private final Map<UUID, ConcurrentHashMap<SessionPushKey, Long>> sessionPushedHashes =
            new ConcurrentHashMap<>();
    private static final int MAX_SESSION_PUSH_HASHES = 8192;

    private record SessionPushKey(String dimension, int x, int z) {}

    /**
     * 握手 C2S 能力上报后调用：记录玩家是否支持 SeedGen。
     */
    public void setPlayerSeedGenSupported(UUID playerId, boolean supported) {
        if (supported) {
            playerSeedGenSupported.put(playerId, Boolean.TRUE);
        } else {
            playerSeedGenSupported.remove(playerId);
        }
    }

    /**
     * 握手 C2S 能力上报后调用：记录玩家是否支持光照计算（影子端/Hassium 引擎）。
     */
    public void setPlayerLightComputeSupported(UUID playerId, boolean supported) {
        if (supported) {
            playerLightComputeSupported.put(playerId, Boolean.TRUE);
        } else {
            playerLightComputeSupported.remove(playerId);
        }
    }

    /** 该玩家是否可剥光（客户端声明引擎可用）。 */
    public boolean isPlayerLightComputeSupported(UUID playerId) {
        return Boolean.TRUE.equals(playerLightComputeSupported.get(playerId));
    }

    /**
     * 该玩家 + 该区块是否走 SeedGen（SeedRef 替代区块数据）。
     * <p>
     * gate：客户端上报能力 && 服务端配置开启 && 主世界维度 && 区块 pristine
     * （本会话生成且未修改）。非主世界维度不命中 pristine（静默走全量）。
     */
    public boolean isSeedGenFor(UUID playerId, ChunkPos pos, String dimension) {
        if (seedGenDisabledPlayers.contains(playerId)) {
            return false;
        }
        return isSeedGenCandidate(playerId, pos, dimension);
    }

    /**
     * SeedGen 候选判定（不含熔断）：配置开启 + 玩家支持 + 主世界 pristine。
     * 熔断前与 {@link #isSeedGenFor} 等价，供请求路径统计回退次数。
     */
    private boolean isSeedGenCandidate(UUID playerId, ChunkPos pos, String dimension) {
        if (!HassiumConfigService.getInstance().isSeedGenEnabled()) {
            return false;
        }
        if (!Boolean.TRUE.equals(playerSeedGenSupported.get(playerId))) {
            return false;
        }
        ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION,
                ResourceLocationCompat.create(dimension));
        return PristineRegistry.isPristine(dimKey, pos);
    }

    /**
     * 记录一次 pristine 全量回退；达到阈值后对该玩家熔断 SeedGen。
     */
    private void recordSeedGenFallback(UUID playerId, ChunkPos pos, String dimension) {
        if (seedGenDisabledPlayers.contains(playerId) || !isSeedGenCandidate(playerId, pos, dimension)) {
            return;
        }
        int count = seedGenFallbackCounts.merge(playerId, 1, Integer::sum);
        if (count >= SEED_GEN_DISABLE_THRESHOLD) {
            seedGenDisabledPlayers.add(playerId);
            DebugLogger.warn(LogType.NETWORK,
                    "[SEEDGEN] Auto-disabling SeedGen for player {} after {} pristine full-data fallbacks "
                            + "(local worldgen appears inconsistent with server) — falling back to full pushes",
                    playerId, count);
        }
    }

    /**
     * 每玩家出界待命任务：drain 时已出视距的任务不静默丢弃（原 bug 根因），
     * 转入待命，玩家折返/静止后重新在视距内时恢复入队；超时（10s）才真丢弃。
     */
    private final Map<UUID, Map<Long, DeferredTask>> deferredChunks = new ConcurrentHashMap<>();

    /** 待命任务（含原始 priority 供重入队参考，实际重入队时按当前位置重算） */
    private record DeferredTask(ChunkPos pos, String dimension, long deferredAtMs) {}

    /** 待命检查周期（毫秒） */
    private static final long DEFER_CHECK_INTERVAL_MS = 1000L;
    /** 待命任务最大等待（毫秒），超时真丢弃（玩家不再回来）。
     *  10s 对移动探索太短：frontline 任务被过早丢弃后客户端静止/折返时无新请求可触发，
     *  导致扇形/十字虚空“永久”不补。 */
    private static final long DEFER_MAX_WAIT_MS = 600_000L;
    /** 每玩家待命任务上限：防超长超时下无界增长；超出时不再接纳新的出界任务 */
    private static final int MAX_DEFERRED_PER_PLAYER = 8192;
    /** 每玩家 bloom 层上限（超出丢最旧层） */
    private static final int BLOOM_MAX_LAYERS = 64;

    /**
     * 握手上报的玩家初始 chunk 位置（playerId → ChunkPos）。
     * 服务端玩家对象在 failover/重连场景位置滞后（新对象在出生点），resync 视距中心
     * 先用客户端上报的真实位置校正；消费（resync 中心计算）后移除，后续用玩家对象实时位置。
     */
    private final Map<UUID, ChunkPos> initialPlayerChunkPos = new ConcurrentHashMap<>();

    /**
     * 握手时客户端上报的玩家位置（方块坐标），校正 resync 视距中心。
     * 服务端玩家位置同步前（首个移动包到达前），客户端坐标是最新鲜的来源。
     */
    /**
     * 续流已接受玩家：UUID → 接受的续流票据 epoch（T7 验票通过后标记；removePlayer 清理）。
     * 续流模式下客户端跳过 login/维度初始化，服务端按上报位置续发视距内 hash（见
     * {@link #resyncTrackedChunks} 的 [RESUME] 日志与现有 hash 连续性机制）。
     */
    private final Map<UUID, Long> resumePlayers = new ConcurrentHashMap<>();

    /**
     * 握手时客户端上报的完整玩家状态（x/y/z/yaw/pitch/维度），供续流/会话同步使用。
     */
    private final Map<UUID, PlayerStateReport> playerStateReports = new ConcurrentHashMap<>();

    /** 仅位置兜底（旧客户端上报 x/z） */
    public void setInitialPlayerPosition(ServerPlayer player, double x, double z) {
        setInitialPlayerPosition(player, PlayerStateReport.fromXZ(x, z));
    }

    /**
     * 网关帧侧握手路径（T11）：无 ServerPlayer 时按 UUID 记录初始位置（续流玩家实体
     * 尚未物化；resyncTrackedChunks 与后续会话同步消费同一张表）。removePlayer 清理。
     */
    public void setInitialPlayerPosition(UUID playerId, PlayerStateReport state) {
        if (playerId == null || state == null) {
            return;
        }
        ChunkPos pos = new ChunkPos((int) Math.floor(state.x() / 16.0), (int) Math.floor(state.z() / 16.0));
        initialPlayerChunkPos.put(playerId, pos);
        if (state.present()) {
            playerStateReports.put(playerId, state);
        }
        DebugLogger.info(LogType.NETWORK,
                "[GATEWAY] Player {} reported initial position {} → chunk ({}, {})",
                playerId, state.describe(), pos.x, pos.z);
    }

    /** 完整玩家状态（T7 位置上报扩展；present=false 时仅取 x/z） */
    public void setInitialPlayerPosition(ServerPlayer player, PlayerStateReport state) {
        if (player == null || state == null) {
            return;
        }
        ChunkPos pos = new ChunkPos((int) Math.floor(state.x() / 16.0), (int) Math.floor(state.z() / 16.0));
        initialPlayerChunkPos.put(player.getUUID(), pos);
        if (state.present()) {
            playerStateReports.put(player.getUUID(), state);
        }
        DebugLogger.info(LogType.NETWORK,
                "[HANDSHAKE] Player {} reported initial position {} → chunk ({}, {})",
                player.getName().getString(), state.describe(), pos.x, pos.z);
    }

    /** 续流验票通过后标记（epoch = 票据 epoch）；removePlayer 清理 */
    public void markPlayerResumeActive(UUID playerId, long epoch) {
        resumePlayers.put(playerId, epoch);
        DebugLogger.info(LogType.NETWORK, "[RESUME] Player {} resume ready (epoch={})", playerId, epoch);
    }

    public boolean isPlayerResumeActive(UUID playerId) {
        return resumePlayers.containsKey(playerId);
    }

    public long playerResumeEpoch(UUID playerId) {
        return resumePlayers.getOrDefault(playerId, Long.MIN_VALUE);
    }

    /** 最近一次上报的完整玩家状态（无上报 → null） */
    public PlayerStateReport getPlayerStateReport(UUID playerId) {
        return playerStateReports.get(playerId);
    }

    /**
     * 已编码包字节（与 chunkHash / 反透视视图一致的包数据）。
     */
    /**
     * 已准备的区块数据：拦截路径缓存 {@code packet}（纯数据，后台 encode）或广播路径缓存线格式 {@code data}；
     * 二选一，另一为 null。
     */
    private record PreparedChunk(byte[] data, ClientboundLevelChunkWithLightPacket packet) {}

    /**
     * 每玩家：chunkPosLong → 已编码的 ClientboundLevelChunkWithLightPacket 线格式字节。
     * 在广播/初始发送拦截时写入，miss 全量请求时优先取出，避免从 LevelChunk 重建旁路反透视。
     */
    private final Map<UUID, ConcurrentHashMap<Long, PreparedChunk>> preparedChunkPackets = new ConcurrentHashMap<>();

    /**
     * 数据请求处理线程池（hash 计算 + 压缩发送）
     */
    private volatile ThreadPoolExecutor pushPool;

    /**
     * 线程池是否已初始化
     */
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    private ServerChunkPushManager() {}

    public static ServerChunkPushManager getInstance() {
        return INSTANCE;
    }

    /** ChunkHash 单包最多 entries */
    private static final int HASH_BATCH_MAX_ENTRIES = 16;

    /** ChunkHash 批次最大等待（毫秒） */
    private static final long HASH_BATCH_MAX_WAIT_MS = 10;

    /**
     * 服务端推送管线计时诊断（R1 供给版本差异排查：1.20.1 80/s vs 1.21.x 32/s）。
     * 每 256 块打印一次各段均值（build=主线程重建 packet / hash=pushPool 哈希 /
     * encode=线格式编码 / send=压缩+发送），打印后清零。热路径仅加 Atomic 累加。
     */
    private static final int D_BUILD = 0, D_HASH = 1, D_ENCODE = 2, D_SEND = 3;
    private static final AtomicLongArray DIAG_NS = new AtomicLongArray(4);
    private static final AtomicLong DIAG_COUNT = new AtomicLong();

    private static void diag(int slot, long ns) {
        DIAG_NS.addAndGet(slot, ns);
        long c = DIAG_COUNT.incrementAndGet();
        if ((c & 0xFF) == 0L) {
            Constants.LOG.info(
                    "[SERVE-DIAG] chunks={} build={}ms hash={}ms encode={}ms send={}ms",
                    c,
                    String.format("%.2f", DIAG_NS.get(D_BUILD) / 1e6 / 256.0),
                    String.format("%.2f", DIAG_NS.get(D_HASH) / 1e6 / 256.0),
                    String.format("%.2f", DIAG_NS.get(D_ENCODE) / 1e6 / 256.0),
                    String.format("%.2f", DIAG_NS.get(D_SEND) / 1e6 / 256.0));
            for (int i = 0; i < 4; i++) {
                DIAG_NS.set(i, 0L);
            }
        }
    }

    /**
     * 初始化线程池（懒加载）
     */
    private void ensureInitialized() {
        if (initialized.compareAndSet(false, true)) {
            int threads = HassiumConfigService.getInstance().getServerChunkPushThreads();
            pushPool = new ThreadPoolExecutor(
                    threads,
                    threads,
                    60L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(),
                    r -> {
                        Thread t = new Thread(r, "Hassium-ChunkPush");
                        t.setDaemon(true);
                        return t;
                    }
            );
            pushPool.allowCoreThreadTimeOut(true);

            Constants.LOG.info("Hassium: ServerChunkPushManager initialized with {} threads", threads);
            io.github.limuqy.mc.hassium.utils.StallDiag.event(
                    "pushManager timeoutMs={} maxInFlightWindow={}",
                    TimeUnit.NANOSECONDS.toMillis(DELIVERY_TIMEOUT_NANOS),
                    ChunkAdmissionController.POST_ACK_MAX_UNACKNOWLEDGED_BATCHES);
        }
    }

    /**
     * 异步计算 sectionHashes → chunkHash 并发送阶段一元数据（从 broadcast 调用，多玩家）。
     * <p>
     * 先计算 per-section 哈希（不含 blockEntity），再组合为 chunkHash。
     * 通过 ChunkHashS2CPacket 发送 chunkHash + sectionBitmap。
     * 客户端比对后决定缓存命中或进入阶段二。
     *
     * @param players   Hassium 客户端玩家列表
     * @param pos       区块位置
     * @param packet    已构建的区块数据包（只读，线程安全）
     * @param dimension 维度标识
     */
    public void submitMetadataTask(List<ServerPlayer> players, ChunkPos pos,
                                   ClientboundLevelChunkWithLightPacket packet, String dimension) {
        ensureInitialized();
        final ServerLevel firstLevel = PlayerCompat.getServerLevel(players.get(0));
        final int sectionCount = firstLevel.getSectionsCount();
        final RegistryAccess registryAccess = firstLevel.registryAccess();
        // 主线程登记 pristine（生成完成 → 首次推送时机；此后 setBlockState 修改即移除）
        PristineRegistry.markIfPristine(firstLevel, pos);
        // 统一剥光：broadcast 传入的是原版带光包，按配置重建空 mask 剥光包
        // （主线程调用；1.21.2+ ThreadingDetector 允许主线程读 chunk）
        long tBuild = System.nanoTime();
        final ClientboundLevelChunkWithLightPacket strippedPacket = stripLightIfConfigured(players.get(0), pos, packet);
        diag(D_BUILD, System.nanoTime() - tBuild);
        // 包字节编码挪 pushPool 后台：packet 为已构建完的纯数据（只读编码线程安全，
        // 反透视等 mod 已在拦截时改写好包视图），不再占用主线程。
        pushPool.submit(() -> {
            try {
                byte[] encoded = encodeChunkPacket(strippedPacket, registryAccess);
                if (encoded != null) {
                    for (ServerPlayer player : players) {
                        // SeedGen 玩家：不发数据任务（本地生成），只发 SeedRef
                        if (isSeedGenFor(player.getUUID(), pos, dimension)) {
                            continue;
                        }
                        putPreparedChunkPacket(player.getUUID(), pos, encoded);
                    }
                }
                // 从已序列化的 packet 数据计算 section 哈希（线程安全，无需读取世界）
                long tHash = System.nanoTime();
                Map<Integer, Long> sectionHashes = ChunkContentHashUtil.computeSectionHashesFromPacket(
                        strippedPacket.getChunkData(), sectionCount, registryAccess);
                diag(D_HASH, System.nanoTime() - tHash);
                long chunkHash = ChunkContentHashUtil.combineSectionHashes(sectionHashes);
                long[] sectionHashArray = ChunkContentHashUtil.sectionHashesToArray(sectionHashes);
                // 从 sectionHashes 推导 bitmap：有 hash 的 section = 有方块数据
                int sectionBitmap = 0;
                for (int idx : sectionHashes.keySet()) {
                    sectionBitmap |= (1 << idx);
                }

                sendChunkHashAndMaybePush(players, pos, chunkHash, sectionHashArray, sectionBitmap, dimension);
            } catch (Exception e) {
                Constants.LOG.error("[ASYNC_METADATA] Failed to compute chunkHash for chunk {}", pos, e);
            }
        });
    }

    /**
     * 异步计算 sectionHashes → chunkHash 并发送阶段一元数据（从 trackChunk 调用，单玩家）。
     * <p>
     * 先计算 per-section 哈希（不含 blockEntity），再组合为 chunkHash。
     * 通过 ChunkHashS2CPacket 发送 chunkHash + sectionBitmap。
     *
     * @param player      目标玩家
     * @param pos         区块位置
     * @param chunkPacket 区块数据包（可能是 ClientboundLevelChunkWithLightPacket 或其他类型）
     * @param dimension   维度标识
     */
    public void submitMetadataTask(ServerPlayer player, ChunkPos pos,
                                   Packet<?> chunkPacket, String dimension) {
        ensureInitialized();
        // 主线程登记 pristine（trackChunk 时机 = 生成完成 → 首次推送）
        ServerLevel playerLevel0 = PlayerCompat.getServerLevel(player);
        if (playerLevel0 != null) {
            PristineRegistry.markIfPristine(playerLevel0, pos);
        }
        final Packet<?> effectivePacket;
        if (chunkPacket instanceof ClientboundLevelChunkWithLightPacket lightPacket) {
            // 统一剥光：trackChunk 传入的是原版带光包，按配置重建空 mask 剥光包
            // （主线程调用；1.21.2+ ThreadingDetector 允许主线程读 chunk）
            long tBuild = System.nanoTime();
            effectivePacket = stripLightIfConfigured(player, pos, lightPacket);
            diag(D_BUILD, System.nanoTime() - tBuild);
        } else {
            effectivePacket = chunkPacket;
        }
        final ServerLevel playerLevel = PlayerCompat.getServerLevel(player);
        final int sectionCount = playerLevel.getSectionsCount();
        final RegistryAccess registryAccess = playerLevel.registryAccess();
        // 编码 + hash 计算 + 发送全部在 pushPool（packet 纯数据只读编码，线程安全）
        pushPool.submit(() -> {
            try {
                if (effectivePacket instanceof ClientboundLevelChunkWithLightPacket lightPacket) {
                    // SeedGen 玩家：不发数据任务（本地生成），只发 SeedRef
                    if (!isSeedGenFor(player.getUUID(), pos, dimension)) {
                        byte[] encoded = encodeChunkPacket(lightPacket, registryAccess);
                        if (encoded != null) {
                            putPreparedChunkPacket(player.getUUID(), pos, encoded);
                        }
                    }
                }
                Map<Integer, Long> sectionHashes;
                int sectionBitmap;

                if (effectivePacket instanceof ClientboundLevelChunkWithLightPacket lightPacket) {
                    // 从已序列化的 packet 数据计算（线程安全）
                    long tHash = System.nanoTime();
                    sectionHashes = ChunkContentHashUtil.computeSectionHashesFromPacket(
                            lightPacket.getChunkData(), sectionCount, registryAccess);
                    diag(D_HASH, System.nanoTime() - tHash);
                    sectionBitmap = 0;
                    for (int idx : sectionHashes.keySet()) {
                        sectionBitmap |= (1 << idx);
                    }
                } else {
                    // 回退：从世界读取（非标准 packet 类型）
                    ServerLevel level = PlayerCompat.getServerLevel(player);
                    LevelChunk chunk = level.getChunk(pos.x, pos.z);
                    sectionHashes = ChunkContentHashUtil.computeSectionHashes(chunk);
                    sectionBitmap = computeSectionBitmap(chunk);
                }

                long chunkHash = ChunkContentHashUtil.combineSectionHashes(sectionHashes);
                long[] sectionHashArray = ChunkContentHashUtil.sectionHashesToArray(sectionHashes);

                if (player.isAlive() && !player.hasDisconnected()) {
                    sendChunkHashAndMaybePush(List.of(player), pos, chunkHash, sectionHashArray, sectionBitmap, dimension);
                }
            } catch (Exception e) {
                Constants.LOG.error("[ASYNC_METADATA] Failed to compute chunkHash for chunk {} (player={})",
                        pos, player.getName().getString(), e);
            }
        });
    }

    /**
     * 异步计算 sectionHashes → chunkHash 并发送阶段一元数据（从 PlayerChunkSender.sendChunk 调用，1.20.2+）。
     * <p>
     * 1.20.2+ 移除了 {@code ServerPlayer.trackChunk}，初始区块发送改走
     * {@code PlayerChunkSender.sendChunk}。packet 构建在**调用线程**同步完成：
     * 1.21.2+ 的 PalettedContainer ThreadingDetector 禁止跨线程读 chunk，只有主线程/
     * 原版 ChunkSender 线程合法（拦截点正是这两个线程之一）；<1.21.2 无检测但同样
     * 在调用线程构建（拦截点即主线程）。编码不再占用调用线程：packet 为纯数据，
     * 由 drain 消费方在 pushPool 后台 encode；hash 计算同样下推 pushPool。
     *
     * @param player    目标玩家
     * @param pos       区块位置
     * @param chunk     区块对象（须在主线程或原版 ChunkSender 线程调用）
     * @param dimension 维度标识
     */
    /**
     * @return true if the metadata/hash path was actually submitted (packet built + async work queued)
     */
    public boolean submitMetadataTaskFromChunk(ServerPlayer player, ChunkPos pos,
                                             LevelChunk chunk, String dimension) {
        ensureInitialized();
        ServerLevel level = PlayerCompat.getServerLevel(player);
        final int sectionCount = level.getSectionsCount();
        final RegistryAccess registryAccess = level.registryAccess();

        ClientboundLevelChunkWithLightPacket packet;
        long tBuild = System.nanoTime();
        packet = buildChunkPacket(chunk, level);
        diag(D_BUILD, System.nanoTime() - tBuild);
        if (packet == null) {
            Constants.LOG.warn("[ASYNC_METADATA] Failed to build chunk packet for {}", pos);
            return false;
        }
        // 主线程登记 pristine（resync 时区块可能早已生成且未被修改——登记语义：会话内生成完成
        // 且未修改；已是 FULL 的旧块 inhabitedTime==0 同样满足候选，登记无副作用）
        PristineRegistry.markIfPristine(level, pos);
        // 同步缓存已构建的 packet：encode 留给 drain 消费方后台执行，主线程不再付线格式编码成本；
        // packet 为纯数据（构造后不碰 chunk），后台 hash/encode 并发读安全。
        if (!isSeedGenFor(player.getUUID(), pos, dimension)) {
            putPreparedChunkPacket(player.getUUID(), pos, packet);
        }

        pushPool.submit(() -> {
            try {
                long tHash = System.nanoTime();
                Map<Integer, Long> sectionHashes = ChunkContentHashUtil.computeSectionHashesFromPacket(
                        packet.getChunkData(), sectionCount, registryAccess);
                diag(D_HASH, System.nanoTime() - tHash);
                int sectionBitmap = 0;
                for (int idx : sectionHashes.keySet()) {
                    sectionBitmap |= (1 << idx);
                }
                long chunkHash = ChunkContentHashUtil.combineSectionHashes(sectionHashes);
                long[] sectionHashArray = ChunkContentHashUtil.sectionHashesToArray(sectionHashes);

                if (player.isAlive() && !player.hasDisconnected()) {
                    sendChunkHashAndMaybePush(List.of(player), pos, chunkHash, sectionHashArray, sectionBitmap, dimension);
                }
            } catch (Throwable e) {
                Constants.LOG.error("[ASYNC_METADATA] Failed to compute chunkHash for chunk {} (player={})",
                        pos, player.getName().getString(), e);
            }
        });
        return true;
    }

    /**
     * 将阶段一 chunkHash 加入短窗口批次（由 server tick 限流发送；维度切换时立即冲刷）。
     * <p>
     * 批次不因凑满而立即发送：flush 由 {@link #flushPlayerHashBatchIfDue} 按每 tick
     * {@code maxChunksPerTick} 条预算限流，避免 resync 一次性向客户端倾泻数百个 hash
     * 触发缓存比对风暴（读盘-计算 hash）。直推块走 {@link #sendChunkHashAndMaybePush}
     * 的直发路径（与数据同节奏），不受此限流。
     */
    private void sendChunkHash(List<ServerPlayer> players, ChunkPos pos,
                                long chunkHash, int sectionBitmap, String dimension) {
        ChunkHashS2CPacket.Entry entry =
                new ChunkHashS2CPacket.Entry(pos.x, pos.z, chunkHash, sectionBitmap);
        for (ServerPlayer player : players) {
            if (!player.isAlive() || player.hasDisconnected()) {
                continue;
            }
            UUID playerId = player.getUUID();
            PendingHashBatch flushDueToDimension = null;
            synchronized (hashBatches) {
                PendingHashBatch batch = hashBatches.get(playerId);
                if (batch != null && !batch.dimension.equals(dimension)) {
                    flushDueToDimension = batch;
                    hashBatches.remove(playerId);
                    batch = null;
                }
                if (batch == null) {
                    batch = new PendingHashBatch(dimension);
                    hashBatches.put(playerId, batch);
                }
                batch.entries.add(entry);
            }
            if (flushDueToDimension != null) {
                flushHashBatch(player, flushDueToDimension.entries, flushDueToDimension.dimension);
            }
        }
    }

    /**
     * 统一 hash/直推入口：per-player 分流。
     * <p>
     * SeedGen 玩家（能力 + 配置 + pristine）→ 只发 SeedRef（本地生成，零区块数据流量）；
     * 其余玩家 → Bloom 分流（客户端握手上报影子端存档布隆位图）：
     * <ul>
     *   <li>bloom 未就绪（尚未上报）→ 只发 hash，由客户端 HashIndex/磁盘比对；</li>
     *   <li>bloom hit（可能有缓存）→ 只发 hash，不查/不写本会话直推表；</li>
     *   <li>bloom 已到且 miss → 查本会话直推表：同柱同 hash 已直推过则只发 hash；
     *       表无记录才整柱直推并在发送成功后登记。</li>
     * </ul>
     * hash 直发（不走限流批次）：影子端比对在后台线程（无旧客户端比对风暴）。
     */
    private void sendChunkHashAndMaybePush(List<ServerPlayer> players, ChunkPos pos,
                                           long chunkHash, long[] sectionHashes, int sectionBitmap, String dimension) {
        for (ServerPlayer player : players) {
            if (!player.isAlive() || player.hasDisconnected()) {
                continue;
            }
            if (isSeedGenFor(player.getUUID(), pos, dimension)) {
                enqueueSeedRef(player, pos, dimension, chunkHash, sectionHashes);
                continue;
            }
            boolean inBloom = !shouldPushFull(player, pos, dimension);
            if (inBloom) {
                sendChunkHashDirect(player, pos, chunkHash, sectionBitmap, dimension);
                continue;
            }
            Long lastSent = lastSessionPushedHash(player.getUUID(), dimension, pos);
            if (shouldReuseSessionPush(inBloom, lastSent, chunkHash)) {
                sendChunkHashDirect(player, pos, chunkHash, sectionBitmap, dimension);
                continue;
            }
            enqueueDirectPush(player, dimension, List.of(pos), chunkHash);
            // 直推仍带 hash：客户端才能记「应用区块」分母，R2 才能走读盘命中。
            // resync 不等 Bloom（方案 A），不带 hash 时 ROUND2 缓存命中恒为 0。
            if (shouldPairHashWithDirectPush()) {
                sendChunkHashDirect(player, pos, chunkHash, sectionBitmap, dimension);
            }
        }
    }

    /** 发送已由 admission 分配 deliveryId 的 SeedRef。 */
    private void sendSeedRef(ServerPlayer player, DataRequestTask task, long deliveryId) {
        SeedRefWork seedRef = task.seedRef();
        SeedRefS2CPacket packet = new SeedRefS2CPacket(task.pos().x, task.pos().z, seedRef.chunkHash(),
                seedRef.sectionHashes(), deliveryId);
        FriendlyByteBuf buf = null;
        boolean sent = false;
        try {
            buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            packet.encode(buf);
            int bytes = buf.readableBytes();
            if (!markDeliverySent(player.getUUID(), deliveryId)) {
                rollbackDelivery(player, task, deliveryId);
                return;
            }
            Services.NETWORK_MANAGER.sendSeedRef(player, buf);
            sent = true;
            NetworkStats.recordMetadataSent(bytes);
            Constants.LOG.info("[SEED_REF] Sent ({}, {}) hash={} deliveryId={} bytes={} to {}",
                    task.pos().x, task.pos().z, Long.toHexString(seedRef.chunkHash()), deliveryId, bytes,
                    player.getName().getString());
        } catch (Exception e) {
            Constants.LOG.error("[SEED_REF] Failed to send SeedRef to player {}",
                    player.getName().getString(), e);
            rollbackDelivery(player, task, deliveryId);
        } finally {
            if (!sent && buf != null) {
                buf.release();
            }
        }
    }

    /**
     * 直推场景的 hash 直发（单玩家单块，不走限流批次——与直推数据同节奏）。
     */
    private void sendChunkHashDirect(ServerPlayer player, ChunkPos pos,
                                     long chunkHash, int sectionBitmap, String dimension) {
        ChunkHashS2CPacket packet = new ChunkHashS2CPacket(dimension,
                List.of(new ChunkHashS2CPacket.Entry(pos.x, pos.z, chunkHash, sectionBitmap)));
        FriendlyByteBuf buf = null;
        boolean sent = false;
        try {
            buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            packet.encode(buf);
            int bytes = buf.readableBytes();
            Services.NETWORK_MANAGER.sendChunkHashPacket(player, buf);
            sent = true;
            NetworkStats.recordMetadataSent(bytes);
        } catch (Exception e) {
            Constants.LOG.error("[CHUNK_HASH] Failed to send direct chunkHash to player {}",
                    player.getName().getString(), e);
        } finally {
            if (!sent && buf != null) {
                buf.release();
            }
        }
    }

    /**
     * 冲刷指定 hash 条目列表（单个玩家的单个包）。
     */
    private void flushHashBatch(ServerPlayer player, List<ChunkHashS2CPacket.Entry> entries, String dimension) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        if (!player.isAlive() || player.hasDisconnected()) {
            return;
        }
        DebugLogger.info(LogType.NETWORK, "[SEND_HASH] Flushing {} chunkHashes to player {} (dimension={})",
                entries.size(), player.getName().getString(), dimension);
        FriendlyByteBuf buf = null;
        boolean sent = false;
        try {
            ChunkHashS2CPacket packet = new ChunkHashS2CPacket(dimension, new ArrayList<>(entries));
            buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            packet.encode(buf);
            int bytes = buf.readableBytes();
            Services.NETWORK_MANAGER.sendChunkHashPacket(player, buf);
            sent = true;
            NetworkStats.recordMetadataSent(bytes);
        } catch (Exception e) {
            Constants.LOG.error("[CHUNK_HASH] Failed to flush chunkHash batch to player {}",
                    player.getName().getString(), e);
        } finally {
            if (!sent && buf != null) {
                buf.release();
            }
        }
    }

    /**
    /**
     * 处理客户端的影子端存档 Bloom 位图同步包。
     * <p>
     * {@code full=true} 覆盖旧层（进服全量）；{@code full=false} 追加一层（会话增量）。
     * 首个 Bloom 到达后，{@link #drainPendingResync} 自动恢复 resync 提交（无需额外动作）。
     * 必须在主线程调用（三端 receiver 均 enqueueWork）。
     */
    public void handleClientBloomSync(ServerPlayer player, ClientBloomSyncPacket packet) {
        if (player == null || !player.isAlive() || player.hasDisconnected()) {
            return;
        }
        try {
            io.github.limuqy.mc.hassium.cache.client.ChunkBloomFilter filter =
                    io.github.limuqy.mc.hassium.cache.client.ChunkBloomFilter.fromByteArray(packet.bloomBytes());
            if (filter == null) {
                Constants.LOG.warn("[BLOOM_SYNC] Invalid bloom bytes from player {} ({} bytes)",
                        player.getName().getString(), packet.bloomBytes() == null ? -1 : packet.bloomBytes().length);
                return;
            }
            UUID playerId = player.getUUID();
            PlayerBloomLayers layers = bloomLayers.computeIfAbsent(playerId, k -> new PlayerBloomLayers());
            if (packet.full()) {
                layers.reset(filter);
                DebugLogger.info(LogType.NETWORK,
                        "[BLOOM_SYNC] Full bloom from {} ({} bytes) — resync unblocked",
                        player.getName().getString(), packet.bloomBytes().length);
            } else {
                layers.append(filter);
                DebugLogger.info(LogType.NETWORK,
                        "[BLOOM_SYNC] Incremental bloom from {} ({} bytes)", player.getName().getString(),
                        packet.bloomBytes().length);
            }
        } catch (Exception e) {
            Constants.LOG.error("[BLOOM_SYNC] Failed to handle bloom sync from player {}",
                    player.getName().getString(), e);
        }
    }

    /**
     * Bloom 分流：未就绪 / 空层 → 只发 hash（防 Bloom 未到时 R2 整视距直推）；
     * 已收到 Bloom 且 miss → 直推；hit → 只发 hash。
     */
    private boolean shouldPushFull(ServerPlayer player, ChunkPos pos, String dimension) {
        return shouldPushFull(bloomLayers.get(player.getUUID()), pos.x, pos.z, dimension);
    }

    /**
     * Bloom 未命中时，本会话已直推过相同 contentHash → 只发 hash，不再整柱。
     * Bloom 已命中不走本表。
     */
    static boolean shouldReuseSessionPush(boolean inBloom, Long lastSentHash, long currentHash) {
        if (inBloom || lastSentHash == null || currentHash == 0L) {
            return false;
        }
        return lastSentHash == currentHash;
    }

    /**
     * Bloom miss 直推时仍附带 hash。客户端用它记账 / R2 读盘比对；
     * 不附带则直推路径永远进不了 {@code getClientAppliedChunkCount}。
     */
    static boolean shouldPairHashWithDirectPush() {
        return true;
    }

    /** 只登记不在 Bloom 中的直推柱。 */
    static boolean shouldRecordSessionPush(boolean inBloom, long currentHash) {
        return !inBloom && currentHash != 0L;
    }

    private Long lastSessionPushedHash(UUID playerId, String dimension, ChunkPos pos) {
        if (pos == null) {
            return null;
        }
        return lastSessionPushedHash(playerId, dimension, pos.x, pos.z);
    }

    private Long lastSessionPushedHash(UUID playerId, String dimension, int chunkX, int chunkZ) {
        if (playerId == null || dimension == null) {
            return null;
        }
        ConcurrentHashMap<SessionPushKey, Long> table = sessionPushedHashes.get(playerId);
        if (table == null) {
            return null;
        }
        return table.get(new SessionPushKey(dimension, chunkX, chunkZ));
    }

    private void rememberSessionPush(UUID playerId, String dimension, ChunkPos pos, long chunkHash) {
        if (playerId == null || dimension == null || pos == null || chunkHash == 0L) {
            return;
        }
        ConcurrentHashMap<SessionPushKey, Long> table =
                sessionPushedHashes.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>());
        SessionPushKey key = new SessionPushKey(dimension, pos.x, pos.z);
        if (table.size() >= MAX_SESSION_PUSH_HASHES && !table.containsKey(key)) {
            return;
        }
        table.put(key, chunkHash);
    }

    /**
     * 包可见：供单测覆盖 empty / unready / miss / hit。
     * 未就绪（{@code layers == null}）或空层：不直推，走 hash，避免 Bloom 尚未上报时
     * R2 被当 ROUND1。已收到 Bloom 后 miss 才直推（再由会话表决定是否复用）。
     */
    static boolean shouldPushFull(PlayerBloomLayers layers, int chunkX, int chunkZ, String dimension) {
        if (!isBloomReady(layers)) {
            return false;
        }
        return !layers.mightContain(chunkX, chunkZ, dimension);
    }

    /** 已收到至少一层 Bloom。空过滤器（ROUND1 无缓存）也算就绪。 */
    static boolean isBloomReady(PlayerBloomLayers layers) {
        return layers != null && !layers.isEmpty();
    }

    /** 每玩家 bloom 层（full 重置 / 增量追加；查询任一命中即可能缓存）。包可见供单测构造。 */
    static final class PlayerBloomLayers {
        private final List<io.github.limuqy.mc.hassium.cache.client.ChunkBloomFilter> layers = new ArrayList<>();

        void reset(io.github.limuqy.mc.hassium.cache.client.ChunkBloomFilter filter) {
            synchronized (layers) {
                layers.clear();
                layers.add(filter);
            }
        }

        void append(io.github.limuqy.mc.hassium.cache.client.ChunkBloomFilter filter) {
            synchronized (layers) {
                if (layers.size() >= BLOOM_MAX_LAYERS) {
                    layers.remove(0);
                }
                layers.add(filter);
            }
        }

        boolean isEmpty() {
            synchronized (layers) {
                return layers.isEmpty();
            }
        }

        boolean mightContain(int chunkX, int chunkZ, String dimension) {
            synchronized (layers) {
                for (io.github.limuqy.mc.hassium.cache.client.ChunkBloomFilter layer : layers) {
                    if (layer.mightContain(chunkX, chunkZ, dimension)) {
                        return true;
                    }
                }
                return false;
            }
        }
    }

    /**
     * 握手成功后：1.20.1 把已加载视距柱登记进 paced pending；1.20.2+ 不 dump 整盘 hash
     * （由 PlayerChunkSender 定额 sendChunk）。必须在主线程调用。
     */
    public void resyncTrackedChunks(ServerPlayer player) {
        if (player == null || !player.isAlive() || player.hasDisconnected()) {
            return;
        }
        if (!PlayerCompressionTracker.isCompressionEnabled(player)) {
            return;
        }
        if (!HassiumConfigService.getInstance().isNetworkCompressionEnabled()
                || !HassiumConfigService.getInstance().isClientCacheEnabled()) {
            return;
        }

        ensureInitialized();
        ServerLevel level = PlayerCompat.getServerLevel(player);
        if (level == null) {
            return;
        }

        int viewDistance = PlayerCompat.getViewDistance(player);
        // 与 ChunkMap 扫描余量一致，略扩一圈避免边界遗漏
        int radius = Math.max(2, viewDistance + 1);
        // 位置选择：普通登录/重连时 vanilla 已按 playerdata 物化，player.chunkPosition()
        // 是权威位置；握手上报位置可能是旧出生点/未更新快照，不能覆盖真实位置。
        // 续流（resume）路径按上报位置物化玩家，此时上报位置才是权威。
        // 额外保留旧 failover 兜底：若玩家对象仍滞留在出生点且上报位置非出生点，
        // 说明真实位置尚未同步，仍以上报位置为准。
        ChunkPos reportedPos = initialPlayerChunkPos.remove(player.getUUID());
        ChunkPos actualPos = player.chunkPosition();
        ChunkPos spawnPos;
#if MC_VER < MC_1_21_9
        spawnPos = new ChunkPos(level.getSharedSpawnPos());
#else
        spawnPos = new ChunkPos(level.getRespawnData().pos());
#endif
        boolean resume = isPlayerResumeActive(player.getUUID());
        boolean actualStillAtSpawn = actualPos.x == spawnPos.x && actualPos.z == spawnPos.z
                && reportedPos != null
                && (reportedPos.x != spawnPos.x || reportedPos.z != spawnPos.z);
        boolean preferReported = (resume || actualStillAtSpawn) && reportedPos != null;
        int centerX;
        int centerZ;
        if (preferReported) {
            centerX = reportedPos.x;
            centerZ = reportedPos.z;
        } else {
            centerX = actualPos.x;
            centerZ = actualPos.z;
            if (reportedPos != null && (reportedPos.x != actualPos.x || reportedPos.z != actualPos.z)) {
                DebugLogger.info(LogType.NETWORK,
                        "[RESYNC] Ignoring stale reported position ({}, {}) for player {} — using actual player chunk ({}, {})",
                        reportedPos.x, reportedPos.z, player.getName().getString(), centerX, centerZ);
            }
        }
        // T7 续流：验票通过后走同一 resync 机制 —— 按上报位置重发视距 hash，
        // 客户端与本地 ShadowStorageHashes 比对后只请求增量（hash 连续性）。
        if (resume) {
            DebugLogger.info(LogType.NETWORK,
                    "[RESUME] Player {} — 续流模式：按上报位置 ({}, {}) 续发视距 chunkHash",
                    player.getName().getString(), centerX, centerZ);
        }
        String dimension = level.dimension()
#if MC_VER < MC_1_21_11
                .location()
#else
                .identifier()
#endif
                .toString();

        // 不再把整盘 hash 灌进 pendingResync（曾 2s dump 上千条，绕开原版定额）。
        // 1.20.2+：PlayerChunkSender 已近距定额 sendChunk。
        // 1.20.1：只把已加载柱登记进 pendingSends，由 tick drain 出队。
#if MC_VER < MC_1_20_2
        int marked = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = centerX + dx;
                int cz = centerZ + dz;
                if (!isServerChunkInRange(cx, cz, centerX, centerZ, viewDistance)) {
                    continue;
                }
                if (level.getChunkSource().getChunkNow(cx, cz) == null) {
                    continue;
                }
                markChunkPendingToSend(player, new ChunkPos(cx, cz), dimension);
                marked++;
            }
        }
        DebugLogger.info(LogType.CHUNK_APPLY,
                "Hassium: Pending {} loaded chunks for paced send (player={}, vd={})",
                marked, player.getName().getString(), viewDistance);
#endif
    }

    /**
     * 1.20.1：登记待发送柱。出队见 {@link #drainPendingSends}。
     */
    public void markChunkPendingToSend(ServerPlayer player, ChunkPos pos, String dimension) {
        if (player == null || pos == null || dimension == null) {
            return;
        }
        UUID playerId = player.getUUID();
        pendingSendDimension.put(playerId, dimension);
        pendingSends.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet())
                .add(ChunkPos.asLong(pos.x, pos.z));
    }

    /**
     * 全量数据义务：C2S miss 或从 admission 队列被挤出的柱。Bloom 未就绪只发 hash
     * 不会从这里出队。
     */
    void markPendingFullSend(ServerPlayer player, ChunkPos pos, String dimension) {
        if (player == null || pos == null || dimension == null) {
            return;
        }
        UUID playerId = player.getUUID();
        pendingFullDimension.put(playerId, dimension);
        pendingFullSends.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet())
                .add(ChunkPos.asLong(pos.x, pos.z));
    }

    /**
     * 未进入 admission 的全量请求必须留在 backlog。已经 pending/in-flight 的不算丢失。
     */
    static boolean shouldBacklogUnacceptedFullRequest(boolean offered, boolean alreadyAdmitted) {
        return !offered && !alreadyAdmitted;
    }

    /**
     * 每 tick 把 C2S/挤出 backlog 定额 {@link #enqueueDirectPush}。admission 满则停下
     * 等 ACK 腾出窗口，绝不把 key 当成功丢掉。
     */
    private void drainPendingFullSends(ServerPlayer player) {
        UUID playerId = player.getUUID();
        Set<Long> pending = pendingFullSends.get(playerId);
        if (pending == null || pending.isEmpty()) {
            return;
        }
        String dimension = pendingFullDimension.get(playerId);
        if (dimension == null) {
            pending.clear();
            return;
        }
        int maxPerTick = HassiumConfigService.getInstance().getConfig().master().maxChunksPerTick();
        if (maxPerTick <= 0) {
            maxPerTick = 4;
        }
        ChunkAdmissionController controller = admissionControllers.computeIfAbsent(
                playerId, ignored -> new ChunkAdmissionController());
        int budget = pacedSendBudget(controller.pendingCount(), controller.inFlightCount(), maxPerTick);
        if (budget <= 0) {
            return;
        }
        ChunkPos center = player.chunkPosition();
        List<Long> ordered = new ArrayList<>(pending);
        sortPackedKeysByDistance(ordered, center.x, center.z);
        for (Long packed : ordered) {
            if (budget <= 0) {
                break;
            }
            int x = ChunkPos.getX(packed);
            int z = ChunkPos.getZ(packed);
            ChunkAdmissionController.ChunkDeliveryKey key =
                    new ChunkAdmissionController.ChunkDeliveryKey(dimension, x, z);
            if (controller.contains(key)) {
                pending.remove(packed);
                continue;
            }
            boolean accepted = enqueueDirectPush(player, dimension, List.of(new ChunkPos(x, z)), 0L);
            if (!commitPacedPendingKey(pending, packed, accepted)) {
                break;
            }
            budget--;
        }
        if (pending.isEmpty()) {
            pendingFullSends.remove(playerId);
            pendingFullDimension.remove(playerId);
        }
    }

    /**
     * 每 tick 从 pending 取距玩家最近、且已加载的柱出队。
     * <p>
     * 先按 packed key 距离排序（不碰世界），再 {@code getChunkNow} 直到填满本 tick
     * 入队预算即停，禁止每 tick 扫满 VD 方阵。主线程不算 section hash：仅当 Bloom
     * 已到且 miss、且 {@code lastSessionPushedHash==null} 才 {@code enqueueDirectPush(..., 0L)}；
     * 未就绪 / hit / 会话复用走 {@link #submitMetadataTaskFromChunk} 的 pushPool。
     * 仅在 admission/直推入队（或 hash 路径实际提交）成功后才从 {@code pendingSends} 移除。
     * 入队预算见 {@link #pacedEnqueueBudget}；hash 与全量共用 {@code maxChunksPerTick}。
     */
    private void drainPendingSends(ServerPlayer player) {
        UUID playerId = player.getUUID();
        Set<Long> pending = pendingSends.get(playerId);
        if (pending == null || pending.isEmpty()) {
            return;
        }
        String dimension = pendingSendDimension.get(playerId);
        if (dimension == null) {
            pending.clear();
            return;
        }
        ServerLevel level = PlayerCompat.getServerLevel(player);
        if (level == null) {
            return;
        }
        int maxPerTick = HassiumConfigService.getInstance().getConfig().master().maxChunksPerTick();
        if (maxPerTick <= 0) {
            maxPerTick = 4;
        }
        if (!PlayerCompressionTracker.isCompressionEnabled(player)) {
            drainVanillaPacedSends(player, pending, level, maxPerTick);
            return;
        }
        ChunkAdmissionController controller = admissionControllers.computeIfAbsent(
                playerId, ignored -> new ChunkAdmissionController());
        int fullBudget = pacedSendBudget(controller.pendingCount(), controller.inFlightCount(), maxPerTick);
        int hashBudget = maxPerTick;
        if (fullBudget <= 0 && hashBudget <= 0) {
            return;
        }
        ChunkPos center = player.chunkPosition();
        List<Long> ordered = new ArrayList<>(pending);
        sortPackedKeysByDistance(ordered, center.x, center.z);
        for (Long packed : ordered) {
            if (fullBudget <= 0 && hashBudget <= 0) {
                break;
            }
            int x = ChunkPos.getX(packed);
            int z = ChunkPos.getZ(packed);
            PlayerBloomLayers layers = bloomLayers.get(playerId);
            boolean bloomReady = isBloomReady(layers);
            boolean bloomMiss = shouldPushFull(layers, x, z, dimension);
            Long lastSent = lastSessionPushedHash(playerId, dimension, x, z);
            boolean directNoHash = shouldDirectPushWithoutHash(bloomMiss, lastSent, bloomReady);
            if (!shouldProbeWorldForPacedPending(directNoHash, fullBudget, hashBudget)) {
                continue;
            }
            LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
            if (chunk == null) {
                // 未加载：保留在 pendingSends，等 getChunkNow != null 再试
                continue;
            }
            ChunkPos pos = new ChunkPos(x, z);
            boolean accepted = directNoHash
                    ? enqueueDirectPush(player, dimension, List.of(pos), 0L)
                    : submitMetadataTaskFromChunk(player, pos, chunk, dimension);
            if (!commitPacedPendingKey(pending, packed, accepted)) {
                // admission 拒绝：本 tick 不再硬塞更远的直推柱；hash 失败则跳过该柱重试
                if (directNoHash) {
                    break;
                }
                continue;
            }
            if (directNoHash) {
                fullBudget--;
            } else {
                hashBudget--;
            }
        }
        if (pending.isEmpty()) {
            pendingSends.remove(playerId);
            pendingSendDimension.remove(playerId);
        }
    }

    /**
     * packed key 按距中心平方距离排序，不访问世界。
     */
    static void sortPackedKeysByDistance(List<Long> packedKeys, int centerChunkX, int centerChunkZ) {
        packedKeys.sort(Comparator.comparingLong(packed -> {
            long dx = (long) ChunkPos.getX(packed) - centerChunkX;
            long dz = (long) ChunkPos.getZ(packed) - centerChunkZ;
            return dx * dx + dz * dz;
        }));
    }

    /**
     * 压缩尚未启用时按原版包滴灌。走 {@code connection.send}，不经过 {@code trackChunk} mixin。
     */
    private void drainVanillaPacedSends(ServerPlayer player, Set<Long> pending, ServerLevel level,
                                        int maxPerTick) {
        ChunkPos center = player.chunkPosition();
        List<Long> ordered = new ArrayList<>(pending);
        sortPackedKeysByDistance(ordered, center.x, center.z);
        int sent = 0;
        for (Long packed : ordered) {
            if (sent >= maxPerTick) {
                break;
            }
            int x = ChunkPos.getX(packed);
            int z = ChunkPos.getZ(packed);
            LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
            if (chunk == null) {
                continue;
            }
            ClientboundLevelChunkWithLightPacket packet;
            try {
                packet = new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null);
            } catch (Exception e) {
                Constants.LOG.error("Hassium: Failed to build paced vanilla chunk packet [{}, {}]", x, z, e);
                continue;
            }
            player.connection.send(packet);
            pending.remove(packed);
            sent++;
        }
        if (pending.isEmpty()) {
            pendingSends.remove(player.getUUID());
            pendingSendDimension.remove(player.getUUID());
        }
    }

    /**
     * ROUND1 / Bloom 已上报且 miss、本会话尚未直推过：跳过主线程 hash，直接入队。
     * 直推仅此条件：Bloom 存在但不命中，且会话表无记录。
     * Bloom 未就绪必须走 pushPool hash（R2 重连上报前）；hit 或会话复用同样走
     * {@link #submitMetadataTaskFromChunk}。
     */
    static boolean shouldDirectPushWithoutHash(boolean bloomMiss, Long lastSessionPushedHash) {
        return shouldDirectPushWithoutHash(bloomMiss, lastSessionPushedHash, true);
    }

    /**
     * {@code bloomReady=false}（尚未收到 Bloom）禁止 skip-hash：否则 R2 在上报前会把
     * 整视距当 ROUND1 直推且 contentHash=0，缓存全命中恒为 0。
     */
    static boolean shouldDirectPushWithoutHash(boolean bloomMiss, Long lastSessionPushedHash,
                                               boolean bloomReady) {
        return bloomReady && bloomMiss && lastSessionPushedHash == null;
    }

    /**
     * 预算已尽的路径禁止 {@code getChunkNow}：ROUND1 直推看入队预算，hash/会话复用看 hash 预算。
     */
    static boolean shouldProbeWorldForPacedPending(boolean directNoHash, int fullBudget, int hashBudget) {
        if (fullBudget <= 0 && hashBudget <= 0) {
            return false;
        }
        return directNoHash ? fullBudget > 0 : hashBudget > 0;
    }

    /**
     * 包可见：paced drain 单 tick 发送定额。admission room = MAX_PENDING − (pending + inFlight)。
     */
    static int pacedSendBudget(int pendingCount, int inFlightCount, int maxChunksPerTick) {
        int room = ChunkAdmissionController.MAX_PENDING_PER_PLAYER - pendingCount - inFlightCount;
        if (room <= 0 || maxChunksPerTick <= 0) {
            return 0;
        }
        return Math.min(maxChunksPerTick, room);
    }

    /**
     * 包可见：paced 入队预算与 drain 定额相同（源头已限速，不再领先入队）。
     */
    static int pacedEnqueueBudget(int pendingCount, int inFlightCount, int maxChunksPerTick) {
        return pacedSendBudget(pendingCount, inFlightCount, maxChunksPerTick);
    }

    /**
     * 包可见：仅在入队/提交成功时从 paced pending 移除 key；失败则保留以便重试。
     *
     * @return true if the key was consumed (accepted)
     */
    static boolean commitPacedPendingKey(Set<Long> pending, long packedKey, boolean accepted) {
        if (!accepted) {
            return false;
        }
        pending.remove(packedKey);
        return true;
    }

    /**
     * 每 tick 分批补发 resync 队列：每玩家最多 RESYNC_PER_TICK 个。
     * 在主线程调用（getChunkNow 读世界区块）。
     */
    private void drainPendingResync(net.minecraft.server.MinecraftServer server) {
        if (pendingResync.isEmpty()) {
            return;
        }
        try {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                Deque<ResyncEntry> queue = pendingResync.get(player.getUUID());
                if (queue == null || queue.isEmpty()) {
                    continue;
                }
                ServerLevel level = PlayerCompat.getServerLevel(player);
                if (level == null) {
                    continue;
                }
                // 方案 A：客户端无缓存/Bloom，resync 无需等待，直接提交
                int processed = 0;
                int skipped = 0;
                // skipped 计入 tick 配额：null 条目放回队尾后若仅约束 processed，
                // 同 tick 内全 null 时会 poll→addLast 无限循环；processed+skipped 封顶保证有界。
                while (!queue.isEmpty() && processed + skipped < RESYNC_PER_TICK) {
                    ResyncEntry entry = queue.poll();
                    // chunk 可能尚未生成（1.21.11 首次登录大部分区块未生成）或已被卸载；
                    // getChunkNow 返回 null 时放回队尾，留待后续 tick 重试，避免永久丢弃补发
                    LevelChunk chunk = level.getChunkSource().getChunkNow(entry.pos().x, entry.pos().z);
                    if (chunk == null) {
                        skipped++;
                        queue.addLast(entry);
                        continue;
                    }
                    if (isAdmissionPendingOrInFlight(player, entry)) {
                        // resync 只补 full admission 尚未覆盖的 key；不重发 hash/直推以绕过窗口。
                        processed++;
                        continue;
                    }
                    submitMetadataTaskFromChunk(player, entry.pos(), chunk, entry.dimension());
                    processed++;
                }
                if (processed > 0) {
                    DebugLogger.info(LogType.CHUNK_APPLY,
                            "Hassium: Resync drain for {} — submitted {}, skipped {}, remaining {}",
                            player.getName().getString(), processed, skipped, queue.size());
                }
                if (queue.isEmpty()) {
                    pendingResync.remove(player.getUUID());
                }
            }
            // 清理已离线玩家的队列
            pendingResync.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        } catch (Exception e) {
            Constants.LOG.error("Hassium: drainPendingResync failed", e);
        }
    }

    /**
     * 与原版 {@code ChunkMap.isChunkInRange} 一致的视距判定（圆柱近似）。
     */
    private static boolean isServerChunkInRange(int chunkX, int chunkZ, int centerX, int centerZ, int viewDistance) {
        int dx = Math.max(0, Math.abs(chunkX - centerX) - 1);
        int dz = Math.max(0, Math.abs(chunkZ - centerZ) - 1);
        long outer = Math.max(0, Math.max(dx, dz) - 1);
        long inner = Math.min(dx, dz);
        long distSq = inner * inner + outer * outer;
        long limit = (long) viewDistance * (long) viewDistance;
        return distSq < limit;
    }

    /**
     * 服务端每 tick：冲刷到期 hash 批次 + 按 tick 限流序列化数据请求。
     */
    public void onServerTick(net.minecraft.server.MinecraftServer server) {
        if (server == null) {
            return;
        }
        // 注意：pendingResync 也需要 onServerTick 来 drain，必须加入条件判断
        if (!initialized.get() && dataQueues.isEmpty() && hashBatches.isEmpty()
                && pendingResync.isEmpty() && pendingSends.isEmpty()
                && pendingFullSends.isEmpty()) {
            return;
        }
        ensureInitialized();

        long now = System.currentTimeMillis();
        long nowNanos = System.nanoTime();
        long drainPendingNs = 0L;
        long drainQueueNs = 0L;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            flushPlayerHashBatchIfDue(player, now);
            long t0 = System.nanoTime();
            drainPendingSends(player);
            drainPendingFullSends(player);
            drainPendingNs += System.nanoTime() - t0;
            t0 = System.nanoTime();
            drainPlayerQueueTick(player);
            drainQueueNs += System.nanoTime() - t0;
            expirePlayerDeliveries(player, nowNanos);
            logStallServer(player);
        }
        TickMonitor.addHassiumDrainNs(drainPendingNs, drainQueueNs);

        // 出界待命任务周期重评估（玩家折返/静止后恢复入队，防永久虚空）
        if (now - lastDeferredCheckMs >= DEFER_CHECK_INTERVAL_MS) {
            lastDeferredCheckMs = now;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                requeueDeferredChunks(player, now);
            }
        }

        // 分批补发握手后 resync 队列（每 tick 最多 RESYNC_PER_TICK 个/玩家）
        drainPendingResync(server);

        // 清理已离线玩家的批次
        hashBatches.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        admissionControllers.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        inFlightTasks.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
    }

    /** 上次出界待命检查时间（毫秒） */
    private volatile long lastDeferredCheckMs = 0L;

    /**
     * 出界待命任务重评估：重新在视距内 → 恢复入队（优先级按当前位置重算）；
     * 超时未回来 → 真丢弃（玩家已远离，无再推意义）。
     */
    private void requeueDeferredChunks(ServerPlayer player, long nowMs) {
        Map<Long, DeferredTask> deferred = deferredChunks.get(player.getUUID());
        if (deferred == null || deferred.isEmpty()) {
            return;
        }
        ChunkPos playerChunk = player.chunkPosition();
        int serverVD = PlayerCompat.getViewDistance(player);
        var it = deferred.entrySet().iterator();
        while (it.hasNext()) {
            DeferredTask task = it.next().getValue();
            if (nowMs - task.deferredAtMs() > DEFER_MAX_WAIT_MS) {
                it.remove();
                continue;
            }
            if (isServerChunkInRange(task.pos().x, task.pos().z, playerChunk.x, playerChunk.z, serverVD)) {
                it.remove();
                DebugLogger.info(LogType.NETWORK,
                        "[PROCESS_QUEUE] Re-enqueueing deferred chunk {} (back in range)",
                        task.pos());
                enqueueDirectPush(player, task.dimension(), List.of(task.pos()));
            }
        }
    }

    private void flushPlayerHashBatchIfDue(ServerPlayer player, long nowMs) {
        UUID playerId = player.getUUID();
        PendingHashBatch batch;
        List<ChunkHashS2CPacket.Entry> toSend;
        synchronized (hashBatches) {
            batch = hashBatches.get(playerId);
            if (batch == null || batch.entries.isEmpty()) {
                return;
            }
            if (nowMs - batch.createdAtMs < HASH_BATCH_MAX_WAIT_MS
                    && batch.entries.size() < HASH_BATCH_MAX_ENTRIES) {
                return;
            }
            // 到期：本 tick 最多发 maxChunksPerTick 条（与数据直推同节奏），剩余留批次下 tick 续发。
            // 客户端比对（读盘-计算 hash）速率由此受限，避免 resync 一次性倾泻数百 hash。
            int perTick = HassiumConfigService.getInstance().getConfig()
                    .master().maxChunksPerTick();
            int take = Math.min(Math.max(1, perTick), batch.entries.size());
            toSend = new ArrayList<>(batch.entries.subList(0, take));
            batch.entries.subList(0, take).clear();
            if (batch.entries.isEmpty()) {
                hashBatches.remove(playerId, batch);
            }
        }
        flushHashBatch(player, toSend, batch.dimension);
    }

    /**
     * 从 LevelChunk 计算 sectionBitmap（哪些 section 有方块数据）。
     */
    private int computeSectionBitmap(LevelChunk chunk) {
        int bitmap = 0;
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length && i < 24; i++) {
            if (!sections[i].hasOnlyAir()) {
                bitmap |= (1 << i);
            }
        }
        return bitmap;
    }

    /**
     * 分段增量视距余量：覆盖玩家移动导致「刚推完 ChunkHash 就走出视距」的竞态。
     */
    private static final int SECTION_DELTA_VIEW_MARGIN = 1;

    /**
     * 处理客户端的 section 哈希请求（阶段二）。
     * <p>
     * 主线程只做已加载柱的脱离拷贝（{@code getChunkNow} + PalettedContainer.copy），
     * 禁止 {@code getChunk} 同步读盘。hash / 平面 / 规划 / write / 回包全部下推
     * {@code pushPool}：live {@link LevelChunk} 不能给后台读（ThreadingDetector）。
     * 每次请求都回包：可服务的进 {@code entries}，超距/失败的进 {@code skipped}（客户端回退全量）。
     */
    public void handleSectionHashRequest(ServerPlayer player, SectionHashRequestC2SPacket request) {
        if (!player.isAlive() || player.hasDisconnected()) { return; }
        ensureInitialized();

        ServerLevel level = PlayerCompat.getServerLevel(player);
        if (level == null) {
            return;
        }
        int maxDist = PlayerCompat.getViewDistance(player) + SECTION_DELTA_VIEW_MARGIN;
        ChunkPos playerChunkPos = player.chunkPosition();
        List<SectionDeltaS2CPacket.SkippedChunk> skipped = new ArrayList<>();
        List<SectionDeltaWork> works = new ArrayList<>();

        for (var entry : request.entries()) {
            int dx = Math.abs(entry.chunkX() - playerChunkPos.x);
            int dz = Math.abs(entry.chunkZ() - playerChunkPos.z);
            if (dx > maxDist || dz > maxDist) {
                DebugLogger.info(LogType.NETWORK,
                        "[SECTION_DELTA] Skip [{}, {}] out of range (dx={}, dz={}, maxDist={}, player=[{}, {}])",
                        entry.chunkX(), entry.chunkZ(), dx, dz, maxDist,
                        playerChunkPos.x, playerChunkPos.z);
                skipped.add(new SectionDeltaS2CPacket.SkippedChunk(entry.chunkX(), entry.chunkZ()));
                continue;
            }

            LevelChunk chunk = level.getChunkSource().getChunkNow(entry.chunkX(), entry.chunkZ());
            if (chunk == null) {
                skipped.add(new SectionDeltaS2CPacket.SkippedChunk(entry.chunkX(), entry.chunkZ()));
                continue;
            }

            try {
                works.add(new SectionDeltaWork(snapshotSectionDeltaColumn(chunk), entry));
            } catch (Exception e) {
                Constants.LOG.error("[SECTION_DELTA] Failed to snapshot chunk [{}, {}]",
                        entry.chunkX(), entry.chunkZ(), e);
                skipped.add(new SectionDeltaS2CPacket.SkippedChunk(entry.chunkX(), entry.chunkZ()));
            }
        }

        String dimension = request.dimension();
        pushPool.submit(() -> {
            if (player.hasDisconnected()) {
                return;
            }
            List<SectionDeltaS2CPacket.DeltaEntry> deltas = new ArrayList<>();
            List<SectionDeltaS2CPacket.SkippedChunk> skippedOut = new ArrayList<>(skipped);
            for (SectionDeltaWork work : works) {
                try {
                    SectionDeltaS2CPacket.DeltaEntry planned = planAndSerialize(work.snap(), work.entry());
                    if (planned == null) {
                        skippedOut.add(new SectionDeltaS2CPacket.SkippedChunk(
                                work.entry().chunkX(), work.entry().chunkZ()));
                        continue;
                    }
                    deltas.add(planned);
                } catch (Exception e) {
                    Constants.LOG.error("[SECTION_DELTA] Failed to process chunk [{}, {}]",
                            work.entry().chunkX(), work.entry().chunkZ(), e);
                    skippedOut.add(new SectionDeltaS2CPacket.SkippedChunk(
                            work.entry().chunkX(), work.entry().chunkZ()));
                }
            }
            sendSectionDeltaResponse(player, dimension, deltas, skippedOut);
        });
    }

    /**
     * 组包并发送阶段二响应（Data 通道优先，回退 Primary）。
     */
    private void sendSectionDeltaResponse(ServerPlayer player, String dimension,
                                          List<SectionDeltaS2CPacket.DeltaEntry> deltas,
                                          List<SectionDeltaS2CPacket.SkippedChunk> skipped) {
        // 始终回包，避免客户端悬等（含 entries/skipped 皆空的边界）
        FriendlyByteBuf buf = null;
        boolean sent = false;
        boolean routedViaData = false;
        try {
            SectionDeltaS2CPacket deltaPacket = new SectionDeltaS2CPacket(
                    dimension, deltas, skipped);
            buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            deltaPacket.encode(buf);

            // §14 第 3 步：Data 通道优先分流（与 ChunkSender BulkCompressedChunk 路径同构）
            // 口径等价 Primary：delta 已独立压缩，本路径不再进 ZSTD；payload = encode 后原始字节
            int payloadLen = buf.readableBytes();
            byte[] bulkPayload = new byte[payloadLen];
            buf.getBytes(buf.readerIndex(), bulkPayload);
            routedViaData = io.github.limuqy.mc.hassium.network.dataplane.DataPlaneServer.tryRouteBulk(
                            player.getUUID(),
                            io.github.limuqy.mc.hassium.network.dataplane.DataPlaneFrame.TYPE_BULK_SECTION_DELTA,
                            bulkPayload);
            // §14 v2 后续重建：feature 侧 DataPlaneServer.tryRouteBulk façade 内部自检未启用/未 bound/无会话
            // → 直接调用；去掉 master 旧 DataPlanePoCConfig.isEnabled() 短路守卫（方法已不存在）。
            if (routedViaData) {
                // tryRouteBulk 内部已 recordBulkSentData + recordBulkSentDataByPort(endpointIdx+1, payloadLen)；
                // 端点维度累加由 router routeAndPick 暴露 chosen.target.endpointId() 后在调用站点记。此处不再二次累加。
                sent = true;
                DebugLogger.info(LogType.NETWORK,
                        "[SECTION_DELTA] Sent via Data plane (frameType=4) deltas={} skipped={} (dimension={})",
                        deltas.size(), skipped.size(), dimension);
            } else {
                // 走 Primary：口径与 ChunkSender Primary fallback 一致
                io.github.limuqy.mc.hassium.metrics.NetworkStats.recordBulkSentPrimary(payloadLen);
                Services.NETWORK_MANAGER.sendSectionDeltaPacket(player, buf);
                sent = true;
                DebugLogger.info(LogType.NETWORK,
                        "[SECTION_DELTA] Sent via Primary: {} deltas, {} skipped (dimension={})",
                        deltas.size(), skipped.size(), dimension);
            }
        } catch (Exception e) {
            Constants.LOG.error("[SECTION_DELTA] Failed to send delta response", e);
        } finally {
            // sendSectionDeltaPacket 会 release buf；Data 路径未消费 buf，需主动释放
            // Primary 路径（sendSectionDeltaPacket）内部 release buf；Data 路径未消费 buf，需兜底 release；异常路径同样需兜底
            if (buf != null && (!sent || routedViaData)) {
                buf.release();
            }
        }
    }

    /**
     * 处理客户端的 blockEntity 数据请求。
     * <p>
     * 主线程只对已加载柱做 NBT 快照（{@code getChunkNow}）；组包发送下推 {@code pushPool}。
     */
    @SuppressWarnings("deprecation") // Forge: BuiltInRegistries 字段在 Forge patched jar 中被标记 @Deprecated
    public void handleBlockEntityRequest(ServerPlayer player, BlockEntityRequestC2SPacket request) {
        if (!player.isAlive() || player.hasDisconnected()) { return; }
        ensureInitialized();

        ServerLevel level = PlayerCompat.getServerLevel(player);
        if (level == null) {
            return;
        }
        int viewDistance = PlayerCompat.getViewDistance(player);
        ChunkPos playerChunkPos = player.chunkPosition();
        List<BlockEntityDataS2CPacket.ChunkBlockEntities> entries = new ArrayList<>();

        for (ChunkPos pos : request.chunks()) {
            try {
                int dx = Math.abs(pos.x - playerChunkPos.x);
                int dz = Math.abs(pos.z - playerChunkPos.z);
                if (dx > viewDistance || dz > viewDistance) { continue; }

                LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
                if (chunk == null) { continue; }

                entries.add(new BlockEntityDataS2CPacket.ChunkBlockEntities(
                        pos.x, pos.z, collectRequestedBlockEntities(chunk)));
            } catch (Exception e) {
                Constants.LOG.error("[BLOCK_ENTITY] Failed to collect block entities for chunk {}", pos, e);
            }
        }

        if (entries.isEmpty()) {
            return;
        }
        String dimension = request.dimension();
        pushPool.submit(() -> {
            if (player.hasDisconnected()) {
                return;
            }
            FriendlyByteBuf buf = null;
            boolean sent = false;
            try {
                BlockEntityDataS2CPacket packet = new BlockEntityDataS2CPacket(dimension, entries);
                buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                packet.encode(buf);
                Services.NETWORK_MANAGER.sendBlockEntityData(player, buf);
                sent = true;
            } catch (Exception e) {
                Constants.LOG.error("[BLOCK_ENTITY] Failed to send block entity data", e);
            } finally {
                if (!sent && buf != null) {
                    buf.release();
                }
            }
        });
    }

    /**
     * 对已脱离 live world 的 section 拷贝做 Planner → FULL / BLOCKS。
     * 返回 null 表示整块 skipped（75% 回退）。
     */
    private SectionDeltaS2CPacket.DeltaEntry planAndSerialize(SectionDeltaColumnSnap snap,
                                                             SectionHashRequestC2SPacket.Entry clientEntry) {
        SectionDeltaSnapshot serverSnap = SectionDeltaSnapshot.capture(snap.sections());
        SectionDeltaSnapshot clientSnap = new SectionDeltaSnapshot(
                clientEntry.sectionHashes(), clientEntry.planes());
        SectionDeltaPlanner.ChunkDecision decision = SectionDeltaPlanner.plan(clientSnap, serverSnap);
        if (decision.skipWholeChunk()) {
            DebugLogger.info(LogType.NETWORK,
                    "[SECTION_DELTA] Fallback to full for [{}, {}]: changed sections >= {}%",
                    clientEntry.chunkX(), clientEntry.chunkZ(),
                    SectionDeltaPlanner.FALLBACK_THRESHOLD_PCT);
            return null;
        }
        List<SectionDeltaS2CPacket.SectionData> changedSections = new ArrayList<>();
        for (SectionDeltaPlanner.SectionDecision sd : decision.sections()) {
            if (sd.kind() == SectionDeltaPlanner.Kind.SKIP) {
                continue;
            }
            LevelChunkSection section = sectionAt(snap.sections(), sd.sectionIndex());
            if (section == null) {
                continue;
            }
            if (sd.kind() == SectionDeltaPlanner.Kind.FULL) {
                changedSections.add(new SectionDeltaS2CPacket.SectionData(
                        sd.sectionIndex(), SectionDeltaS2CPacket.KIND_FULL,
                        writeSectionBytes(section)));
                continue;
            }
            byte[] full = writeSectionBytes(section);
            int[] stateIds = stateIdsOf(section, sd.candidates());
            byte[] blocks = SectionPlaneSyndrome.encodeBlockList(sd.candidates(), stateIds);
            if (blocks.length <= full.length) {
                changedSections.add(new SectionDeltaS2CPacket.SectionData(
                        sd.sectionIndex(), SectionDeltaS2CPacket.KIND_BLOCKS, blocks));
            } else {
                changedSections.add(new SectionDeltaS2CPacket.SectionData(
                        sd.sectionIndex(), SectionDeltaS2CPacket.KIND_FULL, full));
            }
        }
        long expectedChunkHash = ChunkContentHashUtil.combineSectionHashesFromArray(serverSnap.sectionHashes());
        return new SectionDeltaS2CPacket.DeltaEntry(
                clientEntry.chunkX(), clientEntry.chunkZ(), changedSections,
                snap.heightmaps(), snap.blockEntities(), expectedChunkHash);
    }

    private static LevelChunkSection sectionAt(LevelChunkSection[] sections, int index) {
        return index >= 0 && index < sections.length ? sections[index] : null;
    }

    /** 主线程：PalettedContainer 拷贝 + heightmap/BE 快照，不再 hash / 扫格 / write。 */
    private SectionDeltaColumnSnap snapshotSectionDeltaColumn(LevelChunk chunk) {
        LevelChunkSection[] src = chunk.getSections();
        LevelChunkSection[] copies = new LevelChunkSection[src.length];
        for (int i = 0; i < src.length; i++) {
            if (src[i] == null) {
                continue;
            }
            copies[i] = LevelChunkSectionCompat.copyDetached(src[i]);
        }
        return new SectionDeltaColumnSnap(copies, collectHeightmaps(chunk), collectBlockEntities(chunk));
    }

    private int[] stateIdsOf(LevelChunkSection section, int[] candidates) {
        int[] ids = new int[candidates.length];
        for (int i = 0; i < candidates.length; i++) {
            int packed = candidates[i];
            ids[i] = LevelChunkSectionCompat.blockStateId(section.getBlockState(
                    SectionPlaneSyndrome.localX(packed),
                    SectionPlaneSyndrome.localY(packed),
                    SectionPlaneSyndrome.localZ(packed)));
        }
        return ids;
    }

    private byte[] writeSectionBytes(LevelChunkSection section) {
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            section.write(buf);
            byte[] data = new byte[buf.readableBytes()];
            buf.getBytes(0, data);
            return data;
        } finally {
            buf.release();
        }
    }

    /**
     * 收集 chunk 全部 heightmap rawData（FULL status 的 types；与服务端 chunk 当前状态一致）。
     * delta 包不含 heightmap 线格式，必须随包下发，客户端 merge 后逐 type setHeightmap。
     */
    private List<SectionDeltaS2CPacket.HeightmapData> collectHeightmaps(LevelChunk chunk) {
        List<SectionDeltaS2CPacket.HeightmapData> result = new ArrayList<>();
        for (var entry : chunk.getHeightmaps()) {
            long[] raw = entry.getValue().getRawData();
            result.add(new SectionDeltaS2CPacket.HeightmapData(
                    entry.getKey().ordinal(), raw != null ? raw.clone() : new long[0]));
        }
        return result;
    }

    /**
     * 收集 chunk 中所有 blockEntity 的数据
     */
    @SuppressWarnings("deprecation") // Forge: BuiltInRegistries 字段在 Forge patched jar 中被标记 @Deprecated
    private List<SectionDeltaS2CPacket.BlockEntityData> collectBlockEntities(LevelChunk chunk) {
        List<SectionDeltaS2CPacket.BlockEntityData> result = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
            BlockPos pos = entry.getKey();
            BlockEntity be = entry.getValue();
#if MC_VER < MC_1_20_5
            CompoundTag nbt = be.saveWithoutMetadata();
#else
            CompoundTag nbt = be.saveWithoutMetadata(be.getLevel().registryAccess());
#endif
#if MC_VER < MC_1_21_11
            ResourceLocation
#else
            Identifier
#endif
            type = net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
            if (type != null) {
                result.add(new SectionDeltaS2CPacket.BlockEntityData(pos, type, nbt));
            }
        }
        return result;
    }

    private List<BlockEntityDataS2CPacket.BlockEntityData> collectRequestedBlockEntities(LevelChunk chunk) {
        List<SectionDeltaS2CPacket.BlockEntityData> src = collectBlockEntities(chunk);
        List<BlockEntityDataS2CPacket.BlockEntityData> out = new ArrayList<>(src.size());
        for (SectionDeltaS2CPacket.BlockEntityData be : src) {
            out.add(new BlockEntityDataS2CPacket.BlockEntityData(be.pos(), be.type(), be.nbt()));
        }
        return out;
    }

    /**
     * 将区块数据请求入队
     *
     * @param player    请求的玩家
     * @param dimension 维度
     * @param chunks    请求的区块列表
     */
    public void enqueueDataRequest(ServerPlayer player, String dimension, List<ChunkPos> chunks) {
        enqueueDataRequest(player, dimension, chunks, 0L);
    }

    /**
     * Enqueues a full-data request. A positive fallback id is valid only for its original single SeedRef
     * delivery; this monitor releases that exact reservation and commits the replacement admission
     * without allowing another producer to reuse the key between those transitions.
     */
    public synchronized void enqueueDataRequest(ServerPlayer player, String dimension, List<ChunkPos> chunks,
                                                long fallbackDeliveryId) {
        if (fallbackDeliveryId <= 0L) {
            NetworkStats.recordDataRequestReceived();
            enqueueInternal(player, dimension, chunks, true);
            return;
        }
        if (player == null || chunks == null || chunks.size() != 1) {
            return;
        }
        UUID playerId = player.getUUID();
        DataRequestTask task = inFlightTasks.getOrDefault(playerId, Map.of()).get(fallbackDeliveryId);
        ChunkPos pos = chunks.get(0);
        if (task == null || task.seedRef() == null || !task.dimension().equals(dimension)
                || task.pos().x != pos.x || task.pos().z != pos.z) {
            return;
        }
        ChunkAdmissionController controller = admissionControllers.get(playerId);
        if (controller == null || !controller.release(admissionKey(task), fallbackDeliveryId)) {
            return;
        }
        inFlightTasks.getOrDefault(playerId, Map.of()).remove(fallbackDeliveryId, task);
        NetworkStats.recordDataRequestReceived();
        enqueueInternal(player, dimension, chunks, true);
    }

    /**
     * Bloom miss 主动直推入队（服务端驱动，不计入客户端请求统计）。
     * 与客户端请求共用同一队列/去重/出界待命语义。
     *
     * @return true if every chunk was newly queued or already under admission
     */
    public boolean enqueueDirectPush(ServerPlayer player, String dimension, List<ChunkPos> chunks) {
        return enqueueDirectPush(player, dimension, chunks, 0L);
    }

    boolean enqueueDirectPush(ServerPlayer player, String dimension, List<ChunkPos> chunks, long contentHash) {
        DebugLogger.info(LogType.NETWORK, "[ENQUEUE_DATA] Direct push {} chunks to player {} (dimension={})",
                chunks.size(), player.getName().getString(), dimension);

        return enqueueInternal(player, dimension, chunks, false, contentHash);
    }

    /**
     * @return true if every chunk was newly queued or already under admission
     */
    private boolean enqueueInternal(ServerPlayer player, String dimension, List<ChunkPos> chunks,
                                 boolean countSeedGenFallback) {
        return enqueueInternal(player, dimension, chunks, countSeedGenFallback, 0L);
    }

    private boolean enqueueInternal(ServerPlayer player, String dimension, List<ChunkPos> chunks,
                                 boolean countSeedGenFallback, long contentHash) {
        ensureInitialized();
        if (!player.isAlive() || player.hasDisconnected()) {
            Constants.LOG.warn("[ENQUEUE_DATA] Player {} is not online, ignoring data request",
                    player.getName().getString());
            return false;
        }

        UUID playerId = player.getUUID();
        ChunkAdmissionController controller = admissionControllers.computeIfAbsent(
                playerId, ignored -> new ChunkAdmissionController());
        int queued = 0;
        boolean allAccepted = true;
        for (ChunkPos pos : chunks) {
            long taskHash = chunks.size() == 1 ? contentHash : 0L;
            DataRequestTask task = new DataRequestTask(pos, dimension, null, taskHash);
            // 已在 pending/in-flight：视为成功（paced pending 可安全移除，避免永久重试）
            if (controller.contains(admissionKey(task))) {
                if (countSeedGenFallback) {
                    recordSeedGenFallback(playerId, pos, dimension);
                }
                continue;
            }
            if (offerPendingTask(player, task)) {
                queued++;
            } else {
                allAccepted = false;
                if (shouldBacklogUnacceptedFullRequest(false, controller.contains(admissionKey(task)))) {
                    markPendingFullSend(player, pos, dimension);
                }
            }
            if (countSeedGenFallback) {
                recordSeedGenFallback(playerId, pos, dimension);
            }
        }
        DebugLogger.info(LogType.NETWORK,
                "[ENQUEUE_DATA] Player {} queued {} chunks (dimension={}, accepted={})",
                player.getName().getString(), queued, dimension, allAccepted);
        return allAccepted;
    }

    /** Enqueues a SeedRef through the same keyed admission as compressed full payloads. */
    private void enqueueSeedRef(ServerPlayer player, ChunkPos pos, String dimension,
                                long chunkHash, long[] sectionHashes) {
        if (!player.isAlive() || player.hasDisconnected()) {
            return;
        }
        offerPendingTask(player, new DataRequestTask(pos, dimension,
                new SeedRefWork(chunkHash, sectionHashes)));
    }

    /**
     * Commits the controller and FIFO entries under one manager monitor. A queue insertion
     * failure withdraws the just-added pending key, so it cannot consume admission
     * capacity without a drainable task.
     */
    private synchronized boolean offerPendingTask(ServerPlayer player, DataRequestTask task) {
        UUID playerId = player.getUUID();
        ChunkAdmissionController controller = admissionControllers.computeIfAbsent(
                playerId, ignored -> new ChunkAdmissionController());
        ChunkAdmissionController.ChunkDeliveryKey key = admissionKey(task);
        if (controller.contains(key)) {
            return false;
        }
        FifoChunkQueue queue = dataQueues.computeIfAbsent(playerId, ignored -> new FifoChunkQueue());
        if (controller.pendingCount() >= ChunkAdmissionController.MAX_PENDING_PER_PLAYER
                || queue.size() >= MAX_DATA_QUEUE_PER_PLAYER) {
            io.github.limuqy.mc.hassium.utils.StallDiag.noteEnqueueReject();
            return false;
        }
        if (!controller.offer(key)) {
            io.github.limuqy.mc.hassium.utils.StallDiag.noteEnqueueReject();
            return false;
        }
        try {
            queue.offer(key, task);
            return true;
        } catch (RuntimeException e) {
            controller.withdrawPending(key);
            throw e;
        }
    }

    private static ChunkAdmissionController.ChunkDeliveryKey admissionKey(DataRequestTask task) {
        return new ChunkAdmissionController.ChunkDeliveryKey(task.dimension(), task.pos().x, task.pos().z);
    }

    /** Gateway 会话存在时，full admission 只在 active + writable 的 channel 上推进。 */
    private static boolean isFullDeliveryChannelWritable(ServerPlayer player) {
        io.github.limuqy.mc.hassium.network.gateway.GatewayPlayerSession session =
                io.github.limuqy.mc.hassium.network.gateway.GatewayServer.getInstance()
                        .registry().get(player.getUUID());
        return session == null || session.channel().isWritable();
    }

    /** resync 不得覆盖同一 key 已排队或等待 ACK 的 authoritative full delivery。 */
    private boolean isAdmissionPendingOrInFlight(ServerPlayer player, ResyncEntry entry) {
        ChunkAdmissionController controller = admissionControllers.get(player.getUUID());
        return controller != null && controller.contains(new ChunkAdmissionController.ChunkDeliveryKey(
                entry.dimension(), entry.pos().x, entry.pos().z));
    }

    /**
     * 网关 ACK 经 {@code MinecraftServer.execute} 切入服务器线程后调用。
     * 会话对象是网关附着时创建的不可变身份；重连覆盖后，旧 event-loop 回调无法释放
     * 新会话从 1 重新分配的 delivery id。
     */
    public void handleChunkApplyAck(GatewayPlayerSession session, ChunkApplyAck ack) {
        if (session == null || ack == null
                || GatewayServer.getInstance().registry().get(session.playerId()) != session) {
            return;
        }
        UUID playerId = session.playerId();
        ChunkAdmissionController controller = admissionControllers.get(playerId);
        if (controller == null) {
            return;
        }
        Map<Long, DataRequestTask> tasks = inFlightTasks.get(playerId);
        long nowNanos = System.nanoTime();
        int unknown = 0;
        for (long deliveryId : ack.deliveryIds()) {
            if (controller.acknowledge(deliveryId, nowNanos)) {
                if (tasks != null) {
                    tasks.remove(deliveryId);
                }
            } else {
                unknown++;
            }
        }
        if (unknown > 0) {
            io.github.limuqy.mc.hassium.utils.StallDiag.event(
                    "ack unknown={} of {} {}", unknown, ack.size(), controller.diagLine());
        }
    }

    /** Requeues only timed-out deliveries that remain in this player's currently tracked view. */
    private void expirePlayerDeliveries(ServerPlayer player, long nowNanos) {
        ChunkAdmissionController controller = admissionControllers.get(player.getUUID());
        if (controller == null) {
            return;
        }
        Map<Long, DataRequestTask> tasks = inFlightTasks.get(player.getUUID());
        int expiredCount = 0;
        int requeued = 0;
        for (ChunkAdmissionController.ExpiredDelivery expired :
                controller.expire(nowNanos, DELIVERY_TIMEOUT_NANOS)) {
            expiredCount++;
            DataRequestTask task = tasks != null ? tasks.remove(expired.deliveryId()) : null;
            if (task != null && isStillTracking(player, task)) {
                offerPendingTask(player, task);
                requeued++;
            } else {
                controller.release(expired.key());
            }
        }
        if (expiredCount > 0) {
            io.github.limuqy.mc.hassium.utils.StallDiag.event(
                    "expire n={} requeued={} timeoutMs={} {}",
                    expiredCount, requeued, TimeUnit.NANOSECONDS.toMillis(DELIVERY_TIMEOUT_NANOS),
                    controller.diagLine());
        }
    }

    private void logStallServer(ServerPlayer player) {
        ChunkAdmissionController controller = admissionControllers.get(player.getUUID());
        FifoChunkQueue queue = dataQueues.get(player.getUUID());
        java.util.Set<Long> pending = pendingSends.get(player.getUUID());
        java.util.Set<Long> fullPending = pendingFullSends.get(player.getUUID());
        io.github.limuqy.mc.hassium.utils.StallDiag.serverHz(
                "writable={} q={} pacedPending={} fullPending={} enqReject={} {}",
                isFullDeliveryChannelWritable(player),
                queue == null ? 0 : queue.size(),
                pending == null ? 0 : pending.size(),
                fullPending == null ? 0 : fullPending.size(),
                io.github.limuqy.mc.hassium.utils.StallDiag.takeEnqueueRejects(),
                controller == null ? "admission=null" : controller.diagLine());
    }

    private static boolean isStillTracking(ServerPlayer player, DataRequestTask task) {
        ServerLevel level = PlayerCompat.getServerLevel(player);
        if (!
#if MC_VER < MC_1_21_11
                level.dimension().location()
#else
                level.dimension().identifier()
#endif
                .toString().equals(task.dimension())) {
            return false;
        }
        ChunkPos center = player.chunkPosition();
        return isServerChunkInRange(task.pos().x, task.pos().z, center.x, center.z,
                PlayerCompat.getViewDistance(player));
    }

    /**
     * 每 tick 构建最多 maxChunksPerTick 个区块包快照，并将编码、压缩与发送下推 pushPool。
     * <p>
     * 任何版本都不能让 pushPool 读取 {@link LevelChunk}：1.20.1 已证实其
     * {@code PalettedContainer} 会与服务端主线程并发访问并抛出 ThreadingDetector 异常。
     */
    private void drainPlayerQueueTick(ServerPlayer player) {
        UUID playerId = player.getUUID();
        FifoChunkQueue queue = dataQueues.get(playerId);
        if (queue == null || queue.isEmpty()) {
            return;
        }

        if (!player.isAlive() || player.hasDisconnected()) {
            removePlayer(playerId);
            return;
        }


        AtomicBoolean flag = processingFlags.computeIfAbsent(playerId, k -> new AtomicBoolean(false));
        if (!flag.compareAndSet(false, true)) {
            return;
        }

        try {
            ChunkSender sender = ChunkSender.getInstance();
            if (sender == null) {
                Constants.LOG.error("[PROCESS_QUEUE] ChunkSender not initialized, cannot send chunk data "
                        + "(loader must call ChunkSender.setInstance in mod init)");
                return;
            }

            int maxPerTick = HassiumConfigService.getInstance().getConfig().master().maxChunksPerTick();
            if (maxPerTick <= 0) {
                maxPerTick = 4;
            }
            ChunkAdmissionController controller = admissionControllers.computeIfAbsent(
                    playerId, ignored -> new ChunkAdmissionController());
            if (!controller.beginTick(maxPerTick, isFullDeliveryChannelWritable(player))) {
                return;
            }

            ServerLevel level = PlayerCompat.getServerLevel(player);
            // 本 tick 玩家锚点与视距（服务端 tick 内位置不变）：drain 前快照，供出界丢弃判定
            ChunkPos playerChunk = player.chunkPosition();
            int serverVD = PlayerCompat.getViewDistance(player);
            List<SerializedChunkWork> works = new ArrayList<>(maxPerTick);

            // 所有版本主线程构建 packet 快照：ThreadingDetector 禁止/会崩于跨线程读 LevelChunk。
            // packet 内部数据已脱离世界对象，encode/压缩/发送在 pushPool。
            while (controller.canAdmit() && !queue.isEmpty()) {
                if (!player.isAlive() || player.hasDisconnected()) {
                    removePlayer(playerId);
                    return;
                }

                DataRequestTask task = queue.peek();
                if (task == null) {
                    break;
                }
                ChunkAdmissionController.ChunkDeliveryKey deliveryKey = admissionKey(task);

                // 任务排队期间玩家可能已移出权威视距：不静默丢弃（客户端无重试 → 永久虚空 bug 根因），
                // 转入待命集合，玩家折返/静止后重新在视距内时恢复入队；超时（10s）才真丢弃。
                if (!isServerChunkInRange(task.pos().x, task.pos().z, playerChunk.x, playerChunk.z, serverVD)) {
                    queue.poll();
                    controller.release(deliveryKey);
                    Map<Long, DeferredTask> deferred = deferredChunks.computeIfAbsent(
                            playerId, k -> new ConcurrentHashMap<>());
                    if (deferred.size() >= MAX_DEFERRED_PER_PLAYER) {
                        DebugLogger.warn(LogType.NETWORK,
                                "[PROCESS_QUEUE] Dropping deferred chunk {} (deferred map full, size={})",
                                task.pos(), deferred.size());
                        continue;
                    }
                    deferred.putIfAbsent(ChunkPos.asLong(task.pos().x, task.pos().z),
                            new DeferredTask(task.pos(), task.dimension(), System.currentTimeMillis()));
                    DebugLogger.info(LogType.NETWORK,
                            "[PROCESS_QUEUE] Deferring chunk {} (out of range, vd={}) — retry when back in range",
                            task.pos(), serverVD);
                    continue;
                }

                ChunkAdmissionController.Reservation reservation = controller.admit(deliveryKey);
                if (reservation == null) {
                    // 仍占队头。admit 因 !canAdmit 失败时 pending 仍在，下一 tick 再试。
                    // pending 已撤（untrack/出界）→ 丢掉队头。
                    if (controller.isPending(deliveryKey)) {
                        break;
                    }
                    queue.poll();
                    continue;
                }
                queue.poll();
                inFlightTasks.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>())
                        .put(reservation.deliveryId(), task);
                if (task.seedRef() != null) {
                    sendSeedRef(player, task, reservation.deliveryId());
                    continue;
                }

                try {
                    // 优先使用拦截时缓存的包字节或 packet（与 chunkHash / 反透视视图一致）
                    PreparedChunk prepared = takePreparedChunkPacket(playerId, task.pos());
                    byte[] chunkData = prepared != null ? prepared.data() : null;
                    ClientboundLevelChunkWithLightPacket packet = null;
                    if (chunkData == null) {
                        if (prepared != null) {
                            // 拦截路径已同步 build：直接后台 encode，主线程零序列化
                            packet = prepared.packet();
                        } else {
                            LevelChunk chunk = level.getChunkSource().getChunkNow(task.pos().x, task.pos().z);
                            if (chunk == null) {
                                Constants.LOG.warn("[PROCESS_QUEUE] Chunk {} not loaded, skipping", task.pos());
                                rollbackDelivery(player, task, reservation.deliveryId());
                                continue;
                            }
                            long tBuild = System.nanoTime();
                            packet = buildChunkPacket(chunk, level);
                            diag(D_BUILD, System.nanoTime() - tBuild);
                            if (packet == null) {
                                Constants.LOG.warn("[PROCESS_QUEUE] Failed to build chunk packet {}", task.pos());
                                rollbackDelivery(player, task, reservation.deliveryId());
                                continue;
                            }
                        }
                    }

                    works.add(new SerializedChunkWork(player, task, reservation.deliveryId(), chunkData,
                            packet, level.registryAccess()));
                    DebugLogger.info(LogType.NETWORK, "[PROCESS_QUEUE] Built chunk packet {} (remaining={})",
                            task.pos(), queue.size());
                } catch (Exception e) {
                    Constants.LOG.error("[PROCESS_QUEUE] Failed to prepare chunk {} for player {}",
                            task.pos(), player.getName().getString(), e);
                    rollbackDelivery(player, task, reservation.deliveryId());
                }
            }

            if (!works.isEmpty()) {
                DebugLogger.info(LogType.NETWORK, "[PROCESS_QUEUE] Tick drain for {}: prepared={}, remaining={}",
                        player.getName().getString(), works.size(), queue.size());
                for (SerializedChunkWork work : works) {
                    try {
                        pushPool.submit(() -> {
                            try {
                                byte[] chunkData = work.chunkData();
                                if (chunkData == null) {
                                    long tEnc = System.nanoTime();
                                    chunkData = encodeChunkPacket(work.packet(), work.registryAccess());
                                    diag(D_ENCODE, System.nanoTime() - tEnc);
                                }
                                if (chunkData == null) {
                                    Constants.LOG.warn("[PROCESS_QUEUE] Failed to encode chunk {}", work.task().pos());
                                    rollbackDelivery(work.player(), work.task(), work.deliveryId());
                                    return;
                                }
                                long tSend = System.nanoTime();
                                compressAndSend(work.player(), work.task(), chunkData, work.deliveryId(), sender);
                                diag(D_SEND, System.nanoTime() - tSend);
                            } catch (Throwable t) {
                                Constants.LOG.error("[PROCESS_QUEUE] Failed to encode/send chunk {}",
                                        work.task().pos(), t);
                                rollbackDelivery(work.player(), work.task(), work.deliveryId());
                            }
                        });
                    } catch (RuntimeException e) {
                        Constants.LOG.error("[PROCESS_QUEUE] Chunk push submission rejected for {}", work.task().pos(), e);
                        rollbackDelivery(work.player(), work.task(), work.deliveryId());
                    }
                }
            }
        } finally {
            flag.set(false);
        }
    }

    /** 后台压缩并发送（不访问世界对象）；计时从实际 transport handoff 开始。 */
    private void compressAndSend(ServerPlayer player, DataRequestTask task, byte[] chunkData, long deliveryId,
                                 ChunkSender sender) {
        if (!player.isAlive() || player.hasDisconnected()) {
            rollbackDelivery(player, task, deliveryId);
            return;
        }
        try {
            ChunkCompressionHandler.CompressedChunkData compressed =
                    ChunkCompressionHandler.compressChunkData(chunkData, task.pos().x, task.pos().z, deliveryId);
            if (compressed == null) {
                Constants.LOG.warn("[PROCESS_QUEUE] Failed to compress chunk {}", task.pos());
                rollbackDelivery(player, task, deliveryId);
                return;
            }
            sendCompressed(player, task, chunkData, compressed, deliveryId, sender);
        } catch (Exception e) {
            Constants.LOG.error("[PROCESS_QUEUE] Failed to compress/send chunk {} for player {}",
                    task.pos(), player.getName().getString(), e);
            rollbackDelivery(player, task, deliveryId);
        }
    }

    /**
     * 发送：速率由 maxChunksPerTick（每 tick 提交上限）× tick 节奏决定，不做秒级限速——
     * 掉刻时每 tick 提交量不变、每秒总量自然下降，即保护主线程。无令牌桶后
     * 无需延迟重提交，压缩完成后直接发送（全在 pushPool，不占主线程）。
     */
    private void sendCompressed(ServerPlayer player, DataRequestTask task, byte[] chunkData,
                                ChunkCompressionHandler.CompressedChunkData compressed, long deliveryId,
                                ChunkSender sender) {
        if (!player.isAlive() || player.hasDisconnected()) {
            rollbackDelivery(player, task, deliveryId);
            return;
        }
        if (!markDeliverySent(player.getUUID(), deliveryId)) {
            rollbackDelivery(player, task, deliveryId);
            return;
        }
        sender.sendCompressedChunk(player, compressed);
        NetworkStats.recordChunkSent(VanillaZlibEstimator.estimate(chunkData));
        if (shouldRecordSessionPush(shouldPushFull(player, task.pos(), task.dimension()), task.contentHash())) {
            rememberSessionPush(player.getUUID(), task.dimension(), task.pos(), task.contentHash());
        }
        // [LIGHT-DATA] 观测锚点：每 512 块打印一次出站光照实测（校准 ESTIMATED_LIGHT_BYTES=16KB；
        // MixinLightDataWrite 按包实测线格式字节——含握手前原版直发真实 light 与剥光空包，
        // 均值 ≈ 每块 light 实际线格式字节；与 lightStrip=false 对照可量化剥光收益）
        if (NetworkStats.isEnabled()
                && (NetworkStats.getMetrics().getChunksCompressed() & 511) == 0) {
            long lightBytes = NetworkStats.getLightDataBytesWritten();
            long lightChunks = NetworkStats.getLightDataWriteCount();
            Constants.LOG.info(
                    "[LIGHT-DATA] 实测 outbound light: {} bytes / {} chunks = {}/chunk（估算 {}）",
                    lightBytes, lightChunks,
                    lightChunks == 0 ? 0 : lightBytes / lightChunks,
                    NetworkStats.ESTIMATED_LIGHT_BYTES);
        }
        DebugLogger.info(LogType.NETWORK, "[PROCESS_QUEUE] Sent chunk {} to player {} ({} -> {} bytes, ratio={})",
                task.pos(), player.getName().getString(),
                chunkData.length, compressed.compressedData.length,
                String.format("%.2f", (double) chunkData.length / compressed.compressedData.length));
    }

    private boolean markDeliverySent(UUID playerId, long deliveryId) {
        ChunkAdmissionController controller = admissionControllers.get(playerId);
        return controller != null && controller.markSent(deliveryId, System.nanoTime());
    }

    /** Removes the exact reservation and schedules a fresh admission only while the player still tracks it. */
    private synchronized void rollbackDelivery(ServerPlayer player, DataRequestTask task, long deliveryId) {
        UUID playerId = player.getUUID();
        ChunkAdmissionController controller = admissionControllers.get(playerId);
        if (controller == null || !controller.release(admissionKey(task), deliveryId)) {
            return;
        }
        Map<Long, DataRequestTask> tasks = inFlightTasks.get(playerId);
        if (tasks != null) {
            tasks.remove(deliveryId, task);
        }
        if (player.isAlive() && !player.hasDisconnected() && isStillTracking(player, task)) {
            offerPendingTask(player, task);
        }
    }

    /**
     * 拦截路径统一剥光：原版 trackChunk/broadcast 传入的 packet 带真实 light，
     * 此处按配置在主线程重建空 mask 剥光包，保证 Hassium 客户端全链路
     * （剥光 + 限流 + hash 元数据）一致生效。重建失败或 lightStrip 关闭时回退原包。
     */
    private ClientboundLevelChunkWithLightPacket stripLightIfConfigured(
            ServerPlayer player, ChunkPos pos, ClientboundLevelChunkWithLightPacket packet) {
        if (!HassiumConfigService.getInstance().isServerLightStrip()) {
            return packet;
        }
        // 剥光协商：仅客户端声明可算光（握手 lightComputeSupported = hassiumEngineEnabled）
        // 才剥——客户端没装 MOD / 关闭引擎时服务端不剥，光随包自带（否则客户端黑块）。
        if (!isPlayerLightComputeSupported(player.getUUID())) {
            return packet;
        }
        try {
            ServerLevel level = PlayerCompat.getServerLevel(player);
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
            if (chunk == null) {
                return packet;
            }
            if (chunk.getPos().x != pos.x || chunk.getPos().z != pos.z) {
                Constants.LOG.warn("[LIGHT-STRIP] CHUNK POS MISMATCH pos=[{}, {}] chunk=[{}, {}]",
                        pos.x, pos.z, chunk.getPos().x, chunk.getPos().z);
            }            ClientboundLevelChunkWithLightPacket stripped = buildChunkPacket(chunk, level);
            return stripped != null ? stripped : packet;
        } catch (Exception e) {
            Constants.LOG.warn("[LIGHT-STRIP] Failed to rebuild stripped chunk packet at {}", pos, e);
            return packet;
        }
    }

    /**
     * 按原版构造路径构建区块包。必须在拥有 LevelChunk 的调用线程执行；packet 构造完成后
     * 已持有 section/light 的序列化快照，可安全地在 pushPool 编码、压缩并发送。
     */
    private ClientboundLevelChunkWithLightPacket buildChunkPacket(LevelChunk chunk, ServerLevel level) {
        try {
            boolean stripLight = HassiumConfigService.getInstance().isServerLightStrip();
            java.util.BitSet lightMask = stripLight ? new java.util.BitSet() : null;
            return new ClientboundLevelChunkWithLightPacket(
                    chunk, level.getLightEngine(), lightMask, lightMask);
        } catch (Exception e) {
            Constants.LOG.error("Hassium: Failed to build chunk packet {}", chunk.getPos(), e);
            return null;
        }
    }

    /**
     * 将区块包编码为线格式字节（RegistryAccess 服务端启动后只读，任意线程编码安全）。
     */
    @SuppressWarnings("deprecation") // NeoForge 1.21.11+: RegistryFriendlyByteBuf(2-param) deprecated; 3-param 需 ConnectionType.OTHER(仅 NeoForge)
    private byte[] encodeChunkPacket(ClientboundLevelChunkWithLightPacket chunkPacket,
                                     RegistryAccess registryAccess) {
#if MC_VER < MC_1_20_5
        io.netty.buffer.ByteBuf tempBuf = io.netty.buffer.Unpooled.buffer();
        try {
            FriendlyByteBuf friendlyBuf = new FriendlyByteBuf(tempBuf);
            chunkPacket.write(friendlyBuf);
            byte[] data = new byte[tempBuf.readableBytes()];
            tempBuf.getBytes(0, data);
            return data;
        } catch (Exception e) {
            Constants.LOG.error("Hassium: Failed to encode chunk packet", e);
            return null;
        } finally {
            tempBuf.release();
        }
#else
        net.minecraft.network.RegistryFriendlyByteBuf buf =
                new net.minecraft.network.RegistryFriendlyByteBuf(
                        io.netty.buffer.Unpooled.buffer(), registryAccess);
        try {
            ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(buf, chunkPacket);
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            return data;
        } catch (Exception e) {
            Constants.LOG.error("Hassium: Failed to encode chunk packet", e);
            return null;
        } finally {
            buf.release();
        }
#endif
    }


    private void putPreparedChunkPacket(UUID playerId, ChunkPos pos, byte[] data) {
        putPreparedChunkPacket(playerId, pos, new PreparedChunk(data, null));
    }

    /**
     * 拦截路径：同步缓存已构建的 packet（主线程零 encode），消费方（drain）后台 encode。
     */
    private void putPreparedChunkPacket(UUID playerId, ChunkPos pos,
                                        ClientboundLevelChunkWithLightPacket packet) {
        putPreparedChunkPacket(playerId, pos, new PreparedChunk(null, packet));
    }

    private void putPreparedChunkPacket(UUID playerId, ChunkPos pos, PreparedChunk prepared) {
        ConcurrentHashMap<Long, PreparedChunk> map =
                preparedChunkPackets.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
        map.put(ChunkPos.asLong(pos.x, pos.z), prepared);
        if (map.size() > MAX_PREPARED_PER_PLAYER) {
            int toRemove = map.size() - MAX_PREPARED_PER_PLAYER;
            var it = map.keySet().iterator();
            while (toRemove-- > 0 && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }

    private PreparedChunk takePreparedChunkPacket(UUID playerId, ChunkPos pos) {
        ConcurrentHashMap<Long, PreparedChunk> map = preparedChunkPackets.get(playerId);
        if (map == null) {
            return null;
        }
        return map.remove(ChunkPos.asLong(pos.x, pos.z));
    }

    /**
     * Releases one exact tracked chunk delivery without scanning the player's queue or in-flight map.
     * Version-specific tracking hooks can call this when their mapped untrack callback is available.
     */
    public void releasePlayerChunkDelivery(UUID playerId, String dimension, ChunkPos pos) {
        if (playerId == null || dimension == null || pos == null) {
            return;
        }
        long posLong = ChunkPos.asLong(pos.x, pos.z);
        Set<Long> pending = pendingSends.get(playerId);
        if (pending != null) {
            pending.remove(posLong);
        }
        Map<Long, DeferredTask> deferred = deferredChunks.get(playerId);
        if (deferred != null) {
            deferred.remove(posLong);
        }
        FifoChunkQueue queue = dataQueues.get(playerId);
        if (queue != null) {
            queue.removeIf(task -> dimension.equals(task.dimension())
                    && task.pos().x == pos.x && task.pos().z == pos.z);
        }
        ChunkAdmissionController controller = admissionControllers.get(playerId);
        if (controller == null) {
            return;
        }
        ChunkAdmissionController.ChunkDeliveryKey key =
                new ChunkAdmissionController.ChunkDeliveryKey(dimension, pos.x, pos.z);
        long deliveryId = controller.releaseDeliveryId(key);
        if (deliveryId != 0L) {
            Map<Long, DataRequestTask> tasks = inFlightTasks.get(playerId);
            if (tasks != null) {
                tasks.remove(deliveryId);
            }
            return;
        }
        controller.release(key);
    }

    /**
     * 移除玩家的所有队列（含 bloom 层——玩家断开后旧 bloom 必须失效：
     * 否则 R2 重连 trackChunk 会用 R1 残留的空 bloom 误判 miss → 全量直推，
     * bloom 分流退化为无缓存形态。清空后 R2 上报前走"未就绪只发 hash"，
     * 由影子端读盘比对决定本地回传/请求，语义正确）。
     */
    public void removePlayer(UUID playerId) {
        FifoChunkQueue queue = dataQueues.remove(playerId);
        if (queue != null) {
            queue.clear();
        }
        processingFlags.remove(playerId);
        ChunkAdmissionController controller = admissionControllers.remove(playerId);
        if (controller != null) {
            controller.clear();
        }
        inFlightTasks.remove(playerId);
        hashBatches.remove(playerId);
        preparedChunkPackets.remove(playerId);
        pendingResync.remove(playerId);
        pendingSends.remove(playerId);
        pendingSendDimension.remove(playerId);
        pendingFullSends.remove(playerId);
        pendingFullDimension.remove(playerId);
        deferredChunks.remove(playerId);
        initialPlayerChunkPos.remove(playerId);
        bloomLayers.remove(playerId);
        sessionPushedHashes.remove(playerId);
        resumePlayers.remove(playerId);
        playerStateReports.remove(playerId);
        // review-fix: T3-52：能力表随玩家清理（防 per-player 表无界增长）
        playerSeedGenSupported.remove(playerId);
        playerLightComputeSupported.remove(playerId);
        seedGenDisabledPlayers.remove(playerId);
        seedGenFallbackCounts.remove(playerId);
    }

    /**
     * 清空所有队列并关闭线程池
     */
    public void shutdown() {
        dataQueues.clear();
        processingFlags.clear();
        admissionControllers.values().forEach(ChunkAdmissionController::clear);
        admissionControllers.clear();
        inFlightTasks.clear();
        hashBatches.clear();
        preparedChunkPackets.clear();
        pendingResync.clear();
        pendingSends.clear();
        pendingSendDimension.clear();
        deferredChunks.clear();
        initialPlayerChunkPos.clear();
        resumePlayers.clear();
        playerStateReports.clear();
        sessionPushedHashes.clear();
        // review-fix: T3-52：能力表一并清理
        playerSeedGenSupported.clear();
        playerLightComputeSupported.clear();
        seedGenDisabledPlayers.clear();
        seedGenFallbackCounts.clear();
        if (pushPool != null) {
            pushPool.shutdownNow();
        }
        initialized.set(false);
    }

    /**
     * 获取统计信息
     */
    public String getStats() {
        int totalQueues = dataQueues.size();
        int totalPending = dataQueues.values().stream()
                .mapToInt(FifoChunkQueue::size)
                .sum();
        int poolSize = pushPool != null ? pushPool.getPoolSize() : 0;
        int activeThreads = pushPool != null ? pushPool.getActiveCount() : 0;
        return String.format("Queues: %d, Pending: %d, Threads: %d/%d",
                totalQueues, totalPending, activeThreads, poolSize);
    }

    /** 区块数据请求任务。 */
    private record DataRequestTask(ChunkPos pos, String dimension, SeedRefWork seedRef, long contentHash) {
        DataRequestTask(ChunkPos pos, String dimension, SeedRefWork seedRef) {
            this(pos, dimension, seedRef, 0L);
        }
    }

    /**
     * 按入队顺序衔接 admission。同 key 再入队只更新任务、不改变位置。
     */
    static final class FifoChunkQueue {
        private final LinkedHashMap<ChunkAdmissionController.ChunkDeliveryKey, DataRequestTask> entries =
                new LinkedHashMap<>();

        int size() {
            return entries.size();
        }

        boolean isEmpty() {
            return entries.isEmpty();
        }

        void clear() {
            entries.clear();
        }

        DataRequestTask peek() {
            var it = entries.entrySet().iterator();
            return it.hasNext() ? it.next().getValue() : null;
        }

        DataRequestTask poll() {
            var it = entries.entrySet().iterator();
            if (!it.hasNext()) {
                return null;
            }
            DataRequestTask task = it.next().getValue();
            it.remove();
            return task;
        }

        void offer(ChunkAdmissionController.ChunkDeliveryKey key, DataRequestTask task) {
            entries.put(key, task);
        }

        void removeIf(java.util.function.Predicate<DataRequestTask> predicate) {
            entries.entrySet().removeIf(e -> predicate.test(e.getValue()));
        }
    }

    private static final class SeedRefWork {
        private final long chunkHash;
        private final long[] sectionHashes;

        SeedRefWork(long chunkHash, long[] sectionHashes) {
            this.chunkHash = chunkHash;
            this.sectionHashes = sectionHashes != null ? sectionHashes.clone() : new long[0];
        }

        long chunkHash() {
            return chunkHash;
        }

        long[] sectionHashes() {
            return sectionHashes.clone();
        }
    }

    /** 主线程拷贝 + 客户端请求条目，交给 pushPool 规划/序列化。 */
    private record SectionDeltaWork(SectionDeltaColumnSnap snap, SectionHashRequestC2SPacket.Entry entry) {}

    /** 已脱离 live world 的柱数据；后台可自由读。 */
    private record SectionDeltaColumnSnap(
            LevelChunkSection[] sections,
            List<SectionDeltaS2CPacket.HeightmapData> heightmaps,
            List<SectionDeltaS2CPacket.BlockEntityData> blockEntities) {}
    /**
     * 工作项携带已构建 packet 或已编码字节；二者均不再读取世界对象，后台 encode 安全。
     * registryAccess 在服务端启动后只读。
     */
    private record SerializedChunkWork(ServerPlayer player, DataRequestTask task, long deliveryId,
                                       byte[] chunkData, ClientboundLevelChunkWithLightPacket packet,
                                       RegistryAccess registryAccess) {}
    /**
     * 短窗口 ChunkHash 批次
     */
    private static final class PendingHashBatch {
        final String dimension;
        final List<ChunkHashS2CPacket.Entry> entries = new ArrayList<>();
        final long createdAtMs = System.currentTimeMillis();

        PendingHashBatch(String dimension) {
            this.dimension = dimension;
        }
    }
}