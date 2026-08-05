package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.LevelHeightCompat;
import io.github.limuqy.mc.hassium.compat.LightAccessCompat;
import io.github.limuqy.mc.hassium.concurrent.ExecutorFactory;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;

/**
 * 并行光照引擎（方案 D）：后台线程池全量 BFS 重算 + 主线程原子提交。
 * <p>
 * 正确性依据：光照强度 0–15，每格传播成本 ≥ 1 → 任何格子的值只依赖 15 格半径内的
 * 光源与遮挡；任务域 = 核心柱 + 每方向 1 个柱（16 格）halo（= 3×3 区块），域外不可能
 * 影响核心柱结果 → 任务间无依赖、无需迭代、无需合并，可完全并行。
 * <p>
 * 主线程 {@link #drainCompletions} 批量提交（预算内）：建层 + 核心柱/邻柱差异 memcpy 落地
 * （后台自研传播域 W=48 = 核心柱 ±16 格，传播半径 15 全覆盖；邻居吸收不再走官方
 * 校准链 propagateLightSources/pullLightFromNeighborEdges/runLightUpdates，官方引擎仅保留
 * 低频原版触发兜底）；然后逐结果官方验算（{@code debug.lightVerify}：核心柱层清零后走
 * 官方引擎从零重算，官方结果 vs BFS 逐格对比，内芯 x/z ∈ [1,14]，边界差异属输入范围差异
 * 非错误；纯观察，验算后恢复世界）+ 缓存写回（写回经 CacheSaveQueue 后台化：主线程只组
 * NBT，压缩与写盘由后台单消费者执行）。
 * <p>
 * 默认关闭（{@code clientCache.parallelLightEngineEnabled}），现有同步路径为默认。
 * <p>
 * {@link #clear()} 可跨线程调用（断连清理在 1.21.11 Fabric 可能在 Netty IO 线程触发）：
 * 全部队列与映射使用并发容器。
 */
public final class LightComputeService {

