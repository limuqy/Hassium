package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil;
import io.github.limuqy.mc.hassium.compat.LevelChunkSectionCompat;
import io.github.limuqy.mc.hassium.compat.ChunkPacketDataCompat;

import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import io.github.limuqy.mc.hassium.metrics.VanillaZlibEstimator;
import io.github.limuqy.mc.hassium.platform.Services;
import io.github.limuqy.mc.hassium.compat.PlayerCompat;
import io.github.limuqy.mc.hassium.compat.RegistryCompat;
import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.compat.LevelCompat;
import io.github.limuqy.mc.hassium.compat.ShadowChunkMapCompat;
import io.github.limuqy.mc.hassium.utils.TickMonitor;
import io.github.limuqy.mc.hassium.network.gateway.GatewayPlayerSession;
import io.github.limuqy.mc.hassium.network.gateway.GatewayServer;
import io.github.limuqy.mc.hassium.network.sectiondelta.SectionDeltaPlanner;
import io.github.limuqy.mc.hassium.network.sectiondelta.SectionDeltaSnapshot;
import io.github.limuqy.mc.hassium.network.sectiondelta.SectionPlaneSyndrome;
import io.github.limuqy.mc.hassium.utils.DebugLogger.LogType;
import io.github.limuqy.mc.hassium.mixin.LevelChunkWithLightPacketAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerChunkCache;
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

    /** 每玩家已封装且仍在通道中排队的批次上限；满则跳过本 tick 封批。 */
    static final int MAX_QUEUED_BATCHES_PER_PLAYER = 10;
    /** 已准入任务满时的全量数据溢出上限；覆盖单玩家最大可见区，避免静默丢柱。 */
    private static final int MAX_OVERFLOW_TASKS_PER_PLAYER = 8192;
    private static final long PENDING_CONFIRM_TIMEOUT_MS = 60_000L;
    private static final int SECTION_DELTA_VIEW_MARGIN = 1;

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


    /**
     * 批次通道：主线程封批后投递，serverChunkPushThreads 条常驻消费者共享抢批。
     * 每个 {@link SealedBatch} 已在所属玩家队列中占用一个排队批次名额。
     */
    private final java.util.concurrent.LinkedBlockingQueue<SealedBatch> batchChannel =
            new java.util.concurrent.LinkedBlockingQueue<>();

    /**
     * 每玩家待发送的 chunkHash 批次


    /**


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

    /** 在服务端主线程构建单区块 pull 终态；不读取客户端提交的 payload。 */
    public ShadowPullResponseS2CPacket.Result resolveShadowPull(ServerPlayer player,
                                                                  ShadowPullRequestC2SPacket.Entry entry,
                                                                  String dimension) {
        if (player == null || entry == null) {
            return null;
        }
        ServerLevel level = PlayerCompat.getServerLevel(player);
        if (level == null || !LevelCompat.getDimensionId(level).equals(dimension)) {
            return ShadowPullResponseS2CPacket.Result.error(entry.chunkX(), entry.chunkZ(), "dimension");
        }
        ChunkPos pos = new ChunkPos(entry.chunkX(), entry.chunkZ());
        try {
            LevelChunk chunk = level.getChunk(pos.x, pos.z);
            Map<Integer, Long> hashes = ChunkContentHashUtil.computeSectionHashes(chunk);
            long chunkHash = ChunkContentHashUtil.combineSectionHashes(hashes);
            long[] sectionHashArray = ChunkContentHashUtil.sectionHashesToArray(hashes);
            List<Long> sectionHashList = new ArrayList<>(sectionHashArray.length);
            for (long hash : sectionHashArray) {
                sectionHashList.add(hash);
            }
            // 命中判定：客户端基线（chunkHash 或非空 sectionHashes）与服务端权威一致即 UNCHANGED。
            // chunkHash = combine(sectionHashes)（确定性，空 section 不计），二者等价；
            // 客户端重连后影子端重建会清 hash 表（chunkHash=0），此时非空 sectionHashes 全一致仍判命中。
            boolean hashMatch = entry.chunkHash() != 0L && entry.chunkHash() == chunkHash;
            boolean sectionsMatch = !entry.sectionHashes().isEmpty() && entry.sectionHashes().equals(sectionHashList);
            if (hashMatch || sectionsMatch) {
                return ShadowPullResponseS2CPacket.Result.unchanged(entry.chunkX(), entry.chunkZ(),
                        chunkHash, sectionHashList);
            }
            if (!entry.sectionHashes().isEmpty()
                    && HassiumConfigService.getInstance().isSectionDeltaEnabled()) {
                SectionDeltaS2CPacket.DeltaEntry planned = planAndSerialize(
                        snapshotSectionDeltaColumn(chunk), entry);
                if (planned != null) {
                    SectionDeltaS2CPacket delta = new SectionDeltaS2CPacket(
                            dimension, List.of(planned), List.of());
                    FriendlyByteBuf deltaBuf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                    try {
                        delta.encode(deltaBuf);
                        byte[] payload = new byte[deltaBuf.readableBytes()];
                        deltaBuf.readBytes(payload);
                        return ShadowPullResponseS2CPacket.Result.payload(entry.chunkX(), entry.chunkZ(),
                                ShadowPullResponseS2CPacket.Kind.DELTA, chunkHash, sectionHashList, payload);
                    } finally {
                        deltaBuf.release();
                    }
                }
            }
            ClientboundLevelChunkWithLightPacket packet = buildChunkPacket(chunk, level);
            byte[] payload = packet == null ? null : encodeChunkPacket(packet, level.registryAccess());
            return payload == null
                    ? ShadowPullResponseS2CPacket.Result.error(entry.chunkX(), entry.chunkZ(), "encode")
                    : ShadowPullResponseS2CPacket.Result.payload(entry.chunkX(), entry.chunkZ(),
                    ShadowPullResponseS2CPacket.Kind.FULL, chunkHash, sectionHashList, payload);
        } catch (Throwable t) {
            Constants.LOG.warn("Hassium: shadowPullV1 failed for {}", pos, t);
            return ShadowPullResponseS2CPacket.Result.error(entry.chunkX(), entry.chunkZ(), "load");
        }
    }


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
     * 续流模式下客户端跳过 login/维度初始化；新 shadowPull 请求携带位置与 hash，
     * 服务端按请求结果返回区块终态。
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
     * 网关帧侧握手路径：无 ServerPlayer 时按 UUID 记录初始位置，removePlayer 清理。
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




    /** 服务端每 tick：按原版 tracking 产生的推送队列限流序列化。 */
    public void onServerTick(net.minecraft.server.MinecraftServer server) {
        if (server == null) {
            return;
        }
        ensureInitialized();

        long now = System.currentTimeMillis();
        long drainPendingNs = 0L;
        long drainQueueNs = 0L;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PlayerPushQueue playerQueue = pushQueues.get(player.getUUID());
            if (playerQueue != null) {
                playerQueue.promoteOverflow();
            }
            long t0 = System.nanoTime();
            t0 = System.nanoTime();
            sealPlayerBatch(player);
            drainQueueNs += System.nanoTime() - t0;
        }
        TickMonitor.addHassiumDrainNs(drainPendingNs, drainQueueNs);




        // 清理已离线玩家的批次
        pushQueues.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
    }



    /** FORCE_FULL 任务覆盖较弱的可见性推送任务。 */
    static boolean shouldReplaceQueuedPush(PushKind existing, PushKind incoming) {
        return incoming == PushKind.FORCE_FULL && existing != PushKind.FORCE_FULL;
    }


    /**
     * 后台直发剥光全量：复用已准备的快照；无快照的柱回退到推送队列。
     */
    private void directPushStrippedFull(ServerPlayer player, String dimension, List<ChunkPos> chunks) {
        ChunkSender sender = ChunkSender.getInstance();
        if (sender == null) {
            for (ChunkPos pos : chunks) {
                enqueuePushTask(player, pos, dimension, PushKind.FORCE_FULL);
            }
            return;
        }
        ServerLevel level = PlayerCompat.getServerLevel(player);
        net.minecraft.core.RegistryAccess registryAccess = level != null ? level.registryAccess() : null;
        List<ChunkPos> firstGate = new ArrayList<>();
        for (ChunkPos pos : chunks) {
            PreparedChunk prepared = takePreparedChunkPacket(player.getUUID(), pos);
            if (prepared == null) {
                firstGate.add(pos);
                continue;
            }
            byte[] chunkData = prepared.data();
            if (chunkData == null && prepared.packet() != null && registryAccess != null) {
                chunkData = encodeChunkPacket(prepared.packet(), registryAccess);
            }
            if (chunkData == null) {
                firstGate.add(pos);
                continue;
            }
            compressAndSend(player, new PushTask(pos, dimension, null, PushKind.FORCE_FULL),
                    chunkData, prepared.contentHash(), sender);
        }
        for (ChunkPos pos : firstGate) {
            enqueuePushTask(player, pos, dimension, PushKind.FORCE_FULL);
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
                                                             ShadowPullRequestC2SPacket.Entry clientEntry) {
        SectionDeltaSnapshot serverSnap = SectionDeltaSnapshot.capture(snap.sections());
        long[] clientSectionHashes = new long[clientEntry.sectionHashes().size()];
        for (int index = 0; index < clientSectionHashes.length; index++) {
            clientSectionHashes[index] = clientEntry.sectionHashes().get(index);
        }
        SectionDeltaSnapshot clientSnap = new SectionDeltaSnapshot(clientSectionHashes, clientEntry.planes());
        SectionDeltaPlanner.ChunkDecision decision = SectionDeltaPlanner.plan(clientSnap, serverSnap);
        if (decision.skipWholeChunk()) {
            DebugLogger.info(LogType.NETWORK,
                    "[SECTION_DELTA] Fallback to full for [{}, {}]: FULL sections >= {}%",
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
        int nonEmptySections = 0;
        int fullSections = 0;
        for (int idx = 0; idx < serverSnap.sectionCount(); idx++) {
            if (serverSnap.sectionHash(idx) != 0L) {
                nonEmptySections++;
            }
        }
        for (SectionDeltaS2CPacket.SectionData sectionData : changedSections) {
            if (sectionData.kind() == SectionDeltaS2CPacket.KIND_FULL
                    && serverSnap.sectionHash(sectionData.sectionIndex()) != 0L) {
                fullSections++;
            }
        }
        if (nonEmptySections > 0 && fullSections > 0
                && fullSections * 100 / nonEmptySections >= SectionDeltaPlanner.FALLBACK_THRESHOLD_PCT) {
            DebugLogger.info(LogType.NETWORK,
                    "[SECTION_DELTA] Fallback to full for [{}, {}]: encoded FULL sections >= {}% ({}/{})",
                    clientEntry.chunkX(), clientEntry.chunkZ(),
                    SectionDeltaPlanner.FALLBACK_THRESHOLD_PCT, fullSections, nonEmptySections);
            return null;
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
            // 首次 vanilla tracking 是 pristine 的唯一可靠登记点：此时 chunk 已经完成 FULL
            // 构造，后续 processOne 才能安全地把该柱转换为 SeedRef。
            if (pos != null) {
                PristineRegistry.markIfPristine(player.level(), pos);
            }
            all &= enqueuePushTask(player, pos, dimension, PushKind.FULL_VISIBLE);
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
        if (queue == null) {
            return;
        }
        queue.promoteOverflow();
        if (queue.isEmpty() || !queue.tryReserveSealedBatch()) {
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
        // 本 tick 位置快照仅用于队列距离优先级。
        ChunkPos playerChunk = player.chunkPosition();
        List<SealedWork> works = new ArrayList<>(maxPerTick);
        while (works.size() < maxPerTick && !queue.isEmpty()) {
            PushTask task = queue.pollNearest(playerChunk.x, playerChunk.z);
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
                if (task.kind() == PushKind.FULL_VISIBLE
                        && packet != null
                        && isSeedGenFor(playerId, task.pos(), task.dimension())) {
                    Map<Integer, Long> sectionHashes = ChunkContentHashUtil.computeSectionHashesFromPacket(
                            packet.getChunkData(), level.getSectionsCount(), level.registryAccess());
                    long seedGenHash = ChunkContentHashUtil.combineSectionHashes(sectionHashes);
                    PushTask seedRefTask = new PushTask(task.pos(), task.dimension(),
                            new DataRequestTask(task.pos(), task.dimension(),
                                    new SeedRefWork(seedGenHash,
                                            ChunkContentHashUtil.sectionHashesToArray(sectionHashes)), seedGenHash),
                            PushKind.SEED_REF);
                    works.add(new SealedWork(player, seedRefTask, null,
                            level.registryAccess(), sender, 0L));
                    continue;
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

            // 原版 tracking 产生的任务统一发送权威 full snapshot；不做 Bloom/hash 二次 admission。
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
            DebugLogger.info(LogType.NETWORK, "[PROCESS_QUEUE] Sent stripped full chunk {} to player {} ({} -> {} bytes)", task.pos(), player.getName().getString(),
                    chunkData.length, compressed.compressedData.length);
        } catch (Exception e) {
            Constants.LOG.error("[PROCESS_QUEUE] Failed to compress/send chunk {} for player {}",
                    task.pos(), player.getName().getString(), e);
        }
    }



    /**
     * 仅替换既有 packet 的 light payload；chunk data 保持原版/兼容 Mod 已写入的不可变视图。
     * 广播包会被所有 Hassium 玩家共享，混合 light capability 时不能原地修改，整体回退原 light。
     */
    private ClientboundLevelChunkWithLightPacket stripLightIfConfigured(
            List<ServerPlayer> players, ChunkPos pos, ClientboundLevelChunkWithLightPacket packet) {
        if (!HassiumConfigService.getInstance().isServerLightStrip() || players == null || players.isEmpty()) {
            return packet;
        }
        for (ServerPlayer player : players) {
            if (!isPlayerLightComputeSupported(player.getUUID())) {
                return packet;
            }
        }
        return stripLightInPlace(pos, packet);
    }

    private ClientboundLevelChunkWithLightPacket stripLightIfConfigured(
            ServerPlayer player, ChunkPos pos, ClientboundLevelChunkWithLightPacket packet) {
        if (!HassiumConfigService.getInstance().isServerLightStrip()
                || !isPlayerLightComputeSupported(player.getUUID())) {
            return packet;
        }
        return stripLightInPlace(pos, packet);
    }

    private ClientboundLevelChunkWithLightPacket stripLightInPlace(
            ChunkPos pos, ClientboundLevelChunkWithLightPacket packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            ChunkPacketDataCompat.writeEmptyLightData(buffer);
            ClientboundLightUpdatePacketData emptyLight =
                    new ClientboundLightUpdatePacketData(buffer, pos.x, pos.z);
            ((LevelChunkWithLightPacketAccessor) (Object) packet).hassium$setLightData(emptyLight);
            return packet;
        } catch (Exception e) {
            Constants.LOG.warn("[LIGHT-STRIP] Failed to replace light payload at {}; retaining vanilla light", pos, e);
            return packet;
        } finally {
            buffer.release();
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
    }

    private PreparedChunk takePreparedChunkPacket(UUID playerId, ChunkPos pos) {
        ConcurrentHashMap<Long, PreparedChunk> map = preparedChunkPackets.get(playerId);
        if (map == null) {
            return null;
        }
        PreparedChunk prepared = map.remove(ChunkPos.asLong(pos.x, pos.z));
        if (map.isEmpty()) {
            preparedChunkPackets.remove(playerId, map);
        }
        return prepared;
    }

    private void discardPreparedChunkPacket(UUID playerId, ChunkPos pos) {
        ConcurrentHashMap<Long, PreparedChunk> map = preparedChunkPackets.get(playerId);
        if (map == null) {
            return;
        }
        map.remove(ChunkPos.asLong(pos.x, pos.z));
        if (map.isEmpty()) {
            preparedChunkPackets.remove(playerId, map);
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
        preparedChunkPackets.remove(playerId);
        initialPlayerChunkPos.remove(playerId);
        resumePlayers.remove(playerId);
        playerStateReports.remove(playerId);
        playerLightComputeSupported.remove(playerId);
        seedGenDisabledPlayers.remove(playerId);
        seedGenFallbackCounts.remove(playerId);
    }

    /**
     * 清空所有队列并关闭线程池
     */
    public void shutdown() {
        pushQueues.clear();
        preparedChunkPackets.clear();
        initialPlayerChunkPos.clear();
        resumePlayers.clear();
        playerStateReports.clear();
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
                .mapToInt(PlayerPushQueue::pendingCount)
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
    static record PushTask(ChunkPos pos, String dimension, DataRequestTask data, PushKind kind) {
        static PushTask full(ChunkPos pos, String dimension, PushKind kind) {
            return new PushTask(pos, dimension, null, kind);
        }
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
    /** 推送任务类型：可见全量、强制全量、元数据快照、SeedRef。 */
    enum PushKind { FULL_VISIBLE, FORCE_FULL, METADATA, SEED_REF }


    /**
     * 封批产物：主线程已完成世界快照（chunkData 或 packet），消费线程只做 encode/hash/ZSTD。
     */
    private record SealedWork(ServerPlayer player, PushTask task, Object payload,
                              RegistryAccess registryAccess, ChunkSender sender, long contentHash) {}

    /** 通道项及其所属玩家的已封装批次计数。 */
    private record SealedBatch(PlayerPushQueue owner, List<SealedWork> works) {}

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
        /** 已到达但尚未获准进入主 FIFO 的推送义务，严格保持首次入队顺序。 */
        private final java.util.ArrayDeque<PushTask> overflow = new java.util.ArrayDeque<>();
        private int queuedBatches;

        /** 仅统计可在本 tick 封批的主 FIFO；背压判定不得把 overflow 当作可用槽位。 */
        synchronized int size() {
            return tasks.size();
        }

        synchronized int pendingCount() {
            return tasks.size() + overflow.size();
        }

        synchronized int overflowSize() {
            return overflow.size();
        }

        synchronized int queuedBatchCount() {
            return queuedBatches;
        }

        synchronized boolean isEmpty() {
            return tasks.isEmpty() && overflow.isEmpty();
        }

        /**
         * 把溢出 FIFO 队头回填至主 FIFO。调用方必须在接纳新任务前执行，禁止后来任务插队。
         */
        synchronized void promoteOverflow() {
            while (!overflow.isEmpty() && tasks.size() < queueCapacity()) {
                tasks.addLast(overflow.removeFirst());
            }
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

        /**
         * 同柱已有任务视为成功；FORCE_FULL 原地升级弱义务。主 FIFO 满时改入 overflow，
         * 并在 overflow 未清空前拒绝后来任务直接进入主 FIFO，避免失败柱被后到任务反超。
         */
        synchronized boolean enqueue(PushTask task) {
            PushTask existing = findSameTask(tasks, task);
            if (existing != null) {
                upgradeInPlace(tasks, existing, task);
                return true;
            }
            existing = findSameTask(overflow, task);
            if (existing != null) {
                upgradeInPlace(overflow, existing, task);
                return true;
            }
            if (!overflow.isEmpty() || tasks.size() >= queueCapacity()) {
                if (overflow.size() >= MAX_OVERFLOW_TASKS_PER_PLAYER) {
                    Constants.LOG.warn("[PROCESS_QUEUE] Full-data overflow full (size={}); cannot stage chunk {}",
                            overflow.size(), task.pos());
                    return false;
                }
                overflow.addLast(task);
                return true;
            }
            addToPrimary(task);
            return true;
        }

        private static PushTask findSameTask(java.util.ArrayDeque<PushTask> queue, PushTask task) {
            for (PushTask existing : queue) {
                if (existing.pos().equals(task.pos()) && existing.dimension().equals(task.dimension())) {
                    return existing;
                }
            }
            return null;
        }

        private static void upgradeInPlace(java.util.ArrayDeque<PushTask> queue,
                                           PushTask existing, PushTask replacement) {
            if (!shouldReplaceQueuedPush(existing.kind(), replacement.kind())) {
                return;
            }
            java.util.ArrayDeque<PushTask> rebuilt = new java.util.ArrayDeque<>(queue.size());
            while (!queue.isEmpty()) {
                PushTask current = queue.removeFirst();
                rebuilt.addLast(current == existing ? replacement : current);
            }
            queue.addAll(rebuilt);
        }

        private void addToPrimary(PushTask task) {
            tasks.addLast(task);
        }

        synchronized PushTask poll() {
            return tasks.pollFirst();
        }
        synchronized PushTask pollNearest(int centerX, int centerZ) {
            PushTask nearest = null;
            java.util.ArrayDeque<PushTask> source = null;
            int nearestDistance = Integer.MAX_VALUE;
            for (java.util.ArrayDeque<PushTask> candidateQueue :
                    java.util.List.of(tasks, overflow)) {
                for (PushTask candidate : candidateQueue) {
                    int distance = Math.abs(candidate.pos().x - centerX)
                            + Math.abs(candidate.pos().z - centerZ);
                    if (nearest == null || distance < nearestDistance) {
                        nearest = candidate;
                        nearestDistance = distance;
                        source = candidateQueue;
                    }
                }
            }
            if (nearest != null) {
                source.remove(nearest);
            }
            return nearest;
        }

        synchronized void clear() {
            tasks.clear();
            overflow.clear();
            queuedBatches = 0;
        }

        synchronized void removeIf(java.util.function.Predicate<PushTask> predicate) {
            tasks.removeIf(predicate);
            overflow.removeIf(predicate);
        }
    }
}
