package io.github.limuqy.mc.hassium.network.seedgen;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil;
import io.github.limuqy.mc.hassium.concurrent.ExecutorFactory;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.ClientChunkHandler;
import io.github.limuqy.mc.hassium.network.ClientChunkPipeline;
import io.github.limuqy.mc.hassium.network.ClientMetadataHandler;
import io.github.limuqy.mc.hassium.network.SeedRefS2CPacket;
import io.github.limuqy.mc.hassium.network.sectiondelta.SectionDeltaPlanner;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import io.github.limuqy.mc.hassium.compat.LevelCompat;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DimensionKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * SeedGen 生成执行器（Phase 2）：接收 SeedRef → 影子服务端本地生成 FULL 区块
 * → 按直推同格式压缩 → 喂给 {@link ClientChunkHandler#handleCompressedChunk}（复用解压/应用链）。
 * <p>
 * 线程模型：
 * <ul>
 *   <li>网络线程入队（{@link #enqueue}，非阻塞）</li>
 *   <li>平台线程池（seedGenThreads，CPU 密集：worldgen + 编码 + 压缩，避免虚拟线程超订）</li>
 *   <li>每 worker 一个独立 drain 循环（{@link #activeWorkers} 记账，补足到 seedGenThreads 个），
 *       并行生成（影子端 getChunkFuture 任意线程可调；队列原子取出防重复接管）</li>
 * </ul>
 * 生成失败/超时统一回退全量请求（{@link ClientMetadataHandler#fallbackToFullRequest}）；hash mismatch
 * 走分片增量（{@link ShadowLightCompute#requestSectionDeltas}，delta 链路失败内部兜底全量），正确性优先。
 */
public final class SeedGenExecutor {

    private static final SeedGenExecutor INSTANCE = new SeedGenExecutor();

    private final SeedGenQueue queue = new SeedGenQueue();
    /**
     * P1 节流 + 距离优先：seedref 先入 pendingLive 缓冲，由 drain 按生成完成速率、
     * <b>按当前玩家位置最近优先</b>释放进有界工作队列（{@link SeedGenQueue#peekNearest}），
     * 队列深度恒 ≤ {@link #MAX_WORK_DEPTH}——world-ready 一次性重放 1784 时不再瞬时灌入，
     * 影子端创建/装配/生成不再被洪峰淹没，且尾块在队等待被深度上界约束（超时窗口可准确覆盖）。
     * <p>
     * 不用 FIFO：FIFO 会让「当前视野内刚到达的 SeedRef」排在更早路径/初始 resync 的
     * SeedRef 之后，工作队列 96 槽被远方旧块占满 → 近处块几十秒后才被释放/生成，
     * 落地时玩家已走远被 vanilla 丢弃（Ignoring chunk since it's not in the view range）
     * → 身边持续空洞。
     */
    private final SeedGenQueue pendingLive = new SeedGenQueue();

    /**
     * 盲预生成低优先级缓冲：仅当 pendingLive 无可用条目时才释放（R2 缓存预热不得
     * 挤占活体 SeedGen；盲预生成条目 contentHash=0 永不超时）。
     */
    private final SeedGenQueue pendingPregen = new SeedGenQueue();
    /**
     * SeedRef 维度上下文：key = ChunkPos.asLong → 接收该 SeedRef 时客户端所在维度。
     * <p>
     * SeedRef 协议包不携带维度（REQ 明细8 不新增协议字段）；服务端仅在玩家当前
     * 维度推送该维 SeedRef，故接收时客户端维度即目标维度。队列条目（SeedGenQueue.Entry）
     * 不含维度，生成线程经此表取回；断连/条目出队后清理（防泄漏）。
     */
    private static final ConcurrentHashMap<Long, String> DIMENSION_CONTEXT = new ConcurrentHashMap<>();

    /** 当前客户端维度 id（mc.level 不可用返回 null）。 */
    private static String currentDimension() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) {
            return null;
        }
        return LevelCompat.getDimensionId(mc.level);
    }

    /** 有界工作队列最大深度：96 槽 × 实测生成 p90≈182ms/块 ≈ 17.5s 最坏在队等待。 */
    private static final int MAX_WORK_DEPTH = 96;

    /** 回退请求合包上限：超时/失败回退攒批发送（不逐块单包），服务端 100/s 限速下单包风暴 = P1 诱因。 */
    private static final int FALLBACK_BATCH_MAX = 64;
    /** 盲预生成半径（区块）：进服后主动覆盖 R2 重连视距 VD10（±10 → 441 块），不依赖 SeedRef。
     *  1.21.9+ worldgen 串行（ChunkMap ConsecutiveExecutor），实测 ~6.7 块/s——R1 35s 窗口
     *  可生成 ~230 块（≈59% 覆盖率）；441 全量 ~66s 跨入 R2 窗口续生成。 */
    private static final int PREGEN_RADIUS = 10;
    /** 盲预生成已调度（首个 SeedRef 到达时触发一次；断连重置）。 */
    private final AtomicBoolean pregenScheduled = new AtomicBoolean(false);
    /** P2 诊断埋点（T7）：mismatch dump 总量上限（前 N 块），防 debug 全开时刷屏。 */
    private static final int MISMATCH_DUMP_MAX = 20;
    private static final AtomicInteger mismatchDumpsLogged = new AtomicInteger();
    /** 活跃 drain worker 数（并行生成；新 seedref 到达时补足到配置线程数）。 */
    private final AtomicInteger activeWorkers = new AtomicInteger();
    private volatile ExecutorService pool;

    private SeedGenExecutor() {}

    public static SeedGenExecutor getInstance() {
        return INSTANCE;
    }

    /**
     * 处理一个 SeedRef。返回 true = 已接管（入队，将本地生成）；
     * false = 未接管（门控未过/配置关闭），调用方应回退全量请求。
     */
    public boolean handleSeedRef(SeedRefS2CPacket packet) {
        if (!isEnabled()) {
            return false;
        }
        // 维度门控：非三主维度（自定义维度）不接管——影子端未装配其 level，
        // 本地 worldgen 无法复算 → 回退全量请求（与 PristineRegistry 白名单同源语义）。
        String dimension = currentDimension();
        if (!io.github.limuqy.mc.hassium.utils.DimensionKey.isCacheableDimension(dimension)) {
            DebugLogger.info(DebugLogger.LogType.ASYNC,
                    "[SEEDGEN] Dimension {} not cacheable -> fallback ({}, {})",
                    dimension, packet.chunkX(), packet.chunkZ());
            return false;
        }
        // 节流接管：先入 pendingLive 缓冲不直接进工作队列——由 drain 按生成完成速率、
        // 距玩家最近优先释放（releasePendingWork），world-ready 重放不会一次性灌 1784 进队。
        ChunkPos pos = new ChunkPos(packet.chunkX(), packet.chunkZ());
        // 同 pos 盲预生成条目若还在低优先级缓冲，交给活体 SeedRef 取代（防重复 worldgen）。
        pendingPregen.remove(pos);
        DIMENSION_CONTEXT.put(ChunkPos.asLong(pos.x, pos.z), dimension);
        pendingLive.enqueue(pos, packet.contentHash(), packet.sectionHashes(), packet.deliveryId());
        DebugLogger.info(DebugLogger.LogType.ASYNC, "[SEEDGEN] Claimed ({}, {}) dim={} hash={} (bufferedLive={}, bufferedPregen={}, queue={})",
                packet.chunkX(), packet.chunkZ(), dimension, Long.toHexString(packet.contentHash()),
                pendingLive.size(), pendingPregen.size(), queue.size());
        pump();
        // 首个 SeedRef：影子端已就绪 → 铺开盲预生成队列（幂等，覆盖 R2 重连视距）
        schedulePregen();
        return true;
    }

    /** 门控：客户端配置开启 && 服务端 SeedGen 启用 && 影子端未失败。 */
    private boolean isEnabled() {
        if (ShadowServerRegistry.getInstance().isFailed()) {
            return false;
        }
        HassiumConfigService cfg = HassiumConfigService.getInstance();
        if (!cfg.isClientSeedGenEnabled() || cfg.getSeedGenThreads() <= 0) {
            return false;
        }
        return ClientChunkPipeline.getInstance().isServerSeedGenEnabled();
    }

    /** 断连清理：停池、清队列；影子端 park 保活（同 serverId 重进复用，见 registry）。 */
    public void onDisconnect() {
        queue.clear();
        pendingLive.clear();
        pendingPregen.clear();
        DIMENSION_CONTEXT.clear();
        pregenScheduled.set(false);
        ExecutorService p = pool;
        pool = null;
        if (p != null) {
            p.shutdownNow();
        }
        ShadowServerRegistry.getInstance().parkForReuse();
    }
    /** 影子端就绪回调（ShadowServerRegistry.getOrCreate 创建成功后调用，任意线程）：
     *  铺开盲预生成——不依赖 SeedRef（T3 复验实证：R1 期间服务端可能 0 个 SeedRef，
     *  仅 handleSeedRef 触发时预生成永不铺开 → R1 覆盖率 0）。幂等（pregenScheduled）。 */
    public void onShadowReady() {
        schedulePregen();
    }
    /**
     * 盲预生成（不依赖 SeedRef）：进服后主动把玩家 ±{@link #PREGEN_RADIUS} 内全部块
     * 入队生成（contentHash=0，不校验服务端 hash，直接注入影子端存档）。
     * <p>
     * 动机（M5 实证）：R1 10 秒窗口服务端仅推 45 个 SeedRef（spawn 区 pregen 已完成、
     * 其余块非 pristine 走数据直推）→ 影子端存档仅 45 块 → R2 重连请求 393 块时
     * 覆盖率 11.5% → 命中率 ≈ 覆盖率（实测 11.9%）。盲预生成把影子端存档铺满
     * R2 视距（±10 → 441 块），覆盖率 → ~60%+，R2 读盘比对/增量命中随之提升。
     * <p>
     * 与服务端一致性：同种子同算法（M4 双端 region 对比已证大多数块逐 section 一致），
     * feature 随机与邻域无关（M4e 核实）；差异仅为服务端 tick 演化（lava/植被），
     * 走 R2 增量命中兜底。SeedRef 条目（hash≠0）在队列中优先（peekNearest 同距优先）。
     */
    private void schedulePregen() {
        // 门控（与 handleSeedRef 的 isEnabled 同源）：客户端配置关 / 线程 0 /
        // 服务端未启用 SeedGen 时绝不铺开盲预生成——否则影子端就绪即对 ±PREGEN_RADIUS
        // 全量本地 worldgen（441 块），生成结果还会回传客户端覆盖服务端权威数据
        // （放置方块消失 / 区块突变现场）。
        if (!isEnabled()) {
            return;
        }
        // 维度门控（与 handleSeedRef 同源）：自定义维度不铺开盲预生成。
        String dimension = currentDimension();
        if (!DimensionKey.isCacheableDimension(dimension)) {
            return;
        }
        if (!pregenScheduled.compareAndSet(false, true)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            pregenScheduled.set(false); // 未进服：等下一个 SeedRef 再试
            return;
        }
        ChunkPos center = mc.player.chunkPosition();
        int n = 0;
        for (int dx = -PREGEN_RADIUS; dx <= PREGEN_RADIUS; dx++) {
            for (int dz = -PREGEN_RADIUS; dz <= PREGEN_RADIUS; dz++) {
                ChunkPos pos = new ChunkPos(center.x + dx, center.z + dz);
                // 走 pendingPregen 低优先级缓冲：仅当无活体 SeedRef 时由 releasePendingWork
                // 释放（盲预生成不挤占 SeedGen）；441 块若直接入队会撑爆 MAX_WORK_DEPTH=96。
                // 去重：工作队列 / 活体缓冲 / 低优先级缓冲已有该柱则跳过。
                if (queue.isPending(pos) || pendingLive.isPending(pos) || pendingPregen.isPending(pos)) {
                    continue;
                }
                pendingPregen.enqueue(pos, 0L, new long[0]);
                DIMENSION_CONTEXT.put(ChunkPos.asLong(pos.x, pos.z), dimension);
                n++;
            }
        }
        DebugLogger.info(DebugLogger.LogType.ASYNC,
                "[SEEDGEN] Blind pregen scheduled: {} chunks around ({}, {}) (bufferedPregen={}, queue={})",
                n, center.x, center.z, pendingPregen.size(), queue.size());
        pump();
    }

    /** 触发 drain：补足活跃 worker 到配置线程数（默认 2，并行生成；CAS 语义由 activeWorkers 记账承担）。 */
    private void pump() {
        ExecutorService p = pool;
        if (p == null || p.isShutdown()) {
            synchronized (this) {
                p = pool;
                if (p == null || p.isShutdown()) {
                    HassiumConfigService cfg = HassiumConfigService.getInstance();
                    p = ExecutorFactory.createPlatform("hassium-seedgen",
                            Math.max(1, cfg.getSeedGenThreads()));
                    pool = p;
                }
            }
        }
        int target = Math.max(1, HassiumConfigService.getInstance().getSeedGenThreads());
        while (activeWorkers.get() < target) {
            activeWorkers.incrementAndGet();
            try {
                p.submit(this::drain);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                activeWorkers.decrementAndGet(); // 池已停（断连竞态），丢弃本次
                break;
            }
        }
    }
    /** 工作循环（每 worker 一份）：释放缓冲 → 原子取最近未超时条目 → 生成 → 编码 → 压缩 → 交给客户端链；空则退出。 */
    private void drain() {
        // 回退请求聚合缓冲：超时/失败回退攒批发送（不逐块单包），防 1000+ 单包风暴（P1）
        List<FallbackRequest> fallbackBuffer = new ArrayList<>();
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player == null || mc.level == null) {
                    break; // 断连/未进服：剩余条目由 onDisconnect 清空
                }
                int playerChunkX = mc.player.chunkPosition().x;
                int playerChunkZ = mc.player.chunkPosition().z;
                // 工作队列超时条目批量回退（expire 已移除；聚合攒批，flush 时合包）
                for (SeedGenQueue.Entry expired : queue.expire()) {
                    addFallback(fallbackBuffer, expired);
                }
                // 活体缓冲超时同样回退：超时从 SeedRef 到达即起算（FIFO 旧实现要等释放进
                // 工作队列才起算，尾部条目最长可被额外拖 30s+ 才有兜底）。
                for (SeedGenQueue.Entry expired : pendingLive.expire()) {
                    addFallback(fallbackBuffer, expired);
                }
                if (fallbackBuffer.size() >= FALLBACK_BATCH_MAX) {
                    flushFallback(fallbackBuffer);
                }
                releasePendingWork(playerChunkX, playerChunkZ);
                SeedGenQueue.Entry entry = queue.peekNearest(playerChunkX, playerChunkZ);
                if (entry == null) {
                    break;
                }
                // 原子取出：多 worker 并行时防重复接管同一条目（已被其他 worker 取走则跳过）
                if (!queue.tryTake(entry)) {
                    continue;
                }
                generateOne(entry, fallbackBuffer);
                if (fallbackBuffer.size() >= FALLBACK_BATCH_MAX) {
                    flushFallback(fallbackBuffer);
                }
            }
        } finally {
            flushFallback(fallbackBuffer);
            activeWorkers.decrementAndGet();
            // 竞态窗口：worker 退出瞬间有新条目 → 重新触发（补足 worker 数）
            if ((!queue.isEmpty() || !pendingLive.isEmpty() || !pendingPregen.isEmpty())
                    && !ShadowServerRegistry.getInstance().isFailed()) {
                pump();
            }
        }
    }

    /**
     * 从两级缓冲释放 seedref 进有界工作队列：队列恒 ≤ {@link #MAX_WORK_DEPTH} 槽，
     * 实际入队速率 ≈ 生成完成速率（生成完 1 块才释放 1 块）——world-ready 一次性重放
     * 1784 不再瞬时灌入，影子端装配/生成不被洪峰淹没；尾块在队等待被深度上界约束，
     * 使 SeedGenQueue 自适应超时窗口可准确覆盖（P1）。
     * <p>
     * 释放顺序：活体 SeedRef 永远先于盲预生成；两者内部都按「距当前玩家最近优先」
     * 而不是到达顺序（FIFO）。这消除头部阻塞：玩家快速移动时，刚到达的当前视野
     * SeedRef 不再排在几百个旧路径 SeedRef / 441 个盲预生成条目之后。
     */
    private void releasePendingWork(int playerChunkX, int playerChunkZ) {
        releasePendingWork(queue, pendingLive, pendingPregen, playerChunkX, playerChunkZ, MAX_WORK_DEPTH);
    }

    /** 纯逻辑静态版本（单测可用）：活体优先、按当前玩家位置最近优先地从两级缓冲释放进工作队列。 */
    static void releasePendingWork(SeedGenQueue workQueue, SeedGenQueue liveQueue, SeedGenQueue pregenQueue,
                                   int playerChunkX, int playerChunkZ, int maxWorkDepth) {
        int released = 0;
        while (workQueue.size() < maxWorkDepth && released < maxWorkDepth) {
            SeedGenQueue.Entry entry = liveQueue.peekNearest(playerChunkX, playerChunkZ);
            SeedGenQueue source = liveQueue;
            if (entry == null) {
                entry = pregenQueue.peekNearest(playerChunkX, playerChunkZ);
                source = pregenQueue;
            }
            if (entry == null) {
                break;
            }
            // 原子认领：多 worker 并行释放时防重复接管同一条目；失败重试下一轮。
            if (!source.tryTake(entry)) {
                continue;
            }
            workQueue.enqueue(entry.pos(), entry.contentHash(), entry.sectionHashes(), entry.deliveryId());
            released++;
            DebugLogger.info(DebugLogger.LogType.ASYNC,
                    "[SEEDGEN] Released ({}, {}) into work queue (queue={}, live={}, pregen={})",
                    entry.pos().x, entry.pos().z, workQueue.size(),
                    liveQueue.size(), pregenQueue.size());
        }
    }

    /** SeedRef 回退必须携带原 deliveryId 与维度；盲预生成等无 admission 的条目为 0。 */
    private record FallbackRequest(ChunkPos pos, long deliveryId, String dimension) {}

    /** 登记回退：出队 + 攒批（实际发送由 {@link #flushFallback} 按批合包）。 */
    private void addFallback(List<FallbackRequest> buffer, SeedGenQueue.Entry entry) {
        addFallback(buffer, entry.pos(), entry.deliveryId(), dimensionOf(entry.pos()));
    }

    private void addFallback(List<FallbackRequest> buffer, ChunkPos pos, long deliveryId, String dimension) {
        queue.remove(pos);
        DIMENSION_CONTEXT.remove(ChunkPos.asLong(pos.x, pos.z));
        buffer.add(new FallbackRequest(pos, deliveryId, dimension));
    }

    /**
     * 攒批发送回退全量请求：无 deliveryId 的条目按 {@link #FALLBACK_BATCH_MAX} 合包；
     * 正 deliveryId 必须单柱直发，否则服务端无法释放 SeedRef reservation。
     * 断连时清缓冲丢弃。
     */
    private void flushFallback(List<FallbackRequest> buffer) {
        if (buffer.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            buffer.clear();
            return;
        }
        // 回退请求按条目自身维度路由（SeedRef 接收时捕获），不再取当前维度——
        // 玩家跨维瞬间回退批次仍发往原维度，服务端按 task.dimension 落对 level。
        List<ChunkPos> toRequest = new ArrayList<>(buffer.size());
        String batchDimension = null;
        for (FallbackRequest item : buffer) {
            if (batchDimension == null) {
                batchDimension = item.dimension();
            }
            if (item.deliveryId() > 0L) {
                ShadowLightCompute.tryRequestMiss(item.pos());
                DebugLogger.warn(DebugLogger.LogType.ASYNC,
                        "[SEEDGEN] Fallback ({}, {}) dim={} deliveryId={}",
                        item.pos().x, item.pos().z, item.dimension(), item.deliveryId());
                ClientMetadataHandler.requestFullChunksPublic(
                        item.dimension(), List.of(item.pos()), false, item.deliveryId());
            } else if (ShadowLightCompute.tryRequestMiss(item.pos())) {
                toRequest.add(item.pos());
            }
        }
        buffer.clear();
        if (toRequest.isEmpty()) {
            return;
        }
        for (int i = 0; i < toRequest.size(); i += FALLBACK_BATCH_MAX) {
            int end = Math.min(i + FALLBACK_BATCH_MAX, toRequest.size());
            List<ChunkPos> batch = new ArrayList<>(toRequest.subList(i, end));
            DebugLogger.warn(DebugLogger.LogType.ASYNC,
                    "[SEEDGEN] Fallback batch: {} chunks dim={} (new-path, deduped)",
                    batch.size(), batchDimension);
            ClientMetadataHandler.requestFullChunksPublic(batchDimension, batch, false);
        }
    }

    /** 条目维度（SeedRef 接收时捕获；缺失兜底当前维度——盲预生成断连重放等边缘）。 */
    private static String dimensionOf(ChunkPos pos) {
        String dim = DIMENSION_CONTEXT.get(ChunkPos.asLong(pos.x, pos.z));
        return dim != null ? dim : currentDimension();
    }

    private void generateOne(SeedGenQueue.Entry entry, List<FallbackRequest> fallbackBuffer) {
        ChunkPos pos = entry.pos();
        // 条目维度路由：SeedRef 接收时捕获的维度（服务端仅推玩家所在维度的 SeedRef，
        // 该维度即生成目标）。影子端按此取对应 ServerLevel 做 worldgen 与注入。
        String dimension = dimensionOf(pos);
        try {
            ShadowSeedServer server = shadowServer();
            if (server == null || server.level(dimension) == null) {
                addFallback(fallbackBuffer, entry);
                return;
            }
            long t0 = System.nanoTime();
            // 缓存预判（生成前采样）：目标柱已在影子缓存（内存注入表 / 盘 contentHash 表）
            // → generateChunk 将走内存/盘命中而非本地 worldgen。盲预生成路径据此区分
            // 「缓存服务」与「本地补生成」，仅后者计入 locallyGenerated——否则 R2 重连
            // 对已缓存柱重铺盲预生成时，计数被缓存命中灌满（G4 门禁语义：
            // 影子端全量命中时 counters.locallyGenerated 必须为 0）。
            boolean cacheServed = server.injectedChunk(dimension, pos.x, pos.z) != null
                    || io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.get(dimension, pos) != null;
            LevelChunk chunk = server.generateChunk(dimension, pos);
            if (chunk == null) {
                if (entry.contentHash() == 0L) {
                    // 盲预生成（无服务端对应推送）：超时/失败静默跳过，不回退全量
                    DebugLogger.warn(DebugLogger.LogType.ASYNC,
                            "[SEEDGEN] Blind pregen timeout/failed ({}, {})", pos.x, pos.z);
                    DIMENSION_CONTEXT.remove(ChunkPos.asLong(pos.x, pos.z));
                    return;
                }
                DebugLogger.warn(DebugLogger.LogType.ASYNC,
                        "[SEEDGEN] Generation timeout/failed ({}, {}) -> fallback", pos.x, pos.z);
                addFallback(fallbackBuffer, entry);
                return;
            }
            if (entry.contentHash() == 0L) {
                // 盲预生成（无服务端 hash）：仅注入影子端缓存——内存表（injectedChunks）
                // → 断连 saveAll 落盘 → R2 读盘比对/增量基线。**绝不回传客户端世界**：
                // 生成结果未经服务端校验（服务端对应块可能已放置方块/被修改），回传会
                // 用 pristine 地形覆盖服务端权威数据（放置方块消失 = 区块突变/方块缺失
                // 现场）。此前误走 submitGenerated → generated → pushReady → 官方通道，
                // 且产物不入注入表（既不落盘也服务不了 R2，双重失效）。
                if (!cacheServed) {
                    NetworkStats.recordLocallyGeneratedChunk(NetworkStats.ESTIMATED_CHUNK_BYTES);
                }
                server.injectLoadedChunk(dimension, pos, chunk, true);
                ShadowCacheEviction.recordAccess(dimension, pos);
                // 光收敛性无保证（生成时邻域仅 BIOMES 空壳，边界光欠）→ isLightCorrect=false：
                // 落盘省略 isLightOn，R2 读盘走 lightChunk(false)；内存命中经屏障重算。
                chunk.setLightCorrect(false);
                // 计算并登记 contentHash（与 injectChunk 同款）：后续 hash 比对/R2 落盘复用；
                // 失败则比对路径仅在表缺失时现算兜底（chunkHashOf/diskHashMatches）。
                try {
                    long pregenHash = ChunkContentHashUtil.combineSectionHashes(
                            ChunkContentHashUtil.computeSectionHashes(chunk));
                    io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.put(dimension, pos, pregenHash);
                } catch (Throwable hashError) {
                    DebugLogger.debug(DebugLogger.LogType.ASYNC,
                            "[SEEDGEN] Blind pregen hash compute failed ({}, {})", pos.x, pos.z);
                }
                DIMENSION_CONTEXT.remove(ChunkPos.asLong(pos.x, pos.z));
                return;
            }
            // 生成后 chunkHash 校验：与服务端 SeedRef 下发 hash 比对（同 ChunkContentHashUtil
            // 算法，服务端 packet 路径与客户端内存路径等价性有保证）；不匹配 = 本地 worldgen
            // 与服务器不一致（自定义 datapack 缺失等）→ 本地块作基线走分片增量（服务端按 section 回补），不产出错误地形。
            final long localHash;
            final Map<Integer, Long> localSectionHashes;
            try {
                localSectionHashes = ChunkContentHashUtil.computeSectionHashes(chunk);
                localHash = ChunkContentHashUtil.combineSectionHashes(localSectionHashes);
            } catch (Throwable hashError) {
                addFallback(fallbackBuffer, entry);
                return;
            }
            if (localHash != entry.contentHash()) {
                DebugLogger.warn(DebugLogger.LogType.ASYNC,
                        "[SEEDGEN] Hash mismatch ({}, {}): local={} server={} -> full-request fallback",
                        pos.x, pos.z, Long.toHexString(localHash), Long.toHexString(entry.contentHash()));
                // P2 诊断埋点（T7）：mismatch 数据 dump——逐 section hash 对比 + 首个差异
                // section 块状态摘要 + 影子端 seed/LevelStem 身份摘要，供下轮定案 P2 机制。
                dumpMismatchDiagnostics(pos, entry, chunk, localSectionHashes, localHash, server, dimension);
                // P2 缓解（T7）：不匹配回退改走 new 请求路径（hash-miss 正轨，注入数据路径
                // hash 忠实已被 R2 磁盘命中实证）——预判全量分支不再注入本地块作 delta 基线，
                // 也不再 stale=true 请求。保留 delta 预判（>=75% 变更服务端必跳过 delta →
                // 直接 full）；<75% 仍可先试 delta（本地块作基线），delta 链失败兜底
                // （发送失败/超时/skipped/apply 失败）已统一改走 new 路径。
                queue.remove(pos);
                if (wouldServerSkipDelta(entry, localSectionHashes)) {
                    DebugLogger.warn(DebugLogger.LogType.ASYNC,
                            "[SEEDGEN] Delta preempted ({}, {}): >= {}% non-empty sections differ -> direct full request",
                            pos.x, pos.z, SectionDeltaPlanner.FALLBACK_THRESHOLD_PCT);
                    ShadowLightCompute.tryRequestMiss(pos);
                    ClientMetadataHandler.requestFullChunksPublic(
                            dimension, List.of(pos), false, entry.deliveryId());
                } else {
                    server.injectLoadedChunk(dimension, pos, chunk, true);
                    ShadowLightCompute.requestSectionDeltas(dimension, List.of(pos));
                }
                return;
            }
            ServerLevel level = server.level(dimension);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            DebugLogger.info(DebugLogger.LogType.ASYNC, "[SEEDGEN] Generated ({}, {}) in {}ms",
                    pos.x, pos.z, ms);
            // 计数口径与盲预生成分支（上方 !cacheServed）同源：R2 重连时服务端对 pristine
            // 柱无视客户端缓存仍推 SeedRef，此处 generateChunk 实为影子缓存内存取回
            // （park 复用保留 injectedChunks/HASHES，零 worldgen）——cacheServed 时不得计入
            // locallyGenerated（G4 门禁：影子端全量命中时必须为 0），否则间歇性 FAIL。
            if (!cacheServed) {
                NetworkStats.recordLocallyGeneratedChunk(NetworkStats.ESTIMATED_CHUNK_BYTES);
            }
            // 统一影子通道：等光收敛（原版生成后算光同款逻辑）→ 打包官方包 →
            // 官方通道落地（客户端不参与缓存/光照）。
            // review-fix: T3-51：投递失败（并发降级 isEnabled=false）→ 回退全量，
            // 防止生成结果静默丢弃后该柱客户端虚空
            if (!ShadowLightCompute.submitGenerated(pos, chunk, level, entry.deliveryId())) {
                addFallback(fallbackBuffer, entry);
                return;
            }
            queue.remove(pos);
            DIMENSION_CONTEXT.remove(ChunkPos.asLong(pos.x, pos.z));
            ClientMetadataHandler.scheduleBeRefresh(dimension, pos);
        } catch (Exception e) {
            Constants.LOG.error("Hassium: SeedGen generation failed for {}", pos, e);
            addFallback(fallbackBuffer, entry);
        }
    }

    /**
     * 镜像服务端 section-delta 回退判定：按完整索引比对双方
     * section 哈希（0 = 空 section），「变更 section × 100 / 服务端非空 section 数 ≥ 75%」时
     * 服务端跳过 delta 回退全量。客户端持有 SeedRef 下发的服务端 sectionHashes 与本地生成哈希，
     * 可精确预判；无服务端哈希（空数组）时维持原 delta 路径由服务端自行判定。
     */
    private static boolean wouldServerSkipDelta(SeedGenQueue.Entry entry,
                                                Map<Integer, Long> localSectionHashes) {
        long[] serverHashes = entry.sectionHashes();
        if (serverHashes == null || serverHashes.length == 0) {
            return false;
        }
        long[] localHashes = ChunkContentHashUtil.sectionHashesToArray(localSectionHashes);
        return SectionDeltaPlanner.shouldFallbackFullChunk(localHashes, serverHashes);
    }

    /**
     * P2 诊断埋点（T7）：mismatch 分支一次性 dump 生成上下文数据，供下轮定案 P2 机制
     * （整柱平移 = seed/装配问题 vs 稀疏差异 = feature 放置问题 vs 仅部分 section =
     * biome/高度图边界）。DebugLogger（LogType.ASYNC，debug.asyncLogging 配置门控）；
     * 首次 mismatch 每 chunk 一条，总量限制前 {@link #MISMATCH_DUMP_MAX} 块防刷屏。内容：
     * <ul>
     *   <li>影子端 worldgen 身份：level.getSeed() vs 服务端下发 seed + generator 类名 +
     *       NoiseGeneratorSettings holder key/关键字段（LevelStem 装配差异直接现形）</li>
     *   <li>逐 section hash 对比：服务端 SeedRef 下发 sectionHashes[] vs 本地生成
     *       （前 8 个差异 + 差异总数）</li>
     *   <li>首个差异 section 块状态摘要：sectionY + hasOnlyAir + nonAir 块数 + distinct block 数</li>
     * </ul>
     */
    private static void dumpMismatchDiagnostics(ChunkPos pos, SeedGenQueue.Entry entry,
                                                LevelChunk chunk, Map<Integer, Long> localSectionHashes,
                                                long localHash, ShadowSeedServer server, String dimension) {
        if (!DebugLogger.isEnabled(DebugLogger.LogType.ASYNC)) {
            return;
        }
        if (mismatchDumpsLogged.getAndIncrement() >= MISMATCH_DUMP_MAX) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder(256);
            sb.append("[SEEDGEN][DIAG] mismatch (").append(pos.x).append(", ").append(pos.z).append(") ");
            // 影子端 worldgen 身份摘要：level seed vs 服务端下发 seed（seed 装配差异直接现形）
            ServerLevel level = server.level(dimension);
            sb.append("shadowSeed=").append(level.getSeed())
                    .append("(serverSeed=")
                    .append(ClientChunkPipeline.getInstance().getServerSeed())
                    .append(')');
            // LevelStem generator 身份摘要（generator 消费自服务端握手 LevelStem NBT）
            net.minecraft.world.level.chunk.ChunkGenerator generator = level.getChunkSource().getGenerator();
            sb.append(" generator=").append(generator.getClass().getSimpleName());
            if (generator instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator nbcg) {
                net.minecraft.core.Holder<net.minecraft.world.level.levelgen.NoiseGeneratorSettings> holder =
                        nbcg.generatorSettings();
                String settingsKey = holder.unwrapKey()
                        .map(LevelCompat::keyId)
                        .orElse("(unregistered)");
                net.minecraft.world.level.levelgen.NoiseGeneratorSettings ns = holder.value();
                sb.append(" noiseSettings=").append(settingsKey)
                        .append("{seaLevel=").append(ns.seaLevel())
                        .append(", defaultFluid=").append(ns.defaultFluid())
                        .append(", aquifers=").append(ns.isAquifersEnabled())
                        .append(", oreVeins=").append(ns.oreVeinsEnabled())
                        .append(", legacyRandom=").append(ns.useLegacyRandomSource())
                        .append('}');
            }
            // 逐 section hash 对比（服务端 SeedRef 下发 vs 本地生成）
            long[] serverHashes = entry.sectionHashes();
            long[] localHashes = ChunkContentHashUtil.sectionHashesToArray(localSectionHashes);
            sb.append(" localHash=0x").append(Long.toHexString(localHash))
                    .append(" serverHash=0x").append(Long.toHexString(entry.contentHash()));
            if (serverHashes == null || serverHashes.length == 0) {
                sb.append(" (server section hashes unavailable, per-section compare skipped)");
            } else {
                int len = Math.max(serverHashes.length, localHashes.length);
                int diffs = 0;
                int firstDiffIdx = -1;
                sb.append(" sections[");
                for (int i = 0; i < len; i++) {
                    long sh = i < serverHashes.length ? serverHashes[i] : 0L;
                    long lh = i < localHashes.length ? localHashes[i] : 0L;
                    if (sh != lh) {
                        if (diffs < 8) {
                            sb.append(i).append(":0x").append(Long.toHexString(lh))
                                    .append("!=0x").append(Long.toHexString(sh)).append(' ');
                        }
                        if (firstDiffIdx < 0) {
                            firstDiffIdx = i;
                        }
                        diffs++;
                    }
                }
                sb.append("] diffs=").append(diffs);
                // 首个差异 section 块状态摘要
                if (firstDiffIdx >= 0 && firstDiffIdx < chunk.getSections().length) {
                    net.minecraft.world.level.chunk.LevelChunkSection section = chunk.getSection(firstDiffIdx);
                    int nonAir = 0;
                    java.util.Set<net.minecraft.world.level.block.Block> distinctBlocks = new java.util.HashSet<>();
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                net.minecraft.world.level.block.state.BlockState bs = section.getBlockState(x, y, z);
                                if (!bs.isAir()) {
                                    nonAir++;
                                    distinctBlocks.add(bs.getBlock());
                                }
                            }
                        }
                    }
                    sb.append(" firstDiffSection=idx").append(firstDiffIdx)
                            .append("(y=").append(chunk.getSectionYFromSectionIndex(firstDiffIdx)).append(')')
                            .append(" hasOnlyAir=").append(section.hasOnlyAir())
                            .append(" nonAir=").append(nonAir)
                            .append(" distinctBlocks=").append(distinctBlocks.size());
                }
            }
            DebugLogger.info(DebugLogger.LogType.ASYNC, "{}", sb);
        } catch (Throwable t) {
            DebugLogger.warn(DebugLogger.LogType.ASYNC,
                    "[SEEDGEN][DIAG] Mismatch dump failed ({}, {})", pos.x, pos.z);
        }
    }

    /** 影子服务端懒创建（共享 registry；创建失败 → failed + 回退本次）。 */
    private ShadowSeedServer shadowServer() {
        ShadowSeedServer server = ShadowServerRegistry.getInstance().getOrCreate();
        if (server == null) {
            DebugLogger.info(DebugLogger.LogType.ASYNC,
                    "[SEEDGEN] Shadow server unavailable (no seed / creation failed) -> fallback");
        }
        return server;
    }

    /** 队列内待生成条目数 = 有界工作队列 + 未释放缓冲（诊断/测试）。 */
    public int pendingCount() {
        return queue.size() + pendingLive.size() + pendingPregen.size();
    }

    /** 维度上下文登记（package-private 测试钩子：模拟 handleSeedRef 捕获的维度）。 */
    static void putDimensionForTest(ChunkPos pos, String dimension) {
        DIMENSION_CONTEXT.put(ChunkPos.asLong(pos.x, pos.z), dimension);
    }

    /** 条目维度解析（package-private 测试钩子：暴露路由判定纯逻辑）。 */
    static String dimensionOfForTest(ChunkPos pos) {
        return dimensionOf(pos);
    }

    /** 清空维度上下文（测试隔离用；与 onDisconnect 同源）。 */
    static void clearDimensionsForTest() {
        DIMENSION_CONTEXT.clear();
    }
}
