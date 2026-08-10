package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil;

import io.github.limuqy.mc.hassium.concurrent.ChunkDistancePriority;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import io.github.limuqy.mc.hassium.metrics.VanillaZlibEstimator;
import io.github.limuqy.mc.hassium.platform.Services;
import io.github.limuqy.mc.hassium.compat.PlayerCompat;
import io.github.limuqy.mc.hassium.compat.RegistryCompat;
import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
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
import java.util.List;
import java.util.Map;
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

    /**
     * 握手后 resync 分批补发：每 tick 最多处理的区块数。
     * 避免一次性提交数百个 submitMetadataTaskFromChunk 卡住主线程，
     * 且减缓客户端 ChunkDataRequest 风暴导致 readyQueue 堆积。
     */
    private static final int RESYNC_PER_TICK = 32;

    /**
     * 每玩家区块数据请求队列（{@link io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue}：
     * 同区块重复请求 REPLACE 取代 + 消费时按当前玩家位置重算优先级）
     */
    private final Map<UUID, io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue<DataRequestTask>> dataQueues = new ConcurrentHashMap<>();

    /**
     * 每玩家是否正在本 tick 序列化（防重复 drain）
     */
    private final Map<UUID, AtomicBoolean> processingFlags = new ConcurrentHashMap<>();

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
     * 每玩家 SeedGen 能力（握手 C2S 上报 seedGenSupported；默认 false）。
     */
    private final Map<UUID, Boolean> playerSeedGenSupported = new ConcurrentHashMap<>();

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
     * 每玩家出界待命任务：drain 时已出视距的任务不静默丢弃（原 bug 根因），
     * 转入待命，玩家折返/静止后重新在视距内时恢复入队；超时（10s）才真丢弃。
     */
    private final Map<UUID, Map<Long, DeferredTask>> deferredChunks = new ConcurrentHashMap<>();

    /** 待命任务（含原始 priority 供重入队参考，实际重入队时按当前位置重算） */
    private record DeferredTask(ChunkPos pos, String dimension, long deferredAtMs) {}

    /** 待命检查周期（毫秒） */
    private static final long DEFER_CHECK_INTERVAL_MS = 1000L;
    /** 待命任务最大等待（毫秒），超时真丢弃（玩家不再回来） */
    private static final long DEFER_MAX_WAIT_MS = 10_000L;
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

    /**
     * 上次调整线程池的时间戳
     */
    private volatile long lastAdjustmentTime = 0;

    /**
     * 线程池调整间隔（毫秒）
     */
    private static final long ADJUSTMENT_INTERVAL_MS = 5000;

    /**
     * 队列长度阈值（用于动态调整）
     */
    private static final int QUEUE_HIGH_THRESHOLD = 50;
    private static final int QUEUE_LOW_THRESHOLD = 10;

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
            HassiumConfigService configService = HassiumConfigService.getInstance();
            int initialThreads = configService.getServerChunkPushThreads();
            int minThreads = configService.getMinPushThreads();
            int maxThreads = configService.getMaxPushThreads();

            if (initialThreads <= 0) {
                initialThreads = 2;
            }
            initialThreads = Math.max(minThreads, Math.min(maxThreads, initialThreads));

            pushPool = new ThreadPoolExecutor(
                    initialThreads,
                    maxThreads,
                    60L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(),
                    r -> {
                        Thread t = new Thread(r, "Hassium-ChunkPush");
                        t.setDaemon(true);
                        return t;
                    }
            );
            pushPool.allowCoreThreadTimeOut(true);

            Constants.LOG.info("Hassium: ServerChunkPushManager initialized with {} threads (min={}, max={})",
                    initialThreads, minThreads, maxThreads);
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
    public void submitMetadataTaskFromChunk(ServerPlayer player, ChunkPos pos,
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
            return;
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
     * 统一 hash 发送入口：per-player 分流。
     * <p>
     * SeedGen 玩家（能力 + 配置 + pristine）→ 只发 SeedRef（本地生成，零区块数据流量）；
     * 其余玩家 → Bloom 分流（客户端握手上报影子端存档布隆位图）：
     * <ul>
     *   <li>bloom 就绪且 miss（确定无缓存）→ hash 直发 + 数据直推（enqueueDirectPush）；</li>
     *   <li>bloom hit（可能有缓存）或 bloom 未就绪 → 只发 hash——由客户端影子端
     *       读盘比对（内存/存档 hash 表）决定：命中本地回传、未命中请求数据
     *       （ChunkDataRequestC2S → enqueueDataRequest 推送）。</li>
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
                sendSeedRef(player, pos, chunkHash, sectionHashes);
                continue;
            }
            sendChunkHashDirect(player, pos, chunkHash, sectionBitmap, dimension);
            if (shouldPushFull(player, pos, dimension)) {
                // bloom miss（确定无缓存）→ 数据直推；影子端无需再请求。
                enqueueDirectPush(player, dimension, List.of(pos));
            }
        }
    }

    /**
     * SeedRef 直发（SeedGen 玩家）：替代 chunkHash+区块数据，几十字节引用。
     */
    private void sendSeedRef(ServerPlayer player, ChunkPos pos, long chunkHash, long[] sectionHashes) {
        SeedRefS2CPacket packet = new SeedRefS2CPacket(pos.x, pos.z, chunkHash,
                sectionHashes != null ? sectionHashes : new long[0]);
        FriendlyByteBuf buf = null;
        boolean sent = false;
        try {
            buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            packet.encode(buf);
            int bytes = buf.readableBytes();
            Services.NETWORK_MANAGER.sendSeedRef(player, buf);
            sent = true;
            NetworkStats.recordMetadataSent(bytes);
            Constants.LOG.info("[SEED_REF] Sent ({}, {}) hash={} bytes={} to {}", pos.x, pos.z,
                    Long.toHexString(chunkHash), bytes, player.getName().getString());
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
     * Bloom 分流判定：有 Bloom 且 miss（确定无缓存）→ 直推；否则发 hash（hit 或 Bloom 未就绪）。
     */
    private boolean shouldPushFull(ServerPlayer player, ChunkPos pos, String dimension) {
        PlayerBloomLayers layers = bloomLayers.get(player.getUUID());
        if (layers == null || layers.isEmpty()) {
            return false;
        }
        return !layers.mightContain(pos.x, pos.z, dimension);
    }

    /** 每玩家 bloom 层（full 重置 / 增量追加；查询任一命中即可能缓存）。 */
    private static final class PlayerBloomLayers {
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
     * 握手成功后补发玩家当前视距内已加载区块的 chunkHash。
     * <p>
     * 初始 {@code trackChunk}/{@code sendChunk} 往往发生在握手完成之前，
     * 彼时 {@link PlayerCompressionTracker#isCompressionEnabled} 为 false，
     * 拦截器放行原版包且不推 hash，导致客户端统计全 0、缓存主链路永不启动。
     * 必须在主线程调用（读世界区块）。
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
        // 握手上报位置优先（failover/重连时服务端玩家对象位置滞后于客户端真实位置）；消费后移除
        ChunkPos reportedPos = initialPlayerChunkPos.remove(player.getUUID());
        int centerX = reportedPos != null ? reportedPos.x : player.chunkPosition().x;
        int centerZ = reportedPos != null ? reportedPos.z : player.chunkPosition().z;
        // T7 续流：验票通过后走同一 resync 机制 —— 按上报位置重发视距 hash，
        // 客户端与本地 ShadowStorageHashes 比对后只请求增量（hash 连续性）。
        if (isPlayerResumeActive(player.getUUID())) {
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

        // 按距玩家欧氏距离升序入队，使每 tick RESYNC_PER_TICK 优先补发近处 hash
        // （原 dx/dz 扫掠会让远离出生点的边缘块先进入客户端请求流）
        List<ResyncEntry> entries = new ArrayList<>((radius * 2 + 1) * (radius * 2 + 1));
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (!isServerChunkInRange(centerX + dx, centerZ + dz, centerX, centerZ, viewDistance)) {
                    continue;
                }
                entries.add(new ResyncEntry(new ChunkPos(centerX + dx, centerZ + dz), dimension));
            }
        }
        // 距离升序；同距（同环）时按角度扫，且每环起点旋转 45°（ring*π/4），
        // 避免 stable sort 保留 dx/dz 扫掠序 → 负方向（西/南）恒早于正方向（东/北）2-3s。
        entries.sort(Comparator.comparingDouble((ResyncEntry e) ->
                        ChunkDistancePriority.distSq(e.pos(), centerX, centerZ))
                .thenComparingDouble(e -> {
                    ChunkPos p = e.pos();
                    double dx = p.x - (double) centerX;
                    double dz = p.z - (double) centerZ;
                    double ring = Math.round(Math.sqrt(dx * dx + dz * dz));
                    double angle = Math.atan2(dz, dx) + ring * (Math.PI / 4.0);
                    return angle - 2 * Math.PI * Math.floor(angle / (2 * Math.PI));
                }));
        Deque<ResyncEntry> queue = new ArrayDeque<>(entries.size());
        queue.addAll(entries);
        if (!queue.isEmpty()) {
            pendingResync.put(player.getUUID(), queue);
            DebugLogger.info(LogType.CHUNK_APPLY,
                    "Hassium: Queued {} chunkHashes for resync (player={}, vd={}, perTick={})",
                    queue.size(), player.getName().getString(), viewDistance, RESYNC_PER_TICK);
        }
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
                while (!queue.isEmpty() && processed < RESYNC_PER_TICK) {
                    ResyncEntry entry = queue.poll();
                    // chunk 可能已被卸载；getChunkNow 返回 null 时跳过
                    LevelChunk chunk = level.getChunkSource().getChunkNow(entry.pos().x, entry.pos().z);
                    if (chunk == null) {
                        skipped++;
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

    /** 前向加权：前方每格优先 ~BIAS² 距离平方（BIAS=6 → 前方 8 格 ≈ 距离 4 格的优先级） */
    private static final double FORWARD_BIAS = 6.0;

    /** drain 时消费侧重算优先级的最大重插次数（防移动振荡导致任务永不被消费） */
    private static final int DRAIN_REPRIORITIZE_MAX_REINSERTS = 8;

    /**
     * 消费时按当前玩家位置 + 移动方向重算优先级（与 {@code enqueueInternal} 入队公式一致）。
     */
    private static double refreshPriority(long posLong, double playerChunkX, double playerChunkZ,
                                          double dirX, double dirZ, double forwardBias) {
        ChunkPos pos = new ChunkPos(ChunkPos.getX(posLong), ChunkPos.getZ(posLong));
        double priority = ChunkDistancePriority.distSq(pos, playerChunkX, playerChunkZ);
        if (dirX != 0.0) {
            double dx = pos.x - playerChunkX;
            double dz = pos.z - playerChunkZ;
            double dot = dx * dirX + dz * dirZ;
            if (dot > 0.0) {
                priority -= forwardBias * dot;
            }
        }
        return priority;
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
        if (!initialized.get() && dataQueues.isEmpty() && hashBatches.isEmpty() && pendingResync.isEmpty()) {
            return;
        }
        ensureInitialized();

        long now = System.currentTimeMillis();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            flushPlayerHashBatchIfDue(player, now);
            drainPlayerQueueTick(player);
        }

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
        adjustThreadPool();
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
     * 分段增量退化阈值：变更 section 占总 section 的百分比达到此值时回退全量请求。
     * <p>
     * 当 chunk 大部分 section 变更时，分段增量的 per-section 框架开销
     * (sectionIndex VarInt + dataLen VarInt) 会使响应比全量包更大。
     * 此阈值兜底防止「分段增量不如原版」的退化场景。
     * <p>
     * 75% 选择依据：分段增量额外开销 ≈ 变更数 × 3 字节(框架) + BE 列表；
     * 全量开销 ≈ 全部 sections + heightmaps + light。当变更占比 ≥ 75% 时，
     * 分段增量数据量已接近全量，加上框架开销后大概率超过全量。
     */
    private static final int SECTION_DELTA_FALLBACK_THRESHOLD_PCT = 75;

    /**
     * 处理客户端的 section 哈希请求（阶段二）。
     * <p>
     * 全版本主线程处理：1.21.2+（PalettedContainer ThreadingDetector）比对/序列化必须在主线程；
     * 1.20.x / 1.21.1 的 ThreadingDetector 为信号量版（tryAcquire 失败阻塞、持有方 checkAndUnlock
     * 抛 ReportedException），后台读 chunk 与主线程 tick 写并发即崩服（drain 路径 5bf3c6b 已实测
     * 回退，阶段二同理由）——<1.21.2 同样全主线程执行，正确性优先。
     * 每次请求都回包：可服务的进 {@code entries}，超距/失败的进 {@code skipped}（客户端回退全量）。
     */
    public void handleSectionHashRequest(ServerPlayer player, SectionHashRequestC2SPacket request) {
        if (!player.isAlive() || player.hasDisconnected()) { return; }
#if MC_VER < MC_1_21_2
        ensureInitialized();

        ServerLevel level = PlayerCompat.getServerLevel(player);
        int maxDist = PlayerCompat.getViewDistance(player) + SECTION_DELTA_VIEW_MARGIN;
        ChunkPos playerChunkPos = player.chunkPosition();
        List<SectionDeltaS2CPacket.SkippedChunk> skipped = new ArrayList<>();
        List<DeltaWork> works = new ArrayList<>();

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

            LevelChunk chunk = level.getChunk(entry.chunkX(), entry.chunkZ());
            if (chunk == null) {
                skipped.add(new SectionDeltaS2CPacket.SkippedChunk(entry.chunkX(), entry.chunkZ()));
                continue;
            }

            works.add(new DeltaWork(chunk, entry.sectionHashes(), entry.chunkX(), entry.chunkZ()));
        }

        // 主线程直接执行：ThreadingDetector 信号量版（<1.21.2）后台读 chunk 与主线程 tick 写并发即崩服
        processSectionDelta(player, request.dimension(), works, skipped);
#else
        ServerLevel level = PlayerCompat.getServerLevel(player);
        int maxDist = PlayerCompat.getViewDistance(player) + SECTION_DELTA_VIEW_MARGIN;
        ChunkPos playerChunkPos = player.chunkPosition();
        List<SectionDeltaS2CPacket.DeltaEntry> deltas = new ArrayList<>();
        List<SectionDeltaS2CPacket.SkippedChunk> skipped = new ArrayList<>();

        for (var entry : request.entries()) {
            try {
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

                LevelChunk chunk = level.getChunk(entry.chunkX(), entry.chunkZ());
                if (chunk == null) {
                    skipped.add(new SectionDeltaS2CPacket.SkippedChunk(entry.chunkX(), entry.chunkZ()));
                    continue;
                }

                // 计算当前 section 哈希（不含 blockEntity；空气 section 不在 map 中，视为 0）
                Map<Integer, Long> currentHashes = ChunkContentHashUtil.computeSectionHashes(chunk);
                long[] clientHashes = entry.sectionHashes();

                // 按完整索引比对：避免「服务端变空气」时漏发清除（非空 map 扫不到该 idx）
                List<SectionDeltaS2CPacket.SectionData> changedSections = new ArrayList<>();
                int sectionCount = chunk.getSectionsCount();
                for (int idx = 0; idx < sectionCount; idx++) {
                    long serverHash = currentHashes.getOrDefault(idx, 0L);
                    long clientHash = idx < clientHashes.length ? clientHashes[idx] : 0L;
                    if (serverHash != clientHash) {
                        byte[] data = serializeSection(chunk, idx);
                        changedSections.add(new SectionDeltaS2CPacket.SectionData(idx, data));
                    }
                }

                // 退化保护：变更 section 占非空 section 的占比达阈值时回退全量，避免分段增量比全量包更大。
                // 用非空 section 数（currentHashes.size()）而非总 section 数做分母：
                // 空 section 不进 delta，用总数会稀释占比，导致该回退时不回退。
                int nonEmptyCount = currentHashes.size();
                if (nonEmptyCount > 0 && !changedSections.isEmpty()
                        && changedSections.size() * 100 / nonEmptyCount >= SECTION_DELTA_FALLBACK_THRESHOLD_PCT) {
                    DebugLogger.info(LogType.NETWORK,
                            "[SECTION_DELTA] Fallback to full for [{}, {}]: {}/{} non-empty sections changed (>= {}%)",
                            entry.chunkX(), entry.chunkZ(), changedSections.size(), nonEmptyCount,
                            SECTION_DELTA_FALLBACK_THRESHOLD_PCT);
                    skipped.add(new SectionDeltaS2CPacket.SkippedChunk(entry.chunkX(), entry.chunkZ()));
                    continue;
                }

                // 收集 blockEntity 数据：仅在有变更 section 时发送。
                // 若 changedSections 为空（section hash 全部匹配），客户端缓存 BE 仍有效，无需重发。
                List<SectionDeltaS2CPacket.BlockEntityData> blockEntities = changedSections.isEmpty()
                        ? List.of()
                        : collectBlockEntities(chunk);

                deltas.add(new SectionDeltaS2CPacket.DeltaEntry(
                        entry.chunkX(), entry.chunkZ(), changedSections,
                        collectHeightmaps(chunk), blockEntities));
            } catch (Exception e) {
                Constants.LOG.error("[SECTION_DELTA] Failed to process chunk [{}, {}]",
                        entry.chunkX(), entry.chunkZ(), e);
                skipped.add(new SectionDeltaS2CPacket.SkippedChunk(entry.chunkX(), entry.chunkZ()));
            }
        }

        sendSectionDeltaResponse(player, request.dimension(), deltas, skipped);
#endif
    }

#if MC_VER < MC_1_21_2
    /**
     * 主线程执行 section 比对 + 序列化 + 回包（单请求单回包语义，顺序按请求顺序保持）。
     */
    private void processSectionDelta(ServerPlayer player, String dimension,
                                     List<DeltaWork> works,
                                     List<SectionDeltaS2CPacket.SkippedChunk> skipped) {
        if (player.hasDisconnected()) {
            return; // 断线无需回包
        }
        List<SectionDeltaS2CPacket.DeltaEntry> deltas = new ArrayList<>();
        for (DeltaWork w : works) {
            try {
                // 计算当前 section 哈希（不含 blockEntity；空气 section 不在 map 中，视为 0）
                Map<Integer, Long> currentHashes = ChunkContentHashUtil.computeSectionHashes(w.chunk());
                long[] clientHashes = w.clientHashes();

                // 按完整索引比对：避免「服务端变空气」时漏发清除（非空 map 扫不到该 idx）
                List<SectionDeltaS2CPacket.SectionData> changedSections = new ArrayList<>();
                int sectionCount = w.chunk().getSectionsCount();
                for (int idx = 0; idx < sectionCount; idx++) {
                    long serverHash = currentHashes.getOrDefault(idx, 0L);
                    long clientHash = idx < clientHashes.length ? clientHashes[idx] : 0L;
                    if (serverHash != clientHash) {
                        byte[] data = serializeSection(w.chunk(), idx);
                        changedSections.add(new SectionDeltaS2CPacket.SectionData(idx, data));
                    }
                }

                // 退化保护：变更 section 占非空 section 的占比达阈值时回退全量，避免分段增量比全量包更大。
                // 用非空 section 数（currentHashes.size()）而非总 section 数做分母：
                // 空 section 不进 delta，用总数会稀释占比，导致该回退时不回退。
                int nonEmptyCount = currentHashes.size();
                if (nonEmptyCount > 0 && !changedSections.isEmpty()
                        && changedSections.size() * 100 / nonEmptyCount >= SECTION_DELTA_FALLBACK_THRESHOLD_PCT) {
                    DebugLogger.info(LogType.NETWORK,
                            "[SECTION_DELTA] Fallback to full for [{}, {}]: {}/{} non-empty sections changed (>= {}%)",
                            w.x(), w.z(), changedSections.size(), nonEmptyCount,
                            SECTION_DELTA_FALLBACK_THRESHOLD_PCT);
                    skipped.add(new SectionDeltaS2CPacket.SkippedChunk(w.x(), w.z()));
                    continue;
                }

                // 收集 blockEntity 数据：仅在有变更 section 时发送。
                // 若 changedSections 为空（section hash 全部匹配），客户端缓存 BE 仍有效，无需重发。
                List<SectionDeltaS2CPacket.BlockEntityData> blockEntities = changedSections.isEmpty()
                        ? List.of()
                        : collectBlockEntities(w.chunk());

                deltas.add(new SectionDeltaS2CPacket.DeltaEntry(
                        w.x(), w.z(), changedSections, collectHeightmaps(w.chunk()), blockEntities));
            } catch (Exception e) {
                Constants.LOG.error("[SECTION_DELTA] Failed to process chunk [{}, {}]",
                        w.x(), w.z(), e);
                skipped.add(new SectionDeltaS2CPacket.SkippedChunk(w.x(), w.z()));
            }
        }

        sendSectionDeltaResponse(player, dimension, deltas, skipped);
    }

    /** 阶段二后台工作项：chunk 引用 + 客户端 section 哈希快照 */
    private record DeltaWork(LevelChunk chunk, long[] clientHashes, int x, int z) {}
#endif

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
     * 低频路径，且 BE 收集需遍历 chunk BE map（1.21.2+ 跨线程读 chunk 结构被 ThreadingDetector 拦截），
     * 保持主线程处理。
     */
    @SuppressWarnings("deprecation") // Forge: BuiltInRegistries 字段在 Forge patched jar 中被标记 @Deprecated
    public void handleBlockEntityRequest(ServerPlayer player, BlockEntityRequestC2SPacket request) {
        if (!player.isAlive() || player.hasDisconnected()) { return; }

        ServerLevel level = PlayerCompat.getServerLevel(player);
        int viewDistance = PlayerCompat.getViewDistance(player);
        ChunkPos playerChunkPos = player.chunkPosition();
        List<BlockEntityDataS2CPacket.ChunkBlockEntities> entries = new ArrayList<>();

        for (ChunkPos pos : request.chunks()) {
            try {
                // 校验区块是否在玩家视距范围内
                int dx = Math.abs(pos.x - playerChunkPos.x);
                int dz = Math.abs(pos.z - playerChunkPos.z);
                if (dx > viewDistance || dz > viewDistance) { continue; }

                LevelChunk chunk = level.getChunk(pos.x, pos.z);
                if (chunk == null) { continue; }

                List<BlockEntityDataS2CPacket.BlockEntityData> blockEntities = new ArrayList<>();
                for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                    BlockPos bePos = entry.getKey();
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
                    type = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
                    if (type != null) {
                        blockEntities.add(new BlockEntityDataS2CPacket.BlockEntityData(bePos, type, nbt));
                    }
                }

                entries.add(new BlockEntityDataS2CPacket.ChunkBlockEntities(pos.x, pos.z, blockEntities));
            } catch (Exception e) {
                Constants.LOG.error("[BLOCK_ENTITY] Failed to collect block entities for chunk {}", pos, e);
            }
        }

        if (!entries.isEmpty()) {
            FriendlyByteBuf buf = null;
            boolean sent = false;
            try {
                BlockEntityDataS2CPacket packet = new BlockEntityDataS2CPacket(
                        request.dimension(), entries);
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
        }
    }

    /**
     * 序列化单个 section 的方块数据
     */
    private byte[] serializeSection(LevelChunk chunk, int sectionIndex) {
        LevelChunkSection section = chunk.getSection(sectionIndex);
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
            result.add(new SectionDeltaS2CPacket.HeightmapData(entry.getKey().ordinal(), raw));
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

    /**
     * 将区块数据请求入队
     *
     * @param player    请求的玩家
     * @param dimension 维度
     * @param chunks    请求的区块列表
     */
    public void enqueueDataRequest(ServerPlayer player, String dimension, List<ChunkPos> chunks) {
        // 记录收到数据请求
        NetworkStats.recordDataRequestReceived();

        DebugLogger.info(LogType.NETWORK, "[ENQUEUE_DATA] Player {} requested {} chunks (dimension={})",
                player.getName().getString(), chunks.size(), dimension);

        enqueueInternal(player, dimension, chunks);
    }

    /**
     * Bloom miss 主动直推入队（服务端驱动，不计入客户端请求统计）。
     * 与客户端请求共用同一队列/去重/出界待命语义。
     */
    public void enqueueDirectPush(ServerPlayer player, String dimension, List<ChunkPos> chunks) {
        DebugLogger.info(LogType.NETWORK, "[ENQUEUE_DATA] Direct push {} chunks to player {} (dimension={})",
                chunks.size(), player.getName().getString(), dimension);

        enqueueInternal(player, dimension, chunks);
    }

    private void enqueueInternal(ServerPlayer player, String dimension, List<ChunkPos> chunks) {
        ensureInitialized();

        // 检查玩家是否仍然在线
        if (!player.isAlive() || player.hasDisconnected()) {
            Constants.LOG.warn("[ENQUEUE_DATA] Player {} is not online, ignoring data request",
                    player.getName().getString());
            return;
        }

        UUID playerId = player.getUUID();
        io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue<DataRequestTask> queue = dataQueues.computeIfAbsent(
                playerId, k -> new io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue<>(100));

        double playerChunkX = player.getX() / 16.0;
        double playerChunkZ = player.getZ() / 16.0;

        // 移动方向加权：纯 distSq 升序（近先推）会把飞行方向前方的块（距离最远）
        // 永远排到队尾——飞行时前方出现锥形虚空（~30° 扇区），转弯后新前方继续滞后
        // （实测：前方块迟推数秒、已加载块在视距圆移动时正常卸载 → 视觉跳变）。
        // 按玩家速度方向把前方块提前：priority = distSq - BIAS * dot(pos-player, dir)。
        // 仅对前方（dot>0）加权，后方不加权不惩罚；速度阈值防原地抖动/转视角误判。
        net.minecraft.world.phys.Vec3 vel = player.getDeltaMovement();
        double dirX = 0.0;
        double dirZ = 0.0;
        double velLenSq = vel.x * vel.x + vel.z * vel.z;
        if (velLenSq > 0.01) {
            double len = Math.sqrt(velLenSq);
            dirX = vel.x / len;
            dirZ = vel.z / len;
        }

        List<DataRequestTask> tasks = new ArrayList<>(chunks.size());
        for (ChunkPos pos : chunks) {
            // 入队瞬间冻结 distSq（层内排序键；无 renderOnly 层）
            double priority = ChunkDistancePriority.distSq(pos, playerChunkX, playerChunkZ);
            if (dirX != 0.0) {
                double dx = pos.x - playerChunkX;
                double dz = pos.z - playerChunkZ;
                double dot = dx * dirX + dz * dirZ;
                if (dot > 0.0) {
                    priority -= FORWARD_BIAS * dot;
                }
            }
            tasks.add(new DataRequestTask(pos, dimension, priority));
        }

        // 去重/取代：同区块已在队时用新任务（最新优先级）替换旧任务——
        // 直推 + 客户端请求同块、多路径并发时仍只推送一次，且玩家折返时的
        // 重复请求能刷新冻结的优先级键（原 queuedChunkKeys 仅丢弃新请求）。
        int replaced = 0;
        for (DataRequestTask task : tasks) {
            io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue.OfferResult result = queue.offer(
                    task,
                    new io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue.Key(
                            ChunkPos.asLong(task.pos().x, task.pos().z), 0),
                    task.priority(),
                    io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue.OfferPolicy.REPLACE);
            if (result == io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue.OfferResult.REPLACED) {
                replaced++;
            }
        }

        DebugLogger.info(LogType.NETWORK, "[ENQUEUE_DATA] Player {} queued {} chunks (queueSize={}, replaced={}, playerPos=({}, {}))",
                player.getName().getString(), tasks.size(), queue.size(), replaced, playerChunkX, playerChunkZ);
        // 实际 drain 由 onServerTick 按真实每 tick 上限处理，避免连环 submit 卡主线程
    }

    /**
     * 每 tick 构建最多 maxChunksPerTick 个区块包快照，并将编码、压缩、限速与发送下推 pushPool。
     * <p>
     * 任何版本都不能让 pushPool 读取 {@link LevelChunk}：1.20.1 已证实其
     * {@code PalettedContainer} 会与服务端主线程并发访问并抛出 ThreadingDetector 异常。
     */
    private void drainPlayerQueueTick(ServerPlayer player) {
        UUID playerId = player.getUUID();
        io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue<DataRequestTask> queue = dataQueues.get(playerId);
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

            ServerLevel level = PlayerCompat.getServerLevel(player);
            // 本 tick 玩家锚点与视距（服务端 tick 内位置不变）：drain 前快照，供出界丢弃判定
            ChunkPos playerChunk = player.chunkPosition();
            int serverVD = PlayerCompat.getViewDistance(player);
            List<SerializedChunkWork> works = new ArrayList<>(maxPerTick);
            int processed = 0;

            // 消费时重算优先级：冻结键按本 tick 玩家位置 + 移动方向刷新（同 enqueueInternal
            // 的前向加权公式）。玩家排队期间移动 → 近处块不再被「入队时远、现在近」的旧键
            // 压在队尾；重插有界（防移动振荡导致任务永不被消费）。
            double playerChunkX = player.getX() / 16.0;
            double playerChunkZ = player.getZ() / 16.0;
            net.minecraft.world.phys.Vec3 vel = player.getDeltaMovement();
            double dirX = 0.0;
            double dirZ = 0.0;
            double velLenSq = vel.x * vel.x + vel.z * vel.z;
            if (velLenSq > 0.01) {
                double len = Math.sqrt(velLenSq);
                dirX = vel.x / len;
                dirZ = vel.z / len;
            }
            // lambda 需捕获 effectively-final 副本
            final double anchorX = playerChunkX;
            final double anchorZ = playerChunkZ;
            final double fwdX = dirX;
            final double fwdZ = dirZ;
            io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue.PriorityRefresher refresher =
                    (key, old) -> key == null ? old
                            : refreshPriority(key.posLong(), anchorX, anchorZ, fwdX, fwdZ, FORWARD_BIAS);

            // 所有版本主线程构建 packet 快照：1.20.1 的 ThreadingDetector 是信号量互斥，
            // 主线程 tick 写 chunk 与后台 build 并发即引爆（本次 01:09:04 [10,3] 崩溃实测），
            // 无合法后台读路径；packet 内部数据已脱离世界对象，encode/压缩/限速/发送在 pushPool。

            // 1.21.2+ 的 PalettedContainer ThreadingDetector 禁止跨线程读 chunk：
            // 主线程构建完整 packet 快照，encode/压缩/限速/发送在 pushPool。
            // <1.21.2 无检测：主线程只取 chunk 引用，build/encode 全后台（恢复 1.20.1 已验证的全后台路径）。
            // packet 的内部 chunk/light 数据已脱离世界对象，线格式 encode、压缩、限速与发送仍在 pushPool。
            while (processed < maxPerTick && !queue.isEmpty()) {
                if (!player.isAlive() || player.hasDisconnected()) {
                    removePlayer(playerId);
                    return;
                }

                io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue.Entry<DataRequestTask> entry =
                        queue.pollBest(refresher, DRAIN_REPRIORITIZE_MAX_REINSERTS);
                if (entry == null) {
                    break;
                }
                DataRequestTask task = entry.item();
                // 出队即释放 key 登记（允许客户端重试/直推重新入队；原 queuedChunkKeys.remove 语义）
                queue.release(entry);

                // 任务排队期间玩家可能已移出权威视距：不静默丢弃（客户端无重试 → 永久虚空 bug 根因），
                // 转入待命集合，玩家折返/静止后重新在视距内时恢复入队；超时（10s）才真丢弃。
                if (!isServerChunkInRange(task.pos().x, task.pos().z, playerChunk.x, playerChunk.z, serverVD)) {
                    Map<Long, DeferredTask> deferred = deferredChunks.computeIfAbsent(
                            playerId, k -> new ConcurrentHashMap<>());
                    deferred.putIfAbsent(ChunkPos.asLong(task.pos().x, task.pos().z),
                            new DeferredTask(task.pos(), task.dimension(), System.currentTimeMillis()));
                    DebugLogger.info(LogType.NETWORK,
                            "[PROCESS_QUEUE] Deferring chunk {} (out of range, vd={}) — retry when back in range",
                            task.pos(), serverVD);
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
                            LevelChunk chunk = level.getChunk(task.pos().x, task.pos().z);
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

                    works.add(new SerializedChunkWork(player, task.pos(), chunkData,
                            packet, level.registryAccess()));
                    processed++;
                    DebugLogger.info(LogType.NETWORK, "[PROCESS_QUEUE] Built chunk packet {} (remaining={})",
                            task.pos(), queue.size());
                } catch (Exception e) {
                    Constants.LOG.error("[PROCESS_QUEUE] Failed to prepare chunk {} for player {}",
                            task.pos(), player.getName().getString(), e);
                }
            }

            if (!works.isEmpty()) {
                DebugLogger.info(LogType.NETWORK, "[PROCESS_QUEUE] Tick drain for {}: prepared={}, remaining={}",
                        player.getName().getString(), works.size(), queue.size());
                for (SerializedChunkWork work : works) {
                    pushPool.submit(() -> {
                        byte[] chunkData = work.chunkData();
                        if (chunkData == null) {
                            long tEnc = System.nanoTime();
                            chunkData = encodeChunkPacket(work.packet(), work.registryAccess());
                            diag(D_ENCODE, System.nanoTime() - tEnc);
                        }
                        if (chunkData == null) {
                            Constants.LOG.warn("[PROCESS_QUEUE] Failed to encode chunk {}", work.pos());
                            return;
                        }
                        long tSend = System.nanoTime();
                        compressAndSend(work.player(), work.pos(), chunkData, sender);
                        diag(D_SEND, System.nanoTime() - tSend);
                    });
                }
            }
        } finally {
            flag.set(false);
        }
    }

    /**
     * 后台压缩并发送（不访问世界对象）。
     */
    private void compressAndSend(ServerPlayer player, ChunkPos pos, byte[] chunkData, ChunkSender sender) {
        if (!player.isAlive() || player.hasDisconnected()) {
            return;
        }
        try {
            ChunkCompressionHandler.CompressedChunkData compressed =
                    ChunkCompressionHandler.compressChunkData(chunkData, pos.x, pos.z);
            if (compressed == null) {
                Constants.LOG.warn("[PROCESS_QUEUE] Failed to compress chunk {}", pos);
                return;
            }
            sendCompressed(player, pos, chunkData, compressed, sender);
        } catch (Exception e) {
            Constants.LOG.error("[PROCESS_QUEUE] Failed to compress/send chunk {} for player {}",
                    pos, player.getName().getString(), e);
        }
    }

    /**
     * 发送：速率由 maxChunksPerTick（每 tick 提交上限）× tick 节奏决定，不做秒级限速——
     * 掉刻时每 tick 提交量不变、每秒总量自然下降，即保护主线程。无令牌桶后
     * 无需延迟重提交，压缩完成后直接发送（全在 pushPool，不占主线程）。
     */
    private void sendCompressed(ServerPlayer player, ChunkPos pos, byte[] chunkData,
                                ChunkCompressionHandler.CompressedChunkData compressed, ChunkSender sender) {
        // 压缩完成与发送之间玩家可能断开：发送前校验
        if (!player.isAlive() || player.hasDisconnected()) {
            return;
        }
        sender.sendCompressedChunk(player, compressed);
        NetworkStats.recordChunkSent(VanillaZlibEstimator.estimate(chunkData));
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
                pos, player.getName().getString(),
                chunkData.length, compressed.compressedData.length,
                String.format("%.2f", (double) chunkData.length / compressed.compressedData.length));
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
            LevelChunk chunk = level.getChunk(pos.x, pos.z);
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
     * 动态调整线程池大小
     */
    private void adjustThreadPool() {
        if (!HassiumConfigService.getInstance().isDynamicThreadPoolEnabled()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastAdjustmentTime < ADJUSTMENT_INTERVAL_MS) {
            return;
        }
        lastAdjustmentTime = now;

        int totalPending = dataQueues.values().stream()
                .mapToInt(io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue::size)
                .sum();

        int coreThreads = pushPool.getCorePoolSize();
        int maxThreads = pushPool.getMaximumPoolSize();
        int configuredMax = HassiumConfigService.getInstance().getMaxPushThreads();
        int minThreads = HassiumConfigService.getInstance().getMinPushThreads();
        int activeThreads = pushPool.getActiveCount();

        // 队列堆积：抬高 maximum（须先保证 max >= core）
        if (totalPending > QUEUE_HIGH_THRESHOLD && maxThreads < configuredMax) {
            int newMax = Math.min(maxThreads + 2, configuredMax);
            pushPool.setMaximumPoolSize(newMax);
            Constants.LOG.debug("Hassium: Thread pool max expanded to {} (queueSize={}, active={})",
                    newMax, totalPending, activeThreads);
        }
        // 队列空闲：先降 core 再降 max，避免 max < core 抛 IllegalArgumentException
        else if (totalPending < QUEUE_LOW_THRESHOLD && coreThreads > minThreads && activeThreads < coreThreads) {
            int newCore = Math.max(coreThreads - 1, minThreads);
            pushPool.setCorePoolSize(newCore);
            if (pushPool.getMaximumPoolSize() < newCore) {
                pushPool.setMaximumPoolSize(newCore);
            }
            Constants.LOG.debug("Hassium: Thread pool core shrunk to {} (queueSize={}, active={})",
                    newCore, totalPending, activeThreads);
        }
    }

    /**
     * 移除玩家的所有队列（含 bloom 层——玩家断开后旧 bloom 必须失效：
     * 否则 R2 重连 trackChunk 会用 R1 残留的空 bloom 误判 miss → 全量直推，
     * bloom 分流退化为无缓存形态。清空后 R2 上报前走"未就绪只发 hash"，
     * 由影子端读盘比对决定本地回传/请求，语义正确）。
     */
    public void removePlayer(UUID playerId) {
        io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue<DataRequestTask> queue = dataQueues.remove(playerId);
        if (queue != null) {
            queue.clear();
        }
        processingFlags.remove(playerId);
        hashBatches.remove(playerId);
        preparedChunkPackets.remove(playerId);
        pendingResync.remove(playerId);
        deferredChunks.remove(playerId);
        initialPlayerChunkPos.remove(playerId);
        bloomLayers.remove(playerId);
        resumePlayers.remove(playerId);
        playerStateReports.remove(playerId);
    }

    /**
     * 清空所有队列并关闭线程池
     */
    public void shutdown() {
        dataQueues.clear();
        processingFlags.clear();
        hashBatches.clear();
        preparedChunkPackets.clear();
        pendingResync.clear();
        deferredChunks.clear();
        initialPlayerChunkPos.clear();
        resumePlayers.clear();
        playerStateReports.clear();
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
                .mapToInt(io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue::size)
                .sum();
        int poolSize = pushPool != null ? pushPool.getPoolSize() : 0;
        int activeThreads = pushPool != null ? pushPool.getActiveCount() : 0;
        return String.format("Queues: %d, Pending: %d, Threads: %d/%d",
                totalQueues, totalPending, activeThreads, poolSize);
    }

    /**
     * 区块数据请求任务
     */
    private record DataRequestTask(ChunkPos pos, String dimension, double priority) {}

    /**
     * 工作项携带已构建 packet 或已编码字节；二者均不再读取世界对象，后台 encode 安全。
     * registryAccess 在服务端启动后只读。
     */
    private record SerializedChunkWork(ServerPlayer player, ChunkPos pos,
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