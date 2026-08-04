package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.LevelHeightCompat;
import io.github.limuqy.mc.hassium.concurrent.ExecutorFactory;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;

/**
 * 并行光照引擎（方案 D）：后台线程池全量 BFS 重算 + 主线程原子提交。
 * <p>
 * 正确性依据：光照强度 0–15，每格传播成本 ≥ 1 → 任何格子的值只依赖 15 格半径内的
 * 光源与遮挡；任务域 = 核心柱 + 每方向 1 个柱（16 格）halo（= 3×3 区块），域外不可能
 * 影响核心柱结果 → 任务间无依赖、无需迭代、无需合并，可完全并行。
 * <p>
 * 主线程 {@link #drainCompletions} 批量提交（预算内两阶段）：建层 + {@code queueSectionData}
 * + 官方校准（propagateLightSources + pullLightFromNeighborEdges，吸收快照陈旧与任何残余
 * 语义偏差）逐结果入批，批尾统一一次 runLightUpdates 落地；然后逐结果验算对比（
 * {@code debug.lightVerify}，内芯 x/z ∈ [1,14]，边界差异属输入范围差异非错误）+ 缓存写回
 * （写回经 CacheSaveQueue 后台化：主线程只组 NBT，压缩与写盘由后台单消费者执行）。
 * <p>
 * 默认关闭（{@code clientCache.parallelLightEngineEnabled}），现有同步路径为默认。
 */
public final class LightComputeService {

    private static final int DOMAIN_CHUNKS = 3;
    private static final int W = DOMAIN_CHUNKS * 16;
    private static final int CORE_OFFSET = 16;
    private static final int SNAPSHOT_CACHE_MAX = 128;
    /**
     * 主线程单帧最多捕获的 16×16 柱数。每块完整域需 9 柱；按切片采样避免一个区块 apply
     * 同步占用整个帧，同时后台仍可并行处理已完成的不可变快照。
     */
    private static final int MAX_CAPTURE_COLUMNS_PER_FRAME = 12;
    private static final int MAX_COLUMNS_PER_CAPTURE_SLICE = 3;
    private static final long CAPTURE_BUDGET_NS = 2_000_000L;
    /**
     * 捕获等待邻居就绪的最大帧数。超过后该柱降级为空占位（视距边缘等永不到达场景），
     * 代价仅为边界 1 格可能偏暗；避免任务无限挂起。
     */
    private static final int NEIGHBOR_WAIT_FRAMES = 20;
    /**
     * 单帧最大入批结果数。批尾 {@code runLightUpdates} 全量传播不受帧预算约束（预算循环
     * 只限制 poll），风暴期单帧 poll 可入队 18+ 块 → 批尾一次传播数十块，主线程帧时间爆炸
     * （实测加载期 9/10 采样主线程卡在传播链）。限批 = 限传播量：每帧传播成本与入批数线性。
     */
    private static final int MAX_RESULTS_PER_FRAME = 8;
    private static final int[][] DOMAIN_OFFSETS = {{-1, -1}, {0, -1}, {1, -1}, {-1, 0},
            {0, 0}, {1, 0}, {-1, 1}, {0, 1}, {1, 1}};
    private static final int[] OPPOSITE_DIR = {LightFloodFill.UP, LightFloodFill.DOWN,
            LightFloodFill.SOUTH, LightFloodFill.NORTH, LightFloodFill.EAST, LightFloodFill.WEST};

    private static final LightComputeService INSTANCE = new LightComputeService();

    public static LightComputeService getInstance() {
        return INSTANCE;
    }

