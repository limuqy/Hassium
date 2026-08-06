package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.CompoundTagCompat;
import io.github.limuqy.mc.hassium.compat.LevelHeightCompat;
import io.github.limuqy.mc.hassium.compat.LightAccessCompat;
import io.github.limuqy.mc.hassium.concurrent.ExecutorFactory;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
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
 * （后台 W=48 solve = 核心柱 ±16 格，传播半径 15 全覆盖，结果与全量重算一致），随后
 * propagateLightSources 把官方种子入队、批尾 runLightUpdates 一次收敛（solve 值基于
 * capture 时刻快照，与官方在最终邻柱值上的不动点有竞态差，官方传播补亮偏暗格）；
 * 然后逐结果官方验算（{@code debug.lightVerify}：核心柱层清零后走官方引擎从零重算，
 * 官方结果 vs BFS 逐格对比，内芯 x/z ∈ [1,14]，边界差异属输入范围差异非错误；纯观察，
 * 验算后恢复世界）+ 缓存写回（写回经 CacheSaveQueue 后台化：主线程只组 NBT，压缩与
 * 写盘由后台单消费者执行）。
 * <p>
 * 默认关闭（{@code clientCache.parallelLightEngineEnabled}），现有同步路径为默认。
 * <p>
 * {@link #clear()} 可跨线程调用（断连清理在 1.21.11 Fabric 可能在 Netty IO 线程触发）：
 * 全部队列与映射使用并发容器。
 */
public final class LightComputeService {

    /**
     * 求解域 == 传播域：W=48 = 核心柱 ±16 格（3 柱全宽，覆盖传播半径 15 + 1 格余量）。
     * 域布局：核心柱 [16,32)²；N 柱 z∈[0,16)；S 柱 z∈[32,48)；W 柱 x∈[0,16)；E 柱 x∈[32,48)；
     * 对角柱全宽装入 4 角 [0,16)²/[32,48)²（对角柱内距核心柱角 ≤15 格的光源/天空柱
     * 会照进核心柱角区，留空即永久暗角）。
     * 历史教训：W=32（halo 8 格 < 半径 15）时 core 柱整体受域截断影响（边缘 ~7 格最重），
     * 而 modeA 落地只写 BFS 结果、校准链（pullLightFromNeighborEdges 差 1）已随传播域
     * 落地移除 → 每块四周环带永久黑块（实测用户报告）。升 W=48 后核心柱结果与全量
     * 重算一致，不再依赖校准吸收。
     */
    private static final int PROP_W = 48;
    /** 核心柱起点（两侧 halo 各 16 格 = 传播半径全覆盖）。 */
    private static final int PROP_CORE_OFFSET = 16;
    /** 传播域组装序：N/S/W/E（索引 0–3 = 结果邻柱掩码序）+ 核心柱（索引 4）。 */
    private static final int[][] PROP_OFFSETS = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}, {0, 0}};
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
    /** 分段重算：变化 section 的 y 跨度上限（含）。跨度 > 此值 → 整 chunk 重算（D y 超过 8 section，收益消失）。 */
    private static final int SEGMENT_MAX_CHANGE_SPAN = 6;
    /** 分段落地域 D y section 数上限（= SEGMENT_MAX_CHANGE_SPAN + 2 圈）。 */
    private static final int SEGMENT_MAX_DOMAIN_SECTIONS = SEGMENT_MAX_CHANGE_SPAN + 2;
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
    /**
     * 会话内区块数据版本：chunk 数据被替换（handleLevelChunkWithLight → replaceWithPacketData，
     * 同一 chunk 对象上 delta/全量/缓存 apply）时 bump。快照失效 + 任务重启 + 结果落地校验
     * 共用同一基准：旧地形 solve 结果不得覆盖新地形（实测深水区区块间亮度跳变根因——
     * 快照缓存按 chunk 对象命中，数据替换后旧快照继续产出浅水亮值，copyMasked 只增亮
     * 把亮值写进深水邻块，形成亮暗分明的区块跳变；暗值才是正确物理）。
     */
    private static final java.util.concurrent.ConcurrentHashMap<Long, Integer> dataVersions =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static int dataVersionOf(long chunkKey) {
        return dataVersions.getOrDefault(chunkKey, 0);
    }

    /**
     * chunk 数据已替换（MixinLightRecompute TAIL 调用，数据替换后、重算提交前）：
     * 失效该柱快照、bump 版本、重启未完成任务（capture 阶段重采样新地形）。
     * 已提交后台的旧任务（solve 基于旧地形）由结果落地版本校验丢弃。
     */
    public void onChunkDataReplaced(ClientLevel level, ChunkPos pos) {
        long key = ChunkPos.asLong(pos.x, pos.z);
        synchronized (snapshotCache) {
            snapshotCache.remove(key);
        }
        dataVersions.merge(key, 1, Integer::sum);
        CaptureTask task = pendingByCore.get(key);
        if (task != null) {
            // 同对象数据替换：只重采样核心柱（邻柱地形未变）。全清 restart 在
            // 连续 delta 下每次被打断 → capture 饿死 → 重算永不落地（黑块持久）。
            task.restartCoreOnly();
        }
    }

    private final java.util.concurrent.atomic.AtomicInteger diagApply = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger diagSplit = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger diagProp = new java.util.concurrent.atomic.AtomicInteger();
    /** 分段重算触发限频诊断（[LIGHT-SEG]）。 */
    private final java.util.concurrent.atomic.AtomicInteger segLog = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger verifyInputSample = new java.util.concurrent.atomic.AtomicInteger();
    /** 最终收敛探针一次性标志（队列全空后只跑一次，见 drainCompletions）。 */
    private boolean finalProbeDone;

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

    /** 分段落地域 D 的 y 范围（绝对 section y，[minSectionY, maxSectionYExclusive)）。 */
    private record SectionDomain(int minSectionY, int maxSectionYExclusive) {
    }

    /**
     * 从缓存 NBT 推断变化 section 的 y 跨度（无 sky_light 且无 block_light 字段的 section）。
     * 判定与 {@code restoreCachedLightToEngine} 同款（delta merge 的新 section 两个字段都缺失；
     * 未变 section 保留旧光字段）。is_light_on=1 / 全部有光 / 跨度超阈值 → null（整 chunk）。
     */
    private static SectionDomain inferChangeSpan(CompoundTag nbt, int minSection, int maxSection) {
        if (nbt == null || ChunkDiskCodec.isLightOn(nbt)) {
            return null;
        }
        ListTag sections = CompoundTagCompat.getList(nbt, "sections");
        int minSy = -1, maxSyEx = -1;
        for (int i = 0; i < sections.size(); i++) {
            if (!(sections.get(i) instanceof CompoundTag st)) {
                continue;
            }
            boolean hasSky = st.get("sky_light") instanceof ByteArrayTag bat
                    && bat.getAsByteArray().length == DataLayer.SIZE;
            boolean hasBlock = st.get("block_light") instanceof ByteArrayTag bbat
                    && bbat.getAsByteArray().length == DataLayer.SIZE;
            if (hasSky && hasBlock) {
                continue; // 有光字段 = 未变（delta merge 的新 section 两个字段都缺失）
            }
            int sy = minSection + i;
            if (sy >= maxSection) {
                break;
            }
            if (minSy < 0) {
                minSy = sy;
            }
            maxSyEx = sy + 1;
        }
        if (minSy < 0) {
            return null;
        }
        if (maxSyEx - minSy > SEGMENT_MAX_CHANGE_SPAN) {
            return null;
        }
        return new SectionDomain(minSy, maxSyEx);
    }

    /** DOMAIN_OFFSETS 偏移 → 下标（0–8）；非 3×3 偏移返回 -1。 */
    private static int domainIndexFor(int[] off) {
        for (int i = 0; i < DOMAIN_OFFSETS.length; i++) {
            if (DOMAIN_OFFSETS[i][0] == off[0] && DOMAIN_OFFSETS[i][1] == off[1]) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 后台任务产物：核心柱各 section 的 sky/block 半字节数组（DataLayer 布局，2048 字节）；
     * 邻柱差异（neighbor*Masks[nb] 位 = section 相对 minSection 偏移，neighbor*Sections[nb] =
     * 变化 section 的打包结果；sectionCount > 64 时为 null = 邻柱传播降级）。
     * <p>
     * 分段模式（domainSectionCount > 0）：skySections/blockSections = 核心柱 D y section
     * （长度 = domainSectionCount）；neighbor*Sections[nb] = 对应邻柱 D y section（掩码不参与
     * 落地）；cornerSkySections/cornerBlockSections = 4 对角柱（DOMAIN_OFFSETS 序 {0,2,6,8}）
     * × D y section；domainMinSection = 落地域 D 底（绝对 section y）；domainDataVersions =
     * 9 柱（DOMAIN_OFFSETS 序）捕获时刻版本（落地校验）。全量模式：corner* = null、
     * domainMinSection = -1、domainSectionCount = 0、domainDataVersions = null。
     */
    public record LightComputeResult(ChunkPos corePos, LevelChunk expectedCoreChunk,
                                     byte[][] skySections, byte[][] blockSections,
                                     long[] neighborSkyMasks, byte[][][] neighborSkySections,
                                     long[] neighborBlockMasks, byte[][][] neighborBlockSections,
                                     CompoundTag cachedNbt, long generation, long captureNanos,
                                     int coreDataVersion, int[] neighborDataVersions,
                                     byte[][][] cornerSkySections, byte[][][] cornerBlockSections,
                                     int domainMinSection, int domainSectionCount,
                                     int[] domainDataVersions) {
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
        /** 4 邻柱旧光照（DataLayer 打包 2048B/section × 2 层），供后台 diff 邻柱差异 section。
         *  邻柱数组在 sectionCount > 64 时为 null（邻柱传播降级，掩码 long 装不下）。
         *  核心柱不再抓旧值：W=48 求解域直接产出完整核心柱结果，无需 diff 种子。 */
        private final byte[][][] neighborOldSky;   // [4] × [sectionCount] × 2048，PROP_OFFSETS 0–3 序（N/S/W/E）
        private final byte[][][] neighborOldBlock;
        private LevelChunk expectedCoreChunk;
        private CompoundTag cachedNbt;
        private int nextColumn;
        private int waitFrames;
        private long captureNanos;
        /** 本帧轮转过的时间戳（缺邻居放回队尾后，同帧重 poll 直接跳过，防轮转烧光预算）。 */
        private long lastRotatedNs;
        /** 核心柱数据版本（创建/重启时记录）：结果落地时与当前版本比对，旧地形结果丢弃。 */
        private int coreDataVersion;
        /** 4 邻柱捕获时刻数据版本（PROP_OFFSETS 0–3 序；sectionCount > 64 时为 null）。 */
        private int[] neighborDataVersions;
        /** 分段落地域 D 的 y 范围（绝对 section y，已含 ±1 圈；domainSectionCount == 0 = 整 chunk）。
         *  volatile：capture 完成前主线程可并集扩张（连续 delta），后台 solve 读。 */
        volatile int domainMinSection = -1;
        volatile int domainSectionCount = 0;
        /** 9 柱（DOMAIN_OFFSETS 序）捕获时刻数据版本：分段落地校验（壳光/邻柱区输入时效）。 */
        final int[] domainDataVersions = new int[DOMAIN_OFFSETS.length];

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
            this.coreDataVersion = dataVersionOf(ChunkPos.asLong(corePos.x, corePos.z));
            if (sectionCount <= 64) {
                this.neighborOldSky = new byte[4][sectionCount][];
                this.neighborOldBlock = new byte[4][sectionCount][];
                this.neighborDataVersions = new int[4];
            } else {
                this.neighborOldSky = null;
                this.neighborOldBlock = null;
                this.neighborDataVersions = null;
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
            coreDataVersion = dataVersionOf(ChunkPos.asLong(corePos.x, corePos.z));
        }

        /**
         * 数据替换（同对象，onChunkDataReplaced）时只重采样核心柱：邻柱地形未变，
         * 旧快照仍有效。全清从头 capture 在连续 delta（放方块/水流）下每次都被打断
         * → 任务永远 capture 不完 → 重算永不落地（引擎旧光/错光持久 = 黑块）。
         * 核心柱重采样 1/9 柱，连续变化下也能收敛到最后一个 delta 之后。
         */
        private void restartCoreOnly() {
            // DOMAIN_OFFSETS[4] == {0, 0}：核心柱（见 DOMAIN_OFFSETS 定义）
            snapshots[4] = null;
            if (nextColumn > 4) {
                nextColumn = 4; // 重采样核心柱；其后邻柱已捕获且未变，重复采样幂等无害
            }
            coreDataVersion = dataVersionOf(ChunkPos.asLong(corePos.x, corePos.z));
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
        int minSection = LevelHeightCompat.getMinSection(level);
        int maxSection = LevelHeightCompat.getMaxSectionExclusive(level);
        CaptureTask task = pendingByCore.get(key);
        if (task != null) {
            task.retainNbt(cachedNbt);
            if (task.expectedCoreChunk != coreChunk) {
                task.restart(coreChunk);
            }
            // 连续 delta 的域并集扩张：capture 完成前主线程可合并，超过阈值回退整 chunk。
            SectionDomain nd = inferChangeSpan(cachedNbt, minSection, maxSection);
            if (nd != null && task.domainSectionCount > 0) {
                int dMin = Math.max(minSection, nd.minSectionY() - 1);
                int dMax = Math.min(maxSection, nd.maxSectionYExclusive() + 1);
                int mergedMin = Math.min(task.domainMinSection, dMin);
                int mergedMax = Math.max(task.domainMinSection + task.domainSectionCount, dMax);
                if (mergedMax - mergedMin > SEGMENT_MAX_DOMAIN_SECTIONS) {
                    task.domainMinSection = -1;      // 并集超阈值 → 回退整 chunk
                    task.domainSectionCount = 0;
                } else {
                    task.domainMinSection = mergedMin;
                    task.domainSectionCount = mergedMax - mergedMin;
                }
            }
            return;
        }
        // 新任务：从缓存 NBT 推断变化 section 的 y 跨度（D = 跨度 ±1 圈），超过阈值整 chunk。
        int dMin = -1, dCount = 0;
        SectionDomain nd = inferChangeSpan(cachedNbt, minSection, maxSection);
        if (nd != null) {
            int tMin = Math.max(minSection, nd.minSectionY() - 1);
            int tMax = Math.min(maxSection, nd.maxSectionYExclusive() + 1);
            if (tMax - tMin <= SEGMENT_MAX_DOMAIN_SECTIONS) {
                dMin = tMin;
                dCount = tMax - tMin;
            }
        }
        task = new CaptureTask(corePos, minSection, maxSection - minSection,
                LevelHeightCompat.getMinBlockY(level), level.getHeight(), coreChunk, cachedNbt, generation);
        task.domainMinSection = dMin;
        task.domainSectionCount = dCount;
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
                    // 9 柱（DOMAIN_OFFSETS 序）捕获时刻版本：分段落地校验（壳光/邻柱区输入时效）
                    int domIdx = domainIndexFor(off);
                    if (domIdx >= 0) {
                        task.domainDataVersions[domIdx] = dataVersionOf(ChunkPos.asLong(cx, cz));
                    }
                    // 4 邻柱：立即抓旧光照（克隆防后台读与官方写竞态；缓存命中仍重抓，无害）。
                    // 对角柱不需旧光照（非落地目标柱）；核心柱结果由 W=48 求解域直接产出，
                    // 不再抓核心柱旧值。null 层 = 全 0（无光）。
                    // 分段任务不做 diff（D 覆盖写），跳过旧光抓取（省 ~0.4ms/任务）。
                    // sectionCount > 64 时邻柱传播降级（掩码 long 装不下），不抓邻柱旧值。
                    int nbIdx = neighborIndexFor(off);
                    boolean needOldLight = nbIdx >= 0 && task.neighborOldSky != null
                            && task.domainSectionCount == 0;
                    if (needOldLight) {
                        captureOldLight(level, task, cx, cz, nbIdx);
                        // 邻柱捕获时刻版本：落地时校验，邻柱数据在捕获后变化则跳过该柱写入
                        // （旧地形 solve 的邻柱区值不得写进新地形，防 copyMasked 只增亮扩散错值）
                        task.neighborDataVersions[nbIdx] = dataVersionOf(ChunkPos.asLong(cx, cz));
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
                // W=48 求解域 == 传播域：9 柱全宽组装（含对角柱——光按曼哈顿距离传播，
                // 对角柱内距核心柱内格 ≤15 的光源（如 (16,16) 距 (14,14) 仅 4 格）会照进
                // 核心柱；缺角会系统性偏暗）。核心柱 [16,32)² 两侧 halo 16 格 ≥ 传播半径
                // 15 → solve 结果与全量重算一致，无需校准吸收（历史：W=32 halo 8 格截断
                // → core 柱整体偏暗，落地为永久黑块，见常量注释）。
                //
                // 分段模式（domainSectionCount > 0）：求解域裁剪为 D（变化 section ±1 圈）+
                // 上下各 1 层壳（壳光从缓存 NBT 读，作为边界种子）。物理事实：光强 0–15 每格
                // 衰减 ≥1，任何方块变化影响范围 ≤15 格 < 16 格（1 section）→ D 之外的光不变，
                // 壳种子承载边界值后，D 内解 = 全量解。solve 量降 ~7 倍（变化 1 section 时）。
                int ww48 = PROP_W * PROP_W;
                int dMin = task.domainMinSection;
                int dCount = task.domainSectionCount;
                boolean segmented = dCount > 0;
                int solveHeight = task.height;
                int worldY0 = task.minY;
                int domainMinY = task.minY;
                int domainMaxYEx = task.minY + task.height;
                int shellBottom = 0, shellTop = 0;
                int[] skyShellSeeds = null, blockShellSeeds = null;
                CompoundTag nbt = task.cachedNbt;
                if (nbt == null) {
                    nbt = io.github.limuqy.mc.hassium.network.ClientChunkHandler
                            .loadChunkNbtFromCache(task.corePos);
                }
                if (segmented) {
                    int minSection = task.minSection;
                    int maxSection = task.minSection + task.sectionCount;
                    shellBottom = (dMin - 1 >= minSection) ? 1 : 0;   // 底壳 section dMin-1 存在
                    shellTop = (dMin + dCount < maxSection) ? 1 : 0;  // 顶壳 section dMin+dCount 存在
                    solveHeight = dCount * 16 + shellBottom + shellTop;
                    worldY0 = dMin * 16 - shellBottom;
                    domainMinY = dMin * 16;
                    domainMaxYEx = (dMin + dCount) * 16;
                    if (nbt == null) {
                        segmented = false;
                    } else {
                        skyShellSeeds = buildShellSeeds(nbt, minSection, dMin, dCount,
                                shellBottom, shellTop, PROP_W, solveHeight, 0 /* sky */);
                        blockShellSeeds = buildShellSeeds(nbt, minSection, dMin, dCount,
                                shellBottom, shellTop, PROP_W, solveHeight, 1 /* block */);
                        if ((shellBottom > 0 && (skyShellSeeds == null || blockShellSeeds == null))
                                || (shellTop > 0 && (skyShellSeeds == null || blockShellSeeds == null))) {
                            segmented = false;   // 壳 section 合法但 NBT 无光（连续 delta 域并集把壳
                            // section 也变了）→ 回退全量 solve（正确性不降级，仅丢一次优化）
                        }
                    }
                }
                byte[] propLight;
                int[] propShape;
                int[] propSourceY;
                List<Integer> propEmitters = new ArrayList<>();
                List<VoxelShape[]> propShapes = new ArrayList<>();
                if (segmented) {
                    propLight = new byte[ww48 * solveHeight];
                    propShape = new int[ww48 * solveHeight];
                    propSourceY = new int[ww48];
                    Arrays.fill(propSourceY, LightFloodFill.NO_COLUMN);
                    for (int i = 0; i < DOMAIN_OFFSETS.length; i++) {
                        int[] off = DOMAIN_OFFSETS[i];
                        assembleDomain(task.snapshots[i], off[0], off[1], worldY0, solveHeight, task.minY,
                                domainMinY, domainMaxYEx, propLight, propShape, propSourceY,
                                propEmitters, propShapes);
                    }
                } else {
                    propLight = new byte[ww48 * task.height];   // 遮挡（每格 lightBlock）
                    propShape = new int[ww48 * task.height];
                    propSourceY = new int[ww48];
                    Arrays.fill(propSourceY, LightFloodFill.NO_COLUMN);
                    for (int i = 0; i < DOMAIN_OFFSETS.length; i++) {
                        int[] off = DOMAIN_OFFSETS[i];
                        assembleFull(task.snapshots[i], off[0], off[1], task.height,
                                propLight, propShape, propSourceY, propEmitters, propShapes);
                    }
                }
                int[] emitter48 = propEmitters.stream().mapToInt(Integer::intValue).toArray();
                VoxelShape[][] shapeTable48 = propShapes.toArray(new VoxelShape[0][]);
                LightFloodFill.Occlusion occlusion48 = (srcShape, dstShape, dir) -> Shapes.faceShapeOccludes(
                        shapeTable48[srcShape - 1][dir], shapeTable48[dstShape - 1][OPPOSITE_DIR[dir]]);
                if (segmented && segLog.getAndIncrement() < 10) {
                    Constants.LOG.info("[LIGHT-SEG] chunk {} dMin={} dCount={} H={} shellB={} shellT={}",
                            task.corePos, dMin, dCount, solveHeight, shellBottom, shellTop);
                }
                // 全域 solve（block/sky 独立计时）：核心柱与邻柱区的最终值一步到位。
                // 无增量传播/无模式判定：solve 已含 decrease 与邻柱旧值无关的全部语义。
                long splitT0 = System.nanoTime();
                byte[] blockLight = LightFloodFill.solveBlock(PROP_W, solveHeight,
                        propLight, emitter48, propShape, occlusion48, blockShellSeeds, shellBottom, shellTop);
                long splitT1 = System.nanoTime();
                byte[] skyLight = LightFloodFill.solveSky(PROP_W, solveHeight,
                        propLight, propSourceY, propShape, occlusion48, skyShellSeeds, shellBottom, shellTop);
                long splitT2 = System.nanoTime();
                if (diagSplit.getAndIncrement() < 20) {
                    Constants.LOG.info("[LIGHT-DIAG-SPLIT] block={}us sky={}us total={}us",
                            (splitT1 - splitT0) / 1000, (splitT2 - splitT1) / 1000, (splitT2 - splitT0) / 1000);
                }
                // 提取：核心柱全 section + 邻柱差异 section（掩码位 = 变化 section）
                // 分段：核心柱 D + 4 邻柱 D + 4 对角柱 D（覆盖写，不做 diff——solve 域含壳层保证 D 内精确）
                long propT0 = System.nanoTime();
                byte[][] skySections;
                byte[][] blockSections;
                long[] neighborSkyMasks = null;
                long[] neighborBlockMasks = null;
                byte[][][] neighborSkySections = null;
                byte[][][] neighborBlockSections = null;
                byte[][][] cornerSkySections = null;
                byte[][][] cornerBlockSections = null;
                int resDomainMin = -1;
                int resDomainCount = 0;
                if (segmented) {
                    resDomainMin = dMin;
                    resDomainCount = dCount;
                    int yOffset = shellBottom;
                    skySections = extractRegion(skyLight, PROP_CORE_OFFSET, PROP_CORE_OFFSET,
                            yOffset, dCount, solveHeight);
                    blockSections = extractRegion(blockLight, PROP_CORE_OFFSET, PROP_CORE_OFFSET,
                            yOffset, dCount, solveHeight);
                    long fullMask = (dCount >= 64 ? -1L : (1L << dCount) - 1);
                    neighborSkyMasks = new long[4];
                    neighborBlockMasks = new long[4];
                    Arrays.fill(neighborSkyMasks, fullMask);   // 诊断用；分段落地不读掩码
                    Arrays.fill(neighborBlockMasks, fullMask);
                    neighborSkySections = new byte[4][][];
                    neighborBlockSections = new byte[4][][];
                    int[] nbDomIdx = {1, 7, 3, 5};   // DOMAIN_OFFSETS 序的 N/S/W/E
                    for (int nb = 0; nb < 4; nb++) {
                        int[] off = DOMAIN_OFFSETS[nbDomIdx[nb]];
                        int ox = off[0] == 0 ? PROP_CORE_OFFSET : (off[0] < 0 ? 0 : 32);
                        int oz = off[1] == 0 ? PROP_CORE_OFFSET : (off[1] < 0 ? 0 : 32);
                        neighborSkySections[nb] = extractRegion(skyLight, ox, oz, yOffset, dCount, solveHeight);
                        neighborBlockSections[nb] = extractRegion(blockLight, ox, oz, yOffset, dCount, solveHeight);
                    }
                    cornerSkySections = new byte[4][][];
                    cornerBlockSections = new byte[4][][];
                    int[] cornerDomIdx = {0, 2, 6, 8};   // DOMAIN_OFFSETS 序的 4 对角
                    for (int i = 0; i < 4; i++) {
                        int[] off = DOMAIN_OFFSETS[cornerDomIdx[i]];
                        int ox = off[0] == 0 ? PROP_CORE_OFFSET : (off[0] < 0 ? 0 : 32);
                        int oz = off[1] == 0 ? PROP_CORE_OFFSET : (off[1] < 0 ? 0 : 32);
                        cornerSkySections[i] = extractRegion(skyLight, ox, oz, yOffset, dCount, solveHeight);
                        cornerBlockSections[i] = extractRegion(blockLight, ox, oz, yOffset, dCount, solveHeight);
                    }
                } else {
                    skySections = extractRegion(skyLight, PROP_CORE_OFFSET, PROP_CORE_OFFSET,
                            0, task.sectionCount, task.height);
                    blockSections = extractRegion(blockLight, PROP_CORE_OFFSET, PROP_CORE_OFFSET,
                            0, task.sectionCount, task.height);
                    if (task.sectionCount <= 64) {
                        neighborSkyMasks = new long[4];
                        neighborBlockMasks = new long[4];
                        neighborSkySections = new byte[4][][];
                        neighborBlockSections = new byte[4][][];
                        for (int nb = 0; nb < 4; nb++) {
                            int[] off = PROP_OFFSETS[nb];
                            int ox = off[0] == 0 ? PROP_CORE_OFFSET : (off[0] < 0 ? 0 : 32);
                            int oz = off[1] == 0 ? PROP_CORE_OFFSET : (off[1] < 0 ? 0 : 32);
                            neighborSkySections[nb] = diffNeighborColumn(skyLight, ox, oz, task,
                                    task.neighborOldSky[nb], neighborSkyMasks, nb);
                            neighborBlockSections[nb] = diffNeighborColumn(blockLight, ox, oz, task,
                                    task.neighborOldBlock[nb], neighborBlockMasks, nb);
                        }
                    }
                }
                long propT2 = System.nanoTime();
                // 诊断：域内发射源总数 + solve 后非零格数（排查发射源捕获/求解缺失）
                int emitN = 0;
                for (int i = 0; i < DOMAIN_OFFSETS.length; i++) {
                    emitN += task.snapshots[i].getEmitters().length;
                }
                int blockNonZero = 0;
                if (segmented) {
                    int yOff = shellBottom;
                    for (int s = 0; s < dCount; s++) {
                        int y0 = yOff + s * 16;
                        int y1 = Math.min(y0 + 16, solveHeight);
                        for (int y = y0; y < y1; y++) {
                            int base = y * ww48;
                            for (int i = 0; i < ww48; i++) {
                                blockNonZero += (blockLight[base + i] & 0xFF) != 0 ? 1 : 0;
                            }
                        }
                    }
                } else {
                    for (int s = 0; s < task.sectionCount; s++) {
                        int y0 = s * 16;
                        int y1 = Math.min(y0 + 16, task.height);
                        for (int y = y0; y < y1; y++) {
                            int base = y * ww48;
                            for (int i = 0; i < ww48; i++) {
                                blockNonZero += (blockLight[base + i] & 0xFF) != 0 ? 1 : 0;
                            }
                        }
                    }
                }
                diagProp("solve={}us extract={}us neighSections={} emitN={} blockNonZero={}",
                        (splitT2 - splitT0) / 1000, (propT2 - propT0) / 1000,
                        neighborSectionCount(neighborSkyMasks, neighborBlockMasks), emitN, blockNonZero);
                NetworkStats.recordLightRecomputeBackgroundTime(System.nanoTime() - backgroundStartNs);
                diagBg("done {} elapsed={}us", task.corePos, (System.nanoTime() - backgroundStartNs) / 1000);
                if (task.generation == generation) {
                    if (!results.offer(new LightComputeResult(task.corePos, task.expectedCoreChunk,
                            skySections, blockSections,
                            neighborSkyMasks, neighborSkySections,
                            neighborBlockMasks, neighborBlockSections,
                            nbt, task.generation, task.captureNanos,
                            task.coreDataVersion, task.neighborDataVersions,
                            cornerSkySections, cornerBlockSections,
                            resDomainMin, resDomainCount,
                            segmented ? task.domainDataVersions : null))) {
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
     * 预算内循环对每个结果做「建层 + 主线程 memcpy 落地 + 官方传播种子入队」
     * （自研 W=48 solve 已在后台完成，含核心柱全 section 与邻柱差异 section），逐结果入批；
     * 批尾 {@code safeRunLightUpdates} 一次收敛官方队列（种子 = 注入点/发射源，增量小——
     * solve 已正确，仅补竞态偏暗格），然后逐结果做验算对比 + 缓存写回（写回后台化）。
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
        // 批尾统一传播：官方队列种子（第 4 步入队的注入点/发射源/边界光）在此收敛到不动点，
        // 补亮竞态偏暗格（solve 已正确，增量小；实测 safeRunLightUpdates 空队列 ~0.1ms）。
        // 帧预算外执行（同旧架构批尾 runLightUpdates 语义：预算循环只限 poll）。
        try {
            ClientLightRecomputeService.safeRunLightUpdates(lightEngine);
        } catch (Throwable t) {
            Constants.LOG.error("Hassium: Failed to run light updates for parallel batch", t);
        }
        // 最终收敛探针（诊断，跑一次）：队列全空 = 加载结束，官方在最后落地值上继续传播的
        // 增量 > 0 即最终状态仍有暗格残留（黑块等待不消的量化）。纯观察：先 clone、后恢复。
        if (pendingCaptures.isEmpty() && results.isEmpty() && !finalProbeDone && !batch.isEmpty()) {
            finalProbeDone = true;
            LightComputeResult r = batch.get(batch.size() - 1);
            if (r.domainSectionCount() > 0) {
                // 分段结果：probe 的 minSection+s 索引错位（核心柱 section 数组 = D y），跳过
            } else {
            try {
                int minSection = LevelHeightCompat.getMinSection(level);
                int maxSection = LevelHeightCompat.getMaxSectionExclusive(level);
                int sectionCount = Math.min(r.skySections().length, maxSection - minSection);
                LayerLightEventListener skyListener = lightEngine.getLayerListener(LightLayer.SKY);
                LayerLightEventListener blockListener = lightEngine.getLayerListener(LightLayer.BLOCK);
                byte[][] snapSky = snapshotColumn(skyListener, r.corePos().x, r.corePos().z, minSection, sectionCount);
                byte[][] snapBlock = snapshotColumn(blockListener, r.corePos().x, r.corePos().z, minSection, sectionCount);
                lightEngine.setLightEnabled(r.corePos(), true);
                lightEngine.propagateLightSources(r.corePos());
                ClientLightRecomputeService.safeRunLightUpdates(lightEngine);
                long increased = 0;
                for (int s = 0; s < sectionCount; s++) {
                    SectionPos sp = SectionPos.of(r.corePos().x, minSection + s, r.corePos().z);
                    DataLayer sky = skyListener.getDataLayerData(sp);
                    DataLayer block = blockListener.getDataLayerData(sp);
                    byte[] skyArr = sky == null ? null : sky.getData();
                    byte[] blockArr = block == null ? null : block.getData();
                    if (skyArr != null) {
                        for (int i = 0; i < 2048; i++) {
                            int before = snapSky[s][i] & 0xFF;
                            int now = skyArr[i] & 0xFF;
                            if ((now & 0x0F) > (before & 0x0F)) {
                                increased++;
                            }
                            if ((now >> 4) > (before >> 4)) {
                                increased++;
                            }
                        }
                    }
                    if (blockArr != null) {
                        for (int i = 0; i < 2048; i++) {
                            int before = snapBlock[s][i] & 0xFF;
                            int now = blockArr[i] & 0xFF;
                            if ((now & 0x0F) > (before & 0x0F)) {
                                increased++;
                            }
                            if ((now >> 4) > (before >> 4)) {
                                increased++;
                            }
                        }
                    }
                    if (skyArr != null) {
                        System.arraycopy(snapSky[s], 0, skyArr, 0, 2048);
                    }
                    if (blockArr != null) {
                        System.arraycopy(snapBlock[s], 0, blockArr, 0, 2048);
                    }
                }
                Constants.LOG.info("[LIGHT-PROBE-FINAL] chunk {} increased={} (0 = 最终无暗格残留)", r.corePos(), increased);
                } catch (Throwable t) {
                    Constants.LOG.warn("Hassium: final light probe failed", t);
                }
            }
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
        dataVersions.clear();
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
        if (r.coreDataVersion() != dataVersionOf(ChunkPos.asLong(r.corePos().x, r.corePos().z))) {
            // 数据替换（delta/全量/缓存 apply 同对象 replaceWithPacketData）后旧任务结果作废：
            // solve 基于旧地形，落地会覆盖新地形（深水区亮暗跳变根因）。替换时 TAIL 已
            // 重新 submit，本块由新任务收敛。
            diagApply("drop {} reason=dataVersion {}!={}", r.corePos(),
                    r.coreDataVersion(), dataVersionOf(ChunkPos.asLong(r.corePos().x, r.corePos().z)));
            return false;
        }
        if (r.domainSectionCount() > 0 && r.domainDataVersions() != null) {
            // 分段结果：9 柱（DOMAIN_OFFSETS 序）任一在捕获后被替换 → 整结果丢弃
            // （壳光/邻柱区输入失效；该柱由自身新任务收敛）。
            for (int i = 0; i < DOMAIN_OFFSETS.length; i++) {
                int[] off = DOMAIN_OFFSETS[i];
                if (r.domainDataVersions()[i] != dataVersionOf(
                        ChunkPos.asLong(r.corePos().x + off[0], r.corePos().z + off[1]))) {
                    diagApply("drop {} reason=domain version col={}", r.corePos(), i);
                    return false;
                }
            }
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

        // 分段落地：9 柱 × D y 覆盖写（solve 域含壳层保证 D 内全部精确）+ 官方种子/传播（整柱范围）。
        if (r.domainSectionCount() > 0) {
            int dMin = r.domainMinSection();
            int dCount = r.domainSectionCount();
            // 1. 建层：9 柱 × D y（未加载邻柱跳过——其加载时自会重算，避免为卸载柱建光层）
            for (int[] off : DOMAIN_OFFSETS) {
                int cx = r.corePos().x + off[0];
                int cz = r.corePos().z + off[1];
                if (off[0] != 0 || off[1] != 0) {
                    if (level.getChunkSource().getChunkNow(cx, cz) == null) {
                        continue;
                    }
                }
                ClientLightRecomputeService.ensureColumnDataLayers(level, lightEngine,
                        new ChunkPos(cx, cz), dMin, dMin + dCount);
            }
            // 传播会读 4 邻柱全列 DataLayer（官方 runLightUpdates 路径，缺层有 NPE 先例 1.21.10）
            ClientLightRecomputeService.ensureNeighborDataLayers(level, lightEngine, r.corePos(), minSection, maxSection);
            // 2. 写入：核心柱全写 + 4 邻柱全写 + 4 对角全写（覆盖式）
            LayerLightEventListener skyListener = lightEngine.getLayerListener(LightLayer.SKY);
            LayerLightEventListener blockListener = lightEngine.getLayerListener(LightLayer.BLOCK);
            int[] nbDomIdx = {1, 7, 3, 5};   // DOMAIN_OFFSETS 序的 N/S/W/E
            int[] cornerDomIdx = {0, 2, 6, 8};
            writeColumnSections(level, lightEngine, skyListener, blockListener,
                    r.corePos().x, r.corePos().z, dMin, dCount, r.skySections(), r.blockSections());
            for (int nb = 0; nb < 4; nb++) {
                int[] off = DOMAIN_OFFSETS[nbDomIdx[nb]];
                writeColumnSections(level, lightEngine, skyListener, blockListener,
                        r.corePos().x + off[0], r.corePos().z + off[1], dMin, dCount,
                        r.neighborSkySections()[nb], r.neighborBlockSections()[nb]);
            }
            for (int i = 0; i < 4; i++) {
                int[] off = DOMAIN_OFFSETS[cornerDomIdx[i]];
                writeColumnSections(level, lightEngine, skyListener, blockListener,
                        r.corePos().x + off[0], r.corePos().z + off[1], dMin, dCount,
                        r.cornerSkySections()[i], r.cornerBlockSections()[i]);
            }
            // 3. 官方种子与传播（与全量路径相同，保持整柱范围）：D 外 section 的官方种子 =
            //    旧光正确值，increase-only 传播无净变化（stability 论证），批尾 runLightUpdates 收敛。
            lightEngine.setLightEnabled(r.corePos(), true);
            lightEngine.propagateLightSources(r.corePos());
            ClientLightRecomputeService.pullLightFromNeighborEdges(level, r.corePos(), minSection, maxSection);
            return;
        }

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
                if (r.neighborDataVersions() == null
                        || r.neighborDataVersions()[nb] != dataVersionOf(ChunkPos.asLong(nx, nz))) {
                    // 邻柱数据在捕获后已替换：本任务算出的该柱区基于旧地形，不得落地
                    // （copyMasked 只增亮会把旧地形亮值写进新地形）；该柱由自身新任务收敛。
                    continue;
                }
                copyMasked(r.neighborSkySections()[nb], skyMasks[nb], nx, nz, skyListener, minSection, level);
                copyMasked(r.neighborBlockSections()[nb], blockMasks[nb], nx, nz, blockListener, minSection, level);
            }
        }
        // 4. 官方传播种子入队（成本 ~0.1-0.4ms/块，批尾 runLightUpdates 统一收敛）：
        //    a) propagateLightSources：sky 注入 + block 发射源种子（覆盖 solve 快照过期；
        //       实测 stabilityIncreased 395→0，落地值成为官方不动点）
        //    b) pullLightFromNeighborEdges：8 邻柱边界光拉进核心柱边缘（差 >1 入队）——
        //       修复 solve 输入缺邻居（empty 兜底 / 邻居后落地）导致的边缘暗格，
        //       批尾传播后收敛。缺此步则边缘暗格永久（黑块等待不消）。
        lightEngine.setLightEnabled(r.corePos(), true);
        lightEngine.propagateLightSources(r.corePos());
        ClientLightRecomputeService.pullLightFromNeighborEdges(level, r.corePos(), minSection, maxSection);
    }

    /** 单柱 D y section 覆盖写（分段落地）：DataLayer 非 null 时整段覆盖 + 脏标记（核心柱 memcpy 循环同款）。 */
    private static void writeColumnSections(ClientLevel level, LevelLightEngine lightEngine,
                                            LayerLightEventListener skyListener, LayerLightEventListener blockListener,
                                            int chunkX, int chunkZ, int dMin, int dCount,
                                            byte[][] skySections, byte[][] blockSections) {
        for (int s = 0; s < dCount; s++) {
            SectionPos sp = SectionPos.of(chunkX, dMin + s, chunkZ);
            DataLayer sky = skyListener.getDataLayerData(sp);
            if (sky != null) {
                System.arraycopy(skySections[s], 0, sky.getData(), 0, 2048);
            }
            DataLayer block = blockListener.getDataLayerData(sp);
            if (block != null) {
                System.arraycopy(blockSections[s], 0, block.getData(), 0, 2048);
            }
            level.setSectionDirtyWithNeighbors(chunkX, dMin + s, chunkZ);
        }
    }

    /** 掩码位 → 变化 section 的「只增亮」合并（邻柱差异落地策略）。
     *  邻柱最终正确值由邻柱自身任务（W=48 solve 全 section memcpy）保证；本任务算出的
     *  邻柱区在 48 域边界截断 1-2 格可能偏暗，若整段覆盖会与邻柱任务落地顺序形成竞态
     *  黑线。逐 nibble max 合并 → 提前点亮、永不写暗；decrease 由邻柱自身任务落地。
     *  null 层防御：邻柱未加载/未建层时跳过，加载时自会重算。 */
    private static void copyMasked(byte[][] sections, long mask, int chunkX, int chunkZ,
                                   LayerLightEventListener listener, int minSection, ClientLevel level) {
        for (int s = 0; s < sections.length; s++) {
            if ((mask >>> s & 1L) != 0) {
                SectionPos sp = SectionPos.of(chunkX, minSection + s, chunkZ);
                DataLayer layer = listener.getDataLayerData(sp);
                if (layer != null) {
                    byte[] dst = layer.getData();
                    byte[] src = sections[s];
                    for (int i = 0; i < 2048; i++) {
                        int d = dst[i] & 0xFF;
                        int v = src[i] & 0xFF;
                        int dl = d & 0x0F;
                        int dh = d >> 4;
                        int sl = v & 0x0F;
                        int sh = v >> 4;
                        dst[i] = (byte) ((Math.max(dh, sh) << 4) | Math.max(dl, sl));
                    }
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
        // 分段结果：核心柱 section 数组 = D y → oracle 起点 = D 底；全量 = minSection。
        int oracleStart = r.domainSectionCount() > 0 ? r.domainMinSection() : minSection;
        int sectionCount = Math.min(r.skySections().length, maxSection - oracleStart);
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
                    oracleStart, sectionCount);
            snapBlock[snapIdx] = snapshotColumn(blockListener, r.corePos().x + off[0], r.corePos().z + off[1],
                    oracleStart, sectionCount);
            snapIdx++;
        }
        // 2. 层清零（官方从零重算的初始状态）。第 1 个验算块额外清零整个 3×3 域并重播全部
        //    9 柱种子——官方引擎在【与自研 solve 完全相同的边界】上计算，剩余差异 = 纯算法缺陷；
        //    常规块只清零核心柱（邻柱 stored 值 = 真实世界边界，含域外光源）。
        //    分段：清零范围 = 9 柱 × D y（sectionCount = D 长度，循环天然只覆盖 D）。
        boolean zero3x3 = verifyInputSample.get() == 0;
        for (int[] off : DOMAIN_OFFSETS) {
            if (!zero3x3 && !(off[0] == 0 && off[1] == 0)) {
                continue;
            }
            for (int s = 0; s < sectionCount; s++) {
                SectionPos sp = SectionPos.of(r.corePos().x + off[0], oracleStart + s, r.corePos().z + off[1]);
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
            SectionPos sp = SectionPos.of(r.corePos().x, oracleStart + s, r.corePos().z);
            DataLayer sky = skyListener.getDataLayerData(sp);
            officialSky[s] = sky == null ? new byte[2048] : sky.getData().clone();
            DataLayer block = blockListener.getDataLayerData(sp);
            officialBlock[s] = block == null ? new byte[2048] : block.getData().clone();
        }
        if (zero3x3) {
            // W 柱读回（全量模式用；分段模式跳过，见下）
            byte[][] offW = null;
            byte[][] offWB = null;
            if (r.domainSectionCount() > 0) {
                // 分段：只对比核心柱 D（offW/W 柱数组与 shaft 探针在分段模式下跳过——
                // 邻柱差异数组分段模式 = D 覆盖写，W 柱剖面无全列可比）
                long zs = 0, zb = 0;
                StringBuilder zsamples = new StringBuilder();
                for (int s = 0; s < sectionCount; s++) {
                    zs += diffLayers(officialSky[s], r.skySections()[s]);
                    zb += diffLayers(officialBlock[s], r.blockSections()[s]);
                    int sectionY = oracleStart + s;
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
            } else {
                // 邻柱（W）读回：官方传播在 W 柱的值（对比自研 W 柱解算值）
                offW = new byte[sectionCount][];
                offWB = new byte[sectionCount][];
                for (int s = 0; s < sectionCount; s++) {
                    SectionPos wsp = SectionPos.of(r.corePos().x - 1, oracleStart + s, r.corePos().z);
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
                    int sectionY = oracleStart + s;
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
            }
            // 差异格现场：官方 vs 自研 vs LIVE 方块 lightBlock（澄清 opacity 输入是否一致）
            StringBuilder ctx = new StringBuilder();
            for (int s = 0; s < sectionCount && ctx.length() < 1200; s++) {
                int sectionY = oracleStart + s;
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
                        int li = sec - oracleStart;
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
            // 柱状剖面：W 柱发射源沿 y 方向 8 格——官方 vs 自研（找传播分歧起点）。
            // 分段模式跳过：neighborBlockSections = D 覆盖写（非差异），且 offW 未读回。
            if (r.domainSectionCount() == 0
                    && r.neighborBlockSections() != null && r.neighborBlockSections().length > 2) {
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
                            int li = sec - oracleStart;
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
        //     分段：replay 全高 solve，核心柱提取偏移 = D 底（replaySolve 内处理）。
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
        //     分段跳过：stability 的 r.skySections()[s] 按 minSection 索引会错位（D 数组）。
        if (r.domainSectionCount() == 0 && verifyInputSample.get() < 2) {
            long increased = stabilityPass(level, lightEngine, skyListener, blockListener,
                    r, officialSky, officialBlock, minSection, sectionCount);
            dumpInputComparison(level, r.corePos(), r, increased);
        } else if (r.domainSectionCount() > 0) {
            // 分段不跑 stability/dumpInput：推进采样计数，避免 zero3x3/replay 对每个分段块重复执行
            verifyInputSample.incrementAndGet();
        }
        // 5. 恢复：核心柱 ← 我们的值；8 邻柱 ← 快照（验算纯观察，生产语义 = memcpy-only）
        for (int s = 0; s < sectionCount; s++) {
            SectionPos sp = SectionPos.of(r.corePos().x, oracleStart + s, r.corePos().z);
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
            restoreColumn(skyListener, r.corePos().x + off[0], r.corePos().z + off[1], oracleStart, snapSky[snapIdx]);
            restoreColumn(blockListener, r.corePos().x + off[0], r.corePos().z + off[1], oracleStart, snapBlock[snapIdx]);
            snapIdx++;
        }
        // 6. 逐格对比（官方数组 vs 我们的数组；内芯与边界分开计）
        long skyMismatch = 0;
        long blockMismatch = 0;
        long edgeSky = 0;
        long edgeBlock = 0;
        StringBuilder samples = new StringBuilder();
        for (int s = 0; s < sectionCount; s++) {
            int sectionY = oracleStart + s;
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
        byte[] sky = LightFloodFill.solveSky(PROP_W, height, propLight, propSourceY, propShape, occlusion48, null, 0, 0);
        byte[] block = LightFloodFill.solveBlock(PROP_W, height, propLight, emitter48, propShape, occlusion48, null, 0, 0);
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
        // 分段结果：核心柱 section 数组 = D y，replay 域 = 全高 → 提取偏移 = D 底相对 minSection 的格数。
        int oracleStart = r.domainSectionCount() > 0 ? r.domainMinSection() : minSection;
        int yOffset = (oracleStart - minSection) * 16;
        byte[][] simSkySec = extractRegion(simSky, PROP_CORE_OFFSET, PROP_CORE_OFFSET, yOffset, sectionCount, height);
        byte[][] simBlockSec = extractRegion(simBlock, PROP_CORE_OFFSET, PROP_CORE_OFFSET, yOffset, sectionCount, height);
        long simVsOffSky = 0;
        long simVsOffBlock = 0;
        for (int s = 0; s < sectionCount; s++) {
            simVsOffSky += diffLayers(simSkySec[s], officialSky[s]);
            simVsOffBlock += diffLayers(simBlockSec[s], officialBlock[s]);
        }
        Constants.LOG.error("[LIGHT_VERIFY-SIMVSORACLE] chunk {} sky={} block={}",
                r.corePos(), simVsOffSky, simVsOffBlock);
        byte[][] skySec = extractRegion(sky, PROP_CORE_OFFSET, PROP_CORE_OFFSET, yOffset, sectionCount, height);
        byte[][] blockSec = extractRegion(block, PROP_CORE_OFFSET, PROP_CORE_OFFSET, yOffset, sectionCount, height);
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
     * 主线程抓取 4 邻柱旧光照（nbIdx 0–3）全 section × 2 层，
     * DataLayer 打包 2048B 克隆（clone 后数组即私有，后台线程只读，与官方写无竞态）。
     * null 层 = 全 0（无光；未加载块或未建层 section）。成本 ~96KB×4 柱 ≈ 0.4ms/任务。
     */
    private void captureOldLight(ClientLevel level, CaptureTask task, int cx, int cz, int nbIdx) {        LevelLightEngine lightEngine = level.getLightEngine();
        LayerLightEventListener skyListener = lightEngine.getLayerListener(LightLayer.SKY);
        LayerLightEventListener blockListener = lightEngine.getLayerListener(LightLayer.BLOCK);
        byte[][] skyTarget = task.neighborOldSky[nbIdx];
        byte[][] blockTarget = task.neighborOldBlock[nbIdx];
        for (int s = 0; s < task.sectionCount; s++) {
            SectionPos sp = SectionPos.of(cx, task.minSection + s, cz);
            DataLayer sky = skyListener.getDataLayerData(sp);
            skyTarget[s] = sky == null ? new byte[2048] : sky.getData().clone();
            DataLayer block = blockListener.getDataLayerData(sp);
            blockTarget[s] = block == null ? new byte[2048] : block.getData().clone();
        }
    }

    /**
     * 全宽柱拷贝（求解/传播域共用）：柱内全 16×16 → 域 [16+dx*16, 32+dx*16)²。
     * emitters/shapeCells 无裁剪（W=48 域需要邻柱/对角柱全宽数据）。
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

    /**
     * 分段域组装：仅 y ∈ [worldY0, worldY0+height)（域内索引 y = 世界 y - worldY0），x/z 全宽 3×3 柱。
     * emitters 只收世界 y ∈ [domainMinY, domainMaxYEx) 的发射源（壳层内的源已含在壳层光值里）。
     * sourceY / lightBlock / shapeCells 全域（含壳层格，壳层格遮挡参与传播判定）。
     */
    private static void assembleDomain(LightColumnSnapshot snap, int dx, int dz,
                                       int worldY0, int height, int minBlockY,
                                       int domainMinY, int domainMaxYEx,
                                       byte[] domainLightBlock, int[] domainShapeIds, int[] domainSourceY,
                                       List<Integer> emitters, List<VoxelShape[]> allShapes) {
        int bx0 = PROP_CORE_OFFSET + dx * 16;
        int bz0 = PROP_CORE_OFFSET + dz * 16;
        byte[] lb = snap.getLightBlock();
        int[] sy = snap.getSourceY();
        int yBase = worldY0 - minBlockY; // 快照 sourceY 基准（minBlockY 相对）→ 域内 y 的平移量
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int domCol = (bz0 + z) * PROP_W + (bx0 + x);
                int s = sy[z * 16 + x];
                domainSourceY[domCol] = (s == LightFloodFill.NO_COLUMN || s == LightFloodFill.NEG_INF)
                        ? s : s - yBase;
                for (int ly = 0; ly < height; ly++) {
                    int worldY = worldY0 + ly;
                    int src = (worldY - minBlockY) * 256 + z * 16 + x;
                    int dst = (ly * PROP_W + (bz0 + z)) * PROP_W + (bx0 + x);
                    domainLightBlock[dst] = lb[src];
                }
            }
        }
        for (int e : snap.getEmitters()) {
            int emission = e >>> 20;
            int cell = e & 0xFFFFF;
            int y = cell >> 8;
            int worldY = minBlockY + y;
            if (worldY < domainMinY || worldY >= domainMaxYEx) {
                continue;
            }
            int z = (cell >> 4) & 0xF;
            int x = cell & 0xF;
            int dst = ((worldY - worldY0) * PROP_W + (bz0 + z)) * PROP_W + (bx0 + x);
            emitters.add((emission << 20) | dst);
        }
        int[] shapeCells = snap.getShapeCells();
        VoxelShape[][] shapeFaces = snap.getShapeFaces();
        for (int i = 0; i < shapeCells.length; i++) {
            int cell = shapeCells[i];
            int y = cell >> 8;
            int worldY = minBlockY + y;
            if (worldY < worldY0 || worldY >= worldY0 + height) {
                continue;
            }
            int z = (cell >> 4) & 0xF;
            int x = cell & 0xF;
            int dst = ((worldY - worldY0) * PROP_W + (bz0 + z)) * PROP_W + (bx0 + x);
            domainShapeIds[dst] = allShapes.size() + 1;
            allShapes.add(shapeFaces[i]);
        }
    }

    /**
     * 从缓存 NBT 读壳层光（底壳 = section dMin-1 的 y=15 层；顶壳 = section dMin+dCount 的 y=0 层），
     * 打包为 solve 壳种子 {@code (level<<20)|域内idx}；壳 section 无对应光字段（或长度 != 2048）→ 返回 null。
     * D 贴世界边界（shellBottom/shellTop 为 0）时不读该侧，直接返回非 null 空数组。
     */
    private static int[] buildShellSeeds(CompoundTag nbt, int minSection, int dMin, int dCount,
                                         int shellBottom, int shellTop, int width, int height,
                                         int layerSky /* 0 = sky_light, 1 = block_light */) {
        String key = layerSky == 0 ? "sky_light" : "block_light";
        int[] seeds = new int[width * width * (shellBottom + shellTop)];
        int n = 0;
        ListTag sectionsList = CompoundTagCompat.getList(nbt, "sections");
        if (shellBottom > 0) {
            int idx = (dMin - 1) - minSection;
            if (idx < 0 || idx >= sectionsList.size()) {
                return null;
            }
            if (!(sectionsList.get(idx) instanceof CompoundTag st)) {
                return null;
            }
            if (!(st.get(key) instanceof ByteArrayTag bat) || bat.getAsByteArray().length != DataLayer.SIZE) {
                return null;
            }
            byte[] data = bat.getAsByteArray();
            for (int z = 0; z < width; z++) {
                for (int x = 0; x < width; x++) {
                    // 底壳层 = section 的 y=15 层；域内 y=0
                    int idxByte = (15 << 8) | (z << 4) | x;
                    int level = (data[idxByte >> 1] >> ((idxByte & 1) * 4)) & 0xF;
                    if (level > 0) {
                        seeds[n++] = (level << 20) | (z * width) + x;
                    }
                }
            }
        }
        if (shellTop > 0) {
            int idx = (dMin + dCount) - minSection;
            if (idx < 0 || idx >= sectionsList.size()) {
                return null;
            }
            if (!(sectionsList.get(idx) instanceof CompoundTag st)) {
                return null;
            }
            if (!(st.get(key) instanceof ByteArrayTag bat) || bat.getAsByteArray().length != DataLayer.SIZE) {
                return null;
            }
            byte[] data = bat.getAsByteArray();
            int topY = height - 1;
            for (int z = 0; z < width; z++) {
                for (int x = 0; x < width; x++) {
                    // 顶壳层 = section 的 y=0 层；域内 y=height-1
                    int idxByte = (z << 4) | x;
                    int level = (data[idxByte >> 1] >> ((idxByte & 1) * 4)) & 0xF;
                    if (level > 0) {
                        seeds[n++] = (level << 20) | (topY * width + z) * width + x;
                    }
                }
            }
        }
        return Arrays.copyOf(seeds, n);
    }

    /** 域内 (ox,oz) 起始的 16×16 柱各 section 提取为 DataLayer 布局的 2048 字节半字节数组。
     *  @param domainYOffset 首个 section 在域内的 y 偏移（格数；全量域 = 0，分段域 = 底壳层数）。 */
    private static byte[][] extractRegion(byte[] domain, int ox, int oz,
                                          int domainYOffset, int sectionCount, int height) {
        byte[][] out = new byte[sectionCount][];
        for (int s = 0; s < sectionCount; s++) {
            byte[] data = new byte[2048];
            int y0 = domainYOffset + s * 16;
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

    /** 邻柱区提取 + 与旧值打包对比：返回变化 section（掩码位 1 对应），并写入 masks[nb]。 */
    private static byte[][] diffNeighborColumn(byte[] domain, int ox, int oz, CaptureTask task,
                                               byte[][] oldSections, long[] masks, int nb) {
        byte[][] packed = extractRegion(domain, ox, oz, 0, task.sectionCount, task.height);
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
