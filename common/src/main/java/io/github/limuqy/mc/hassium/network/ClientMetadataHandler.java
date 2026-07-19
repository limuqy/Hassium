package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil;
import io.github.limuqy.mc.hassium.cache.client.ClientCacheLoadQueue;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import io.github.limuqy.mc.hassium.cache.client.ClientHassiumStorage;
import io.github.limuqy.mc.hassium.concurrent.HassiumTaskExecutor;
import io.github.limuqy.mc.hassium.concurrent.TaskCategory;
import io.github.limuqy.mc.hassium.platform.Services;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DebugLogger.LogType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 客户端区块元数据处理器
 * <p>
 * 处理服务端发送的区块元数据包，比对本地缓存决定加载方式。
 * S3: 元数据比对在后台线程执行，避免主线程阻塞在 region 文件磁盘 I/O 上。
 * M2: 缓存存储初始化由 MixinClientPacketListener 在 handleLogin 时异步完成。
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
     * storage 未就绪时暂存的 chunkHash 包；就绪后批量比对，超时再回退全量请求。
     */
    private static final ConcurrentLinkedQueue<ChunkHashS2CPacket> PENDING_HASH_PACKETS =
            new ConcurrentLinkedQueue<>();

    /** 首次因 storage 未就绪而暂存 hash 的时间戳；0 表示当前无等待 */
    private static volatile long pendingHashWaitStartedAtMs = 0L;

    /** storage 就绪等待超时（毫秒），超时后对暂存包回退全量请求 */
    private static final long STORAGE_READY_TIMEOUT_MS = 2_000L;

    /**
     * 已发出、尚未被 SectionDelta 覆盖的 MISMATCH 区块（chunkKey → 维度 + 截止时间）。
     * 服务端 skipped / 超时均回退全量，避免反复 MISMATCH。
     */
    private static final ConcurrentHashMap<Long, PendingDeltaRequest> PENDING_DELTA_REQUESTS =
            new ConcurrentHashMap<>();

    private record PendingDeltaRequest(String dimension, long deadlineMs) {}

    /** 分段增量无响应超时（毫秒） */
    private static final long DELTA_RESPONSE_TIMEOUT_MS = 3_000L;

    // ===== 阶段一：chunkHash 比对 =====

    /**
     * 处理阶段一 chunkHash 广播包。
     * <p>
     * 比对本地缓存的 chunkHash：
     * - 匹配 → 缓存命中，从缓存加载 + 请求 blockEntity
     * - 无缓存 → 全量请求
     * - MISMATCH → {@code clientCache.sectionDeltaEnabled} 开启时走分段增量，否则全量
     */
    public static void handleChunkHashPacket(ChunkHashS2CPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        DebugLogger.info(LogType.METADATA, "[RECV_HASH] Received chunk hash packet: {} entries, dimension={}",
                packet.entries().size(), packet.dimension());

        // 记录收到元数据（估算大小：dimension字符串 + 每条记录约16字节）
        int estimatedSize = packet.dimension().length() + packet.entries().size() * 16 + 8;
        NetworkStats.recordMetadataReceived(estimatedSize);

        ClientHassiumStorage storage = ClientChunkHandler.getClientStorage();
        if (storage == null) {
            // 暂存等待 storage 就绪，避免进服早期假全量请求
            if (pendingHashWaitStartedAtMs == 0L) {
                pendingHashWaitStartedAtMs = System.currentTimeMillis();
            }
            PENDING_HASH_PACKETS.offer(packet);
            DebugLogger.warn(LogType.METADATA,
                    "[CHUNK_HASH] Storage not ready, buffering hash packet (pending={})",
                    PENDING_HASH_PACKETS.size());
            return;
        }

        dispatchChunkHashCompare(storage, packet);
    }

    /**
     * storage 异步初始化完成后调用：冲刷暂存的 hash 包。
     */
    public static void onStorageReady() {
        pendingHashWaitStartedAtMs = 0L;
        flushPendingHashPackets(false);
    }

    /**
     * 每帧检查：storage 已就绪则冲刷；超时则回退全量请求；并冲刷超时的分段增量。
     * <p>
     * 断连后 player/level 为 null，此时不应再 flush，避免向已关闭的连接发包抛
     * {@code Cannot send packets when not in game!}。完整清理由
     * {@link #clearPendingState()} 在 Mixin onDisconnect 中完成。
     */
    public static void tickPendingHashGate() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (!PENDING_HASH_PACKETS.isEmpty()) {
            ClientHassiumStorage storage = ClientChunkHandler.getClientStorage();
            if (storage != null) {
                onStorageReady();
            } else {
                long started = pendingHashWaitStartedAtMs;
                if (started > 0L && System.currentTimeMillis() - started >= STORAGE_READY_TIMEOUT_MS) {
                    DebugLogger.warn(LogType.METADATA,
                            "[CHUNK_HASH] Storage ready timeout ({}ms), falling back to full requests for {} packets",
                            STORAGE_READY_TIMEOUT_MS, PENDING_HASH_PACKETS.size());
                    flushPendingHashPackets(true);
                    pendingHashWaitStartedAtMs = 0L;
                }
            }
        }
        tickPendingDeltaTimeouts();
    }

    /**
     * 超时未收到 SectionDelta 覆盖的区块 → 回退全量。
     */
    private static void tickPendingDeltaTimeouts() {
        if (PENDING_DELTA_REQUESTS.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Map<String, List<ChunkPos>> timedOut = new HashMap<>();
        for (var it = PENDING_DELTA_REQUESTS.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (now >= e.getValue().deadlineMs()) {
                ChunkPos pos = new ChunkPos(e.getKey());
                timedOut.computeIfAbsent(e.getValue().dimension(), k -> new ArrayList<>()).add(pos);
                it.remove();
            }
        }
        for (var e : timedOut.entrySet()) {
            DebugLogger.warn(LogType.METADATA,
                    "[SECTION_DELTA] Timeout waiting for {} chunks, fallback to full", e.getValue().size());
            requestFullChunks(e.getKey(), e.getValue());
        }
    }

    /**
     * 冲刷暂存 hash：正常比对或超时全量请求。
     */
    private static void flushPendingHashPackets(boolean forceFullRequest) {
        ClientHassiumStorage storage = ClientChunkHandler.getClientStorage();
        ChunkHashS2CPacket packet;
        while ((packet = PENDING_HASH_PACKETS.poll()) != null) {
            if (forceFullRequest || storage == null) {
                requestFullChunks(packet.dimension(), packet.entries().stream()
                        .map(e -> new ChunkPos(e.chunkX(), e.chunkZ()))
                        .toList());
            } else {
                dispatchChunkHashCompare(storage, packet);
            }
        }
    }

    /**
     * 将 chunkHash 比对提交到后台或同步执行。
     */
    private static void dispatchChunkHashCompare(ClientHassiumStorage storage, ChunkHashS2CPacket packet) {
        // 同步执行比对：compareChunkHashes 是纯内存操作（readChunkHash 查 Bloom Filter +
        // SectionHashStore，均在内存），不阻塞主线程。
        // 不能提交到 HassiumTaskExecutor：进服时超视渲染会提交数千个 processNextTask
        // （缓存加载）任务到同一 executor，chunkHash 比对任务被淹没导致延迟十数秒，
        // serverVD 内区块无法及时加载（用户看到"脚下10区块加载后等10秒才继续"）。
        ChunkHashResult result = compareChunkHashes(storage, packet);
        applyChunkHashResult(result, packet.dimension());
    }

    /**
     * chunkHash 比对结果
     * <p>
     * 三桶分流：HIT（缓存命中）/ MISS（无缓存，全量）/ MISMATCH（缓存过期，走分段增量）。
     */
    private record ChunkHashResult(
            List<ChunkPos> hitChunks,
            List<ChunkPos> fullRequestChunks,
            List<ChunkPos> deltaRequestChunks,
            String dimension
    ) {}

    /**
     * 在后台线程比对 chunkHash
     */
    private static ChunkHashResult compareChunkHashes(
            ClientHassiumStorage storage, ChunkHashS2CPacket packet) {
        List<ChunkPos> hitChunks = new ArrayList<>();
        List<ChunkPos> fullRequestChunks = new ArrayList<>();
        List<ChunkPos> deltaRequestChunks = new ArrayList<>();

        for (ChunkHashS2CPacket.Entry entry : packet.entries()) {
            ChunkPos pos = new ChunkPos(entry.chunkX(), entry.chunkZ());
            long cachedChunkHash = storage.readChunkHash(pos);

            if (cachedChunkHash != 0L && cachedChunkHash == entry.chunkHash()) {
                hitChunks.add(pos);
                NetworkStats.recordCacheHit(ESTIMATED_CHUNK_BYTES);
                DebugLogger.info(LogType.METADATA, "[CHUNK_HASH] HIT chunk {} (hash={})",
                        pos, Long.toHexString(entry.chunkHash()));
            } else if (cachedChunkHash == 0L) {
                // MISS：无缓存，走全量
                NetworkStats.recordCacheMiss(ESTIMATED_CHUNK_BYTES);
                DebugLogger.info(LogType.METADATA, "[CHUNK_HASH] MISS chunk {}", pos);
                fullRequestChunks.add(pos);
            } else {
                // MISMATCH：开关开启走分段增量，否则全量（见 clientCache.sectionDeltaEnabled）
                NetworkStats.recordCacheStale(ESTIMATED_CHUNK_BYTES);
                if (HassiumConfigService.getInstance().isSectionDeltaEnabled()) {
                    DebugLogger.info(LogType.METADATA,
                            "[CHUNK_HASH] MISMATCH chunk {} (cached={}, server={}) -> delta",
                            pos, Long.toHexString(cachedChunkHash), Long.toHexString(entry.chunkHash()));
                    deltaRequestChunks.add(pos);
                } else {
                    DebugLogger.info(LogType.METADATA,
                            "[CHUNK_HASH] MISMATCH chunk {} (cached={}, server={}) -> full",
                            pos, Long.toHexString(cachedChunkHash), Long.toHexString(entry.chunkHash()));
                    fullRequestChunks.add(pos);
                }
            }
        }

        DebugLogger.info(LogType.METADATA,
                "[CHUNK_HASH] Result: {} hits, {} full-request, {} delta-request (total {})",
                hitChunks.size(), fullRequestChunks.size(), deltaRequestChunks.size(),
                packet.entries().size());

        return new ChunkHashResult(hitChunks, fullRequestChunks, deltaRequestChunks, packet.dimension());
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
                    "[CHUNK_HASH] {} misses, requesting full chunks",
                    result.fullRequestChunks().size());
            requestFullChunks(dimension, result.fullRequestChunks());
        }

        // MISMATCH：发送 sectionHash 请求，服务端比对后回 SectionDeltaS2CPacket（分段增量）
        if (!result.deltaRequestChunks().isEmpty()) {
            DebugLogger.info(LogType.METADATA,
                    "[CHUNK_HASH] {} mismatches, requesting section-delta",
                    result.deltaRequestChunks().size());
            sendSectionHashRequestForChunks(dimension, result.deltaRequestChunks());
        }
    }

    /**
     * 构造并发送 sectionHash 请求（从缓存读 sectionHashes，无则该块回退全量）。
     */
    private static void sendSectionHashRequestForChunks(String dimension, List<ChunkPos> chunks) {
        ClientHassiumStorage storage = ClientChunkHandler.getClientStorage();
        if (storage == null) {
            // storage 不可用，回退全量
            requestFullChunks(dimension, chunks);
            return;
        }
        List<SectionHashRequestC2SPacket.Entry> entries = new ArrayList<>();
        List<ChunkPos> fallback = new ArrayList<>();
        for (ChunkPos pos : chunks) {
            long[] sectionHashes = storage.readSectionHashes(pos);
            if (sectionHashes == null || sectionHashes.length == 0) {
                fallback.add(pos);
            } else {
                entries.add(new SectionHashRequestC2SPacket.Entry(pos.x, pos.z, sectionHashes));
            }
        }
        if (!entries.isEmpty()) {
            sendSectionHashRequest(dimension, entries);
        }
        if (!fallback.isEmpty()) {
            DebugLogger.info(LogType.METADATA,
                    "[CHUNK_HASH] {} chunks have no sectionHashes, fallback to full", fallback.size());
            requestFullChunks(dimension, fallback);
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
        PENDING_HASH_PACKETS.clear();
        PENDING_DELTA_REQUESTS.clear();
        pendingHashWaitStartedAtMs = 0L;
    }

    /**
     * 请求完整区块数据（无缓存时的回退）
     */
    private static void requestFullChunks(String dimension, List<ChunkPos> chunks) {
        // 兜底：断连后不再发包，避免 Cannot send packets when not in game!
        // 异步回调（applyChunkHashResult 等）与 tickPendingHashGate 之间存在竞态，
        // 即使上层已检查，这里仍兜一道。
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) {
            DebugLogger.warn(LogType.METADATA,
                    "[CHUNK_HASH] Skip full chunk request — not in game ({} chunks)",
                    chunks.size());
            return;
        }
        // 按区块数计，避免一批多块只记 1 次导致「全量请求」与日志对不上
        NetworkStats.recordDataRequestsSent(chunks.size());
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
        // 不计入「全量数据请求」——否则 /hassiumc stats 会把每次 HIT 后的 BE 补发误算成 miss 流量
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

    // ===== 阶段二：sectionHash 请求和 delta 响应（MISMATCH 路径，NBT merge）=====

    /**
     * 发送阶段二 sectionHash 请求到服务端。
     * <p>
     * 由 {@link #applyChunkHashResult} 的 MISMATCH 分支调用。服务端比对后回
     * {@link SectionDeltaS2CPacket}，客户端走 {@link #applyDeltaEntry} NBT merge。
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
            NetworkStats.recordSectionDeltaRequestsSent(entries.size());
            long deadline = System.currentTimeMillis() + DELTA_RESPONSE_TIMEOUT_MS;
            for (SectionHashRequestC2SPacket.Entry entry : entries) {
                PENDING_DELTA_REQUESTS.put(
                        ChunkPos.asLong(entry.chunkX(), entry.chunkZ()),
                        new PendingDeltaRequest(dimension, deadline));
            }
            DebugLogger.info(LogType.METADATA, "[SECTION_HASH] Sent section hash request for {} chunks",
                    entries.size());
        } catch (Exception e) {
            DebugLogger.error("[SECTION_HASH] Failed to send section hash request", e);
        } finally {
            if (!sent && buf != null) buf.release();
        }
        if (!sent) {
            List<ChunkPos> fallback = new ArrayList<>(entries.size());
            for (SectionHashRequestC2SPacket.Entry entry : entries) {
                fallback.add(new ChunkPos(entry.chunkX(), entry.chunkZ()));
            }
            requestFullChunks(dimension, fallback);
        }
    }

    /**
     * 处理阶段二分段增量响应。
     * <p>
     * {@code entries} 走 NBT merge；{@code skipped}（超视距等）立即回退全量。
     */
    public static void handleSectionDeltaPacket(SectionDeltaS2CPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        DebugLogger.info(LogType.METADATA,
                "[SECTION_DELTA] Received delta packet: {} entries, {} skipped, dimension={}",
                packet.entries().size(), packet.skipped().size(), packet.dimension());

        if (!packet.entries().isEmpty()) {
            long actualBytes = estimateSectionDeltaPayloadBytes(packet);
            long vanillaBytes = packet.entries().size() * ESTIMATED_CHUNK_BYTES;
            NetworkStats.recordSectionDeltaReceived(packet.entries().size(), vanillaBytes, actualBytes);
        }

        for (SectionDeltaS2CPacket.DeltaEntry entry : packet.entries()) {
            PENDING_DELTA_REQUESTS.remove(ChunkPos.asLong(entry.chunkX(), entry.chunkZ()));
            try {
                applyDeltaEntry(entry, packet.dimension());
            } catch (Exception e) {
                DebugLogger.error("[SECTION_DELTA] Failed to apply delta for chunk [{}, {}]",
                        entry.chunkX(), entry.chunkZ(), e);
            }
        }

        if (!packet.skipped().isEmpty()) {
            List<ChunkPos> fallback = new ArrayList<>(packet.skipped().size());
            for (SectionDeltaS2CPacket.SkippedChunk s : packet.skipped()) {
                PENDING_DELTA_REQUESTS.remove(ChunkPos.asLong(s.chunkX(), s.chunkZ()));
                fallback.add(new ChunkPos(s.chunkX(), s.chunkZ()));
            }
            DebugLogger.info(LogType.METADATA,
                    "[SECTION_DELTA] {} chunks skipped by server, fallback to full", fallback.size());
            requestFullChunks(packet.dimension(), fallback);
        }
    }

    /**
     * 估算分段增量包载荷大小（用于客户端「网络接收」统计）。
     */
    private static long estimateSectionDeltaPayloadBytes(SectionDeltaS2CPacket packet) {
        long bytes = 8L + packet.dimension().length();
        for (SectionDeltaS2CPacket.DeltaEntry entry : packet.entries()) {
            bytes += 8;
            for (SectionDeltaS2CPacket.SectionData sd : entry.changedSections()) {
                bytes += 8L + (sd.blockData() != null ? sd.blockData().length : 0);
            }
            for (SectionDeltaS2CPacket.BlockEntityData be : entry.blockEntities()) {
                // BE：坐标 + 类型字符串 + NBT 粗估
                bytes += 48;
                if (be.type() != null) {
                    bytes += be.type().toString().length();
                }
                if (be.nbt() != null) {
                    bytes += 64;
                }
            }
        }
        bytes += packet.skipped().size() * 8L;
        return bytes;
    }

    /**
     * 应用单个 chunk 的 delta 数据（NBT merge）。
     * <p>
     * 步骤：
     * <ol>
     *   <li>读缓存 NBT（{@link ClientChunkHandler#loadChunkNbtFromCache}）</li>
     *   <li>对每个 changedSection：替换 {@code sections[idx].data}，更新 {@code has_only_air}</li>
     *   <li>BE 全量覆盖：delta 的 BE 列表替换 {@code block_entities}</li>
     *   <li>重算 {@code sectionHashes} + {@code chunkHash} → persist</li>
     *   <li>{@code nbtToPacketBytes} → 主线程 apply</li>
     * </ol>
     * 任一步失败 → {@code requestFullChunks}（保底，不比现在差）。
     */
    private static void applyDeltaEntry(SectionDeltaS2CPacket.DeltaEntry entry, String dimension) {
        ChunkPos pos = new ChunkPos(entry.chunkX(), entry.chunkZ());
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) {
                requestFullChunks(dimension, List.of(pos));
                return;
            }

            // 1. 读缓存 NBT
            net.minecraft.nbt.CompoundTag nbt = ClientChunkHandler.loadChunkNbtFromCache(pos);
            if (nbt == null) {
                DebugLogger.info(LogType.METADATA,
                        "[SECTION_DELTA] No cached NBT for {}, fallback to full", pos);
                requestFullChunks(dimension, List.of(pos));
                return;
            }

            // 2. 替换变更的 sections
            var registryAccess = mc.level.registryAccess();
            net.minecraft.nbt.ListTag sections =
                    io.github.limuqy.mc.hassium.compat.CompoundTagCompat.getList(nbt, "sections");
            for (SectionDeltaS2CPacket.SectionData sd : entry.changedSections()) {
                int idx = sd.sectionIndex();
                ensureSectionsSize(sections, idx + 1, registryAccess);
                net.minecraft.nbt.CompoundTag newSection = new net.minecraft.nbt.CompoundTag();
                boolean hasOnlyAir = checkSectionHasOnlyAir(sd.blockData(), registryAccess);
                newSection.putBoolean("has_only_air", hasOnlyAir);
                newSection.putByteArray("data", sd.blockData());
                sections.set(idx, newSection);
            }
            nbt.put("sections", sections);

            // 3. BE 全量覆盖（写盘）；世界内 BE 在 apply 后走 applyBlockEntities
            if (!entry.blockEntities().isEmpty()) {
                net.minecraft.nbt.ListTag beList = new net.minecraft.nbt.ListTag();
                for (SectionDeltaS2CPacket.BlockEntityData be : entry.blockEntities()) {
                    if (be.nbt() != null) {
                        beList.add(be.nbt());
                    }
                }
                nbt.put("block_entities", beList);
            }

            // 4. 重算 hash + persist
            int sectionCount = mc.level.getSectionsCount();
            nbt.putInt("section_count", sectionCount);
            long[] newSectionHashes = io.github.limuqy.mc.hassium.cache.client.ChunkDiskCodec
                    .computeSectionHashesFromNbt(nbt, sectionCount, registryAccess);
            long newChunkHash = ChunkContentHashUtil.combineSectionHashesFromArray(newSectionHashes);
            byte[] nbtBytes = io.github.limuqy.mc.hassium.cache.client.ChunkDiskCodec.nbtToBytes(nbt);
            if (nbtBytes == null) {
                requestFullChunks(dimension, List.of(pos));
                return;
            }
            ClientChunkHandler.persistToCache(pos, nbtBytes, newChunkHash, newSectionHashes);

            DebugLogger.info(LogType.METADATA,
                    "[SECTION_DELTA] Merged {} sections for {} (newHash={})",
                    entry.changedSections().size(), pos, Long.toHexString(newChunkHash));

            // 5. 主线程 apply + BE（nbtToPacketBytes 不写 BE 线格式）
            byte[] packetBytes = io.github.limuqy.mc.hassium.cache.client.ChunkDiskCodec
                    .nbtToPacketBytes(nbt, registryAccess, sectionCount);
            if (packetBytes == null) {
                requestFullChunks(dimension, List.of(pos));
                return;
            }
            final byte[] finalPacketBytes = packetBytes;
            final List<SectionDeltaS2CPacket.BlockEntityData> bes = entry.blockEntities();
            io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher.execute(() -> {
                ClientChunkHandler.applyChunkData(entry.chunkX(), entry.chunkZ(), finalPacketBytes, false);
                if (!bes.isEmpty()) {
                    applyBlockEntities(entry.chunkX(), entry.chunkZ(), bes);
                }
            }, pos);

        } catch (Throwable t) {
            DebugLogger.warn(LogType.METADATA,
                    "[SECTION_DELTA] Merge failed for {}, fallback to full", pos, t);
            requestFullChunks(dimension, List.of(pos));
        }
    }

    /**
     * 扩容 sections ListTag 到 minSize。
     * <p>
     * 占位必须是合法 {@code LevelChunkSection.write} 字节；禁止 {@code data=[]}，
     * 否则 {@code nbtToPacketBytes} 拼流缺字节导致虚空。
     */
    private static void ensureSectionsSize(net.minecraft.nbt.ListTag sections, int minSize,
                                           net.minecraft.core.RegistryAccess registryAccess) {
        while (sections.size() < minSize) {
            var emptySec = io.github.limuqy.mc.hassium.compat.LevelChunkSectionCompat.create(registryAccess);
            sections.add(io.github.limuqy.mc.hassium.compat.ChunkPacketDataCompat
                    .writeSection(emptySec, registryAccess));
        }
    }

    /** 解析 section 线格式字节，检查是否全空气（用 LevelChunkSectionCompat + ChunkPacketDataCompat）。 */
    private static boolean checkSectionHasOnlyAir(byte[] blockData, net.minecraft.core.RegistryAccess registryAccess) {
        if (blockData == null || blockData.length == 0) return true;
        try {
            net.minecraft.world.level.chunk.LevelChunkSection sec =
                    io.github.limuqy.mc.hassium.compat.LevelChunkSectionCompat.create(registryAccess);
            net.minecraft.nbt.CompoundTag tmp = new net.minecraft.nbt.CompoundTag();
            tmp.putByteArray("data", blockData);
            io.github.limuqy.mc.hassium.compat.ChunkPacketDataCompat.readSectionInto(tmp, sec, registryAccess);
            return sec.hasOnlyAir();
        } catch (Exception e) {
            return false;
        }
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
                io.github.limuqy.mc.hassium.compat.BlockEntityCompat.loadFromTag(
                        be, copy, be.getLevel().registryAccess());
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
                    io.github.limuqy.mc.hassium.compat.BlockEntityCompat.loadFromTag(
                            be, nbt, be.getLevel().registryAccess());
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