    private final ConcurrentLinkedQueue<LightComputeResult> results = new ConcurrentLinkedQueue<>();
    /** 仅 Render thread 访问：把昂贵的世界快照分散到多个 tick。 */
    private final ArrayDeque<CaptureTask> pendingCaptures = new ArrayDeque<>();
    private final Map<Long, CaptureTask> pendingByCore = new HashMap<>();
    private final LinkedHashMap<Long, SnapshotCacheEntry> snapshotCache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, SnapshotCacheEntry> eldest) {
            return size() > SNAPSHOT_CACHE_MAX;
        }
    };
    private volatile ExecutorService pool;
    /** 断连后拒绝仍在运行的旧会话任务及其结果。 */
    private volatile long generation;
    private final java.util.concurrent.atomic.AtomicInteger diagCapture = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger diagBg = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger diagApply = new java.util.concurrent.atomic.AtomicInteger();

    private LightComputeService() {
    }

    /** 限频诊断日志（定位并行引擎断链用，定位后移除）。 */
    private void diagCapture(String msg, Object... args) {
        if (diagCapture.getAndIncrement() < 10) {
            Constants.LOG.info("[LIGHT-DIAG-CAPTURE] " + msg, args);
        }
    }

    private void diagBg(String msg, Object... args) {
        if (diagBg.getAndIncrement() < 10) {
            Constants.LOG.info("[LIGHT-DIAG-BG] " + msg, args);
        }
    }

    private void diagApply(String msg, Object... args) {
        if (diagApply.getAndIncrement() < 10) {
            Constants.LOG.info("[LIGHT-DIAG-APPLY] " + msg, args);
        }
    }

    /** 后台任务产物：核心柱各 section 的 sky/block 半字节数组（DataLayer 布局，2048 字节）。 */
    public record LightComputeResult(ChunkPos corePos, LevelChunk expectedCoreChunk,
                                     byte[][] skySections, byte[][] blockSections,
                                     CompoundTag cachedNbt, long generation, long captureNanos) {
    }

    private record SnapshotCacheEntry(LevelChunk chunk, LightColumnSnapshot snapshot) {
    }

    /** 一个核心块的 9 柱主线程采样状态；填满后只传递不可变快照到后台。 */
    private static final class CaptureTask {
        private final ChunkPos corePos;
        private final int minSection;
        private final int sectionCount;
        private final int minY;
        private final int height;
        private final long generation;
        private final LightColumnSnapshot[] snapshots = new LightColumnSnapshot[DOMAIN_OFFSETS.length];
        private LevelChunk expectedCoreChunk;
        private CompoundTag cachedNbt;
        private int nextColumn;
        private int waitFrames;
        private long captureNanos;

        private CaptureTask(ChunkPos corePos, int minSection, int sectionCount, int minY, int height,
                            LevelChunk expectedCoreChunk, CompoundTag cachedNbt, long generation) {
            this.corePos = corePos;
            this.minSection = minSection;
            this.sectionCount = sectionCount;
            this.minY = minY;
            this.height = height;
            this.expectedCoreChunk = expectedCoreChunk;
            this.cachedNbt = cachedNbt;
            this.generation = generation;
        }

        private void retainNbt(CompoundTag nbt) {
            if (cachedNbt == null && nbt != null) {
                cachedNbt = nbt;
            }
        }

        private void restart(LevelChunk coreChunk) {
            java.util.Arrays.fill(snapshots, null);
            expectedCoreChunk = coreChunk;
            nextColumn = 0;
            waitFrames = 0;
            captureNanos = 0L;
        }
    }

    /**
     * 登记一次重算。世界读取始终留在 Render thread；9 柱采样由 {@link #drainCompletions}
     * 按帧预算完成，随后后台执行域组装与 BFS。
     * <p>
     * 缓存预提交时 core chunk 尚未入世界也允许登记；TAIL 再次提交时检测到 chunk 身份变化，
     * 会丢弃预提交的部分采样并从权威 chunk 重启。
     */
    public void submitRecompute(ChunkPos corePos, CompoundTag cachedNbt) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        long key = ChunkPos.asLong(corePos.x, corePos.z);
        LevelChunk coreChunk = level.getChunkSource().getChunkNow(corePos.x, corePos.z);
        CaptureTask task = pendingByCore.get(key);
        if (task != null) {
            task.retainNbt(cachedNbt);
            if (task.expectedCoreChunk != coreChunk) {
                task.restart(coreChunk);
            }
            return;
        }
        int minSection = LevelHeightCompat.getMinSection(level);
        int maxSection = LevelHeightCompat.getMaxSectionExclusive(level);
        task = new CaptureTask(corePos, minSection, maxSection - minSection,
                LevelHeightCompat.getMinBlockY(level), level.getHeight(), coreChunk, cachedNbt, generation);
        pendingByCore.put(key, task);
        pendingCaptures.add(task);
    }

    /**
     * 在 Render thread 采样少量柱。每一柱完整读取，因而不会把 {@link ClientLevel}/{@code BlockState}
     * 访问泄漏到后台；完整 3×3 域一旦就绪，之后的数组组装、BFS、NBT 回读全在 CPU 池完成。
     */
    private void capturePending(ClientLevel level, long outerDeadlineNs) {
        long captureDeadlineNs = Math.min(outerDeadlineNs, System.nanoTime() + CAPTURE_BUDGET_NS);
        int captured = 0;
        while (captured < MAX_CAPTURE_COLUMNS_PER_FRAME && System.nanoTime() < captureDeadlineNs) {
            CaptureTask task = pendingCaptures.poll();
            if (task == null) {
                return;
            }
            if (task.generation != generation) {
                pendingByCore.remove(ChunkPos.asLong(task.corePos.x, task.corePos.z), task);
                continue;
            }
            LevelChunk coreChunk = level.getChunkSource().getChunkNow(task.corePos.x, task.corePos.z);
            if (task.expectedCoreChunk != coreChunk) {
                task.restart(coreChunk);
            }
            int slice = 0;
            while (task.nextColumn < DOMAIN_OFFSETS.length && slice < MAX_COLUMNS_PER_CAPTURE_SLICE
                    && captured < MAX_CAPTURE_COLUMNS_PER_FRAME && System.nanoTime() < captureDeadlineNs) {
                int[] off = DOMAIN_OFFSETS[task.nextColumn];
                int cx = task.corePos.x + off[0];
                int cz = task.corePos.z + off[1];
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    // 邻居（或核心块）未加载：等待而非空柱占位。3×3 域缺角会让
                    // BFS 边界 1 格固化暗值（视觉环带），且结果会被写回缓存永久固化。
                    // 任务放回队尾轮转，waitFrames 超时后用 empty 兜底（视距边缘）。
                    if (task.waitFrames < NEIGHBOR_WAIT_FRAMES) {
                        task.waitFrames++;
                        pendingCaptures.add(task);
                        break;
                    }
                }
                long captureStartNs = System.nanoTime();
                try {
                    task.snapshots[task.nextColumn] = chunk == null
                            ? LightColumnSnapshot.empty(task.minY, task.height)
                            : snapshotOrCapture(level, chunk, cx, cz);
                } catch (Throwable t) {
                    // 单柱采样失败：作废整个任务（该 core 稍后 TAIL 提交会重建），不得静默卡死队列
                    diagCapture("capture FAIL {} col={} off={},{} err={}", task.corePos, task.nextColumn, off[0], off[1], t);
                    pendingByCore.remove(ChunkPos.asLong(task.corePos.x, task.corePos.z), task);
                    task = null;
                    break;
                }
                task.captureNanos += System.nanoTime() - captureStartNs;
                task.nextColumn++;
                slice++;
                captured++;
            }
            if (task == null) {
                continue;
            }
            if (task.nextColumn == DOMAIN_OFFSETS.length) {
                pendingByCore.remove(ChunkPos.asLong(task.corePos.x, task.corePos.z), task);
                diagCapture("capture done {} gen={} queue={} caps={}us", task.corePos, task.generation,
                        pendingCaptures.size(), task.captureNanos / 1000);
                submitCapturedTask(task);
            } else {
                pendingCaptures.add(task);
            }
        }
    }

    /** 所有输入已不可变；该任务不得再触碰 ClientLevel、chunk source 或 light engine。 */
    private void submitCapturedTask(CaptureTask task) {
        ExecutorService p = ensurePool();
        p.execute(() -> {
            try {
                if (task.generation != generation) {
                    diagBg("drop {} gen={} current={}", task.corePos, task.generation, generation);
                    return;
                }
                diagBg("start {}", task.corePos);
                long backgroundStartNs = System.nanoTime();
                int cells = W * W * task.height;
                byte[] domainLightBlock = new byte[cells];
                int[] domainShapeIds = new int[cells];
                int[] domainSourceY = new int[W * W];
                java.util.Arrays.fill(domainSourceY, LightFloodFill.NO_COLUMN);
                List<Integer> emitters = new ArrayList<>();
                List<VoxelShape[]> allShapes = new ArrayList<>();
                for (int i = 0; i < DOMAIN_OFFSETS.length; i++) {
                    int[] off = DOMAIN_OFFSETS[i];
                    assemble(task.snapshots[i], off[0], off[1], task.height,
                            domainLightBlock, domainShapeIds, domainSourceY, emitters, allShapes);
                }
                int[] emitterArr = emitters.stream().mapToInt(Integer::intValue).toArray();
                VoxelShape[][] shapeTable = allShapes.toArray(new VoxelShape[0][]);
                LightFloodFill.Occlusion occlusion = (srcShape, dstShape, dir) -> Shapes.faceShapeOccludes(
                        shapeTable[srcShape - 1][dir], shapeTable[dstShape - 1][OPPOSITE_DIR[dir]]);
                CompoundTag nbt = task.cachedNbt;
                if (nbt == null) {
                    nbt = io.github.limuqy.mc.hassium.network.ClientChunkHandler
                            .loadChunkNbtFromCache(task.corePos);
                }
                LightFloodFill.Result r = LightFloodFill.solve(W, task.height,
                        domainLightBlock, emitterArr, domainSourceY, domainShapeIds, occlusion);
                byte[][] skySections = extractCore(r.skyLight(), task.minSection, task.sectionCount, task.height);
                byte[][] blockSections = extractCore(r.blockLight(), task.minSection, task.sectionCount, task.height);
                NetworkStats.recordLightRecomputeBackgroundTime(System.nanoTime() - backgroundStartNs);
                diagBg("done {} elapsed={}us", task.corePos, (System.nanoTime() - backgroundStartNs) / 1000);
                if (task.generation == generation) {
                    results.add(new LightComputeResult(task.corePos, task.expectedCoreChunk, skySections, blockSections,
                            nbt, task.generation, task.captureNanos));
                }
            } catch (Throwable t) {
                diagBg("error {}: {}", task.corePos, t);
                if (task.generation == generation) {
                    Constants.LOG.error("Hassium: Parallel light recompute failed for {}", task.corePos, t);
                }
            }
        });
    }

    /**
     * 主线程在帧预算内批量提交（超预算提前退出，剩余留待下帧）。
     * <p>
     * 两阶段：预算内循环对每个结果做「建层 + 入队 + 光源校准」（不含落地），收集入批；
     * 批尾统一执行一次官方 {@code runLightUpdates} 落地——N 个结果共享一次 swapSectionMap
     * 全 map 遍历与空 checkNode（逐结果提交是 N 次）。传播队列在引擎内全局累积，稳态与
     * 逐结果提交一致；且同批邻居先落地后 checkNode 按新层评估，校准反而更准。落地后
     * 逐结果做验算对比 + 缓存写回（写回本身已后台化，见 CacheSaveQueue）。
     */
    public void drainCompletions(long deadlineNs) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return; // 断连/未进服：结果由 clear() 清理，不消费
        }
        capturePending(level, deadlineNs);
        LevelLightEngine lightEngine = level.getLightEngine();
        java.util.List<LightComputeResult> batch = new java.util.ArrayList<>();
        while (System.nanoTime() < deadlineNs && batch.size() < MAX_RESULTS_PER_FRAME) {
            LightComputeResult r = results.poll();
            if (r == null) {
                break;
            }
            try {
                if (applyResultEnqueue(level, lightEngine, r)) {
                    batch.add(r);
                }
            } catch (Throwable t) {
                Constants.LOG.error("Hassium: Failed to enqueue parallel light result for {}", r.corePos(), t);
            }
        }
        if (batch.isEmpty()) {
            return;
        }
        try {
            ClientLightRecomputeService.safeRunLightUpdates(lightEngine);
        } catch (Throwable t) {
            Constants.LOG.error("Hassium: Failed to run batched light updates", t);
        }
        for (LightComputeResult r : batch) {
            try {
                applyResultPost(level, lightEngine, r);
            } catch (Throwable t) {
                Constants.LOG.error("Hassium: Failed to finalize parallel light result for {}", r.corePos(), t);
            }
        }
    }

    /** 断连清理：拒绝旧会话任务、清空全部队列与快照缓存并关闭线程池（下次提交重建）。 */
    public void clear() {
        generation++;
        results.clear();
        pendingCaptures.clear();
        pendingByCore.clear();
        synchronized (snapshotCache) {
            snapshotCache.clear();
        }
        ExecutorService p = pool;
        pool = null;
        if (p != null) {
            p.shutdownNow();
        }
    }

    /** 阶段一（预算内）：校验 + 建层 + 入队 + 光源校准，不含落地。返回是否入批。 */
    private boolean applyResultEnqueue(ClientLevel level, LevelLightEngine lightEngine, LightComputeResult r) {
        if (r.generation() != generation) {
            diagApply("drop {} reason=generation {}!={}", r.corePos(), r.generation(), generation);
            return false;
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(r.corePos().x, r.corePos().z);
        if (chunk == null || chunk != r.expectedCoreChunk()) {
            diagApply("drop {} reason=chunk {}!={}", r.corePos(),
                    chunk == null ? "null" : System.identityHashCode(chunk),
                    r.expectedCoreChunk() == null ? "null" : System.identityHashCode(r.expectedCoreChunk()));
            return false; // 卸载/刷新竞态：旧快照不得覆盖新权威 chunk
        }
        diagApply("ok {} gen={} capture={}us", r.corePos(), r.generation(), r.captureNanos() / 1000);
        // 主线程光照应用耗时（同步路径同口径：applyLightEngine 的 finally 记录同一指标；
        // 分帧 capture 时间在此合并，批量共享的 runLightUpdates 与后台化写回不计入单结果）
        long mainThreadStartNs = System.nanoTime();
        try {
            applyResultEnqueueInner(level, lightEngine, r);
        } finally {
            NetworkStats.recordLightRecomputeTime(System.nanoTime() - mainThreadStartNs + r.captureNanos());
        }
        return true;
    }

    private void applyResultEnqueueInner(ClientLevel level, LevelLightEngine lightEngine, LightComputeResult r) {
        int minSection = LevelHeightCompat.getMinSection(level);
        int maxSection = LevelHeightCompat.getMaxSectionExclusive(level);

        // 1. 建层（storage 未建层的 section 在 queueSectionData 后不生效）
        ClientLightRecomputeService.ensureColumnDataLayers(level, lightEngine, r.corePos(), minSection, maxSection);
        ClientLightRecomputeService.ensureNeighborDataLayers(level, lightEngine, r.corePos(), minSection, maxSection);

        // 2. 原子提交 BFS 结果（官方 queueSectionData 签名全版本一致；只入队，落地在批尾统一 runLightUpdates）
        int sectionCount = Math.min(r.skySections().length, maxSection - minSection);
        for (int s = 0; s < sectionCount; s++) {
            int sectionY = minSection + s;
            SectionPos sp = SectionPos.of(r.corePos().x, sectionY, r.corePos().z);
            lightEngine.queueSectionData(LightLayer.SKY, sp, new DataLayer(r.skySections()[s]));
            lightEngine.queueSectionData(LightLayer.BLOCK, sp, new DataLayer(r.blockSections()[s]));
            level.setSectionDirtyWithNeighbors(r.corePos().x, sectionY, r.corePos().z);
        }

        // 3. 校准：基态正确时官方 drain 近乎零成本；同时吸收快照陈旧/残余语义偏差
        lightEngine.propagateLightSources(r.corePos());
        ClientLightRecomputeService.pullLightFromNeighborEdges(level, r.corePos(), minSection, maxSection);
    }

    /** 阶段二（批尾统一落地后）：验算对比 + 缓存写回（写回入后台队列）。 */
    private void applyResultPost(ClientLevel level, LevelLightEngine lightEngine, LightComputeResult r) {
        HassiumConfigService cfg = HassiumConfigService.getInstance();
        int minSection = LevelHeightCompat.getMinSection(level);
        int maxSection = LevelHeightCompat.getMaxSectionExclusive(level);
        if (cfg.isLightVerifyEnabled()) {
            // 验算（稳态对照）：校准吸收的修正量 = BFS 的真实错误；BFS 正确时校准零成本，
            // 稳态 == BFS 输入。批量落地后读层，内芯 x/z∈[1,14] 不依赖邻居落地态，结论不变。
            compareAndRecord(level, lightEngine, r, minSection, maxSection);
        }
        if (cfg.isLightCacheEnabled()) {
            ClientLightRecomputeService.updateCacheWithLightData(level, r.corePos(), r.cachedNbt());
        }
    }

    /** 验算：官方权威 vs BFS 逐格对比（内芯 x/z ∈ [1,14]；边界差异属输入范围差异）。 */


    private void compareAndRecord(ClientLevel level, LevelLightEngine lightEngine,
                                  LightComputeResult r, int minSection, int maxSection) {
        long mismatch = 0;
        long skyMismatch = 0;
        long blockMismatch = 0;
        long edgeMismatch = 0;
        long edgeSky = 0;
        long edgeBlock = 0;
        StringBuilder samples = new StringBuilder();
        int sectionCount = Math.min(r.skySections().length, maxSection - minSection);
        for (int s = 0; s < sectionCount; s++) {
            int sectionY = minSection + s;
            SectionPos sp = SectionPos.of(r.corePos().x, sectionY, r.corePos().z);
            DataLayer skyOfficial = lightEngine.getLayerListener(LightLayer.SKY).getDataLayerData(sp);
            DataLayer blockOfficial = lightEngine.getLayerListener(LightLayer.BLOCK).getDataLayerData(sp);
            skyMismatch += compareLayer("sky", samples, sectionY, skyOfficial, r.skySections()[s]);
            blockMismatch += compareLayer("block", samples, sectionY, blockOfficial, r.blockSections()[s]);
            edgeSky += compareEdge("sky", sectionY, skyOfficial, r.skySections()[s]);
            edgeBlock += compareEdge("block", sectionY, blockOfficial, r.blockSections()[s]);
        }
        mismatch = skyMismatch + blockMismatch;
        edgeMismatch = edgeSky + edgeBlock;
        NetworkStats.recordLightVerifyMismatch(mismatch);
        if (mismatch > 0) {
            Constants.LOG.error("[LIGHT_VERIFY] chunk {} mismatch={} (sky={} block={})", r.corePos(), mismatch, skyMismatch, blockMismatch);
            if (samples.length() > 0) {
                Constants.LOG.error("[LIGHT_VERIFY-SAMPLE] {}", samples);
            }
        } else {
            Constants.LOG.debug("[LIGHT_VERIFY] chunk {} ok", r.corePos());
        }
        if (edgeMismatch > 0) {
            Constants.LOG.error("[LIGHT_VERIFY-EDGE] chunk {} edgeMismatch={} (sky={} block={})",
                    r.corePos(), edgeMismatch, edgeSky, edgeBlock);
        }
    }

    /** 单 section 内芯对比（x/z ∈ [1,14]；官方层为 null = 全 0）；收集前 6 个差异样例。 */
    private static long compareLayer(String layer, StringBuilder samples, int sectionY,
                                     DataLayer official, byte[] bfs) {
        long mismatch = 0;
        for (int y = 0; y < 16; y++) {
            for (int x = 1; x < 15; x++) {
                for (int z = 1; z < 15; z++) {
                    int officialValue = official == null ? 0 : official.get(x, y, z);
                    int bfsValue = nibbleAt(bfs, x, y, z);
                    if (officialValue != bfsValue) {
                        if (samples.length() < 600) {
                            samples.append(layer).append('(').append(x).append(',').append(sectionY * 16 + y).append(',').append(z).append(")o=").append(officialValue).append("b=").append(bfsValue).append(' ');
                        }
                        mismatch++;
                    }
                }
            }
        }
        return mismatch;
    }


    /** 边界格（x/z ∈ {0,15}）对比：邻居缺失导致的边缘暗值在此暴露。 */
    private static long compareEdge(String layer, int sectionY, DataLayer official, byte[] bfs) {
        long mismatch = 0;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x += 15) {
                for (int z = 0; z < 16; z++) {
                    int officialValue = official == null ? 0 : official.get(x, y, z);
                    int bfsValue = nibbleAt(bfs, x, y, z);
                    if (officialValue != bfsValue) {
                        mismatch++;
                    }
                }
            }
            for (int z = 0; z < 16; z += 15) {
                for (int x = 1; x < 15; x++) {
                    int officialValue = official == null ? 0 : official.get(x, y, z);
                    int bfsValue = nibbleAt(bfs, x, y, z);
                    if (officialValue != bfsValue) {
                        mismatch++;
                    }
                }
            }
        }
        return mismatch;
    }

    private static int nibbleAt(byte[] data, int x, int y, int z) {
        int index = (y << 8) | (z << 4) | x;
        return (data[index >> 1] >> ((index & 1) * 4)) & 0xF;
    }

    private LightColumnSnapshot snapshotOrCapture(ClientLevel level, LevelChunk chunk, int cx, int cz) {
        long key = ChunkPos.asLong(cx, cz);
        synchronized (snapshotCache) {
            SnapshotCacheEntry entry = snapshotCache.get(key);
            if (entry == null || entry.chunk() != chunk) {
                LightColumnSnapshot snapshot = LightColumnSnapshot.capture(level, cx, cz);
                entry = new SnapshotCacheEntry(chunk, snapshot);
                snapshotCache.put(key, entry);
            }
            return entry.snapshot();
        }
    }

    /** 把柱快照拷入域数组（chunkOffset = 相对核心柱的 -1/0/+1）。 */
    private static void assemble(LightColumnSnapshot snap, int dx, int dz, int height,
                                 byte[] domainLightBlock, int[] domainShapeIds, int[] domainSourceY,
                                 List<Integer> emitters, List<VoxelShape[]> allShapes) {
        int baseX = CORE_OFFSET + dx * 16;
        int baseZ = CORE_OFFSET + dz * 16;
        byte[] lb = snap.getLightBlock();
        int[] sy = snap.getSourceY();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int domCol = (baseZ + z) * W + (baseX + x);
                domainSourceY[domCol] = sy[z * 16 + x];
                for (int y = 0; y < height; y++) {
                    int src = y * 256 + z * 16 + x;
                    int dst = (y * W + (baseZ + z)) * W + (baseX + x);
                    domainLightBlock[dst] = lb[src];
                }
            }
        }
        for (int e : snap.getEmitters()) {
            int emission = e >>> 20;
            int cell = e & 0xFFFFF;
            int y = cell >> 8;
            int z = (cell >> 4) & 0xF;
            int x = cell & 0xF;
            int dst = (y * W + (baseZ + z)) * W + (baseX + x);
            emitters.add((emission << 20) | dst);
        }
        int[] shapeCells = snap.getShapeCells();
        VoxelShape[][] shapeFaces = snap.getShapeFaces();
        for (int i = 0; i < shapeCells.length; i++) {
            int cell = shapeCells[i];
            int y = cell >> 8;
            int z = (cell >> 4) & 0xF;
            int x = cell & 0xF;
            int dst = (y * W + (baseZ + z)) * W + (baseX + x);
            // 0 是 LightFloodFill 的「无形状」哨兵；真实形状编号必须从 1 开始。
            // 否则域中的第一个遮挡方块会被当成空气，BFS 与官方引擎产生系统性偏差。
            domainShapeIds[dst] = allShapes.size() + 1;
            allShapes.add(shapeFaces[i]);
        }
    }

    /** 核心柱各 section 提取为 DataLayer 布局的 2048 字节半字节数组。 */
    private static byte[][] extractCore(byte[] domain, int minSection, int sectionCount, int height) {
        byte[][] out = new byte[sectionCount][];
        for (int s = 0; s < sectionCount; s++) {
            byte[] data = new byte[2048];
            int y0 = s * 16;
            for (int ly = 0; ly < 16; ly++) {
                int y = y0 + ly;
                if (y >= height) {
                    break;
                }
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        int v = domain[(y * W + (CORE_OFFSET + z)) * W + (CORE_OFFSET + x)] & 0xFF;
                        int di = (ly << 8) | (z << 4) | x;
                        data[di >> 1] |= (byte) (v << ((di & 1) * 4));
                    }
                }
            }
            out[s] = data;
        }
        return out;
    }

    private ExecutorService ensurePool() {
        ExecutorService p = pool;
        if (p == null || p.isShutdown()) {
            synchronized (this) {
                p = pool;
                if (p == null || p.isShutdown()) {
                    // 光照 solve 是纯 CPU 密集任务：固定平台线程池（parallelLightEngineThreads，FIFO）。
                    // 虚拟线程（每任务一线程、无并发上限）在进服 chunk 风暴下瞬时并发数百个 solve
                    // 互相抢核超订，单任务 wall 时间膨胀数倍（实测 1.21.11 R1 均值 29.6ms vs
                    // 平台池 ~5ms）；排队等待不计入后台耗时统计，FIFO 队列反而更可预测。
                    p = ExecutorFactory.createPlatform("hassium-light",
                            HassiumConfigService.getInstance().getParallelLightEngineThreads());
                    pool = p;
                }
            }
        }
        return p;
    }
}