    /**
     * BFS 域宽（柱）。3 柱（48 格）时核心柱边界裕量 16 格 ≥ sky 传播半径 15 → 结果与
     * 全量重算一致；2 柱（32 格）时裕量 8 格 < 15，核心柱边缘 ~7 格受域外影响产生偏差
     * （落地时由 pullLightFromNeighborEdges 差 1 校准吸收，代价是校准级联略增）。
     * sky BFS 占重算成本 ~90%，域面积 ×0.44 → 后台吞吐 ×2.3，黑块窗口显著缩短。
     * 2026-08-05 对照实验：2 柱域 + 校准吸收验证中（lightVerify 会报边缘偏差属预期）。
     */
    private static final int DOMAIN_CHUNKS = 2;
    private static final int W = DOMAIN_CHUNKS * 16;
    /**
     * W=32 求解域 = 世界 [core-8, core+24)，core 柱 = 域 [8,24)（两侧 halo 各 8 格）。
     * 2026-08-05 修正：此前 core 柱误置于 [16,32)，与 E/S halo [24,32) 重叠，
     * 提取出的核心柱 3/4 被邻柱数据覆盖（传播域落地改造时经覆盖算术验证发现）。
     */
    private static final int CORE_OFFSET = 8;
    /**
     * 传播域 = 核心柱 ±16 格（3 柱全宽，覆盖传播半径 15 + 1 格余量）。
     * 域布局：核心柱 [16,32)²；N 柱 z∈[0,16)；S 柱 z∈[32,48)；W 柱 x∈[0,16)；E 柱 x∈[32,48)。
     * 对角柱区域留空（对角柱距核心柱边缘 ≥16 格 > 传播半径 15，其影响由各自任务覆盖）。
     */
    private static final int PROP_W = 48;
    /** 传播域核心柱起点（两侧 halo 各 16 格 = 传播半径全覆盖）。 */
    private static final int PROP_CORE_OFFSET = 16;
    /** 传播域组装序：N/S/W/E（索引 0–3 = 结果邻柱掩码序）+ 核心柱（索引 4）。 */
    private static final int[][] PROP_OFFSETS = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}, {0, 0}};
    /** PROP_OFFSETS 各项在 DOMAIN_OFFSETS 中的下标（N/S/W/E/core → 1/7/3/5/4），capture 顺序固定。 */
    private static final int[] PROP_SNAPSHOT_INDEX = {1, 7, 3, 5, 4};
    /**
     * 3×3 域共享柱缓存容量。加载期 1700+ 块螺旋序 apply，邻任务提交时间相近（螺旋环
     * 相邻）→ 缓存需覆盖近期 1-2 环 ≈ 256 柱；实测 128 容量命中率仅 28-33%（每柱
     * 98KB，128 容量 ~12.5MB，256 ~25MB，可接受）。
     */
    private static final int SNAPSHOT_CACHE_MAX = 256;
    /**
     * 主线程单帧最多捕获的 16×16 柱数。每块完整域需 9 柱；按切片采样避免一个区块 apply
     * 同步占用整个帧，同时后台仍可并行处理已完成的不可变快照。
     * <p>
     * 吞吐必须 ≥ 提交速率：实测（1.20.1 fabric, seed=42, VD20）单柱捕获真实成本 ~450μs
     * （16×16×384 = 98304 次 getBlockState，接近内存带宽下限），3ms 预算仅 ~6.6 柱/帧
     * （132 柱/s）vs 提交 ~190 柱/s 新柱 → R1 完成率仅 30%（断连时 400+ 任务未重算，
     * 视觉暗块窗口）。24 柱/帧 + 5ms 预算 ≈ 220 柱/s 反超提交速率 → 加载期内完成全部
     * 任务、无暗块残留；加载期 tick 增加 ≤2ms（帧 50ms 上限内 avg 15-24ms 有余量）。
     */
    private static final int MAX_CAPTURE_COLUMNS_PER_FRAME = 24;
    private static final int MAX_COLUMNS_PER_CAPTURE_SLICE = 3;
    private static final long CAPTURE_BUDGET_NS = 5_000_000L;
    /**
     * 捕获等待邻居就绪的最大帧数。超过后该柱降级为空占位（视距边缘等永不到达场景），
     * 代价仅为边界 1 格可能偏暗；避免任务无限挂起。10 帧（0.5s）对加载期螺旋序 apply
     * 足够（邻居 1-5 帧内到达），更早兜底可缩短队列、减少轮转开销。
     */
    private static final int NEIGHBOR_WAIT_FRAMES = 10;
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

    /** 后台结果积压上限：结果含 expectedCoreChunk 强引用（阻止卸载块回收），容量即内存上界。 */
    private static final int MAX_PENDING_RESULTS = 128;
    private final java.util.concurrent.ArrayBlockingQueue<LightComputeResult> results =
            new java.util.concurrent.ArrayBlockingQueue<>(MAX_PENDING_RESULTS);
    /** 任意线程可 clear()（cleanupOnDisconnect 在 1.21.11 Fabric 可在 Netty IO 线程触发）：并发容器。
     * 距离优先调度：主线程帧尾按玩家实时位置重排（{@link #maybeResortCaptures}），重建时交换引用，
     * 因此必须 volatile；Netty 侧 add 与重建互斥见 {@link #captureQueueLock}。 */
    private volatile ConcurrentLinkedQueue<CaptureTask> pendingCaptures = new ConcurrentLinkedQueue<>();
    /** add（任意线程）与重建（主线程）互斥；capturePending 的 poll/放回是主线程独占，不经过此锁。 */
    private final Object captureQueueLock = new Object();
    /** 重排阈值：玩家水平位移超过 64 格才重建队列（飞行中约每秒一次，排序成本微秒级）。 */
    private static final double RESORT_DIST_SQ = 64.0 * 64.0;
    private double lastSortPlayerX = Double.NaN;
    private double lastSortPlayerZ = Double.NaN;
    private final Map<Long, CaptureTask> pendingByCore = new ConcurrentHashMap<>();
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
    private final java.util.concurrent.atomic.AtomicInteger diagSplit = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger diagProp = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger verifyInputSample = new java.util.concurrent.atomic.AtomicInteger();

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

    /** 传播阶段限频诊断（自研传播域工作量化，定位后移除）。 */
    private void diagProp(String msg, Object... args) {
        if (diagProp.getAndIncrement() < 20) {
            Constants.LOG.info("[LIGHT-PROP] " + msg, args);
        }
    }

    /**
     * 后台任务产物：核心柱各 section 的 sky/block 半字节数组（DataLayer 布局，2048 字节）；
     * 邻柱差异（neighbor*Masks[nb] 位 = section 相对 minSection 偏移，neighbor*Sections[nb] =
     * 变化 section 的打包结果；sectionCount > 64 时为 null = 邻柱传播降级）。
     */
    public record LightComputeResult(ChunkPos corePos, LevelChunk expectedCoreChunk,
                                     byte[][] skySections, byte[][] blockSections,
                                     long[] neighborSkyMasks, byte[][][] neighborSkySections,
                                     long[] neighborBlockMasks, byte[][][] neighborBlockSections,
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
        /** 核心柱 + 4 邻柱旧光照（DataLayer 打包 2048B/section × 2 层），供后台 diff 种子与传播域初始值。
         *  邻柱数组在 sectionCount > 64 时为 null（邻柱传播降级，掩码 long 装不下）。 */
        private final byte[][] coreOldSky;
        private final byte[][] coreOldBlock;
        private final byte[][][] neighborOldSky;   // [4] × [sectionCount] × 2048，PROP_OFFSETS 0–3 序（N/S/W/E）
        private final byte[][][] neighborOldBlock;
        private LevelChunk expectedCoreChunk;
        private CompoundTag cachedNbt;
        private int nextColumn;
        private int waitFrames;
        private long captureNanos;
        /** 本帧轮转过的时间戳（缺邻居放回队尾后，同帧重 poll 直接跳过，防轮转烧光预算）。 */
        private long lastRotatedNs;

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
            this.coreOldSky = new byte[sectionCount][];
            this.coreOldBlock = new byte[sectionCount][];
            if (sectionCount <= 64) {
                this.neighborOldSky = new byte[4][sectionCount][];
                this.neighborOldBlock = new byte[4][sectionCount][];
            } else {
                this.neighborOldSky = null;
                this.neighborOldBlock = null;
            }
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
            lastRotatedNs = 0L;
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
        synchronized (captureQueueLock) {
            pendingCaptures.add(task);
        }
    }

    /**
     * 距离优先调度：玩家水平位移超阈值时按「当前玩家位置」重建 capture 队列（近处任务先
     * capture → 先入后台池 → 先亮）。刻意用执行时刻的实时距离而非入队时快照：飞行中已入队
     * 的边缘任务，玩家飞近后若区块没有新的重算触发（无变化源），快照语义会让它永远低优先，
     * 视野中心黑块滞留；实时重排成本可忽略（任务数几十 × 距离平方，微秒级）。
     * <p>
     * 并发：本方法只由主线程调用；Netty 侧 add 经 {@link #captureQueueLock} 与重建互斥，
     * poll/放回（主线程独占）无锁读 volatile 引用，重建前后都合法。
     */
    private void maybeResortCaptures() {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        double px = player.getX();
        double pz = player.getZ();
        double dx = px - lastSortPlayerX;
        double dz = pz - lastSortPlayerZ;
        if (dx * dx + dz * dz < RESORT_DIST_SQ) {
            return;
        }
        lastSortPlayerX = px;
        lastSortPlayerZ = pz;
        synchronized (captureQueueLock) {
            CaptureTask[] arr = pendingCaptures.toArray(new CaptureTask[0]);
            if (arr.length < 2) {
                return;
            }
            double fx = px;
            double fz = pz;
            Arrays.sort(arr, Comparator.comparingDouble(t -> distSqToPlayer(t.corePos, fx, fz)));
            pendingCaptures = new ConcurrentLinkedQueue<>(Arrays.asList(arr));
        }
    }

    private static double distSqToPlayer(ChunkPos pos, double px, double pz) {
        double dx = (pos.x * 16 + 8) - px;
        double dz = (pos.z * 16 + 8) - pz;
        return dx * dx + dz * dz;
    }

    /**
     * 在 Render thread 采样少量柱。每一柱完整读取，因而不会把 {@link ClientLevel}/{@code BlockState}
     * 访问泄漏到后台；完整 3×3 域一旦就绪，之后的数组组装、BFS、NBT 回读全在 CPU 池完成。
     */
    private void capturePending(ClientLevel level, long outerDeadlineNs) {
        maybeResortCaptures();
        // 预算独立于 outerDeadlineNs：加载期帧预算被 apply/flush 挤压时 capture 会饿死
        // （实测仅 ~5 柱/帧 ≈ 100 柱/s，vs 提交 ~190 柱/s 新柱）→ R1 尾部积压数百任务、
        // 断连时 400+ 块未重算（视觉暗块）。保底预算 + 单柱直读 + 轮转防重 poll 后实测
        // 吞吐 ≈ 220 柱/s 反超提交速率 → 加载期内全部完成、结束后零残留税。
        long captureDeadlineNs = System.nanoTime() + CAPTURE_BUDGET_NS;
        long frameNs = captureDeadlineNs; // 本帧标识：轮转任务本帧只处理一次
        CaptureTask lastSkipped = null;
        int captured = 0;
        while (captured < MAX_CAPTURE_COLUMNS_PER_FRAME && System.nanoTime() < captureDeadlineNs) {
            CaptureTask task = pendingCaptures.poll();
            if (task == null) {
                return;
            }
            if (task.lastRotatedNs == frameNs) {
                // 本帧已轮转过（缺邻居放回）：跳过重 poll。实测队列 500 个缺邻居任务时
                // 无此防护每帧 poll 数百次烧光预算（轮转税 = 每帧 +3ms、有效采柱仅
                // ~5 柱/帧 → 队列越长完成越慢，R1 完成率 25%）。转满一圈即无采柱
                // 可能，提前退出省预算。
                if (task == lastSkipped) {
                    pendingCaptures.add(task);
                    return;
                }
                if (lastSkipped == null) {
                    lastSkipped = task;
                }
                pendingCaptures.add(task);
                continue;
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
            boolean deferred = false;
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
                    // 注意：轮转只允许放回一次——若在此 add 后再走外层「未完成放回」，
                    // 同一对象会在队列中持有两个引用：重复提交 + 队列虚长（实测空闲期
                    // capture 预算被持续烧满、stats 重算计数虚增 35）。
                    if (task.waitFrames < NEIGHBOR_WAIT_FRAMES) {
                        task.waitFrames++;
                        deferred = true;
                        break;
                    }
                }
                long captureStartNs = System.nanoTime();
                try {
                    task.snapshots[task.nextColumn] = chunk == null
                            ? LightColumnSnapshot.empty(task.minY, task.height)
                            : snapshotOrCapture(level, chunk, cx, cz);
                    // 核心柱 / 4 邻柱：立即抓旧光照（克隆防后台读与官方写竞态；缓存命中仍重抓，无害）。
                    // 对角柱不需旧光照：传播域不含对角柱区域。null 层 = 全 0（无光）。
                    // sectionCount > 64 时邻柱传播降级（掩码 long 装不下），不抓邻柱旧值。
                    int nbIdx = neighborIndexFor(off);
                    boolean needOldLight = (off[0] == 0 && off[1] == 0)
                            || (nbIdx >= 0 && task.neighborOldSky != null);
                    if (needOldLight) {
                        captureOldLight(level, task, cx, cz, nbIdx);
                    }
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
            if (deferred) {
                // 轮转：本帧不再碰该任务，避免同帧内自旋重 poll 同一对象
                task.lastRotatedNs = frameNs;
                pendingCaptures.add(task);
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
                // block/sky 分离计算（等价合并计算，独立计时；数据支持 sky 优先落地的分离提交方案）
                long splitT0 = System.nanoTime();
                byte[] blockLight = LightFloodFill.solveBlock(W, task.height,
                        domainLightBlock, emitterArr, domainShapeIds, occlusion);
                long splitT1 = System.nanoTime();
                byte[] skyLight = LightFloodFill.solveSky(W, task.height,
                        domainLightBlock, domainSourceY, domainShapeIds, occlusion);
                long splitT2 = System.nanoTime();
                if (diagSplit.getAndIncrement() < 20) {
                    Constants.LOG.info("[LIGHT-DIAG-SPLIT] block={}us sky={}us total={}us",
                            (splitT1 - splitT0) / 1000, (splitT2 - splitT1) / 1000, (splitT2 - splitT0) / 1000);
                }
                // --- 传播阶段：核心柱 BFS 结果向邻居柱的后台传播（主线程落地只做 memcpy）---
                // 传播域 W=48 = 核心柱 ±16 格（传播半径 15 + 1 格余量），9 柱全宽组装（含对角柱）；
                // 邻柱旧值装域 + 核心柱新值 diff → 种子 → 增量传播（官方 propagateIncrease 语义，
                // 只增不减）；整体变暗（decrease 主导）走模式 B：W=48 全域重算兜底。
                long propT0 = System.nanoTime();
                int ww32 = W * W;
                int ww48 = PROP_W * PROP_W;
                byte[] propSky = new byte[ww48 * task.height];
                byte[] propBlock = new byte[ww48 * task.height];
                byte[] propLight = new byte[ww48 * task.height];   // 遮挡（每格 lightBlock）
                int[] propShape = new int[ww48 * task.height];
                int[] propSourceY = new int[ww48];
                Arrays.fill(propSourceY, LightFloodFill.NO_COLUMN);
                List<Integer> propEmitters = new ArrayList<>();
                List<VoxelShape[]> propShapes = new ArrayList<>();
                // 9 柱全宽组装：核心柱 ±16 格全域。必须含对角柱——光按曼哈顿距离传播，
                // 对角柱内距离核心柱内格 ≤15 的光源（如 (16,16) 距 (14,14) 仅 4 格）
                // 会照进核心柱；5 柱域缺角会系统性偏暗（官方验算实测：缺角时 ~17% 内芯格偏暗）。
                for (int i = 0; i < DOMAIN_OFFSETS.length; i++) {
                    int[] off = DOMAIN_OFFSETS[i];
                    assembleFull(task.snapshots[i], off[0], off[1], task.height,
                            propLight, propShape, propSourceY, propEmitters, propShapes);
                }
                int[] emitter48 = propEmitters.stream().mapToInt(Integer::intValue).toArray();
                VoxelShape[][] shapeTable48 = propShapes.toArray(new VoxelShape[0][]);
                LightFloodFill.Occlusion occlusion48 = (srcShape, dstShape, dir) -> false; // 实验：禁形状遮挡

                long[] skySeeds = new long[0];
                long[] blockSeeds = new long[0];
                // 实验（验算归因）：强制全域重算，验证 W=32 2 柱域 halo 截断是误差主因
                boolean modeB = true;
                if (task.sectionCount <= 64) {
                    // 邻柱旧值解码装域（4bit → 8bit，柱区原点 ox/oz）
                    for (int nb = 0; nb < 4; nb++) {
                        int[] off = PROP_OFFSETS[nb];
                        int ox = off[0] == 0 ? PROP_CORE_OFFSET : (off[0] < 0 ? 0 : 32);
                        int oz = off[1] == 0 ? PROP_CORE_OFFSET : (off[1] < 0 ? 0 : 32);
                        decodeOldColumn(task.neighborOldSky[nb], propSky, ox, oz, task, ww48);
                        decodeOldColumn(task.neighborOldBlock[nb], propBlock, ox, oz, task, ww48);
                    }
                    // 核心柱 diff：newVal > oldVal → 种子（新值预写后传播）；< → decrease 计数
                    List<Long> skySeedList = new ArrayList<>();
                    List<Long> blockSeedList = new ArrayList<>();
                    int increase = 0;
                    int decrease = 0;
                    for (int s = 0; s < task.sectionCount; s++) {
                        int y0 = s * 16;
                        for (int ly = 0; ly < 16; ly++) {
                            int y = y0 + ly;
                            if (y >= task.height) {
                                break;
                            }
                            for (int x = 0; x < 16; x++) {
                                for (int z = 0; z < 16; z++) {
                                    int di = (ly << 8) | (z << 4) | x;
                                    int idx32 = y * ww32 + (CORE_OFFSET + z) * W + (CORE_OFFSET + x);
                                    int idx48 = y * ww48 + (PROP_CORE_OFFSET + z) * PROP_W + (PROP_CORE_OFFSET + x);
                                    int oldS = (task.coreOldSky[s][di >> 1] >> ((di & 1) * 4)) & 0xF;
                                    int newS = skyLight[idx32] & 0xFF;
                                    if (newS > oldS) {
                                        skySeedList.add(LightFloodFill.buildSeed(idx48, newS,
                                                LightFloodFill.ALL_DIRS, propShape[idx48] == 0, 1));
                                        increase++;
                                    } else if (newS < oldS) {
                                        decrease++;
                                    }
                                    int oldB = (task.coreOldBlock[s][di >> 1] >> ((di & 1) * 4)) & 0xF;
                                    int newB = blockLight[idx32] & 0xFF;
                                    if (newB > oldB) {
                                        blockSeedList.add(LightFloodFill.buildSeed(idx48, newB,
                                                LightFloodFill.ALL_DIRS, propShape[idx48] == 0, 0));
                                        increase++;
                                    } else if (newB < oldB) {
                                        decrease++;
                                    }
                                }
                            }
                        }
                    }
                    skySeeds = skySeedList.stream().mapToLong(Long::longValue).toArray();
                    blockSeeds = blockSeedList.stream().mapToLong(Long::longValue).toArray();
                    // 模式判定：整体变暗（decrease 主导，缓存修正等罕见场景）→ 全域重算兜底；
                    // 日常 BFS 与官方 1 级边缘偏差 → 模式 A 增量传播（只增不减，残留偏亮单调自愈）
                    modeB = decrease > increase;
                }
                if (modeB) {
                    // 全域重算：5 柱全宽 solve（邻柱区已由 solve 算出，旧值无需装域）
                    propSky = LightFloodFill.solveSky(PROP_W, task.height, propLight, propSourceY, propShape, occlusion48);
                    propBlock = LightFloodFill.solveBlock(PROP_W, task.height, propLight, emitter48, propShape, occlusion48);
                } else {
                    // 模式 A：核心柱新值预写（种子格值必须与种子 level 逐位相等）+ 增量传播
                    for (int s = 0; s < task.sectionCount; s++) {
                        int y0 = s * 16;
                        for (int ly = 0; ly < 16; ly++) {
                            int y = y0 + ly;
                            if (y >= task.height) {
                                break;
                            }
                            for (int x = 0; x < 16; x++) {
                                for (int z = 0; z < 16; z++) {
                                    int idx32 = y * ww32 + (CORE_OFFSET + z) * W + (CORE_OFFSET + x);
                                    int idx48 = y * ww48 + (PROP_CORE_OFFSET + z) * PROP_W + (PROP_CORE_OFFSET + x);
                                    propSky[idx48] = skyLight[idx32];
                                    propBlock[idx48] = blockLight[idx32];
                                }
                            }
                        }
                    }
                    if (skySeeds.length > 0) {
                        LightFloodFill.propagate(PROP_W, task.height, propSky, propLight, propShape, occlusion48, skySeeds, 1);
                    }
                    if (blockSeeds.length > 0) {
                        LightFloodFill.propagate(PROP_W, task.height, propBlock, propLight, propShape, occlusion48, blockSeeds, 0);
                    }
                }
                long propT1 = System.nanoTime();
                // 提取：核心柱 + 邻柱差异（掩码位 = 变化 section）
                byte[][] skySections = extractRegion(propSky, PROP_CORE_OFFSET, PROP_CORE_OFFSET,
                        task.minSection, task.sectionCount, task.height);
                byte[][] blockSections = extractRegion(propBlock, PROP_CORE_OFFSET, PROP_CORE_OFFSET,
                        task.minSection, task.sectionCount, task.height);
                long[] neighborSkyMasks = null;
                long[] neighborBlockMasks = null;
                byte[][][] neighborSkySections = null;
                byte[][][] neighborBlockSections = null;
                if (task.sectionCount <= 64) {
                    neighborSkyMasks = new long[4];
                    neighborBlockMasks = new long[4];
                    neighborSkySections = new byte[4][][];
                    neighborBlockSections = new byte[4][][];
                    for (int nb = 0; nb < 4; nb++) {
                        int[] off = PROP_OFFSETS[nb];
                        int ox = off[0] == 0 ? PROP_CORE_OFFSET : (off[0] < 0 ? 0 : 32);
                        int oz = off[1] == 0 ? PROP_CORE_OFFSET : (off[1] < 0 ? 0 : 32);
                        neighborSkySections[nb] = diffNeighborColumn(propSky, ox, oz, task,
                                task.neighborOldSky[nb], neighborSkyMasks, nb);
                        neighborBlockSections[nb] = diffNeighborColumn(propBlock, ox, oz, task,
                                task.neighborOldBlock[nb], neighborBlockMasks, nb);
                    }
                }
                long propT2 = System.nanoTime();
                // 诊断：域内发射源总数 + solve 后非零格数（排查发射源捕获/求解缺失）
                int emitN = 0;
                for (int i = 0; i < DOMAIN_OFFSETS.length; i++) {
                    emitN += task.snapshots[i].getEmitters().length;
                }
                int blockNonZero = 0;
                for (int s = 0; s < task.sectionCount; s++) {
                    int y0 = s * 16;
                    int y1 = Math.min(y0 + 16, task.height);
                    for (int y = y0; y < y1; y++) {
                        int base = y * ww48;
                        for (int i = 0; i < ww48; i++) {
                            blockNonZero += (propBlock[base + i] & 0xFF) != 0 ? 1 : 0;
                        }
                    }
                }
                diagProp("mode={} skySeeds={} blockSeeds={} prop={}us extract={}us neighSections={} emitN={} blockNonZero={}",
                        modeB ? "B" : "A", skySeeds.length, blockSeeds.length,
                        (propT1 - propT0) / 1000, (propT2 - propT1) / 1000,
                        neighborSectionCount(neighborSkyMasks, neighborBlockMasks), emitN, blockNonZero);
                NetworkStats.recordLightRecomputeBackgroundTime(System.nanoTime() - backgroundStartNs);
                diagBg("done {} elapsed={}us", task.corePos, (System.nanoTime() - backgroundStartNs) / 1000);
                if (task.generation == generation) {
                    if (!results.offer(new LightComputeResult(task.corePos, task.expectedCoreChunk,
                            skySections, blockSections,
                            neighborSkyMasks, neighborSkySections,
                            neighborBlockMasks, neighborBlockSections,
                            nbt, task.generation, task.captureNanos))) {
                        diagBg("results full, dropped {}", task.corePos);
                    }
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
     * 单阶段：预算内循环对每个结果做「建层 + 主线程 memcpy 落地」（自研传播域已在后台完成，
     * 含核心柱全 section 与邻柱差异 section），逐结果入批；批尾无官方 runLightUpdates——
     * 自研任务不再入官方传播队列，官方队列仅剩原版触发兜底（帧尾
     * {@code ClientLightRecomputeService.flushPendingCalibrations}）。落地后逐结果做
     * 验算对比 + 缓存写回（写回本身已后台化，见 CacheSaveQueue）。
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

        // 1. 建层（storage 未建层的 section 在 memcpy 后不生效）
        ClientLightRecomputeService.ensureColumnDataLayers(level, lightEngine, r.corePos(), minSection, maxSection);
        ClientLightRecomputeService.ensureNeighborDataLayers(level, lightEngine, r.corePos(), minSection, maxSection);

        // 2. 核心柱全 section memcpy（DataLayer.getData() 返回内部数组，覆盖内容即官方 swap 落地；
        //    渲染/缓存/序列化全部读这份数组。原 queueSectionData+批尾 runLightUpdates 已由
        //    后台自研传播域替代——任务不再入官方传播队列，主线程税只剩 memcpy+脏标记 ~0.2–0.5ms）
        int sectionCount = Math.min(r.skySections().length, maxSection - minSection);
        LayerLightEventListener skyListener = lightEngine.getLayerListener(LightLayer.SKY);
        LayerLightEventListener blockListener = lightEngine.getLayerListener(LightLayer.BLOCK);
        for (int s = 0; s < sectionCount; s++) {
            SectionPos sp = SectionPos.of(r.corePos().x, minSection + s, r.corePos().z);
            DataLayer sky = skyListener.getDataLayerData(sp);
            if (sky != null) {
                System.arraycopy(r.skySections()[s], 0, sky.getData(), 0, 2048);
            }
            DataLayer block = blockListener.getDataLayerData(sp);
            if (block != null) {
                System.arraycopy(r.blockSections()[s], 0, block.getData(), 0, 2048);
            }
            level.setSectionDirtyWithNeighbors(r.corePos().x, minSection + s, r.corePos().z);
        }

        // 3. 邻柱差异 memcpy（掩码位 → 变化 section；null 层 = 邻柱未加载/未建层 → 跳过，
        //    其加载时自会重算；sectionCount > 64 时 masks 为 null = 邻柱传播降级）
        long[] skyMasks = r.neighborSkyMasks();
        long[] blockMasks = r.neighborBlockMasks();
        if (skyMasks != null && blockMasks != null) {
            for (int nb = 0; nb < 4; nb++) {
                int nx = r.corePos().x + PROP_OFFSETS[nb][0];
                int nz = r.corePos().z + PROP_OFFSETS[nb][1];
                copyMasked(r.neighborSkySections()[nb], skyMasks[nb], nx, nz, skyListener, minSection, level);
                copyMasked(r.neighborBlockSections()[nb], blockMasks[nb], nx, nz, blockListener, minSection, level);
            }
        }
        // 无 queueSectionData / 无 propagateLightSources / 无 pullLightFromNeighborEdges：
        // 官方传播队列不再有自研任务，批尾 runLightUpdates 已从 drainCompletions 移除。
    }

    /** 掩码位 → 变化 section 的 memcpy（null 层防御：邻柱未加载/未建层时跳过，加载时自会重算）。 */
    private static void copyMasked(byte[][] sections, long mask, int chunkX, int chunkZ,
                                   LayerLightEventListener listener, int minSection, ClientLevel level) {
        for (int s = 0; s < sections.length; s++) {
            if ((mask >>> s & 1L) != 0) {
                SectionPos sp = SectionPos.of(chunkX, minSection + s, chunkZ);
                DataLayer layer = listener.getDataLayerData(sp);
                if (layer != null) {
                    System.arraycopy(sections[s], 0, layer.getData(), 0, 2048);
                    level.setSectionDirtyWithNeighbors(chunkX, minSection + s, chunkZ);
                }
            }
        }
    }

    /** 阶段二（批尾统一落地后）：官方验算（可选）+ 缓存写回（写回入后台队列）。 */
    private void applyResultPost(ClientLevel level, LevelLightEngine lightEngine, LightComputeResult r) {
        HassiumConfigService cfg = HassiumConfigService.getInstance();
        int minSection = LevelHeightCompat.getMinSection(level);
        int maxSection = LevelHeightCompat.getMaxSectionExclusive(level);
        if (cfg.isLightVerifyEnabled()) {
            // 官方引擎从零重算对照（纯观察，验算后恢复世界 = 我们的输出）：
            // memcpy 落地后读层 == 输入（恒等），必须让官方算法独立算一遍才有对比意义。
            verifyWithOfficial(level, lightEngine, r, minSection, maxSection);
        }
        if (cfg.isLightCacheEnabled()) {
            ClientLightRecomputeService.updateCacheWithLightData(level, r.corePos(), r.cachedNbt());
        }
    }

    /**
     * 官方引擎从零重算对照（debug.lightVerify，默认关；每结果执行一次，纯观察不改世界）：
     * 1) 快照 8 邻柱（含对角）当前层 → 2) 核心柱 DataLayer 清零（官方重算的初始状态 = 新块无光照）
     * → 3) 官方机制重算（同同步路径 applyLightEngine：setLightEnabled + propagateLightSources
     *    + pullLightFromNeighborEdges + runLightUpdates —— 官方算法在真实世界状态上的独立结果）
     * → 4) 读回官方结果 → 5) 恢复：核心柱 ← 我们的 memcpy 值、8 邻柱 ← 快照（官方扩散增量回收）
     * → 6) 逐格对比（内芯 x/z ∈ [1,14]；边界差异属输入范围差异，单独计 edge）。
     * 差异 ≠ 0 即 BFS/传播域的确定性错误（官方层清零后不依赖我们的任何值）。
     */
    private void verifyWithOfficial(ClientLevel level, LevelLightEngine lightEngine, LightComputeResult r,
                                    int minSection, int maxSection) {
        int sectionCount = Math.min(r.skySections().length, maxSection - minSection);
        LayerLightEventListener skyListener = lightEngine.getLayerListener(LightLayer.SKY);
        LayerLightEventListener blockListener = lightEngine.getLayerListener(LightLayer.BLOCK);
        // 1. 快照 8 邻柱（DOMAIN_OFFSETS 去掉核心柱）
        byte[][][] snapSky = new byte[8][][];
        byte[][][] snapBlock = new byte[8][][];
        int snapIdx = 0;
        for (int[] off : DOMAIN_OFFSETS) {
            if (off[0] == 0 && off[1] == 0) {
                continue;
            }
            snapSky[snapIdx] = snapshotColumn(skyListener, r.corePos().x + off[0], r.corePos().z + off[1],
                    minSection, sectionCount);
            snapBlock[snapIdx] = snapshotColumn(blockListener, r.corePos().x + off[0], r.corePos().z + off[1],
                    minSection, sectionCount);
            snapIdx++;
        }
        // 2. 层清零（官方从零重算的初始状态）。第 1 个验算块额外清零整个 3×3 域并重播全部
        //    9 柱种子——官方引擎在【与自研 solve 完全相同的边界】上计算，剩余差异 = 纯算法缺陷；
        //    常规块只清零核心柱（邻柱 stored 值 = 真实世界边界，含域外光源）。
        boolean zero3x3 = verifyInputSample.get() == 0;
        for (int[] off : DOMAIN_OFFSETS) {
            if (!zero3x3 && !(off[0] == 0 && off[1] == 0)) {
                continue;
            }
            for (int s = 0; s < sectionCount; s++) {
                SectionPos sp = SectionPos.of(r.corePos().x + off[0], minSection + s, r.corePos().z + off[1]);
                zeroLayer(skyListener.getDataLayerData(sp));
                zeroLayer(blockListener.getDataLayerData(sp));
            }
        }
        // 3. 官方机制重算（同步路径同款；失败则本轮验算作废，恢复后继续）
        try {
            if (zero3x3) {
                // 3×3 全域重播：9 柱种子（天空高度图 + 发射源），无邻柱边界光可拉
                for (int[] off : DOMAIN_OFFSETS) {
                    ChunkPos cp = new ChunkPos(r.corePos().x + off[0], r.corePos().z + off[1]);
                    lightEngine.setLightEnabled(cp, true);
                    lightEngine.propagateLightSources(cp);
                }
            } else {
                lightEngine.setLightEnabled(r.corePos(), true);
                lightEngine.propagateLightSources(r.corePos());
                ClientLightRecomputeService.pullLightFromNeighborEdges(level, r.corePos(), minSection, maxSection);
            }
            ClientLightRecomputeService.safeRunLightUpdates(lightEngine);
        } catch (Throwable t) {
            Constants.LOG.warn("Hassium: light verify oracle failed for {}", r.corePos(), t);
        }
        // 4. 读回官方结果（null 层 = 全 0）
        byte[][] officialSky = new byte[sectionCount][];
        byte[][] officialBlock = new byte[sectionCount][];
        for (int s = 0; s < sectionCount; s++) {
            SectionPos sp = SectionPos.of(r.corePos().x, minSection + s, r.corePos().z);
            DataLayer sky = skyListener.getDataLayerData(sp);
            officialSky[s] = sky == null ? new byte[2048] : sky.getData().clone();
            DataLayer block = blockListener.getDataLayerData(sp);
            officialBlock[s] = block == null ? new byte[2048] : block.getData().clone();
        }
        if (zero3x3) {
            // 邻柱（W）读回：官方传播在 W 柱的值（对比自研 W 柱解算值）
            byte[][] offW = new byte[sectionCount][];
            byte[][] offWB = new byte[sectionCount][];
            for (int s = 0; s < sectionCount; s++) {
                SectionPos wsp = SectionPos.of(r.corePos().x - 1, minSection + s, r.corePos().z);
                DataLayer wsky = skyListener.getDataLayerData(wsp);
                offW[s] = wsky == null ? new byte[2048] : wsky.getData().clone();
                DataLayer wblk = blockListener.getDataLayerData(wsp);
                offWB[s] = wblk == null ? new byte[2048] : wblk.getData().clone();
            }
            long zs = 0, zb = 0;
            StringBuilder zsamples = new StringBuilder();
            for (int s = 0; s < sectionCount; s++) {
                zs += diffLayers(officialSky[s], r.skySections()[s]);
                zb += diffLayers(officialBlock[s], r.blockSections()[s]);
                int sectionY = minSection + s;
                for (int y = 0; y < 16 && zsamples.length() < 1500; y++) {
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            int o = nibbleAt(officialSky[s], x, y, z);
                            int b = nibbleAt(r.skySections()[s], x, y, z);
                            if (o != b && zsamples.length() < 1500) {
                                zsamples.append("s(").append(x).append(',').append(sectionY * 16 + y).append(',').append(z)
                                        .append(")o=").append(o).append("b=").append(b).append(' ');
                            }
                            int ob = nibbleAt(officialBlock[s], x, y, z);
                            int bb = nibbleAt(r.blockSections()[s], x, y, z);
                            if (ob != bb && zsamples.length() < 1500) {
                                zsamples.append("b(").append(x).append(',').append(sectionY * 16 + y).append(',').append(z)
                                        .append(")o=").append(ob).append("b=").append(bb).append(' ');
                            }
                        }
                    }
                }
            }
            Constants.LOG.error("[LIGHT_VERIFY-ZERO3X3] chunk {} sky={} block={} {}", r.corePos(), zs, zb, zsamples);
            // 差异格现场：官方 vs 自研 vs LIVE 方块 lightBlock（澄清 opacity 输入是否一致）
            StringBuilder ctx = new StringBuilder();
            for (int s = 0; s < sectionCount && ctx.length() < 1200; s++) {
                int sectionY = minSection + s;
                for (int y = 0; y < 16 && ctx.length() < 1200; y++) {
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            int ob = nibbleAt(officialBlock[s], x, y, z);
                            int bb = nibbleAt(r.blockSections()[s], x, y, z);
                            if (ob != bb && Math.abs(ob - bb) >= 3) {
                                int yy = sectionY * 16 + y;
                                int wmin = LevelHeightCompat.getMinBlockY(level);
                                if (yy >= wmin && yy < wmin + level.getHeight()) {
                                    BlockPos bp = new BlockPos(r.corePos().x * 16 + x, yy, r.corePos().z * 16 + z);
                                    BlockState bs = level.getBlockState(bp);
                                    ctx.append("b(").append(x).append(',').append(yy).append(',').append(z)
                                            .append(")o=").append(ob).append("b=").append(bb)
                                            .append("lb=").append(LightAccessCompat.getLightBlock(bs, level, bp))
                                            .append(' ').append(bs.getBlock()).append(' ');
                                }
                            }
                        }
                    }
                }
            }
            if (ctx.length() > 0) {
                Constants.LOG.error("[LIGHT_VERIFY-CTX] chunk {} {}", r.corePos(), ctx);
            }
            // 发射源探针：官方 vs 自研 在发射源格 + 6 邻域的值——分歧起点在种子还是传播路径
            StringBuilder probe = new StringBuilder();
            for (int i = 0; i < DOMAIN_OFFSETS.length; i++) {
                int[] off = DOMAIN_OFFSETS[i];
                LevelChunk pchunk = level.getChunkSource().getChunkNow(r.corePos().x + off[0], r.corePos().z + off[1]);
                if (pchunk == null) {
                    continue;
                }
                LightColumnSnapshot psnap = LightColumnSnapshot.capture(level, pchunk);
                int cx0 = r.corePos().x * 16 + off[0] * 16;
                int cz0 = r.corePos().z * 16 + off[1] * 16;
                for (int e : psnap.getEmitters()) {
                    int emission = e >>> 20;
                    int cell = e & 0xFFFFF;
                    int y = (cell >> 8) + LevelHeightCompat.getMinBlockY(level);
                    int z = (cell >> 4) & 0xF;
                    int x = cell & 0xF;
                    if (probe.length() >= 1800) {
                        break;
                    }
                    probe.append("E(").append(cx0 + x).append(',').append(y).append(',').append(cz0 + z)
                            .append(")em=").append(emission);
                    for (int dy = -1; dy <= 1; dy++) {
                        int yy = y + dy;
                        int sec = SectionPos.blockToSectionCoord(yy);
                        int li = sec - minSection;
                        if (li < 0 || li >= sectionCount) {
                            continue;
                        }
                        int lo = nibbleAt(officialBlock[li], x, yy & 15, z);
                        int lb = nibbleAt(r.blockSections()[li], x, yy & 15, z);
                        probe.append('[').append(dy).append(':').append(lo).append('/').append(lb).append(']');
                    }
                    probe.append(' ');
                }
            }
            Constants.LOG.error("[LIGHT_VERIFY-EMITTERS] chunk {} {}", r.corePos(), probe);
            // 柱状剖面：W 柱发射源沿 y 方向 8 格——官方 vs 自研（找传播分歧起点）
            if (r.neighborBlockSections() != null && r.neighborBlockSections().length > 2) {
                StringBuilder shaft = new StringBuilder();
                LevelChunk wchunk = level.getChunkSource().getChunkNow(r.corePos().x - 1, r.corePos().z);
                if (wchunk != null) {
                    LightColumnSnapshot wsnap = LightColumnSnapshot.capture(level, wchunk);
                    for (int e : wsnap.getEmitters()) {
                        int emission = e >>> 20;
                        int cell = e & 0xFFFFF;
                        int y = (cell >> 8) + LevelHeightCompat.getMinBlockY(level);
                        int z = (cell >> 4) & 0xF;
                        int x = cell & 0xF;
                        if (shaft.length() >= 2200) {
                            break;
                        }
                        shaft.append("T(").append(x).append(',').append(y).append(',').append(z)
                                .append(")em=").append(emission).append(' ');
                        for (int dy = -2; dy <= 8; dy++) {
                            int yy = y + dy;
                            int sec = SectionPos.blockToSectionCoord(yy);
                            int li = sec - minSection;
                            if (li < 0 || li >= sectionCount) {
                                continue;
                            }
                            int o = nibbleAt(offWB[li], x, yy & 15, z);
                            int b = nibbleAt(r.neighborBlockSections()[2][li], x, yy & 15, z);
                            int lbv = nibbleAt(wsnap.getLightBlock(), x, yy & 15, z);
                            shaft.append(dy).append('{').append(lbv).append(',').append(o).append(',').append(b).append("} ");
                        }
                    }
                }
                Constants.LOG.error("[LIGHT_VERIFY-SHAFT] chunk {} {}", r.corePos(), shaft);
            }
        }
        // 4b. 重放对比（仅第 1 块）：主线程用【全新】9 柱捕获重跑同一 solve——区分
        //     背景任务快照过期/缺角（任务时邻块未加载 → empty 兜底 → 缺光）vs BFS 算法缺陷。
        //     replayVsR > 0 → 任务输入过期；replayVsOfficial 大 → 算法本身偏离官方。
        if (verifyInputSample.get() == 0) {
            byte[][] replaySky = new byte[sectionCount][];
            byte[][] replayBlock = new byte[sectionCount][];
            boolean replayOk = replaySolve(level, r, minSection, sectionCount, replaySky, replayBlock,
                    officialSky, officialBlock);
            if (replayOk) {
                long rs = 0, rb = 0, so = 0, bo = 0;
                for (int s = 0; s < sectionCount; s++) {
                    rs += diffLayers(replaySky[s], r.skySections()[s]);
                    rb += diffLayers(replayBlock[s], r.blockSections()[s]);
                    so += diffLayers(replaySky[s], officialSky[s]);
                    bo += diffLayers(replayBlock[s], officialBlock[s]);
                }
                Constants.LOG.error("[LIGHT_VERIFY-REPLAY] chunk {} replayVsR sky={} block={} replayVsOfficial sky={} block={}",
                        r.corePos(), rs, rb, so, bo);
            } else {
                Constants.LOG.error("[LIGHT_VERIFY-REPLAY] chunk {} FAILED (neighbor not loaded at verify time)", r.corePos());
            }
        }
        // 4c. 稳定性对比（不置零）：官方机制在【我们的值】之上继续增长——增长数 > 0 即我们的
        //     解不是官方算法的不动点（自研 BFS 少传播）；=0 则分歧来自验算 oracle 的输入/路径。
        //     仅对前 2 个 mismatch 块执行（verify 模式开销可接受）。
        if (verifyInputSample.get() < 2) {
            long increased = stabilityPass(level, lightEngine, skyListener, blockListener,
                    r, officialSky, officialBlock, minSection, sectionCount);
            dumpInputComparison(level, r.corePos(), r, increased);
        }
        // 5. 恢复：核心柱 ← 我们的值；8 邻柱 ← 快照（验算纯观察，生产语义 = memcpy-only）
        for (int s = 0; s < sectionCount; s++) {
            SectionPos sp = SectionPos.of(r.corePos().x, minSection + s, r.corePos().z);
            DataLayer sky = skyListener.getDataLayerData(sp);
            if (sky != null) {
                System.arraycopy(r.skySections()[s], 0, sky.getData(), 0, 2048);
            }
            DataLayer block = blockListener.getDataLayerData(sp);
            if (block != null) {
                System.arraycopy(r.blockSections()[s], 0, block.getData(), 0, 2048);
            }
        }
        snapIdx = 0;
        for (int[] off : DOMAIN_OFFSETS) {
            if (off[0] == 0 && off[1] == 0) {
                continue;
            }
            restoreColumn(skyListener, r.corePos().x + off[0], r.corePos().z + off[1], minSection, snapSky[snapIdx]);
            restoreColumn(blockListener, r.corePos().x + off[0], r.corePos().z + off[1], minSection, snapBlock[snapIdx]);
            snapIdx++;
        }
        // 6. 逐格对比（官方数组 vs 我们的数组；内芯与边界分开计）
        long skyMismatch = 0;
        long blockMismatch = 0;
        long edgeSky = 0;
        long edgeBlock = 0;
        StringBuilder samples = new StringBuilder();
        for (int s = 0; s < sectionCount; s++) {
            int sectionY = minSection + s;
            skyMismatch += compareLayer("sky", samples, sectionY, officialSky[s], r.skySections()[s]);
            blockMismatch += compareLayer("block", samples, sectionY, officialBlock[s], r.blockSections()[s]);
            edgeSky += compareEdge("sky", sectionY, officialSky[s], r.skySections()[s]);
            edgeBlock += compareEdge("block", sectionY, officialBlock[s], r.blockSections()[s]);
        }
        long mismatch = skyMismatch + blockMismatch;
        long edgeMismatch = edgeSky + edgeBlock;
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

    /** 整列快照（null 层 → null 项；clone 防官方重算写穿原数组）。 */
    private static byte[][] snapshotColumn(LayerLightEventListener listener, int chunkX, int chunkZ,
                                           int minSection, int sectionCount) {
        byte[][] out = new byte[sectionCount][];
        for (int s = 0; s < sectionCount; s++) {
            DataLayer layer = listener.getDataLayerData(SectionPos.of(chunkX, minSection + s, chunkZ));
            out[s] = layer == null ? null : layer.getData().clone();
        }
        return out;
    }

    /** 恢复整列（null 项 = 未加载/未建层，跳过）。 */
    private static void restoreColumn(LayerLightEventListener listener, int chunkX, int chunkZ,
                                      int minSection, byte[][] snap) {
        for (int s = 0; s < snap.length; s++) {
            if (snap[s] == null) {
                continue;
            }
            DataLayer layer = listener.getDataLayerData(SectionPos.of(chunkX, minSection + s, chunkZ));
            if (layer != null) {
                System.arraycopy(snap[s], 0, layer.getData(), 0, 2048);
            }
        }
    }

    private static void zeroLayer(DataLayer layer) {
        if (layer != null) {
            Arrays.fill(layer.getData(), (byte) 0);
        }
    }

    /** 单 section 内芯对比（x/z ∈ [1,14]；官方层为 null = 全 0）；收集前 6 个差异样例。 */
    private static long compareLayer(String layer, StringBuilder samples, int sectionY,
                                     byte[] official, byte[] bfs) {
        long mismatch = 0;
        for (int y = 0; y < 16; y++) {
            for (int x = 1; x < 15; x++) {
                for (int z = 1; z < 15; z++) {
                    int officialValue = nibbleAt(official, x, y, z);
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
    private static long compareEdge(String layer, int sectionY, byte[] official, byte[] bfs) {
        long mismatch = 0;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x += 15) {
                for (int z = 0; z < 16; z++) {
                    int officialValue = nibbleAt(official, x, y, z);
                    int bfsValue = nibbleAt(bfs, x, y, z);
                    if (officialValue != bfsValue) {
                        mismatch++;
                    }
                }
            }
            for (int z = 0; z < 16; z += 15) {
                for (int x = 1; x < 15; x++) {
                    int officialValue = nibbleAt(official, x, y, z);
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

    /** 稳定性对比：不置零，官方机制在【我们的值】之上继续传播；返回官方增亮的格数。 */
    private static long stabilityPass(ClientLevel level, LevelLightEngine lightEngine,
                                      LayerLightEventListener skyListener, LayerLightEventListener blockListener,
                                      LightComputeResult r, byte[][] officialSky, byte[][] officialBlock,
                                      int minSection, int sectionCount) {
        try {
            lightEngine.setLightEnabled(r.corePos(), true);
            lightEngine.propagateLightSources(r.corePos());
            ClientLightRecomputeService.safeRunLightUpdates(lightEngine);
        } catch (Throwable t) {
            Constants.LOG.warn("Hassium: light verify stability pass failed for {}", r.corePos(), t);
        }
        long increased = 0;
        for (int s = 0; s < sectionCount; s++) {
            SectionPos sp = SectionPos.of(r.corePos().x, minSection + s, r.corePos().z);
            DataLayer sky = skyListener.getDataLayerData(sp);
            DataLayer block = blockListener.getDataLayerData(sp);
            byte[] skyArr = sky == null ? null : sky.getData();
            byte[] blockArr = block == null ? null : block.getData();
            if (skyArr != null) {
                for (int i = 0; i < 2048; i++) {
                    int ours = r.skySections()[s][i] & 0xFF;
                    int now = skyArr[i] & 0xFF;
                    if (now > ours) {
                        // 官方向量式逐格对比（高低 nibble 分开，避免差异互相抵消）
                        if ((now & 0x0F) > (ours & 0x0F)) {
                            increased++;
                        }
                        if ((now >> 4) > (ours >> 4)) {
                            increased++;
                        }
                    }
                }
            }
            if (blockArr != null) {
                for (int i = 0; i < 2048; i++) {
                    int ours = r.blockSections()[s][i] & 0xFF;
                    int now = blockArr[i] & 0xFF;
                    if (now > ours) {
                        if ((now & 0x0F) > (ours & 0x0F)) {
                            increased++;
                        }
                        if ((now >> 4) > (ours >> 4)) {
                            increased++;
                        }
                    }
                }
            }
        }
        // 稳定性通过程会改写核心柱 live 层；officialSky/officialBlock（第 4 步 clone）不受影响，
        // 步骤 5 的恢复（核心 ← 我们的值、邻柱 ← 快照）在稳定性通过程之后执行，顺序正确。
        return increased;
    }

    /** 逐格 nibble 差异计数（null 数组 = 全 0）。 */
    private static long diffLayers(byte[] a, byte[] b) {
        if (a == null && b == null) {
            return 0;
        }
        long diff = 0;
        for (int i = 0; i < 2048; i++) {
            int va = a == null ? 0 : a[i] & 0xFF;
            int vb = b == null ? 0 : b[i] & 0xFF;
            if ((va & 0x0F) != (vb & 0x0F)) {
                diff++;
            }
            if ((va >> 4) != (vb >> 4)) {
                diff++;
            }
        }
        return diff;
    }

    /** 域数组 nibble 差异计数（前 10 格入日志）。 */
    private static long diffArrays(byte[] a, byte[] b, StringBuilder log, String tag) {
        long diff = 0;
        int shown = 0;
        for (int i = 0; i < a.length && i < b.length; i++) {
            int va = a[i] & 0xFF;
            int vb = b[i] & 0xFF;
            if ((va & 0x0F) != (vb & 0x0F)) {
                if (log.length() < 900 && shown++ < 5) {
                    log.append(tag).append('(').append(i).append(")s=").append(va & 0x0F)
                            .append("b=").append(vb & 0x0F).append(' ');
                }
                diff++;
            }
            if ((va >> 4) != (vb >> 4)) {
                if (log.length() < 900 && shown++ < 5) {
                    log.append(tag).append('(').append(i).append(")s=").append(va >> 4)
                            .append("b=").append(vb >> 4).append(' ');
                }
                diff++;
            }
        }
        return diff;
    }

    /** 差异格现场：格 + 6 邻域（sim/自研 值 + lb + shapeId）。 */
    private static void dumpMechCell(StringBuilder sb, int i, int width, int height,
                                     byte[] lb, int[] shapeIds, byte[] simArr, byte[] solveArr, String tag) {
        if (sb.length() > 1600) {
            return;
        }
        int ww = width * width;
        int x = i % width;
        int z = (i / width) % width;
        int y = i / ww;
        sb.append(tag).append('(').append(x).append(',').append(y).append(',').append(z).append(')')
                .append("lb=").append(lb[i] & 0xFF).append(" sh=").append(shapeIds[i]).append(' ');
        int[] ns = {i - ww, i + ww, i - width, i + width, i - 1, i + 1};
        String[] dn = {"D", "U", "N", "S", "W", "E"};
        for (int d = 0; d < 6; d++) {
            int t = ns[d];
            if (t < 0 || t >= simArr.length) {
                continue;
            }
            int tx = t % width;
            int tz = (t / width) % width;
            boolean rowOk = true;
            if (d == 4 && tx == width - 1) {
                rowOk = false;
            }
            if (d == 5 && tx == 0) {
                rowOk = false;
            }
            if (d == 2 && tz == width - 1) {
                rowOk = false;
            }
            if (d == 3 && tz == 0) {
                rowOk = false;
            }
            if (!rowOk) {
                continue;
            }
            sb.append(dn[d]).append('[').append(simArr[t] & 0xFF).append('/').append(solveArr[t] & 0xFF)
                    .append('/').append(lb[t] & 0xFF).append('/').append(shapeIds[t]).append("] ");
        }
    }

    /** 官方忠实模拟：在域数组上复刻 vanilla propagateIncreases/propagateIncrease 语义
     *  （含 storingLightForSection 门控与 emission 条目 stored 提升），与自研 BFS 对比定位分歧。 */
    private static void simulateVanilla(int width, int height, byte[] arr, byte[] lightBlock, int[] shapeIds,
                                        long[] seeds, LightFloodFill.Occlusion occlusion,
                                        boolean[] sectionHasData, long[] out) {
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        for (long s : seeds) {
            int idx = (int) (s >>> 12);
            int level = (int) (s & 0xF);
            // 官方 isIncreaseFromEmission：stored < 条目 level → 先提升 stored
            if ((arr[idx] & 0xFF) < level) {
                arr[idx] = (byte) level;
            }
            queue.enqueue(s);
        }
        int ww = width * width;
        int count = 0;
        int[] opposite = {1, 0, 3, 2, 5, 4};
        while (!queue.isEmpty() && count < 200_000_000) {
            long e = queue.dequeueLong();
            count++;
            int idx = (int) (e >>> 12);
            int level = (int) (e & 0xF);
            int dirMask = (int) ((e >>> 5) & 0x3F);
            boolean fromEmpty = ((e >>> 4) & 1) != 0;
            if ((arr[idx] & 0xFF) != level) {
                continue;
            }
            int x = idx % width;
            int z = (idx / width) % width;
            int y = idx / ww;
            // 6 方向，含 section 门控（官方 storingLightForSection）
            int[] targets = {idx - ww, idx + ww, idx - width, idx + width, idx - 1, idx + 1};
            for (int d = 0; d < 6; d++) {
                int t = targets[d];
                if ((dirMask & (1 << d)) == 0) {
                    continue;
                }
                int ty = d < 2 ? (d == 0 ? y - 1 : y + 1) : y;
                if (ty < 0 || ty >= height) {
                    continue;
                }
                if (d == 2 && z == 0) {
                    continue;
                }
                if (d == 3 && z == width - 1) {
                    continue;
                }
                if (d == 4 && x == 0) {
                    continue;
                }
                if (d == 5 && x == width - 1) {
                    continue;
                }
                int sec = ty >> 4;
                if (sectionHasData != null && !sectionHasData[sec]) {
                    continue; // 官方 storingLightForSection = false → 跳过
                }
                int stored = arr[t] & 0xFF;
                if (level - 1 <= stored) {
                    continue;
                }
                int candidate = level - Math.max(1, lightBlock[t] & 0xFF);
                if (candidate <= stored) {
                    continue;
                }
                int srcShape = fromEmpty ? 0 : shapeIds[idx];
                int dstShape = shapeIds[t];
                if (srcShape == 0 || dstShape == 0 || !occlusion.occludes(srcShape, dstShape, d)) {
                    arr[t] = (byte) candidate;
                    if (candidate > 1) {
                        long child = ((long) t << 12) | ((long) (0b111111 & ~(1 << opposite[d])) << 5)
                                | ((long) (dstShape == 0 ? 1 : 0) << 4) | candidate;
                        queue.enqueue(child);
                    }
                }
            }
        }
        out[0] = count;
    }

    /** 主线程重放：全新 9 柱捕获 + 与背景任务相同的 W=48 solve + 核心柱提取。 */
    private static boolean replaySolve(ClientLevel level, LightComputeResult r, int minSection,
                                       int sectionCount, byte[][] outSky, byte[][] outBlock,
                                       byte[][] officialSky, byte[][] officialBlock) {
        int minY = LevelHeightCompat.getMinBlockY(level);
        int height = level.getHeight();
        int ww48 = PROP_W * PROP_W;
        byte[] propLight = new byte[ww48 * height];
        int[] propShape = new int[ww48 * height];
        int[] propSourceY = new int[ww48];
        Arrays.fill(propSourceY, LightFloodFill.NO_COLUMN);
        List<Integer> propEmitters = new ArrayList<>();
        List<VoxelShape[]> propShapes = new ArrayList<>();
        for (int i = 0; i < DOMAIN_OFFSETS.length; i++) {
            int[] off = DOMAIN_OFFSETS[i];
            LevelChunk chunk = level.getChunkSource().getChunkNow(r.corePos().x + off[0], r.corePos().z + off[1]);
            if (chunk == null) {
                return false;
            }
            LightColumnSnapshot snap = LightColumnSnapshot.capture(level, chunk);
            assembleFull(snap, off[0], off[1], height, propLight, propShape, propSourceY, propEmitters, propShapes);
        }
        int[] emitter48 = propEmitters.stream().mapToInt(Integer::intValue).toArray();
        VoxelShape[][] shapeTable48 = propShapes.toArray(new VoxelShape[0][]);
        LightFloodFill.Occlusion occlusion48 = (srcShape, dstShape, dir) -> Shapes.faceShapeOccludes(
                shapeTable48[srcShape - 1][dir], shapeTable48[dstShape - 1][OPPOSITE_DIR[dir]]);
        byte[] sky = LightFloodFill.solveSky(PROP_W, height, propLight, propSourceY, propShape, occlusion48);
        byte[] block = LightFloodFill.solveBlock(PROP_W, height, propLight, emitter48, propShape, occlusion48);
        // 模拟器对比：同一域数组上 vanilla 忠实 BFS vs 自研 solve（纯机制差异测试）
        StringBuilder simLog = new StringBuilder();
        long[] simOut = new long[1];
        LongArrayFIFOQueue skySeedQ = new LongArrayFIFOQueue();
        LongArrayFIFOQueue blockSeedQ = new LongArrayFIFOQueue();
        for (int x = 0; x < PROP_W; x++) {
            for (int z = 0; z < PROP_W; z++) {
                int sy = propSourceY[z * PROP_W + x];
                if (sy == LightFloodFill.NO_COLUMN || sy >= height) {
                    continue;
                }
                int colBase = z * PROP_W + x;
                int y0 = Math.max(0, sy);
                int maxN = Integer.MIN_VALUE;
                if (z > 0) {
                    maxN = Math.max(maxN, propSourceY[(z - 1) * PROP_W + x]);
                }
                if (z < PROP_W - 1) {
                    maxN = Math.max(maxN, propSourceY[(z + 1) * PROP_W + x]);
                }
                if (x > 0) {
                    maxN = Math.max(maxN, propSourceY[z * PROP_W + x - 1]);
                }
                if (x < PROP_W - 1) {
                    maxN = Math.max(maxN, propSourceY[z * PROP_W + x + 1]);
                }
                for (int y = y0; y < height; y++) {
                    if (y == sy || y < maxN) {
                        int mask = 0;
                        if (y == sy) {
                            mask |= 1;
                        }
                        if (z > 0 && y < propSourceY[(z - 1) * PROP_W + x]) {
                            mask |= 1 << 2;
                        }
                        if (z < PROP_W - 1 && y < propSourceY[(z + 1) * PROP_W + x]) {
                            mask |= 1 << 3;
                        }
                        if (x > 0 && y < propSourceY[z * PROP_W + x - 1]) {
                            mask |= 1 << 4;
                        }
                        if (x < PROP_W - 1 && y < propSourceY[z * PROP_W + x + 1]) {
                            mask |= 1 << 5;
                        }
                        if (mask != 0) {
                            skySeedQ.enqueue(((long) (y * ww48 + colBase) << 12) | ((long) mask << 5) | 15);
                        }
                    }
                }
            }
        }
        for (int e : emitter48) {
            int idx = e & 0xFFFFF;
            int emission = e >>> 20;
            blockSeedQ.enqueue(((long) idx << 12) | (0b111111L << 5)
                    | ((long) (propShape[idx] == 0 ? 1 : 0) << 4) | emission);
        }
        long[] skySeedsArr = new long[skySeedQ.size()];
        for (int i = 0; i < skySeedsArr.length; i++) {
            skySeedsArr[i] = skySeedQ.dequeueLong();
        }
        long[] blockSeedsArr = new long[blockSeedQ.size()];
        for (int i = 0; i < blockSeedsArr.length; i++) {
            blockSeedsArr[i] = blockSeedQ.dequeueLong();
        }
        byte[] simSky = new byte[ww48 * height];
        byte[] simBlock = new byte[ww48 * height];
        for (int x = 0; x < PROP_W; x++) {
            for (int z = 0; z < PROP_W; z++) {
                int sy = propSourceY[z * PROP_W + x];
                if (sy == LightFloodFill.NO_COLUMN || sy >= height) {
                    continue;
                }
                int colBase = z * PROP_W + x;
                int y0 = Math.max(0, sy);
                for (int y = y0; y < height; y++) {
                    simSky[y * ww48 + colBase] = 15;
                }
            }
        }
        simulateVanilla(PROP_W, height, simSky, propLight, propShape, skySeedsArr, occlusion48, null, simOut);
        long skyMech = diffArrays(simSky, sky, simLog, "simSky");
        simulateVanilla(PROP_W, height, simBlock, propLight, propShape, blockSeedsArr, occlusion48, null, simOut);
        long blockMech = diffArrays(simBlock, block, simLog, "simBlock");
        // 分歧现场：前 3 个差异格 + 6 邻域（sim/自研 值 + lb + shapeId）
        StringBuilder mech = new StringBuilder();
        int shownCells = 0;
        for (int i = 0; i < simSky.length && shownCells < 2; i++) {
            int s = simSky[i] & 0xFF;
            int v = sky[i] & 0xFF;
            if (s != v) {
                dumpMechCell(mech, i, PROP_W, height, propLight, propShape, simSky, sky, "sky");
                shownCells++;
            }
        }
        for (int i = 0; i < simBlock.length && shownCells < 4; i++) {
            int s = simBlock[i] & 0xFF;
            int v = block[i] & 0xFF;
            if (s != v) {
                dumpMechCell(mech, i, PROP_W, height, propLight, propShape, simBlock, block, "blk");
                shownCells++;
            }
        }
        Constants.LOG.error("[LIGHT_VERIFY-SIM] chunk {} sky={} block={} {} {}", r.corePos(), skyMech, blockMech, simLog, mech);
        // 模拟器核心柱 vs 官方 oracle（验算真正关心的对比：vanilla 忠实 BFS 是否与引擎一致）
        byte[][] simSkySec = extractRegion(simSky, PROP_CORE_OFFSET, PROP_CORE_OFFSET, minSection, sectionCount, height);
        byte[][] simBlockSec = extractRegion(simBlock, PROP_CORE_OFFSET, PROP_CORE_OFFSET, minSection, sectionCount, height);
        long simVsOffSky = 0;
        long simVsOffBlock = 0;
        for (int s = 0; s < sectionCount; s++) {
            simVsOffSky += diffLayers(simSkySec[s], officialSky[s]);
            simVsOffBlock += diffLayers(simBlockSec[s], officialBlock[s]);
        }
        Constants.LOG.error("[LIGHT_VERIFY-SIMVSORACLE] chunk {} sky={} block={}",
                r.corePos(), simVsOffSky, simVsOffBlock);
        byte[][] skySec = extractRegion(sky, PROP_CORE_OFFSET, PROP_CORE_OFFSET, minSection, sectionCount, height);
        byte[][] blockSec = extractRegion(block, PROP_CORE_OFFSET, PROP_CORE_OFFSET, minSection, sectionCount, height);
        System.arraycopy(skySec, 0, outSky, 0, sectionCount);
        System.arraycopy(blockSec, 0, outBlock, 0, sectionCount);
        return true;
    }

    /** 输入对比：官方 findBlockLightSources / ChunkSkyLightSources vs 我们的 capture（前 2 块）。 */
    private static void dumpInputComparison(ClientLevel level, ChunkPos pos, LightComputeResult r, long increased) {
        verifyInputSample.incrementAndGet();
        LevelChunk chunk = level.getChunk(pos.x, pos.z);
        if (chunk == null || !level.isLoaded(pos.getWorldPosition())) {
            Constants.LOG.error("[LIGHT_VERIFY-INPUT] chunk {} not loaded, skip", pos);
            return;
        }
        IntArrayList offEmitters = new IntArrayList();
        int chunkMinY = LevelHeightCompat.getMinBlockY(level);
        chunk.findBlockLightSources((bp, bs) -> {
            int e = bs.getLightEmission();
            if (e != 0) {
                int cell = (bp.getY() - chunkMinY) * 256 + (bp.getZ() & 15) * 16 + (bp.getX() & 15);
                offEmitters.add((e << 20) | cell);
            }
        });
        int[] offArr = offEmitters.toIntArray();
        Arrays.sort(offArr);
        LightColumnSnapshot snap = LightColumnSnapshot.capture(level, chunk);
        int[] ourEmitters = snap.getEmitters().clone();
        Arrays.sort(ourEmitters);
        ChunkSkyLightSources skySources = chunk.getSkyLightSources();
        int[] ourSy = snap.getSourceY();
        StringBuilder sb = new StringBuilder();
        sb.append("[LIGHT_VERIFY-INPUT] chunk=").append(pos)
                .append(" emitters official=").append(offArr.length).append(" ours=").append(ourEmitters.length)
                .append(" stabilityIncreased=").append(increased)
                .append(" skySourcesNull=").append(skySources == null);
        // 发射源集合差异（前 6 个）
        int diffShown = 0;
        int ei = 0;
        int oi = 0;
        while (ei < offArr.length && oi < ourEmitters.length) {
            if (offArr[ei] == ourEmitters[oi]) {
                ei++;
                oi++;
            } else if (offArr[ei] < ourEmitters[oi]) {
                if (diffShown++ < 6) {
                    sb.append(" offOnly=").append(Integer.toHexString(offArr[ei]));
                }
                ei++;
            } else {
                if (diffShown++ < 6) {
                    sb.append(" ourOnly=").append(Integer.toHexString(ourEmitters[oi]));
                }
                oi++;
            }
        }
        if (ei < offArr.length && diffShown < 6) {
            sb.append(" offOnly=").append(Integer.toHexString(offArr[ei]));
        }
        if (oi < ourEmitters.length && diffShown < 6) {
            sb.append(" ourOnly=").append(Integer.toHexString(ourEmitters[oi]));
        }
        // 天空源 Y 差异列（前 6 个；官方 null → 全 0 源）
        int syDiff = 0;
        int syShown = 0;
        for (int i = 0; i < 256; i++) {
            int o = skySources == null ? LightFloodFill.NEG_INF : skySources.getLowestSourceY(i & 15, i >> 4);
            if (o != ourSy[i]) {
                syDiff++;
                if (syShown++ < 6) {
                    sb.append(" sy(").append(i & 15).append(',').append(i >> 4)
                            .append(")o=").append(o).append("b=").append(ourSy[i]);
                }
            }
        }
        sb.append(" syDiffCols=").append(syDiff);
        // 自研 sky 结果 vs 填充期望：核心柱每列 fill 区（y ∈ [sy, sy+2]）应为 15
        int fillBad = 0;
        int fillChecked = 0;
        int fillShown = 0;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int sy = ourSy[z * 16 + x];
                if (sy == LightFloodFill.NEG_INF || sy == LightFloodFill.NO_COLUMN) {
                    continue;
                }
                int sec = sy >> 4;
                int yInSec = sy & 15;
                if (sec >= r.skySections().length) {
                    continue;
                }
                byte[] skySec = r.skySections()[sec];
                if (skySec == null) {
                    continue;
                }
                for (int d = 0; d < 3; d++) {
                    int yy = yInSec + d;
                    if (yy > 15) {
                        break;
                    }
                    fillChecked++;
                    int v = nibbleAt(skySec, x, yy, z);
                    if (v != 15) {
                        fillBad++;
                        if (fillShown++ < 5) {
                            sb.append(" fill(").append(x).append(',').append(sy + d).append(',').append(z)
                                    .append(")=").append(v);
                        }
                    }
                }
            }
        }
        sb.append(" fillChecked=").append(fillChecked).append(" fillBad=").append(fillBad);
        Constants.LOG.error("{}", sb);
    }

    private LightColumnSnapshot snapshotOrCapture(ClientLevel level, LevelChunk chunk, int cx, int cz) {
        long key = ChunkPos.asLong(cx, cz);
        synchronized (snapshotCache) {
            SnapshotCacheEntry entry = snapshotCache.get(key);
            if (entry == null || entry.chunk() != chunk) {
                LightColumnSnapshot snapshot = LightColumnSnapshot.capture(level, chunk);
                entry = new SnapshotCacheEntry(chunk, snapshot);
                snapshotCache.put(key, entry);
            }
            return entry.snapshot();
        }
    }

    /** DOMAIN_OFFSETS 偏移 → PROP_OFFSETS 邻柱下标（0–3 = N/S/W/E）；非 4 邻柱（对角柱）返回 -1。 */
    private static int neighborIndexFor(int[] off) {
        if (off[0] == 0 && off[1] == -1) {
            return 0;
        }
        if (off[0] == 0 && off[1] == 1) {
            return 1;
        }
        if (off[0] == -1 && off[1] == 0) {
            return 2;
        }
        if (off[0] == 1 && off[1] == 0) {
            return 3;
        }
        return -1;
    }

    /**
     * 主线程抓取旧光照：核心柱（nbIdx < 0）或 4 邻柱（nbIdx 0–3）全 section × 2 层，
     * DataLayer 打包 2048B 克隆（clone 后数组即私有，后台线程只读，与官方写无竞态）。
     * null 层 = 全 0（无光；未加载块或未建层 section）。成本 ~96KB×5 柱 ≈ 0.5ms/任务。
     */
    private void captureOldLight(ClientLevel level, CaptureTask task, int cx, int cz, int nbIdx) {
        LevelLightEngine lightEngine = level.getLightEngine();
        LayerLightEventListener skyListener = lightEngine.getLayerListener(LightLayer.SKY);
        LayerLightEventListener blockListener = lightEngine.getLayerListener(LightLayer.BLOCK);
        byte[][] skyTarget = nbIdx < 0 ? task.coreOldSky : task.neighborOldSky[nbIdx];
        byte[][] blockTarget = nbIdx < 0 ? task.coreOldBlock : task.neighborOldBlock[nbIdx];
        for (int s = 0; s < task.sectionCount; s++) {
            SectionPos sp = SectionPos.of(cx, task.minSection + s, cz);
            DataLayer sky = skyListener.getDataLayerData(sp);
            skyTarget[s] = sky == null ? new byte[2048] : sky.getData().clone();
            DataLayer block = blockListener.getDataLayerData(sp);
            blockTarget[s] = block == null ? new byte[2048] : block.getData().clone();
        }
    }

    /**
     * 把柱快照拷入 W=32 求解域数组（chunkOffset = 相对核心柱的 -1/0/+1）。
     * <p>
     * W=32 域 = 世界 [core-8, core+24)：快照 (dx,dz) 与域的交叉裁剪——
     * dx=-1（west 柱东半）→ 柱内 [8,16) → 域 [0,8)；dx=0（core 柱）→ 柱内 [0,16) → 域 [8,24)；
     * dx=+1（east 柱西半）→ 柱内 [0,8) → 域 [24,32)。z 方向同理。
     */
    private static void assemble(LightColumnSnapshot snap, int dx, int dz, int height,
                                 byte[] domainLightBlock, int[] domainShapeIds, int[] domainSourceY,
                                 List<Integer> emitters, List<VoxelShape[]> allShapes) {
        int sx0 = dx < 0 ? 8 : 0;
        int bx0 = dx < 0 ? 0 : (dx == 0 ? CORE_OFFSET : 24);
        int lenX = dx == 0 ? 16 : 8;
        int sz0 = dz < 0 ? 8 : 0;
        int bz0 = dz < 0 ? 0 : (dz == 0 ? CORE_OFFSET : 24);
        int lenZ = dz == 0 ? 16 : 8;
        byte[] lb = snap.getLightBlock();
        int[] sy = snap.getSourceY();
        for (int xi = 0; xi < lenX; xi++) {
            int x = sx0 + xi;
            int bx = bx0 + xi;
            for (int zi = 0; zi < lenZ; zi++) {
                int z = sz0 + zi;
                int bz = bz0 + zi;
                int domCol = bz * W + bx;
                domainSourceY[domCol] = sy[z * 16 + x];
                for (int y = 0; y < height; y++) {
                    int src = y * 256 + z * 16 + x;
                    int dst = (y * W + bz) * W + bx;
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
            if (x < sx0 || x >= sx0 + lenX || z < sz0 || z >= sz0 + lenZ) {
                continue; // 快照柱内不在域交叉区的格（W=32 时半柱裁剪）
            }
            int dst = (y * W + (bz0 + (z - sz0))) * W + (bx0 + (x - sx0));
            emitters.add((emission << 20) | dst);
        }
        int[] shapeCells = snap.getShapeCells();
        VoxelShape[][] shapeFaces = snap.getShapeFaces();
        for (int i = 0; i < shapeCells.length; i++) {
            int cell = shapeCells[i];
            int y = cell >> 8;
            int z = (cell >> 4) & 0xF;
            int x = cell & 0xF;
            if (x < sx0 || x >= sx0 + lenX || z < sz0 || z >= sz0 + lenZ) {
                continue;
            }
            int dst = (y * W + (bz0 + (z - sz0))) * W + (bx0 + (x - sx0));
            // 0 是 LightFloodFill 的「无形状」哨兵；真实形状编号必须从 1 开始。
            // 否则域中的第一个遮挡方块会被当成空气，BFS 与官方引擎产生系统性偏差。
            domainShapeIds[dst] = allShapes.size() + 1;
            allShapes.add(shapeFaces[i]);
        }
    }

    /**
     * 全宽柱拷贝（传播域用）：柱内全 16×16 → 域 [16+dx*16, 32+dx*16)²。
     * emitters/shapeCells 无裁剪（传播域需要邻柱全宽数据）。
     */
    private static void assembleFull(LightColumnSnapshot snap, int dx, int dz, int height,
                                     byte[] domainLightBlock, int[] domainShapeIds, int[] domainSourceY,
                                     List<Integer> emitters, List<VoxelShape[]> allShapes) {
        int bx0 = PROP_CORE_OFFSET + dx * 16;
        int bz0 = PROP_CORE_OFFSET + dz * 16;
        byte[] lb = snap.getLightBlock();
        int[] sy = snap.getSourceY();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int domCol = (bz0 + z) * PROP_W + (bx0 + x);
                domainSourceY[domCol] = sy[z * 16 + x];
                for (int y = 0; y < height; y++) {
                    int src = y * 256 + z * 16 + x;
                    int dst = (y * PROP_W + (bz0 + z)) * PROP_W + (bx0 + x);
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
            int dst = (y * PROP_W + (bz0 + z)) * PROP_W + (bx0 + x);
            emitters.add((emission << 20) | dst);
        }
        int[] shapeCells = snap.getShapeCells();
        VoxelShape[][] shapeFaces = snap.getShapeFaces();
        for (int i = 0; i < shapeCells.length; i++) {
            int cell = shapeCells[i];
            int y = cell >> 8;
            int z = (cell >> 4) & 0xF;
            int x = cell & 0xF;
            int dst = (y * PROP_W + (bz0 + z)) * PROP_W + (bx0 + x);
            domainShapeIds[dst] = allShapes.size() + 1;
            allShapes.add(shapeFaces[i]);
        }
    }

    /** 域内 (ox,oz) 起始的 16×16 柱各 section 提取为 DataLayer 布局的 2048 字节半字节数组。 */
    private static byte[][] extractRegion(byte[] domain, int ox, int oz,
                                          int minSection, int sectionCount, int height) {
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
                        int v = domain[(y * PROP_W + (oz + z)) * PROP_W + (ox + x)] & 0xFF;
                        int di = (ly << 8) | (z << 4) | x;
                        data[di >> 1] |= (byte) (v << ((di & 1) * 4));
                    }
                }
            }
            out[s] = data;
        }
        return out;
    }

    /** 邻柱旧光照（DataLayer 打包 4bit）解码为传播域 8bit 值（柱区原点 ox/oz）。 */
    private static void decodeOldColumn(byte[][] oldSections, byte[] domain, int ox, int oz,
                                        CaptureTask task, int ww48) {
        for (int s = 0; s < task.sectionCount; s++) {
            byte[] old = oldSections[s];
            int y0 = s * 16;
            for (int ly = 0; ly < 16; ly++) {
                int y = y0 + ly;
                if (y >= task.height) {
                    break;
                }
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        int di = (ly << 8) | (z << 4) | x;
                        int v = (old[di >> 1] >> ((di & 1) * 4)) & 0xF;
                        domain[y * ww48 + (oz + z) * PROP_W + (ox + x)] = (byte) v;
                    }
                }
            }
        }
    }

    /** 邻柱区提取 + 与旧值打包对比：返回变化 section（掩码位 1 对应），并写入 masks[nb]。 */
    private static byte[][] diffNeighborColumn(byte[] domain, int ox, int oz, CaptureTask task,
                                               byte[][] oldSections, long[] masks, int nb) {
        byte[][] packed = extractRegion(domain, ox, oz, task.minSection, task.sectionCount, task.height);
        long mask = 0L;
        List<byte[]> changed = new ArrayList<>();
        for (int s = 0; s < task.sectionCount; s++) {
            if (!Arrays.equals(packed[s], oldSections[s])) {
                mask |= 1L << s;
                changed.add(packed[s]);
            }
        }
        masks[nb] = mask;
        return changed.toArray(new byte[0][]);
    }

    /** 邻柱差异 section 总数（传播诊断用；null = 降级未算）。 */
    private static long neighborSectionCount(long[] skyMasks, long[] blockMasks) {
        if (skyMasks == null) {
            return 0;
        }
        long n = 0;
        for (long m : skyMasks) {
            n += Long.bitCount(m);
        }
        for (long m : blockMasks) {
            n += Long.bitCount(m);
        }
        return n;
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
                    // 低优先级（NORM-1）：加载期 BFS 与渲染/主线程抢核时让位，保护帧率
                    // （CPU 密集 solve 实测单任务最高 266ms，6 线程满载会挤占渲染调度）。
                    int lightThreads = HassiumConfigService.getInstance().getParallelLightEngineThreads();
                    Constants.LOG.info("Hassium: Created light compute pool threads={} priority={}",
                            lightThreads, Thread.NORM_PRIORITY - 1);
                    p = ExecutorFactory.createPlatform("hassium-light", lightThreads,
                            Thread.NORM_PRIORITY - 1);
                    pool = p;
                }
            }
        }
        return p;
    }
}
