package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.client.ClientSmokeTest;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import io.github.limuqy.mc.hassium.metrics.VanillaZlibEstimator;
import io.github.limuqy.mc.hassium.concurrent.ChunkDistancePriority;
import io.github.limuqy.mc.hassium.concurrent.HassiumTaskExecutor;
import io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher;
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
import java.util.Comparator;
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

    /** 区块平均大小估算（字节），用于缓存命中率/带宽节省按内容计算。与 {@link NetworkStats#ESTIMATED_CHUNK_BYTES} 同源。 */
    private static final long ESTIMATED_CHUNK_BYTES = NetworkStats.ESTIMATED_CHUNK_BYTES; // 16KB
    /** 光照等价字节估算（字节），与 {@link NetworkStats#ESTIMATED_LIGHT_BYTES} 同源；LightDelta 入站 vanilla 等价 wire 累点使用。 */
    private static final long ESTIMATED_LIGHT_BYTES = NetworkStats.ESTIMATED_LIGHT_BYTES; // 16KB

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
     * 已发出、尚未收到数据的全量请求（chunkKey → 维度 + 截止时间）。
     * 服务端出界丢弃/队列积压导致请求石沉大海时兜底重发，杜绝「永久虚空」。
     */
    private static final ConcurrentHashMap<Long, PendingFullRequest> PENDING_FULL_REQUESTS =
            new ConcurrentHashMap<>();

    private record PendingFullRequest(String dimension, long deadlineMs) {}

    /** 全量请求无响应超时（毫秒） */
    private static final long FULL_REQUEST_TIMEOUT_MS = 8_000L;

    // ===== 阶段一：chunkHash 比对 =====

    /**
     * 处理 SeedRef（SeedGen 区块引用）。
     * <p>
     * Phase 2 语义：SeedGenExecutor 接管（本地影子服务端生成 + hash 校验留 Phase 3）；
     * 门控未过/生成失败/超时一律回退全量请求（正确性优先）。
     */
    public static void handleSeedRefPacket(SeedRefS2CPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        int estimatedSize = 4 + 4 + 8 + 4 + packet.sectionHashes().length * 8;
        NetworkStats.recordMetadataReceived(estimatedSize);

        // 门控：服务端未启用 SeedGen / 客户端配置未开启 → 直接回退
        if (!io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance().isClientSeedGenEnabled()
                || !io.github.limuqy.mc.hassium.network.ClientChunkPipeline.getInstance().isServerSeedGenEnabled()) {
            Constants.LOG.info("[SEED_REF] Received ({}, {}) but SeedGen inactive -> fallback full request",
                    packet.chunkX(), packet.chunkZ());
            fallbackToFullRequest(mc, packet);
            return;
        }

        // Phase 2：本地影子服务端生成（入队后异步；未接管则回退）
        if (io.github.limuqy.mc.hassium.network.seedgen.SeedGenExecutor.getInstance().handleSeedRef(packet)) {
            return;
        }
        fallbackToFullRequest(mc, packet);
    }

    /**
     * SeedRef 回退：按当前维度全量请求该区块。
     */
    private static void fallbackToFullRequest(Minecraft mc, SeedRefS2CPacket packet) {
        fallbackToFullRequestByPos(new ChunkPos(packet.chunkX(), packet.chunkZ()));
    }

    /**
     * 公共回退入口（SeedGen 生成线程/超时用）：按当前维度全量请求该区块。
     * 任意线程可调；断连/未进服时内部兜底跳过。
     */
    public static void fallbackToFullRequestByPos(ChunkPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        String dimension = mc.level.dimension()
#if MC_VER < MC_1_21_11
                .location()
#else
                .identifier()
#endif
                .toString();
        requestFullChunks(dimension, List.of(pos), true);
    }

    /**
     * 处理阶段一 chunkHash 广播包。
     * <p>
     * 方案 A：客户端不再比对 hash；hash 仅暂存供影子端读盘比对
     * （R2 磁盘命中判定），区块数据由服务端全量推送。
     */
    public static void handleChunkHashPacket(ChunkHashS2CPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        DebugLogger.info(LogType.METADATA, "[RECV_HASH] Received chunk hash packet: {} entries, dimension={}",
                packet.entries().size(), packet.dimension());

        // 记录收到元数据（估算大小：dimension字符串 + 每条记录约16字节）
        int estimatedSize = packet.dimension().length() + packet.entries().size() * 16 + 8;
        NetworkStats.recordMetadataReceived(estimatedSize);

        ClientChunkPipeline pipeline = ClientChunkPipeline.getInstance();
        for (ChunkHashS2CPacket.Entry entry : packet.entries()) {
            pipeline.storePendingContentHash(entry.chunkX(), entry.chunkZ(), entry.chunkHash());
        }

        // 影子端 hash 比对（服务端 bloom hit 只发 hash；影子端决定是否需要数据）：
        // 命中 → 本地回传；不中 → 请求数据。后台池执行（查盘不阻塞 Netty）。
        io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute
                .handleRemoteHashes(packet.dimension(), packet.entries());
    }

    /**
     * 超时未收到区块数据的全量请求 → 重发（服务端出界丢弃/积压兜底）。
     */
    public static void tickPendingFullRequestTimeouts() {
        if (PENDING_FULL_REQUESTS.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Map<String, List<ChunkPos>> timedOut = new HashMap<>();
        for (var it = PENDING_FULL_REQUESTS.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (now >= e.getValue().deadlineMs()) {
                ChunkPos pos = new ChunkPos(e.getKey());
                timedOut.computeIfAbsent(e.getValue().dimension(), k -> new ArrayList<>()).add(pos);
                it.remove();
            }
        }
        for (var e : timedOut.entrySet()) {
            DebugLogger.warn(LogType.METADATA,
                    "[CHUNK_HASH] {} full requests timed out, retrying", e.getValue().size());
            requestFullChunks(e.getKey(), e.getValue(), true);
        }
    }

    /**
     * 区块数据到达后清除对应全量请求登记（handleCompressedChunk 解码后调用）。
     */
    public static void onChunkDataReceived(int chunkX, int chunkZ) {
        PENDING_FULL_REQUESTS.remove(ChunkPos.asLong(chunkX, chunkZ));
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
        PENDING_FULL_REQUESTS.clear();
        // SeedGen 影子服务端随断连回收（重连后按需重建）
        io.github.limuqy.mc.hassium.network.seedgen.SeedGenExecutor.getInstance().onDisconnect();
        // 影子光照管线随断连清空（投递/回传/失败标记；影子服务端由上面 registry 统一关停）
        io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.onDisconnect();
    }

    /**
     * 影子端 hash 比对 miss 后的数据请求入口（ShadowLightCompute 后台线程调用；
     * requestFullChunks 内部有 not-in-game 兜底与距离排序）。
     */
    public static void requestFullChunksPublic(String dimension, List<ChunkPos> chunks, boolean staleOrFallback) {
        requestFullChunks(dimension, chunks, staleOrFallback);
    }

    /**
     * 请求完整区块数据（无缓存时的回退）
     */
    private static void requestFullChunks(String dimension, List<ChunkPos> chunks, boolean staleOrFallback) {
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
        // 同步刷新主线程调度器的玩家坐标（hash 结果可能在首 tick 前到达）
        MainThreadDispatcher.updatePlayerPosition(mc.player.getX(), mc.player.getZ());
        // 按距玩家距离排序：近处先请求，配合服务端 data 队列距离优先
        List<ChunkPos> ordered = chunks;
        if (chunks.size() > 1) {
            double playerChunkX = mc.player.getX() / 16.0;
            double playerChunkZ = mc.player.getZ() / 16.0;
            ordered = new ArrayList<>(chunks);
            ordered.sort(Comparator.comparingDouble(
                    p -> ChunkDistancePriority.distSq(p, playerChunkX, playerChunkZ)));
        }
        ChunkDataRequestC2SPacket request = new ChunkDataRequestC2SPacket(dimension, ordered);
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        boolean sent = false;
        try {
            request.encode(buf);
            Services.NETWORK_MANAGER.sendChunkDataRequest(buf);
            sent = true;
            // 按区块数计，避免一批多块只记 1 次导致「全量请求」与日志对不上
            NetworkStats.recordDataRequestsSent(ordered.size());
            NetworkStats.recordFullChunkRequests(ordered.size(), ordered.size() * ESTIMATED_CHUNK_BYTES, staleOrFallback);
            // 登记超时重试（服务端出界丢弃/积压时兜底）；收到数据由 onChunkDataReceived 清除
            long deadline = System.currentTimeMillis() + FULL_REQUEST_TIMEOUT_MS;
            for (ChunkPos pos : ordered) {
                PENDING_FULL_REQUESTS.put(
                        ChunkPos.asLong(pos.x, pos.z),
                        new PendingFullRequest(dimension, deadline));
            }
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
                // OP_BLOCK_ENTITY：与 OP_CHUNK_APPLY 同位置互不取代（BE 数据不得顶掉全量 apply）
                io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher.execute(() -> {
                    applyBlockEntityDataEntries(entry.chunkX(), entry.chunkZ(), entry.blockEntities());
                }, io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher.chunkKey(
                        new ChunkPos(entry.chunkX(), entry.chunkZ()),
                        io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher.OP_BLOCK_ENTITY));
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

    // ===== 实体数据转发（T3：客户端只转发不消费）=====

    /**
     * 实体包转发到影子端（MixinClientPacketListener 7 个实体 handler HEAD 注入调用）。
     * <p>
     * 纯转发：不解析包内容、不 cancel vanilla、不做任何实体数据消费——影子端
     * {@code ShadowSeedServer.applyEntityPacket} 内部按 instanceof 分发重建/更新实体。
     * <p>
     * gate：未进服 / 配置关或影子端降级（{@code isClientFeatureGateOpen}）/ 影子端未创建
     * （{@code ShadowServerRegistry#get()} 不触发创建——实体包不应触发影子端创建，
     * 登录流程已创建）→ 静默丢弃。转发调用包 try-catch：恶意/异常包不得打断
     * vanilla 处理。
     */
    public static void forwardEntityPacket(net.minecraft.network.protocol.Packet<?> packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        // 网络/功能 gate：hassiumEngineEnabled 关或影子端创建失败（降级态）→ 静默丢弃
        if (!io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance().isClientFeatureGateOpen()) {
            return;
        }
        io.github.limuqy.mc.hassium.network.seedgen.ShadowSeedServer server =
                io.github.limuqy.mc.hassium.network.seedgen.ShadowServerRegistry.getInstance().get();
        if (server == null) {
            return; // 影子端未就绪/握手未完成（登录流程创建；此处不 getOrCreate）
        }
        try {
            server.applyEntityPacket(packet);
        } catch (Throwable ignored) {
            // 纯转发：转发异常不得影响 vanilla 包处理（防恶意包）
        }
    }

    // ===== 方块更新转发（T2：客户端只转发不消费）=====

    /**
     * 方块更新包转发到影子端（MixinClientPacketListener 三个方块包 handler HEAD 注入调用：
     * handleBlockUpdate / handleChunkBlocksUpdate / handleBlockEntityData）。
     * <p>
     * 纯转发：不解析包内容、不 cancel vanilla——影子端
     * {@code ShadowSeedServer.applyBlockUpdate} 内部按 instanceof 分发应用
     * （setBlock / runUpdates / loadFromTag），使影子端缓存内容 hash 与服务端权威一致
     * （方块变动不再导致进服立即 miss 全量重拉）。
     * <p>
     * gate：未进服 / 配置关（{@code isClientFeatureGateOpen}）→ 静默丢弃。
     * 与 {@link #forwardEntityPacket} 不同，这里用 {@code getOrCreate()}：
     * 方块包可能先于种子握手完成到达（登录后首批方块更新），首次到达即创建影子端，
     * 保证影子端与客户端世界同源；未握手/创建失败返回 null → 静默跳过（断连/未装配）。
     * 转发调用包 try-catch：异常包不得打断 vanilla 处理。
     */
    public static void forwardBlockUpdate(net.minecraft.network.protocol.Packet<?> packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (!io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance().isClientFeatureGateOpen()) {
            return;
        }
        io.github.limuqy.mc.hassium.network.seedgen.ShadowSeedServer server =
                io.github.limuqy.mc.hassium.network.seedgen.ShadowServerRegistry.getInstance().getOrCreate();
        if (server == null) {
            return; // 未握手/创建失败（断连或降级）：静默跳过，hash 比对 miss 兜底
        }
        try {
            server.applyBlockUpdate(packet);
        } catch (Throwable ignored) {
            // 纯转发：转发异常不得影响 vanilla 包处理（防恶意包）
        }
    }
}
