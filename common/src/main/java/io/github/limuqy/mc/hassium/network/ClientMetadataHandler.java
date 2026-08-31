package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.client.ClientSmokeTest;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.compat.LevelCompat;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import io.github.limuqy.mc.hassium.metrics.VanillaZlibEstimator;
import io.github.limuqy.mc.hassium.concurrent.ChunkDistancePriority;
import io.github.limuqy.mc.hassium.concurrent.HassiumTaskExecutor;
import io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher;
import io.github.limuqy.mc.hassium.concurrent.TaskCategory;
import io.github.limuqy.mc.hassium.platform.Services;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DebugLogger.LogType;
import io.github.limuqy.mc.hassium.utils.DimensionKey;
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
     * 区块已应用到客户端世界后才发送的 BE 请求（DimensionKey 复合键 → dimension）。
     * BE 不进 chunkHash：缓存命中只复用方块，NBT 每次向主控另拉。
     * 避免 BE 包先于缓存区块到达导致 getBlockEntity() 为 null。
     */
    private static final ConcurrentHashMap<Long, String> PENDING_BE_REQUESTS = new ConcurrentHashMap<>();

    /**
     * BE 数据暂存（DimensionKey 复合键）：区块尚未加载时先缓存，apply 后再写入。
     */
    private static final ConcurrentHashMap<Long, List<PendingBlockEntityNbt>> PENDING_BLOCK_ENTITIES =
            new ConcurrentHashMap<>();

    private record PendingBlockEntityNbt(BlockPos pos, CompoundTag nbt) {}


    /** 诊断接口保留兼容格式；区块 admission 不再由客户端维护请求状态。 */
    public static String stallSnapshot() {
        return "fullReq=0";
    }

    /**
     * 首登过渡窗口缓冲：SEED_REF 帧。
     * 服务端 login bridge 完成后客户端由 shadowPull runtime 主动请求区块；
     * SeedRef/其它业务元数据仍按各自队列等待 world 就绪。
     * 驱动的区块加载永不启动。此处 Netty 线程入队，客户端主线程每 tick 由
     * {@link #drainPendingOnWorldReady()} 在 world 就绪后重放。
     * <p>
     * 线程安全：{@code SeedRefS2CPacket} 为 record，decode 后不再被任何方修改
     * （{@code long[] sectionHashes} 构造后只读），可安全跨线程持有。
     */
    private static final ConcurrentLinkedQueue<SeedRefS2CPacket> PENDING_SEED_REFS =
            new ConcurrentLinkedQueue<>();



    /**
     * 处理服务端许可的 SeedRef。本地生成不可用时，立刻请求同维度的权威 FULL；
     * 不允许以 failed 标记或未定义的 vanilla tracking 替代回退。
     */
    public static void handleSeedRefPacket(SeedRefS2CPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            PENDING_SEED_REFS.add(packet);
            return;
        }
        int estimatedSize = 4 + 4 + 8 + 4 + packet.sectionHashes().length * 8;
        NetworkStats.recordMetadataReceived(estimatedSize);

        if (HassiumConfigService.getInstance().isClientSeedGenEnabled()
                && ClientChunkPipeline.getInstance().isServerSeedGenEnabled()
                && ClientChunkPipeline.getInstance().isServerSeedAvailable()
                && io.github.limuqy.mc.hassium.network.seedgen.SeedGenExecutor.getInstance().handleSeedRef(packet)) {
            return;
        }
        String dimension = LevelCompat.getDimensionId(mc.level);
        ChunkPos pos = new ChunkPos(packet.chunkX(), packet.chunkZ());
        if (io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.tryRequestMiss(dimension, pos)) {
            Constants.LOG.warn("[SEED_REF] Local generation unavailable for ({}, {}) -> shadowPull FULL",
                    packet.chunkX(), packet.chunkZ());
            ShadowPullClient.requestFull(dimension, List.of(pos));
        }
    }


    /** world 就绪后仅重放服务端校验通过的 SeedRef。 */
    public static void drainPendingOnWorldReady() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        List<SeedRefS2CPacket> seedRefs = new ArrayList<>();
        for (SeedRefS2CPacket p; (p = PENDING_SEED_REFS.poll()) != null; ) {
            seedRefs.add(p);
        }
        for (SeedRefS2CPacket p : seedRefs) {
            handleSeedRefPacket(p);
        }
    }

    /** 断开连接时清空 SeedRef 过渡缓冲。 */
    public static void clearPendingOnDisconnect() {
        PENDING_SEED_REFS.clear();
    }
    private static void requestBlockEntities(String dimension, List<ChunkPos> chunks) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) {
            DebugLogger.warn(LogType.METADATA,
                    "[BLOCK_ENTITY] Skip BE request — not in game ({} chunks)", chunks.size());
            return;
        }
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

        long chunkKey = DimensionKey.key(currentDimension(mc), chunkX, chunkZ);
        for (BlockEntityDataS2CPacket.BlockEntityData beData : blockEntities) {
            tryApplyOrStashBlockEntity(chunkKey, beData.pos(), beData.nbt());
        }
        io.github.limuqy.mc.hassium.network.seedgen.ShadowSeedServer shadow =
                io.github.limuqy.mc.hassium.network.seedgen.ShadowServerRegistry.getInstance().get();
        if (shadow != null) {
            shadow.applyBlockEntitySnapshot(currentDimension(mc), new ChunkPos(chunkX, chunkZ), blockEntities);
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
            server.applyBlockUpdate(currentDimension(mc), packet);
        } catch (Throwable ignored) {
            // 纯转发：转发异常不得影响 vanilla 包处理（防恶意包）
        }
    }

    /** 客户端当前所在维度 id（{@code namespace:path}；LevelCompat 封装）。 */
    private static String currentDimension(Minecraft mc) {
        return mc == null || mc.level == null ? null : LevelCompat.getDimensionId(mc.level);
    }
}
