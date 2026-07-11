package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import io.github.limuqy.mc.hassium.platform.Services;
import io.github.limuqy.mc.hassium.compat.RegistryCompat;
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
import java.util.Comparator;
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
 * 1. 直接发送区块元数据（位置+时间戳）给客户端
 * 2. 管理区块数据请求队列，使用线程池异步压缩和发送
 */
public class ServerChunkPushManager {

    private static final ServerChunkPushManager INSTANCE = new ServerChunkPushManager();

    /**
     * 每玩家区块数据请求队列
     */
    private final Map<UUID, PriorityBlockingQueue<DataRequestTask>> dataQueues = new ConcurrentHashMap<>();

    /**
     * 数据请求处理线程池
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
     * 直接发送区块元数据给客户端（轻量操作，不入队）
     *
     * @param player      目标玩家
     * @param pos         区块位置
     * @param contentHash 内容哈希
     */
    public void sendMetadata(ServerPlayer player, ChunkPos pos, long contentHash) {
        FriendlyByteBuf buf = null;
        boolean sent = false;
        try {
            String dimension = player.level().dimension()
#if MC_VER < MC_1_21_11
                    .location()
#else
                    .identifier()
#endif
                    .toString();
            List<ChunkMetadataS2CPacket.MetadataEntry> entries = List.of(
                    new ChunkMetadataS2CPacket.MetadataEntry(pos.x, pos.z, contentHash)
            );
            ChunkMetadataS2CPacket packet = new ChunkMetadataS2CPacket(dimension, entries);

            buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            packet.encode(buf);

            Constants.LOG.debug("[SEND_METADATA] chunk {} -> {} (hash={}, bufSize={})",
                    pos, player.getName().getString(), Long.toHexString(contentHash), buf.readableBytes());

            Services.NETWORK_MANAGER.sendMetadataPacket(player, buf);
            sent = true;
            NetworkStats.recordMetadataSent(buf.readableBytes());
        } catch (Exception e) {
            Constants.LOG.error("[SEND_METADATA] Failed to send metadata for chunk {} to player {}",
                    pos, player.getName().getString(), e);
        } finally {
            if (!sent && buf != null) {
                buf.release();
            }
        }
    }

