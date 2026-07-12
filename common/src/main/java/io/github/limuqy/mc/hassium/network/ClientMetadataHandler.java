package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil;
import io.github.limuqy.mc.hassium.cache.client.ClientCacheLoadQueue;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import io.github.limuqy.mc.hassium.cache.client.ClientChunkMetadata;
import io.github.limuqy.mc.hassium.cache.client.ClientHassiumStorage;
import io.github.limuqy.mc.hassium.concurrent.HassiumTaskExecutor;
import io.github.limuqy.mc.hassium.concurrent.TaskCategory;
import io.github.limuqy.mc.hassium.platform.Services;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DebugLogger.LogType;
import io.github.limuqy.mc.hassium.compat.RegistryCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端区块元数据处理器
 * <p>
 * 处理服务端发送的区块元数据包，比对本地缓存决定加载方式。
 * S3: 元数据比对在后台线程执行，避免主线程阻塞在 region 文件磁盘 I/O 上。
 * M2: SQLite 初始化由 MixinClientPacketListener 在 handleLogin 时异步完成。
 */
public class ClientMetadataHandler {

    /** 区块平均大小估算（字节），用于缓存命中率按内容计算 */
    private static final long ESTIMATED_CHUNK_BYTES = 16 * 1024; // 16KB

    /**
     * 区块已应用到世界后才发送的 BE 请求（chunkKey → dimension）。
     * 避免 BE 包先于缓存区块到达导致 getBlockEntity() 为 null。
     */
    private static final ConcurrentHashMap<Long, String> PENDING_BE_REQUESTS = new ConcurrentHashMap<>();

    /**
     * BE 数据暂存：区块尚未加载时先缓存，apply 后再写入。
     */
    private static final ConcurrentHashMap<Long, List<PendingBlockEntityNbt>> PENDING_BLOCK_ENTITIES =
            new ConcurrentHashMap<>();

    private record PendingBlockEntityNbt(BlockPos pos, CompoundTag nbt) {}

    /**
     * 元数据比对结果
     */
    public record MetadataResult(
            List<ChunkPos> hitChunks,
            List<Double> hitDistances,
            List<ChunkPos> missedChunks,
            String dimension,
            int totalEntries
    ) {}

    /**
     * 处理区块元数据包（S3 异步化入口）
     * <p>
     * 将元数据比对（磁盘 I/O）提交到后台线程，结果通过 MainThreadDispatcher 回到主线程应用。
     * 任务标记为 BEST_EFFORT：登出时可以取消，但通常会完成以利下次连接。
     * <p>
     * 如果 HassiumTaskExecutor 未初始化，回退到同步处理。
     */
    public static void handleMetadataPacket(ChunkMetadataS2CPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            DebugLogger.warn(LogType.METADATA, "[CLIENT_METADATA] Player is null, skipping metadata packet");
            return;
        }

        DebugLogger.info(LogType.METADATA, "[CLIENT_METADATA] Received metadata packet, dimension={}, entries={}",
                packet.dimension(), packet.entries().size());

        // 记录收到元数据（估算大小：dimension字符串 + 每条记录约18字节）
        int estimatedSize = packet.dimension().length() + packet.entries().size() * 18 + 8;
        NetworkStats.recordMetadataReceived(estimatedSize);

        // 如果 storage 尚未初始化（异步初始化尚未完成），尝试同步初始化
        ClientHassiumStorage storage = ClientChunkHandler.getClientStorage();
        if (storage == null) {
            DebugLogger.warn(LogType.METADATA, "[CLIENT_METADATA] Client storage not initialized, attempting sync init");
            // 回退到同步初始化（仅在此路径发生）
            tryInitStorage(mc);
            storage = ClientChunkHandler.getClientStorage();
            if (storage == null) {
                DebugLogger.error("[CLIENT_METADATA] Client storage initialization failed, skipping metadata");
                return;
            }
            DebugLogger.info(LogType.METADATA, "[CLIENT_METADATA] Client storage initialized successfully");
        }

