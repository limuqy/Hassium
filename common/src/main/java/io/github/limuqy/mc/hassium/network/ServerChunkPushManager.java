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
import io.github.limuqy.mc.hassium.compat.LevelCompat;
import io.github.limuqy.mc.hassium.utils.TickMonitor;
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
import java.util.HashMap;
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
    /** 每玩家已封装且仍在通道中排队的批次上限；满则跳过本 tick 封批。 */
    static final int MAX_QUEUED_BATCHES_PER_PLAYER = 10;
    /** 待确认（已发 ChunkHashS2C 等客户端回执）超时：超时绕过批次队列异步直发剥光全量。 */
    private static final long PENDING_CONFIRM_TIMEOUT_MS = 5_000L;
    /** peekPrioritized 队头数据优先扫描窗口：跳过 SeedRef 元数据找 full 任务的有限深度。 */
    private static final int PRIORITY_SCAN_BOUND = 64;


    /**
     * 握手后 resync 分批补发：每 tick 最多处理的区块数。
     * 避免一次性提交数百个 submitMetadataTaskFromChunk 卡住主线程，
     * 且减缓客户端 ChunkDataRequest 风暴导致 readyQueue 堆积。
     */
    private static final int RESYNC_PER_TICK = 32;

    /**
     * Bloom hit 只发 hash：不占 full chunk 批队列配额。
     * 与 resync 同量级，避免 R2 有缓存时仍按 maxChunksPerTick=5 滴灌导致空窗。
     */
    static final int HASH_SENDS_PER_TICK = 32;

    /**
     * 每玩家推送队列：per-player FIFO 批次队列 + 每 tick 封批（≤maxChunksPerTick）。
     * 主线程 buildChunkPacket 快照在封批前完成，encode/hash/ZSTD 在消费线程。
     */
    private final Map<UUID, PlayerPushQueue> pushQueues = new ConcurrentHashMap<>();

    /** 已发送 hash 等待客户端回执的柱：playerId → (packedPos → 待确认条目)。 */
    private final Map<UUID, Map<Long, PendingConfirm>> pendingConfirms = new ConcurrentHashMap<>();

    /** 待确认条目：记录维度与发送时间戳，供每 tick 超时扫描。 */
    private record PendingConfirm(String dimension, long sentAtMs) {}

    /**
     * 批次通道：主线程封批后投递，serverChunkPushThreads 条常驻消费者共享抢批。
     * 每个 {@link SealedBatch} 已在所属玩家队列中占用一个排队批次名额。
     */
    private final java.util.concurrent.LinkedBlockingQueue<SealedBatch> batchChannel =
            new java.util.concurrent.LinkedBlockingQueue<>();

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
     * {@link #enqueueDirectPush}；任务队列满则留到下 tick，禁止当成功丢掉。
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
    private record PreparedChunk(byte[] data, ClientboundLevelChunkWithLightPacket packet, long contentHash) {}

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
            // 全局计算池：核数硬编码（encode/hash/ZSTD），与配置解耦
            int threads = Runtime.getRuntime().availableProcessors();
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

            // 常驻消费者线程：serverChunkPushThreads 条共享抢批（LinkedBlockingQueue 批次通道）
            int consumers = HassiumConfigService.getInstance().getServerChunkPushThreads();
            for (int i = 0; i < consumers; i++) {
                Thread t = new Thread(this::consumeBatchesLoop, "Hassium-PushConsumer-" + i);
                t.setDaemon(true);
                t.start();
            }

            Constants.LOG.info("Hassium: ServerChunkPushManager initialized with {} compute threads, {} consumer threads",
                    threads, consumers);
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
                // Hash 以规范化 section 内容为准；1.20.1 的 packet 线格式含不稳定 palette
                // 排列，绝不能作为缓存协议 hash。
                long tHash = System.nanoTime();
                Map<Integer, Long> sectionHashes = ChunkContentHashUtil.computeSectionHashesFromPacket(
                        strippedPacket.getChunkData(), sectionCount, registryAccess);
                diag(D_HASH, System.nanoTime() - tHash);
                long chunkHash = ChunkContentHashUtil.combineSectionHashes(sectionHashes);
                long[] sectionHashArray = ChunkContentHashUtil.sectionHashesToArray(sectionHashes);
                byte[] encoded = encodeChunkPacket(strippedPacket, registryAccess);
                if (encoded != null) {
                    // 预编码字节后续可能在 Bloom 命中路径直接发送；保留同一 packet 的语义 hash。
                    for (ServerPlayer player : players) {
                        if (!isSeedGenFor(player.getUUID(), pos, dimension)) {
                            putPreparedChunkPacket(player.getUUID(), pos,
                                    new PreparedChunk(encoded, strippedPacket, chunkHash));
                        }
                    }
                }
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
                registerPendingConfirm(player, pos, dimension);
                sendChunkHashDirect(player, pos, chunkHash, sectionBitmap, dimension);
                continue;
            }
            Long lastSent = lastSessionPushedHash(player.getUUID(), dimension, pos);
            if (shouldReuseSessionPush(inBloom, lastSent, chunkHash)) {
                registerPendingConfirm(player, pos, dimension);
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

    /** 发送 SeedRef 元数据（SeedGen 玩家本地生成，零区块数据流量；不需确认标识）。 */
    private void sendSeedRef(ServerPlayer player, DataRequestTask task) {
        SeedRefWork seedRef = task.seedRef();
        SeedRefS2CPacket packet = new SeedRefS2CPacket(task.pos().x, task.pos().z, seedRef.chunkHash(),
                seedRef.sectionHashes());
        FriendlyByteBuf buf = null;
        boolean sent = false;
        try {
            buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            packet.encode(buf);
            int bytes = buf.readableBytes();
            Services.NETWORK_MANAGER.sendSeedRef(player, buf);
            sent = true;
            NetworkStats.recordMetadataSent(bytes);
            Constants.LOG.info("[SEED_REF] Sent ({}, {}) hash={} bytes={} to {}",
                    task.pos().x, task.pos().z, Long.toHexString(seedRef.chunkHash()), bytes,
                    player.getName().getString());
        } catch (Exception e) {
            Constants.LOG.error("[SEED_REF] Failed to send SeedRef to player {}",
                    player.getName().getString(), e);
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
                layers.reset(packet.dimension(), filter);
                DebugLogger.info(LogType.NETWORK,
                        "[BLOOM_SYNC] Full bloom from {} (dimension={}, {} bytes) — resync unblocked",
                        player.getName().getString(), packet.dimension(), packet.bloomBytes().length);
            } else {
                layers.append(packet.dimension(), filter);
                DebugLogger.info(LogType.NETWORK,
                        "[BLOOM_SYNC] Incremental bloom from {} (dimension={}, {} bytes)",
                        player.getName().getString(), packet.dimension(), packet.bloomBytes().length);
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
     * 未就绪（{@code layers == null} 或该维度无层）或空层：不直推，走 hash，避免 Bloom 尚未上报时
     * R2 被当 ROUND1。已收到该维度 Bloom 后 miss 才直推（再由会话表决定是否复用）。
     */
    static boolean shouldPushFull(PlayerBloomLayers layers, int chunkX, int chunkZ, String dimension) {
        if (!isBloomReady(layers, dimension)) {
            return false;
        }
        return !layers.mightContain(chunkX, chunkZ, dimension);
    }

    /** 已收到至少一层该维度 Bloom。空过滤器（ROUND1 无缓存）也算就绪。 */
    static boolean isBloomReady(PlayerBloomLayers layers, String dimension) {
        return layers != null && !layers.isEmpty(dimension);
    }

    /**
     * 每玩家 bloom 层，按维度分桶（full 重置该维度 / 增量追加；查询同维度任一层命中即可能缓存）。
     * T2-fabric-r1 no-hash 回归修复：维度必须参与分桶——三维度各发一帧 full 时，
     * 若共用一个层列表，后到的空 nether/end 帧会把 overworld 层清掉，
     * R2 查询恒 miss → 整视距被误判 ROUND1 直推且不带 hash（cacheHitFullChunkCount=0）。
     */
    static final class PlayerBloomLayers {
        private final Map<String, List<io.github.limuqy.mc.hassium.cache.client.ChunkBloomFilter>> byDimension =
                new HashMap<>();

        void reset(String dimension, io.github.limuqy.mc.hassium.cache.client.ChunkBloomFilter filter) {
            synchronized (byDimension) {
                List<io.github.limuqy.mc.hassium.cache.client.ChunkBloomFilter> list = new ArrayList<>();
                list.add(filter);
                byDimension.put(dimension, list);
            }
        }

        void append(String dimension, io.github.limuqy.mc.hassium.cache.client.ChunkBloomFilter filter) {
            synchronized (byDimension) {
                List<io.github.limuqy.mc.hassium.cache.client.ChunkBloomFilter> list =
                        byDimension.computeIfAbsent(dimension, ignored -> new ArrayList<>());
                if (list.size() >= BLOOM_MAX_LAYERS) {
                    list.remove(0);
                }
                list.add(filter);
            }
        }

        boolean isEmpty(String dimension) {
            synchronized (byDimension) {
                List<io.github.limuqy.mc.hassium.cache.client.ChunkBloomFilter> list = byDimension.get(dimension);
                return list == null || list.isEmpty();
            }
        }

        boolean mightContain(int chunkX, int chunkZ, String dimension) {
            synchronized (byDimension) {
                List<io.github.limuqy.mc.hassium.cache.client.ChunkBloomFilter> list = byDimension.get(dimension);
                if (list == null) {
                    return false;
                }
                for (io.github.limuqy.mc.hassium.cache.client.ChunkBloomFilter layer : list) {
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
        String dimension = LevelCompat.getDimensionId(level);

        // 不再把整盘 hash 灌进 pendingResync（曾 2s dump 上千条，绕开原版定额）。
        // 1.20.1：只把已加载柱登记进 pendingSends，由 tick drain 出队。
        // 1.21.2+：正常路径依赖 vanilla PlayerChunkSender.sendChunk 拦截驱动 hash/push；
        // 但 warm-world 重连时该触发偶发不火（1.21.8_neoforge_fin R2 实证：pending=0、
        // gatewayS2c=4、带宽 204B、landed=4，而 1.21.7/9/10/11 同流程全绿）——vanilla
        // tracking 对已 FULL 的暖柱未重发。故兜底把视距内已加载柱也排进 pendingResync，
        // 由 drainPendingResync 按 RESYNC_PER_TICK 定额走 submitMetadataTaskFromChunk
        // （bloom 感知：hit→只发 hash；miss→hash+直推，会话表去重）。与 vanilla 触发并存无害：
        // 重复 hash 帧幂等，重复直推被 lastSessionPushedHash 去重。
#if MC_VER < MC_1_21_1
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
#else
        int marked = 0;
        Deque<ResyncEntry> queue =
                pendingResync.computeIfAbsent(player.getUUID(), ignored -> new java.util.ArrayDeque<>());
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
                ResyncEntry entry = new ResyncEntry(new ChunkPos(cx, cz), dimension);
                if (!queue.contains(entry)) {
                    queue.addLast(entry);
                    marked++;
                }
            }
        }
        if (marked > 0) {
            DebugLogger.info(LogType.CHUNK_APPLY,
                    "Hassium: Resync {} loaded chunks queued for paced metadata (player={}, vd={})",
                    marked, player.getName().getString(), viewDistance);
        }
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
     * 全量数据义务：C2S miss 或出界复活的柱。统一入 {@link PlayerPushQueue} 批次队列，
     * 由每 tick 封批（≤maxChunksPerTick）后交消费线程。
     */
    void markPendingFullSend(ServerPlayer player, ChunkPos pos, String dimension) {
        if (player == null || pos == null || dimension == null) {
            return;
        }
        enqueuePushTask(player, pos, dimension, PushKind.FULL);
    }

    /**
     * 每 tick 把 C2S/挤出 backlog 定额 {@link #enqueueDirectPush}；任务队列满则停下，
     * 绝不把未入队 key 当作已发送丢弃。
     */
    private void drainPendingFullSends(ServerPlayer player) {
        // 全量义务已改为直接入 PlayerPushQueue，无需 per-tick backlog 泵
    }

    /**
     * 每 tick 从 pending 取距玩家最近、且已加载的柱出队。
     * <p>
     * 先按 packed key 距离排序（不碰世界），再 {@code getChunkNow} 直到填满本 tick
     * 入队预算即停，禁止每 tick 扫满 VD 方阵。主线程不算 section hash：仅当 Bloom
     * 已到且 miss、且 {@code lastSessionPushedHash==null} 才直推剥光全量；
     * 未就绪 / hit / 会话复用走 hash 路径。所有柱统一入 {@link PlayerPushQueue}
     * 批次队列，由封批 + 消费线程处理。
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
        int maxPerTick = normalizeMaxChunksPerTick(
                HassiumConfigService.getInstance().getConfig().master().maxChunksPerTick());
        if (!PlayerCompressionTracker.isCompressionEnabled(player)) {
            drainVanillaPacedSends(player, pending, level, maxPerTick);
            return;
        }
        PlayerPushQueue queue = pushQueues.computeIfAbsent(playerId, ignored -> new PlayerPushQueue());
        ChunkPos center = player.chunkPosition();
        List<Long> ordered = new ArrayList<>(pending);
        sortPackedKeysByDistance(ordered, center.x, center.z);
        for (Long packed : ordered) {
            int x = ChunkPos.getX(packed);
            int z = ChunkPos.getZ(packed);
            if (queue.size() >= queueCapacity()) {
                // 排队满：本 tick 停止封批，剩余柱留待下 tick
                break;
            }
            LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
            if (chunk == null) {
                // 未加载：保留在 pendingSends，等 getChunkNow != null 再试
                continue;
            }
            // 主线程快照（buildChunkPacket）在入队前完成；encode/hash/ZSTD 在消费线程
            ClientboundLevelChunkWithLightPacket packet = buildChunkPacket(chunk, level);
            if (packet == null) {
                continue;
            }
            queue.enqueue(new PushTask(new ChunkPos(x, z), dimension,
                    new DataRequestTask(new ChunkPos(x, z), dimension, null, 0L),
                    PushKind.FULL));
            pending.remove(packed);
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
        if (!initialized.get() && pushQueues.isEmpty() && hashBatches.isEmpty()
                && pendingResync.isEmpty() && pendingSends.isEmpty()) {
            return;
        }
        ensureInitialized();

        long now = System.currentTimeMillis();
        long drainPendingNs = 0L;
        long drainQueueNs = 0L;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            flushPlayerHashBatchIfDue(player, now);
            long t0 = System.nanoTime();
            drainPendingSends(player);
            drainPendingNs += System.nanoTime() - t0;
            t0 = System.nanoTime();
            sealPlayerBatch(player);
            drainQueueNs += System.nanoTime() - t0;
        }
        TickMonitor.addHassiumDrainNs(drainPendingNs, drainQueueNs);

        // 待确认扫描：超时 >5s 绕过批次队列异步批量直发剥光全量并移除
        expirePendingConfirms(server, now);

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
        pushQueues.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        pendingConfirms.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
    }


    /** 登记 hash 待确认：记录发送时间戳，供每 tick 超时扫描。 */
    private void registerPendingConfirm(ServerPlayer player, ChunkPos pos, String dimension) {
        if (player == null || pos == null || dimension == null) {
            return;
        }
        pendingConfirms.computeIfAbsent(player.getUUID(), ignored -> new ConcurrentHashMap<>())
                .put(ChunkPos.asLong(pos.x, pos.z), new PendingConfirm(dimension, System.currentTimeMillis()));
    }

    /**
     * 每 tick 扫描待确认：超时 &gt;{@link #PENDING_CONFIRM_TIMEOUT_MS} 的柱绕过批次队列，
     * 异步批量直发剥光全量并移除（客户端可能没收到 hash 或比对失败）。
     */
    private void expirePendingConfirms(net.minecraft.server.MinecraftServer server, long nowMs) {
        if (pendingConfirms.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, Map<Long, PendingConfirm>> playerEntry : pendingConfirms.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerEntry.getKey());
            if (player == null || !player.isAlive() || player.hasDisconnected()) {
                continue;
            }
            List<ChunkPos> expired = new ArrayList<>();
            String dimension = null;
            for (Map.Entry<Long, PendingConfirm> e : playerEntry.getValue().entrySet()) {
                PendingConfirm pc = e.getValue();
                if (!isPendingConfirmExpired(pc.sentAtMs(), nowMs)) {
                    continue;
                }
                expired.add(new ChunkPos(ChunkPos.getX(e.getKey()), ChunkPos.getZ(e.getKey())));
                dimension = pc.dimension();
            }
            if (expired.isEmpty() || dimension == null) {
                continue;
            }
            for (ChunkPos pos : expired) {
                playerEntry.getValue().remove(ChunkPos.asLong(pos.x, pos.z));
            }
            DebugLogger.info(LogType.NETWORK,
                    "[PENDING_CONFIRM] {} confirms timed out (>{}ms), direct-pushing stripped full to {}",
                    expired.size(), Long.valueOf(PENDING_CONFIRM_TIMEOUT_MS), player.getName().getString());
            // 绕过批次队列：异步直发剥光全量
            String finalDimension = dimension;
            pushPool.execute(() -> directPushStrippedFull(player, finalDimension, expired));
        }
    }

    /**
     * 客户端 C2S 回执分流：
     * <ul>
     *   <li>result=hit（命中）→ 移除待确认，客户端本地回传/读盘，服务端无事可做；</li>
     *   <li>result=miss → 移除待确认 + 异步直推剥光全量。</li>
     * </ul>
     * 幂等：重复回执 / 未知柱安全无副作用。
     */
    public void handleChunkDataRequestResult(ServerPlayer player, String dimension,
                                             List<ChunkPos> chunks, int result) {
        if (player == null || chunks == null) {
            return;
        }
        UUID playerId = player.getUUID();
        Map<Long, PendingConfirm> confirms = pendingConfirms.get(playerId);
        if (result == ChunkDataRequestC2SPacket.RESULT_HIT && chunks.isEmpty()) {
            if (confirms != null) {
                confirms.entrySet().removeIf(entry -> shouldClearPendingConfirmOnEmptyHit(
                        result, dimension, entry.getValue().dimension()));
            }
            return;
        }
        if (chunks.isEmpty()) {
            return;
        }
        List<ChunkPos> misses = new ArrayList<>();
        for (ChunkPos pos : chunks) {
            long packed = ChunkPos.asLong(pos.x, pos.z);
            PendingConfirm removed = confirms != null ? confirms.remove(packed) : null;
            if (shouldPushFullOnConfirmResult(result, removed != null)) {
                // miss 且确属本管理器待确认的柱才直推；未知柱忽略（幂等）
                misses.add(pos);
            }
        }
        if (!misses.isEmpty()) {
            DebugLogger.info(LogType.NETWORK,
                    "[PENDING_CONFIRM] result=miss n={} from {}, direct-pushing stripped full",
                    misses.size(), player.getName().getString());
            ensureInitialized();
            pushPool.execute(() -> directPushStrippedFull(player, dimension, misses));
        }
    }

    /** pending-confirm 超过五秒后必须收敛为全量直推。 */
    static boolean isPendingConfirmExpired(long sentAtMs, long nowMs) {
        return nowMs - sentAtMs > PENDING_CONFIRM_TIMEOUT_MS;
    }

    /** 只有待确认的 C2S miss 才触发全量直推；hit 和未知项均为幂等收敛。 */
    static boolean shouldPushFullOnConfirmResult(int result, boolean wasPending) {
        return result == ChunkDataRequestC2SPacket.RESULT_MISS && wasPending;
    }

    /** 空列表 HIT 代表该维度没有缺失柱，必须收敛其全部待确认项。 */
    static boolean shouldClearPendingConfirmOnEmptyHit(int result, String responseDimension,
                                                        String pendingDimension) {
        return result == ChunkDataRequestC2SPacket.RESULT_HIT
                && java.util.Objects.equals(responseDimension, pendingDimension);
    }

    /**
     * 后台直发剥光全量：主线程快照已不可得（本方法跑在 pushPool），因此先在
     * 下个 tick 由封批路径补建？否——直发语义要求立即响应。这里只允许对
     * {@code preparedChunkPackets} 已缓存快照的柱发送；未缓存的柱转投批次队列。
     */
    private void directPushStrippedFull(ServerPlayer player, String dimension, List<ChunkPos> chunks) {
        PlayerPushQueue queue = pushQueues.computeIfAbsent(player.getUUID(), ignored -> new PlayerPushQueue());
        for (ChunkPos pos : chunks) {
            queue.enqueue(new PushTask(pos, dimension, null, PushKind.FULL));
        }
    }

    /**
     * 统一入队入口：所有推送义务（fullReq、bloom miss 直推、resync 补发、出界复活、
     * section delta 响应）都经此进入 per-player FIFO 批次队列。排队批满则拒绝。
     *
     * @return true 入队成功或同柱已有任务排队
     */
    boolean enqueuePushTask(ServerPlayer player, ChunkPos pos, String dimension, PushKind kind) {
        if (player == null || pos == null || dimension == null) {
            return false;
        }
        if (!player.isAlive() || player.hasDisconnected()) {
            return false;
        }
        PlayerPushQueue queue = pushQueues.computeIfAbsent(player.getUUID(), ignored -> new PlayerPushQueue());
        return queue.enqueue(new PushTask(pos, dimension, null, kind));
    }

    /** 剥光全量的内容 hash（消费线程调用；仅 encode 后字节）。 */
    private static long computeChunkHash(byte[] chunkData) {
        return io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil.xxHash64OfBytes(chunkData);
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
            // 自定义 chunk/delta 通道与 vanilla 初始区块包语义一致：只发送客户端
            // update NBT。saveWithoutMetadata 会携带 TrialSpawnerLogic 的服务器 worldgen
            // registry 引用；1.21.5+ 客户端并未同步 trial_spawner registry，load 即报错。
#if MC_VER < MC_1_21_1
            CompoundTag nbt = be.getUpdateTag();
#else
            CompoundTag nbt = be.getUpdateTag(be.getLevel().registryAccess());
#endif
            String type = String.valueOf(
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()));
            result.add(new SectionDeltaS2CPacket.BlockEntityData(pos, type, nbt));
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
     * 将区块数据请求入队（客户端 fullReq：result=miss → 要数据）。
     *
     * @param player    请求的玩家
     * @param dimension 维度
     * @param chunks    请求的区块列表
     */
    public void enqueueDataRequest(ServerPlayer player, String dimension, List<ChunkPos> chunks) {
        NetworkStats.recordDataRequestReceived();
        for (ChunkPos pos : chunks) {
            enqueuePushTask(player, pos, dimension, PushKind.FULL);
        }
    }

    /**
     * Bloom miss 主动直推入队（服务端驱动，不计入客户端请求统计）。
     * 统一入 {@link PlayerPushQueue} 批次队列。
     */
    public boolean enqueueDirectPush(ServerPlayer player, String dimension, List<ChunkPos> chunks) {
        return enqueueDirectPush(player, dimension, chunks, 0L);
    }

    boolean enqueueDirectPush(ServerPlayer player, String dimension, List<ChunkPos> chunks, long contentHash) {
        DebugLogger.info(LogType.NETWORK, "[ENQUEUE_DATA] Direct push {} chunks to player {} (dimension={})",
                chunks.size(), player.getName().getString(), dimension);
        boolean all = true;
        for (ChunkPos pos : chunks) {
            all &= enqueuePushTask(player, pos, dimension, PushKind.FULL);
        }
        return all;
    }

    /**
     * SeedGen 玩家的 SeedRef 元数据入队（经批次队列消费线程发送）。
     */
    private void enqueueSeedRef(ServerPlayer player, ChunkPos pos, String dimension,
                                long chunkHash, long[] sectionHashes) {
        if (!player.isAlive() || player.hasDisconnected()) {
            return;
        }
        PlayerPushQueue queue = pushQueues.computeIfAbsent(player.getUUID(), ignored -> new PlayerPushQueue());
        queue.enqueue(new PushTask(pos, dimension,
                new DataRequestTask(pos, dimension, new SeedRefWork(chunkHash, sectionHashes), 0L),
                PushKind.SEED_REF));
    }



    /**
     * 主线程封批：每玩家每 tick 取 ≤maxChunksPerTick 个任务快照成 1 批，投入批次通道。
     * <p>
     * 任何版本都不能让后台线程读 {@link LevelChunk}：其 {@code PalettedContainer}
     * 会与服务端主线程并发访问并抛出 ThreadingDetector 异常。因此 buildChunkPacket
     * 快照必须在封批前于本方法（主线程 tick 内）完成；encode/hash/ZSTD 在消费线程。
     */
    private void sealPlayerBatch(ServerPlayer player) {
        UUID playerId = player.getUUID();
        PlayerPushQueue queue = pushQueues.get(playerId);
        if (queue == null || queue.isEmpty() || !queue.tryReserveSealedBatch()) {
            return;
        }

        if (!player.isAlive() || player.hasDisconnected()) {
            removePlayer(playerId);
            return;
        }

        // 发送前最后一道闸：channel 不可写则释放本 tick 的封批名额，任务留到下 tick
        if (!isFullDeliveryChannelWritable(player)) {
            queue.releaseSealedBatchReservation();
            return;
        }

        ChunkSender sender = ChunkSender.getInstance();
        if (sender == null) {
            queue.releaseSealedBatchReservation();
            Constants.LOG.error("[PROCESS_QUEUE] ChunkSender not initialized, cannot send chunk data "
                    + "(loader must call ChunkSender.setInstance in mod init)");
            return;
        }

        int maxPerTick = normalizeMaxChunksPerTick(
                HassiumConfigService.getInstance().getConfig().master().maxChunksPerTick());

        ServerLevel level = PlayerCompat.getServerLevel(player);
        // 本 tick 玩家锚点与视距（服务端 tick 内位置不变）：封批前快照，供出界丢弃判定
        ChunkPos playerChunk = player.chunkPosition();
        int serverVD = PlayerCompat.getViewDistance(player);
        List<SealedWork> works = new ArrayList<>(maxPerTick);
        while (works.size() < maxPerTick && !queue.isEmpty()) {
            PushTask task = queue.poll();
            if (task == null) {
                break;
            }
            if (!player.isAlive() || player.hasDisconnected()) {
                queue.releaseSealedBatchReservation();
                removePlayer(playerId);
                return;
            }

            if (task.seedRef() != null) {
                // SeedRef 元数据无 hash 比对语义，直接随批发送。
                works.add(new SealedWork(player, task, null, level.registryAccess(), sender, 0L));
                continue;
            }

            // 任务排队期间玩家可能已移出权威视距：转入待命集合，折返后复活；超时才真丢弃
            if (!isServerChunkInRange(task.pos().x, task.pos().z, playerChunk.x, playerChunk.z, serverVD)) {
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

            try {
                // 主线程快照（buildChunkPacket）：优先用拦截时缓存的包字节/packet
                PreparedChunk prepared = takePreparedChunkPacket(playerId, task.pos());
                byte[] chunkData = prepared != null ? prepared.data() : null;
                ClientboundLevelChunkWithLightPacket packet = prepared != null ? prepared.packet() : null;
                long contentHash = prepared != null ? prepared.contentHash() : 0L;
                if (chunkData == null) {
                    if (packet == null) {
                        LevelChunk chunk = level.getChunkSource().getChunkNow(task.pos().x, task.pos().z);
                        if (chunk == null) {
                            Constants.LOG.warn("[PROCESS_QUEUE] Chunk {} not loaded, skipping", task.pos());
                            continue;
                        }
                        long tBuild = System.nanoTime();
                        packet = buildChunkPacket(chunk, level);
                        diag(D_BUILD, System.nanoTime() - tBuild);
                        if (packet == null) {
                            Constants.LOG.warn("[PROCESS_QUEUE] Failed to build chunk packet {}", task.pos());
                            continue;
                        }
                    }
                }
                if (contentHash == 0L && packet != null) {
                    contentHash = ChunkContentHashUtil.combineSectionHashes(
                            ChunkContentHashUtil.computeSectionHashesFromPacket(
                                    packet.getChunkData(), level.getSectionsCount(), level.registryAccess()));
                }
                works.add(new SealedWork(player, task, chunkData != null ? chunkData : packet,
                        level.registryAccess(), sender, contentHash));
            } catch (Exception e) {
                Constants.LOG.error("[PROCESS_QUEUE] Failed to prepare chunk {} for player {}",
                        task.pos(), player.getName().getString(), e);
            }
        }

        if (!works.isEmpty()) {
            DebugLogger.info(LogType.NETWORK, "[PROCESS_QUEUE] Tick sealed batch for {}: size={}, remaining={}",
                    player.getName().getString(), works.size(), queue.size());
            batchChannel.offer(new SealedBatch(queue, works));
        } else {
            queue.releaseSealedBatchReservation();
        }
    }

    /**
     * 常驻消费者循环：与其它消费者共享抢批（batchChannel 阻塞队列）。
     * 批内逐任务在 pushPool 上 encode/hash/ZSTD 后发送。
     */
    private void consumeBatchesLoop() {
        while (true) {
            SealedBatch sealed;
            try {
                sealed = batchChannel.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            sealed.owner().dequeueSealedBatch();
            try {
                processBatch(sealed.works());
            } catch (Throwable t) {
                Constants.LOG.error("Hassium: push consumer failed to process batch", t);
            }
        }
    }

    /** 消费一批：批>1 时 fan-out 全局池 invokeAll 同步等齐。 */
    private void processBatch(List<SealedWork> batch) {
        if (batch.isEmpty()) {
            return;
        }
        if (batch.size() == 1) {
            processOne(batch.get(0));
            return;
        }
        try {
            List<java.util.concurrent.Callable<Void>> callables = new ArrayList<>(batch.size());
            for (SealedWork work : batch) {
                callables.add(() -> {
                    processOne(work);
                    return null;
                });
            }
            pushPool.invokeAll(callables);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            for (SealedWork work : batch) {
                processOne(work);
            }
        }
    }

    /** 单任务消费（pushPool 线程）：先判定后计算 + SeedRef 直发。 */
    private void processOne(SealedWork work) {
        ServerPlayer player = work.player();
        PushTask task = work.task();
        if (!player.isAlive() || player.hasDisconnected()) {
            return;
        }
        if (task.seedRef() != null) {
            sendSeedRef(player, task.data());
            return;
        }
        try {
            byte[] chunkData;
            if (work.payload() instanceof byte[] bytes) {
                chunkData = bytes;
            } else {
                long tEnc = System.nanoTime();
                chunkData = encodeChunkPacket((ClientboundLevelChunkWithLightPacket) work.payload(),
                        work.registryAccess());
                diag(D_ENCODE, System.nanoTime() - tEnc);
            }
            if (chunkData == null) {
                Constants.LOG.warn("[PROCESS_QUEUE] Failed to encode chunk {}", task.pos());
                return;
            }

            // 先判定后计算：bloom miss 且 session 无记录 → 只 encode 直发剥光全量（不算 hash）
            boolean bloomMissNoRecord =
                    shouldPushFull(player, task.pos(), task.dimension())
                    && lastSessionPushedHash(player.getUUID(), task.dimension(), task.pos()) == null;
            if (!bloomMissNoRecord) {
                // Bloom-hit/R2 元数据必须使用 section 规范化 hash；raw packet bytes 在
                // 1.20.1 含不稳定 palette 排列，会令客户端磁盘 hash 永远失配。
                long hash = work.contentHash();
                if (hash == 0L) {
                    Constants.LOG.warn("[PROCESS_QUEUE] Missing semantic chunk hash for {}", task.pos());
                    return;
                }
                registerPendingConfirm(player, task.pos(), task.dimension());
                sendChunkHashDirect(player, task.pos(), hash, 0, task.dimension());
                return;
            }
            compressAndSend(player, task, chunkData, work.contentHash(), work.sender());
        } catch (Throwable t) {
            Constants.LOG.error("[PROCESS_QUEUE] Failed to encode/send chunk {}", task.pos(), t);
        }
    }

    /** 后台压缩并发送剥光全量（不访问世界对象）。 */
    private void compressAndSend(ServerPlayer player, PushTask task, byte[] chunkData, long contentHash,
                                 ChunkSender sender) {
        if (!player.isAlive() || player.hasDisconnected()) {
            return;
        }
        try {
            ChunkCompressionHandler.CompressedChunkData compressed =
                    ChunkCompressionHandler.compressChunkData(chunkData, task.pos().x, task.pos().z);
            if (compressed == null) {
                Constants.LOG.warn("[PROCESS_QUEUE] Failed to compress chunk {}", task.pos());
                return;
            }
            sender.sendCompressedChunk(player, compressed);
            NetworkStats.recordChunkSent(VanillaZlibEstimator.estimate(chunkData));
            rememberSessionPush(player.getUUID(), task.dimension(), task.pos(), contentHash);
            DebugLogger.info(LogType.NETWORK, "[PROCESS_QUEUE] Sent stripped full chunk {} to player {} ({} -> {} bytes)", task.pos(), player.getName().getString(),
                    chunkData.length, compressed.compressedData.length);
        } catch (Exception e) {
            Constants.LOG.error("[PROCESS_QUEUE] Failed to compress/send chunk {} for player {}",
                    task.pos(), player.getName().getString(), e);
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
#if MC_VER < MC_1_21_1
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
        putPreparedChunkPacket(playerId, pos, new PreparedChunk(data, null, 0L));
    }

    /**
     * 拦截路径：同步缓存已构建的 packet（主线程零 encode），消费方（drain）后台 encode。
     */
    private void putPreparedChunkPacket(UUID playerId, ChunkPos pos,
                                        ClientboundLevelChunkWithLightPacket packet) {
        putPreparedChunkPacket(playerId, pos, new PreparedChunk(null, packet, 0L));
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
     * 丢弃玩家已不再 tracking 的柱的未封批任务、待命项和旧 pending 标记。
     * Version-specific tracking hooks can call this when their mapped untrack callback is available.
     */
    public void discardUntrackedChunk(UUID playerId, String dimension, ChunkPos pos) {
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
        PlayerPushQueue queue = pushQueues.get(playerId);
        if (queue != null) {
            queue.removeIf(task -> dimension.equals(task.dimension())
                    && task.pos().x == pos.x && task.pos().z == pos.z);
        }
    }


    /**
     * 移除玩家的所有队列（含 bloom 层——玩家断开后旧 bloom 必须失效：
     * 否则 R2 重连 trackChunk 会用 R1 残留的空 bloom 误判 miss → 全量直推，
     * bloom 分流退化为无缓存形态。清空后 R2 上报前走"未就绪只发 hash"，
     * 由影子端读盘比对决定本地回传/请求，语义正确）。
     */
    public void removePlayer(UUID playerId) {
        PlayerPushQueue queue = pushQueues.remove(playerId);
        if (queue != null) {
            queue.clear();
        }
        pendingConfirms.remove(playerId);
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
        pushQueues.clear();
        pendingConfirms.clear();
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
        int totalQueues = pushQueues.size();
        int totalPending = pushQueues.values().stream()
                .mapToInt(PlayerPushQueue::size)
                .sum();
        int poolSize = pushPool != null ? pushPool.getPoolSize() : 0;
        int activeThreads = pushPool != null ? pushPool.getActiveCount() : 0;
        return String.format("Queues: %d, Pending: %d, Threads: %d/%d",
                totalQueues, totalPending, activeThreads, poolSize);
    }

    /** 区块数据请求任务。 */
    private record DataRequestTask(ChunkPos pos, String dimension, SeedRefWork seedRef,
                                   long contentHash) {
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

    /**
     * 工作项携带已构建 packet 或已编码字节；二者均不再读取世界对象，后台 encode 安全。
     * registryAccess 在服务端启动后只读。
     */
    private record SerializedChunkWork(ServerPlayer player, DataRequestTask task,
                                       byte[] chunkData, ClientboundLevelChunkWithLightPacket packet,
                                       RegistryAccess registryAccess) {}

    /** 批次队列任务：柱 + 维度 + SeedRef 元数据（可空）。 */
    private record PushTask(ChunkPos pos, String dimension, DataRequestTask data, PushKind kind) {
        SeedRefWork seedRef() {
            return data != null ? data.seedRef() : null;
        }

        public ChunkPos pos() {
            return pos;
        }

        public String dimension() {
            return dimension;
        }

        long contentHash() {
            return data != null ? data.contentHash() : 0L;
        }
    }

    /** 推送任务类型：全量数据 / 元数据快照 / SeedRef。 */
    enum PushKind { FULL, METADATA, SEED_REF }

    /**
     * 封批产物：主线程已完成世界快照（chunkData 或 packet），消费线程只做 encode/hash/ZSTD。
     */
    private record SealedWork(ServerPlayer player, PushTask task, Object payload,
                              RegistryAccess registryAccess, ChunkSender sender, long contentHash) {}

    /** 通道项及其所属玩家的已封装批次计数。 */
    private record SealedBatch(PlayerPushQueue owner, List<SealedWork> works) {}
    /** 主线程拷贝 + 客户端请求条目，交给 pushPool 规划/序列化。 */
    private record SectionDeltaWork(SectionDeltaColumnSnap snap, SectionHashRequestC2SPacket.Entry entry) {}

    /** 已脱离 live world 的柱数据；后台可自由读。 */
    private record SectionDeltaColumnSnap(
            LevelChunkSection[] sections,
            List<SectionDeltaS2CPacket.HeightmapData> heightmaps,
            List<SectionDeltaS2CPacket.BlockEntityData> blockEntities) {}

    /** Gateway 会话存在时，full 推送只在 writable 的 channel 上推进（发送前最后一道闸）。 */
    private static boolean isFullDeliveryChannelWritable(ServerPlayer player) {
        io.github.limuqy.mc.hassium.network.gateway.GatewayPlayerSession session =
                io.github.limuqy.mc.hassium.network.gateway.GatewayServer.getInstance()
                        .registry().get(player.getUUID());
        return session == null || session.channel().isWritable();
    }

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

    /**
     * 每玩家 FIFO 任务队列。已封装批次在 {@link #queuedBatches} 中单独计数，
     * 因而不会把尚未到 tick 封批时机的任务错误地当作已排队批次。
     */


    /** 配置异常时保留历史安全默认值；正常配置值即每 tick 单批任务上限。 */
    static int normalizeMaxChunksPerTick(int configured) {
        return configured > 0 ? configured : 4;
    }

    /** 未封批任务背压最多容纳十个满批，已封装批次另由 PlayerPushQueue 单独限额。 */
    private static int queueCapacity() {
        return MAX_QUEUED_BATCHES_PER_PLAYER * normalizeMaxChunksPerTick(
                HassiumConfigService.getInstance().getConfig().master().maxChunksPerTick());
    }

    static final class PlayerPushQueue {
        private final java.util.ArrayDeque<PushTask> tasks = new java.util.ArrayDeque<>();
        private int queuedBatches;

        synchronized int size() {
            return tasks.size();
        }

        synchronized int queuedBatchCount() {
            return queuedBatches;
        }

        synchronized boolean isEmpty() {
            return tasks.isEmpty();
        }

        /** 预留一个已封装批次名额；满时本 tick 不从任务队列取任何任务。 */
        synchronized boolean tryReserveSealedBatch() {
            if (queuedBatches >= MAX_QUEUED_BATCHES_PER_PLAYER) {
                return false;
            }
            queuedBatches++;
            return true;
        }

        /** 封批未产出任何可消费工作时归还预留名额。 */
        synchronized void releaseSealedBatchReservation() {
            if (queuedBatches > 0) {
                queuedBatches--;
            }
        }

        /** 常驻消费者从通道取到批次后释放其排队名额。 */
        synchronized void dequeueSealedBatch() {
            if (queuedBatches > 0) {
                queuedBatches--;
            }
        }

        /** 同柱已有任务排队则视为成功（幂等）；任务背压上限为 10 个满批。 */
        synchronized boolean enqueue(PushTask task) {
            for (PushTask existing : tasks) {
                if (existing.pos().equals(task.pos())
                        && existing.dimension().equals(task.dimension())) {
                    return true;
                }
            }
            if (tasks.size() >= queueCapacity()) {
                return false;
            }

            tasks.addLast(task);
            return true;
        }

        synchronized PushTask poll() {
            return tasks.pollFirst();
        }

        synchronized void clear() {
            tasks.clear();
            queuedBatches = 0;
        }

        synchronized void removeIf(java.util.function.Predicate<PushTask> predicate) {
            tasks.removeIf(predicate);
        }
    }
}
