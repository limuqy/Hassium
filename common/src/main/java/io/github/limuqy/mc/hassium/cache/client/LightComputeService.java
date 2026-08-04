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

import java.util.ArrayList;
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
    private static final int[] OPPOSITE_DIR = {LightFloodFill.UP, LightFloodFill.DOWN,
            LightFloodFill.SOUTH, LightFloodFill.NORTH, LightFloodFill.EAST, LightFloodFill.WEST};

    private static final LightComputeService INSTANCE = new LightComputeService();

    public static LightComputeService getInstance() {
        return INSTANCE;
    }

    private final ConcurrentLinkedQueue<LightComputeResult> results = new ConcurrentLinkedQueue<>();
    private final LinkedHashMap<Long, LightColumnSnapshot> snapshotCache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, LightColumnSnapshot> eldest) {
            return size() > SNAPSHOT_CACHE_MAX;
        }
    };
    private volatile ExecutorService pool;

    private LightComputeService() {
    }

    /** 后台任务产物：核心柱各 section 的 sky/block 半字节数组（DataLayer 布局，2048 字节）。 */
    public record LightComputeResult(ChunkPos corePos, byte[][] skySections, byte[][] blockSections,
                                     CompoundTag cachedNbt) {
    }

    /**
     * 主线程提交重算（输入一致性：9 柱快照同一时刻捕获）。
     */
    public void submitRecompute(ChunkPos corePos, CompoundTag cachedNbt) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(corePos.x, corePos.z);
        if (chunk == null) {
            return;
        }
        int minSection = LevelHeightCompat.getMinSection(level);
        int maxSection = LevelHeightCompat.getMaxSectionExclusive(level);
        int sectionCount = maxSection - minSection;
        int minY = LevelHeightCompat.getMinBlockY(level);
        int height = level.getHeight();

        // ---- 主线程域组装（9 柱；未加载邻居用空占位）----
        int cells = W * W * height;
        byte[] domainLightBlock = new byte[cells];
        int[] domainShapeIds = new int[cells];
        int[] domainSourceY = new int[W * W];
        java.util.Arrays.fill(domainSourceY, LightFloodFill.NO_COLUMN);
        List<Integer> emitters = new ArrayList<>();
        List<VoxelShape[]> allShapes = new ArrayList<>();
        int[][] offsets = {{-1, -1}, {0, -1}, {1, -1}, {-1, 0}, {0, 0}, {1, 0}, {-1, 1}, {0, 1}, {1, 1}};

        for (int[] off : offsets) {
            int cx = corePos.x + off[0];
            int cz = corePos.z + off[1];
            LightColumnSnapshot snap;
            if (level.getChunkSource().getChunkNow(cx, cz) == null) {
                snap = LightColumnSnapshot.empty(minY, height);
            } else {
                snap = snapshotOrCapture(level, cx, cz);
            }
            assemble(snap, off[0], off[1], height,
                    domainLightBlock, domainShapeIds, domainSourceY, emitters, allShapes);
        }
        int[] emitterArr = emitters.stream().mapToInt(Integer::intValue).toArray();
        VoxelShape[][] shapeTable = allShapes.toArray(new VoxelShape[0][]);
        LightFloodFill.Occlusion occlusion = (srcShape, dstShape, dir) -> Shapes.faceShapeOccludes(
                shapeTable[srcShape][dir], shapeTable[dstShape][OPPOSITE_DIR[dir]]);

        ExecutorService p = ensurePool();
        p.execute(() -> {
            try {
                // 后台线程补充缓存 NBT（主线程调用方可能未携带）：读盘+ZSTD 解压
                // 在后台线程完成，避免 updateCacheWithLightData 的 fallback 在主线程读盘。
                net.minecraft.nbt.CompoundTag nbt = cachedNbt;
                if (nbt == null) {
                    nbt = io.github.limuqy.mc.hassium.network.ClientChunkHandler
                            .loadChunkNbtFromCache(corePos);
                }
                long backgroundStartNs = System.nanoTime();
                LightFloodFill.Result r = LightFloodFill.solve(W, height,
                        domainLightBlock, emitterArr, domainSourceY, domainShapeIds, occlusion);
                byte[][] skySections = extractCore(r.skyLight(), minSection, sectionCount, height);
                byte[][] blockSections = extractCore(r.blockLight(), minSection, sectionCount, height);
                NetworkStats.recordLightRecomputeBackgroundTime(System.nanoTime() - backgroundStartNs);
                results.add(new LightComputeResult(corePos, skySections, blockSections, nbt));
            } catch (Throwable t) {
                Constants.LOG.error("Hassium: Parallel light recompute failed for {}", corePos, t);
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
        LevelLightEngine lightEngine = level.getLightEngine();
        java.util.List<LightComputeResult> batch = new java.util.ArrayList<>();
        while (System.nanoTime() < deadlineNs) {
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

    /** 断连清理：清空结果队列、快照缓存并关闭线程池（下次提交重建）。 */
    public void clear() {
        results.clear();
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
        LevelChunk chunk = level.getChunkSource().getChunkNow(r.corePos().x, r.corePos().z);
        if (chunk == null) {
            return false; // 卸载竞态：丢弃
        }
        // 主线程光照应用耗时（同步路径同口径：applyLightEngine 的 finally 记录同一指标；
        // 批量共享的 runLightUpdates 与后台化写回不计入单结果）
        long mainThreadStartNs = System.nanoTime();
        try {
            applyResultEnqueueInner(level, lightEngine, r);
        } finally {
            NetworkStats.recordLightRecomputeTime(System.nanoTime() - mainThreadStartNs);
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
        StringBuilder samples = new StringBuilder();
        int sectionCount = Math.min(r.skySections().length, maxSection - minSection);
        for (int s = 0; s < sectionCount; s++) {
            int sectionY = minSection + s;
            SectionPos sp = SectionPos.of(r.corePos().x, sectionY, r.corePos().z);
            DataLayer skyOfficial = lightEngine.getLayerListener(LightLayer.SKY).getDataLayerData(sp);
            DataLayer blockOfficial = lightEngine.getLayerListener(LightLayer.BLOCK).getDataLayerData(sp);
            skyMismatch += compareLayer("sky", samples, sectionY, skyOfficial, r.skySections()[s]);
            blockMismatch += compareLayer("block", samples, sectionY, blockOfficial, r.blockSections()[s]);
        }
        mismatch = skyMismatch + blockMismatch;
        NetworkStats.recordLightVerifyMismatch(mismatch);
        if (mismatch > 0) {
            Constants.LOG.error("[LIGHT_VERIFY] chunk {} mismatch={} (sky={} block={})", r.corePos(), mismatch, skyMismatch, blockMismatch);
            if (samples.length() > 0) {
                Constants.LOG.error("[LIGHT_VERIFY-SAMPLE] {}", samples);
            }
        } else {
            Constants.LOG.debug("[LIGHT_VERIFY] chunk {} ok", r.corePos());
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


    private static int nibbleAt(byte[] data, int x, int y, int z) {
        int index = (y << 8) | (z << 4) | x;
        return (data[index >> 1] >> ((index & 1) * 4)) & 0xF;
    }

    private LightColumnSnapshot snapshotOrCapture(ClientLevel level, int cx, int cz) {
        long key = ChunkPos.asLong(cx, cz);
        synchronized (snapshotCache) {
            LightColumnSnapshot s = snapshotCache.get(key);
            if (s == null) {
                s = LightColumnSnapshot.capture(level, cx, cz);
                snapshotCache.put(key, s);
            }
            return s;
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
            domainShapeIds[dst] = allShapes.size();
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
                    p = ExecutorFactory.create("hassium-light",
                            HassiumConfigService.getInstance().getParallelLightEngineThreads());
                    pool = p;
                }
            }
        }
        return p;
    }
}