        HassiumTaskExecutor executor = HassiumTaskExecutor.getClient();
        if (executor != null && executor.isRunning()) {
            DebugLogger.debug(LogType.METADATA, "[CLIENT_METADATA] Using async path for metadata comparison");
            // 异步路径：在后台线程比对元数据
            final ClientHassiumStorage finalStorage = storage;
            final String dimension = packet.dimension();
            final List<ChunkMetadataS2CPacket.MetadataEntry> entries = packet.entries();
            final double playerChunkX = mc.player.getX() / 16.0;
            final double playerChunkZ = mc.player.getZ() / 16.0;

            executor.submitAndCallback(() ->
                    compareMetadata(finalStorage, dimension, entries, playerChunkX, playerChunkZ),
                    ClientMetadataHandler::applyMetadataResult,
                    TaskCategory.BEST_EFFORT);
        } else {
            DebugLogger.debug(LogType.METADATA, "[CLIENT_METADATA] Using sync path for metadata comparison");
            // 同步回退
            double playerChunkX = mc.player.getX() / 16.0;
            double playerChunkZ = mc.player.getZ() / 16.0;
            MetadataResult result = compareMetadata(storage, packet.dimension(), packet.entries(),
                    playerChunkX, playerChunkZ);
            applyMetadataResult(result);
        }
    }

    /**
     * 在后台线程比对元数据（S3 核心）
     * <p>
     * 此方法在后台线程执行，可以安全地进行 region 文件磁盘 I/O。
     *
     * @return 比对结果
     */
    private static MetadataResult compareMetadata(
            ClientHassiumStorage storage,
            String dimension,
            List<ChunkMetadataS2CPacket.MetadataEntry> entries,
            double playerChunkX,
            double playerChunkZ) {

        int totalEntries = entries.size();
        DebugLogger.info(LogType.METADATA, "[COMPARE_METADATA] Starting comparison: {} entries, dimension={}, playerPos=({}, {})",
                totalEntries, dimension, playerChunkX, playerChunkZ);

        List<ChunkPos> hitChunks = new ArrayList<>();
        List<Double> hitDistances = new ArrayList<>();
        List<ChunkPos> missedChunks = new ArrayList<>();
        int hitCount = 0;
        int staleCount = 0;
        int missCount = 0;

        for (ChunkMetadataS2CPacket.MetadataEntry entry : entries) {
            ChunkPos pos = new ChunkPos(entry.chunkX(), entry.chunkZ());

            // 暂存 contentHash，供后续收到区块数据时写入缓存
            ClientChunkHandler.storePendingContentHash(entry.chunkX(), entry.chunkZ(), entry.contentHash());

            double dx = entry.chunkX() - playerChunkX;
            double dz = entry.chunkZ() - playerChunkZ;
            double distance = Math.sqrt(dx * dx + dz * dz);

            // 比对本地缓存：contentHash 相等才命中
            ClientChunkMetadata meta = storage.readMetadata(pos);
            if (meta != null && meta.contentHash() == entry.contentHash()) {
                hitChunks.add(pos);
                hitDistances.add(distance);
                hitCount++;
                NetworkStats.recordCacheHit(ESTIMATED_CHUNK_BYTES);
                DebugLogger.info(LogType.METADATA, "[COMPARE_METADATA] HIT chunk {} (hash={}, dist={})",
                        pos, Long.toHexString(entry.contentHash()), String.format("%.1f", distance));
            } else {
                missedChunks.add(pos);
                if (meta != null) {
                    staleCount++;
                    NetworkStats.recordCacheStale(ESTIMATED_CHUNK_BYTES);
                    DebugLogger.info(LogType.METADATA, "[COMPARE_METADATA] STALE chunk {} (local={}, server={})",
                            pos, Long.toHexString(meta.contentHash()), Long.toHexString(entry.contentHash()));
                } else {
                    missCount++;
                    NetworkStats.recordCacheMiss(ESTIMATED_CHUNK_BYTES);
                    DebugLogger.info(LogType.METADATA, "[COMPARE_METADATA] MISS chunk {} (not cached)", pos);
                }
            }
        }

        DebugLogger.info(LogType.METADATA, "[COMPARE_METADATA] Result: {} hits, {} stale, {} misses (total {})",
                hitCount, staleCount, missCount, totalEntries);

        return new MetadataResult(hitChunks, hitDistances, missedChunks, dimension, totalEntries);
    }

    /**
     * 主线程应用元数据比对结果
     * <p>
     * 通过 MainThreadDispatcher 回到主线程执行。
     */
    private static void applyMetadataResult(MetadataResult result) {
        // 异步回调可能在玩家断开后执行，需要检查连接状态
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) {
            DebugLogger.warn(LogType.METADATA, "[APPLY_METADATA] Player disconnected, skipping result");
            return;
        }

        DebugLogger.info(LogType.METADATA, "[APPLY_METADATA] Applying result: {} hits, {} misses, dimension={}",
                result.hitChunks().size(), result.missedChunks().size(), result.dimension());

        ClientCacheLoadQueue loadQueue = ClientCacheLoadQueue.getInstance();

        // 缓存命中：加入加载队列
        if (!result.hitChunks().isEmpty()) {
            for (int i = 0; i < result.hitChunks().size(); i++) {
                ChunkPos pos = result.hitChunks().get(i);
                double distance = result.hitDistances().get(i);
                loadQueue.enqueue(pos, distance);
                DebugLogger.info(LogType.METADATA, "[APPLY_METADATA] Queued chunk {} for cache loading (distance={})",
                        pos, String.format("%.1f", distance));
            }
            DebugLogger.info(LogType.METADATA, "[APPLY_METADATA] {} chunks queued for cache loading", result.hitChunks().size());
        } else {
            DebugLogger.info(LogType.METADATA, "[APPLY_METADATA] No cache hits to load");
        }

        // 缓存未命中：发送数据请求给服务端
        if (!result.missedChunks().isEmpty()) {
            DebugLogger.info(LogType.METADATA, "[APPLY_METADATA] Requesting {} chunks from server: {}",
                    result.missedChunks().size(), result.missedChunks());

            // 记录发送数据请求
            NetworkStats.recordDataRequestSent();

            ChunkDataRequestC2SPacket request = new ChunkDataRequestC2SPacket(result.dimension(), result.missedChunks());

            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            boolean sent = false;
            try {
                request.encode(buf);
                Services.NETWORK_MANAGER.sendChunkDataRequest(buf);
                sent = true;
                DebugLogger.info(LogType.METADATA, "[APPLY_METADATA] Successfully sent chunk data request to server");
            } catch (Exception e) {
                DebugLogger.error("[APPLY_METADATA] Failed to send chunk data request", e);
            } finally {
                // 只有在发送失败时才释放缓冲区
                // 发送成功时，Fabric/Forge 会接管 buf 的所有权
                if (!sent && buf != null) {
                    buf.release();
                }
            }
        } else {
            DebugLogger.info(LogType.METADATA, "[APPLY_METADATA] No chunks to request from server");
        }
    }

    /**
     * 尝试初始化存储（同步回退路径）
     */
    private static void tryInitStorage(Minecraft mc) {
        try {
            String serverIp = mc.getConnection().getServerData().ip;
            String serverId = "server_" + serverIp.replaceAll("[^a-zA-Z0-9._-]", "_");
            String dimension = mc.player.level().dimension()
#if MC_VER < MC_1_21_11
                    .location()
#else
                    .identifier()
#endif
                    .toString();
            ClientChunkHandler.initStorage(
                    mc.gameDirectory.toPath(),
                    serverId,
                    dimension);
        } catch (Exception e) {
            Constants.LOG.error("Hassium: Failed to initialize client storage", e);
        }
    }

    // ===== 阶段一：chunkHash 比对 =====

    /**
     * 处理阶段一 chunkHash 广播包。
     * <p>
     * 比对本地缓存的 chunkHash：
     * - 匹配 → 缓存命中，从缓存加载 + 请求 blockEntity
     * - 不匹配 → 直接请求全量（section-delta merge 暂禁用）
     */
    public static void handleChunkHashPacket(ChunkHashS2CPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        Constants.LOG.info("[RECV_HASH] Received chunk hash packet: {} entries, dimension={}",
                packet.entries().size(), packet.dimension());

        // 记录收到元数据（估算大小：dimension字符串 + 每条记录约16字节）
        int estimatedSize = packet.dimension().length() + packet.entries().size() * 16 + 8;
        NetworkStats.recordMetadataReceived(estimatedSize);

        ClientHassiumStorage storage = ClientChunkHandler.getClientStorage();
        if (storage == null) {
            DebugLogger.warn(LogType.METADATA, "[CHUNK_HASH] Storage not initialized, requesting full data");
            // 无缓存，直接请求完整数据
            requestFullChunks(packet.dimension(), packet.entries().stream()
                    .map(e -> new ChunkPos(e.chunkX(), e.chunkZ()))
                    .toList());
            return;
        }

        HassiumTaskExecutor executor = HassiumTaskExecutor.getClient();
        if (executor != null && executor.isRunning()) {
            final ClientHassiumStorage finalStorage = storage;
            executor.submitAndCallback(() ->
                    compareChunkHashes(finalStorage, packet),
                    result -> applyChunkHashResult(result, packet.dimension()),
                    TaskCategory.BEST_EFFORT);
        } else {
            ChunkHashResult result = compareChunkHashes(storage, packet);
            applyChunkHashResult(result, packet.dimension());
        }
    }

    /**
     * chunkHash 比对结果
     */
    private record ChunkHashResult(
            List<ChunkPos> hitChunks,
            List<ChunkPos> fullRequestChunks,
            String dimension
    ) {}

    /**
     * 在后台线程比对 chunkHash
     */
    private static ChunkHashResult compareChunkHashes(
            ClientHassiumStorage storage, ChunkHashS2CPacket packet) {
        List<ChunkPos> hitChunks = new ArrayList<>();
        List<ChunkPos> fullRequestChunks = new ArrayList<>();

        for (ChunkHashS2CPacket.Entry entry : packet.entries()) {
            ChunkPos pos = new ChunkPos(entry.chunkX(), entry.chunkZ());
            long cachedChunkHash = storage.readChunkHash(pos);

            if (cachedChunkHash != 0L && cachedChunkHash == entry.chunkHash()) {
                hitChunks.add(pos);
                NetworkStats.recordCacheHit(ESTIMATED_CHUNK_BYTES);
                DebugLogger.info(LogType.METADATA, "[CHUNK_HASH] HIT chunk {} (hash={})",
                        pos, Long.toHexString(entry.chunkHash()));
            } else {
                // section-delta merge 暂不可靠：miss/mismatch 一律全量
                if (cachedChunkHash == 0L) {
                    NetworkStats.recordCacheMiss(ESTIMATED_CHUNK_BYTES);
                    DebugLogger.info(LogType.METADATA, "[CHUNK_HASH] MISS chunk {}", pos);
                } else {
                    NetworkStats.recordCacheStale(ESTIMATED_CHUNK_BYTES);
                    DebugLogger.info(LogType.METADATA, "[CHUNK_HASH] MISMATCH chunk {} (cached={}, server={})",
                            pos, Long.toHexString(cachedChunkHash), Long.toHexString(entry.chunkHash()));
                }
                fullRequestChunks.add(pos);
            }
        }

        DebugLogger.info(LogType.METADATA,
                "[CHUNK_HASH] Result: {} hits, {} full-request (total {})",
                hitChunks.size(), fullRequestChunks.size(), packet.entries().size());

        return new ChunkHashResult(hitChunks, fullRequestChunks, packet.dimension());
    }

    /**
     * 主线程应用 chunkHash 比对结果
     */
    private static void applyChunkHashResult(ChunkHashResult result, String dimension) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return;

        // 缓存命中：从缓存加载；BE 请求延后到区块真正 apply 之后
        if (!result.hitChunks().isEmpty()) {
            DebugLogger.info(LogType.METADATA, "[CHUNK_HASH] {} cache hits, loading from cache",
                    result.hitChunks().size());
            ClientCacheLoadQueue loadQueue = ClientCacheLoadQueue.getInstance();
            for (ChunkPos pos : result.hitChunks()) {
                double dx = pos.x - mc.player.getX() / 16.0;
                double dz = pos.z - mc.player.getZ() / 16.0;
                double distance = Math.sqrt(dx * dx + dz * dz);
                PENDING_BE_REQUESTS.put(ChunkPos.asLong(pos.x, pos.z), dimension);
                loadQueue.enqueue(pos, distance);
            }
        }

        if (!result.fullRequestChunks().isEmpty()) {
            DebugLogger.info(LogType.METADATA,
                    "[CHUNK_HASH] {} mismatches/misses, requesting full chunks (delta merge deferred)",
                    result.fullRequestChunks().size());
            requestFullChunks(dimension, result.fullRequestChunks());
        }
    }

    /**
     * 区块已成功应用到客户端世界后调用。
     * <p>
     * 1. 发送此前登记的 BE 请求（缓存命中路径）
     * 2. 冲刷因竞态暂存的 BE NBT
     */
    public static void onChunkApplied(ChunkPos pos) {
        long key = ChunkPos.asLong(pos.x, pos.z);

        String dimension = PENDING_BE_REQUESTS.remove(key);
        if (dimension != null) {
            requestBlockEntities(dimension, List.of(pos));
        }

        flushPendingBlockEntities(key);
    }

    /**
     * 断开连接时清理待处理状态
     */
    public static void clearPendingState() {
        PENDING_BE_REQUESTS.clear();
        PENDING_BLOCK_ENTITIES.clear();
    }

    /**
     * 请求完整区块数据（无缓存时的回退）
     */
    private static void requestFullChunks(String dimension, List<ChunkPos> chunks) {
        NetworkStats.recordDataRequestSent();
        ChunkDataRequestC2SPacket request = new ChunkDataRequestC2SPacket(dimension, chunks);
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        boolean sent = false;
        try {
            request.encode(buf);
            Services.NETWORK_MANAGER.sendChunkDataRequest(buf);
            sent = true;
        } catch (Exception e) {
            DebugLogger.error("[CHUNK_HASH] Failed to request full chunks", e);
        } finally {
            if (!sent && buf != null) buf.release();
        }
    }

    /**
     * 请求 blockEntity 补发（缓存命中后，blockEntity 不在缓存中）
     */
    private static void requestBlockEntities(String dimension, List<ChunkPos> chunks) {
        NetworkStats.recordDataRequestSent();
        BlockEntityRequestC2SPacket request = new BlockEntityRequestC2SPacket(dimension, chunks);
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        boolean sent = false;
        try {
            request.encode(buf);
            Services.NETWORK_MANAGER.sendBlockEntityRequest(buf);
            sent = true;
            DebugLogger.info(LogType.METADATA, "[BLOCK_ENTITY] Requested block entity data for {} chunks",
                    chunks.size());
        } catch (Exception e) {
            DebugLogger.error("[BLOCK_ENTITY] Failed to request block entities", e);
        } finally {
            if (!sent && buf != null) buf.release();
        }
    }

    // ===== 阶段二：sectionHash 请求和 delta 响应 =====

    /**
     * 发送阶段二 sectionHash 请求到服务端
     */
    private static void sendSectionHashRequest(String dimension,
                                                List<SectionHashRequestC2SPacket.Entry> entries) {
        SectionHashRequestC2SPacket request = new SectionHashRequestC2SPacket(dimension, entries);
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        boolean sent = false;
        try {
            request.encode(buf);
            Services.NETWORK_MANAGER.sendSectionHashRequest(buf);
            sent = true;
            DebugLogger.info(LogType.METADATA, "[SECTION_HASH] Sent section hash request for {} chunks",
                    entries.size());
        } catch (Exception e) {
            DebugLogger.error("[SECTION_HASH] Failed to send section hash request", e);
        } finally {
            if (!sent && buf != null) buf.release();
        }
    }

    /**
     * 处理阶段二 section delta 响应。
     * <p>
     * 服务端比对后发送变更的 section 数据和 blockEntity 数据。
     * 客户端组装：缓存的 sections + 新 sections + blockEntity。
     */
    public static void handleSectionDeltaPacket(SectionDeltaS2CPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        DebugLogger.info(LogType.METADATA, "[SECTION_DELTA] Received delta packet: {} entries, dimension={}",
                packet.entries().size(), packet.dimension());

        for (SectionDeltaS2CPacket.DeltaEntry entry : packet.entries()) {
            try {
                applyDeltaEntry(entry, packet.dimension());
            } catch (Exception e) {
                DebugLogger.error("[SECTION_DELTA] Failed to apply delta for chunk [{}, {}]",
                        entry.chunkX(), entry.chunkZ(), e);
            }
        }
    }

    /**
     * 应用单个 chunk 的 delta 数据。
     * <p>
     * 当前策略：只要有 section 变更就回退全量请求，避免不可靠的 section merge。
     * 仅 BE 补丁（无 section 变更）时走缓存加载 + BE 写入。
     */
    private static void applyDeltaEntry(SectionDeltaS2CPacket.DeltaEntry entry, String dimension) {
        ChunkPos pos = new ChunkPos(entry.chunkX(), entry.chunkZ());

        if (!entry.changedSections().isEmpty()) {
            DebugLogger.info(LogType.METADATA,
                    "[SECTION_DELTA] Chunk {} has {} section changes, requesting full chunk",
                    pos, entry.changedSections().size());
            requestFullChunks(dimension, List.of(pos));
            return;
        }

        // 无 section 变更：只补 BE（若有缓存则先确保区块在世界中）
        byte[] cachedData = ClientChunkHandler.loadChunkDataFromCache(pos);
        List<SectionDeltaS2CPacket.BlockEntityData> blockEntities = entry.blockEntities();
        if (cachedData == null) {
            if (!blockEntities.isEmpty()) {
                // 无缓存无法只靠 BE 重建区块
                requestFullChunks(dimension, List.of(pos));
            }
            return;
        }

        io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher.execute(() -> {
            ClientChunkHandler.applyChunkData(entry.chunkX(), entry.chunkZ(), cachedData, false);
            if (!blockEntities.isEmpty()) {
                applyBlockEntities(entry.chunkX(), entry.chunkZ(), blockEntities);
            }
        }, pos);
    }

    /**
     * 合并变更的 section 数据到缓存数据中
     * <p>
     * 解析缓存 packet 中的 sections 字节数组，按索引替换变更的 section，
     * 再重建完整 packet。变更 section 的格式与原版 LevelChunkSection.write() 一致。
     */
    private static byte[] mergeSectionData(byte[] cachedData,
                                            List<SectionDeltaS2CPacket.SectionData> changedSections) {
        if (changedSections == null || changedSections.isEmpty()) return cachedData;

        // 构建 sectionIndex → blockData 映射
        java.util.Map<Integer, byte[]> changeMap = new java.util.HashMap<>();
        for (SectionDeltaS2CPacket.SectionData s : changedSections) {
            changeMap.put(s.sectionIndex(), s.blockData());
        }

        try {
            return replaceSectionsInPacket(cachedData, changeMap);
        } catch (Exception e) {
            DebugLogger.error("[SECTION_DELTA] Failed to merge section data", e);
            return null;
        }
    }

    /**
     * 替换 packet 字节中指定索引的 section 数据
     * <p>
     * packet 格式：chunkX(4) + chunkZ(4) + heightmaps(NBT) + sectionsSize(varint) + sections(bytes) + ...
     * sections 内部按 LevelChunkSection.write() 格式顺序排列。
     */
    private static byte[] replaceSectionsInPacket(byte[] packetData,
                                                   java.util.Map<Integer, byte[]> changes) throws Exception {
        FriendlyByteBuf src = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(packetData));
        FriendlyByteBuf dst;
        try {
            dst = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer(packetData.length));
        } catch (Exception e) {
            src.release();
            throw e;
        }

        try {
            // 复制 header: chunkX + chunkZ + heightmaps NBT
            dst.writeInt(src.readInt()); // chunkX
            dst.writeInt(src.readInt()); // chunkZ
            dst.writeNbt(src.readNbt()); // heightmaps

            // 读取 sections 字节数组
            int sectionsSize = src.readVarInt();
            if (sectionsSize <= 0 || sectionsSize > src.readableBytes()) {
                // 无 sections 数据，原样返回
                return packetData;
            }
            byte[] sectionsBytes = new byte[sectionsSize];
            src.readBytes(sectionsBytes);

            // 解析各 section 边界
            List<int[]> sectionRanges = parseSectionRanges(sectionsBytes);

            // 构建替换后的 sections 字节数组
            io.netty.buffer.ByteBuf newSectionsBuf = io.netty.buffer.Unpooled.buffer(sectionsSize);
            try {
                int sectionIndex = 0;
                for (int[] range : sectionRanges) {
                    int start = range[0], end = range[1];
                    if (changes.containsKey(sectionIndex)) {
                        // 使用变更数据替换
                        newSectionsBuf.writeBytes(changes.get(sectionIndex));
                    } else {
                        // 保留原始数据
                        newSectionsBuf.writeBytes(sectionsBytes, start, end - start);
                    }
                    sectionIndex++;
                }

                // 写入新的 sections
                byte[] newSections = new byte[newSectionsBuf.readableBytes()];
                newSectionsBuf.readBytes(newSections);
                dst.writeVarInt(newSections.length);
                dst.writeBytes(newSections);
            } finally {
                newSectionsBuf.release();
            }

            // 复制剩余数据（blockEntities + light）
            int remaining = src.readableBytes();
            if (remaining > 0) {
                dst.writeBytes(src, remaining);
            }

            byte[] result = new byte[dst.readableBytes()];
            dst.readBytes(result);
            return result;

        } finally {
            src.release();
            dst.release();
        }
    }

    /**
     * 解析 sections 字节数组中每个 section 的 [start, end) 偏移量
     * <p>
     * section 格式：blockCount(short) + blockStates(PalettedContainer) + biomes(PalettedContainer)
     */
    private static List<int[]> parseSectionRanges(byte[] sectionsBytes) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            throw new IllegalStateException("Client level is null, cannot parse section ranges");
        }
        return ChunkContentHashUtil.parseSectionRanges(
                sectionsBytes, mc.level.getSectionsCount(), mc.level.registryAccess());
    }

    /**
     * 应用 blockEntity 数据到世界
     */
    private static void applyBlockEntities(int chunkX, int chunkZ,
                                            List<SectionDeltaS2CPacket.BlockEntityData> blockEntities) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
        for (SectionDeltaS2CPacket.BlockEntityData beData : blockEntities) {
            tryApplyOrStashBlockEntity(chunkKey, beData.pos(), beData.nbt());
        }
    }

    /**
     * 处理服务端返回的 blockEntity 数据包。
     * <p>
     * 缓存命中后客户端请求 blockEntity 数据，服务端只发送 blockEntity（不含完整区块）。
     */
    public static void handleBlockEntityDataPacket(BlockEntityDataS2CPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        DebugLogger.info(LogType.METADATA, "[BLOCK_ENTITY] Received block entity data: {} chunks, dimension={}",
                packet.entries().size(), packet.dimension());

        for (BlockEntityDataS2CPacket.ChunkBlockEntities entry : packet.entries()) {
            if (!entry.blockEntities().isEmpty()) {
                io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher.execute(() -> {
                    applyBlockEntityDataEntries(entry.chunkX(), entry.chunkZ(), entry.blockEntities());
                }, new ChunkPos(entry.chunkX(), entry.chunkZ()));
            }
        }
    }

    /**
     * 应用 blockEntity 数据条目（来自 BlockEntityDataS2CPacket）
     */
    private static void applyBlockEntityDataEntries(int chunkX, int chunkZ,
                                                     List<BlockEntityDataS2CPacket.BlockEntityData> blockEntities) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
        for (BlockEntityDataS2CPacket.BlockEntityData beData : blockEntities) {
            tryApplyOrStashBlockEntity(chunkKey, beData.pos(), beData.nbt());
        }
    }

    /**
     * 尝试写入 BE；若区块尚未加载则暂存，等 onChunkApplied 时冲刷。
     */
    private static void tryApplyOrStashBlockEntity(long chunkKey, BlockPos pos, CompoundTag nbt) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        try {
            net.minecraft.world.level.block.entity.BlockEntity be = mc.level.getBlockEntity(pos);
            if (be != null) {
                CompoundTag copy = nbt.copy();
                copy.putInt("x", pos.getX());
                copy.putInt("y", pos.getY());
                copy.putInt("z", pos.getZ());
#if MC_VER < MC_1_20_5
                be.load(copy);
#else
                // 1.20.6+: BlockEntity.load() removed, loadAdditional is protected; use reflection
                try {
                    var m = net.minecraft.world.level.block.entity.BlockEntity.class
                            .getDeclaredMethod("loadAdditional",
                                    net.minecraft.nbt.CompoundTag.class,
                                    net.minecraft.core.HolderLookup.Provider.class);
                    m.setAccessible(true);
                    m.invoke(be, copy, be.getLevel().registryAccess());
                } catch (ReflectiveOperationException ex) {
                    DebugLogger.error("[BLOCK_ENTITY] Failed to load block entity via reflection at {}", pos, ex);
                }
#endif
                DebugLogger.info(LogType.METADATA, "[BLOCK_ENTITY] Updated block entity at {}", pos);
            } else {
                PENDING_BLOCK_ENTITIES
                        .computeIfAbsent(chunkKey, k -> new ArrayList<>())
                        .add(new PendingBlockEntityNbt(pos.immutable(), nbt.copy()));
                DebugLogger.info(LogType.METADATA,
                        "[BLOCK_ENTITY] Stashed block entity at {} (chunk not ready)", pos);
            }
        } catch (Exception e) {
            DebugLogger.error("[BLOCK_ENTITY] Failed to apply block entity at {}", pos, e);
        }
    }

    /**
     * 冲刷暂存的 BE 数据到已加载的区块
     */
    private static void flushPendingBlockEntities(long chunkKey) {
        List<PendingBlockEntityNbt> pending = PENDING_BLOCK_ENTITIES.remove(chunkKey);
        if (pending == null || pending.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        for (PendingBlockEntityNbt beData : pending) {
            try {
                net.minecraft.world.level.block.entity.BlockEntity be =
                        mc.level.getBlockEntity(beData.pos());
                if (be != null) {
                    CompoundTag nbt = beData.nbt().copy();
                    nbt.putInt("x", beData.pos().getX());
                    nbt.putInt("y", beData.pos().getY());
                    nbt.putInt("z", beData.pos().getZ());
#if MC_VER < MC_1_20_5
                    be.load(nbt);
#else
                    // 1.20.6+: BlockEntity.load() removed, loadAdditional is protected; use reflection
                    try {
                        var m = net.minecraft.world.level.block.entity.BlockEntity.class
                                .getDeclaredMethod("loadAdditional",
                                        net.minecraft.nbt.CompoundTag.class,
                                        net.minecraft.core.HolderLookup.Provider.class);
                        m.setAccessible(true);
                        m.invoke(be, nbt, be.getLevel().registryAccess());
                    } catch (ReflectiveOperationException ex) {
                        DebugLogger.error("[BLOCK_ENTITY] Failed to load block entity via reflection at {}",
                                beData.pos(), ex);
                    }
#endif
                    DebugLogger.info(LogType.METADATA, "[BLOCK_ENTITY] Flushed pending block entity at {}",
                            beData.pos());
                } else {
                    DebugLogger.warn(LogType.METADATA,
                            "[BLOCK_ENTITY] Pending BE at {} still missing after chunk apply", beData.pos());
                }
            } catch (Exception e) {
                DebugLogger.error("[BLOCK_ENTITY] Failed to flush pending block entity at {}",
                        beData.pos(), e);
            }
        }
    }
}
