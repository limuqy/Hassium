package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.client.ClientSmokeTest;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.compat.LevelCompat;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import io.github.limuqy.mc.hassium.metrics.VanillaZlibEstimator;
import io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService;
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

    /**
     * 已发出、尚未收到数据的全量请求（DimensionKey 复合键 → 维度 + 截止时间）。
     * 服务端出界丢弃/队列积压导致请求石沉大海时兜底重发，杜绝「永久虚空」。
     */
    private static final ConcurrentHashMap<Long, PendingFullRequest> PENDING_FULL_REQUESTS =
            new ConcurrentHashMap<>();

    private record PendingFullRequest(String dimension, long deadlineMs, int retries) {}

    /**
     * 全量请求超时基数：30s（原 8s 固定值与服务端推送吞吐 maxChunksPerTick=5 → 满刻 100/s 不匹配：
     * 1000+ 块全量尾部服务时间必然 &gt;8s → 级联重发风暴，FINDINGS P1 根因之一）。
     * 动态部分：批次大小 × 25ms/块（保守 40/s 服务率，实际满刻 ≈100/s）
     * + 在途请求数 × 10ms/块（多批合包共享服务端队列，尾部等待按在途总量估算）。
     */
    private static final long FULL_REQUEST_TIMEOUT_BASE_MS = 30_000L;

    /** 每批次内每块追加的等待预算（毫秒）。 */
    private static final long FULL_REQUEST_TIMEOUT_PER_CHUNK_MS = 25L;

    /** 每在途请求追加的等待预算（毫秒）。 */
    private static final long FULL_REQUEST_TIMEOUT_PER_INFLIGHT_MS = 10L;

    /** 动态超时上限（毫秒），防极端深队下重发窗口无限拉长。 */
    private static final long FULL_REQUEST_TIMEOUT_MAX_MS = 120_000L;

    /** 全量请求重发上限：超限丢弃登记——服务端直推/vanilla 跟踪仍会送达区块，登记仅用于超时重试兜底。 */
    private static final int FULL_REQUEST_MAX_RETRIES = 3;

    /**
     * 自适应全量请求超时：批次越大 / 在途越多，服务端服务时间越长，窗口随之放大，
     * 保证正常积压永不触发重发（旧 8s 必然误触发 → 重发风暴）。
     */
    private static long fullRequestTimeoutMs(int batchSize, int inFlightBefore) {
        long t = FULL_REQUEST_TIMEOUT_BASE_MS
                + (long) batchSize * FULL_REQUEST_TIMEOUT_PER_CHUNK_MS
                + (long) inFlightBefore * FULL_REQUEST_TIMEOUT_PER_INFLIGHT_MS;
        return Math.min(t, FULL_REQUEST_TIMEOUT_MAX_MS);
    }

    /** 冒烟卡顿诊断：在途全量请求数 + 最近到期剩余毫秒。 */
    public static String stallSnapshot() {
        int n = PENDING_FULL_REQUESTS.size();
        if (n == 0) {
            return "fullReq=0";
        }
        long now = System.currentTimeMillis();
        long nextMs = Long.MAX_VALUE;
        for (PendingFullRequest req : PENDING_FULL_REQUESTS.values()) {
            nextMs = Math.min(nextMs, req.deadlineMs() - now);
        }
        return "fullReq=" + n + " nextMs=" + Math.max(0L, nextMs);
    }

    /**
     * 首登过渡窗口缓冲：SEED_REF 帧。
     * <p>
     * 服务端 login bridge 完成后（finishLoginBridge → resyncTrackedChunks）立即发
     * SeedRef/chunkHash，但客户端 {@code mc.level}/{@code mc.player} 要等到
     * player entered world 才非空——早退 return 会把这些帧静默丢弃，seedgen/hash
     * 驱动的区块加载永不启动。此处 Netty 线程入队，客户端主线程每 tick 由
     * {@link #drainPendingOnWorldReady()} 在 world 就绪后重放。
     * <p>
     * 线程安全：{@code SeedRefS2CPacket} 为 record，decode 后不再被任何方修改
     * （{@code long[] sectionHashes} 构造后只读），可安全跨线程持有。
     */
    private static final ConcurrentLinkedQueue<SeedRefS2CPacket> PENDING_SEED_REFS =
            new ConcurrentLinkedQueue<>();

    /**
     * 首登过渡窗口缓冲：chunkHash 广播帧（语义同上）。
     * <p>
     * 线程安全：{@code ChunkHashS2CPacket} 为 record（String + 不可变 Entry），
     * 可安全跨线程持有。
     */
    private static final ConcurrentLinkedQueue<ChunkHashS2CPacket> PENDING_CHUNK_HASHES =
            new ConcurrentLinkedQueue<>();

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
            // 首登过渡窗口：login bridge 完成后服务端立即发 SeedRef，但客户端 world
            // 要等 player entered world 才就绪——入队缓冲待 drainPendingOnWorldReady
            // 重放（不静默丢弃，否则 seedgen/hash 驱动的区块加载永不启动）。
            PENDING_SEED_REFS.add(packet);
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
        String dimension = LevelCompat.getDimensionId(mc.level);
        requestFullChunks(dimension, List.of(new ChunkPos(packet.chunkX(), packet.chunkZ())), true, 0);
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
        String dimension = LevelCompat.getDimensionId(mc.level);
        requestFullChunks(dimension, List.of(pos), true, 0);
    }

    /**
     * 处理阶段一 chunkHash 广播包。
     * <p>
     * 方案 A：客户端不再比对 hash；hash 仅暂存供影子端读盘比对
     * （R2 磁盘命中判定），区块数据由服务端全量推送。
     */
    public static void handleChunkHashPacket(ChunkHashS2CPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            // 首登过渡窗口：同上，入队缓冲待 world 就绪重放
            PENDING_CHUNK_HASHES.add(packet);
            DebugLogger.info(LogType.NETWORK,
                    "[CHUNK_HASH_TRACE] buffered-before-world dimension={} entries={} payload={}",
                    packet.dimension(), packet.entries().size(), packet.entries());
            return;
        }

        DebugLogger.info(LogType.NETWORK,
                "[CHUNK_HASH_TRACE] dispatch-client dimension={} entries={} payload={}",
                packet.dimension(), packet.entries().size(), packet.entries());

        // 记录收到元数据（估算大小：dimension字符串 + 每条记录约16字节）
        int estimatedSize = packet.dimension().length() + packet.entries().size() * 16 + 8;
        NetworkStats.recordMetadataReceived(estimatedSize);

        ClientChunkPipeline pipeline = ClientChunkPipeline.getInstance();
        for (ChunkHashS2CPacket.Entry entry : packet.entries()) {
            pipeline.storePendingContentHash(packet.dimension(), entry.chunkX(), entry.chunkZ(),
                    entry.chunkHash());
        }

        // 影子端 hash 比对（服务端 bloom hit 只发 hash；影子端决定是否需要数据）：
        // 命中 → 本地回传；不中 → 请求数据。后台池执行（查盘不阻塞 Netty）。
        io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute
                .handleRemoteHashes(packet.dimension(), packet.entries());
    }

    /**
     * 首登缓冲重放（客户端主线程每 tick 由 MixinClientTick 调用）：
     * world（{@code mc.player}/{@code mc.level}）就绪后一次性取出过渡窗口内
     * 缓冲的 SEED_REF/chunkHash 并按原 handler 处理体重放。
     * <p>
     * 先清空队列再遍历重放（poll 到本地快照），避免重入/重复 drain；
     * 重放时 world 已就绪，handler 的早退分支不会再触发。重放期间新到的帧
     * 留在队列，下个 tick 继续 drain，不丢帧。
     */
    public static void drainPendingOnWorldReady() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        List<SeedRefS2CPacket> seedRefs = new ArrayList<>();
        for (SeedRefS2CPacket p; (p = PENDING_SEED_REFS.poll()) != null; ) {
            seedRefs.add(p);
        }
        List<ChunkHashS2CPacket> hashes = new ArrayList<>();
        for (ChunkHashS2CPacket p; (p = PENDING_CHUNK_HASHES.poll()) != null; ) {
            hashes.add(p);
        }
        if (!seedRefs.isEmpty()) {
            DebugLogger.info(LogType.METADATA,
                    "[SEED_REF] World ready — replaying {} buffered seed refs", seedRefs.size());
            for (SeedRefS2CPacket p : seedRefs) {
                handleSeedRefPacket(p);
            }
        }
        if (!hashes.isEmpty()) {
            DebugLogger.info(LogType.METADATA,
                    "[CHUNK_HASH] World ready — replaying {} buffered chunk hash packets", hashes.size());
            for (ChunkHashS2CPacket p : hashes) {
                handleChunkHashPacket(p);
            }
        }
        io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.flushDeferredRemoteHashes();
    }

    /**
     * 断开连接时清空首登缓冲：旧会话过渡窗口内的帧不得残留到下一会话
     * （重连后 resync 会重新推送，残留帧反而可能污染新会话）。
     */
    public static void clearPendingOnDisconnect() {
        PENDING_SEED_REFS.clear();
        PENDING_CHUNK_HASHES.clear();
    }

    /**
     * 超时未收到区块数据的全量请求 → 重发（服务端出界丢弃/积压兜底）。
     * <p>
     * P1 修复：旧逻辑每 8s 无限重发全部超时块 → 级联重发风暴（9 次 347 块 → 过期 1593）。
     * 现按重试次数分组合包（每块重发计数独立递增），超限
     * （{@link #FULL_REQUEST_MAX_RETRIES}）丢弃登记——区块仍由服务端直推/vanilla 跟踪送达，
     * 登记仅用于超时重试兜底，不会因丢弃而虚空。
     */
    public static void tickPendingFullRequestTimeouts() {
        if (PENDING_FULL_REQUESTS.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Map<String, List<ChunkPos>> timedOut = new HashMap<>();
        Map<Long, Integer> retryCounts = new HashMap<>();
        for (var it = PENDING_FULL_REQUESTS.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (now >= e.getValue().deadlineMs()) {
                ChunkPos pos = new ChunkPos(DimensionKey.chunkXOf(e.getKey()),
                        DimensionKey.chunkZOf(e.getKey()));
                timedOut.computeIfAbsent(e.getValue().dimension(), k -> new ArrayList<>()).add(pos);
                retryCounts.put(e.getKey(), e.getValue().retries());
                it.remove();
            }
        }
        for (var e : timedOut.entrySet()) {
            String dim = e.getKey();
            // 按重试次数分组：每块的重发计数独立递增（requestFullChunks 按组登记 retries+1）
            java.util.Map<Integer, List<ChunkPos>> byRetry = new java.util.TreeMap<>();
            int dropped = 0;
            for (ChunkPos pos : e.getValue()) {
                int r = retryCounts.getOrDefault(DimensionKey.key(dim, pos.x, pos.z), 0);
                if (r >= FULL_REQUEST_MAX_RETRIES) {
                    dropped++;
                } else {
                    byRetry.computeIfAbsent(r, k -> new ArrayList<>()).add(pos);
                }
            }
            int retrying = e.getValue().size() - dropped;
            io.github.limuqy.mc.hassium.utils.StallDiag.event(
                    "fullReq timeout n={} retry={} drop={}", e.getValue().size(), retrying, dropped);
            if (dropped > 0) {
                DebugLogger.warn(LogType.METADATA,
                        "[CHUNK_HASH] {} full requests timed out, {} gave up after {} retries (push path still delivers)",
                        e.getValue().size(), dropped, FULL_REQUEST_MAX_RETRIES);
            }
            for (var grp : byRetry.entrySet()) {
                DebugLogger.warn(LogType.METADATA,
                        "[CHUNK_HASH] {} full requests timed out, retrying (attempt {})",
                        grp.getValue().size(), grp.getKey() + 1);
                requestFullChunks(dim, grp.getValue(), true, grp.getKey() + 1);
            }
        }
    }

    /**
     * 区块数据到达后清除对应全量请求登记（handleCompressedChunk 解码后调用）。
     */
    public static void onChunkDataReceived(int chunkX, int chunkZ) {
        io.github.limuqy.mc.hassium.utils.ChunkFlowTiming.recordReceive(chunkX, chunkZ); // T0b 诊断：端到端起点
        // 全量请求登记键 = 复合键；数据到达时以当前客户端维度解析归属（与 requestFullChunks 登记一致）。
        PENDING_FULL_REQUESTS.remove(fullRequestKey(currentDimension(Minecraft.getInstance()), chunkX, chunkZ));
    }

    /** 全量请求登记键：DimensionKey 复合键。 */
    private static long fullRequestKey(String dimension, int chunkX, int chunkZ) {
        return DimensionKey.key(dimension, chunkX, chunkZ);
    }

    /**
     * 登记缓存命中柱：等 {@link #onChunkApplied} 后再向主控拉 BE（随 apply 节奏）。
     */
    public static void scheduleBeRefresh(String dimension, ChunkPos pos) {
        if (dimension == null || pos == null) {
            return;
        }
        PENDING_BE_REQUESTS.put(DimensionKey.key(dimension, pos.x, pos.z), dimension);
    }

    /**
     * 柱已在客户端世界：立即拉 BE（重进视距 / 跳过重复 apply）。
     */
    public static void requestBeRefreshNow(String dimension, List<ChunkPos> chunks) {
        if (dimension == null || chunks == null || chunks.isEmpty()) {
            return;
        }
        for (ChunkPos pos : chunks) {
            PENDING_BE_REQUESTS.remove(DimensionKey.key(dimension, pos.x, pos.z));
        }
        requestBlockEntities(dimension, chunks);
    }

    /**
     * 区块已成功应用到客户端世界后调用。
     * <p>
     * 1. 发送此前登记的 BE 请求（缓存命中路径）
     * 2. 冲刷因竞态暂存的 BE NBT
     */
    public static void onChunkApplied(ChunkPos pos) {
        // apply 时机无维度上下文（vanilla 通道回调）：按客户端当前维度解析登记键，
        // 与 scheduleBeRefresh/requestFullChunks 的登记维度一致。
        String dimension = currentDimension(Minecraft.getInstance());
        long key = DimensionKey.key(dimension, pos.x, pos.z);

        String registered = PENDING_BE_REQUESTS.remove(key);
        if (registered != null) {
            requestBlockEntities(registered, List.of(pos));
        }

        flushPendingBlockEntities(key);
    }

    /**
     * 断开连接时清理待处理状态
     */
    public static void clearPendingState() {
        clearPendingOnDisconnect();
        PENDING_BE_REQUESTS.clear();
        PENDING_BLOCK_ENTITIES.clear();
        PENDING_FULL_REQUESTS.clear();
        // SeedGen 影子服务端登出保活（同 serverId 重进复用；idle/换服再 shutdown）
        io.github.limuqy.mc.hassium.network.seedgen.SeedGenExecutor.getInstance().onDisconnect();
        // 影子光照管线随断连清空（投递/回传；影子服务端由上面 registry park）
        io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.onDisconnect();
    }

    /**
     * 影子端 hash 比对 miss 后的数据请求入口（ShadowLightCompute 后台线程调用；
     * requestFullChunks 内部有 not-in-game 兜底与距离排序）。
     */
    public static void requestFullChunksPublic(String dimension, List<ChunkPos> chunks, boolean staleOrFallback) {
        requestFullChunks(dimension, chunks, staleOrFallback, 0);
    }


    /**
     * 请求完整区块数据（无缓存时的回退；批量合包）。
     * <p>
     * P1 修复：超时窗口按批次/在途量自适应（服务端 maxChunksPerTick=5 → 100/s 吞吐下
     * 1000+ 块尾部服务 ≈18s，旧 8s 固定窗口必然误触发级联重发）；同区块同维度已在途
     * （未过期）→ 去重跳过发包（服务端 KeyedPriorityQueue REPLACE 语义下重复请求零增益）。
     */

    private static void requestFullChunks(String dimension, List<ChunkPos> chunks, boolean staleOrFallback,
                                          int retries) {
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
        chunks = dropOvdFullChunkRequests(chunks);
        if (chunks.isEmpty()) {
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
        // 普通请求按同区块同维度未过期登记去重（服务端 KeyedPriorityQueue REPLACE 语义下重复请求零增益）。
        long now = System.currentTimeMillis();
        int inFlightBefore = PENDING_FULL_REQUESTS.size();
        long deadline = now + fullRequestTimeoutMs(ordered.size(), inFlightBefore);
        List<ChunkPos> toRequest = new ArrayList<>(ordered.size());
        for (ChunkPos pos : ordered) {
            long key = DimensionKey.key(dimension, pos.x, pos.z);
            PendingFullRequest existing = PENDING_FULL_REQUESTS.get(key);
            if (existing != null && existing.deadlineMs() > now
                    && existing.dimension().equals(dimension)) {
                continue;
            }
            PENDING_FULL_REQUESTS.put(key, new PendingFullRequest(dimension, deadline, retries));
            toRequest.add(pos);
        }
        if (toRequest.isEmpty()) {
            return;
        }
        for (List<ChunkPos> batch : ChunkDataRequestC2SPacket.partition(toRequest)) {
            sendFullChunkRequest(dimension, batch, staleOrFallback);
        }
    }

    /**
     * 红线：超视渲染环带只走影子端本地（内存/磁盘/生成），禁止向主控拉全量。
     */
    public static boolean allowFullChunkRequestFromServer(boolean shouldKeepAsRenderOnly) {
        return !shouldKeepAsRenderOnly;
    }

    static List<ChunkPos> dropOvdFullChunkRequests(List<ChunkPos> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        ViewDistanceExtensionService ovd = ViewDistanceExtensionService.getInstance();
        List<ChunkPos> tracked = new ArrayList<>(chunks.size());
        int dropped = 0;
        for (ChunkPos pos : chunks) {
            if (pos == null) {
                continue;
            }
            if (!allowFullChunkRequestFromServer(ovd.shouldKeepAsRenderOnly(pos))) {
                dropped++;
                continue;
            }
            tracked.add(pos);
        }
        if (dropped > 0) {
            DebugLogger.warn(LogType.METADATA,
                    "[CHUNK_HASH] Dropped {} OVD columns from full-chunk request (local shadow only)",
                    dropped);
        }
        return tracked;
    }

    /**
     * 影子端 hash 比对完成后回发回执：hit 柱以空列表 + RESULT_HIT 发送
     * （ShadowLightCompute 后台线程调用）。空列表 HIT 不是拉取请求。
     */
    public static void sendChunkDataResult(String dimension, List<ChunkPos> hits) {
        if (hits == null || hits.isEmpty()) {
            return;
        }
        DebugLogger.info(LogType.NETWORK,
                "[CHUNK_HASH_TRACE] emit-result dimension={} result=hit positions={} transport=empty-hit-list",
                dimension, hits);
        sendChunkDataFrame(dimension, List.of(), ChunkDataRequestC2SPacket.RESULT_HIT);
    }

    private static void sendFullChunkRequest(String dimension, List<ChunkPos> toRequest,
                                             boolean staleOrFallback) {
        sendChunkDataFrame(dimension, toRequest, ChunkDataRequestC2SPacket.RESULT_MISS);
    }

    private static void sendChunkDataFrame(String dimension, List<ChunkPos> chunks, int result) {
        ChunkDataRequestC2SPacket request = new ChunkDataRequestC2SPacket(dimension, chunks, result);
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        boolean sent = false;
        try {
            request.encode(buf);
            DebugLogger.info(LogType.NETWORK,
                    "[CHUNK_HASH_TRACE] emit-result-frame dimension={} result={} chunks={}",
                    dimension, result, chunks);
            Services.NETWORK_MANAGER.sendChunkDataRequest(buf);
            sent = true;
            // HIT 空柱回执只收敛 pending-confirm，不得记成全量拉取。
            if (request.requestsFullChunks()) {
                NetworkStats.recordDataRequestsSent(chunks.size());
            }
        } catch (Exception e) {
            DebugLogger.error("[CHUNK_HASH] Failed to send chunk data frame", e);
        } finally {
            if (!sent && buf != null) buf.release();
        }
    }

    /**
     * 请求 blockEntity 补发（缓存命中后每次另拉；不计入全量 miss 流量）
     */
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
