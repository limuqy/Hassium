package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil;
import io.github.limuqy.mc.hassium.concurrent.ChunkDistancePriority;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import io.github.limuqy.mc.hassium.platform.Services;
import io.github.limuqy.mc.hassium.compat.PlayerCompat;
import io.github.limuqy.mc.hassium.compat.RegistryCompat;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
     * 每玩家区块数据请求队列
     */
    private final Map<UUID, PriorityBlockingQueue<DataRequestTask>> dataQueues = new ConcurrentHashMap<>();

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
     * 每玩家：chunkPosLong → 已编码的 ClientboundLevelChunkWithLightPacket 线格式字节。
     * 在广播/初始发送拦截时写入，miss 全量请求时优先取出，避免从 LevelChunk 重建旁路反透视。
     */
    private final Map<UUID, ConcurrentHashMap<Long, byte[]>> preparedChunkPackets = new ConcurrentHashMap<>();

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
        // 主线程编码并缓存：保留反透视等 mod 已改写的包视图
        byte[] encoded = encodeChunkPacket(packet, registryAccess);
        if (encoded != null) {
            for (ServerPlayer player : players) {
                putPreparedChunkPacket(player.getUUID(), pos, encoded);
            }
        }
        pushPool.submit(() -> {
            try {
                // 从已序列化的 packet 数据计算 section 哈希（线程安全，无需读取世界）
                Map<Integer, Long> sectionHashes = ChunkContentHashUtil.computeSectionHashesFromPacket(
                        packet.getChunkData(), sectionCount, registryAccess);
                long chunkHash = ChunkContentHashUtil.combineSectionHashes(sectionHashes);
                // 从 sectionHashes 推导 bitmap：有 hash 的 section = 有方块数据
                int sectionBitmap = 0;
                for (int idx : sectionHashes.keySet()) {
                    sectionBitmap |= (1 << idx);
                }

                sendChunkHash(players, pos, chunkHash, sectionBitmap, dimension);
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
        final ServerLevel playerLevel = PlayerCompat.getServerLevel(player);
        final int sectionCount = playerLevel.getSectionsCount();
        final RegistryAccess registryAccess = playerLevel.registryAccess();
        if (chunkPacket instanceof ClientboundLevelChunkWithLightPacket lightPacket) {
            byte[] encoded = encodeChunkPacket(lightPacket, registryAccess);
            if (encoded != null) {
                putPreparedChunkPacket(player.getUUID(), pos, encoded);
            }
        }
        pushPool.submit(() -> {
            try {
                Map<Integer, Long> sectionHashes;
                int sectionBitmap;

                if (chunkPacket instanceof ClientboundLevelChunkWithLightPacket lightPacket) {
                    // 从已序列化的 packet 数据计算（线程安全）
                    sectionHashes = ChunkContentHashUtil.computeSectionHashesFromPacket(
                            lightPacket.getChunkData(), sectionCount, registryAccess);
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

                if (player.isAlive() && !player.hasDisconnected()) {
                    sendChunkHash(List.of(player), pos, chunkHash, sectionBitmap, dimension);
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
     * {@code PlayerChunkSender.sendChunk}。主线程先按原版路径构建包并缓存字节（供反透视注入），
     * 再在 pushPool 上从包数据计算 hash。
     *
     * @param player    目标玩家
     * @param pos       区块位置
     * @param chunk     区块对象（须在主线程调用）
     * @param dimension 维度标识
     */
    public void submitMetadataTaskFromChunk(ServerPlayer player, ChunkPos pos,
                                             LevelChunk chunk, String dimension) {
        ensureInitialized();
        ServerLevel level = PlayerCompat.getServerLevel(player);
        final int sectionCount = level.getSectionsCount();
        final RegistryAccess registryAccess = level.registryAccess();

        ClientboundLevelChunkWithLightPacket packet = buildChunkPacket(chunk, level);
        if (packet == null) {
            Constants.LOG.warn("[ASYNC_METADATA] Failed to build chunk packet for {}", pos);
            return;
        }
        byte[] encoded = encodeChunkPacket(packet, registryAccess);
        if (encoded != null) {
            putPreparedChunkPacket(player.getUUID(), pos, encoded);
        }

        pushPool.submit(() -> {
            try {
                Map<Integer, Long> sectionHashes = ChunkContentHashUtil.computeSectionHashesFromPacket(
                        packet.getChunkData(), sectionCount, registryAccess);
                int sectionBitmap = 0;
                for (int idx : sectionHashes.keySet()) {
                    sectionBitmap |= (1 << idx);
                }
                long chunkHash = ChunkContentHashUtil.combineSectionHashes(sectionHashes);

                if (player.isAlive() && !player.hasDisconnected()) {
                    sendChunkHash(List.of(player), pos, chunkHash, sectionBitmap, dimension);
                }
            } catch (Exception e) {
                Constants.LOG.error("[ASYNC_METADATA] Failed to compute chunkHash for chunk {} (player={})",
                        pos, player.getName().getString(), e);
            }
        });
    }

    /**
     * 将阶段一 chunkHash 加入短窗口批次（由 server tick 或凑满后发送）。
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
            PendingHashBatch flushDueToSize = null;
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
                if (batch.entries.size() >= HASH_BATCH_MAX_ENTRIES) {
                    flushDueToSize = batch;
                    hashBatches.remove(playerId);
                }
            }
            if (flushDueToDimension != null) {
                flushHashBatch(player, flushDueToDimension);
            }
            if (flushDueToSize != null) {
                flushHashBatch(player, flushDueToSize);
            }
        }
    }

    /**
     * 冲刷单个玩家的 hash 批次。
     */
    private void flushHashBatch(ServerPlayer player, PendingHashBatch batch) {
        if (batch == null || batch.entries.isEmpty()) {
            return;
        }
        if (!player.isAlive() || player.hasDisconnected()) {
            return;
        }
        DebugLogger.info(LogType.NETWORK, "[SEND_HASH] Flushing {} chunkHashes to player {} (dimension={})",
                batch.entries.size(), player.getName().getString(), batch.dimension);
        FriendlyByteBuf buf = null;
        boolean sent = false;
        try {
            ChunkHashS2CPacket packet = new ChunkHashS2CPacket(batch.dimension, new ArrayList<>(batch.entries));
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
        int centerX = player.chunkPosition().x;
        int centerZ = player.chunkPosition().z;
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
        entries.sort(Comparator.comparingDouble(e ->
                ChunkDistancePriority.distSq(e.pos(), centerX, centerZ)));
        Deque<ResyncEntry> queue = new ArrayDeque<>(entries.size());
        queue.addAll(entries);
        if (!queue.isEmpty()) {
            pendingResync.put(player.getUUID(), queue);
            Constants.LOG.info("Hassium: Queued {} chunkHashes for resync (player={}, vd={}, perTick={})",
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
                    Constants.LOG.info("Hassium: Resync drain for {} — submitted {}, skipped {}, remaining {}",
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
        if (!initialized.get() && dataQueues.isEmpty() && hashBatches.isEmpty() && pendingResync.isEmpty()) {
            return;
        }
        ensureInitialized();

        long now = System.currentTimeMillis();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            flushPlayerHashBatchIfDue(player, now);
            drainPlayerQueueTick(player);
        }

        // 分批补发握手后 resync 队列（每 tick 最多 RESYNC_PER_TICK 个/玩家）
        drainPendingResync(server);

        // 清理已离线玩家的批次
        hashBatches.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        adjustThreadPool();
    }

    private void flushPlayerHashBatchIfDue(ServerPlayer player, long nowMs) {
        UUID playerId = player.getUUID();
        PendingHashBatch batch;
        synchronized (hashBatches) {
            batch = hashBatches.get(playerId);
            if (batch == null || batch.entries.isEmpty()) {
                return;
            }
            if (nowMs - batch.createdAtMs < HASH_BATCH_MAX_WAIT_MS
                    && batch.entries.size() < HASH_BATCH_MAX_ENTRIES) {
                return;
            }
            hashBatches.remove(playerId, batch);
        }
        flushHashBatch(player, batch);
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
     * 比对客户端的 section 哈希与服务端当前数据，只发送变更的 section 和 blockEntity。
     * 每次请求都回包：可服务的进 {@code entries}，超距/失败的进 {@code skipped}（客户端回退全量）。
     */
    public void handleSectionHashRequest(ServerPlayer player, SectionHashRequestC2SPacket request) {
        if (!player.isAlive() || player.hasDisconnected()) { return; }

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
                        entry.chunkX(), entry.chunkZ(), changedSections, blockEntities));
            } catch (Exception e) {
                Constants.LOG.error("[SECTION_DELTA] Failed to process chunk [{}, {}]",
                        entry.chunkX(), entry.chunkZ(), e);
                skipped.add(new SectionDeltaS2CPacket.SkippedChunk(entry.chunkX(), entry.chunkZ()));
            }
        }

        // 始终回包，避免客户端悬等（含 entries/skipped 皆空的边界）
        FriendlyByteBuf buf = null;
        boolean sent = false;
        try {
            SectionDeltaS2CPacket deltaPacket = new SectionDeltaS2CPacket(
                    request.dimension(), deltas, skipped);
            buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            deltaPacket.encode(buf);
            Services.NETWORK_MANAGER.sendSectionDeltaPacket(player, buf);
            sent = true;
            DebugLogger.info(LogType.NETWORK,
                    "[SECTION_DELTA] Sent response: {} deltas, {} skipped (dimension={})",
                    deltas.size(), skipped.size(), request.dimension());
        } catch (Exception e) {
            Constants.LOG.error("[SECTION_DELTA] Failed to send delta response", e);
        } finally {
            if (!sent && buf != null) {
                buf.release();
            }
        }
    }

    /**
     * 处理客户端的 blockEntity 数据请求。
     * <p>
     * 收集指定区块的所有 blockEntity 数据并发送，不传输完整区块。
     */
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
     * 收集 chunk 中所有 blockEntity 的数据
     */
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

        ensureInitialized();

        // 检查玩家是否仍然在线
        if (!player.isAlive() || player.hasDisconnected()) {
            Constants.LOG.warn("[ENQUEUE_DATA] Player {} is not online, ignoring data request",
                    player.getName().getString());
            return;
        }

        UUID playerId = player.getUUID();
        PriorityBlockingQueue<DataRequestTask> queue = dataQueues.computeIfAbsent(
                playerId, k -> new PriorityBlockingQueue<>(100, Comparator.comparingDouble(DataRequestTask::priority))
        );

        // 限制队列大小，防止内存溢出；超限时按距离只填剩余容量，勿整批丢弃
//        int maxQueueSize = 2048;
//        int room = maxQueueSize - queue.size();
//        if (room <= 0) {
//            Constants.LOG.warn("[ENQUEUE_DATA] Queue full for player {} (size={}, max={}), dropping {} chunks",
//                    player.getName().getString(), queue.size(), maxQueueSize, chunks.size());
//            return;
//        }

        double playerChunkX = player.getX() / 16.0;
        double playerChunkZ = player.getZ() / 16.0;

        List<DataRequestTask> tasks = new ArrayList<>(chunks.size());
        for (ChunkPos pos : chunks) {
            // 入队瞬间冻结 distSq（层内排序键；无 renderOnly 层）
            double priority = ChunkDistancePriority.distSq(pos, playerChunkX, playerChunkZ);
            tasks.add(new DataRequestTask(pos, dimension, priority));
        }
//        if (tasks.size() > room) {
//            tasks.sort(Comparator.comparingDouble(DataRequestTask::priority));
//            int dropped = tasks.size() - room;
//            tasks = tasks.subList(0, room);
//            Constants.LOG.warn("[ENQUEUE_DATA] Queue near limit for player {} ({} + {} > {}), queued nearest {} dropped {}",
//                    player.getName().getString(), queue.size(), chunks.size(), maxQueueSize, room, dropped);
//        }
        for (DataRequestTask task : tasks) {
            queue.offer(task);
        }

        DebugLogger.info(LogType.NETWORK, "[ENQUEUE_DATA] Player {} queued {} chunks (queueSize={}, playerPos=({}, {}))",
                player.getName().getString(), tasks.size(), queue.size(), playerChunkX, playerChunkZ);
        // 实际 drain 由 onServerTick 按真实每 tick 上限处理，避免连环 submit 卡主线程
    }

    /**
     * 每 tick 在主线程序列化最多 maxChunksPerTick 个区块，压缩发送下推到 pushPool。
     */
    private void drainPlayerQueueTick(ServerPlayer player) {
        UUID playerId = player.getUUID();
        PriorityBlockingQueue<DataRequestTask> queue = dataQueues.get(playerId);
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

            int maxPerTick = HassiumConfigService.getInstance().getConfig().serverNetwork().maxChunksPerTick();
            if (maxPerTick <= 0) {
                maxPerTick = 10;
            }

            ServerLevel level = PlayerCompat.getServerLevel(player);
            List<SerializedChunkWork> works = new ArrayList<>(maxPerTick);
            int processed = 0;

            while (processed < maxPerTick && !queue.isEmpty()) {
                if (!player.isAlive() || player.hasDisconnected()) {
                    removePlayer(playerId);
                    return;
                }

                DataRequestTask task = queue.poll();
                if (task == null) {
                    break;
                }

                try {
                    // 优先使用拦截时缓存的包字节（与 chunkHash / 反透视视图一致）
                    byte[] chunkData = takePreparedChunkPacket(playerId, task.pos());
                    if (chunkData == null) {
                        LevelChunk chunk = level.getChunk(task.pos().x, task.pos().z);
                        if (chunk == null) {
                            Constants.LOG.warn("[PROCESS_QUEUE] Chunk {} not loaded, skipping", task.pos());
                            continue;
                        }
                        chunkData = serializeChunk(chunk, level);
                    }
                    if (chunkData == null) {
                        Constants.LOG.warn("[PROCESS_QUEUE] Failed to serialize chunk {}", task.pos());
                        continue;
                    }

                    works.add(new SerializedChunkWork(player, task.pos(), chunkData));
                    processed++;
                    DebugLogger.info(LogType.NETWORK, "[PROCESS_QUEUE] Serialized chunk {} ({} bytes, remaining={})",
                            task.pos(), chunkData.length, queue.size());
                } catch (Exception e) {
                    Constants.LOG.error("[PROCESS_QUEUE] Failed to serialize chunk {} for player {}",
                            task.pos(), player.getName().getString(), e);
                }
            }

            if (!works.isEmpty()) {
                DebugLogger.info(LogType.NETWORK, "[PROCESS_QUEUE] Tick drain for {}: serialized={}, remaining={}",
                        player.getName().getString(), works.size(), queue.size());
                for (SerializedChunkWork work : works) {
                    pushPool.submit(() -> compressAndSend(work, sender));
                }
            }
        } finally {
            flag.set(false);
        }
    }

    /**
     * 后台压缩并发送（不访问世界对象）。
     */
    private void compressAndSend(SerializedChunkWork work, ChunkSender sender) {
        ServerPlayer player = work.player();
        if (!player.isAlive() || player.hasDisconnected()) {
            return;
        }
        try {
            ChunkCompressionHandler.CompressedChunkData compressed =
                    ChunkCompressionHandler.compressChunkData(
                            work.chunkData(), work.pos().x, work.pos().z);
            if (compressed == null) {
                Constants.LOG.warn("[PROCESS_QUEUE] Failed to compress chunk {}", work.pos());
                return;
            }
            sender.sendCompressedChunk(player, compressed);
            NetworkStats.recordChunkSent(work.chunkData().length);
            DebugLogger.info(LogType.NETWORK, "[PROCESS_QUEUE] Sent chunk {} to player {} ({} -> {} bytes, ratio={})",
                    work.pos(), player.getName().getString(),
                    work.chunkData().length, compressed.compressedData.length,
                    String.format("%.2f", (double) work.chunkData().length / compressed.compressedData.length));
        } catch (Exception e) {
            Constants.LOG.error("[PROCESS_QUEUE] Failed to compress/send chunk {} for player {}",
                    work.pos(), player.getName().getString(), e);
        }
    }

    /**
     * 按原版构造路径构建区块包（主线程；反透视等可在构造/写路径注入）。
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
     * 将区块包编码为线格式字节（须在持有 RegistryAccess 的线程调用，通常为主线程）。
     */
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

    /**
     * 序列化区块为字节数组（缓存未命中时的回退路径）。
     */
    private byte[] serializeChunk(LevelChunk chunk, ServerLevel level) {
        ClientboundLevelChunkWithLightPacket chunkPacket = buildChunkPacket(chunk, level);
        if (chunkPacket == null) {
            return null;
        }
        return encodeChunkPacket(chunkPacket, level.registryAccess());
    }

    private void putPreparedChunkPacket(UUID playerId, ChunkPos pos, byte[] data) {
        ConcurrentHashMap<Long, byte[]> map =
                preparedChunkPackets.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
        map.put(ChunkPos.asLong(pos.x, pos.z), data);
        if (map.size() > MAX_PREPARED_PER_PLAYER) {
            int toRemove = map.size() - MAX_PREPARED_PER_PLAYER;
            var it = map.keySet().iterator();
            while (toRemove-- > 0 && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }

    private byte[] takePreparedChunkPacket(UUID playerId, ChunkPos pos) {
        ConcurrentHashMap<Long, byte[]> map = preparedChunkPackets.get(playerId);
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
                .mapToInt(PriorityBlockingQueue::size)
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
     * 移除玩家的所有队列
     */
    public void removePlayer(UUID playerId) {
        PriorityBlockingQueue<DataRequestTask> queue = dataQueues.remove(playerId);
        if (queue != null) {
            queue.clear();
        }
        processingFlags.remove(playerId);
        hashBatches.remove(playerId);
        preparedChunkPackets.remove(playerId);
        pendingResync.remove(playerId);
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
                .mapToInt(PriorityBlockingQueue::size)
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
     * 主线程已序列化、待后台压缩发送的工作项
     */
    private record SerializedChunkWork(ServerPlayer player, ChunkPos pos, byte[] chunkData) {}

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
