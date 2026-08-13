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
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
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
 *   <li>单个 drain 循环互斥运行（AtomicBoolean CAS），串行生成（影子服务端单实例）</li>
 * </ul>
 * 生成失败/超时统一回退全量请求（{@link ClientMetadataHandler#fallbackToFullRequest}）；hash mismatch
 * 走分片增量（{@link ShadowLightCompute#requestSectionDeltas}，delta 链路失败内部兜底全量），正确性优先。
 */
public final class SeedGenExecutor {

    private static final SeedGenExecutor INSTANCE = new SeedGenExecutor();

    private final SeedGenQueue queue = new SeedGenQueue();
    /**
     * P1 节流：seedref 先入 pendingIn 缓冲，由 drain 按生成完成速率释放进有界工作队列，
     * 队列深度恒 ≤ {@link #MAX_WORK_DEPTH}——world-ready 一次性重放 1784 时不再瞬时灌入，
     * 影子端创建/装配/生成不再被洪峰淹没，且尾块在队等待被深度上界约束（超时窗口可准确覆盖）。
     */
    private final ConcurrentLinkedQueue<SeedRefS2CPacket> pendingIn = new ConcurrentLinkedQueue<>();

    /** 有界工作队列最大深度：96 槽 × 实测生成 p90≈182ms/块 ≈ 17.5s 最坏在队等待。 */
    private static final int MAX_WORK_DEPTH = 96;

    /** 回退请求合包上限：超时/失败回退攒批发送（不逐块单包），服务端 100/s 限速下单包风暴 = P1 诱因。 */
    private static final int FALLBACK_BATCH_MAX = 64;
    /** T7：镜像服务端 {@code ServerChunkPushManager#SECTION_DELTA_FALLBACK_THRESHOLD_PCT}（75%）。
     *  本地 worldgen 与服务器不一致时，变更 section 占比达阈值服务端必然跳过 delta 回退全量——
     *  客户端预判后直接全量请求，跳过必败的 delta 往返。改动需与服务端同值同步。 */
    private static final int SECTION_DELTA_FALLBACK_THRESHOLD_PCT = 75;
    private final AtomicBoolean drainRunning = new AtomicBoolean(false);
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
        // 节流接管：先入缓冲不直接进工作队列——由 drain 按生成完成速率释放
        // （releasePendingWork），world-ready 重放不会一次性灌 1784 进队
        pendingIn.add(packet);
        DebugLogger.info(DebugLogger.LogType.ASYNC, "[SEEDGEN] Claimed ({}, {}) hash={} (buffered={}, queue={})",
                packet.chunkX(), packet.chunkZ(), Long.toHexString(packet.contentHash()), pendingIn.size(), queue.size());
        pump();
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

    /** 断连清理：停池、关影子服务端（registry 共享）、清队列。 */
    public void onDisconnect() {
        queue.clear();
        pendingIn.clear();
        ExecutorService p = pool;
        pool = null;
        if (p != null) {
            p.shutdownNow();
        }
        ShadowServerRegistry.getInstance().shutdown();
    }

    /** 触发一次 drain（CAS 防并发；池未建则先建）。 */
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
        if (drainRunning.compareAndSet(false, true)) {
            try {
                p.submit(this::drain);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                drainRunning.set(false); // 池已停（断连竞态），丢弃本次
            }
        }
    }

    /** 工作循环：释放缓冲 → 取最近未超时条目 → 生成 → 编码 → 压缩 → 交给客户端链；空则退出。 */
    private void drain() {
        // 回退请求聚合缓冲：超时/失败回退攒批发送（不逐块单包），防 1000+ 单包风暴（P1）
        List<ChunkPos> fallbackBuffer = new ArrayList<>();
        try {
            while (!Thread.currentThread().isInterrupted()) {
                releasePendingWork();
                // 超时条目批量回退（expire 已移除；聚合攒批，flush 时合包）
                for (SeedGenQueue.Entry expired : queue.expire()) {
                    addFallback(fallbackBuffer, expired.pos());
                }
                Minecraft mc = Minecraft.getInstance();
                if (mc.player == null || mc.level == null) {
                    break; // 断连/未进服：剩余条目由 onDisconnect 清空
                }
                SeedGenQueue.Entry entry = queue.peekNearest(
                        mc.player.chunkPosition().x, mc.player.chunkPosition().z);
                if (entry == null) {
                    break;
                }
                generateOne(entry, fallbackBuffer);
                if (fallbackBuffer.size() >= FALLBACK_BATCH_MAX) {
                    flushFallback(fallbackBuffer);
                }
            }
        } finally {
            flushFallback(fallbackBuffer);
            drainRunning.set(false);
            // 竞态窗口：drain 退出瞬间有新条目 → 重新触发
            if ((!queue.isEmpty() || !pendingIn.isEmpty()) && !ShadowServerRegistry.getInstance().isFailed()) {
                pump();
            }
        }
    }

    /**
     * 从 pendingIn 释放 seedref 进有界工作队列：队列恒 ≤ {@link #MAX_WORK_DEPTH} 槽，
     * 实际入队速率 ≈ 生成完成速率（生成完 1 块才释放 1 块）——world-ready 一次性重放
     * 1784 不再瞬时灌入，影子端装配/生成不被洪峰淹没；尾块在队等待被深度上界约束，
     * 使 SeedGenQueue 自适应超时窗口可准确覆盖（P1）。
     */
    private void releasePendingWork() {
        while (queue.size() < MAX_WORK_DEPTH) {
            SeedRefS2CPacket p = pendingIn.poll();
            if (p == null) {
                break;
            }
            queue.enqueue(new ChunkPos(p.chunkX(), p.chunkZ()), p.contentHash(), p.sectionHashes());
            DebugLogger.info(DebugLogger.LogType.ASYNC,
                    "[SEEDGEN] Released ({}, {}) into work queue (queue={})",
                    p.chunkX(), p.chunkZ(), queue.size());
        }
    }

    /** 登记回退：出队 + 攒批（实际发送由 {@link #flushFallback} 按批合包）。 */
    private void addFallback(List<ChunkPos> buffer, ChunkPos pos) {
        queue.remove(pos);
        buffer.add(pos);
    }

    /**
     * 攒批发送回退全量请求：按 {@link #FALLBACK_BATCH_MAX} 合包，不再逐块单包
     * （服务端 100/s 限速下单包风暴 = P1 级联重发诱因）。断连时清缓冲丢弃。
     */
    private void flushFallback(List<ChunkPos> buffer) {
        if (buffer.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            buffer.clear();
            return;
        }
        String dimension = mc.level.dimension()
#if MC_VER < MC_1_21_11
                .location()
#else
                .identifier()
#endif
                .toString();
        for (int i = 0; i < buffer.size(); i += FALLBACK_BATCH_MAX) {
            int end = Math.min(i + FALLBACK_BATCH_MAX, buffer.size());
            List<ChunkPos> batch = new ArrayList<>(buffer.subList(i, end));
            DebugLogger.warn(DebugLogger.LogType.ASYNC,
                    "[SEEDGEN] Fallback batch: {} chunks (aggregated, not per-chunk)", batch.size());
            ClientMetadataHandler.requestFullChunksPublic(dimension, batch, true);
        }
        buffer.clear();
    }

    private void generateOne(SeedGenQueue.Entry entry, List<ChunkPos> fallbackBuffer) {
        ChunkPos pos = entry.pos();
        try {
            ShadowSeedServer server = shadowServer();
            if (server == null) {
                addFallback(fallbackBuffer, pos);
                return;
            }
            long t0 = System.nanoTime();
            LevelChunk chunk = server.generateChunk(pos);
            if (chunk == null) {
                DebugLogger.warn(DebugLogger.LogType.ASYNC,
                        "[SEEDGEN] Generation timeout/failed ({}, {}) -> fallback", pos.x, pos.z);
                addFallback(fallbackBuffer, pos);
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
                addFallback(fallbackBuffer, pos);
                return;
            }
            if (localHash != entry.contentHash()) {
                DebugLogger.warn(DebugLogger.LogType.ASYNC,
                        "[SEEDGEN] Hash mismatch ({}, {}): local={} server={} -> section-delta fallback",
                        pos.x, pos.z, Long.toHexString(localHash), Long.toHexString(entry.contentHash()));
                // review-fix: 本地 worldgen 与服务器不一致 → 不再全量请求：本地生成块作 section delta
                // 基线注入影子端，上报本地 section hashes，服务端按 section 比对只回变更 section
                // （heightmaps/BE 一并），不产出错误地形。delta 请求发送失败（requestSectionDeltas 内部）/
                // 超时（tickPendingDeltaTimeouts）/ 服务端 skipped（submitDelta 内部）均已
                // requestFullChunksPublic 兜底，此处不重复回退。
                queue.remove(pos);
                server.injectLoadedChunk(pos, chunk);
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null && mc.level != null) {
                    String dimension = mc.level.dimension()
#if MC_VER < MC_1_21_11
                            .location()
#else
                            .identifier()
#endif
                            .toString();
                    // T7 预判：变更占比达服务端回退阈值时，delta 往返必败（R2 观测 25 次 mismatch
                    // → 19 次服务端 skipped，全部来自此路径）——直接全量请求，节省一次往返与
                    // 服务端逐 section 比对/序列化开销；统计记账与 skipped 兜底路径完全一致（stale=true）。
                    if (wouldServerSkipDelta(entry, localSectionHashes)) {
                        DebugLogger.warn(DebugLogger.LogType.ASYNC,
                                "[SEEDGEN] Delta preempted ({}, {}): >= {}% non-empty sections differ -> direct full request",
                                pos.x, pos.z, SECTION_DELTA_FALLBACK_THRESHOLD_PCT);
                        ClientMetadataHandler.requestFullChunksPublic(dimension, List.of(pos), true);
                    } else {
                        ShadowLightCompute.requestSectionDeltas(dimension, List.of(pos));
                    }
                }
                return;
            }
            ServerLevel level = server.overworld();
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            DebugLogger.info(DebugLogger.LogType.ASYNC, "[SEEDGEN] Generated ({}, {}) in {}ms",
                    pos.x, pos.z, ms);
            NetworkStats.recordLocallyGeneratedChunk(NetworkStats.ESTIMATED_CHUNK_BYTES);
            // 统一影子通道：等光收敛（原版生成后算光同款逻辑）→ 打包官方包 →
            // 官方通道落地（客户端不参与缓存/光照）。
            // review-fix: T3-51：投递失败（并发降级 isEnabled=false）→ 回退全量，
            // 防止生成结果静默丢弃后该柱客户端虚空
            if (!ShadowLightCompute.submitGenerated(pos, chunk, level)) {
                addFallback(fallbackBuffer, pos);
                return;
            }
            queue.remove(pos);
        } catch (Exception e) {
            Constants.LOG.error("Hassium: SeedGen generation failed for {}", pos, e);
            addFallback(fallbackBuffer, pos);
        }
    }

    /**
     * 镜像服务端 section-delta 回退判定（{@code ServerChunkPushManager} 的
     * {@code processSectionDelta}/{@code handleSectionHashRequest} 同算法）：按完整索引比对双方
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
        int len = Math.max(serverHashes.length, localHashes.length);
        int changed = 0;
        int nonEmpty = 0;
        for (int idx = 0; idx < len; idx++) {
            long serverHash = idx < serverHashes.length ? serverHashes[idx] : 0L;
            long localHash = idx < localHashes.length ? localHashes[idx] : 0L;
            if (serverHash != 0L) {
                nonEmpty++;
            }
            if (serverHash != localHash) {
                changed++;
            }
        }
        return nonEmpty > 0 && changed > 0
                && changed * 100 / nonEmpty >= SECTION_DELTA_FALLBACK_THRESHOLD_PCT;
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
        return queue.size() + pendingIn.size();
    }
}
