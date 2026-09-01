package io.github.limuqy.mc.hassium.network.seedgen;

import io.github.limuqy.mc.hassium.cache.client.ClientMainThreadBudget;
import io.github.limuqy.mc.hassium.concurrent.HassiumTaskExecutor;
import io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue;
import io.github.limuqy.mc.hassium.concurrent.TaskCategory;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.ClientChunkHandler;
import io.github.limuqy.mc.hassium.network.ClientChunkHandler.TraceOrigin;
import io.github.limuqy.mc.hassium.network.ClientChunkPipeline;
import io.github.limuqy.mc.hassium.compat.LevelCompat;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DimensionKey;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * 影子区块投递/回传管线：远程压缩通道解压出的区块数据统一注入影子服务端
 * （{@link ShadowSeedServer}，完整 ServerLevel + 官方光照引擎 + 持久存档），
 * 影子端算光收敛（原版区块生成后算光同款逻辑）后打包官方区块包（带权威光），
 * 回传客户端主线程走官方通道（{@code ClientPacketListener.handleLevelChunkWithLight}）
 * 落地。客户端不参与缓存读写/光照计算——缓存由影子端世界存档承担
 * （type 126 + chunkHash 落盘）。
 * <p>
 * 线程模型：
 * <ul>
 *   <li>投递（{@link #submit} / {@link #submitGenerated}）：任意线程（解压后台 /
 *       主线程 / SeedGen 生成池），pos→数据 REPLACE 覆盖（同柱新数据盖旧）</li>
 *   <li>消费（后台池单循环 CAS，管道化）：取批 → 注入 → 请求原版 {@code ChunkMap} 的
 *       {@code ChunkStatus.LIGHT} future（含 holder 依赖）→ 打包回传。本类不直接驱动
 *       {@code LightEngine}，也不创建隔离预览引擎；在途上限
 *       {@link #PIPELINE_MAX_INFLIGHT} 只计等待原版 future 的柱</li>
 *   <li>光照完成（原版 LIGHT future 回调）：{@link #completeLight} exactly-once 收口</li>
 *   <li>客户端主线程：{@link #drainReady}（帧尾，MixinClientTick）攒批 light 包入
 *       同一 FIFO 回传队列，按到达顺序落地（时间预算）</li>
 * </ul>
 * 影子端不可用（未握手 / 创建失败 / 引擎关闭）时不投递——调用方（
 * {@link ClientChunkHandler#handleCompressedChunk}）走既有客户端直连链（apply +
 * 本地缓存），剥光仅在握手声明引擎可用后发生。
 * <p>
 * <b>注入失败 = 影子链路整体失败</b>：直接置 {@code shadowServerFailed}（与握手失败 /
 * 引擎创建失败同级的降级态——关闭缓存/OVD/SeedGen + 游戏内提示），不做逐柱兜底。
 * <p>
 * 断连（{@link #onDisconnect}）清空全部状态；影子服务端经
 * {@link ShadowServerRegistry} 统一关停（含持久存档保存）。
 */
public final class ShadowLightCompute {



    /** 投递队列：DimensionKey 复合键 -> 服务端 packet 与仅诊断来源（REPLACE）。 */
    private static final ConcurrentHashMap<Long, PendingEntry> pending =
            new ConcurrentHashMap<>();
    /** 分段增量队列：复合键 -> (dimension, DeltaEntry)。REPLACE 语义：服务端每份 delta
     *  都是「当前服务端状态 vs 客户端基线」的完整差异，后到覆盖先到（内容都正确）。 */
    private static final ConcurrentHashMap<Long, DeltaWork> pendingDeltas =
            new ConcurrentHashMap<>();
    /** 本地生成队列：复合键 -> (chunk, level)（SeedGen worldgen 完成 / 磁盘光脏 relight，打包回传）。 */
    private static final ConcurrentHashMap<Long, GenEntry> generated = new ConcurrentHashMap<>();
    /**
     * 增量算光队列（LightDelta 消费）：复合键 -> 变更掩码。REPLACE/合并不冲突——
     * 服务端 LightDelta 是「哪几个 section 刚变完」的增量信号，同柱后到与先到的
     * 掩码取并集即可（影子端按并集清光重算，最终一致性不变）。
     */
    private static final ConcurrentHashMap<Long, LightWork> pendingLightUpdates =
            new ConcurrentHashMap<>();
    /** 管道在途光屏障：复合键 -> 提交上下文（submitLightBatch 提交，completeLight/超时扫表
     *  条件移除；size = 在途计数，上限 {@link #PIPELINE_MAX_INFLIGHT}，断连清空）。 */
    private static final ConcurrentHashMap<Long, InflightLight> inflightLight = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Long> shadowApplyEpochs = new ConcurrentHashMap<>();
    private static final AtomicLong shadowApplyEpoch = new AtomicLong();

    /**
     * 影子→客户端回传：区块包与光包同一 FIFO（入队序号）+ 同柱同 op REPLACE。
     * 距离优先级只在服务端推送与 {@link io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher} 缓存读取。
     * 出区块包时丢掉该柱尚未落地的旧光，避免「新区块已亮、旧空光后到盖暗」。
     */
    private static final KeyedPriorityQueue<ReadyItem> ready = new KeyedPriorityQueue<>(64);
    private static final AtomicLong applyOfferSeq = new AtomicLong();

    /** 回传队列元素：chunk 包 / light 包二选一（消费侧按非 null 分发）。 */
    private record ReadyItem(ClientboundLevelChunkWithLightPacket chunkPacket,
                             ClientboundLightUpdatePacket lightPacket,
                             boolean renderOnly,
                             TraceOrigin traceOrigin,
                             Long lightQueuedAtMs,
                             Long lightApplyEpoch) {}

    /** 仅诊断用：最近一次已落地全量区块（复合键），供后续光包判定回填时序。 */
    private static final ConcurrentHashMap<Long, FullApplyTrace> fullApplyTraces = new ConcurrentHashMap<>();
    private static final AtomicLong fullApplySequence = new AtomicLong();

    /**
     * 光照更新收集表：DimensionKey 复合键 → LightMask（影子端 light 线程写，客户端主线程读）。
     * 引擎每完成一个 section 的光计算写数据层 → {@code onLightUpdate}（MixinServerChunkCache
     * 拦截，T2）→ {@link #collectLightUpdate} 收集绝对 sectionY；主线程帧尾
     * {@link #drainLightMasks} 攒批打包入回传队列。维度取客户端当前所在维度
     * （LightDelta 协议与 mixin 入口均无维度上下文；影子端只装配三主维度）。
     */
    private static final ConcurrentHashMap<Long, LightMask> lightUpdates = new ConcurrentHashMap<>();

    /**
     * 单 chunk 光照更新掩码：绝对 sectionY 收集。用 TreeSet 而非 BitSet——绝对
     * sectionY 可为负（-64 高度世界），BitSet 负索引抛异常；攒批时按
     * {@code engine.getMinLightSection()} 偏移转 BitSet（mask 位 = sectionY − minLightSection，
     * 与 ClientboundLightUpdatePacketData 语义一致，两版零适配）。
     */
    private static final class LightMask {
        private final java.util.TreeSet<Integer> skySections = new java.util.TreeSet<>();
        private final java.util.TreeSet<Integer> blockSections = new java.util.TreeSet<>();
        /** 最终全量光已回传（或即将回传）：drainLightMasks 持有旧引用时必须跳过构建。 */
        private volatile boolean discarded;
    }

    private static final AtomicBoolean consumeRunning = new AtomicBoolean(false);

    /** 原版 LIGHT future 请求的提交顺序锁；不持有该锁等待 future 或执行打包。 */
    static final Object LIGHT_ENGINE_MUTEX = new Object();


    /** miss 已请求集合（复合键；会话内防抖：直推与请求并存时不重复请求；断连清空）。 */
    private static final java.util.Set<Long> requestedMisses = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * 分母「应用区块」已记账柱。不得与 {@link #requestedMisses} 共用：hash miss 会先
     * {@code tryRequestMiss}，若共用则后续 inject/落地不再记，R1 applied 恒为 0。
     */
    private static final java.util.Set<Long> accountedIngress = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** hash 全命中已记账柱（复合键）。同一柱磁盘命中后再收到 hash 会走内存命中，不得再加一次。 */
    private static final java.util.Set<Long> accountedCacheHits = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 光照命中/重算已记账柱（复合键）。邻柱 LIGHT_ONLY 补光会把同一片柱刷成千上万次。 */
    private static final java.util.Set<Long> accountedLights = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** hash 分流计数（会话累计；断连清零）。冒烟探针用，分辨整柱 miss 是内存漂移还是盘上无槽。 */
    private static final AtomicLong hashMemoryHits = new AtomicLong();
    private static final AtomicLong hashMemoryMismatches = new AtomicLong();
    private static final AtomicLong hashDiskHits = new AtomicLong();
    private static final AtomicLong hashDiskMismatches = new AtomicLong();
    private static final AtomicLong hashAbsents = new AtomicLong();
    private static final AtomicLong hashLeftovers = new AtomicLong();
    /** 哈希分类诊断：按 (维度坐标复合键, 远程 hash) 识别重复元数据与重复分类。 */
    private record HashObservation(long chunkKey, long remoteHash) {
    }

    private static final Set<HashObservation> hashEntriesSeen = ConcurrentHashMap.newKeySet();
    private static final Set<Long> hashChunkKeysSeen = ConcurrentHashMap.newKeySet();
    private static final Set<HashObservation> hashMemoryMismatchEntries = ConcurrentHashMap.newKeySet();
    private static final Set<HashObservation> hashDiskMismatchEntries = ConcurrentHashMap.newKeySet();
    private static final Set<HashObservation> hashLeftoverEntries = ConcurrentHashMap.newKeySet();
    private static final AtomicLong hashEntriesProcessed = new AtomicLong();
    private static final AtomicLong hashEntryDuplicates = new AtomicLong();
    private static final AtomicLong hashMemoryMismatchDuplicates = new AtomicLong();
    private static final AtomicLong hashDiskMismatchDuplicates = new AtomicLong();
    private static final AtomicLong hashLeftoverDuplicates = new AtomicLong();

    private static HashObservation observeHashEntry(long chunkKey, long remoteHash) {
        hashEntriesProcessed.incrementAndGet();
        hashChunkKeysSeen.add(chunkKey);
        HashObservation observation = new HashObservation(chunkKey, remoteHash);
        if (!hashEntriesSeen.add(observation)) {
            hashEntryDuplicates.incrementAndGet();
        }
        return observation;
    }

    private static void observeMemoryMismatch(HashObservation observation) {
        if (!hashMemoryMismatchEntries.add(observation)) {
            hashMemoryMismatchDuplicates.incrementAndGet();
        }
    }

    private static void observeDiskMismatch(HashObservation observation) {
        if (!hashDiskMismatchEntries.add(observation)) {
            hashDiskMismatchDuplicates.incrementAndGet();
        }
    }

    private static void observeLeftover(HashObservation observation) {
        if (!hashLeftoverEntries.add(observation)) {
            hashLeftoverDuplicates.incrementAndGet();
        }
    }

    public static long hashEntriesProcessedCount() {
        return hashEntriesProcessed.get();
    }

    public static long hashEntriesUniqueCount() {
        return hashEntriesSeen.size();
    }

    public static long hashChunkKeysUniqueCount() {
        return hashChunkKeysSeen.size();
    }

    public static long hashEntryDuplicatesCount() {
        return hashEntryDuplicates.get();
    }

    public static long hashMemoryMismatchUniqueCount() {
        return hashMemoryMismatchEntries.size();
    }

    public static long hashMemoryMismatchDuplicatesCount() {
        return hashMemoryMismatchDuplicates.get();
    }

    public static long hashDiskMismatchUniqueCount() {
        return hashDiskMismatchEntries.size();
    }

    public static long hashDiskMismatchDuplicatesCount() {
        return hashDiskMismatchDuplicates.get();
    }

    public static long hashLeftoverUniqueCount() {
        return hashLeftoverEntries.size();
    }

    public static long hashLeftoverUniqueChunkKeysCount() {
        Set<Long> keys = new HashSet<>();
        for (HashObservation observation : hashLeftoverEntries) {
            keys.add(observation.chunkKey());
        }
        return keys.size();
    }

    public static long hashLeftoverDuplicatesCount() {
        return hashLeftoverDuplicates.get();
    }


    /**
     * P1（T7）：注入 chunk section 容器（PalettedContainer）并发锁——hash 比对线程
     * （processRemoteHashes→chunkHashOf / localPullEntry→computeSectionHashes）与
     * consumeLoop 打包线程（pushReady→SeedGenChunkCodec.buildPacket / applySectionDelta）
     * 对同一注入 LevelChunk 的容器并发触碰 → 1.21.11 ThreadingDetector 崩溃（全 miss 触发
     * delta 洪峰时）。按 chunk 粒度互斥：key = 裸 ChunkPos.asLong（刻意维度无关——
     * ShadowSeedServer.withChunkLock(pos) 与本文件必须命中同一把锁；跨维同坐标共享锁
     * 只会多等不会漏互斥）。短临界区（无 IO、无跨 chunk
     * 嵌套锁），HassiumTaskExecutor 虚拟线程池内无死锁风险；不同 chunk 不同 monitor 无争用放大。
     * 静态存活（不随断连清理）：键数 = 会话内触碰 chunk 数（每键 ~40B），可忽略；清空反而
     * 引入新旧 monitor 交错窗口。
     */
    private static final ConcurrentHashMap<Long, Object> chunkLocks = new ConcurrentHashMap<>();

    private static Object chunkLock(ChunkPos pos) {
        return chunkLocks.computeIfAbsent(chunkPosKey(pos), k -> new Object());
    }


    /**
     * 与注入/hash 比对/apply/落盘序列化共用同一把 per-chunk 锁。
     * {@code injectChunk.replaceWithPacketData} 与 {@code ChunkSerializer.pack}
     * 必须互斥，否则 1.20.1 PalettedContainer ThreadingDetector 会崩影子端。
     */
    public static void withChunkLock(ChunkPos pos, Runnable action) {
        synchronized (chunkLock(pos)) {
            action.run();
        }
    }

    public static <T> T withChunkLock(ChunkPos pos, java.util.function.Supplier<T> action) {
        synchronized (chunkLock(pos)) {
            return action.get();
        }
    }

    /**
     * 供 OVD 本地生成/磁盘/注入打包复用同一把 chunk 锁：buildPacket 会读取
     * LevelChunkSection 的 PalettedContainer（write → acquire），若与 hash 比对/
     * 光照引擎更新并发，可能序列化出“计数非空但方块数据被读成空气”的撕裂包。
     * 影子主回传路径（pushReady）已在此锁内打包，OVD 直连生成路径此前未加锁，
     * 是 OVD 应用到客户端后 topBlock=air 的可疑根因。
     */
    public static ClientboundLevelChunkWithLightPacket buildPacketLocked(
            ChunkPos pos, net.minecraft.world.level.chunk.LevelChunk chunk,
            net.minecraft.server.level.ServerLevel level) {
        synchronized (chunkLock(pos)) {
            return SeedGenChunkCodec.buildPacket(chunk, level);
        }
    }

    /**
     * delta 请求超时（毫秒）——P3（T7）：镜像 full 路径自适应（基数 + 每块 + 每在途 + 上限，
     * 同 ClientMetadataHandler.fullRequestTimeoutMs），替换固定 8s——深队/服务端逐 section
     * 比对下 8s 过紧，会误触发回退风暴。服务端始终回包（entries/skipped 可空），丢包/断连竞态兜底。
     */
    private static final long DELTA_REQUEST_TIMEOUT_BASE_MS = 30_000L;
    private static final long DELTA_REQUEST_TIMEOUT_PER_CHUNK_MS = 25L;
    private static final long DELTA_REQUEST_TIMEOUT_PER_INFLIGHT_MS = 10L;
    private static final long DELTA_REQUEST_TIMEOUT_MAX_MS = 120_000L;

    private static long deltaRequestTimeoutMs(int batchSize, int inFlightBefore) {
        long t = DELTA_REQUEST_TIMEOUT_BASE_MS
                + (long) batchSize * DELTA_REQUEST_TIMEOUT_PER_CHUNK_MS
                + (long) inFlightBefore * DELTA_REQUEST_TIMEOUT_PER_INFLIGHT_MS;
        return Math.min(t, DELTA_REQUEST_TIMEOUT_MAX_MS);
    }

    /** P2（T7）：new 路径回退去重登记（SeedGenExecutor 回退链 / delta 失败兜底共用；
     *  与 processRemoteHashes 的 miss 请求同一会话内防抖集合）。true = 首次登记（应发送请求）。
     *  键 = DimensionKey 复合键（跨维同坐标互不防抖）；无维度上下文的调用方走旧签名
     *  （委托 OVERWORLD，与 SeedGen 盲预生成等主世界路径一致）。 */
    public static boolean tryRequestMiss(String dimension, ChunkPos pos) {
        return requestedMisses.add(DimensionKey.key(dimension, pos.x, pos.z));
    }

    /** 旧签名（过渡期兼容）：语义 = OVERWORLD。 */
    public static boolean tryRequestMiss(ChunkPos pos) {
        return tryRequestMiss(DimensionKey.OVERWORLD, pos);
    }

    /**
     * 保活重连的会话边界：保留影子缓存基线，但允许同一柱再次发起 Compare + Pull。
     * 不得调用 {@link #onDisconnect()}，它会同时清空待处理工作和指标去重状态。
     */
    public static void resetRequestDedupForReconnect() {
        requestedMisses.clear();
        accountedIngress.clear();
        accountedCacheHits.clear();
        accountedLights.clear();
    }

    /** 直推已在影子管线里：hash miss 不得再打全量，否则和进服推送抢 4/tick 配额留下虚空。 */
    static boolean isAuthoritativeIngressInFlight(long key) {
        return pending.containsKey(key)
                || generated.containsKey(key)
                || inflightLight.containsKey(key);
    }

    /** P2（T7）：回退请求去重过滤——只保留 requestedMisses 首次登记的 chunk（杜绝同 chunk 重复回退）。 */
    private static List<ChunkPos> dedupeFallback(String dimension, List<ChunkPos> chunks) {
        List<ChunkPos> toRequest = new ArrayList<>(chunks.size());
        for (ChunkPos pos : chunks) {
            if (tryRequestMiss(dimension, pos)) {
                toRequest.add(pos);
            }
        }
        return toRequest;
    }

    /**
     * per-chunk 光屏障超时（毫秒）。对齐原版：一轮 initializeLight + lightChunk 后即首包，
     * 不因层不全/全局收敛挡回传；超时仍 pushReady（标脏），补光走 drainLightMasks。
     */
    private static final long CONVERGENCE_WAIT_TIMEOUT_MS = 5_000L;
    /** 等邻柱完成 INITIALIZE_LIGHT 再 lightChunk 的上限。超时按原版视距边缘处理。 */
    static final long NEIGHBOR_PACK_WAIT_MS = 2_000L;
    /** 每轮消费循环「光屏障提交」总量上限（含 pending/generated/delta/light 全部来源，
     *  不再仅限 pending 批）。两阶段屏障每柱仅 ~3 个引擎任务（initializeLight PRE+POST、
     *  lightChunk PRE+POST 的提交单元），24 柱 ≈ 72 任务，远低于 1000 并发阈值。 */
    private static final int CONSUME_BATCH_LIMIT = 24;
    /**
     * 管道在途光屏障上限。R1 清缓存实测 32 在途 ≈ 50 块/s（VD16 铺满 ~20.5s）；
     * 计划 C 目标 ~15s / ~67 块/s。两阶段每柱约 4 个提交单元，48 柱 ≈ 192 任务；
     * 即便按旧口径 19 任务/柱也约 912，仍低于 vanilla sorter 并发阈值 1000。
     */
    private static final int PIPELINE_MAX_INFLIGHT = 48;
    /** 清光路径（re-inject / relight）按柱排水水位：每柱清光前水位 ≤450，一柱清光后
     *  ≤502；随后整批两阶段首阶段任务 ≤12×35=420，二者叠加恒 < 1000（vanilla sorter
     *  并发 runUpdate 阈值），且比旧值 300 更少触发 5s 忙等。仅由 ShadowSeedServer 的
     *  清光路径使用。 */
    private static final int ENGINE_TASK_LOW_WATER = 450;
    /**
     * 清光会一次性向原版 sorter 投递大量 PRE 任务；在下一柱前把队列压回低水位，
     * 避免超过 vanilla sorter 的并发阈值后出现任务错序与空光层。
     */
    static void awaitEngineTaskDrain(net.minecraft.server.level.ThreadedLevelLightEngine engine) {
        try {
            io.github.limuqy.mc.hassium.mixin.ThreadedLevelLightEngineAccessor acc =
                    (io.github.limuqy.mc.hassium.mixin.ThreadedLevelLightEngineAccessor) engine;
            long deadline = System.currentTimeMillis() + CONVERGENCE_WAIT_TIMEOUT_MS;
            while (acc.hassium$getLightTasks().size() > ENGINE_TASK_LOW_WATER
                    && System.currentTimeMillis() < deadline) {
                try {
                    engine.tryScheduleUpdate();
                } catch (Throwable ignored) {
                    // 引擎关闭/断连竞态：由超时兜底退出。
                }
                LockSupport.parkNanos(200_000L);
            }
        } catch (Throwable ignored) {
            // accessor 或版本差异：跳过水位控制，保留正常光照链路。
        }
    }


    /** 管道低水位：在途低于此值才由完成回调重新 pump（= 1 批：低水位→满水位恰好补一批，
     *  避免每完成一块就一次 executor 往返）。 */
    private static final int PIPELINE_LOW_WATER = CONSUME_BATCH_LIMIT;

    private record DeltaWork(String dimension, io.github.limuqy.mc.hassium.network.SectionDeltaS2CPacket.DeltaEntry entry) {}

    /** LightDelta 变更掩码合并单元（BitSet 由 packet decode 创建，提交后不再被外部修改）。 */
    private record LightWork(BitSet skyMask, BitSet blockMask,
                              BitSet emptySkyMask, BitSet emptyBlockMask) {
        boolean hasSections() {
            return !skyMask.isEmpty() || !blockMask.isEmpty()
                    || !emptySkyMask.isEmpty() || !emptyBlockMask.isEmpty();
        }

        LightWork merged(LightWork other) {
            if (other == null) {
                return this;
            }
            BitSet sky = (BitSet) this.skyMask.clone();
            BitSet block = (BitSet) this.blockMask.clone();
            BitSet emptySky = (BitSet) this.emptySkyMask.clone();
            BitSet emptyBlock = (BitSet) this.emptyBlockMask.clone();
            sky.or(other.skyMask);
            block.or(other.blockMask);
            emptySky.or(other.emptySkyMask);
            emptyBlock.or(other.emptyBlockMask);
            return new LightWork(sky, block, emptySky, emptyBlock);
        }
    }

    private ShadowLightCompute() {}

    /**
     * 影子端暂不可用时的待处理权威区块处置。包内可见以守卫登录时序回归；
     * {@code false} 仅代表可恢复未就绪，不能与不可恢复失败混同。
     */
    static boolean shouldRetainPendingWhenServerUnavailable(boolean isShadowServerFailed) {
        return !isShadowServerFailed;
    }

    /**
     * gameDir/serverId 已记录时唤醒先前因世界根尚不可决议而退出的消费者。
     * 创建仍在后台消费任务内发生，避免在客户端线程阻塞。
     */
    public static void onCacheLocationReady() {
        if (shouldPumpAfterShadowServerReady(hasPendingWork())) {
            pump();
        }
    }

    /** 影子端创建完成时唤醒此前保留的待处理工作。 */
    static void onShadowServerReady() {
        if (shouldPumpAfterShadowServerReady(hasPendingWork())) {
            pump();
        }
    }

    /** 影子端 ready 后必须重新 pump 保留的权威工作。 */
    static boolean shouldPumpAfterShadowServerReady(boolean hasPendingWork) {
        return hasPendingWork;
    }

    /**
     * 原版 {@code lightChunk(chunk, hasLight)} 的第二参：仅 {@link LightMetric#REUSE_CACHE}
     * （引擎内已有可用光）才跳过 {@code propagateLightSources}。
     * 不得单凭 {@code isLightCorrect()} 跳过——标志为真但层未装好时会打出空光包，
     * 盖掉客户端已自算的亮光（先亮后暗）。
     */
    static boolean lightChunkHasExistingLight(boolean reuseCache) {
        return reuseCache;
    }

    /**
     * 客户端已落地过影子全量包时，相同方块不得再整柱重推。
     */
    static boolean shouldSkipRedundantFullPush(boolean alreadyShadowApplied) {
        return alreadyShadowApplied;
    }

    /**
     * 远程 hash 已知且相同，或无远程 hash 时，已落地柱无需重复整柱推送。
     */
    static boolean shouldSkipUnchangedRepush(boolean alreadyShadowApplied,
                                             boolean remoteHashPresent, boolean hashMatches,
                                             boolean lightComplete) {
        return alreadyShadowApplied && lightComplete
                && (!remoteHashPresent || hashMatches);
    }

    /** 在途屏障被新的整柱方块或同柱光增量取代。 */
    static boolean isSupersededByNewerWork(boolean fullChunkTask, boolean hasBlockWork,
                                           boolean hasLightDelta) {
        return hasBlockWork || (!fullChunkTask && hasLightDelta);
    }

    static boolean canStartLightDeltaNow(boolean chunkBarrierBusy) {
        return !chunkBarrierBusy;
    }

    private static boolean isChunkBarrierBusy(long key) {
        return inflightLight.containsKey(key)
                || pending.containsKey(key)
                || generated.containsKey(key)
                || pendingDeltas.containsKey(key);
    }

    private static boolean hasQueuedBlockWork(long key) {
        return pending.containsKey(key) || generated.containsKey(key);
    }

    private static boolean isSuperseded(LightTask t) {
        return t != null && isSupersededByNewerWork(
                t.source != LightSource.LIGHT_ONLY,
                hasQueuedBlockWork(t.key),
                pendingLightUpdates.containsKey(t.key));
    }

    /** 现在就能开屏障的投递。 */
    private static boolean hasStartablePendingWork() {
        if (!pending.isEmpty() || !pendingDeltas.isEmpty() || !generated.isEmpty()) {
            return true;
        }
        for (Long key : pendingLightUpdates.keySet()) {
            if (canStartLightDeltaNow(isChunkBarrierBusy(key))) {
                return true;
            }
        }
        return false;
    }

    /** 服务端直推注入：同一柱的 hash miss 只记一次。 */
    static boolean shouldAccountServerPushAsApplied(boolean alreadyRequested) {
        return !alreadyRequested;
    }

    /** 可见柱实际落到 ClientChunkCache 后的全量来源记账；光照另在光屏障提交时记。 */
    static void accountVisibleNetworkIngress(String dimension, ChunkPos pos) {
        accountVisibleNetworkIngress(dimension, pos, false);
    }

    /**
     * 网络直推柱落地记账（新增 / 过期二选一分类）。
     *
     * @param staleOrFallback true = 影子副本 hash 与远端不一致的重推（旧口径「过期」，
     *                        {@code recordFullChunkRequests(stale=true)} 分类），false = 全新柱
     */
    static void accountVisibleNetworkIngress(String dimension, ChunkPos pos, boolean staleOrFallback) {
        if (pos == null) {
            return;
        }
        String resolved = dimension == null ? currentDimension() : dimension;
        long key = DimensionKey.key(resolved, pos.x, pos.z);
        if (!accountedIngress.add(key)) {
            return;
        }
        requestedMisses.add(key);
        io.github.limuqy.mc.hassium.metrics.NetworkStats.recordFullChunkRequests(
                1, io.github.limuqy.mc.hassium.metrics.NetworkStats.ESTIMATED_CHUNK_BYTES, staleOrFallback);
    }

    /** 客户端实际落地的缓存全量柱按键去重，磁盘与内存复用不得重复记账。 */
    public static boolean accountCacheFullHit(String dimension, ChunkPos pos) {
        if (pos == null) {
            return false;
        }
        String resolved = dimension == null ? currentDimension() : dimension;
        long key = DimensionKey.key(resolved, pos.x, pos.z);
        if (!accountedCacheHits.add(key)) {
            return false;
        }
        io.github.limuqy.mc.hassium.metrics.NetworkStats.recordCacheLoadEligible(
                io.github.limuqy.mc.hassium.metrics.NetworkStats.ESTIMATED_CHUNK_BYTES);
        io.github.limuqy.mc.hassium.metrics.NetworkStats.recordCacheFullHit(
                io.github.limuqy.mc.hassium.metrics.NetworkStats.ESTIMATED_CHUNK_BYTES);
        return true;
    }

    /**
     * Publishes a server-confirmed local baseline through the same shadow light and vanilla apply
     * path as remote data. A confirmed hash without a materializable chunk is not a hit and lets
     * the caller retry an unconditional FULL.
     */
    public static boolean publishCachedChunk(String dimension, ChunkPos pos) {
        if (pos == null || !isEnabled()) {
            return false;
        }
        String resolved = dimension == null ? currentDimension() : dimension;
        ShadowSeedServer server = ShadowServerRegistry.getInstance().getOrCreate();
        if (server == null) {
            return false;
        }
        net.minecraft.server.level.ServerLevel level = server.level(resolved);
        if (level == null) {
            return false;
        }
        net.minecraft.world.level.chunk.LevelChunk chunk = server.injectedChunk(resolved, pos.x, pos.z);
        TraceOrigin origin = TraceOrigin.SHADOW_MEMORY_CACHE;
        if (chunk == null) {
            chunk = server.loadFromDisk(resolved, pos);
            origin = TraceOrigin.SHADOW_DISK_CACHE;
            if (chunk != null) {
                server.injectLoadedChunk(resolved, pos, chunk);
            }
        }
        if (chunk == null) {
            return false;
        }
        return submitPreLight(ShadowChunkSource.CACHE_SNAPSHOT, pos, chunk, level,
                traceOrigin(origin));
    }

    public static long hashMemoryHitCount() {
        return hashMemoryHits.get();
    }

    public static long hashMemoryMismatchCount() {
        return hashMemoryMismatches.get();
    }

    public static long hashDiskHitCount() {
        return hashDiskHits.get();
    }

    public static long hashDiskMismatchCount() {
        return hashDiskMismatches.get();
    }

    public static long hashAbsentCount() {
        return hashAbsents.get();
    }

    public static long hashLeftoverCount() {
        return hashLeftovers.get();
    }

    public static void resetHashClassify() {
        hashMemoryHits.set(0);
        hashMemoryMismatches.set(0);
        hashDiskHits.set(0);
        hashDiskMismatches.set(0);
        hashAbsents.set(0);
        hashLeftovers.set(0);
        hashEntriesProcessed.set(0);
        hashEntryDuplicates.set(0);
        hashMemoryMismatchDuplicates.set(0);
        hashDiskMismatchDuplicates.set(0);
        hashLeftoverDuplicates.set(0);
        hashEntriesSeen.clear();
        hashChunkKeysSeen.clear();
        hashMemoryMismatchEntries.clear();
        hashDiskMismatchEntries.clear();
        hashLeftoverEntries.clear();
    }


    /**
     * 权威柱真正落到 ClientChunkCache 后按来源记账；在影子端注入或收到 packet 时不记。
     * 全量来源按键去重，避免同一柱的重传、磁盘与内存复用重复计数。
     * <p>
     * 分段增量已在 consumeLoop 记 {@code cacheDeltaCount}，落地不得再记全量 miss。
     * {@code origin == null}（应用日志关闭剥离诊断源）不推断来源，保守不计。
     */
    static void accountAuthoritativeLanded(String dimension, ChunkPos pos, TraceOrigin origin) {
        if (pos == null) {
            return;
        }
        if (origin == TraceOrigin.SHADOW_MEMORY_CACHE || origin == TraceOrigin.SHADOW_DISK_CACHE) {
            accountCacheFullHit(dimension, pos);
            return;
        }
        if (origin == TraceOrigin.SECTION_DELTA || origin == null) {
            return;
        }
        accountVisibleNetworkIngress(dimension, pos);
    }

    /**
     * 光照缓存按柱去重：一柱一次命中或一次重算。{@code reuse=true} 仅可在
     * 引擎已有已应用的光（{@code isLightCorrect} 且 {@code lightChunk(..., true)}）时传入；
     * 有缓存区块但仍需传播的路径必须记重算。邻柱 LIGHT_ONLY 补光不走这里。
     */
    static boolean accountLightColumn(String dimension, ChunkPos pos, boolean reuse) {
        if (pos == null) {
            return false;
        }
        String resolved = dimension == null ? currentDimension() : dimension;
        long key = DimensionKey.key(resolved, pos.x, pos.z);
        if (!accountedLights.add(key)) {
            return false;
        }
        if (reuse) {
            io.github.limuqy.mc.hassium.metrics.NetworkStats.recordLightReuseShadow(
                    io.github.limuqy.mc.hassium.metrics.NetworkStats.ESTIMATED_LIGHT_BYTES);
        } else {
            io.github.limuqy.mc.hassium.metrics.NetworkStats.recordLightCacheMiss(
                    io.github.limuqy.mc.hassium.metrics.NetworkStats.ESTIMATED_LIGHT_BYTES);
        }
        return true;
    }

    /** 邻柱 / LightDelta 的 LIGHT_ONLY 不是「这一柱的光照缓存」事件。 */
    static boolean shouldAccountLightBarrierMetric(boolean lightOnly) {
        return !lightOnly;
    }


    /**
     * JoinBoost：队列里还有 chunk 时本帧不落地光包（reoffer 到本帧 chunk 过完）。
     * 非 JoinBoost：保持 FIFO，光包可与 chunk 交错。
     */
    static boolean shouldApplyLightThisFrame(boolean joinBoost, boolean chunkWaiting,
                                             int chunksAppliedThisFrame) {
        if (!joinBoost) {
            return true;
        }
        if (chunkWaiting) {
            return false;
        }
        // 本帧 chunk 已过（含 0 个 chunk 的帧）：剩余预算可落地光
        return chunksAppliedThisFrame >= 0;
    }

    /**
     * 光桥本帧是否再打包一条。JoinBoost：有 chunk 在等则不打包；否则最多 1 条，
     * 且仅在 deadline 未到时（本帧已 apply 过 chunk，或本帧队列无 chunk 的补光帧）。
     * 非 JoinBoost：与既有 drainLightMasks 一致，第一条不受 deadline 约束。
     */
    static boolean shouldPackLightMaskThisFrame(boolean joinBoost, boolean chunkWaiting,
                                                int chunksAppliedThisFrame, boolean deadlineHit,
                                                int packedThisFrame) {
        if (joinBoost) {
            if (chunkWaiting || deadlineHit || packedThisFrame >= 1) {
                return false;
            }
            // 已 apply 过 chunk，或本帧没有 chunk 可 apply（光桥补光）
            return chunksAppliedThisFrame > 0 || packedThisFrame == 0;
        }
        return packedThisFrame == 0 || !deadlineHit;
    }


    private static boolean alreadyShadowApplied(long key) {
        return shouldSkipRedundantFullPush(shadowApplyEpochs.containsKey(key));
    }

    /**
     * 影子回传入队序号：数值越小越先 apply。区块/光包均 FIFO，不按玩家距离。
     */
    static double fifoApplyPriority() {
        return applyOfferSeq.getAndIncrement();
    }


    /**
     * 磁盘命中后是否还需 LIGHT 续算：只看原版 {@code isLightCorrect()}
     * （NBT {@code isLightOn}）。未点亮则 {@code lightChunk(false)} 播种+传播；
     * 内容是否过期由 hash 表决定，不另维护光照脏表。
     */
    static boolean diskNeedRelight(boolean lightCorrect) {
        return !lightCorrect;
    }

    /**
     * R2 磁盘 hash 命中是否必须重算：只看 NBT {@code isLightOn} 与光脏位。
     * 不得用引擎层是否已安装作判据——{@code ChunkSerializer.read} 的
     * {@code queueSectionData} 仍在光线程，立刻查层会把已落盘的屋檐光判成缺光，
     * 再 {@code lightChunk(false)} 丢掉邻柱入流。
     */
    static boolean diskHitNeedRelight(boolean lightDirty, boolean lightCorrect) {
        return lightDirty || diskNeedRelight(lightCorrect);
    }


    private static boolean hasPendingWork() {
        return !pending.isEmpty() || !pendingDeltas.isEmpty() || !generated.isEmpty()
                || !pendingLightUpdates.isEmpty();
    }

    /** 影子链路可用：引擎开启且影子端未失败；不再依赖旧 NetworkCore 握手。 */
    public static boolean isEnabled() {
        return HassiumConfigService.getInstance().isHassiumEngineEnabled()
                && !ClientChunkPipeline.getInstance().isShadowServerFailed();
    }

    /**
     * 登录初始化入口：单端点原版会话没有旧 gateway 握手，影子端按配置常驻至断连 park。
     */
    public static void onLogin() {
        startShadowSpeculative();
    }

    /**
     * 配置就绪即后台 getOrCreate；单端点区块流以第一个原版 FULL 柱确认会话，
     * 不再因不存在的旧网关握手销毁影子端。
     */
    public static void startShadowSpeculative() {
        if (!HassiumConfigService.getInstance().isHassiumEngineEnabled()) {
            return;
        }
        HassiumTaskExecutor executor = HassiumTaskExecutor.getClient();
        if (executor == null || !executor.isRunning()) {
            return;
        }
        executor.submit(ShadowServerRegistry.getInstance()::getOrCreate, TaskCategory.BEST_EFFORT);
    }

    /** 分段增量门控：配置开启 && 影子链路可用。 */
    private static boolean deltaEnabled() {
        return io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance().isSectionDeltaEnabled()
                && isEnabled();
    }

    /** 构造统一 ShadowPull 的本地基线；影子端未就绪时仅携带 chunkHash。 */
    public static io.github.limuqy.mc.hassium.network.ShadowPullRequestC2SPacket.Entry localPullEntry(
            String dimension, ChunkPos pos) {
        Long localHash = io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.get(dimension, pos);
        ShadowSeedServer shadow = ShadowServerRegistry.getInstance().get();
        net.minecraft.world.level.chunk.LevelChunk chunk = shadow == null ? null
                : shadow.injectedChunk(dimension, pos.x, pos.z);
        if (localHash == null && chunk != null) {
            synchronized (chunkLock(pos)) {
                localHash = io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil
                        .combineSectionHashes(io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil
                                .computeSectionHashes(chunk));
                io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.put(dimension, pos, localHash);
            }
        }
        if (localHash == null) {
            return new io.github.limuqy.mc.hassium.network.ShadowPullRequestC2SPacket.Entry(
                    pos.x, pos.z, 0L, List.of(), 0);
        }
        if (chunk == null) {
            return new io.github.limuqy.mc.hassium.network.ShadowPullRequestC2SPacket.Entry(
                    pos.x, pos.z, localHash, List.of(), 0);
        }
        synchronized (chunkLock(pos)) {
            io.github.limuqy.mc.hassium.network.sectiondelta.SectionDeltaSnapshot snapshot =
                    io.github.limuqy.mc.hassium.network.sectiondelta.SectionDeltaSnapshots
                            .getOrCapture(dimension, pos, chunk);
            long[] hashes = snapshot.sectionHashes();
            List<Long> sectionHashes = new ArrayList<>(hashes.length);
            for (long hash : hashes) {
                sectionHashes.add(hash);
            }
            return new io.github.limuqy.mc.hassium.network.ShadowPullRequestC2SPacket.Entry(
                    pos.x, pos.z, localHash, sectionHashes, snapshot.planes(), 0);
        }
    }

    /** 已登记 hash 或驻留影子柱均可作为统一比较拉取的本地基线。 */
    public static boolean hasLocalPullBaseline(String dimension, ChunkPos pos) {
        if (dimension == null || pos == null) {
            return false;
        }
        if (io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.get(dimension, pos) != null) {
            return true;
        }
        ShadowSeedServer shadow = ShadowServerRegistry.getInstance().get();
        return shadow != null && shadow.injectedChunk(dimension, pos.x, pos.z) != null;
    }

    /**
     * 接收分段增量响应（任意线程：Netty / DataPlane 事件循环）：entries 入
     * consumeLoop 应用（applySectionDelta + 清变更 section 光 + 打包回传）；
     * skipped 立即回退全量（服务端视距外/退化保护/异常）。
     */
    public static void submitDelta(io.github.limuqy.mc.hassium.network.SectionDeltaS2CPacket packet) {
        if (packet == null || !isEnabled()) {
            return;
        }
        DebugLogger.info(DebugLogger.LogType.ASYNC,
                "[SHADOW_DELTA] Received {} delta entries (dimension={})", packet.entries().size(), packet.dimension());
        String dimension = packet.dimension();
        for (var entry : packet.entries()) {
            long key = DimensionKey.key(dimension, entry.chunkX(), entry.chunkZ());
            pendingDeltas.put(key, new DeltaWork(dimension, entry));
        }
        // 全量等价流量不在此处记：apply 失败会回退全量，收到即记会在「delta + 回退全量」
        // 场景把同一区块计两次。成功应用后由 consumeLoop 记 recordSectionDeltaReceived。
        if (!packet.skipped().isEmpty()) {
            List<net.minecraft.world.level.ChunkPos> skipped = new ArrayList<>(packet.skipped().size());
            for (var s : packet.skipped()) {
                long key = DimensionKey.key(dimension, s.chunkX(), s.chunkZ());
                skipped.add(new net.minecraft.world.level.ChunkPos(s.chunkX(), s.chunkZ()));
            }
            DebugLogger.info(DebugLogger.LogType.ASYNC,
                    "[SHADOW_DELTA] {} chunks skipped by server, fallback full", skipped.size());
            List<net.minecraft.world.level.ChunkPos> fallback = dedupeFallback(dimension, skipped);
            io.github.limuqy.mc.hassium.network.ShadowPullClient.requestAuthoritativeFull(dimension, fallback);
        }
        pump();
    }

    /**
     * 增量算光入口（LightDeltaS2CPacket 消费，任意线程：网关 Netty / 数据面事件循环）。
     * <p>
     * 服务端把官方 {@code ClientboundLightUpdatePacket} 剥成掩码后推送；影子端把对应
     * section（含 empty 掩码 = 变全空的 section）的光清掉 → 重算收敛 → 以官方
     * {@code ClientboundLightUpdatePacket}（全柱光，见 {@link #pushLightReady}）回传。
     * 同柱掩码合并（REPLACE 并集）：LightDelta 是逐 tick 的增量信号，并集重算不丢信息。
     * 目标柱未注入时不丢弃：标脏由 consumeLoop 的失败分支完成，R2 读盘命中会走 relight 链。
     */
    public static void submitLightDelta(io.github.limuqy.mc.hassium.network.LightDeltaS2CPacket packet) {
        if (packet == null || packet.entries().isEmpty() || !isEnabled()) {
            return;
        }
        int queued = 0;
        for (io.github.limuqy.mc.hassium.network.LightDeltaS2CPacket.Entry entry : packet.entries()) {
            if (entry == null || !entry.hasAnySection()) {
                continue;
            }
            // LightDelta 协议无 dimension 字段（REQ 明细8 核对）：以客户端当前维度作键上下文
            // （影子端只装配三主维度，玩家所在维度即数据归属维度）。
            long key = DimensionKey.key(currentDimension(), entry.chunkX(), entry.chunkZ());
            LightWork work = new LightWork((BitSet) entry.skyYMask().clone(),
                    (BitSet) entry.blockYMask().clone(),
                    (BitSet) entry.emptySkyYMask().clone(),
                    (BitSet) entry.emptyBlockYMask().clone());
            pendingLightUpdates.merge(key, work, LightWork::merged);
            queued++;
        }
        if (queued > 0) {
            DebugLogger.info(DebugLogger.LogType.ASYNC,
                    "[SHADOW_LIGHT] LightDelta queued {} chunks for shadow relight", queued);
            pump();
        }
    }


    /** 客户端当前维度 id（{@code namespace:path}；mc.level 不可用回退 OVERWORLD）。 */
    static String currentDimension() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.level != null) {
                return LevelCompat.getDimensionId(mc.level);
            }
        } catch (Throwable ignored) {
        }
        return DimensionKey.OVERWORLD;
    }

    /** 内存区块 hash：ShadowStorageHashes 表优先（注入/读盘已登记），无表现算。 */
    private static boolean chunkHashOf(String dimension, LevelChunk chunk, ChunkPos pos, long remoteHash) {
        return diskHashMatches(dimension, chunk, pos, remoteHash);
    }

    /** 投递一个远程区块（任意线程；启用态 gate）。同柱 REPLACE 覆盖旧数据。 */
    public static void submit(ChunkPos pos, ClientboundLevelChunkWithLightPacket packet) {
        if (pos == null || packet == null || !isEnabled()) {
            return;
        }
        String dimension = currentDimension();
        if (!DimensionKey.isCacheableDimension(dimension)) {
            applyVanillaDirect(pos, packet);
            return;
        }
        long key = DimensionKey.key(dimension, pos.x, pos.z);
        SmokeChunkTrace.recordNetworkReceived(dimension, pos);
        pending.put(key, new PendingEntry(packet, traceOrigin(TraceOrigin.SERVER_PUSH)));
        pump();
    }
    /** 投递可渲染柱；区块追踪与邻域由影子端原版 ChunkMap 管理。 */
    public static void submitVisible(String dimension, ChunkPos pos,
                                     ClientboundLevelChunkWithLightPacket packet) {
        if (pos == null || packet == null || !isEnabled()) {
            return;
        }
        String activeDimension = currentDimension();
        ShadowSeedServer server = ShadowServerRegistry.getInstance().get();
        if (server != null) {
            server.setPersistenceRole(activeDimension, pos, ShadowChunkPersistenceRole.VISIBLE_FULL_LIGHT);
        }
        long key = DimensionKey.key(activeDimension, pos.x, pos.z);
        SmokeChunkTrace.recordNetworkReceived(activeDimension, pos);
        pending.put(key, new PendingEntry(packet, traceOrigin(TraceOrigin.SERVER_PUSH)));
        pump();
    }


    /**
     * 注入后入官方光屏障。剥光柱 persisted=FULL 且 {@code isLightCorrect=false}，
     * native {@code getChunkFuture(FULL)} 只 {@code load} 不 {@code generate} LIGHT，
     * 不能当发布门控。
     */
    static void enqueueInjectedForLight(String dimension, ChunkPos pos, TraceOrigin origin) {
        if (pos == null || !isEnabled()) {
            return;
        }
        String resolved = dimension == null ? currentDimension() : dimension;
        long key = DimensionKey.key(resolved, pos.x, pos.z);
        // Vanilla ChunkMap owns admission; no halo role is tracked here.
        ShadowSeedServer server = ShadowServerRegistry.getInstance().get();
        if (server == null) {
            return;
        }
        LevelChunk chunk = server.injectedChunk(resolved, pos.x, pos.z);
        net.minecraft.server.level.ServerLevel level = server.level(resolved);
        if (chunk == null || level == null) {
            return;
        }
        generated.put(key, new GenEntry(chunk, level, false, false,
                origin == null ? TraceOrigin.SERVER_PUSH : origin));
        pump();
    }

    /**
     * 原版 LIGHT 步 {@code isLighted = persisted >= LIGHT && isLightCorrect}。
     * 注入柱 persisted 恒为 FULL，剥光包 isLightCorrect=false，native FULL 不能当已算光。
     */
    static boolean nativeFullMeansLighted(boolean persistedAtLeastLight, boolean lightCorrect) {
        return persistedAtLeastLight && lightCorrect;
    }


    /** 原版 ChunkHolder LIGHT future 完成后的可见发布入口。 */
    static void publishNativeLightResult(String dimension, ChunkPos pos, LevelChunk chunk,
                                         net.minecraft.server.level.ServerLevel level,
                                         TraceOrigin origin) {
        try {
            runBuildOnShadowMain(pos, () -> {
                ClientboundLevelChunkWithLightPacket packet;
                synchronized (chunkLock(pos)) {
                    packet = SeedGenChunkCodec.buildPacket(chunk, level);
                }
                offerReady(DimensionKey.key(dimension, pos.x, pos.z), pos, packet,
                        true, false, origin);
            });
        } catch (Throwable failure) {
            ShadowServerRegistry.getInstance().failShadowServer();
        }
    }


    /**
     * 自定义维度透传：绕过影子管线，主线程直接原版落地（与 ClientChunkHandler
     * 原版直发路径同语义）。CompressedChunkData 无维度字段，submit 只能以客户端
     * 当前维度判定；非缓存维度在此恢复原版行为。
     */
    private static void applyVanillaDirect(ChunkPos pos,
                                           ClientboundLevelChunkWithLightPacket packet) {
        try {
            Minecraft mc = Minecraft.getInstance();
            ClientPacketListener connection = mc != null ? mc.getConnection() : null;
            if (connection == null || packet == null) {
                return;
            }
            final ClientboundLevelChunkWithLightPacket fPacket = packet;
            final ChunkPos fPos = pos;
            io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher.execute(() -> {
                try {
                    connection.handleLevelChunkWithLight(fPacket);
                } catch (Throwable t) {
                    DebugLogger.warn(DebugLogger.LogType.CHUNK_APPLY,
                            "[SHADOW_CHUNK] vanilla-direct apply failed ({}, {})", fPos.x, fPos.z);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /**
     * 投递一个本地生成区块（SeedGen worldgen 完成；任意线程）。区块已在影子端，
     * 无需注入——引擎传播算光（原版生成后算光同款）后打包官方包回传；欠光由
     * 光照更新桥梁补发。
     *
     * @return true=已入队待回传；false=引擎不可用（并发降级），调用方须回退全量
     */
    public static boolean submitGenerated(ChunkPos pos,
                                          net.minecraft.world.level.chunk.LevelChunk chunk,
                                          net.minecraft.server.level.ServerLevel level) {
        return submitGenerated(pos, chunk, level, false);
    }

    /** 提交已物化区块；cacheServed=true 时保留缓存来源，供落地指标正确记账。 */
    public static boolean submitGenerated(ChunkPos pos,
                                          net.minecraft.world.level.chunk.LevelChunk chunk,
                                          net.minecraft.server.level.ServerLevel level,
                                          boolean cacheServed) {
        ShadowChunkSource source = cacheServed ? ShadowChunkSource.CACHE_SNAPSHOT : ShadowChunkSource.SEEDGEN;
        TraceOrigin origin = cacheServed ? TraceOrigin.SHADOW_MEMORY_CACHE : TraceOrigin.LOCAL_GENERATION;
        return submitPreLight(source, pos, chunk, level, traceOrigin(origin));
    }

    /**
     * 投递本地 materialized 区块到统一 pre-LIGHT 入口。
     *
     * @param source 必须是 CACHE_SNAPSHOT 或 SEEDGEN；packet-backed 来源走
     *               {@link ShadowSeedServer#injectPreLight}。
     */
    public static boolean submitPreLight(ShadowChunkSource source,
                                         ChunkPos pos,
                                         net.minecraft.world.level.chunk.LevelChunk chunk,
                                         net.minecraft.server.level.ServerLevel level,
                                         TraceOrigin traceOrigin) {
        if (source == null || !source.isLocalChunk() || pos == null || chunk == null || !isEnabled()) {
            return false;
        }
        String dimension = level != null ? ShadowSeedServer.dimensionId(level) : null;
        if (dimension == null || !DimensionKey.isCacheableDimension(dimension)) {
            return false;
        }
        generated.put(DimensionKey.key(dimension, pos.x, pos.z),
                new GenEntry(chunk, level, false, false, traceOrigin));
        pump();
        return true;
    }



    /** 触发消费循环（CAS 防并发；已失败/未握手时静默）。 */
    private static void pump() {
        if (!consumeRunning.compareAndSet(false, true)) {
            return;
        }
        HassiumTaskExecutor executor = HassiumTaskExecutor.getClient();
        if (executor == null || !executor.isRunning()) {
            consumeRunning.set(false);
            return;
        }
        try {
            executor.submit(ShadowLightCompute::consumeLoop, TaskCategory.BEST_EFFORT);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            consumeRunning.set(false); // 池已停（断连竞态），队列由 onDisconnect 清空
        }
    }

    /**
     * 后台消费循环（管道化）：取批（≤{@link #CONSUME_BATCH_LIMIT}，受在途余量约束）→

     * 注入/应用/收集 → 提交 per-chunk 两阶段光屏障（{@link #submitLightBatch}，无等待）→
     * 立即回循环取下一批（批间零空转，不再 allOf 全等）。提交即从投递队列移除
     * （管道化前提，条件移除 = REPLACE 守卫）；在途（已提交未完成）上限
     * {@link #PIPELINE_MAX_INFLIGHT}，达上限退出等待，由完成回调在低水位重新
     * {@link #pump()}（连续灌入）。per-chunk 完成回调独立回传
     * （{@link #completeLight} → {@link #finishLight}）。
     */
    private static void consumeLoop() {
        boolean isWaitingForShadowServer = false;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                ShadowServerRegistry registry = ShadowServerRegistry.getInstance();
                ShadowSeedServer server = registry.getOrCreate();
                if (server == null) {
                    if (shouldRetainPendingWhenServerUnavailable(registry.isFailed())) {
                        // gameDir 尚未记录等暂态：保留所有队列；由 cache-location/ready 事件唤醒。
                        isWaitingForShadowServer = true;
                        return;
                    }
                    // 不可恢复创建失败：维持既有全会话降级清理语义。
                    pending.clear();
                    pendingDeltas.clear();
                    generated.clear();
                    pendingLightUpdates.clear();
                    inflightLight.clear();
                    return;
                }
                sweepLightTimeouts(); // 超时兜底第二扫描点（主线程帧尾为主，低帧率兜底）
                int inFlight = inflightLight.size();
                if (inFlight >= PIPELINE_MAX_INFLIGHT) {
                    break; // 管道已满：等完成回调释放容量（低于低水位时重新 pump）
                }
                int room = PIPELINE_MAX_INFLIGHT - inFlight;
                List<Map.Entry<Long, PendingEntry>> batch =
                        new ArrayList<>(Math.min(CONSUME_BATCH_LIMIT, room));
                for (Map.Entry<Long, PendingEntry> e : pending.entrySet()) {
                    if (batch.size() >= CONSUME_BATCH_LIMIT || batch.size() >= room) {
                        break;
                    }
                    io.github.limuqy.mc.hassium.utils.ChunkFlowTiming.recordConsume(e.getKey());
                    batch.add(e);
                }
                // 本轮提交总量（pending+gen+delta+light）也以 CONSUME_BATCH_LIMIT 封顶：
                // 防止 gen/delta/light 把单轮任务量叠回 1000 阈值。
                int remaining = Math.min(room, CONSUME_BATCH_LIMIT) - batch.size();
                List<Map.Entry<Long, GenEntry>> genBatch = new ArrayList<>();
                for (Map.Entry<Long, GenEntry> e : generated.entrySet()) {
                    if (genBatch.size() >= remaining) {
                        break;
                    }
                    genBatch.add(e);
                }
                remaining -= genBatch.size();
                List<Map.Entry<Long, DeltaWork>> deltaBatch = new ArrayList<>();
                for (Map.Entry<Long, DeltaWork> e : pendingDeltas.entrySet()) {
                    if (deltaBatch.size() >= remaining) {
                        break;
                    }
                    deltaBatch.add(e);
                }
                remaining -= deltaBatch.size();
                List<Map.Entry<Long, LightWork>> lightBatch = new ArrayList<>();
                for (Map.Entry<Long, LightWork> e : pendingLightUpdates.entrySet()) {
                    if (lightBatch.size() >= remaining) {
                        break;
                    }
                    if (!canStartLightDeltaNow(isChunkBarrierBusy(e.getKey()))) {
                        continue;
                    }
                    lightBatch.add(e);
                }
                if (batch.isEmpty() && genBatch.isEmpty() && deltaBatch.isEmpty() && lightBatch.isEmpty()) {
                    return; // 全部消费完（在途光屏障由完成回调独立回传）
                }
                org.slf4j.LoggerFactory.getLogger("Hassium/ShadowDisk")
                        .debug("consumeLoop batch={} gen={} delta={} light={}",
                                batch.size(), genBatch.size(), deltaBatch.size(), lightBatch.size());
                List<LightTask> lightTasks = new ArrayList<>();
                for (Map.Entry<Long, PendingEntry> e : batch) {
                    // 复合键解维：pending 键携带维度，服务端查询/引擎操作全部路由到该维度。
                    String dimension = DimensionKey.dimensionOf(e.getKey());
                    ChunkPos pos = new ChunkPos(DimensionKey.chunkXOf(e.getKey()),
                            DimensionKey.chunkZOf(e.getKey()));
                    PendingEntry pendingEntry = e.getValue();
                    long remoteHash = io.github.limuqy.mc.hassium.network.ClientChunkPipeline
                            .getInstance().peekPendingContentHash(dimension, pos.x, pos.z);
                    // 影子内存已有该柱：禁止 injectChunk REPLACE（clearChunkLight 会
                    // 清掉邻柱推进来的屋檐光）。Bloom 直推没有 remoteHash，已落地则直接丢掉。
                    boolean staleRepush = false;
                    LevelChunk existing = server.injectedChunk(dimension, pos.x, pos.z);
                    if (existing != null) {
                        boolean hashKnown = remoteHash != 0L;
                        boolean hashMatches = false;
                        if (hashKnown) {
                            synchronized (chunkLock(pos)) {
                                hashMatches = diskHashMatches(dimension, existing, pos, remoteHash);
                            }
                        }
                    if (!hashKnown || hashMatches) {
                            boolean needRelight = !existing.isLightCorrect();
                            if (!pending.remove(e.getKey(), pendingEntry)) {
                                continue;
                            }
                            if (shouldSkipUnchangedRepush(
                                    alreadyShadowApplied(e.getKey()), hashKnown, hashMatches,
                                    !needRelight)) {
                                DebugLogger.info(DebugLogger.LogType.CHUNK_APPLY,
                                        "[SHADOW_CHUNK] Skip redundant full push ({}, {}): client already applied hashKnown={}",
                                        pos.x, pos.z, hashKnown);
                                continue;
                            }
                            SmokeChunkTrace.recordShadowInjected(dimension, pos);
                            generated.put(e.getKey(), new GenEntry(existing, server.level(dimension), !needRelight,
                                    false, traceOrigin(TraceOrigin.SHADOW_MEMORY_CACHE)));
                            // 已有内存柱且 hash 未知/一致：复用现有柱，只把它送入光照阶段。
                            // 必须跳过下面的 injectChunk；REPLACE 会清空刚由邻柱传播来的光。
                            continue;
                        }
                        // hash 已知且不匹配：影子副本过期，覆盖注入（走下方 injectChunk）。
                        staleRepush = true;
                    }
                    // R1 全量直推：禁 loadFromDisk。内存未命中则注入网络包。
                    if (!server.injectChunk(dimension, pos, pendingEntry.packet())) {
                        // 注入失败 = 影子链路整体失败：走与握手失败/创建失败同级的
                        // 关闭核心逻辑（shadowServerFailed → 缓存/OVD/SeedGen 关闭 + 提示）。
                        pending.clear();
                        pendingDeltas.clear();
                        generated.clear();
                        pendingLightUpdates.clear();
                        inflightLight.clear();
                        ShadowServerRegistry.getInstance().failShadowServer();
                        return;
                    }
                    SmokeChunkTrace.recordShadowInjected(dimension, pos);
                    if (shouldAccountServerPushAsApplied(requestedMisses.contains(e.getKey()))) {
                        requestedMisses.add(e.getKey());
                    }
                    accountVisibleNetworkIngress(dimension, pos, staleRepush);
                    LevelChunk injected = server.injectedChunk(dimension, pos.x, pos.z);
                    lightTasks.add(new LightTask(e.getKey(), LightSource.PENDING, pendingEntry,
                            injected, server.level(dimension), LightMetric.RECOMPUTE,
                            false, pendingEntry.traceOrigin()));
                }
                // 分段增量应用：本地基线 chunk 上就地覆盖变更 section + heightmaps + BE，
                // 变更 section 清光（applySectionDelta 内）→ 与注入共享下方光屏障。
                for (Map.Entry<Long, DeltaWork> e : deltaBatch) {
                    long key = e.getKey();
                    if (!pendingDeltas.containsKey(key)) {
                        continue; // REPLACE 后旧条目已被新 batch 接管 / 断连清理
                    }
                    pendingDeltas.remove(key);
                    ChunkPos pos = new ChunkPos(DimensionKey.chunkXOf(key), DimensionKey.chunkZOf(key));
                    DeltaWork work = e.getValue();
                    // P1（T7）：applySectionDelta 就地覆盖注入 chunk 的 section 容器
                    // （LevelChunkSection.read → PalettedContainer 写）——与 hash 比对线程
                    // （chunkHashOf / computeSectionHashes）同 chunk 锁互斥（T7 崩溃同机制）。
                    boolean applied;
                    synchronized (chunkLock(pos)) {
                        applied = server.applySectionDelta(work.dimension(), pos, work.entry());
                    }
                    if (!applied) {
                        DebugLogger.warn(DebugLogger.LogType.ASYNC,
                                "[SHADOW_DELTA] Apply failed ({}, {}), yield to vanilla tracking",
                                pos.x, pos.z);
                        io.github.limuqy.mc.hassium.network.ClientChunkPipeline.getInstance()
                                .setShadowServerFailed(true);
                    } else {
                        // 成功应用：部分命中 = 本地缓存整柱基线；分片 = FULL 整段 / BLOCKS 按格折算。
                        io.github.limuqy.mc.hassium.metrics.NetworkStats.recordCacheDeltaSaved(
                                io.github.limuqy.mc.hassium.metrics.NetworkStats.ESTIMATED_CHUNK_BYTES);
                        net.minecraft.world.level.chunk.LevelChunk baseline =
                                server.injectedChunk(work.dimension(), pos.x, pos.z);
                        int sectionCount = baseline != null ? baseline.getSectionsCount() : 0;
                        io.github.limuqy.mc.hassium.metrics.NetworkStats.recordCacheShard(
                                io.github.limuqy.mc.hassium.metrics.NetworkStats.shardEquivBytes(
                                        io.github.limuqy.mc.hassium.network.SectionDeltaS2CPacket.changedCells(
                                                work.entry().changedSections()),
                                        sectionCount));
                        io.github.limuqy.mc.hassium.metrics.NetworkStats.recordSectionDeltaReceived(1,
                                io.github.limuqy.mc.hassium.metrics.VanillaZlibEstimator.estimate(
                                        (int) io.github.limuqy.mc.hassium.metrics.NetworkStats.ESTIMATED_CHUNK_BYTES));
                        if (!isChunkBarrierBusy(key)) {
                            lightTasks.add(new LightTask(key, LightSource.DELTA, null,
                                    baseline, server.level(work.dimension()), LightMetric.RECOMPUTE,
                                    false, traceOrigin(TraceOrigin.SECTION_DELTA)));
                        }
                    }
                }
                // 本地生成（SeedGen / 磁盘光脏 relight）柱同样需要 per-chunk 光屏障
                for (Map.Entry<Long, GenEntry> e : genBatch) {
                    GenEntry gen = e.getValue();
                    if (gen == null || gen.chunk == null) {
                        generated.remove(e.getKey(), gen); // 异常条目：条件移除丢弃
                        continue;
                    }
                    lightTasks.add(new LightTask(e.getKey(), LightSource.GENERATED, gen,
                            gen.chunk, gen.level,
                            gen.lightReuse ? LightMetric.REUSE_CACHE : LightMetric.RECOMPUTE,
                            gen.renderOnly, gen.traceOrigin));
                }
                // 增量算光（LightDelta）：只清服务端声明变化的 section，重算后回传光包
                // （不回传整柱 chunk 包——方块数据未变，整柱重推是水面「亮→黑→亮」跳变源）。
                for (Map.Entry<Long, LightWork> e : lightBatch) {
                    long key = e.getKey();
                    LightWork work = e.getValue();
                    if (work == null || !work.hasSections()) {
                        pendingLightUpdates.remove(key, work);
                        continue;
                    }
                    if (!canStartLightDeltaNow(isChunkBarrierBusy(e.getKey()))) {
                        // 竞态：收集后又有整柱投递 / 首包 waiter。LightDelta 留队。
                        continue;
                    }
                    // LightDelta 无维度字段：键以客户端当前维度登记（submitLightDelta）。
                    String dimension = DimensionKey.dimensionOf(key);
                    ChunkPos pos = new ChunkPos(DimensionKey.chunkXOf(key), DimensionKey.chunkZOf(key));
                    boolean invalidated = server.invalidateLightSections(dimension, pos,
                            work.skyMask(), work.blockMask(), work.emptySkyMask(), work.emptyBlockMask());
                    if (!invalidated) {
                        // 柱未注入：不能重算。不请求全量：方块数据未变，hash 命中时本柱
                        // 会经读盘 isLightCorrect 决定是否续算。
                        pendingLightUpdates.remove(key, work);
                        continue;
                    }
                    LevelChunk lightChunk = server.injectedChunk(dimension, pos.x, pos.z);
                    if (lightChunk != null) {
                        server.syncLightCorrect(lightChunk, false);
                    }
                    lightTasks.add(new LightTask(key, LightSource.LIGHT_ONLY, work,
                            server.injectedChunk(dimension, pos.x, pos.z), server.level(dimension),
                            LightMetric.RECOMPUTE, false, null));
                }
                // 管道化：提交后不等待，立即回循环取下一批（批间零空转）；在途上限由轮顶检查约束。
                submitLightBatch(server, lightTasks);
            }
        } finally {
            consumeRunning.set(false);
            // 竞态：退出瞬间有新投递 → 重新触发。管道已满（在途=上限）时不 pump——
            // 由完成回调在低水位重新 pump（避免空转自旋）。
            // 暂不可创建时不自旋：只在 gameDir 已就绪或其他任务已创建影子端的竞态下补一次。
            // 两种就绪事件也会直接调用 pump；此处覆盖事件恰好落在 CAS 占用窗口内的竞态。
            if (hasStartablePendingWork()
                    && isEnabled()
                    && inflightLight.size() < PIPELINE_MAX_INFLIGHT
                    && (!isWaitingForShadowServer
                    || ClientChunkPipeline.getInstance().getGameDir() != null
                    || ShadowServerRegistry.getInstance().get() != null)) {
                pump();
            }
        }
    }


    /**
     * 将已注入区块交给原版 {@code ChunkMap} 的 {@code ChunkStatus.LIGHT} future。
     * 原版 holder 负责邻柱依赖、INITIALIZE_LIGHT/LIGHT 顺序和引擎执行；本类只做
     * REPLACE 条件移除、在途计数与完成后的唯一 packet 出口。
     * <p>
     * 提交失败和 future 异常进入欠光回退，不能阻塞后续区块。
     */
    private static void submitLightBatch(ShadowSeedServer server, List<LightTask> tasks) {
        if (server == null || tasks == null || tasks.isEmpty()) {
            return;
        }
        // 光照引擎 per-dimension：各任务可能属不同维度，逐任务在 startLightBarrier 内
        // 按目标 chunk 所属 level（LightTask.level）解析引擎；此处不再取全局 overworld engine。
        long deadlineMs = System.currentTimeMillis() + CONVERGENCE_WAIT_TIMEOUT_MS;
        synchronized (LIGHT_ENGINE_MUTEX) { // 锁只覆盖「提交循环」，等待/回调/回传全在锁外
            for (LightTask t : tasks) {
                // 提交即条件移除（REPLACE 守卫）：已被同 key 新投递覆盖 → 放弃本屏障。
                switch (t.source) {
                    case PENDING:
                        if (!pending.remove(t.key, t.token)) {
                            continue;
                        }
                        break;
                    case GENERATED:
                        if (!generated.remove(t.key, t.token)) {
                            continue;
                        }
                        break;
                    case DELTA:
                        break; // delta 条目已在 apply 时移除
                    case LIGHT_ONLY:
                        if (!pendingLightUpdates.remove(t.key, t.token)) {
                            continue; // 同柱有更新的 LightDelta 并集：下一轮处理
                        }
                        break;
                }
                if (t.chunk == null) {
                    // 注入/应用后查表缺失（异常路径）：与旧实现一致——warn + 条目已移除（丢弃）
                    DebugLogger.warn(DebugLogger.LogType.ASYNC,
                            "[SHADOW_CHUNK] Chunk missing after {} ({}, {})",
                            t.source == LightSource.DELTA ? "apply" : "inject",
                            new ChunkPos(DimensionKey.chunkXOf(t.key), DimensionKey.chunkZOf(t.key)).x,
                            new ChunkPos(DimensionKey.chunkXOf(t.key), DimensionKey.chunkZOf(t.key)).z);
                    continue;
                }
                try {
                    startLightBarrier(server, t, deadlineMs);
                    // 光照统计在提交成功时记（而非回传完成时）：冒烟快照窗口内光屏障可能还在
                    // 在途，等 finishLight 再记会让「分片增量已发生但光照重算仍显示 0」。
                    // PENDING（服务端直推剥光柱）同样计入：任务建时就带 RECOMPUTE metric，
                    // 排除它会让剥光会话 round1 的重算量整体漏记（光照行恒 0/0 死区）。
                    if (shouldAccountLightBarrierMetric(t.source == LightSource.LIGHT_ONLY)) {
                        ChunkPos metricPos = new ChunkPos(
                                DimensionKey.chunkXOf(t.key), DimensionKey.chunkZOf(t.key));
                        String metricDim = DimensionKey.dimensionOf(t.key);
                        if (t.renderOnly || t.metric == LightMetric.REUSE_CACHE) {
                            // OVD/renderOnly：本地全量服务，按复用记账、不进重算分母。
                            accountLightColumn(metricDim, metricPos, true);
                        } else if (t.metric == LightMetric.RECOMPUTE) {
                            accountLightColumn(metricDim, metricPos, false);
                        }
                    }
                } catch (Throwable ex) {
                    abortLight(t.key);
                }
            }
        }
    }

    /**
     * 影子区块统一通过原版 {@code ChunkStatus.INITIALIZE_LIGHT} → {@code LIGHT} task。
     * 这里使用真实 sections 构造 pre-light {@code ProtoChunk}，不进入 ChunkMap 的
     * FULL/worldgen 金字塔；本类只负责 future 完成后的唯一 packet 出口。
     */
    private static void startLightBarrier(ShadowSeedServer server,
                                          LightTask t, long deadlineMs) {
        InflightLight inf = new InflightLight(t.key, t.source, t.token,
                t.chunk, t.level, deadlineMs, t.metric, t.renderOnly, t.traceOrigin);
        inf.submittedAtNs = System.nanoTime();
        inflightLight.put(t.key, inf);
        try {
            net.minecraft.server.level.ServerLevel level = t.level != null
                    ? t.level : server.overworld();
            inf.nativeChunk = io.github.limuqy.mc.hassium.compat.ShadowServerCompat
                    .createNativeLightChunk(level, t.chunk,
                            lightChunkHasExistingLight(t.metric == LightMetric.REUSE_CACHE));
            io.github.limuqy.mc.hassium.compat.ShadowServerCompat
                    .initializeNativeLight(level, inf.nativeChunk)
                    .thenCompose(ignored -> io.github.limuqy.mc.hassium.compat.ShadowServerCompat
                            .completeNativeLight(level, inf.nativeChunk))
                    .whenComplete((ignored, throwable) -> {
                        if (throwable != null) {
                            abortLight(t.key);
                        } else {
                            completeLight(inf, true);
                        }
                    });
        } catch (Throwable failure) {
            abortLight(t.key);
        }
    }

    /** 原版 LIGHT future 完成后的唯一完成收口。 */
    private static boolean completeLight(InflightLight inf, boolean converged) {
        if (!inflightLight.remove(inf.key, inf)) {
            return false;
        }
        discardLightMask(inf.key);
        HassiumTaskExecutor executor = HassiumTaskExecutor.getClient();
        if (executor == null || !executor.isRunning() || !isEnabled()) {
            return true;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.isSameThread()) {
            finishLight(inf, converged);
            return true;
        }
        try {
            executor.submit(() -> finishLight(inf, converged), TaskCategory.BEST_EFFORT);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // 断连竞态：执行器已停止，丢弃本次回传。
        }
        return true;
    }
    /** LIGHT 未完成时退出影子光照，不广播 partial 数据，后续包走完整回退。 */
    private static void abortLight(long key) {
        inflightLight.remove(key);
        pendingLightUpdates.remove(key);
        ShadowServerRegistry.getInstance().failShadowServer();
    }

    /** 原版 LIGHT future 完成后的唯一完成收口。 */
    private static void finishLight(LightTask task, boolean converged) {
        if (!isEnabled() || isSuperseded(task)) {
            return;
        }
        ChunkPos pos = new ChunkPos(DimensionKey.chunkXOf(task.key), DimensionKey.chunkZOf(task.key));
        ShadowSeedServer server = ShadowServerRegistry.getInstance().get();
        if (task.metric == LightMetric.RECOMPUTE) {
            long elapsedNs = task.submittedAtNs > 0L
                    ? Math.max(0L, System.nanoTime() - task.submittedAtNs)
                    : 0L;
            io.github.limuqy.mc.hassium.metrics.NetworkStats.recordLightRecomputeBackgroundTime(elapsedNs);
        }
        try {
            if (task.source == LightSource.LIGHT_ONLY) {
                LightWork work = task.token instanceof LightWork w ? w : null;
                pushLightReady(pos, task.level, task.chunk, converged, work);
            } else {
                pushReady(task.key, task.chunk, task.level, converged, task.renderOnly,
                        task.traceOrigin);
            }
        } catch (Throwable t) {
            DebugLogger.warn(DebugLogger.LogType.ASYNC,
                    "[SHADOW_CHUNK] Build failed ({}, {})", pos.x, pos.z);
        }
        if (server != null && task.chunk != null) {
            server.persistAfterClientLightPush(task.chunk, converged);
        }
        if (inflightLight.size() < PIPELINE_LOW_WATER && hasStartablePendingWork() && isEnabled()) {
            pump();
        }
    }

    /** 丢弃某柱尚未消费的光照更新与回传队列中的旧光包。 */
    private static void discardLightMask(long key) {
        LightMask mask = lightUpdates.remove(key);
        if (mask != null) {
            synchronized (mask) {
                mask.discarded = true;
                mask.skySections.clear();
                mask.blockSections.clear();
            }
        }
        dropQueuedLights(new ChunkPos(DimensionKey.chunkXOf(key), DimensionKey.chunkZOf(key)));
    }

    private static void dropQueuedLights(ChunkPos pos) {
        ready.removeIf(item -> item.lightPacket != null
                && item.lightPacket.getX() == pos.x && item.lightPacket.getZ() == pos.z);
    }

    /** 区块卸载前取消该柱所有尚未完成的影子光照/回传工作。 */
    public static void cancelChunkWork(long key) {
        pending.remove(key);
        generated.remove(key);
        pendingDeltas.remove(key);
        pendingLightUpdates.remove(key);
        inflightLight.remove(key);
        lightUpdates.remove(key);
        shadowApplyEpochs.remove(key);
        discardLightMask(key);
        DebugLogger.info(DebugLogger.LogType.ASYNC,
                "[SHADOW_LIGHT] Cancelled work before unload ({}, {})",
                DimensionKey.chunkXOf(key), DimensionKey.chunkZOf(key));
    }

    /** LIGHT future 超时只触发完整回退，不广播 partial 光照。 */
    private static void sweepLightTimeouts() {
        ShadowSeedServer server = ShadowServerRegistry.getInstance().get();
        long now = System.currentTimeMillis();
        for (InflightLight inf : inflightLight.values()) {
            if (now >= inf.deadlineMs && inflightLight.remove(inf.key, inf)) {
                if (server != null) {
                    ShadowServerRegistry.getInstance().failShadowServer();
                }
                DebugLogger.warn(DebugLogger.LogType.ASYNC,
                        "[SHADOW_LIGHT] Light timeout ({}ms) ({}, {}), switching to vanilla fallback",
                        CONVERGENCE_WAIT_TIMEOUT_MS, DimensionKey.chunkXOf(inf.key),
                        DimensionKey.chunkZOf(inf.key));
            }
        }
    }

    /**
     * 打包官方包（带权威光）入回传队列（chunk 包 op=OP_CHUNK_APPLY，REPLACE）。
     * <p>
     * 光照复用记账口径（P2）：{@code converged=true} 本身<em>不是</em>复用信号——注入/生成/增量
     * 路径的块在引擎收敛后同样以 converged=true 回传（此时光为本会话新算，非复用）。
     * 真正的光复用事件只发生在三个缓存命中点（{@link #processRemoteHashes} 内存/磁盘命中、
     * {@link #consumeLoop} 磁盘直推），记账在那些调用点完成
     * （{@code NetworkStats.recordLightReuseShadow}，key {@code light.reuse.shadow.*}），
     * 此处不重复计数，避免把「收敛后回传」误计为「光复用」。
     */

    private static void pushReady(long key, net.minecraft.world.level.chunk.LevelChunk chunk,
                                  net.minecraft.server.level.ServerLevel level, boolean converged,
                                  boolean renderOnly, TraceOrigin traceOrigin) {
        ChunkPos pos = chunk.getPos();
        // P1（T7）：buildPacket 读注入 chunk section 容器（extractChunkData →
        // LevelChunkSection.write → PalettedContainer.acquire）——与 hash 比对线程
        // （chunkHashOf / computeSectionHashes）同 chunk 锁互斥，消除 1.21.11
        // ThreadingDetector 崩溃（T7 线程转储：consumeLoop pushReady 打包 vs hash 线程）。
        runBuildOnShadowMain(pos, () -> {
            ClientboundLevelChunkWithLightPacket packet;
            synchronized (chunkLock(pos)) {
                packet = SeedGenChunkCodec.buildPacket(chunk, level);
            }
            offerReady(key, pos, packet, converged, renderOnly, traceOrigin);
        });
    }

    /**
     * PalettedContainer.write/getAndSet 只能与影子主循环串行：后台 finishLight
     * 与 applyBlockUpdate（seedgen-main setBlock）并发会触发 1.21+
     * ThreadingDetector（Accessing PalettedContainer from multiple threads），
     * 打包失败后光屏障不回传，R2 drain 卡死。已在主线程则原地执行。
     */
    private static void runBuildOnShadowMain(ChunkPos pos, Runnable buildAndOffer) {
        ShadowSeedServer server = ShadowServerRegistry.getInstance().get();
        if (server != null && !server.isSameThread()) {
            try {
                server.execute(buildAndOffer);
                return;
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                DebugLogger.warn(DebugLogger.LogType.ASYNC,
                        "[SHADOW_CHUNK] Shadow main stopped, drop pack ({}, {})", pos.x, pos.z);
                return;
            }
        }
        buildAndOffer.run();
    }

    /**
     * 入 ready 队列（pos REPLACE）。{@code packet==null} 只记日志。预览与收敛共用。
     */
    private static void offerReady(long key, ChunkPos pos, ClientboundLevelChunkWithLightPacket packet,
                                   boolean converged, boolean renderOnly, TraceOrigin traceOrigin) {
        if (packet == null) {
            DebugLogger.warn(DebugLogger.LogType.ASYNC,
                    "[SHADOW_CHUNK] Build packet failed ({}, {})", pos.x, pos.z);
            return;
        }
        io.github.limuqy.mc.hassium.utils.ChunkFlowTiming.recordReady(key);
        if (!renderOnly) {
            SmokeChunkTrace.recordShadowReady(DimensionKey.dimensionOf(key), pos);
        }
        dropQueuedLights(pos);
        ready.offer(new ReadyItem(packet, null, renderOnly, traceOrigin, null, null),
                new KeyedPriorityQueue.Key(ChunkPos.asLong(pos.x, pos.z),
                        io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher.OP_CHUNK_APPLY,
                        DimensionKey.dimensionOf(key)),
                fifoApplyPriority(),
                KeyedPriorityQueue.OfferPolicy.REPLACE);
    }

    /**
     * 打包纯光包入回传队列——LightDelta / 邻柱补光等「方块数据未变、只更新光」的路径。
     * 不发整柱 chunk 包：整柱重推会先黑一帧再由光包修正，正是水面「有光→黑→有光」
     * 跳变的来源。
     * <p>
     * 掩码只用 LightDelta 声明变化的 section（skyMask ∪ emptySkyMask、blockMask ∪
     * emptyBlockMask）：未变化的 section 不在包内，客户端保留旧光——全柱 null 掩码会
     * 把所有「当前为空」的 section 打成 empty 掩码，客户端显式置 0，正是
     * 「已亮区块被覆盖成暗」的来源之一。旧协议/未知 work 回退 null（全柱）。
     */
    private static void pushLightReady(ChunkPos pos, net.minecraft.server.level.ServerLevel level,
                                       net.minecraft.world.level.chunk.LevelChunk chunk,
                                       boolean converged, LightWork work) {
        BitSet skyMask = null;
        BitSet blockMask = null;
        if (work != null) {
            skyMask = (BitSet) work.skyMask().clone();
            skyMask.or(work.emptySkyMask());
            blockMask = (BitSet) work.blockMask().clone();
            blockMask.or(work.emptyBlockMask());
        }
        ClientboundLightUpdatePacket packet =
                new ClientboundLightUpdatePacket(pos, level.getLightEngine(), skyMask, blockMask);
        offerLightReady(pos, packet);
        DebugLogger.info(DebugLogger.LogType.CHUNK_APPLY,
                "[SHADOW_LIGHT] Queued full light update ({}, {}), converged={}",
                pos.x, pos.z, converged);
    }

    private static void offerLightReady(ChunkPos pos, ClientboundLightUpdatePacket packet) {
        Long lightQueuedAtMs = DebugLogger.isEnabled(DebugLogger.LogType.CHUNK_APPLY)
                ? System.currentTimeMillis()
                : null;
        ready.offer(new ReadyItem(null, packet, false, null, lightQueuedAtMs, null),
                new KeyedPriorityQueue.Key(ChunkPos.asLong(pos.x, pos.z),
                        io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher.OP_LIGHT_UPDATE,
                        currentDimension()),
                fifoApplyPriority(),
                KeyedPriorityQueue.OfferPolicy.REPLACE);
    }

    private static boolean hasClientChunk(Minecraft mc, int chunkX, int chunkZ) {
        return mc != null && mc.level != null
                && ((io.github.limuqy.mc.hassium.mixin.ClientLevelAccessor) mc.level)
                        .hassium$getChunkSource().hasChunk(chunkX, chunkZ);
    }

    /**
     * 帧尾（MixinClientTick，渲染前）：光掩码入同一 FIFO 回传队列后按到达顺序落地。
     * JoinBoost 两段消费：先 chunk 再光；非 JoinBoost 保持 FIFO。区块包入队时丢掉该柱旧光。
     * 消费只受时间预算约束。
     */
    public static void drainReady() {
        drainReady(Long.MAX_VALUE);
    }

    /**
     * @param deadlineNs 本帧截止（{@link System#nanoTime()}）。超时后至少 force 一条
     *                   <em>chunk</em>（若队列有 chunk）；JoinBoost 期间队列里还有 chunk
     *                   时不 force 光包。
     */
    public static void drainReady(long deadlineNs) {
        io.github.limuqy.mc.hassium.utils.ChunkFlowTiming.noteFrame(); // T0b 诊断：每帧 apply 计数
        ShadowSeedServer server = ShadowServerRegistry.getInstance().get();
        if (server != null && server.isLightConverged()) {
            server.confirmLightsCorrectIfConverged();
        }
        ShadowLightProbe.onEngineTick(); // T3 探针：引擎终态周期快照（debug.lightVerify 门控）
        sweepLightTimeouts(); // per-chunk 光屏障 5s 超时兜底（主扫描点；低帧率由消费轮顶兜底）
        boolean joinBoost = ClientMainThreadBudget.isJoinBoostActive();
        if (!joinBoost) {
            drainLightMasks(deadlineNs, false, false, 0);
        }
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc != null ? mc.getConnection() : null;
        if (connection == null) {
            drainLightMasks(deadlineNs, false, false, 0); // 断连：与原先一样清空 lightUpdates
            ready.clear();
            logStallDrain(0, deadlineNs);
            return;
        }
        // 服务端 ACK 窗口在 100/s 平台期已满：一旦本帧 ready 空、又不再 submit()，
        // consumeLoop 不会被 pump，pending 会一直趴着直到 30s delivery timeout。
        // 只用可开工的投递唤醒：林火 LightDelta 在整柱屏障后排队，等 finishLight 触发，
        // 不在每帧 drain 里空转扫描。
        if ((hasStartablePendingWork() || !ready.isEmpty())
                && inflightLight.size() < PIPELINE_MAX_INFLIGHT
                && isEnabled()) {
            pump();
        }
        if (ready.isEmpty() && !joinBoost) {
            logStallDrain(0, deadlineNs);
            return;
        }
        List<KeyedPriorityQueue.Entry<ReadyItem>> deferredLights = new ArrayList<>();
        List<KeyedPriorityQueue.Entry<ReadyItem>> deferredRetries = new ArrayList<>();
        boolean forceOne = true;
        boolean chunkPassDone = !joinBoost;
        int chunksAppliedThisFrame = 0;
        while (true) {
            KeyedPriorityQueue.Entry<ReadyItem> entry = ready.poll();
            if (entry == null) {
                if (!chunkPassDone) {
                    chunkPassDone = true;
                    for (KeyedPriorityQueue.Entry<ReadyItem> light : deferredLights) {
                        ready.reoffer(light, light.priority());
                    }
                    deferredLights.clear();
                    if (System.nanoTime() < deadlineNs) {
                        drainLightMasks(deadlineNs, true, false, chunksAppliedThisFrame);
                    }
                    continue;
                }
                break;
            }
            if (!forceOne && System.nanoTime() >= deadlineNs) {
                ready.reoffer(entry, entry.priority());
                break;
            }
            if (!ready.isCurrent(entry)) {
                continue;
            }
            ReadyItem item = entry.item();
            boolean isChunk = item.chunkPacket != null;
            boolean isLight = item.lightPacket != null && !isChunk;
            boolean chunkWaiting = joinBoost && !chunkPassDone;
            if (isLight && !shouldApplyLightThisFrame(joinBoost, chunkWaiting, chunksAppliedThisFrame)) {
                deferredLights.add(entry);
                continue;
            }
            boolean releaseEntry = true;
            try {
                if (isChunk) {
                    forceOne = false;
                    chunksAppliedThisFrame++;
                    releaseEntry = applyReadyChunk(mc, connection, entry, item);
                } else if (isLight) {
                    long chunkKey = DimensionKey.key(entry.key().dimension(), entry.key().posLong());
                    if (!shadowApplyEpochs.containsKey(chunkKey)
                            || !hasClientChunk(mc, (int) entry.key().posLong(),
                            (int) (entry.key().posLong() >> 32))) {
                        // 光包可先于区块包抵达；保留并重排队，不能静默丢弃。
                        releaseEntry = false;
                        continue;
                    }
                    forceOne = false;
                    applyReadyLight(mc, connection, entry);
                }
            } catch (Throwable t) {
                if (item.chunkPacket != null) {
                    ClientChunkHandler.logShadowChunkApplyEvent("shadow_failed",
                            new ChunkPos(entry.key().posLong()), item.renderOnly(), item.traceOrigin());
                }
                DebugLogger.warn(DebugLogger.LogType.ASYNC,
                        "[SHADOW_CHUNK] Official channel apply failed ({}, {})",
                        entry.key().posLong() & 0xFFFFFFFFL,
                        (entry.key().posLong() >> 32) & 0xFFFFFFFFL);
            } finally {
                if (releaseEntry) {
                    ready.release(entry);
                } else {
                    deferredRetries.add(entry);
                }
            }
        }
        for (KeyedPriorityQueue.Entry<ReadyItem> light : deferredLights) {
            ready.reoffer(light, light.priority());
        }
        for (KeyedPriorityQueue.Entry<ReadyItem> retry : deferredRetries) {
            ready.reoffer(retry, retry.priority());
        }
        logStallDrain(chunksAppliedThisFrame, deadlineNs);
    }

    private static void logStallDrain(int applied, long deadlineNs) {
        io.github.limuqy.mc.hassium.utils.StallDiag.clientHz(
                "drain applied={} leftoverMs={} {} {} joinBoost={} rem={}ms budgetMs={} dispQ={} ackPend={}",
                applied,
                Math.max(0L, deadlineNs - System.nanoTime()) / 1_000_000L,
                stallSnapshot(),
                io.github.limuqy.mc.hassium.network.ClientMetadataHandler.stallSnapshot(),
                ClientMainThreadBudget.isJoinBoostActive(),
                ClientMainThreadBudget.joinBoostRemainingMs(),
                ClientMainThreadBudget.getBudgetNs() / 1_000_000L,
                io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher.getClientQueueSize(),
                ClientChunkPipeline.getInstance().isServerSeedGenEnabled());
    }

    /** @return true=本条目可 release；false=权威包被原版忽略，帧尾重入队 */
    private static boolean applyReadyChunk(Minecraft mc, ClientPacketListener connection,
                                           KeyedPriorityQueue.Entry<ReadyItem> entry, ReadyItem item) {
        int chunkX = item.chunkPacket.getX();
        int chunkZ = item.chunkPacket.getZ();
        ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
        long chunkKey = DimensionKey.key(entry.key().dimension(), chunkX, chunkZ);
        ClientChunkHandler.logShadowChunkApplyEvent("shadow_attempt", chunkPos, false, item.traceOrigin());
        ClientChunkPipeline pipeline = ClientChunkPipeline.getInstance();
        pipeline.setApplyInProgress(true);
        try {
            connection.handleLevelChunkWithLight(item.chunkPacket);
        } finally {
            pipeline.setApplyInProgress(false);
        }
        if (hasClientChunk(mc, chunkX, chunkZ)) {
            ClientChunkHandler.logShadowChunkApplyEvent("shadow_applied", chunkPos, false, item.traceOrigin());
            shadowApplyEpochs.put(chunkKey, shadowApplyEpoch.incrementAndGet());
            recordFullApplyTrace(chunkKey, false, item.traceOrigin());
            SmokeChunkTrace.recordClientApplied(entry.key().dimension(), chunkPos);
            io.github.limuqy.mc.hassium.metrics.NetworkStats.recordChunkApplied(chunkX, chunkZ);
            accountAuthoritativeLanded(entry.key().dimension(), chunkPos, item.traceOrigin());
            io.github.limuqy.mc.hassium.cache.client.ClientMainThreadBudget.noteChunkApplyActivity();
            ClientChunkHandler.probeChunkState(chunkPos, mc.level, "shadow");
            return true;
        }
        ClientChunkHandler.logShadowChunkApplyEvent("shadow_ignored", chunkPos, false, item.traceOrigin());
        DebugLogger.info(DebugLogger.LogType.CHUNK_APPLY,
                "[SHADOW_CHUNK] Vanilla ignored authoritative chunk ({}, {}) — retrying next frame",
                chunkX, chunkZ);
        return false;
    }

    private static void applyReadyLight(Minecraft mc, ClientPacketListener connection,
                                        KeyedPriorityQueue.Entry<ReadyItem> entry) {
        ReadyItem item = entry.item();
        if (item.lightPacket == null) {
            return;
        }
        ChunkPos lightPos = new ChunkPos(item.lightPacket.getX(), item.lightPacket.getZ());
        connection.handleLightUpdatePacket(item.lightPacket);
        if (!DebugLogger.isEnabled(DebugLogger.LogType.LIGHT_VERIFY)
                || mc == null || mc.level == null) {
            return;
        }
        FullApplyTrace fullTrace = fullApplyTraces.get(DimensionKey.key(
                entry.key().dimension(), item.lightPacket.getX(), item.lightPacket.getZ()));
        long appliedAtMs = System.currentTimeMillis();
        long fullApplyAgeMs = fullTrace == null ? -1L : appliedAtMs - fullTrace.appliedAtMs();
        long lightQueueDelayMs = item.lightQueuedAtMs() == null
                ? -1L
                : appliedAtMs - item.lightQueuedAtMs();
        boolean fullAppliedAfterLightQueued = fullTrace != null && item.lightQueuedAtMs() != null
                && fullTrace.appliedAtMs() > item.lightQueuedAtMs();
        boolean chunkPresent = hasClientChunk(mc, lightPos.x, lightPos.z);
        ClientChunkHandler.probeShadowLightState(lightPos, mc.level,
                fullTrace == null ? null : fullTrace.origin(),
                fullTrace != null && fullTrace.renderOnly(),
                fullTrace == null ? -1L : fullTrace.sequence(), fullApplyAgeMs,
                lightQueueDelayMs, fullAppliedAfterLightQueued, chunkPresent);
    }


    /**
     * 光照更新攒批打包（客户端主线程帧尾，{@link #drainReady} 调用）：
     * light 线程收集的绝对 sectionY 掩码 → 按 {@code engine.getMinLightSection()} 偏移
     * 转 BitSet（mask 位 = sectionY − minLightSection，与 ClientboundLightUpdatePacketData
     * 遍历语义一致，两版零适配）→ 构造官方 {@link ClientboundLightUpdatePacket} 入同一
     * FIFO 回传队列（op={@code OP_LIGHT_UPDATE}，REPLACE）。未落地影子区块包 / 屏障中 /
     * 欠光暂缓 / 邻柱重播中的柱本帧不打包。
     * <p>
     * 非 JoinBoost 先于消费打包（无数量硬顶，受 deadline）；JoinBoost 在本帧 chunk
     * 过完后最多打包 1 条。剩余掩码留待下一帧，构建成功才清除
     * （synchronized(mask) 内清空 + 条件移除，light 线程并发收集不丢失）。
     */
    private static void drainLightMasks(long deadlineNs, boolean joinBoost,
                                        boolean chunkWaiting, int chunksAppliedThisFrame) {
        if (lightUpdates.isEmpty()) {
            return;
        }
        // pauseEncoding 只挡 ChunkSerializer 入队，不挡主线程光包 drain。
        ShadowSeedServer server = ShadowServerRegistry.getInstance().getOrCreate();
        if (server == null) {
            lightUpdates.clear(); // 影子端不可用：收集作废
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc != null ? mc.getConnection() : null;
        if (connection == null) {
            lightUpdates.clear(); // 断连竞态：丢弃
            return;
        }
        // 光照引擎 per-dimension：掩码键携带维度，逐键解析所属 level 的引擎参数
        // （nether/end 高度剖面不同，minLightSection/lightSectionCount 不能恒取 overworld）。
        List<Long> keys = new ArrayList<>();
        int packedThisFrame = 0;
        for (Long key : lightUpdates.keySet()) {
            boolean deadlineHit = System.nanoTime() >= deadlineNs;
            if (!shouldPackLightMaskThisFrame(joinBoost, chunkWaiting, chunksAppliedThisFrame,
                    deadlineHit, packedThisFrame)) {
                break;
            }
            if (!shadowApplyEpochs.containsKey(key)) {
                continue;
            }
            keys.add(key);
            packedThisFrame++;
        }
        for (Long key : keys) {
            LightMask mask = lightUpdates.get(key);
            if (mask == null) {
                continue;
            }
            ChunkPos pos = new ChunkPos(DimensionKey.chunkXOf(key), DimensionKey.chunkZOf(key));
            net.minecraft.server.level.ServerLevel level =
                    server.level(DimensionKey.dimensionOf(key));
            if (level == null) {
                lightUpdates.remove(key); // 维度未装配：收集作废
                continue;
            }
            LevelLightEngine engine = level.getLightEngine();
            int minLightSection = engine.getMinLightSection();
            int lightSectionCount = engine.getLightSectionCount();
            BitSet skyMask;
            BitSet blockMask;
            synchronized (mask) {
                if (mask.discarded) {
                    // completeLight 已把最终全量光入队（或即将入队）：旧引用里的
                    // 中间波次禁止再构建，否则会排在最终光之后造成跳变。
                    mask.skySections.clear();
                    mask.blockSections.clear();
                    continue;
                }
                skyMask = toLightBitSet(mask.skySections, minLightSection, lightSectionCount);
                blockMask = toLightBitSet(mask.blockSections, minLightSection, lightSectionCount);
                boolean removable = mask.skySections.isEmpty() && mask.blockSections.isEmpty();
                mask.skySections.clear();
                mask.blockSections.clear();
                // 锁内条件回收：本轮 copy 前无任何收集才移除登记（防 chunk 离开视距后
                // 空 mask 永久残留）。已持引用等锁的 collect 写入发生在移除之后，其
                // 循环验证（collectLightUpdate 内 get(key) != mask → 重试新建）保证不丢。
                if (removable) {
                    lightUpdates.remove(key, mask);
                }
            }
            if (skyMask.isEmpty() && blockMask.isEmpty()) {
                continue; // 收集全部越界（异常高度数据）：无可发送内容
            }
            try {
                offerLightReady(pos, new ClientboundLightUpdatePacket(pos, engine, skyMask, blockMask));
            } catch (Throwable t) {
                DebugLogger.warn(DebugLogger.LogType.ASYNC,
                        "[SHADOW_LIGHT] Build failed ({}, {})", pos.x, pos.z);
            }
        }
    }

    /** 绝对 sectionY 集合 → 包掩码 BitSet（位 = sectionY − minLightSection；越界丢弃）。 */
    private static BitSet toLightBitSet(java.util.TreeSet<Integer> sections,
                                        int minLightSection, int lightSectionCount) {
        BitSet bits = new BitSet();
        for (int y : sections) {
            int bit = y - minLightSection;
            if (bit >= 0 && bit < lightSectionCount) {
                bits.set(bit);
            }
        }
        return bits;
    }


    /**
     * 光照更新收集（影子端 light 线程入口；MixinServerChunkCache.onLightUpdate HEAD
     * 拦截，T2 门控 {@code RuntimeServerContext.isShadowServerContext()}）：
     * 引擎每完成一个 section 的光计算写数据层 → 收集该 section（绝对 sectionY）到
     * 本 chunk 的 LightMask。线程安全：ConcurrentHashMap<chunkKey, LightMask> 登记 +
     * {@code synchronized(mask)} 写（主线程 drainLightMasks 同锁读清）。
     * <p>
     * 写入前验证登记仍指向本 mask（drain 已条件移除的空 mask 不复用，重试拿新登记），
     * 与 drain 的锁内回收配合：任一收集的 sectionY 必然落入某轮 drain 的处理范围。
     * <p>
     * 键维度 = 客户端当前维度（mixin 入口无维度上下文；影子端只装配三主维度）。
     */
    public static void collectLightUpdate(LightLayer layer, SectionPos sectionPos) {
        if (layer == null || sectionPos == null || !isEnabled()) {
            return;
        }
        long key = DimensionKey.key(currentDimension(), sectionPos.x(), sectionPos.z());
        while (true) {
            LightMask mask = lightUpdates.computeIfAbsent(key, k -> new LightMask());
            synchronized (mask) {
                if (lightUpdates.get(key) != mask) {
                    continue; // 已被 drain 回收：重试拿新登记（不丢数据）
                }
                (layer == LightLayer.SKY ? mask.skySections : mask.blockSections).add(sectionPos.y());
                return;
            }
        }
    }
    /** 客户端原版卸载立刻作废该柱的光桥凭据和未发送的光照掩码。 */
    public static void onClientChunkUnloaded(ChunkPos pos) {
        if (pos == null) {
            return;
        }
        long chunkKey = DimensionKey.key(currentDimension(), pos.x, pos.z);
        Long removedEpoch = shadowApplyEpochs.remove(chunkKey);
        fullApplyTraces.remove(chunkKey);
        if (removedEpoch != null) {
            DebugLogger.info(DebugLogger.LogType.CHUNK_APPLY,
                    "[SHADOW_LIGHT] Client unload invalidated ({}, {}) epoch={}",
                    pos.x, pos.z, removedEpoch);
        }
    }

    private static void recordFullApplyTrace(long chunkKey, boolean renderOnly, TraceOrigin origin) {
        if (!DebugLogger.isEnabled(DebugLogger.LogType.CHUNK_APPLY)) {
            return;
        }
        fullApplyTraces.put(chunkKey, new FullApplyTrace(
                fullApplySequence.incrementAndGet(), System.currentTimeMillis(), renderOnly, origin));
    }


    /** 断连清理：清空投递/生成/回传/光照收集（影子服务端由 registry 统一关停保存）。 */
    public static void onDisconnect() {
        pending.clear();
        pendingDeltas.clear();
        generated.clear();
        pendingLightUpdates.clear();
        inflightLight.clear(); // 在途光屏障：回调侧条件移除失败即短路丢弃（断连竞态）
        ready.clear();
        lightUpdates.clear();
        requestedMisses.clear();
        accountedIngress.clear();
        accountedCacheHits.clear();
        accountedLights.clear();
        resetHashClassify();
        shadowApplyEpochs.clear();
        fullApplyTraces.clear();
        consumeRunning.set(false);
        io.github.limuqy.mc.hassium.network.ShadowPullClient.reset();
    }

    /**
     * 磁盘/内存 contentHash 与远程权威比对：表命中 TRUE 直接信；表缺失或 FALSE
     * 再从活柱现算（避免脏表把整柱判成增量）。
     */
    private static boolean diskHashMatches(String dimension, LevelChunk chunk, ChunkPos pos, long remoteHash) {
        Boolean tableMatch =
                io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.matchesRemote(dimension, pos, remoteHash);
        if (tableMatch == Boolean.TRUE) {
            return true;
        }
        try {
            long diskHash = io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil
                    .combineSectionHashes(io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil
                            .computeSectionHashes(chunk));
            io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.put(dimension, pos, diskHash);
            return diskHash == remoteHash;
        } catch (Throwable t) {
            return false;
        }
    }

    private static TraceOrigin traceOrigin(TraceOrigin origin) {
        return ClientChunkHandler.traceOriginIfLoggingEnabled(origin);
    }

    private static long chunkPosKey(ChunkPos pos) {
        // 必须与官方 ChunkPos.asLong(x, z) 编码一致（x 低位、z 高位）：
        // 之前自定义 (x<<32)|z 与官方相反，consumeLoop 用 new ChunkPos(key) 解出 (z,x) 对调，
        // 导致 hash/数据跨坐标互换（consume [x,z] 拿到 [z,x] 的 packet）。
        return net.minecraft.world.level.ChunkPos.asLong(pos.x, pos.z);
    }

    /** 诊断：投递队列大小。 */
    public static int pendingCount() {
        return pending.size();
    }

    /** 诊断：回传队列大小（区块 + 光照）。 */
    public static int readyCount() {
        return ready.size();
    }

    /**
     * 主线程是否应为 {@code drainReady} 预留预算：ready 非空，或影子管线里还有
     * pending / 在途光屏障。只看 {@link #readyCount()} 会在 JoinBoost 到期后把
     * 100% 预算给 dispatcher，pending 抽不出来、ACK 停、服务端窗口卡死。
     */
    public static boolean hasBacklog() {
        return readyCount() > 0 || hasPendingWork() || !inflightLight.isEmpty();
    }

    /** 冒烟卡顿诊断：影子管线队列快照。 */
    public static String stallSnapshot() {
        return "ready=" + ready.size()
                + " pending=" + pending.size()
                + " gen=" + generated.size()
                + " delta=" + pendingDeltas.size()
                + " inflightLight=" + inflightLight.size()
                + " consume=" + consumeRunning.get();
    }

    /** 光屏障来源：决定 submitLightBatch 提交时的队列条件移除与 finishLight 回传前 REPLACE 校验方式。
     *  预览算光仅 PENDING / GENERATED（且 LightMetric.RECOMPUTE）；DELTA / LIGHT_ONLY 跳过。 */
    private enum LightSource {
        /** 远程全量注入（{@link #submit} → pending）。预览覆盖。 */
        PENDING,
        /** 本地生成 / 磁盘命中 / relight（{@link #submitGenerated} → generated）。预览覆盖。 */
        GENERATED,
        /** 分段增量（{@link #submitDelta} → pendingDeltas）。不做预览。 */
        DELTA,
        /** 增量算光 / 邻柱补光：只回传光包（{@link #submitLightDelta} → pendingLightUpdates）。不做预览。 */
        LIGHT_ONLY
    }

    /** 光照统计口径：REUSE_CACHE = 命中点已记 shadow reuse（跳过预览）；
     *  RECOMPUTE = 光屏障完成后记 miss + 重算耗时（PENDING/GENERATED 提交隔离预览）。
     *  LIGHT_ONLY 不记柱级 miss（邻柱补光会把次数刷到数万）。 */
    private enum LightMetric {
        REUSE_CACHE,
        RECOMPUTE
    }

    private static class LightTask {
        final long key;
        final LightSource source;
        /** PENDING: 提交的 packet；GENERATED: 提交的 GenEntry；DELTA: null；LIGHT_ONLY: LightWork。 */
        final Object token;
        final net.minecraft.world.level.chunk.LevelChunk chunk;
        final net.minecraft.server.level.ServerLevel level;
        /** 1.20.1 native ChunkStatus loading task 使用的 pre-light ProtoChunk。 */
        volatile net.minecraft.world.level.chunk.ChunkAccess nativeChunk;
        final LightMetric metric;
        /** true=OVD 提交的 renderOnly 区块；false=普通影子回传。 */
        final boolean renderOnly;
        /** 来源仅在 debug.chunkApplyLogging 开启时随影子工作项传递。 */
        final TraceOrigin traceOrigin;
        /** 屏障提交时刻；光屏障完成时用于记重算耗时。 */
        volatile long submittedAtNs;
        /** 等邻柱 INITIALIZE_LIGHT 的起点；0=尚未进入 LIGHT 等待。 */
        volatile long packWaitStartMs;

        LightTask(long key, LightSource source, Object token,
                  net.minecraft.world.level.chunk.LevelChunk chunk,
                  net.minecraft.server.level.ServerLevel level, LightMetric metric,
                  boolean renderOnly, TraceOrigin traceOrigin) {
            this.key = key;
            this.source = source;
            this.token = token;
            this.chunk = chunk;
            this.level = level;
            this.metric = metric;
            this.renderOnly = renderOnly;
            this.traceOrigin = traceOrigin;
        }
    }

    /** 在途光屏障条目：LightTask + 超时截止（completeLight / sweepLightTimeouts 共用，
     *  条件移除 {@code inflightLight.remove(key, inf)} 保证 exactly-once）。 */
    private static final class InflightLight extends LightTask {
        final long deadlineMs;
        /** 已调用 {@code lightChunk}：预览 adopt 必须跳过，以免 queued 盖掉邻柱入流。 */
        volatile boolean lightChunkSubmitted;

        InflightLight(long key, LightSource source, Object token,
                      net.minecraft.world.level.chunk.LevelChunk chunk,
                      net.minecraft.server.level.ServerLevel level, long deadlineMs,
                      LightMetric metric, boolean renderOnly, TraceOrigin traceOrigin) {
            super(key, source, token, chunk, level, metric, renderOnly, traceOrigin);
            this.deadlineMs = deadlineMs;
        }
    }


    private record PendingEntry(ClientboundLevelChunkWithLightPacket packet, TraceOrigin traceOrigin) {}
    private record FullApplyTrace(long sequence, long appliedAtMs, boolean renderOnly, TraceOrigin origin) {}


    /** lightReuse=true：存档/引擎光可复用（lightChunk 第二参 true）；false：LIGHT 续算播种+传播。 */
    private record GenEntry(net.minecraft.world.level.chunk.LevelChunk chunk,
                            net.minecraft.server.level.ServerLevel level,
                            boolean lightReuse,
                            boolean renderOnly,
                            TraceOrigin traceOrigin) {}
}
