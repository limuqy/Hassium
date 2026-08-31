package io.github.limuqy.mc.hassium.network.seedgen;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil;
import io.github.limuqy.mc.hassium.concurrent.ExecutorFactory;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.ClientChunkHandler;
import io.github.limuqy.mc.hassium.network.ClientChunkPipeline;
import io.github.limuqy.mc.hassium.network.ClientMetadataHandler;
import io.github.limuqy.mc.hassium.network.SeedRefS2CPacket;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import io.github.limuqy.mc.hassium.compat.LevelCompat;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DimensionKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
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
        DIMENSION_CONTEXT.put(ChunkPos.asLong(pos.x, pos.z), dimension);
        pendingLive.enqueue(pos, packet.contentHash(), packet.sectionHashes());
        DebugLogger.info(DebugLogger.LogType.ASYNC, "[SEEDGEN] Claimed ({}, {}) dim={} hash={} (buffered={}, queue={})",
                packet.chunkX(), packet.chunkZ(), dimension, Long.toHexString(packet.contentHash()),
                pendingLive.size(), queue.size());
        pump();
        return true;
    }

    /** 门控：客户端本地生成开启、服务端 SeedGen 开启、真实 seed 已到达、影子端未失败。 */
    private boolean isEnabled() {
        if (ShadowServerRegistry.getInstance().isFailed()) {
            return false;
        }
        HassiumConfigService cfg = HassiumConfigService.getInstance();
        if (!cfg.isClientSeedGenEnabled() || cfg.getSeedGenThreads() <= 0) {
            return false;
        }
        ClientChunkPipeline pipeline = ClientChunkPipeline.getInstance();
        return pipeline.isServerSeedGenEnabled() && pipeline.isServerSeedAvailable();
    }

    /** 断连清理：停池、清队列并重置 pull 请求号。 */
    public void onDisconnect() {
        queue.clear();
        pendingLive.clear();
        DIMENSION_CONTEXT.clear();
        io.github.limuqy.mc.hassium.network.ShadowPullClient.reset();
        ExecutorService p = pool;
        pool = null;
        if (p != null) {
            p.shutdownNow();
        }
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
        // CAS 认领式补足（非 get()<target 读判）：先占计数再提交，消除「pump 读到旧 activeWorkers
        // ==target 不提交 × 退出 worker 尚未 decrement」交错导致的丢失唤醒——该窗口正是
        // 1.21.2_fabric_fin2 R1 零落地死区：SeedRef 入 pendingLive 后无 worker 认领，
        // pendingLive.expire() 只在 drain 循环头执行 → 超时条目既不释放也不回退。
        while (true) {
            int n = activeWorkers.get();
            if (n >= target) {
                break;
            }
            if (activeWorkers.compareAndSet(n, n + 1)) {
                try {
                    p.submit(this::drain);
                    break;
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    activeWorkers.decrementAndGet(); // 池已停（断连竞态），丢弃本次认领
                    break;
                }
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
                // 退出前兜底：循环头 expire 之后、此刻之前新过截止的条目（影子重启窗口内
                // 到达的 SeedRef 尤甚）必须在本 worker 消亡前回收回退——否则若补足 pump
                // 因竞态未接上，条目将卡「不释放也不回收」死区（1.21.2_fabric_fin2 R1 实证）。
                for (SeedGenQueue.Entry expired : queue.expire()) {
                    addFallback(fallbackBuffer, expired);
                }
                for (SeedGenQueue.Entry expired : pendingLive.expire()) {
                    addFallback(fallbackBuffer, expired);
                }
                if (!fallbackBuffer.isEmpty()) {
                    flushFallback(fallbackBuffer);
                }
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
            if ((!queue.isEmpty() || !pendingLive.isEmpty())
                    && !ShadowServerRegistry.getInstance().isFailed()) {
                pump();
            }
        }
    }

    /** 从 SeedRef 缓冲按玩家距离释放至有界工作队列。 */
    private void releasePendingWork(int playerChunkX, int playerChunkZ) {
        releasePendingWork(queue, pendingLive, playerChunkX, playerChunkZ, MAX_WORK_DEPTH);
    }

    /** 纯逻辑版本：只释放经过服务端 SeedRef 授权的条目。 */
    static void releasePendingWork(SeedGenQueue workQueue, SeedGenQueue liveQueue,
                                   int playerChunkX, int playerChunkZ, int maxWorkDepth) {
        int released = 0;
        while (workQueue.size() < maxWorkDepth && released < maxWorkDepth) {
            SeedGenQueue.Entry entry = liveQueue.peekNearest(playerChunkX, playerChunkZ);
            if (entry == null) {
                return;
            }
            if (!liveQueue.tryTake(entry)) {
                continue;
            }
            workQueue.enqueue(entry.pos(), entry.contentHash(), entry.sectionHashes());
            released++;
            DebugLogger.info(DebugLogger.LogType.ASYNC,
                    "[SEEDGEN] Released ({}, {}) into work queue (queue={}, buffered={})",
                    entry.pos().x, entry.pos().z, workQueue.size(), liveQueue.size());
        }
    }

    /** SeedRef 回退必须携带维度。 */
    private record FallbackRequest(ChunkPos pos, String dimension) {}

    /** 登记回退：出队 + 攒批（实际发送由 {@link #flushFallback} 按批合包）。 */
    private void addFallback(List<FallbackRequest> buffer, SeedGenQueue.Entry entry) {
        addFallback(buffer, entry.pos(), dimensionOf(entry.pos()));
    }

    private void addFallback(List<FallbackRequest> buffer, ChunkPos pos, String dimension) {
        queue.remove(pos);
        DIMENSION_CONTEXT.remove(ChunkPos.asLong(pos.x, pos.z));
        buffer.add(new FallbackRequest(pos, dimension));
    }

    /**
     * 攒批发送回退全量请求：按 {@link #FALLBACK_BATCH_MAX} 合包；断连时清缓冲丢弃。
     */
    /** 按维度合批发送权威 FULL 回退请求。 */
    private void flushFallback(List<FallbackRequest> buffer) {
        if (buffer.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            buffer.clear();
            return;
        }
        Map<String, List<ChunkPos>> requestsByDimension = new java.util.HashMap<>();
        for (FallbackRequest item : buffer) {
            if (ShadowLightCompute.tryRequestMiss(item.dimension(), item.pos())) {
                requestsByDimension.computeIfAbsent(item.dimension(), ignored -> new ArrayList<>()).add(item.pos());
            }
        }
        buffer.clear();
        for (Map.Entry<String, List<ChunkPos>> request : requestsByDimension.entrySet()) {
            io.github.limuqy.mc.hassium.network.ShadowPullClient.requestFull(request.getKey(), request.getValue());
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
                DebugLogger.warn(DebugLogger.LogType.ASYNC,
                        "[SEEDGEN] Generation timeout/failed ({}, {}) -> fallback", pos.x, pos.z);
                addFallback(fallbackBuffer, entry);
                return;
            }
            if (entry.contentHash() == 0L) {
                // 没有服务端内容 hash 即没有本地生成授权；请求权威 FULL。
                addFallback(fallbackBuffer, entry);
                return;
            }
            // 生成后 contentHash 校验；不一致时不把未经服务端确认的本地生成结果注入影子端。
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
                dumpMismatchDiagnostics(pos, entry, chunk, localSectionHashes, localHash, server, dimension);
                DebugLogger.error("[SEEDGEN] Validation mismatch ({}, {}) -> request authoritative FULL",
                        pos.x, pos.z);
                ShadowServerRegistry.getInstance().failShadowServer();
                addFallback(fallbackBuffer, entry);
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
            if (!ShadowLightCompute.submitGenerated(pos, chunk, level)) {
                addFallback(fallbackBuffer, entry);
                return;
            }
            queue.remove(pos);
            DIMENSION_CONTEXT.remove(ChunkPos.asLong(pos.x, pos.z));
        } catch (Exception e) {
            Constants.LOG.error("Hassium: SeedGen generation failed for {}", pos, e);
            addFallback(fallbackBuffer, entry);
        }
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
        return queue.size() + pendingLive.size();
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
