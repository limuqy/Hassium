package io.github.limuqy.mc.hassium.network.seedgen;

import io.github.limuqy.mc.hassium.cache.client.ClientMainThreadBudget;
import io.github.limuqy.mc.hassium.cache.client.JoinWorldFocus;
import io.github.limuqy.mc.hassium.compat.ClientLoadingScreenCompat;
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
 * 影子端不可用（未握手 / 创建失败 / 引擎关闭）时不投递——调用方
 * （Compare+Pull 客户端摄入链）走原版/拉取回退（apply + 本地缓存），剥光仅在
 * 握手声明引擎可用后发生。
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
     * 「原版忽略该柱」的连续重试计数（复合键 → 次数）。
     * <p>
     * 原版 {@code ClientChunkCache} 会拒收不在其 tracking view 内的柱（"Ignoring chunk
     * since it's not in the view range"）。该拒绝<strong>不可自愈</strong>：只要玩家不再移动
     * 回该区域，同一柱永远进不去。原先只做「下一帧重试」，于是快速移动后滞留在投递队列里的
     * 旧窗口柱在 ready 里每帧打转——实测移动冒烟 21s 内 102010 次重试，渲染线程被吃满，
     * 场景推进不了、客户端 120s 不退出。超过 {@link #MAX_IGNORED_RETRIES} 即放弃该投递条目
     * （影子注入表与磁盘基线<strong>不</strong>清除，玩家回到该区域时由权威声明/形状扫描重新投递）。
     */
    private static final ConcurrentHashMap<Long, Integer> ignoredApplyRetries = new ConcurrentHashMap<>();
    /** 单柱连续被原版忽略的上限（约 3s @20fps，足以跨过 tracking view 抖动）。 */
    private static final int MAX_IGNORED_RETRIES = 60;
    /** 被忽略重试的日志节流：每条最多每 N 次打一行（否则 100k 行写爆日志）。 */
    private static final int IGNORED_RETRY_LOG_EVERY = 200;

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

    /**
     * 两阶段光照：已过 INITIALIZE_LIGHT 的柱（空 DataLayer 已安装）。
     * 对齐原版生成金字塔：邻柱先到 INITIALIZE_LIGHT，中心柱才跑 LIGHT——
     * 这样中心柱传播时可写入邻柱空层，触发 onLightUpdate → 光桥下发。
     * key = DimensionKey 复合键。断连/卸载清除。
     */
    private static final java.util.Set<Long> lightInitialized = ConcurrentHashMap.newKeySet();
    /**
     * 齐套门控判据：该柱**曾经**跑完 INITIALIZE_LIGHT（空 DataLayer 已安装）。
     * <p>
     * 与 {@link #lightInitialized} 的区别是**单调**：后者在 {@code startLightBarrier}
     * 起跑 LIGHT 时被 {@code remove}（它表示「native 快照待 LIGHT 消费」这一过渡态），
     * 若拿它当门控判据，任何**已提升过**的邻柱都会永久表现为「已注入但未过
     * INITIALIZE_LIGHT」→ 只能等 {@code NEIGHBORHOOD_TIMEOUT_MS} 超时放行。
     * <p>
     * 清除点 = 卸载（{@code cancelChunkWork}）/ 断连（{@code onDisconnect}）。
     * <b>已知边界</b>：重注入（同柱新数据 REPLACE）不清除——因为 {@code initializeLightImmediately}
     * 被 {@link #lightInitialized} 幂等挡住时不会重装 DataLayer，单清本集合会让该柱永久停在
     * 「未过 INITIALIZE_LIGHT」并拖满超时。重注入路径的层重装需要单独设计，本次不动。
     */
    private static final java.util.Set<Long> lightInitPassed = ConcurrentHashMap.newKeySet();
    /**
     * 两阶段光照：已过 INITIALIZE_LIGHT 的柱的 native ProtoChunk（LIGHT 阶段复用）。
     * key = DimensionKey 复合键。断连/卸载清除。
     */
    private static final ConcurrentHashMap<Long, net.minecraft.world.level.chunk.ChunkAccess>
            nativeLightChunks = new ConcurrentHashMap<>();

    private static final AtomicBoolean consumeRunning = new AtomicBoolean(false);

    /** 原版 LIGHT future 请求的提交顺序锁；不持有该锁等待 future 或执行打包。 */
    static final Object LIGHT_ENGINE_MUTEX = new Object();


    /** miss 已请求集合（复合键；**仅**防对真实服务端的重复 pull；卸载后清除；断连清空）。
     *  不得用于挡「影子 → 真实客户端」交付——原版进范围必重发。 */
    private static final java.util.Set<Long> requestedMisses = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * 分母「应用区块」已记账柱。不得与 {@link #requestedMisses} 共用：hash miss 会先
     * {@code tryRequestMiss}，若共用则后续 inject/落地不再记，R1 applied 恒为 0。
     */
    private static final java.util.Set<Long> accountedIngress = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * 网络全量已入影子管线、尚未完成落地记账的柱。注入时登记，落地/断连清除。
     * 用于堵 generated→inflight 竞态窗口：tracking redeliver/materialize 在此窗口
     * 看到「已注入但无 accountedIngress」会把网络柱误 publish 成缓存全命中。
     */
    private static final java.util.Set<Long> networkInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** hash 全命中已记账柱（复合键）。同一柱磁盘命中后再收到 hash 会走内存命中，不得再加一次。 */
    private static final java.util.Set<Long> accountedCacheHits = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 光照命中/重算已记账柱（复合键）。邻柱 LIGHT_ONLY 补光会把同一片柱刷成千上万次。 */
    private static final java.util.Set<Long> accountedLights = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * 占位被真实数据顶替后、曾按空气算光的 8 邻（复合键）。
     * 只登记不立刻算光：同一邻柱被多个占位顶替时按 key 去重，拍头 drain 一次。
     * 登记时即置邻柱 {@code !isLightCorrect}——park/断连来不及 drain 时 R2 hash 命中
     * 仍会走 needRelight，不把过亮残差带进下一程。
     */
    private static final java.util.Set<Long> pendingPlaceholderNeighborRelight =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

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
    private static final ConcurrentHashMap<Long, java.util.concurrent.locks.ReentrantLock> chunkLocks =
            new ConcurrentHashMap<>();

    private static java.util.concurrent.locks.ReentrantLock chunkLock(ChunkPos pos) {
        return chunkLocks.computeIfAbsent(chunkPosKey(pos),
                k -> new java.util.concurrent.locks.ReentrantLock());
    }

    /**
     * 给影子 {@code ChunkMap.save} mixin 与 {@link #withChunkLock} 共用。
     * 必须成对；可重入（flush 序列化已持锁时再进 vanilla save 包装）。
     */
    public static void lockChunk(ChunkPos pos) {
        chunkLock(pos).lock();
    }

    public static void unlockChunk(ChunkPos pos) {
        chunkLock(pos).unlock();
    }

    /**
     * 与注入/hash 比对/apply/落盘序列化共用同一把 per-chunk 锁。
     * {@code injectChunk.replaceWithPacketData} 与 {@code ChunkSerializer.pack}
     * 必须互斥，否则 1.20.1 PalettedContainer ThreadingDetector 会崩影子端。
     * 原版 {@code ChunkMap.save} 也走这把锁（mixin），避免 flush 线程与
     * seedgen-main 卸载保存同时 {@code pack} 同一容器。
     */
    public static void withChunkLock(ChunkPos pos, Runnable action) {
        lockChunk(pos);
        try {
            action.run();
        } finally {
            unlockChunk(pos);
        }
    }

    public static <T> T withChunkLock(ChunkPos pos, java.util.function.Supplier<T> action) {
        lockChunk(pos);
        try {
            return action.get();
        } finally {
            unlockChunk(pos);
        }
    }

    /**
     * 非阻塞取锁：渲染线程 / flush 不得在 chunkLock 上无限等待。
     * 持锁方若在 setBlockState→getChunk managedBlock（NeoForge 流体邻柱），
     * 阻塞取锁会把 Render thread 冻死（es3 hang）。拿不到锁返回 empty 语义由调用方处理。
     */
    public static <T> java.util.Optional<T> tryWithChunkLock(ChunkPos pos,
            java.util.function.Supplier<T> action) {
        java.util.concurrent.locks.ReentrantLock lock = chunkLock(pos);
        if (!lock.tryLock()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.ofNullable(action.get());
        } finally {
            unlockChunk(pos);
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
        return withChunkLock(pos, () -> SeedGenChunkCodec.buildPacket(chunk, level));
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

    /** 客户端原版卸载后解除对真实服的 pull 防抖，允许再 compare（不挡本地 publish）。 */
    public static void clearRequestMiss(String dimension, ChunkPos pos) {
        if (dimension == null || pos == null) {
            return;
        }
        requestedMisses.remove(DimensionKey.key(dimension, pos.x, pos.z));
    }

    /**
     * 真实客户端是否仍持有该柱的影子全量落地凭据（apply 成功后写入，Forget/unload 时摘除）。
     * 仅用于 server_push 路径的「已落地则跳过重复整柱」；**不是** tracking 进边沿的交付门禁。
     */
    public static boolean hasClientApplyEpoch(String dimension, ChunkPos pos) {
        if (dimension == null || pos == null) {
            return false;
        }
        return shadowApplyEpochs.containsKey(DimensionKey.key(dimension, pos.x, pos.z));
    }

    /**
     * redeliver 防循环：本地复用（publishCached）已在 generated 队列或在途光屏障 →
     * 跳过重发。drainRedeliver 在客户端无落地凭据时每拍重发同柱，若重发持续 put
     * generated，会与 GENERATED 光任务的完成回传互相踩（旧超时回传被取代/队列抖动），
     * 并让「已在排队」的柱反复进 generated；此处短路已排队的复用投递。
     */
    public static boolean isLocalRequeueInFlight(String dimension, ChunkPos pos) {
        if (dimension == null || pos == null) {
            return false;
        }
        long key = DimensionKey.key(dimension, pos.x, pos.z);
        return generated.containsKey(key) || inflightLight.containsKey(key);
    }

    /**
     * 保活重连的会话边界：保留影子缓存基线，但允许同一柱再次发起 Compare + Pull。
     * 必须清 {@code shadowApplyEpochs}：那是上一 ClientChunkCache 的落地凭据。
     * 不清会让 materialize/redeliver 认为「客户端已有」，R2 只回放部分柱（实测 1529→775）。
     * 不得调用 {@link #onDisconnect()}，它会同时清空待处理工作和指标去重状态。
     */
    public static void resetRequestDedupForReconnect() {
        requestedMisses.clear();
        accountedIngress.clear();
        networkInFlight.clear();
        accountedCacheHits.clear();
        accountedLights.clear();
        shadowApplyEpochs.clear();
        fullApplyTraces.clear();
        ignoredApplyRetries.clear();
    }

    /**
     * 真客户端切维：上一 ClientChunkCache 已卸空，落地凭据与在途回传全部作废。
     * 影子注入表 / 落盘基线保留，供新维度会话 {@code publishCached} 重发。
     * <p>
     * {@link #onClientChunkUnloaded} 用 {@code Minecraft.level} 取维：ClientLevel 替换后
     * 旧世界 unload 会打到新维 key，R1 主世界 epoch 残留。返主后
     * {@code hasClientApplyEpoch} 仍为真，boot/sweep/redeliver 全部跳过（实测 1529→23）。
     */
    public static void onClientDimensionChanged() {
        ready.clear();
        pending.clear();
        pendingDeltas.clear();
        generated.clear();
        pendingLightUpdates.clear();
        inflightLight.clear();
        networkInFlight.clear();
        requestedMisses.clear();
        accountedIngress.clear();
        shadowApplyEpochs.clear();
        fullApplyTraces.clear();
        lightFollowUps.clear();
        ignoredApplyRetries.clear();
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
        if (!io.github.limuqy.mc.hassium.compat.mods.ForeignLightEngine.usesLightTaskWatermark(engine)) {
            // 外部光照引擎（Starlight / ScalableLux）从不填充 lightTasks，水位控制无意义；
            // 该柱随后以 RECOMPUTE（lit=false）提交，收敛由 isLightConverged 的
            // hasLightWork()（= 对方自己的 LightQueue）承担。
            return;
        }
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
     * 可复用引擎光：{@code isLightCorrect} 且引擎层已安装。
     * {@code isLightCorrect} 可能先于异步层安装；单独用它会打出空光整柱，
     * 把客户端已亮打回 {@code skyTop=0}（飞行黑块）。
     */
    static boolean isLightReusable(ShadowSeedServer server, ChunkPos pos,
                                   net.minecraft.world.level.chunk.LevelChunk chunk) {
        if (chunk == null || !chunk.isLightCorrect()) {
            return false;
        }
        if (server == null) {
            return true;
        }
        if (!server.isChunkLightComplete(pos, chunk)) {
            return false;
        }
        // 空 DataLayer（INITIALIZE_LIGHT 全 0）也会通过 isChunkLightComplete；
        // 必须再验引擎里确实有非 0 光，否则 REUSE 打出 skyTop=0 黑柱。
        return server.hasUsableEngineLight(pos, chunk);
    }

    /**
     * 空 REUSE 不得整柱交付（无论客户端是否已有该柱）：
     * 卸载后 redeliver 时 epoch 已清，按「已落地」门控会漏掉，出生点回访成片黑块。
     */
    static boolean shouldSkipEmptyReuseRepush(LightMetric metric, boolean lightReusable) {
        return metric == LightMetric.REUSE_CACHE && !lightReusable;
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
        if (!pendingPlaceholderNeighborRelight.isEmpty()) {
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
     * <p>
     * MetricsSemantics §2：stale=false 新增（SERVER_PUSH）；stale=true 过期（REMOTE_PULL
     * compare-pull FULL，或服务端 hash 不匹配重推）。
     *
     * @param staleOrFallback true = 影子副本 hash 与远端不一致的重推 / compare-pull FULL
     *                        （过期桶），false = 全新柱 / authoritative-full（新增桶）
     */
    static void accountVisibleNetworkIngress(String dimension, ChunkPos pos, boolean staleOrFallback) {
        if (pos == null) {
            return;
        }
        String resolved = dimension == null ? currentDimension() : dimension;
        long key = DimensionKey.key(resolved, pos.x, pos.z);
        // 同柱已按缓存全命中记账：不得再记网络全量（防跨路径双计）
        if (accountedCacheHits.contains(key)) {
            return;
        }
        if (!accountedIngress.add(key)) {
            return;
        }
        requestedMisses.add(key);
        networkInFlight.remove(key);
        io.github.limuqy.mc.hassium.metrics.NetworkStats.recordFullChunkRequests(
                1, io.github.limuqy.mc.hassium.metrics.NetworkStats.ESTIMATED_CHUNK_BYTES, staleOrFallback);
    }

    /**
     * 客户端实际落地的缓存全量柱按键去重，磁盘与内存复用不得重复记账。
     * <p>
     * MetricsSemantics §1 全命中锚点：UNCHANGED / 内存 hash 一致复用落地时调用。
     */
    /**
     * 服务端已断言「权威 hash == 本地基线」：解锁该柱的缓存全命中记账。
     * <p>
     * 仅由权威边沿消费端在「客户端已不持有该柱」时调用（见
     * {@code ChunkAuthorityClient.resolve}）：此时本次交付属于缓存再交付，必须计入全命中。
     * 首轮网络交付（{@code accountedIngress} 已记账、客户端仍持有）不得被改记成命中——
     * 那是 R1 双路径假命中红线。
     */
    public static void markAuthorityHashConfirmed(String dimension, ChunkPos pos) {
        if (dimension == null || pos == null) {
            return;
        }
        accountedIngress.remove(DimensionKey.key(dimension, pos.x, pos.z));
    }

    public static boolean accountCacheFullHit(String dimension, ChunkPos pos) {
        if (pos == null) {
            return false;
        }
        String resolved = dimension == null ? currentDimension() : dimension;
        long key = DimensionKey.key(resolved, pos.x, pos.z);
        // 同柱已按网络全量记账：不得再记缓存命中（R1 双路径假命中）
        if (accountedIngress.contains(key)) {
            return false;
        }
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
        return publishCachedChunk(dimension, pos, false, false);
    }

    /** OVD 环带回传：本地源全量服务，标记 renderOnly（不进权威缓存命中分母）。 */
    public static boolean publishOvdCachedChunk(String dimension, ChunkPos pos) {
        return publishCachedChunk(dimension, pos, false, true);
    }

    /**
     * @param localGeneration true = 本会话 vanilla worldgen 产物（门控开路径），
     *                        按 {@link TraceOrigin#LOCAL_GENERATION} 投递，不计缓存全命中
     * @param renderOnly      true = OVD 环带：view=ovd / 不计权威 landed
     */
    public static boolean publishCachedChunk(String dimension, ChunkPos pos,
                                             boolean localGeneration, boolean renderOnly) {
        if (pos == null) {
            return false;
        }
        if (!isEnabled()) {
            notePublishBlocked(renderOnly, "engineDisabledOrShadowFailed", pos);
            return false;
        }
        // 权威缓存复用仍避开在途网络全量；OVD 环带只走本地源、永不 pull，
        // 不得被 R1/权威 in-flight 位卡住（1.20.1 fabric R2 实测 publish 16 柱后全失败）。
        if (!renderOnly && networkInFlight.contains(
                DimensionKey.key(dimension == null ? currentDimension() : dimension, pos.x, pos.z))) {
            return false;
        }
        String resolved = dimension == null ? currentDimension() : dimension;
        // 切维竞态：客户端已是 TF、tracking/OVD 仍按 OVERWORLD publish → 脚下闪主世界柱。
        if (clientDimensionMismatch(resolved)) {
            notePublishBlocked(renderOnly, "clientDimensionMismatch", pos);
            return false;
        }
        ShadowSeedServer server = ShadowServerRegistry.getInstance().getOrCreate();
        if (server == null) {
            notePublishBlocked(renderOnly, "shadowServerNull", pos);
            return false;
        }
        net.minecraft.server.level.ServerLevel level = server.level(resolved);
        if (level == null) {
            notePublishBlocked(renderOnly, "levelNull", pos);
            return false;
        }
        net.minecraft.world.level.chunk.LevelChunk chunk = server.injectedChunk(resolved, pos.x, pos.z);
        // 空气空壳占位柱不得交付客户端：它是光照齐套的临时占位，不是真实区块数据。
        if (chunk != null && server.isPlaceholder(resolved, pos.x, pos.z)) {
            notePublishBlocked(renderOnly, "placeholderChunk", pos);
            return false;
        }
        TraceOrigin origin = TraceOrigin.SHADOW_MEMORY_CACHE;
        if (chunk == null) {
            if (scheduleAsyncDiskPublish(server, resolved, pos, localGeneration, renderOnly)) {
                return true;
            }
            // 无客户端执行器（冷启动）才允许同步读盘；配额耗尽时宁可 miss 走 pull，
            // 不得在主线程再堵 region 冷挂载。
            HassiumTaskExecutor executor = HassiumTaskExecutor.getClient();
            if (executor != null && executor.isRunning()) {
                return false;
            }
            chunk = server.loadFromDisk(resolved, pos);
            origin = TraceOrigin.SHADOW_DISK_CACHE;
            if (chunk != null) {
                server.injectLoadedChunk(resolved, pos, chunk);
            }
        }
        if (chunk == null) {
            return false;
        }
        if (localGeneration) {
            return submitGenerated(pos, chunk, level, false);
        }
        return submitPreLight(ShadowChunkSource.CACHE_SNAPSHOT, pos, chunk, level,
                traceOrigin(origin), renderOnly);
    }

    /** 内存 miss 后的在途异步读盘（按柱去重；配额/执行器不可用时返回 false 走同步兜底）。 */
    private static final java.util.Set<Long> DISK_PUBLISH_INFLIGHT =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 断连清理：丢弃在途异步读盘标记，避免下一会话同柱被误判为已在途。 */
    public static void clearDiskPublishInFlight() {
        DISK_PUBLISH_INFLIGHT.clear();
    }

    private static boolean scheduleAsyncDiskPublish(ShadowSeedServer server, String dimension,
                                                    ChunkPos pos, boolean localGeneration,
                                                    boolean renderOnly) {
        long key = DimensionKey.key(dimension, pos.x, pos.z);
        if (!DISK_PUBLISH_INFLIGHT.add(key)) {
            return true;
        }
        if (!ClientMainThreadBudget.tryAcquireCacheRead()) {
            DISK_PUBLISH_INFLIGHT.remove(key);
            return false;
        }
        HassiumTaskExecutor executor = HassiumTaskExecutor.getClient();
        if (executor == null || !executor.isRunning()) {
            ClientMainThreadBudget.refundCacheRead();
            DISK_PUBLISH_INFLIGHT.remove(key);
            return false;
        }
        server.loadFromDiskAsync(dimension, pos, loaded -> {
            DISK_PUBLISH_INFLIGHT.remove(key);
            try {
                if (clientDimensionMismatch(dimension)) {
                    return;
                }
                if (loaded == null) {
                    onDiskPublishMiss(dimension, pos, localGeneration, renderOnly);
                    return;
                }
                server.injectLoadedChunk(dimension, pos, loaded);
                net.minecraft.server.level.ServerLevel level = server.level(dimension);
                if (level == null) {
                    onDiskPublishMiss(dimension, pos, localGeneration, renderOnly);
                    return;
                }
                if (localGeneration) {
                    submitGenerated(pos, loaded, level, false);
                } else {
                    submitPreLight(ShadowChunkSource.CACHE_SNAPSHOT, pos, loaded, level,
                            TraceOrigin.SHADOW_DISK_CACHE, renderOnly);
                }
            } catch (Throwable t) {
                DebugLogger.debug(DebugLogger.LogType.CACHE,
                        "[SHADOW_PUBLISH] async disk apply failed ({}, {})", pos.x, pos.z, t);
                onDiskPublishMiss(dimension, pos, localGeneration, renderOnly);
            }
        });
        return true;
    }

    /**
     * 异步读盘落空：调用方已按「会交付」记账（authority NONE / UNCHANGED 不再 pull），
     * 此处补发权威 FULL；OVD / 本地生成不回退网络。
     */
    private static void onDiskPublishMiss(String dimension, ChunkPos pos,
                                          boolean localGeneration, boolean renderOnly) {
        DebugLogger.info(DebugLogger.LogType.NETWORK,
                "[SHADOW_PUBLISH] async disk miss ({}, {}) dim={} renderOnly={} localGen={}",
                pos.x, pos.z, dimension, renderOnly, localGeneration);
        if (renderOnly || localGeneration) {
            return;
        }
        io.github.limuqy.mc.hassium.network.ShadowPullClient
                .requestAuthoritativeFull(dimension, List.of(pos));
    }

    /** publish 被门禁挡住时的节流日志（OVD/权威共用；R2 ovdLoaded=0 归因用）。 */
    private static void notePublishBlocked(boolean renderOnly, String reason, ChunkPos pos) {
        long now = System.currentTimeMillis();
        if (now - lastPublishBlockedMs < 2_000L) {
            return;
        }
        lastPublishBlockedMs = now;
        ClientChunkPipeline pipeline = ClientChunkPipeline.getInstance();
        io.github.limuqy.mc.hassium.Constants.LOG.warn(
                "[SHADOW_PUBLISH] blocked reason={} renderOnly={} pos=({},{}) engine={} failed={} ready={} handshake={}",
                reason, renderOnly, pos.x, pos.z,
                HassiumConfigService.getInstance().isHassiumEngineEnabled(),
                pipeline.isShadowServerFailed(),
                pipeline.isShadowServerReady(),
                pipeline.isHassiumHandshakeDone());
    }

    private static volatile long lastPublishBlockedMs;

    public static long hashMemoryHitCount() {
        return hashMemoryHits.get();
    }

    /** 本会话已按网络全量记过账（SERVER_PUSH/REMOTE_PULL 落地或注入）。 */
    public static boolean wasNetworkIngress(String dimension, ChunkPos pos) {
        if (pos == null) {
            return false;
        }
        String resolved = dimension == null ? currentDimension() : dimension;
        long key = DimensionKey.key(resolved, pos.x, pos.z);
        if (accountedIngress.contains(key) || networkInFlight.contains(key) || hasClientApplyEpoch(resolved, pos)) {
            return true;
        }
        GenEntry queued = generated.get(key);
        if (queued != null && isNetworkOrigin(queued.traceOrigin)) {
            return true;
        }
        InflightLight inflight = inflightLight.get(key);
        return inflight != null && isNetworkOrigin(inflight.traceOrigin);
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
        // 本地生成已在 onChunkMaterialized / SeedGenExecutor 记 locallyGenerated；
        // 不得再计缓存全命中或网络全量（否则 R1 门禁把 SeedGen 误判成假缓存）。
        if (origin == TraceOrigin.LOCAL_GENERATION || origin == TraceOrigin.SECTION_DELTA || origin == null) {
            return;
        }
        // 区块加载分桶（MetricsSemantics §2）：新增与过期不互斥。
        //   REMOTE_PULL（compare-pull FULL）→ stale=true，展示时作为「过期」子集标注，
        //   同时计入「新增」（网络全量）。
        //   SERVER_PUSH → stale=false，仅计「新增」。
        if (origin == TraceOrigin.REMOTE_PULL) {
            accountVisibleNetworkIngress(dimension, pos, true);
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
     * 影子回传入队序号：数值越小越先 apply。光包仍 FIFO；区块在有脚下焦点时
     * 由 {@link JoinWorldFocus#chunkApplyPriority} 把切比雪夫叠到序号上。
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

    /**
     * 影子链路可用：引擎开启 && 握手完成（对端装了 Hassium）&& 影子未失败。
     * <p>
     * 未握手（含单人/局域网主机 memory、纯原版服）必须为 false——否则
     * {@code handleLevelChunkWithLight} 会 cancel 原版区块却无影子回填 → 虚空。
     */
    public static boolean isEnabled() {
        return HassiumConfigService.getInstance().isHassiumEngineEnabled()
                && ClientChunkPipeline.getInstance().isShadowEngineActive()
                && !ClientChunkPipeline.getInstance().isShadowServerFailed();
    }

    /** 原版区块可安全 cancel 并交给影子：还需影子实例已 ready（避免创建窗口虚空）。 */
    public static boolean shouldInterceptVanillaChunks() {
        return isEnabled() && ClientChunkPipeline.getInstance().isShadowServerReady();
    }

    /**
     * 单柱光照/打包失败不得关整台影子端。真服对 Hassium 客户端抑制原版区块包，
     * {@code failShadowServer} 之后切维（下界/末地/返主）会变成空 ClientChunkCache。
     */
    static boolean shouldFailShadowOnSingleColumnFailure() {
        return false;
    }

    private static void noteSingleColumnFailure(String message, Object... args) {
        io.github.limuqy.mc.hassium.Constants.LOG.warn(message, args);
        if (shouldFailShadowOnSingleColumnFailure()) {
            ShadowServerRegistry.getInstance().failShadowServer();
        }
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
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.getSingleplayerServer() != null) {
                return;
            }
        } catch (Throwable ignored) {
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
            // tryLock：渲染线程（ChunkAuthority→requestFull）不得在 chunkLock 上阻塞，
            // 否则 seedgen-main 持锁 managedBlock 时整客户端冻死（es3 hang）。
            Long computed = tryWithChunkLock(pos, () -> {
                long h = io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil
                        .combineSectionHashes(io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil
                                .computeSectionHashes(chunk));
                io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.put(dimension, pos, h);
                return h;
            }).orElse(null);
            localHash = computed;
        }
        if (localHash == null) {
            return new io.github.limuqy.mc.hassium.network.ShadowPullRequestC2SPacket.Entry(
                    pos.x, pos.z, 0L, List.of(), 0);
        }
        if (chunk == null) {
            return new io.github.limuqy.mc.hassium.network.ShadowPullRequestC2SPacket.Entry(
                    pos.x, pos.z, localHash, List.of(), 0);
        }
        final long hashForEntry = localHash;
        java.util.Optional<io.github.limuqy.mc.hassium.network.ShadowPullRequestC2SPacket.Entry> locked =
                tryWithChunkLock(pos, () -> {
                    io.github.limuqy.mc.hassium.network.sectiondelta.SectionDeltaSnapshot snapshot =
                            io.github.limuqy.mc.hassium.network.sectiondelta.SectionDeltaSnapshots
                                    .getOrCapture(dimension, pos, chunk);
                    long[] hashes = snapshot.sectionHashes();
                    List<Long> sectionHashes = new ArrayList<>(hashes.length);
                    for (long hash : hashes) {
                        sectionHashes.add(hash);
                    }
                    return new io.github.limuqy.mc.hassium.network.ShadowPullRequestC2SPacket.Entry(
                            pos.x, pos.z, hashForEntry, sectionHashes, snapshot.planes(), 0);
                });
        return locked.orElseGet(() ->
                new io.github.limuqy.mc.hassium.network.ShadowPullRequestC2SPacket.Entry(
                        pos.x, pos.z, hashForEntry, List.of(), 0));
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


    /** 客户端当前维度 id（{@code namespace:path}）。
     * 优先 {@code mc.level}；切维窗口 level 为空时用影子 tracking 维度，**不得**静默回落
     * OVERWORLD——否则 TF 等自定义维柱会按主世界键注入/回放（脚下出现主世界地形）。 */
    static String currentDimension() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.level != null) {
                String id = LevelCompat.getDimensionId(mc.level);
                if (id != null) {
                    return id;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            String tracked = ShadowTrackingSession.getInstance().currentDimension();
            if (tracked != null && !tracked.isEmpty()) {
                return tracked;
            }
        } catch (Throwable ignored) {
        }
        return DimensionKey.OVERWORLD;
    }

    /**
     * 仅当客户端已有 {@code ClientLevel} 时返回其维 id；否则 null。
     * publish 门禁用：与 {@link #currentDimension()} 不同，**绝不**用 tracking/OVERWORLD 填空——
     * 切维瞬间客户端已是 TF、tracking 仍主世界时，必须拒绝把主世界柱推进 TF 世界。
     */
    private static String clientLevelDimension() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.level != null) {
                return LevelCompat.getDimensionId(mc.level);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 发布门禁：客户端 Level 已就绪时，柱维必须与客户端维一致。 */
    private static boolean clientDimensionMismatch(String resolved) {
        String clientDim = clientLevelDimension();
        return clientDim != null && resolved != null && !clientDim.equals(resolved);
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
    /** 投递可渲染柱；区块追踪与邻域由影子端原版 ChunkMap 管理。
     *  必须使用调用方传入的 {@code dimension}（pull/push 响应已带维）；
     *  此前误用 {@link #currentDimension()} 在切维窗口会把 TF 柱按主世界键入表。 */
    public static void submitVisible(String dimension, ChunkPos pos,
                                     ClientboundLevelChunkWithLightPacket packet) {
        if (pos == null || packet == null || !isEnabled()) {
            return;
        }
        String activeDimension = dimension != null && !dimension.isEmpty()
                ? dimension
                : currentDimension();
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
        if (isNetworkOrigin(origin == null ? TraceOrigin.SERVER_PUSH : origin)) {
            networkInFlight.add(key);
        }
        TraceOrigin resolvedOrigin = origin == null ? TraceOrigin.SERVER_PUSH : origin;
        // 已有完整引擎光的柱（缓存命中 / relight 后）：跳过门控，直接进光屏障交付。
        // 门控只对需要重算光的柱有意义——已亮柱走门控会白等 3s 超时。
        // isLightCorrect 单独不够：层未安装时 REUSE 会打包空光。
        if (isLightReusable(server, pos, chunk)) {
            generated.put(key, new GenEntry(chunk, level, true, false, resolvedOrigin));
            pump();
            return;
        }
        // 两阶段光照第一阶段：立即跑 INITIALIZE_LIGHT（装空 DataLayer），
        // 对齐原版生成金字塔——邻柱先到 INITIALIZE_LIGHT，中心柱才跑 LIGHT。
        initializeLightImmediately(server, key, chunk, level);
        // 进齐套门控：等 3×3 邻域都过 INITIALIZE_LIGHT 后再跑 LIGHT 交付。
        LightNeighborhoodGate.enqueue(key, resolved, pos,
                new GateContext(null, chunk, level,
                        LightMetric.RECOMPUTE, false, resolvedOrigin));
        pump();
    }

    /**
     * 两阶段光照第一阶段：立即跑 INITIALIZE_LIGHT（装空 DataLayer）。
     * <p>
     * 对齐原版生成金字塔：邻柱先到 INITIALIZE_LIGHT（空层已安装），中心柱才跑 LIGHT——
     * 这样中心柱传播时可写入邻柱空层，触发 {@code onLightUpdate} → 光桥下发。
     * 幂等：已初始化的柱跳过。
     */
    static void initializeLightImmediately(ShadowSeedServer server, long key,
                                           LevelChunk chunk,
                                           net.minecraft.server.level.ServerLevel level) {
        if (lightInitialized.contains(key)) {
            return; // 已初始化：幂等
        }
        try {
            net.minecraft.world.level.chunk.ProtoChunk nativeChunk =
                    io.github.limuqy.mc.hassium.compat.ShadowServerCompat
                            .createNativeLightChunk(level, chunk, false);
            io.github.limuqy.mc.hassium.compat.ShadowServerCompat
                    .initializeNativeLight(level, nativeChunk)
                    .whenComplete((ignored, throwable) -> {
                        if (throwable != null) {
                            DebugLogger.warn(DebugLogger.LogType.ASYNC,
                                    "[SHADOW_LIGHT] INITIALIZE_LIGHT failed ({}, {})",
                                    DimensionKey.chunkXOf(key), DimensionKey.chunkZOf(key));
                        } else {
                            nativeLightChunks.put(key, nativeChunk);
                            lightInitialized.add(key);
                            lightInitPassed.add(key); // 齐套门控判据（单调，见字段注释）
                            DebugLogger.info(DebugLogger.LogType.CHUNK_APPLY,
                                    "[SHADOW_LIGHT] INITIALIZE_LIGHT done ({}, {})",
                                    DimensionKey.chunkXOf(key), DimensionKey.chunkZOf(key));
                        }
                    });
        } catch (Throwable failure) {
            DebugLogger.warn(DebugLogger.LogType.ASYNC,
                    "[SHADOW_LIGHT] INITIALIZE_LIGHT exception ({}, {})",
                    DimensionKey.chunkXOf(key), DimensionKey.chunkZOf(key), failure);
        }
    }

    /** 该柱是否已过 INITIALIZE_LIGHT（{@code lightInitialized} 的过渡态判据）。 */
    static boolean isLightInitialized(long key) {
        return lightInitialized.contains(key);
    }

    /**
     * 该柱是否**曾经**跑完 INITIALIZE_LIGHT（齐套门控判据；单调，见 {@link #lightInitPassed}）。
     */
    static boolean isLightInitPassed(long key) {
        return lightInitPassed.contains(key);
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
                packet = withChunkLock(pos, () -> SeedGenChunkCodec.buildPacket(chunk, level));
                offerReady(DimensionKey.key(dimension, pos.x, pos.z), pos, packet,
                        true, false, origin);
            });
        } catch (Throwable failure) {
            noteSingleColumnFailure("[SHADOW_CHUNK] publish native light failed ({}, {}) dim={}",
                    pos.x, pos.z, dimension, failure);
        }
    }


    /**
     * 自定义维度透传：绕过影子管线，主线程直接原版落地（与 Compare+Pull
     * 客户端摄入链同语义）。非缓存维度在此恢复原版行为。
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
        return submitPreLight(source, pos, chunk, level, traceOrigin, false);
    }

    public static boolean submitPreLight(ShadowChunkSource source,
                                         ChunkPos pos,
                                         net.minecraft.world.level.chunk.LevelChunk chunk,
                                         net.minecraft.server.level.ServerLevel level,
                                         TraceOrigin traceOrigin,
                                         boolean renderOnly) {
        if (source == null || !source.isLocalChunk() || pos == null || chunk == null || !isEnabled()) {
            return false;
        }
        String dimension = level != null ? ShadowSeedServer.dimensionId(level) : null;
        if (dimension == null || !DimensionKey.isCacheableDimension(dimension)) {
            return false;
        }
        // 光照缓存命中锚点：引擎光已收敛且层已安装才 REUSE，否则 RECOMPUTE。
        // 仅 isLightCorrect 会把空层当已亮，打出 skyTop=0 整柱。
        ShadowSeedServer reuseServer = ShadowServerRegistry.getInstance().get();
        boolean lightReuse = isLightReusable(reuseServer, pos, chunk);
        long key = DimensionKey.key(dimension, pos.x, pos.z);
        // 同柱已有网络来源（SERVER_PUSH/REMOTE_PULL）时禁止被 cache publish 覆盖来源——
        // 否则 R1 首进全量推送会被改写成 MEMORY_CACHE 假全命中。
        // OVD 环带例外：本地源必须真正回传，不能报成功却不交付。
        GenEntry queued = generated.get(key);
        if (queued != null && isNetworkOrigin(queued.traceOrigin) && !isNetworkOrigin(traceOrigin)) {
            if (!renderOnly) {
                return true;
            }
        }
        generated.put(key, new GenEntry(chunk, level, lightReuse, renderOnly, traceOrigin));
        pump();
        return true;
    }

    private static boolean isNetworkOrigin(TraceOrigin origin) {
        return origin == TraceOrigin.SERVER_PUSH || origin == TraceOrigin.REMOTE_PULL;
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
                int limit = Math.min(CONSUME_BATCH_LIMIT, room);
                // 脚下柱若只在 generated：pending 不得占满 24 槽把它挤掉。
                int pendingLimit = limit;
                if (JoinWorldFocus.findStandingKey(generated) != null
                        && JoinWorldFocus.findStandingKey(pending) == null
                        && pendingLimit > 0) {
                    pendingLimit = limit - 1;
                }
                List<Map.Entry<Long, PendingEntry>> batch = new ArrayList<>(Math.max(4, pendingLimit));
                JoinWorldFocus.fillDistanceFirst(pending, batch, pendingLimit);
                for (Map.Entry<Long, PendingEntry> e : batch) {
                    io.github.limuqy.mc.hassium.utils.ChunkFlowTiming.recordConsume(e.getKey());
                }
                // 本轮提交总量（pending+gen+delta+light）也以 CONSUME_BATCH_LIMIT 封顶：
                // 防止 gen/delta/light 把单轮任务量叠回 1000 阈值。
                int remaining = limit - batch.size();
                List<Map.Entry<Long, GenEntry>> genBatch = new ArrayList<>();
                JoinWorldFocus.fillDistanceFirst(generated, genBatch, remaining);
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
                    // 仍需扫描齐套队列：邻柱后到可能让等待中的柱齐套；超时条目强制降级
                    if (LightNeighborhoodGate.pendingCount() > 0) {
                        List<LightTask> gateTasks = new ArrayList<>();
                        pumpGateReady(server, gateTasks);
                        if (!gateTasks.isEmpty()) {
                            submitLightBatch(server, gateTasks);
                        }
                    }
                    return;
                }
                org.slf4j.LoggerFactory.getLogger("Hassium/ShadowDisk")
                        .debug("consumeLoop batch={} gen={} delta={} light={}",
                                batch.size(), genBatch.size(), deltaBatch.size(), lightBatch.size());
                List<LightTask> lightTasks = new ArrayList<>();
                // 先扫描齐套队列：邻柱后到可能让等待中的柱齐套
                pumpGateReady(server, lightTasks);
                drainPlaceholderNeighborRelight(server, lightTasks);
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
                            hashMatches = withChunkLock(pos, () ->
                                    diskHashMatches(dimension, existing, pos, remoteHash));
                        }
                    if (!hashKnown || hashMatches) {
                            boolean needRelight = !isLightReusable(server, pos, existing);
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
                            // 已有正确光的柱：跳过门控，直接进光屏障交付（复用缓存光）。
                            if (!needRelight) {
                                generated.put(e.getKey(), new GenEntry(existing, server.level(dimension),
                                        true, false,
                                        pendingEntry.traceOrigin() != null
                                                ? pendingEntry.traceOrigin()
                                                : traceOrigin(TraceOrigin.SHADOW_MEMORY_CACHE)));
                                continue;
                            }
                            // 需要重算光：先跑 INITIALIZE_LIGHT，再进齐套门控。
                            initializeLightImmediately(server, e.getKey(), existing, server.level(dimension));
                            LightNeighborhoodGate.enqueue(e.getKey(), dimension, pos,
                                    new GateContext(null, existing,
                                            server.level(dimension), LightMetric.RECOMPUTE,
                                            false,
                                            pendingEntry.traceOrigin() != null
                                                    ? pendingEntry.traceOrigin()
                                                    : traceOrigin(TraceOrigin.SHADOW_MEMORY_CACHE)));
                            continue;
                        }
                        // hash 已知且不匹配：影子副本过期，覆盖注入（走下方 injectChunk）。
                        staleRepush = true;
                    }
                    // R1 全量直推：禁 loadFromDisk。内存未命中则注入网络包。
                    if (!server.injectChunk(dimension, pos, pendingEntry.packet())) {
                        pending.remove(e.getKey(), pendingEntry);
                        noteSingleColumnFailure(
                                "[SHADOW_INJECT] inject failed ({}, {}) dim={}, skip column",
                                pos.x, pos.z, dimension);
                        continue;
                    }
                    SmokeChunkTrace.recordShadowInjected(dimension, pos);
                    if (shouldAccountServerPushAsApplied(requestedMisses.contains(e.getKey()))) {
                        requestedMisses.add(e.getKey());
                    }
                    accountVisibleNetworkIngress(dimension, pos, staleRepush);
                    LevelChunk injected = server.injectedChunk(dimension, pos.x, pos.z);
                    // 两阶段光照第一阶段：立即跑 INITIALIZE_LIGHT，再进齐套门控。
                    initializeLightImmediately(server, e.getKey(), injected, server.level(dimension));
                    LightNeighborhoodGate.enqueue(e.getKey(), dimension, pos,
                            new GateContext(pendingEntry, injected,
                                    server.level(dimension), LightMetric.RECOMPUTE, false,
                                    pendingEntry.traceOrigin()));
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
                    applied = withChunkLock(pos, () ->
                            server.applySectionDelta(work.dimension(), pos, work.entry()));
                    if (!applied) {
                        // 单柱 delta 失败不得关整台影子端（与 noteSingleColumnFailure 同语义）：
                        // 整端 fail 会连坐 OVD/缓存 publish（test1 R2 ovdLoaded=0 实证）。
                        // 该柱让给原版 tracking / 后续 compare-pull。
                        io.github.limuqy.mc.hassium.Constants.LOG.warn(
                                "[SHADOW_DELTA] Apply failed ({}, {}), yield to vanilla tracking",
                                pos.x, pos.z);
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
                        // 区块已变必须重算光。barrier 忙时不得静默丢弃——回队下轮再提交，
                        // 否则 R2「部分命中>0 但光照重算=0」。
                        if (!isChunkBarrierBusy(key)) {
                            lightTasks.add(new LightTask(key, LightSource.DELTA, null,
                                    baseline, server.level(work.dimension()), LightMetric.RECOMPUTE,
                                    false, traceOrigin(TraceOrigin.SECTION_DELTA)));
                        } else {
                            pendingDeltas.put(key, work);
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
                    case GATE:
                        break; // 门控条目已由 tryPromote 条件移除，无需再移除队列
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
                    abortLight(t.key, ex);
                }
            }
        }
    }

    /**
     * 影子区块光照第二阶段：只跑 LIGHT（传播），复用第一阶段已初始化的 nativeChunk。
     * <p>
     * 两阶段对齐原版生成金字塔：第一阶段（{@link #initializeLightImmediately}）在注入时
     * 立即跑 INITIALIZE_LIGHT 装空 DataLayer；本阶段等 3×3 邻域都过 INITIALIZE_LIGHT 后
     * 才跑 LIGHT——这样传播可写入邻柱空层，触发 onLightUpdate → 光桥下发。
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
            // 复用第一阶段已初始化的 nativeChunk；未初始化则回退到完整两阶段
            net.minecraft.world.level.chunk.ChunkAccess nativeChunk =
                    nativeLightChunks.remove(t.key);
            if (nativeChunk == null) {
                // 回退：未过 INITIALIZE_LIGHT（超时降级路径），完整两阶段
                inf.nativeChunk = io.github.limuqy.mc.hassium.compat.ShadowServerCompat
                        .createNativeLightChunk(level, t.chunk,
                                lightChunkHasExistingLight(t.metric == LightMetric.REUSE_CACHE));
                io.github.limuqy.mc.hassium.compat.ShadowServerCompat
                        .initializeNativeLight(level, inf.nativeChunk)
                        .thenCompose(ignored -> io.github.limuqy.mc.hassium.compat.ShadowServerCompat
                                .completeNativeLight(level, inf.nativeChunk))
                        .whenComplete((ignored, throwable) -> {
                            if (throwable != null) {
                                abortLight(t.key, throwable);
                            } else {
                                completeLight(inf, true);
                            }
                        });
            } else {
                // 正常路径：只跑 LIGHT（INITIALIZE_LIGHT 已在第一阶段完成）
                inf.nativeChunk = nativeChunk;
                lightInitialized.remove(t.key);
                io.github.limuqy.mc.hassium.compat.ShadowServerCompat
                        .completeNativeLight(level, nativeChunk)
                        .whenComplete((ignored, throwable) -> {
                            if (throwable != null) {
                                abortLight(t.key, throwable);
                            } else {
                                completeLight(inf, true);
                            }
                        });
            }
        } catch (Throwable failure) {
            abortLight(t.key, failure);
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
    /** LIGHT 未完成时退出该柱光照，不广播 partial；其它柱继续。 */
    private static void abortLight(long key, Throwable cause) {
        inflightLight.remove(key);
        pendingLightUpdates.remove(key);
        noteSingleColumnFailure(
                "[SHADOW_LIGHT] abort ({}, {}) dim={}",
                DimensionKey.chunkXOf(key), DimensionKey.chunkZOf(key),
                DimensionKey.dimensionOf(key), cause);
    }

    /** 原版 LIGHT future 完成后的唯一完成收口。 */
    private static void finishLight(LightTask task, boolean converged) {
        if (!isEnabled()) {
            return;
        }
        ChunkPos pos = new ChunkPos(DimensionKey.chunkXOf(task.key), DimensionKey.chunkZOf(task.key));
        ShadowSeedServer server = ShadowServerRegistry.getInstance().get();
        String dimension = DimensionKey.dimensionOf(task.key);
        if (isSuperseded(task)) {
            // 更新工作可能走 shouldSkipUnchangedRepush（alreadyShadowApplied + lightCorrect）
            // 而不再整柱重推——若此处直接 return，客户端会停在 standing 首包欠光
            //（skyTop=0），引擎已亮也送不出去。仍下发当前引擎光包兜底。
            if (server != null && task.chunk != null && task.level != null) {
                try {
                    pushLightReady(pos, task.level, task.chunk, true, null);
                    DebugLogger.info(DebugLogger.LogType.CHUNK_APPLY,
                            "[SHADOW_LIGHT] Superseded finish still pushed light ({}, {})",
                            pos.x, pos.z);
                } catch (Throwable lightPushFailure) {
                    DebugLogger.warn(DebugLogger.LogType.ASYNC,
                            "[SHADOW_LIGHT] Superseded light push failed ({}, {})",
                            pos.x, pos.z);
                }
            }
            if (server != null && task.chunk != null) {
                server.persistAfterClientLightPush(task.chunk, converged);
            }
            return;
        }
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
            } else if (shouldSkipEmptyReuseRepush(task.metric,
                    isLightReusable(server, pos, task.chunk))) {
                // 空 REUSE 一律改重算：卸载后 epoch 已清，不能只护「已落地」。
                DebugLogger.info(DebugLogger.LogType.CHUNK_APPLY,
                        "[SHADOW_LIGHT] Skip empty-reuse pack ({}, {}): requeue RECOMPUTE",
                        pos.x, pos.z);
                generated.put(task.key, new GenEntry(task.chunk, task.level, false,
                        task.renderOnly, task.traceOrigin));
                pump();
            } else {
                // lightChunk future 只保证「本批 task 跑完」，不保证 sky/block 层非空
                // （邻柱未齐 / 占位未过引擎时 POST_UPDATE 仍 setLightCorrect=true）。
                // 打包前必须验引擎非 0 光；否则 empty-mask 客户端显式置 0 = 黑柱。
                boolean lightUsable = isLightReusable(server, pos, task.chunk);
                if (!lightUsable && requeueLightFollowUp(task.key)) {
                    DebugLogger.info(DebugLogger.LogType.CHUNK_APPLY,
                            "[SHADOW_LIGHT] Empty engine light after {} ({}, {}): requeue before pack",
                            task.metric, pos.x, pos.z);
                    generated.put(task.key, new GenEntry(task.chunk, task.level, false,
                            task.renderOnly, task.traceOrigin));
                    pump();
                } else {
                    if (lightUsable) {
                        lightFollowUps.remove(task.key);
                    }
                    pushReady(task.key, task.chunk, task.level, converged, task.renderOnly,
                            task.traceOrigin);
                }
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

    /** 每柱补光重算上限（防「算完仍空」死循环）。 */
    private static final int MAX_LIGHT_FOLLOW_UPS = 2;
    private static final ConcurrentHashMap<Long, Integer> lightFollowUps = new ConcurrentHashMap<>();

    private static boolean requeueLightFollowUp(long key) {
        int n = lightFollowUps.merge(key, 1, Integer::sum);
        if (n > MAX_LIGHT_FOLLOW_UPS) {
            lightFollowUps.remove(key);
            return false;
        }
        return true;
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
        lightFollowUps.remove(key);
        lightInitialized.remove(key);
        lightInitPassed.remove(key);
        nativeLightChunks.remove(key);
        LightNeighborhoodGate.cancel(key);
        discardLightMask(key);
        DebugLogger.info(DebugLogger.LogType.ASYNC,
                "[SHADOW_LIGHT] Cancelled work before unload ({}, {})",
                DimensionKey.chunkXOf(key), DimensionKey.chunkZOf(key));
    }

    /** LIGHT future 超时只丢该柱，不广播 partial，也不关整台影子端。 */
    private static void sweepLightTimeouts() {
        long now = System.currentTimeMillis();
        for (InflightLight inf : inflightLight.values()) {
            if (now >= inf.deadlineMs && inflightLight.remove(inf.key, inf)) {
                noteSingleColumnFailure(
                        "[SHADOW_LIGHT] Light timeout ({}ms) ({}, {}) dim={}",
                        CONVERGENCE_WAIT_TIMEOUT_MS,
                        DimensionKey.chunkXOf(inf.key), DimensionKey.chunkZOf(inf.key),
                        DimensionKey.dimensionOf(inf.key));
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
        pushReady(key, chunk, level, converged, renderOnly, traceOrigin, false);
    }

    private static void pushReady(long key, net.minecraft.world.level.chunk.LevelChunk chunk,
                                  net.minecraft.server.level.ServerLevel level, boolean converged,
                                  boolean renderOnly, TraceOrigin traceOrigin,
                                  boolean standingPreview) {
        ChunkPos pos = chunk.getPos();
        // P1（T7）：buildPacket 读注入 chunk section 容器（extractChunkData →
        // LevelChunkSection.write → PalettedContainer.acquire）——与 hash 比对线程
        // （chunkHashOf / computeSectionHashes）同 chunk 锁互斥，消除 1.21.11
        // ThreadingDetector 崩溃（T7 线程转储：consumeLoop pushReady 打包 vs hash 线程）。
        runBuildOnShadowMain(pos, () -> {
            ClientboundLevelChunkWithLightPacket packet;
            packet = withChunkLock(pos, () -> SeedGenChunkCodec.buildPacket(chunk, level));
            offerReady(key, pos, packet, converged, renderOnly, traceOrigin, standingPreview);
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
     * 入 ready 队列。第一柱立刻回传用 SKIP_IF_PRESENT，不盖已经入队的带光包；
     * 光屏障完成后仍 REPLACE。
     */
    private static void offerReady(long key, ChunkPos pos, ClientboundLevelChunkWithLightPacket packet,
                                   boolean converged, boolean renderOnly, TraceOrigin traceOrigin) {
        offerReady(key, pos, packet, converged, renderOnly, traceOrigin, false);
    }

    private static void offerReady(long key, ChunkPos pos, ClientboundLevelChunkWithLightPacket packet,
                                   boolean converged, boolean renderOnly, TraceOrigin traceOrigin,
                                   boolean standingPreview) {
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
                JoinWorldFocus.chunkApplyPriority(pos.x, pos.z, fifoApplyPriority()),
                standingPreview
                        ? KeyedPriorityQueue.OfferPolicy.SKIP_IF_PRESENT
                        : KeyedPriorityQueue.OfferPolicy.REPLACE);
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
        // 不得断言「空 section」（vanilla 记成 emptyYMask → 客户端显式置 0 = 黑/灭灯），
        // 掩码一律收敛为「线上确有该层光」且「开天格全 15」的 section。
        // 这里必须覆盖两个来源：work 声明的变化 section（{@code work.emptySkyMask()} 会把
        // 「算空的」section 也声明进来）以及 **work == null 的全柱请求**（superseded 收口路径；
        // 旧代码只替换 sky 一侧、blockMask 留 null → 构造器对整个 block 层逐 section 断言，
        // 空 block section 全被显式置 0 = 洞内灭灯）。只看 seed 侧 epoch 也会漏：epoch 会在
        // 客户端卸载时被摘掉，而回传只发 light-only 包（不进 apply 计数），客户端可能仍持有该柱。
        if (chunk != null) {
            skyMask = SeedGenChunkCodec.wireLightMask(level.getLightEngine(),
                    chunk.getPos(), LightLayer.SKY);
            blockMask = SeedGenChunkCodec.wireLightMask(level.getLightEngine(),
                    chunk.getPos(), LightLayer.BLOCK);
            if (skyMask.isEmpty() && blockMask.isEmpty()) {
                noteLightAssertSuppressed(pos);
                return;
            }
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
     * 帧尾（MixinClientTick，渲染前）：区块按脚下切比雪夫优先，光包仍 FIFO。
     * JoinBoost 两段消费：先 chunk 再光。加载屏只 apply 脚下 3×3。
     * 区块包入队时丢掉该柱旧光。消费只受时间预算约束。
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
        JoinWorldFocus.updateFromClient();
        // 黑块判定复检（1 帧后 post-apply 真值）：光包探针即时读数是光队列 flush 前的旧值
        //（handleLightUpdatePacket 只入队），判定状态只由复检写入（handoff §7.1 假阳性）。
        ClientChunkHandler.runProbeRecheck(Minecraft.getInstance() == null
                ? null : Minecraft.getInstance().level);
        ShadowSeedServer server = ShadowServerRegistry.getInstance().get();
        if (server != null && server.isLightConverged()) {
            server.confirmLightsCorrectIfConverged();
        }
        ShadowLightProbe.onEngineTick(); // T3 探针：引擎终态周期快照（debug.lightVerify 门控）
        sweepLightTimeouts(); // per-chunk 光屏障 5s 超时兜底（主扫描点；低帧率由消费轮顶兜底）
        // 齐套门控扫描：邻柱后到 / 超时降级。无新工作时 consumeLoop 不会被 pump，
        // 门控条目需要由帧尾 drain 主动扫描，否则永久等待。
        if (server != null && LightNeighborhoodGate.pendingCount() > 0) {
            List<LightTask> gateTasks = new ArrayList<>();
            pumpGateReady(server, gateTasks);
            if (!gateTasks.isEmpty()) {
                submitLightBatch(server, gateTasks);
            }
        }
        boolean joinBoost = ClientMainThreadBudget.isJoinBoostActive();
        if (!joinBoost) {
            drainLightMasks(deadlineNs, false, false, 0);
        }
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc != null ? mc.getConnection() : null;
        if (connection == null) {
            drainLightMasks(deadlineNs, false, false, 0); // 断连：与原先一样清空 lightUpdates
            ready.clear();
            ignoredApplyRetries.clear();
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
        List<KeyedPriorityQueue.Entry<ReadyItem>> deferredFarChunks = new ArrayList<>();
        boolean forceOne = true;
        boolean chunkPassDone = !joinBoost;
        int chunksAppliedThisFrame = 0;
        boolean loadingScreenVisible = ClientLoadingScreenCompat.isVisible();
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
            if (isChunk) {
                ChunkPos chunkPos = new ChunkPos(entry.key().posLong());
                if (JoinWorldFocus.shouldDeferFarChunk(chunkPos.x, chunkPos.z, loadingScreenVisible)) {
                    deferredFarChunks.add(entry);
                    continue;
                }
            }
            if (isLight && !shouldApplyLightThisFrame(joinBoost, chunkWaiting, chunksAppliedThisFrame)) {
                deferredLights.add(entry);
                continue;
            }
            boolean releaseEntry = true;
            try {
                if (isChunk) {
                    forceOne = false;
                    chunksAppliedThisFrame++;
                    long applyStartNs = System.nanoTime();
                    releaseEntry = applyReadyChunk(mc, connection, entry, item);
                    io.github.limuqy.mc.hassium.utils.ChunkFlowTiming.recordApplyWall(
                            System.nanoTime() - applyStartNs, ready.size());
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
        for (KeyedPriorityQueue.Entry<ReadyItem> far : deferredFarChunks) {
            ready.reoffer(far, far.priority());
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
        ClientChunkHandler.logShadowChunkApplyEvent("shadow_attempt", chunkPos, item.renderOnly(), item.traceOrigin());
        ClientChunkPipeline pipeline = ClientChunkPipeline.getInstance();
        pipeline.setApplyInProgress(true);
        try {
            connection.handleLevelChunkWithLight(item.chunkPacket);
        } finally {
            pipeline.setApplyInProgress(false);
        }
        if (hasClientChunk(mc, chunkX, chunkZ)) {
            ignoredApplyRetries.remove(chunkKey);
            ClientChunkHandler.logShadowChunkApplyEvent("shadow_applied", chunkPos, item.renderOnly(), item.traceOrigin());
            shadowApplyEpochs.put(chunkKey, shadowApplyEpoch.incrementAndGet());
            recordFullApplyTrace(chunkKey, item.renderOnly(), item.traceOrigin());
            SmokeChunkTrace.recordClientApplied(entry.key().dimension(), chunkPos);
            io.github.limuqy.mc.hassium.metrics.NetworkStats.recordChunkApplied(chunkX, chunkZ);
            accountAuthoritativeLanded(entry.key().dimension(), chunkPos, item.traceOrigin());
            io.github.limuqy.mc.hassium.cache.client.ClientMainThreadBudget.noteChunkApplyActivity();
            // 与原版对齐：apply 后标 dirty 触发 mesh 重建。isApplyInProgress 期间
            // 原版 handler 可能跳过 dirty 标记，导致柱进缓存不进渲染队列（虚空）。
            if (mc.level != null) {
                ClientChunkHandler.markChunkSectionsDirty(mc.level, chunkX, chunkZ);
            }
            ClientChunkHandler.probeChunkState(chunkPos, mc.level, "shadow");
            return true;
        }
        ClientChunkHandler.logShadowChunkApplyEvent("shadow_ignored", chunkPos, false, item.traceOrigin());
        // 原版 tracking view 外的柱不可自愈（见 ignoredApplyRetries 注释）：有限重试后放弃投递，
        // 否则整柱在 ready 里每帧打转，渲染线程被日志+无效 apply 吃满。影子表/磁盘基线保留。
        int attempts = ignoredApplyRetries.merge(chunkKey, 1, Integer::sum);
        if (attempts >= MAX_IGNORED_RETRIES) {
            ignoredApplyRetries.remove(chunkKey);
            DebugLogger.info(DebugLogger.LogType.CHUNK_APPLY,
                    "[SHADOW_CHUNK] drop authoritative chunk ({}, {}) outside vanilla view range "
                            + "after {} attempts", chunkX, chunkZ, attempts);
            return true;
        }
        if (attempts == 1 || attempts % IGNORED_RETRY_LOG_EVERY == 0) {
            DebugLogger.info(DebugLogger.LogType.CHUNK_APPLY,
                    "[SHADOW_CHUNK] Vanilla ignored authoritative chunk ({}, {}) — retrying next frame "
                            + "(attempt {}/{})", chunkX, chunkZ, attempts, MAX_IGNORED_RETRIES);
        }
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
            // 客户端已持有该柱：只在影子光照引擎**无在途工作**时回传。重算/邻柱收紧会把该柱
            // 层先打成空层再逐步传播（实测客户端依次收到 0 → 3 → 8 …），中间态写进客户端就是
            // 「已亮柱被打黑」，且不会自愈：整柱包对已落地柱被抑制（hasClientApplyEpoch），
            // 客户端只会继续收后续中间态。此处不清掩码 → 收敛后的那一帧打包的就是收敛值。
            // <p>
            // 判据只读引擎队列是否为空（ShadowSeedServer#isLightConverged(ServerLevel)）：
            // 不读引擎数据层（与光照 worker 并发读 fastutil 会自旋），不排新光任务
            // （推高 lightTasks 水位 → 主线程 injectChunk 5s 忙等 → 整卡死，2026-09-16 实测）。
            if (hasClientApplyEpoch(DimensionKey.dimensionOf(key), pos)
                    && !server.isLightConverged(level)) {
                noteBridgeDeferHeld(pos);
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
            // 两阶段光照的**中间态**（phase-1 INITIALIZE_LIGHT 刚把该柱层覆盖成全 0、
            // phase-2 LIGHT 还没填回；或 phase-2 在途）：此时打包会把「还没算的空层」
            // 以 emptyYMask 下发，客户端 readSectionList 显式写 new DataLayer() = 已亮柱
            // 被打黑（2026-09-16 飞行黑块主因：桥包是当时唯一产光路径）。
            // 本帧整柱不发，客户端保留旧光；屏障完成收口 finishLight 必带真光重发。
            // <p>
            // 本判据只用这两个 map（不读引擎、不排新光任务）。**不得**再往上加引擎读或新任务：
            // 打包时 ClientboundLightUpdatePacket 构造器本身会读引擎数据层（已上线、实测未卡死），
            // 但 2026-09-16 的整卡死实证是「判严 isLightReusable → 多排光屏障 → lightTasks 越水位 →
            // 主线程 injectChunk 5s 忙等」——任何抬高引擎负载的改动都会复现。
            if (isLightMidCompute(key)) {
                noteMidComputeWithheld(pos);
                continue;
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
     * 该柱是否处于两阶段光照的中间态：phase-1 已装空层（{@code nativeLightChunks} 持有
     * nativeChunk，等齐套门控）或 phase-2 在途（{@code inflightLight}）。这两个窗口里引擎
     * 层是占位（全 0），不是算出来的结果——光桥此时打包会把空层打成 emptyYMask 下发。
     */
    private static boolean isLightMidCompute(long key) {
        return nativeLightChunks.containsKey(key) || inflightLight.containsKey(key);
    }

    /** 中间态扣包计数与节流日志（诊断：确认护栏是否生效）。 */
    private static final java.util.concurrent.atomic.AtomicLong withheldMidComputeLights =
            new java.util.concurrent.atomic.AtomicLong();
    private static volatile long lastMidComputeWithholdLogMs;

    private static void noteMidComputeWithheld(ChunkPos pos) {
        long total = withheldMidComputeLights.incrementAndGet();
        long now = System.currentTimeMillis();
        if (now - lastMidComputeWithholdLogMs < 1_000L) {
            return;
        }
        lastMidComputeWithholdLogMs = now;
        DebugLogger.info(DebugLogger.LogType.CHUNK_APPLY,
                "[SHADOW_LIGHT] Withheld mid-compute light update ({}, {}) total={}",
                pos.x, pos.z, total);
    }

    /** 光桥「等引擎收敛再回传」的推迟计数与节流日志（诊断：确认护栏生效）。 */
    private static final java.util.concurrent.atomic.AtomicLong deferredHeldLights =
            new java.util.concurrent.atomic.AtomicLong();
    private static volatile long lastDeferLogMs;

    private static void noteBridgeDeferHeld(ChunkPos pos) {
        long total = deferredHeldLights.incrementAndGet();
        long now = System.currentTimeMillis();
        if (now - lastDeferLogMs < 1_000L) {
            return;
        }
        lastDeferLogMs = now;
        DebugLogger.info(DebugLogger.LogType.CHUNK_APPLY,
                "[SHADOW_LIGHT] Deferred light update for held chunk ({}, {}) total={}",
                pos.x, pos.z, total);
    }

    /** 光桥「掩码收窄后无可下发内容」的扣包计数与节流日志（诊断：确认护栏生效）。 */
    private static final java.util.concurrent.atomic.AtomicLong suppressedLightAssertions =
            new java.util.concurrent.atomic.AtomicLong();
    private static volatile long lastSuppressedAssertLogMs;

    private static void noteLightAssertSuppressed(ChunkPos pos) {
        long total = suppressedLightAssertions.incrementAndGet();
        long now = System.currentTimeMillis();
        if (now - lastSuppressedAssertLogMs < 1_000L) {
            return;
        }
        lastSuppressedAssertLogMs = now;
        DebugLogger.info(DebugLogger.LogType.CHUNK_APPLY,
                "[SHADOW_LIGHT] Suppressed light assert (empty/partial) ({}, {}) total={}",
                pos.x, pos.z, total);
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
    /**
     * 客户端原版 unload 钩子：**只**作废光桥凭据（epoch / 未发 light 掩码依赖）。
     * <p>
     * 原版对齐：客户端卸载 ≠ 影子服务端卸载。影子注入表的回收只跟影子 tracking /
     * {@code RECLAIM_GRACE_MS} 硬编码宽限走；这里不得 {@code unloadChunk} 拆表，也不得登记
     * 「已请求」防抖——否则重进范围无法再交付，形成永久洞。
     */
    public static void onClientChunkUnloaded(ChunkPos pos) {
        onClientChunkUnloaded(pos, currentDimension());
    }

    /** {@code dimension} 必须是正在 unload 的 ClientLevel，不能读当前 {@code Minecraft.level}。 */
    public static void onClientChunkUnloaded(ChunkPos pos, String dimension) {
        if (pos == null) {
            return;
        }
        String dim = dimension != null ? dimension : currentDimension();
        long chunkKey = DimensionKey.key(dim, pos.x, pos.z);
        Long removedEpoch = shadowApplyEpochs.remove(chunkKey);
        fullApplyTraces.remove(chunkKey);
        lightFollowUps.remove(chunkKey);
        // 黑块探针：该柱已不可见，「仍黑」统计不得再包含它（「曾亮」标记保留，回程变黑要算回归）。
        ClientChunkHandler.onProbeChunkUnloaded(pos);
        // 允许对真实服再 compare（卸载后基线可能已过期）；不挡本地 publish
        requestedMisses.remove(chunkKey);
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
        lightInitialized.clear();
        lightInitPassed.clear();
        nativeLightChunks.clear();
        pendingPlaceholderNeighborRelight.clear();
        LightNeighborhoodGate.clear();
        requestedMisses.clear();
        accountedIngress.clear();
        networkInFlight.clear();
        accountedCacheHits.clear();
        accountedLights.clear();
        resetHashClassify();
        shadowApplyEpochs.clear();
        fullApplyTraces.clear();
        ignoredApplyRetries.clear();
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
        // 统计归因不得依赖 CHUNK_APPLY 日志开关；日志关时 strip 成 null 会让落地记账整段跳过。
        return origin;
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
        LIGHT_ONLY,
        /** 齐套门控 promote（{@link LightNeighborhoodGate} → awaiting 表）。条目已由门控移除，submitLightBatch 跳过队列条件移除。 */
        GATE
    }

    /** 光照统计口径：REUSE_CACHE = 命中点已记 shadow reuse（跳过预览）；
     *  RECOMPUTE = 光屏障完成后记 miss + 重算耗时（PENDING/GENERATED 提交隔离预览）。
     *  LIGHT_ONLY 不记柱级 miss（邻柱补光会把次数刷到数万）。 */
    enum LightMetric {
        REUSE_CACHE,
        RECOMPUTE
    }

    /**
     * 齐套门控上下文：enqueue 时暂存 LightTask 构建所需字段，tryPromote 后还原为 LightTask。
     * source 固定为 {@link LightSource#GATE}——门控条目已由 tryPromote 条件移除，
     * submitLightBatch 跳过队列条件移除（否则 remove 恒失败、任务被静默丢弃）。
     */
    private record GateContext(Object token,
                               net.minecraft.world.level.chunk.LevelChunk chunk,
                               net.minecraft.server.level.ServerLevel level,
                               LightMetric metric, boolean renderOnly, TraceOrigin traceOrigin) {
        LightTask toLightTask(long key) {
            return new LightTask(key, LightSource.GATE, token, chunk, level, metric, renderOnly, traceOrigin);
        }
    }

    /**
     * 占位被真实数据顶替：登记 8 邻中「已注入、非占位、isLightCorrect」的柱。
     * 不在此同步算光——拍头 {@link #drainPlaceholderNeighborRelight} 合并去重，避免爬坡期
     * 一次占位顶替放大成 8 次同步整柱 LIGHT。
     */
    static void notePlaceholderReplaced(ShadowSeedServer server, String dimension, ChunkPos pos) {
        if (server == null || dimension == null || pos == null) {
            return;
        }
        int registered = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int nx = pos.x + dx;
                int nz = pos.z + dz;
                LevelChunk neighbor = server.injectedChunk(dimension, nx, nz);
                if (neighbor == null || server.isPlaceholder(dimension, nx, nz)) {
                    continue;
                }
                if (!neighbor.isLightCorrect()) {
                    continue; // 本就要重算（已在门控/屏障路径）
                }
                long nKey = DimensionKey.key(dimension, nx, nz);
                neighbor.setLightCorrect(false);
                io.github.limuqy.mc.hassium.storage.ShadowStorageHashes
                        .markLightDirty(dimension, new ChunkPos(nx, nz));
                pendingPlaceholderNeighborRelight.add(nKey);
                registered++;
            }
        }
        if (registered > 0) {
            DebugLogger.info(DebugLogger.LogType.CHUNK_APPLY,
                    "[SHADOW_PLACEHOLDER] Replaced ({}, {}) dim={} registered {} neighbors for relight",
                    pos.x, pos.z, dimension, registered);
            pump();
        }
    }

    /** park：丢掉未 drain 的邻柱重算登记（邻柱已标 !isLightCorrect，R2 仍会续算）。 */
    static void clearPendingPlaceholderNeighborRelight() {
        pendingPlaceholderNeighborRelight.clear();
    }

    /**
     * 拍头消化占位顶替邻柱：清光 + 同批整柱重算（不经过齐套门控——邻柱自身 3×3
     * 已在各自路径齐套过，这里只修「按空气算出的过亮」残差）。
     * 已有 pending/generated 的柱不重复提交；仅光屏障在途则留队下轮再试。
     */
    private static void drainPlaceholderNeighborRelight(ShadowSeedServer server,
                                                        List<LightTask> lightTasks) {
        if (server == null || pendingPlaceholderNeighborRelight.isEmpty()) {
            return;
        }
        for (Long key : java.util.List.copyOf(pendingPlaceholderNeighborRelight)) {
            if (!pendingPlaceholderNeighborRelight.remove(key)) {
                continue;
            }
            if (hasQueuedBlockWork(key)) {
                continue; // pending/generated 会走完整算光
            }
            if (inflightLight.containsKey(key)) {
                pendingPlaceholderNeighborRelight.add(key); // 屏障在途：下轮再试
                continue;
            }
            String dimension = DimensionKey.dimensionOf(key);
            if (dimension == null) {
                continue;
            }
            ChunkPos pos = new ChunkPos(DimensionKey.chunkXOf(key), DimensionKey.chunkZOf(key));
            LevelChunk neighbor = server.injectedChunk(dimension, pos.x, pos.z);
            if (neighbor == null || server.isPlaceholder(dimension, pos.x, pos.z)) {
                continue;
            }
            net.minecraft.server.level.ServerLevel level = server.level(dimension);
            if (level == null) {
                continue;
            }
            server.forceNeighborLightReset(pos, neighbor);
            lightTasks.add(new LightTask(key, LightSource.GATE, null, neighbor, level,
                    LightMetric.RECOMPUTE, false,
                    traceOrigin(TraceOrigin.SHADOW_MEMORY_CACHE)));
            DebugLogger.info(DebugLogger.LogType.CHUNK_APPLY,
                    "[SHADOW_PLACEHOLDER] Neighbor relight queued ({}, {}) dim={}",
                    pos.x, pos.z, dimension);
        }
    }

    /**
     * 扫描齐套队列：对每个待齐套柱尝试 {@link LightNeighborhoodGate#tryPromote}，
     * 齐套则构建 LightTask 加入本批；同时把同批「已注入但未算光」的邻柱一并提交，
     * 确保 3×3 邻域在同一 consumeLoop 批次内同时算光（引擎跨边界传播依赖同批 DataLayer）。
     */
    private static void pumpGateReady(ShadowSeedServer server, List<LightTask> lightTasks) {
        if (LightNeighborhoodGate.pendingCount() == 0) {
            return;
        }
        for (long key : LightNeighborhoodGate.snapshotKeys()) {
            Object context = LightNeighborhoodGate.tryPromote(server, key);
            if (!(context instanceof GateContext gateCtx)) {
                continue;
            }
            lightTasks.add(gateCtx.toLightTask(key));
            // 一并 promote 同批未算光邻柱：避免中心柱算光时邻柱 DataLayer 为空
            String dimension = DimensionKey.dimensionOf(key);
            ChunkPos pos = new ChunkPos(DimensionKey.chunkXOf(key), DimensionKey.chunkZOf(key));
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    long nKey = DimensionKey.key(dimension, pos.x + dx, pos.z + dz);
                    if (nKey == key) {
                        continue;
                    }
                    net.minecraft.world.level.chunk.LevelChunk neighbor =
                            server.injectedChunk(dimension, pos.x + dx, pos.z + dz);
                    if (neighbor == null || neighbor.isLightCorrect()) {
                        continue;
                    }
                    Object nCtx = LightNeighborhoodGate.tryPromote(server, nKey);
                    if (nCtx instanceof GateContext neighborGateCtx) {
                        lightTasks.add(neighborGateCtx.toLightTask(nKey));
                    }
                }
            }
        }
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