    /**
     * 批量发送区块元数据给客户端
     */
    public void sendMetadataBatch(ServerPlayer player, String dimension,
                                  List<ChunkMetadataS2CPacket.MetadataEntry> entries) {
        FriendlyByteBuf buf = null;
        boolean sent = false;
        try {
            ChunkMetadataS2CPacket packet = new ChunkMetadataS2CPacket(dimension, entries);
            buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            packet.encode(buf);
            Services.NETWORK_MANAGER.sendMetadataPacket(player, buf);
            sent = true;
        } catch (Exception e) {
            Constants.LOG.debug("Hassium: Failed to send metadata batch to player {}",
                    player.getName().getString(), e);
        } finally {
            // 只有在发送失败时才释放缓冲区
            // 发送成功时，Fabric/Forge 会接管 buf 的所有权
            if (!sent && buf != null) {
                buf.release();
            }
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
#if MC_VER < MC_1_21_11
        final int sectionCount = players.get(0).serverLevel().getSectionsCount();
        final RegistryAccess registryAccess = players.get(0).serverLevel().registryAccess();
#else
        final int sectionCount = players.get(0).level().getSectionsCount();
        final RegistryAccess registryAccess = players.get(0).level().registryAccess();
#endif
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
#if MC_VER < MC_1_21_11
        final int sectionCount = player.serverLevel().getSectionsCount();
        final RegistryAccess registryAccess = player.serverLevel().registryAccess();
#else
        final int sectionCount = player.level().getSectionsCount();
        final RegistryAccess registryAccess = player.level().registryAccess();
#endif
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
#if MC_VER < MC_1_21_11
                    ServerLevel level = player.serverLevel();
#else
                    ServerLevel level = player.level();
#endif
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
     * 发送阶段一 chunkHash 给客户端
     */
    private void sendChunkHash(List<ServerPlayer> players, ChunkPos pos,
                                long chunkHash, int sectionBitmap, String dimension) {
        for (ServerPlayer player : players) {
            if (!player.isAlive() || player.hasDisconnected()) { continue; }
            FriendlyByteBuf buf = null;
            boolean sent = false;
            try {
                List<ChunkHashS2CPacket.Entry> entries = List.of(
                        new ChunkHashS2CPacket.Entry(pos.x, pos.z, chunkHash, sectionBitmap)
                );
                ChunkHashS2CPacket packet = new ChunkHashS2CPacket(dimension, entries);
                buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                packet.encode(buf);
                Services.NETWORK_MANAGER.sendChunkHashPacket(player, buf);
                sent = true;
            } catch (Exception e) {
                Constants.LOG.error("[CHUNK_HASH] Failed to send chunkHash for chunk {} to player {}",
                        pos, player.getName().getString(), e);
            } finally {
                if (!sent && buf != null) {
                    buf.release();
                }
            }
        }
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
     * 处理客户端的 section 哈希请求（阶段二）。
     * <p>
     * 比对客户端的 section 哈希与服务端当前数据，只发送变更的 section 和 blockEntity。
     */
    public void handleSectionHashRequest(ServerPlayer player, SectionHashRequestC2SPacket request) {
        if (!player.isAlive() || player.hasDisconnected()) { return; }

#if MC_VER < MC_1_21_11
        ServerLevel level = player.serverLevel();
        int viewDistance = player.server.getPlayerList().getViewDistance();
#else
        ServerLevel level = player.level();
        int viewDistance = player.level().getServer().getPlayerList().getViewDistance();
#endif
        ChunkPos playerChunkPos = player.chunkPosition();
        List<SectionDeltaS2CPacket.DeltaEntry> deltas = new ArrayList<>();

        for (var entry : request.entries()) {
            try {
                // 校验区块是否在玩家视距范围内
                int dx = Math.abs(entry.chunkX() - playerChunkPos.x);
                int dz = Math.abs(entry.chunkZ() - playerChunkPos.z);
                if (dx > viewDistance || dz > viewDistance) { continue; }

                LevelChunk chunk = level.getChunk(entry.chunkX(), entry.chunkZ());
                if (chunk == null) { continue; }

                // 计算当前 section 哈希（不含 blockEntity）
                Map<Integer, Long> currentHashes = ChunkContentHashUtil.computeSectionHashes(chunk);
                long[] clientHashes = entry.sectionHashes();

                // 比对，找出变更的 sections
                List<SectionDeltaS2CPacket.SectionData> changedSections = new ArrayList<>();
                for (var current : currentHashes.entrySet()) {
                    int idx = current.getKey();
                    long serverHash = current.getValue();
                    long clientHash = idx < clientHashes.length ? clientHashes[idx] : 0L;

                    if (serverHash != clientHash) {
                        // 方块数据有变更，序列化该 section
                        byte[] data = serializeSection(chunk, idx);
                        changedSections.add(new SectionDeltaS2CPacket.SectionData(idx, data));
                    }
                }

                // 收集 blockEntity 数据（始终发送）
                List<SectionDeltaS2CPacket.BlockEntityData> blockEntities = collectBlockEntities(chunk);

                deltas.add(new SectionDeltaS2CPacket.DeltaEntry(
                        entry.chunkX(), entry.chunkZ(), changedSections, blockEntities));
            } catch (Exception e) {
                Constants.LOG.error("[SECTION_DELTA] Failed to process chunk [{}, {}]",
                        entry.chunkX(), entry.chunkZ(), e);
            }
        }

        // 发送 delta 响应
        if (!deltas.isEmpty()) {
            FriendlyByteBuf buf = null;
            boolean sent = false;
            try {
                SectionDeltaS2CPacket deltaPacket = new SectionDeltaS2CPacket(
                        request.dimension(), deltas);
                buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                deltaPacket.encode(buf);
                Services.NETWORK_MANAGER.sendSectionDeltaPacket(player, buf);
                sent = true;
            } catch (Exception e) {
                Constants.LOG.error("[SECTION_DELTA] Failed to send delta response", e);
            } finally {
                if (!sent && buf != null) {
                    buf.release();
                }
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

#if MC_VER < MC_1_21_11
        ServerLevel level = player.serverLevel();
        int viewDistance = player.server.getPlayerList().getViewDistance();
#else
        ServerLevel level = player.level();
        int viewDistance = player.level().getServer().getPlayerList().getViewDistance();
#endif
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

        Constants.LOG.info("[ENQUEUE_DATA] Player {} requested {} chunks (dimension={})",
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

        // 限制队列大小，防止内存溢出
        int maxQueueSize = 500;
        if (queue.size() + chunks.size() > maxQueueSize) {
            Constants.LOG.warn("[ENQUEUE_DATA] Queue too large for player {} ({} + {} > {}), dropping request",
                    player.getName().getString(), queue.size(), chunks.size(), maxQueueSize);
            return;
        }

        double playerChunkX = player.getX() / 16.0;
        double playerChunkZ = player.getZ() / 16.0;

        for (ChunkPos pos : chunks) {
            double dx = pos.x - playerChunkX;
            double dz = pos.z - playerChunkZ;
            double distance = Math.sqrt(dx * dx + dz * dz);
            queue.offer(new DataRequestTask(pos, dimension, distance));
        }

        Constants.LOG.info("[ENQUEUE_DATA] Player {} queued {} chunks (queueSize={}, playerPos=({}, {}))",
                player.getName().getString(), chunks.size(), queue.size(), playerChunkX, playerChunkZ);

        // 提交处理任务到线程池
        pushPool.submit(() -> processPlayerQueue(player, dimension));
    }

    /**
     * 处理单个玩家的数据请求队列
     */
    private void processPlayerQueue(ServerPlayer player, String dimension) {
        UUID playerId = player.getUUID();
        PriorityBlockingQueue<DataRequestTask> queue = dataQueues.get(playerId);
        if (queue == null || queue.isEmpty()) {
            Constants.LOG.debug("[PROCESS_QUEUE] Queue is empty for player {}", player.getName().getString());
            return;
        }

        Constants.LOG.info("[PROCESS_QUEUE] Processing queue for player {} (queueSize={})",
                player.getName().getString(), queue.size());

        // 检查玩家是否仍然在线
        if (!player.isAlive() || player.hasDisconnected()) {
            Constants.LOG.warn("[PROCESS_QUEUE] Player {} is not online, removing queue", player.getName().getString());
            removePlayer(playerId);
            return;
        }

#if MC_VER < MC_1_21_11
        ServerLevel level = player.serverLevel();
#else
        ServerLevel level = player.level();
#endif
        ChunkSender sender = ChunkSender.getInstance();
        if (sender == null) {
            Constants.LOG.error("[PROCESS_QUEUE] ChunkSender not initialized, cannot send chunk data");
            return;
        }

        // 每批处理的最大区块数
        int maxPerBatch = HassiumConfigService.getInstance().getConfig().network().maxChunksPerTick();

        Constants.LOG.info("[PROCESS_QUEUE] Processing up to {} chunks for player {}", maxPerBatch, player.getName().getString());

        int processed = 0;
        while (processed < maxPerBatch && !queue.isEmpty()) {
            // 再次检查玩家是否仍然在线
            if (!player.isAlive() || player.hasDisconnected()) {
                Constants.LOG.warn("[PROCESS_QUEUE] Player {} disconnected during processing", player.getName().getString());
                removePlayer(playerId);
                return;
            }

            DataRequestTask task = queue.poll();
            if (task == null) break;

            Constants.LOG.info("[PROCESS_QUEUE] Processing chunk {} (priority={}, remaining={})",
                    task.pos(), String.format("%.1f", task.priority()), queue.size());

            try {
                LevelChunk chunk = level.getChunk(task.pos().x, task.pos().z);
                if (chunk == null) {
                    Constants.LOG.warn("[PROCESS_QUEUE] Chunk {} not loaded, skipping", task.pos());
                    continue;
                }

                // 序列化区块数据
                byte[] chunkData = serializeChunk(chunk, level);
                if (chunkData == null) {
                    Constants.LOG.warn("[PROCESS_QUEUE] Failed to serialize chunk {}", task.pos());
                    continue;
                }

                Constants.LOG.info("[PROCESS_QUEUE] Serialized chunk {} ({} bytes)", task.pos(), chunkData.length);

                // 压缩并发送
                ChunkCompressionHandler.CompressedChunkData compressed =
                        ChunkCompressionHandler.compressChunkData(chunkData, task.pos().x, task.pos().z);
                if (compressed != null) {
                    sender.sendCompressedChunk(player, compressed);
                    processed++;

                    // 记录区块发送（压缩前后对比）
                    NetworkStats.recordChunkSent(chunkData.length, compressed.compressedData.length);

                    Constants.LOG.info("[PROCESS_QUEUE] Sent chunk {} to player {} ({} -> {} bytes, ratio={})",
                            task.pos(), player.getName().getString(), chunkData.length, compressed.compressedData.length,
                            String.format("%.2f", (double) chunkData.length / compressed.compressedData.length));
                } else {
                    Constants.LOG.warn("[PROCESS_QUEUE] Failed to compress chunk {}", task.pos());
                }
            } catch (Exception e) {
                Constants.LOG.error("[PROCESS_QUEUE] Failed to process chunk {} for player {}",
                        task.pos(), player.getName().getString(), e);
            }
        }

        Constants.LOG.info("[PROCESS_QUEUE] Completed processing for player {}: sent={}, remaining={}",
                player.getName().getString(), processed, queue.size());

        // 动态调整线程池
        adjustThreadPool();

        // 队列未排空时继续调度，避免大批量 miss 请求卡死
        if (!queue.isEmpty() && player.isAlive() && !player.hasDisconnected()) {
            pushPool.submit(() -> processPlayerQueue(player, dimension));
        }
    }

    /**
     * 序列化区块为字节数组
     */
    private byte[] serializeChunk(LevelChunk chunk, ServerLevel level) {
        // 光照剥离：启用时传入空 BitSet，不传输光照数据
        boolean stripLight = HassiumConfigService.getInstance().isLightStripEnabled();
        java.util.BitSet lightMask = stripLight ? new java.util.BitSet() : null;
        net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket chunkPacket =
                new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(
                        chunk, level.getLightEngine(), lightMask, lightMask);

        io.netty.buffer.ByteBuf tempBuf = io.netty.buffer.Unpooled.buffer();
        try {
            FriendlyByteBuf friendlyBuf = new FriendlyByteBuf(tempBuf);
#if MC_VER < MC_1_20_5
            chunkPacket.write(friendlyBuf);

            byte[] data = new byte[tempBuf.readableBytes()];
            tempBuf.getBytes(0, data);
            return data;
#else
            // 1.20.6+: Packet.write() removed
            Constants.LOG.warn("Chunk packet serialization not supported on 1.20.6+");
            return null;
#endif
        } catch (Exception e) {
            Constants.LOG.error("Hassium: Failed to serialize chunk {}", chunk.getPos(), e);
            return null;
        } finally {
            tempBuf.release();
        }
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

        int currentPoolSize = pushPool.getPoolSize();
        int activeThreads = pushPool.getActiveCount();
        int maxThreads = pushPool.getMaximumPoolSize();
        int minThreads = HassiumConfigService.getInstance().getMinPushThreads();

        // 队列堆积且线程数未达上限，扩容
        if (totalPending > QUEUE_HIGH_THRESHOLD && currentPoolSize < maxThreads) {
            int newMax = Math.min(currentPoolSize + 2, maxThreads);
            pushPool.setMaximumPoolSize(newMax);
            Constants.LOG.debug("Hassium: Thread pool expanded to {} (queueSize={}, active={})",
                    newMax, totalPending, activeThreads);
        }
        // 队列空闲且线程数高于最小值，缩容
        else if (totalPending < QUEUE_LOW_THRESHOLD && currentPoolSize > minThreads && activeThreads < currentPoolSize) {
            int newMax = Math.max(currentPoolSize - 1, minThreads);
            pushPool.setMaximumPoolSize(newMax);
            Constants.LOG.debug("Hassium: Thread pool shrunk to {} (queueSize={}, active={})",
                    newMax, totalPending, activeThreads);
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
    }

    /**
     * 清空所有队列并关闭线程池
     */
    public void shutdown() {
        dataQueues.clear();
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
}
