package io.github.limuqy.mc.hassium.network.seedgen;

import io.github.limuqy.mc.hassium.cache.client.ClientMainThreadBudget;
import io.github.limuqy.mc.hassium.concurrent.HassiumTaskExecutor;
import io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue;
import io.github.limuqy.mc.hassium.concurrent.TaskCategory;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.ClientChunkHandler;
import io.github.limuqy.mc.hassium.network.ClientChunkHandler.TraceOrigin;
import io.github.limuqy.mc.hassium.network.ClientChunkPipeline;
import io.github.limuqy.mc.hassium.network.ShadowChunkRole;
import io.github.limuqy.mc.hassium.compat.LevelCompat;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DimensionKey;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
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
 *   <li>消费（后台池单循环 CAS，管道化）：取批 → 注入 → 官方 {@code initializeLight}
 *       （对齐 {@code ChunkStatus.INITIALIZE_LIGHT}，range=0）→ 全量重算等 8 邻建层后
 *       {@code lightChunk}（对齐原版 LIGHT range=1；光桥/增量/磁盘复用不等邻）→ 打包回传。
 *       在途上限 {@link #PIPELINE_MAX_INFLIGHT} 只计正在跑引擎任务的柱。
 *       5s 超时兜底欠光标脏，由光照更新桥梁事件驱动补发</li>
 *   <li>光照收集（{@link #collectLightUpdate}）：影子端 light 线程（引擎每完成
 *       一个 section 的光计算）</li>
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
    /** 只作为原版 LIGHT 邻域的柱；完成后绝不发布至 ClientLevel。 */
    private static final ConcurrentHashMap<Long, Boolean> haloKeys = new ConcurrentHashMap<>();
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
    /**
     * 已完成官方 {@code initializeLight}、等待 8 邻建层后 {@code lightChunk}
     * （全量重算的邻居门槛；光桥/增量/磁盘复用不 park）。不计入
     * {@link #PIPELINE_MAX_INFLIGHT}。
     */
    private static final ConcurrentHashMap<Long, InflightLight> waitingForLight = new ConcurrentHashMap<>();
    /** 已完成 {@code initializeLight} 的柱（含已打包）；邻柱 LIGHT 门槛看这张表。 */
    private static final ConcurrentHashMap<Long, Boolean> initializedLight = new ConcurrentHashMap<>();
    /**
     * 客户端已有柱、影子光未完备：暂缓 push，并挡住 {@link #drainLightMasks} 中间态，
     * 避免加载屏快路径自算亮光后被欠光包盖暗。
     */
    private static final ConcurrentHashMap<Long, LightTask> deferredLightPush = new ConcurrentHashMap<>();
    /**
     * 握手尚未完成时到达的 chunkHash：R2 有缓存路径只发 hash，丢弃则既不读盘也不请求 → 空窗。
     */
    private static final ConcurrentLinkedQueue<DeferredRemoteHash> deferredRemoteHashes =
            new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean remoteHashDrainRunning = new AtomicBoolean();
    private record DeferredRemoteHash(String dimension,
                                      List<io.github.limuqy.mc.hassium.network.ChunkHashS2CPacket.Entry> entries) {}
    /**
     * 客户端已通过影子链路落地的全量区块 epoch（复合键）。客户端 unload 立即移除；光包携带
     * 入队时 epoch，消费时必须仍相等，避免旧光包打到已卸载或重新装载的同坐标区块。
     */
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

    /**
     * 影子端光照引擎互斥锁：所有「光照任务投递」串行化——注入/重算/增量的清光
     * （{@code ShadowSeedServer.clearChunkLight}）与光屏障提交（{@link #submitLightBatch}）。
     * <p>
     * 2026-08-14 1.20.1 定位：{@code ThreadedLevelLightEngine} 的任务经
     * ChunkTaskPriorityQueueSorter 在 ForkJoinPool（Worker-Main-*）<b>多线程并行</b>执行，
     * 锁只能串行化<b>投递</b>、无法串行化<b>执行</b>——相邻柱的「清光」（removeSection
     * 延迟删除 → swapSectionMap 物理删除）与「传播」（{@code propagateIncreases} 读
     * DataLayer）在 Worker 线程交错 → {@code getStoredLevel} 拿 null → NPE → 队列残留
     * → isLightConverged 恒 false → 全批超时欠光推送黑块。执行层窗口由
     * {@code MixinLayerLightSectionStorage} 的 null 兜底消灭（DataLayer null → 光级 0，
     * 不 NPE 不残留）；本锁把窗口压到最小（提交屏障期间无新清光投递）。锁内仅投递任务
     * （addTask，微秒级）——管道化后等待已不在锁内：完成回调/超时扫表/打包回传全部锁外。
     */
    static final Object LIGHT_ENGINE_MUTEX = new Object();

    /**
     * 出界卸载延迟表（T5）：DimensionKey 复合键 → 到期毫秒。区块离开卸载边界后登记计时
     * （已登记不动，防抖）；玩家回边界内立即取消（区块驻留）；到期 → 单柱落盘 +
     * 从 injectedChunks 移除。客户端主线程帧尾检查（drainReady 开头，节流扫描）。
     * 与 ShadowCacheEviction（容量淘汰删磁盘）独立共存。
     */
    private static final ConcurrentHashMap<Long, Long> unloadPending = new ConcurrentHashMap<>();
    /** 卸载检查节流（tick 计数）：全表扫描轻量，5 tick（~100ms）一次足够。 */
    private static final int UNLOAD_SCAN_INTERVAL_TICKS = 5;
    /** 每扫描周期卸载限速（规格 8-16 柱/帧，取下限保守：主线程序列化 1-2ms/柱）。 */
    private static final int UNLOAD_PER_SCAN_MAX = 8;
    private static int unloadScanTick = 0;

    /** miss 已请求集合（复合键；会话内防抖：直推与请求并存时不重复请求；断连清空）。 */
    private static final java.util.Set<Long> requestedMisses = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** hash 全命中已记账柱（复合键）。同一柱磁盘命中后再收到 hash 会走内存命中，不得再加一次。 */
    private static final java.util.Set<Long> accountedCacheHits = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 光照命中/重算已记账柱（复合键）。邻柱 LIGHT_ONLY 补光会把同一片柱刷成千上万次。 */
    private static final java.util.Set<Long> accountedLights = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * P1（T7）：注入 chunk section 容器（PalettedContainer）并发锁——hash 比对线程
     * （processRemoteHashes→chunkHashOf / requestSectionDeltas→computeSectionHashes）与
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
     * 隔离预览算光池：与真引擎 {@code lightChunk} 并行。饱和丢最旧柱（
     * {@link ThreadPoolExecutor.DiscardOldestPolicy}）——被丢柱由收敛
     * {@code finishLight → pushReady(converged=true)} 兜底，不影响正确性。
     */
    private static final ThreadPoolExecutor PREVIEW_POOL = createPreviewPool();

    private static ThreadPoolExecutor createPreviewPool() {
        int threads = Mth.clamp(Runtime.getRuntime().availableProcessors() - 2, 2, 8);
        AtomicInteger seq = new AtomicInteger();
        return new ThreadPoolExecutor(
                threads, threads,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(64),
                r -> {
                    Thread t = new Thread(r, "Hassium-PreviewLight-" + seq.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.DiscardOldestPolicy());
    }

    /** 与注入/hash 比对/apply 共用同一把 per-chunk 锁。 */
    public static void withChunkLock(ChunkPos pos, Runnable action) {
        synchronized (chunkLock(pos)) {
            action.run();
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
    /** 管道低水位：在途低于此值才由完成回调重新 pump（= 1 批：低水位→满水位恰好补一批，
     *  避免每完成一块就一次 executor 往返）。 */
    private static final int PIPELINE_LOW_WATER = CONSUME_BATCH_LIMIT;
    /** 已发出、未收到 delta 响应的请求（DimensionKey 复合键 → 维度 + 截止时间）；超时回退全量。 */
    private static final ConcurrentHashMap<Long, PendingDelta> pendingDeltaRequests =
            new ConcurrentHashMap<>();

    private record PendingDelta(String dimension, long deadlineMs) {}

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
        flushDeferredRemoteHashes();
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
     * 已落地过影子全量包 + 本次回传欠光 → 暂缓覆盖。
     * 加载屏快路径会先官方 apply 剥光包（{@code hasClientChunk} 为真），但尚未记
     * {@code shadowApplyEpochs}；那种占位柱必须仍推首包，否则着火/岩浆让引擎一直忙时
     * R1 会留下长时间空洞。欠光盖暗只防「影子包已落地后再被半成品 REPLACE」。
     */
    static boolean shouldDeferIncompleteClientOverwrite(boolean alreadyShadowApplied, boolean pushConverged) {
        return alreadyShadowApplied && !pushConverged;
    }

    /**
     * 客户端已落地过影子全量包时，相同方块不得再整柱重推。
     * Bloom 未就绪会走直推（没有 remoteHash）；方块变更走 section delta，不是整柱。
     * 官方全量包会把引擎里仍为空的 sky section 打成 emptySkyYMask，盖掉屋檐光。
     */
    static boolean shouldSkipRedundantFullPush(boolean alreadyShadowApplied) {
        return alreadyShadowApplied;
    }

    /**
     * @param remoteHashPresent true=对端给了 contentHash，可以判定是否同一份方块
     * @param hashMatches       仅 remoteHashPresent 时有意义
     * @param lightComplete     客户端已落地副本的光照是否完备（isChunkLightComplete）。
     *                          B1 欠光直推也会置 alreadyShadowApplied——光没齐时不得跳过，
     *                          否则屏障 waiter 被方块级新投递作废后无人再触发
     *                          {@code lightChunk}，该柱永久黑（2026-08-23 R1 随机黑根因）。
     */
    static boolean shouldSkipUnchangedRepush(boolean alreadyShadowApplied,
                                             boolean remoteHashPresent, boolean hashMatches,
                                             boolean lightComplete) {
        if (!alreadyShadowApplied || !lightComplete) {
            return false;
        }
        return !remoteHashPresent || hashMatches;
    }

    /**
     * 在途屏障是否被更新的投递作废（触发式，不靠超时扫表）。
     * <p>
     * 整柱重推（pending / generated）取消任何在途任务。分段增量与 LightDelta 都不取消
     * 整柱首包：岩浆/着火会持续改方块并刷 LightDelta，若每次都作废首包，客户端永远
     * 拿不到柱、R1 留下无法愈合的空洞。增量方块并入在途注入柱，首包打包时带上最新
     * 方块；LightDelta 等 {@code finishLight} 后再 {@code pump}。
     *
     * @param fullChunkTask true=PENDING/GENERATED/DELTA（回传 chunk 包）；false=LIGHT_ONLY
     * @param hasBlockWork  仅整柱重推（pending/generated），不含 section delta
     */
    static boolean isSupersededByNewerWork(boolean fullChunkTask, boolean hasBlockWork,
                                           boolean hasLightDelta) {
        if (hasBlockWork) {
            return true;
        }
        return !fullChunkTask && hasLightDelta;
    }

    /**
     * 同柱整柱屏障在途时 LightDelta 只排队，等 {@code finishLight} 后再 {@code pump}。
     * 不靠每帧扫描，也不开第二条屏障顶掉首包。
     */
    static boolean canStartLightDeltaNow(boolean chunkBarrierBusy) {
        return !chunkBarrierBusy;
    }

    /**
     * 有未发布首包在等邻柱/槽位时，林火 {@code LIGHT_ONLY} 不得占满管道。
     * 否则 parked waiter 要等满 {@link #NEIGHBOR_PACK_WAIT_MS} 才豁免上限，R1 着火区空洞数秒。
     */
    static boolean canStartLightOnlyWhileFirstPacketsWait(boolean waitingFirstPackets) {
        return !waitingFirstPackets;
    }

    /**
     * 本柱 {@code lightChunk} 完成后，这条回传是否立刻视为可发布。
     * 首包只看本柱成功：邻柱林火 {@code LIGHT_ONLY} 会让
     * {@link #areVanillaLightNeighborsConverged} 恒为 false，不得因此挡住尚未落地的柱。
     * {@code LIGHT_ONLY} 仍要求邻域 idle（屋檐质量）。
     */
    static boolean firstPacketLightReady(boolean lightChunkOk, boolean neighborColumnsIdle,
                                         boolean lightOnlyRelight) {
        if (!lightChunkOk) {
            return false;
        }
        return lightOnlyRelight ? neighborColumnsIdle : true;
    }

    /**
     * 隔离预览是否仍应推给客户端。邻柱已齐时会立刻 {@code lightChunk}，预览不得因此丢弃——
     * 林火占满真引擎时 future 会迟到，R1 出现空洞。屏障已结束则等收敛包。
     */
    static boolean shouldPushIsolatedPreview(boolean alreadyShadowApplied, boolean superseded,
                                             boolean barrierStillLive) {
        return barrierStillLive && !alreadyShadowApplied && !superseded;
    }

    private static boolean isChunkBarrierBusy(long key) {
        return inflightLight.containsKey(key)
                || waitingForLight.containsKey(key)
                || pending.containsKey(key)
                || generated.containsKey(key)
                || pendingDeltas.containsKey(key);
    }

    private static boolean hasQueuedBlockWork(long key) {
        // 仅整柱重推取消在途首包。section delta 并入注入柱，由当前屏障打包。
        return pending.containsKey(key) || generated.containsKey(key);
    }

    private static boolean isSuperseded(LightTask t) {
        return t != null && isSupersededByNewerWork(
                t.source != LightSource.LIGHT_ONLY,
                hasQueuedBlockWork(t.key),
                pendingLightUpdates.containsKey(t.key));
    }

    /** 现在就能开屏障的投递（排队的 LightDelta 若同柱整柱未完，不算可开工）。 */
    private static boolean hasStartablePendingWork() {
        if (!pending.isEmpty() || !pendingDeltas.isEmpty() || !generated.isEmpty()) {
            return true;
        }
        boolean waitingFirstPackets = !waitingForLight.isEmpty();
        for (Long key : pendingLightUpdates.keySet()) {
            if (canStartLightDeltaNow(isChunkBarrierBusy(key))
                    && canStartLightOnlyWhileFirstPacketsWait(waitingFirstPackets)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 服务端直推注入：hash miss 已经 {@code recordFullChunkRequests} 过的柱不再记一次。
     * 直推若不带 hash，这条是「应用区块」分母的唯一来源。
     */
    static boolean shouldAccountServerPushAsApplied(boolean alreadyRequested) {
        return !alreadyRequested;
    }

    /**
     * 可见柱网络入站记账（与 consumeLoop 直推同口径）。
     * 光屏障要等邻柱 initializeLight，dump 往往发生在落地之前；必须在 inject 当时记
     * 全量请求 + 光照重算，Halo 不走这里。
     */
    static void accountVisibleNetworkIngress(String dimension, ChunkPos pos) {
        if (pos == null) {
            return;
        }
        String resolved = dimension == null ? currentDimension() : dimension;
        long key = DimensionKey.key(resolved, pos.x, pos.z);
        if (!shouldAccountServerPushAsApplied(requestedMisses.contains(key))) {
            return;
        }
        requestedMisses.add(key);
        io.github.limuqy.mc.hassium.metrics.NetworkStats.recordFullChunkRequests(
                1, io.github.limuqy.mc.hassium.metrics.NetworkStats.ESTIMATED_CHUNK_BYTES, false);
        accountLightColumn(resolved, pos, false);
    }

    /**
     * hash 全命中按柱去重。磁盘命中会 inject 进内存，后续同柱再比 hash 会再走内存分支。
     */
    static boolean accountCacheFullHit(String dimension, ChunkPos pos) {
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
        io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService
                .getInstance().noteShadowServed();
        return true;
    }

    /**
     * 光照缓存按柱去重：一柱一次命中或一次重算。邻柱 LIGHT_ONLY 补光不走这里。
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
     * 对齐原版 {@code ChunkStatus.LIGHT}（range=1）：邻柱 holder 已有
     * {@code INITIALIZE_LIGHT} parent（与 {@code getChunkForLighting} 同一条件）
     * 或本端已 {@code initializeLight} 后才 {@code lightChunk}。{@code expected==0}
     * 表示视距外/地图边缘，立即进入 LIGHT。探活：金字塔不会可靠地只重跑 LIGHT，
     * 两阶段屏障保留；不再把自研 {@code initializedLight} 表当作唯一真相。
     */
    static boolean canStartVanillaLightStage(int readyNeighbors, int expectedNeighbors,
                                             boolean timedOut) {
        return timedOut || readyNeighbors >= expectedNeighbors;
    }

    /** 邻柱就绪：holder 已有 INITIALIZE_LIGHT parent，或本端 initializeLight 已完成。 */
    static boolean isVanillaLightNeighborReady(boolean holderHasInitLightParent,
                                               boolean selfInitializedLight) {
        return holderHasInitLightParent || selfInitializedLight;
    }

    /** 邻柱是否算「应该存在」：有 holder / 注入表 / 管道中；超时当边缘。 */
    static boolean isVanillaLightNeighborExpected(boolean holderPresent, boolean inInjectOrPipeline,
                                                  boolean withinViewFallback) {
        return holderPresent || inInjectOrPipeline || withinViewFallback;
    }

    /**
     * 对齐原版 {@code ChunkStatus.LIGHT}：全量重算（PENDING/GENERATED 且非
     * REUSE_CACHE）须等 8 邻 {@code initializeLight} 建层后才 {@code lightChunk}——
     * 先算柱播种时读后算柱的 {@code ChunkSkyLightSources} 几何高度图，把跨边界
     * increase 推进后算柱已建的层；不等邻则先算柱播种止步边界、后算柱屋檐列
     * （lowestSourceY &gt; sectionTop）播种循环空转，凹槽永久缺光（2026-08-22
     * (-13,3) 屋檐根因）。光桥 / 分段增量 / 磁盘复用仍不等邻。
     */
    static boolean needsVanillaLightNeighborWait(boolean fullChunkRecompute) {
        return fullChunkRecompute;
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

    /**
     * 官方 {@code ClientboundLightUpdatePacketData.prepareSectionData}：空 DataLayer
     * 会打进 emptySkyYMask，客户端把该 section <em>显式置 0</em>。
     * <p>
     * 源之上的空层是播种失败，仍要发出去（否则客户端会按缺层向上继承成 15）。
     * 源之下的空层则是屋檐/侧向漏光还没被邻柱 increase 写入——省略该 section，
     * 等光桥带真实数据；不要用 empty 掩码把屋檐钉死成黑。
     */
    static boolean shouldIncludeSkySectionInPacket(boolean layerPresent, boolean layerEmpty,
                                                   boolean sectionAtOrAboveAnySkySource) {
        if (!layerPresent) {
            return false;
        }
        if (!layerEmpty) {
            return true;
        }
        return sectionAtOrAboveAnySkySource;
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
     * 光桥本帧是否打包该柱：必须已有影子带光区块包落地，且不在屏障 / 欠光暂缓 /
     * 等待邻柱 LIGHT 中。加载屏 blocks-only 不算落地——对其套光包会把空层打到客户端，
     * 形成向外扩的黑圈。
     */
    static boolean canDrainLightMaskThisFrame(boolean inflight, boolean deferredOverwrite,
                                              boolean hasShadowEpoch, boolean waitingForLightStage) {
        return hasShadowEpoch && !inflight && !deferredOverwrite && !waitingForLightStage;
    }

    /**
     * 磁盘命中后是否还需 LIGHT 续算：只看原版 {@code isLightCorrect()}
     * （NBT {@code isLightOn}）。未点亮则 {@code lightChunk(false)} 播种+传播；
     * 内容是否过期由 hash 表决定，不另维护光照脏表。
     */
    static boolean diskNeedRelight(boolean lightCorrect) {
        return !lightCorrect;
    }

    private static boolean diskNeedRelight(LevelChunk chunk) {
        return diskNeedRelight(chunk.isLightCorrect());
    }

    private static boolean hasPendingWork() {
        return !pending.isEmpty() || !pendingDeltas.isEmpty() || !generated.isEmpty()
                || !pendingLightUpdates.isEmpty();
    }

    /** 影子链路可用（引擎开启 && 握手完成 && 影子端未失败）。 */
    public static boolean isEnabled() {
        return HassiumConfigService.getInstance().isHassiumEngineEnabled()
                && ClientChunkPipeline.getInstance().isHassiumHandshakeDone()
                && !ClientChunkPipeline.getInstance().isShadowServerFailed();
    }

    /**
     * 登录初始化入口（兼容旧调用点）：等价 {@link #startShadowSpeculative()}。
     * 握手不再阻塞创建；无握手约 3s 后关停投机影子。
     */
    public static void onLogin() {
        startShadowSpeculative();
    }

    /** 投机创建超时：无握手则关停刚拉起的影子（原版服不常驻）。 */
    static final long SPECULATIVE_HANDSHAKE_TIMEOUT_MS = 3_000L;

    /**
     * 配置就绪即后台 getOrCreate（不等握手）。已存在实例则幂等返回；
     * 创建时若尚未握手，武装 3s 看门狗——超时仍无握手则 {@link ShadowServerRegistry#shutdown()}。
     * {@link #isEnabled()} 仍要求握手，避免原版服走剥光路径。
     */
    public static void startShadowSpeculative() {
        if (!HassiumConfigService.getInstance().isHassiumEngineEnabled()) {
            return;
        }
        HassiumTaskExecutor executor = HassiumTaskExecutor.getClient();
        if (executor == null || !executor.isRunning()) {
            return;
        }
        executor.submit(() -> {
            boolean hadHandshake = ClientChunkPipeline.getInstance().isHassiumHandshakeDone();
            DebugLogger.info(DebugLogger.LogType.NETWORK,
                    "[LOGIN-DIAG] startShadowSpeculative handshakeDone={} (no wait)",
                    hadHandshake);
            ShadowSeedServer created = ShadowServerRegistry.getInstance().getOrCreate();
            if (created != null && !hadHandshake
                    && shouldArmSpeculativeWatchdog(hadHandshake)) {
                ShadowServerRegistry.getInstance().armSpeculativeHandshakeWatchdog(
                        SPECULATIVE_HANDSHAKE_TIMEOUT_MS);
            }
        }, TaskCategory.BEST_EFFORT);
    }

    /** 测试缝：仅当创建时尚未握手才武装看门狗。 */
    static boolean shouldArmSpeculativeWatchdog(boolean handshakeDoneAtCreate) {
        return !handshakeDoneAtCreate;
    }

    /** 测试缝：超时且仍无握手 → 应关停投机影子。 */
    static boolean shouldShutdownSpeculativeShadow(boolean handshakeDone, long elapsedMs, long timeoutMs) {
        return !handshakeDone && elapsedMs >= timeoutMs;
    }

    /**
     * 影子端 hash 比对（架构语义：服务端 bloom hit 只发 hash——由影子端决定是否需要
     * 推区块数据）。Netty 线程调用（{@code ClientMetadataHandler.handleChunkHashPacket}）；
     * 查盘/比对/请求全部提交后台池（与 consumeLoop 同池，不阻塞 Netty）。
     * <p>
     * 判定顺序（每块）：
     * <ol>
     *   <li>内存已加载（injectedChunks）→ hash 比对（ShadowStorageHashes 表优先，
     *       无表现算）→ 命中 → 客户端尚未影子落地则回传；已落地则跳过整柱重推
     *       （emptySkyYMask 会盖掉光桥屋檐光）；</li>
     *   <li>未加载 → 读影子端存档比对（loadFromDisk，受 {@code maxChunksPerFrame} 生产配额；
     *       配额用尽则剩余条目留待下 tick，不改判 miss）→ 命中 →
     *       加载进影子端（injectLoadedChunk，后续直接内存命中）+ 回传；</li>
     *   <li>不中 / 存档无此柱 → 请求数据（requestFullChunks → 服务端
     *       enqueueDataRequest 推送 → 数据到达走 submit/consumeLoop 注入链）。</li>
     * </ol>
     * 影子端创建失败 / 不可用 → 全部请求（降级态客户端直连 apply 兜底，数据必须到）。
     * 握手尚未完成时入队，待 {@link #flushDeferredRemoteHashes} 重放，禁止静默丢弃。
     */
    public static void handleRemoteHashes(String dimension,
                                          List<io.github.limuqy.mc.hassium.network.ChunkHashS2CPacket.Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        // 自定义维度透传门控（REQ 明细7）：非三主维度整包忽略——不请求不缓存不比对
        // （数据由服务端直推 + vanilla apply 兜底，见 ClientChunkHandler.handleCompressedChunk）。
        if (!isCacheableDimension(dimension)) {
            DebugLogger.info(DebugLogger.LogType.METADATA,
                    "[SHADOW_HASH] Ignoring non-cacheable dimension packet ({} entries, dimension={})",
                    entries.size(), dimension);
            return;
        }
        deferredRemoteHashes.add(new DeferredRemoteHash(dimension, List.copyOf(entries)));
        tryDrainRemoteHashes();
    }

    /** 维度白名单判定（透传门控；null 安全）。 */
    static boolean isCacheableDimension(String dimension) {
        return DimensionKey.isCacheableDimension(dimension);
    }

    /** 握手完成或影子端就绪：单线程抽干 hash 队列（禁止每包一个虚拟线程打满 CPU）。 */
    public static void flushDeferredRemoteHashes() {
        tryDrainRemoteHashes();
    }

    private static void tryDrainRemoteHashes() {
        if (!ClientChunkPipeline.getInstance().isHassiumHandshakeDone()) {
            return;
        }
        if (!remoteHashDrainRunning.compareAndSet(false, true)) {
            return;
        }
        HassiumTaskExecutor executor = HassiumTaskExecutor.getClient();
        if (executor == null || !executor.isRunning()) {
            remoteHashDrainRunning.set(false);
            return;
        }
        try {
            executor.submit(() -> {
                try {
                    DeferredRemoteHash item;
                    while ((item = deferredRemoteHashes.poll()) != null) {
                        List<io.github.limuqy.mc.hassium.network.ChunkHashS2CPacket.Entry> leftover =
                                processRemoteHashes(item.dimension(), item.entries());
                        if (!leftover.isEmpty()) {
                            deferredRemoteHashes.add(new DeferredRemoteHash(item.dimension(), leftover));
                            break;
                        }
                    }
                    pump();
                } finally {
                    remoteHashDrainRunning.set(false);
                    if (!deferredRemoteHashes.isEmpty()) {
                        tryDrainRemoteHashes();
                    }
                }
            }, TaskCategory.BEST_EFFORT);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            remoteHashDrainRunning.set(false);
        }
    }

    private static List<io.github.limuqy.mc.hassium.network.ChunkHashS2CPacket.Entry> processRemoteHashes(
            String dimension,
            List<io.github.limuqy.mc.hassium.network.ChunkHashS2CPacket.Entry> entries) {
        ShadowSeedServer server = ShadowServerRegistry.getInstance().getOrCreate();
        List<ChunkPos> misses = new ArrayList<>();
        List<ChunkPos> deltaCandidates = new ArrayList<>();
        List<ChunkPos> hits = new ArrayList<>();
        List<ChunkPos> beImmediate = new ArrayList<>();
        List<io.github.limuqy.mc.hassium.network.ChunkHashS2CPacket.Entry> leftover = new ArrayList<>();
        boolean cacheReadBudgetExhausted = false;
        for (io.github.limuqy.mc.hassium.network.ChunkHashS2CPacket.Entry entry : entries) {
            ChunkPos pos = new ChunkPos(entry.chunkX(), entry.chunkZ());
            long remoteHash = entry.chunkHash();
            try {
                if (server != null) {
                    // 1) 内存已加载：hash 表优先（注入时已算），无表现算（OVD 生成块）
                    LevelChunk loaded = server.injectedChunk(dimension, pos.x, pos.z);
                    if (loaded != null) {
                        // P1（T7）：hash 比对读注入 chunk section 容器，与 consumeLoop 打包
                        // （pushReady→buildPacket）/ delta 应用（applySectionDelta）同 chunk 锁互斥。
                        boolean hashHit;
                        synchronized (chunkLock(pos)) {
                            hashHit = chunkHashOf(dimension, loaded, pos, remoteHash);
                        }
                        if (hashHit) {
                            org.slf4j.LoggerFactory.getLogger("Hassium/ShadowDisk")
                                    .info("Shadow hash cache hit ({}, {}), memory push", pos.x, pos.z);
                            // T5g：区块缓存全命中——与磁盘直推同口径；同柱只记一次。
                            accountCacheFullHit(dimension, pos);
                            boolean lightReuse = server.isChunkLightComplete(pos, loaded);
                            if (lightReuse) {
                                accountLightColumn(dimension, pos, true);
                            }
                            long memoryKey = DimensionKey.key(dimension, pos.x, pos.z);
                            if (alreadyShadowApplied(memoryKey)) {
                                DebugLogger.info(DebugLogger.LogType.CHUNK_APPLY,
                                        "[SHADOW_CHUNK] Skip redundant full push ({}, {}): memory hit, client already applied",
                                        pos.x, pos.z);
                                scheduleBeRefreshOnHashHit(dimension, pos, true, beImmediate);
                                hits.add(pos);
                                continue;
                            }
                            // 回传统一走 generated → consumeLoop 光屏障（submitLightBatch）：
                            // buildPacket 从 LevelLightEngine 收集光，注入表 chunk 的光在引擎
                            // 内（本会话算过）但需确认收敛，直接 push 可能推欠光（黑块）。
                            scheduleBeRefreshOnHashHit(dimension, pos, false, beImmediate);
                            generated.put(memoryKey, new GenEntry(loaded, server.level(dimension), lightReuse,
                                    false, traceOrigin(TraceOrigin.SHADOW_MEMORY_CACHE)));
                            continue;
                        }
                        // 内存数据过期（hash MISMATCH）→ 分段增量候选：
                        // 本地 section hashes 上报，服务端只回变更 section。光由后续屏障处理。
                        if (deltaEnabled()) {
                            deltaCandidates.add(pos);
                            continue;
                        }
                        misses.add(pos);
                        continue;
                    }
                    // 2) 未注入：probeHash（缺文件即 mismatch）；hit 才解压一槽。
                    io.github.limuqy.mc.hassium.storage.ShadowStorageManager storage = server.storage(dimension);
                    io.github.limuqy.mc.hassium.storage.ShadowStorageManager.ProbeResult probe =
                            storage != null
                                    ? storage.probeHash(dimension, pos, remoteHash)
                                    : new io.github.limuqy.mc.hassium.storage.ShadowStorageManager.ProbeResult(
                                            io.github.limuqy.mc.hassium.storage.ShadowStorageManager.ProbeStatus.ABSENT);
                    if (!probe.match()) {
                        if (probe.present() && deltaEnabled()) {
                            if (cacheReadBudgetExhausted || !ClientMainThreadBudget.tryAcquireCacheRead()) {
                                cacheReadBudgetExhausted = true;
                                leftover.add(entry);
                                continue;
                            }
                            LevelChunk fromDisk = server.loadFromDisk(dimension, pos);
                            if (fromDisk != null) {
                                server.injectLoadedChunk(dimension, pos, fromDisk);
                                deltaCandidates.add(pos);
                                continue;
                            }
                        }
                        misses.add(pos);
                        continue;
                    }
                    if (cacheReadBudgetExhausted || !ClientMainThreadBudget.tryAcquireCacheRead()) {
                        cacheReadBudgetExhausted = true;
                        leftover.add(entry);
                        continue;
                    }
                    LevelChunk fromDisk = server.loadFromDisk(dimension, pos);
                    if (fromDisk != null) {
                        server.injectLoadedChunk(dimension, pos, fromDisk);
                        boolean needRelight =
                                io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.isLightDirty(dimension, pos)
                                || !server.isChunkLightComplete(pos, fromDisk);
                        org.slf4j.LoggerFactory.getLogger("Hassium/ShadowDisk")
                                .info("Shadow hash cache hit ({}, {}), disk push needRelight={}",
                                        pos.x, pos.z, needRelight);
                        accountCacheFullHit(dimension, pos);
                        if (!needRelight) {
                            accountLightColumn(dimension, pos, true);
                        }
                        long diskKey = DimensionKey.key(dimension, pos.x, pos.z);
                        if (alreadyShadowApplied(diskKey)) {
                            DebugLogger.info(DebugLogger.LogType.CHUNK_APPLY,
                                    "[SHADOW_CHUNK] Skip redundant full push ({}, {}): disk hit, client already applied",
                                    pos.x, pos.z);
                            scheduleBeRefreshOnHashHit(dimension, pos, true, beImmediate);
                            hits.add(pos);
                            continue;
                        }
                        scheduleBeRefreshOnHashHit(dimension, pos, false, beImmediate);
                        generated.put(diskKey, new GenEntry(fromDisk, server.level(dimension), !needRelight,
                                false, traceOrigin(TraceOrigin.SHADOW_DISK_CACHE)));
                        continue;
                    }
                    misses.add(pos);
                    continue;
                }
            } catch (Throwable t) {
                DebugLogger.warn(DebugLogger.LogType.ASYNC,
                        "[SHADOW_HASH] Compare failed ({}, {})", pos.x, pos.z);
            }
            // 3) 不中 / 影子端不可用：请求数据
            misses.add(pos);
        }
        if (!hits.isEmpty()) {
            // 契约6：hit 柱收集后回发 RESULT_HIT 空柱回执（服务端据此释放 reservation）。
            io.github.limuqy.mc.hassium.network.ClientMetadataHandler
                    .sendChunkDataResult(dimension, hits);
        }
        if (!beImmediate.isEmpty()) {
            io.github.limuqy.mc.hassium.network.ClientMetadataHandler
                    .requestBeRefreshNow(dimension, beImmediate);
        }
        if (!deltaCandidates.isEmpty()) {
            requestSectionDeltas(dimension, deltaCandidates);
        }
        if (!misses.isEmpty()) {
            List<ChunkPos> toRequest = new ArrayList<>(misses.size());
            for (ChunkPos pos : misses) {
                if (requestedMisses.add(DimensionKey.key(dimension, pos.x, pos.z))) {
                    toRequest.add(pos);
                }
            }
            if (!toRequest.isEmpty()) {
                io.github.limuqy.mc.hassium.network.ClientMetadataHandler
                        .requestFullChunksPublic(dimension, toRequest, false);
            }
        }
        return leftover;
    }

    /**
     * 缓存命中不复用 BE：未落地则等 apply 后拉；已落地（跳过重复推）立即拉。
     */
    private static void scheduleBeRefreshOnHashHit(String dimension, ChunkPos pos,
                                                   boolean alreadyApplied, List<ChunkPos> beImmediate) {
        if (alreadyApplied) {
            beImmediate.add(pos);
        } else {
            io.github.limuqy.mc.hassium.network.ClientMetadataHandler.scheduleBeRefresh(dimension, pos);
        }
    }

    /** 分段增量门控：配置开启 && 影子链路可用。 */
    private static boolean deltaEnabled() {
        return io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance().isSectionDeltaEnabled()
                && isEnabled();
    }

    /**
     * 上报本地 section hashes 请求分段增量（后台池 / SeedGen 生成线程调用）：影子端本地有旧数据
     * （内存/磁盘）但 contentHash 与远程权威不一致 → 服务端按 section 比对只回
     * 变更 section + heightmaps + BE。登记超时（{@link #tickPendingDeltaTimeouts}）。
     */
    public static void requestSectionDeltas(String dimension, List<ChunkPos> chunks) {
        ShadowSeedServer server = ShadowServerRegistry.getInstance().getOrCreate();
        if (server == null || chunks.isEmpty()) {
            return;
        }
        List<io.github.limuqy.mc.hassium.network.SectionHashRequestC2SPacket.Entry> entries = new ArrayList<>(chunks.size());
        // P3（T7）：自适应超时——镜像 full 路径（基数 + 每块 + 每在途 + 上限），固定 8s
        // 在深队/服务端逐 section 比对下过紧，会误触发回退风暴。
        long deadline = System.currentTimeMillis()
                + deltaRequestTimeoutMs(chunks.size(), pendingDeltaRequests.size());
        for (ChunkPos pos : chunks) {
            LevelChunk chunk = server.injectedChunk(dimension, pos.x, pos.z);
            if (chunk == null) {
                continue; // 已被移除/竞态：数据由服务端直推兜底
            }
            long[] sectionHashes;
            int[][] planes;
            // P1（T7）：读注入 chunk section 容器，与 consumeLoop
            // 打包/写路径（buildPacket/applySectionDelta）同 chunk 锁互斥。
            synchronized (chunkLock(pos)) {
                io.github.limuqy.mc.hassium.network.sectiondelta.SectionDeltaSnapshot snap =
                                io.github.limuqy.mc.hassium.network.sectiondelta.SectionDeltaSnapshots
                                        .getOrCapture(dimension, pos, chunk);
                sectionHashes = snap.sectionHashes();
                planes = snap.planes();
            }
            entries.add(new io.github.limuqy.mc.hassium.network.SectionHashRequestC2SPacket.Entry(
                    pos.x, pos.z, sectionHashes, planes));
            pendingDeltaRequests.put(DimensionKey.key(dimension, pos.x, pos.z),
                    new PendingDelta(dimension, deadline));
        }
        if (entries.isEmpty()) {
            return;
        }
        net.minecraft.network.FriendlyByteBuf buf = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        boolean sent = false;
        try {
            new io.github.limuqy.mc.hassium.network.SectionHashRequestC2SPacket(dimension, entries).encode(buf);
            io.github.limuqy.mc.hassium.platform.Services.NETWORK_MANAGER.sendSectionHashRequest(buf);
            sent = true;
            io.github.limuqy.mc.hassium.metrics.NetworkStats.recordSectionDeltaRequestsSent(entries.size());
            io.github.limuqy.mc.hassium.metrics.NetworkStats.recordCacheLoadEligible(
                    entries.size() * io.github.limuqy.mc.hassium.metrics.NetworkStats.ESTIMATED_CHUNK_BYTES);
            DebugLogger.info(DebugLogger.LogType.ASYNC,
                    "[SHADOW_DELTA] Requested {} section-delta chunks (dimension={})", entries.size(), dimension);
        } catch (Throwable t) {
            // 发送失败 → 立即回退全量（登记的请求清掉，避免超时重复回退）
            for (var e : entries) {
                pendingDeltaRequests.remove(DimensionKey.key(dimension, e.chunkX(), e.chunkZ()));
            }
            DebugLogger.warn(DebugLogger.LogType.ASYNC,
                    "[SHADOW_DELTA] Request send failed, fallback full ({} chunks)", entries.size());
            // P2（T7）：失败回退走 new 路径（hash-miss 正轨）+ requestedMisses 去重
            List<ChunkPos> fallback = dedupeFallback(dimension, chunks);
            if (!fallback.isEmpty()) {
                io.github.limuqy.mc.hassium.network.ClientMetadataHandler
                        .requestFullChunksPublic(dimension, fallback, false);
            }
        } finally {
            if (!sent && buf != null) {
                buf.release();
            }
        }
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
            pendingDeltaRequests.remove(key);
        }
        // 全量等价流量不在此处记：apply 失败会回退全量，收到即记会在「delta + 回退全量」
        // 场景把同一区块计两次。成功应用后由 consumeLoop 记 recordSectionDeltaReceived。
        if (!packet.skipped().isEmpty()) {
            List<net.minecraft.world.level.ChunkPos> skipped = new ArrayList<>(packet.skipped().size());
            for (var s : packet.skipped()) {
                long key = DimensionKey.key(dimension, s.chunkX(), s.chunkZ());
                pendingDeltaRequests.remove(key);
                skipped.add(new net.minecraft.world.level.ChunkPos(s.chunkX(), s.chunkZ()));
            }
            DebugLogger.info(DebugLogger.LogType.ASYNC,
                    "[SHADOW_DELTA] {} chunks skipped by server, fallback full", skipped.size());
            // P2（T7）：失败回退走 new 路径 + requestedMisses 去重
            List<net.minecraft.world.level.ChunkPos> fallback = dedupeFallback(dimension, skipped);
            if (!fallback.isEmpty()) {
                io.github.limuqy.mc.hassium.network.ClientMetadataHandler
                        .requestFullChunksPublic(dimension, fallback, false);
            }
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

    /** 主线程帧尾（MixinClientTick）：delta 请求超时回退全量（服务端始终回包，仅丢包兜底）。 */
    public static void tickPendingDeltaTimeouts() {
        if (pendingDeltaRequests.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        java.util.Map<String, List<net.minecraft.world.level.ChunkPos>> timedOut = new java.util.HashMap<>();
        for (var it = pendingDeltaRequests.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (now >= e.getValue().deadlineMs()) {
                net.minecraft.world.level.ChunkPos pos = new net.minecraft.world.level.ChunkPos(
                        DimensionKey.chunkXOf(e.getKey()), DimensionKey.chunkZOf(e.getKey()));
                timedOut.computeIfAbsent(e.getValue().dimension(), k -> new ArrayList<>()).add(pos);
                it.remove();
            }
        }
        for (var e : timedOut.entrySet()) {
            DebugLogger.warn(DebugLogger.LogType.ASYNC,
                    "[SHADOW_DELTA] {} delta requests timed out, fallback full", e.getValue().size());
            // P2（T7）：失败回退走 new 路径 + requestedMisses 去重
            List<net.minecraft.world.level.ChunkPos> fallback = dedupeFallback(e.getKey(), e.getValue());
            if (!fallback.isEmpty()) {
                io.github.limuqy.mc.hassium.network.ClientMetadataHandler
                        .requestFullChunksPublic(e.getKey(), fallback, false);
            }
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
        // 自定义维度透传（REQ 明细7）：非缓存维度不走影子管线，直接原版落地。
        String dimension = currentDimension();
        if (!DimensionKey.isCacheableDimension(dimension)) {
            applyVanillaDirect(pos, packet);
            return;
        }
        // 全量数据到达 = 该柱不再等 delta 响应（delta 请求超时登记清除）
        long key = DimensionKey.key(dimension, pos.x, pos.z);
        pendingDeltaRequests.remove(key);
        pending.put(key, new PendingEntry(packet, traceOrigin(TraceOrigin.SERVER_PUSH)));
        pump();
    }

    /** 投递可渲染柱；同坐标 Halo 升格时取消其抑制发布标记。 */
    public static void submitVisible(String dimension, ChunkPos pos,
                                     ClientboundLevelChunkWithLightPacket packet) {
        if (pos == null || packet == null || !isEnabled()) {
            return;
        }
        String activeDimension = currentDimension();
        long key = DimensionKey.key(activeDimension, pos.x, pos.z);
        haloKeys.remove(key);
        ShadowSeedServer server = ShadowServerRegistry.getInstance().get();
        if (server != null) {
            server.setPersistenceRole(activeDimension, pos, ShadowChunkPersistenceRole.VISIBLE_FULL_LIGHT);
        }
        pendingDeltaRequests.remove(key);
        pending.put(key, new PendingEntry(packet, traceOrigin(TraceOrigin.SERVER_PUSH)));
        pump();
    }

    /** 投递仅影子端 Halo；其光照结果只能服务相邻可见柱。 */
    public static void submitHalo(String dimension, ChunkPos pos,
                                  ClientboundLevelChunkWithLightPacket packet) {
        if (pos == null || packet == null || !isEnabled()) {
            return;
        }
        String activeDimension = currentDimension();
        if (!DimensionKey.isCacheableDimension(activeDimension)) {
            return;
        }
        long key = DimensionKey.key(activeDimension, pos.x, pos.z);
        haloKeys.put(key, Boolean.TRUE);
        ShadowSeedServer server = ShadowServerRegistry.getInstance().get();
        if (server != null) {
            server.setPersistenceRole(activeDimension, pos, ShadowChunkPersistenceRole.HALO_BLOCKS_ONLY);
        }
        pending.put(key, new PendingEntry(packet, traceOrigin(TraceOrigin.SERVER_PUSH)));
        pump();
    }

    /**
     * 注入后入官方光屏障。剥光柱 persisted=FULL 且 {@code isLightCorrect=false}，
     * native {@code getChunkFuture(FULL)} 只 {@code load} 不 {@code generate} LIGHT，
     * 不能当发布门控。
     */
    static void enqueueInjectedForLight(String dimension, ChunkPos pos, ShadowChunkRole role,
                                        TraceOrigin origin) {
        if (pos == null || !isEnabled()) {
            return;
        }
        String resolved = dimension == null ? currentDimension() : dimension;
        long key = DimensionKey.key(resolved, pos.x, pos.z);
        if (role == ShadowChunkRole.HALO) {
            haloKeys.put(key, Boolean.TRUE);
        } else {
            haloKeys.remove(key);
        }
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

    /** Halo 只给邻柱提供光照边界，算完也不得进 ClientLevel。 */
    static boolean shouldPublishToClient(boolean haloColumn) {
        return !haloColumn;
    }

    /** 原版 ChunkHolder LIGHT future 完成后的可见发布入口。 */
    static void publishNativeLightResult(String dimension, ChunkPos pos, LevelChunk chunk,
                                         net.minecraft.server.level.ServerLevel level,
                                         TraceOrigin origin) {
        try {
            ClientboundLevelChunkWithLightPacket packet;
            synchronized (chunkLock(pos)) {
                packet = SeedGenChunkCodec.buildPacket(chunk, level);
            }
            offerReady(DimensionKey.key(dimension, pos.x, pos.z), pos, packet,
                    true, false, origin);
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
                    io.github.limuqy.mc.hassium.network.ClientMetadataHandler
                            .onChunkApplied(fPos);
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
        return submitGenerated(pos, chunk, level, false, traceOrigin(TraceOrigin.LOCAL_GENERATION));
    }

    /**
     * 投递本地生成/磁盘/注入区块到影子光照管线。
     *
     * @param renderOnly true=OVD 超视渲染区块，算光后经 drainReady 以 renderOnly 落地；
     *                   false=普通影子回传（权威区块）。
     */
    public static boolean submitGenerated(ChunkPos pos,
                                          net.minecraft.world.level.chunk.LevelChunk chunk,
                                          net.minecraft.server.level.ServerLevel level,
                                          boolean renderOnly) {
        return submitGenerated(pos, chunk, level, renderOnly, traceOrigin(TraceOrigin.LOCAL_GENERATION));
    }

    /** 投递带有可选诊断来源的本地区块；来源仅在 chunkApplyLogging 开启时非 null。 */
    public static boolean submitGenerated(ChunkPos pos,
                                            net.minecraft.world.level.chunk.LevelChunk chunk,
                                            net.minecraft.server.level.ServerLevel level,
                                            boolean renderOnly,
                                            TraceOrigin traceOrigin) {
        if (pos == null || chunk == null || !isEnabled()) {
            return false;
        }
        // 维度从 level 推导（SeedGenExecutor 调用点无需改签名）；非缓存维度不入影子管线。
        String dimension = level != null ? ShadowSeedServer.dimensionId(level) : null;
        if (dimension == null || !DimensionKey.isCacheableDimension(dimension)) {
            return false;
        }
        generated.put(DimensionKey.key(dimension, pos.x, pos.z),
                new GenEntry(chunk, level, false, renderOnly, traceOrigin));
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
    static {
        // E1「事件做骨」：SURROUNDED 收敛重算触发回调（LightReadinessRegistry 纯状态机，
        // 反向依赖经此注入，避免 clinit 环）。
        LightReadinessRegistry.setRelightTrigger(ShadowLightCompute::enqueueSurroundedRelight);
    }

    /**
     * SURROUNDED 触发的整柱 LIGHT_ONLY 清层重算（E1「整柱重算」面）：任一邻柱晚于本柱
     * 末次会话内计算变 LIT（或本柱光来自存档复用 lastCompute=0）时，由
     * {@link LightReadinessRegistry} 回调。全 section 掩码并入 pendingLightUpdates
     * （REPLACE 并集语义与 LightDelta 一致），复用既有 LIGHT_ONLY 屏障 + delta 回传链路；
     * 重算完成（lightChunk COMPUTED 事件）才由注册表出队。
     * <p>
     * 引擎回调线程调用；只做并发安全入队 + pump。目标柱未注入/维度未装配 → 放弃本次
     * 收敛登记（{@link LightReadinessRegistry#abandonConverge}），待后续事件重评。
     */
    private static void enqueueSurroundedRelight(long key) {
        if (!isEnabled()) {
            return; // 断连/降级：注册表将随 clear() 清理，无需 abandon
        }
        ShadowSeedServer server = ShadowServerRegistry.getInstance().get();
        if (server == null) {
            return;
        }
        String dimension = DimensionKey.dimensionOf(key);
        ChunkPos pos = new ChunkPos(DimensionKey.chunkXOf(key), DimensionKey.chunkZOf(key));
        net.minecraft.server.level.ServerLevel level = server.level(dimension);
        if (level == null || server.injectedChunk(dimension, pos.x, pos.z) == null) {
            LightReadinessRegistry.abandonConverge(key);
            return;
        }
        net.minecraft.world.level.lighting.LevelLightEngine engine =
                level.getChunkSource().getLightEngine();
        int sectionCount = engine.getLightSectionCount();
        java.util.BitSet allSections = new java.util.BitSet(sectionCount);
        allSections.set(0, sectionCount);
        LightWork work = new LightWork((java.util.BitSet) allSections.clone(),
                (java.util.BitSet) allSections.clone(),
                new java.util.BitSet(), new java.util.BitSet());
        pendingLightUpdates.merge(key, work, LightWork::merged);
        DebugLogger.info(DebugLogger.LogType.CHUNK_APPLY,
                "[SHADOW_LIGHT] Surrounded relight queued ({}, {}) sections={}",
                pos.x, pos.z, sectionCount);
        pump();
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
                    waitingForLight.clear();
                    initializedLight.clear();
                    LightReadinessRegistry.clear();
                    return;
                }
                sweepLightTimeouts(); // 超时兜底第二扫描点（主线程帧尾为主，低帧率兜底）
                tryFlushWaitingLight(server); // 首包 waiter 优先于林火 LIGHT_ONLY 占槽
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
                boolean waitingFirstPackets = !waitingForLight.isEmpty();
                List<Map.Entry<Long, LightWork>> lightBatch = new ArrayList<>();
                for (Map.Entry<Long, LightWork> e : pendingLightUpdates.entrySet()) {
                    if (lightBatch.size() >= remaining) {
                        break;
                    }
                    if (!canStartLightDeltaNow(isChunkBarrierBusy(e.getKey()))
                            || !canStartLightOnlyWhileFirstPacketsWait(waitingFirstPackets)) {
                        continue; // 整柱未完或首包仍在等：林火不进本批
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
                            boolean needRelight = !server.isChunkLightComplete(pos, existing);
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
                            generated.put(e.getKey(), new GenEntry(existing, server.level(dimension), !needRelight,
                                    false, traceOrigin(TraceOrigin.SHADOW_MEMORY_CACHE)));
                            continue;
                        }
                    }
                    // R1 全量直推：禁 loadFromDisk。内存未命中则注入网络包。
                    if (!server.injectChunk(dimension, pos, pendingEntry.packet(),
                            io.github.limuqy.mc.hassium.network.ShadowChunkRole.VISIBLE)) {
                        // 注入失败 = 影子链路整体失败：走与握手失败/创建失败同级的
                        // 关闭核心逻辑（shadowServerFailed → 缓存/OVD/SeedGen 关闭 + 提示）。
                        pending.clear();
                        pendingDeltas.clear();
                        generated.clear();
                        pendingLightUpdates.clear();
                        inflightLight.clear();
                        LightReadinessRegistry.clear();
                        ShadowServerRegistry.getInstance().failShadowServer();
                        return;
                    }
                    if (shouldAccountServerPushAsApplied(requestedMisses.contains(e.getKey()))) {
                        requestedMisses.add(e.getKey());
                        io.github.limuqy.mc.hassium.metrics.NetworkStats.recordFullChunkRequests(
                                1, io.github.limuqy.mc.hassium.metrics.NetworkStats.ESTIMATED_CHUNK_BYTES, false);
                    }
                    LevelChunk injected = server.injectedChunk(dimension, pos.x, pos.z);
                    // 预览包（原 B1）改到 initializeLight 完成后、确定 park 时再推：
                    // 注入即推时引擎层尚未建，整包全空 → 客户端涂黑，邻居门槛下黑窗
                    // 被拉长到秒级；init 完成后源上方已填 15，预览即可正确渲染普通地形。
                    if (injected != null) {
                        accountLightColumn(dimension, pos, false);
                    }
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
                        // 基线缺失 / 应用失败 → 回退全量（正确性优先）；跳过本 chunk 回传
                        DebugLogger.warn(DebugLogger.LogType.ASYNC,
                                "[SHADOW_DELTA] Apply failed ({}, {}), fallback full", pos.x, pos.z);
                        // P2（T7）：失败回退走 new 路径 + requestedMisses 去重
                        if (tryRequestMiss(work.dimension(), pos)) {
                            List<ChunkPos> fallback = new ArrayList<>(1);
                            fallback.add(pos);
                            io.github.limuqy.mc.hassium.network.ClientMetadataHandler
                                    .requestFullChunksPublic(work.dimension(), fallback, false);
                        }
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
                        if (isChunkBarrierBusy(key)) {
                            // 方块已并入在途首包的注入柱；不得另开 DELTA 屏障取消首包。
                            enqueueSurroundedRelight(key);
                        } else {
                            lightTasks.add(new LightTask(key, LightSource.DELTA, null,
                                    baseline, server.level(work.dimension()), LightMetric.RECOMPUTE,
                                    false, traceOrigin(TraceOrigin.SERVER_PUSH)));
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
                    if (!canStartLightDeltaNow(isChunkBarrierBusy(key))
                            || !canStartLightOnlyWhileFirstPacketsWait(!waitingForLight.isEmpty())) {
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
     * 管道化两阶段光屏障提交（原 {@code awaitBatchLight} 的提交半段；批级 allOf 全等已移除）：
     * 对每柱先提交官方 {@code initializeLight(chunk, isLighted)}（range=0，不等邻柱），
     * 其 future 完成后再等 8 邻也完成建层，才提交 {@code lightChunk}（见
     * {@link #startLightBarrier}）。对齐原版 ChunkMap：{@code LIGHT} range=1，
     * 邻柱方块与 DataLayer 已在，一次 {@code propagateLightSources} 就能把天空光
     * increase 进屋檐；算完再打官方 {@code ClientboundLevelChunkWithLightPacket}。
     * <p>
     * 提交即从投递队列条件移除（管道化前提：否则消费循环会重复取同一批）——
     * {@code pending.remove(key, token)} / {@code generated.remove(key, gen)} 即
     * REPLACE 守卫：屏障前已被同 key 新投递覆盖的旧包放弃本屏障（新包下一轮消费处理）。
     * {@link #LIGHT_ENGINE_MUTEX} 只覆盖本提交循环（addTask，微秒级），绝不覆盖
     * 等待/回调/回传：与 {@code ShadowSeedServer.clearChunkLight} 等清光投递互斥，
     * 串行化「投递」顺序；执行层交错由 {@code MixinLayerLightSectionStorage} null 兜底
     * （2026-08-14 1.20.1 定位，读取层勿动）。
     * <p>
     * per-chunk 完成回调（引擎线程）→ {@link #completeLight}（条件移除在途条目 =
     * exactly-once + 断连短路）→ 移交后台池 {@link #finishLight} 立即打包回传
     * （buildPacket ~ms 级，不得占引擎 Worker 线程）；提交失败 / 5s 超时 → 欠光标脏
     * 仍推首包（{@link #sweepLightTimeouts}）。禁止以 isChunkLightComplete 重试挡首包，
     * 以免 PIPELINE_MAX_INFLIGHT 被占满导致吞吐塌缩。
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
                        if (inflightLight.containsKey(t.key) || waitingForLight.containsKey(t.key)) {
                            continue; // 不顶掉整柱；LightDelta 仍在队列，等首包 finishLight → pump
                        }
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
                    if (t.source != LightSource.PENDING
                            && shouldAccountLightBarrierMetric(t.source == LightSource.LIGHT_ONLY)) {
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
                    // 提交失败：欠光兜底（旧 result.put(key, FALSE) 同语义）——同步守卫+回传
                    finishLight(t, false);
                }
            }
        }
    }

    /**
     * 两阶段光屏障起始（vanilla {@code ChunkStatus.INITIALIZE_LIGHT} → {@code LIGHT}）：
     * {@code chunk.initializeLightSources()} + {@code engine.initializeLight(chunk, isLighted)}
     * （range=0），8 邻建层后再 {@code lightChunk}。
     * {@code isLighted} 仅磁盘/引擎已有光为 true（跳过 propagate），与原版
     * {@code ChunkStatus.isLighted} 同义。
     * <p>
     * 引擎 per-dimension：取目标 chunk 所属 level（{@code t.level}）的光照引擎，
     * 不再恒用 overworld——nether/end 柱的 initializeLight/lightChunk 必须路由到
     * 对应 ServerLevel 的引擎（REQ 明细6）。
     * <p>
     * 调用方必须已持有 {@link #LIGHT_ENGINE_MUTEX}（提交循环内）；本方法只投递任务。
     */
    private static void startLightBarrier(ShadowSeedServer server,
                                          LightTask t, long deadlineMs) {
        net.minecraft.server.level.ThreadedLevelLightEngine engine =
                (net.minecraft.server.level.ThreadedLevelLightEngine)
                        (t.level != null ? t.level : server.overworld())
                                .getChunkSource().getLightEngine();
        if (t.source != LightSource.LIGHT_ONLY) {
            waitingForLight.remove(t.key);
            initializedLight.remove(t.key);
        }
        t.chunk.initializeLightSources();
        boolean lighted = lightChunkHasExistingLight(t.metric == LightMetric.REUSE_CACHE);
        java.util.concurrent.CompletableFuture<net.minecraft.world.level.chunk.ChunkAccess> initFuture =
                engine.initializeLight(t.chunk, lighted);
        InflightLight inf = new InflightLight(t.key, t.source, t.token,
                t.chunk, t.level, deadlineMs, t.metric, t.renderOnly, t.traceOrigin);
        inf.submittedAtNs = System.nanoTime();
        inflightLight.put(t.key, inf);
        initFuture.whenComplete((chunkAccess, throwable) ->
                onInitializeComplete(server, inf, throwable == null));
        if (server != null && t.metric == LightMetric.RECOMPUTE
                && (t.source == LightSource.PENDING || t.source == LightSource.GENERATED)) {
            submitPreviewLight(server, t, initFuture);
        }
    }

    /**
     * 提交隔离预览算光。计算与 {@code initializeLight} 重叠；首推等到 init
     * 完成。邻柱已齐、已提交 {@code lightChunk} 时仍推预览，避免林火把真引擎
     * 队列拖死导致 R1 空洞。已落地影子全量包或屏障结束则丢预览。
     */
    private static void submitPreviewLight(ShadowSeedServer server, LightTask t,
                                           CompletableFuture<?> initDone) {
        if (server == null || t == null || t.chunk == null) {
            return;
        }
        PREVIEW_POOL.execute(() -> computeAndPushPreview(server, t, initDone));
    }

    /**
     * 隔离官方 {@link LevelLightEngine} 逐柱预计算，用该隔离实例打包首包
     * （{@link #offerReady} converged=false）。<b>不</b>写入真引擎
     * {@code queuedSections} / {@code updatingSectionData}。
     * <p>
     * T1 曾经 {@code LightEngine.queueSectionData} 同步 put 真引擎 queued，以便
     * {@code getDataLayerData} 立刻可读。非空 put 会置 {@code hasInconsistencies}，
     * 任意柱 Worker {@code runUpdate → markNewInconsistencies} 用 {@code fastIterator}
     * 扫全局 queued——synchronized map 的 iterator 不锁整段，与预览线程 put 并发
     * → Worker-Main 异常（ovd1 LogAudit {@code Caught exception in thread
     * Worker-Main-*}）。park 期间下一次 {@code runUpdate} 还会把隔离层采纳进
     * updating；{@code queueSectionData(null)} 只删 queued，清不掉已采纳层。
     * <p>
     * <b>欠估不变量</b>：shim 只返回本柱，邻柱 {@code getChunkForLighting=null}
     * → {@code getState} 回落基岩，无入流 ⇒ 预览 ≤ 收敛终值。真引擎
     * {@code lightChunk} 只播种 + 增加传播；隔离结果不进真引擎，无过亮残留。
     * 收敛仍走 {@code finishLight → pushReady(converged=true)} REPLACE。
     * <p>
     * 锁序（CONTRACTS，禁止新的 chunkLock × LIGHT_ENGINE_MUTEX 嵌套）：
     * 隔离计算与从隔离引擎打包持 {@link #chunkLock}（PalettedContainer 同域），
     * <b>不</b>持 {@link #LIGHT_ENGINE_MUTEX}。
     */
    private static void computeAndPushPreview(ShadowSeedServer server, LightTask t,
                                              CompletableFuture<?> initDone) {
        if (!isEnabled() || server == null || t == null || t.chunk == null) {
            return;
        }
        LevelLightEngine isolated;
        ChunkPos pos = t.chunk.getPos();
        synchronized (chunkLock(pos)) {
            try {
                isolated = computeIsolatedPreviewEngine(t.chunk);
            } catch (Throwable ignored) {
                return;
            }
        }
        if (isolated == null) {
            return;
        }
        if (initDone != null) {
            try {
                initDone.join();
            } catch (Throwable ignored) {
                return; // init 失败：onInitializeComplete 已走欠光路径，丢预览
            }
        }
        waitUntilInitChoseStage(t);
        InflightLight live = waitingForLight.get(t.key);
        if (live == null) {
            live = inflightLight.get(t.key);
        }
        boolean barrierStillLive = live != null && live.chunk == t.chunk;
        if (!shouldPushIsolatedPreview(alreadyShadowApplied(t.key), isSuperseded(t), barrierStillLive)
                || !isEnabled()
                || server.injectedChunk(DimensionKey.dimensionOf(t.key), pos.x, pos.z) != t.chunk) {
            return;
        }
        try {
            ClientboundLevelChunkWithLightPacket packet;
            synchronized (chunkLock(pos)) {
                packet = new ClientboundLevelChunkWithLightPacket(t.chunk, isolated, null, null);
            }
            offerReady(t.key, pos, packet, false, t.renderOnly, t.traceOrigin);
        } catch (Throwable ignored) {
            // 预览失败由收敛兜底
        }
    }

    /**
     * join(init) 之后 {@code whenComplete → onInitializeComplete} 可能尚未跑完
     * （CompletableFuture 依赖栈 LIFO）。等到 park / lightChunk 已提交 / 屏障消失。
     */
    private static void waitUntilInitChoseStage(LightTask t) {
        long deadlineNs = System.nanoTime() + 50_000_000L;
        while (System.nanoTime() < deadlineNs) {
            if (waitingForLight.containsKey(t.key)) {
                return;
            }
            InflightLight inf = inflightLight.get(t.key);
            if (inf == null || inf.lightChunkSubmitted) {
                return;
            }
            LockSupport.parkNanos(100_000L);
        }
    }

    /**
     * 官方流水线（禁止自研 BFS）：非空 section {@code updateSectionStatus(present)}
     * → {@code setLightEnabled(true)}（真引擎 init(lighted=false) 会关掉 sources；
     * 隔离引擎必须为 true，否则 {@code columnsWithSources} 空、天光 15 不会填）
     * → {@code propagateLightSources} → {@code runLightUpdates}。
     * 调用方从返回的隔离引擎打包，不把层写入真引擎。
     */
    private static LevelLightEngine computeIsolatedPreviewEngine(LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        SingleChunkGetter shim = new SingleChunkGetter(chunk);
        // 欠估不变量：隔离引擎无邻柱入流 ⇒ 预览 ≤ 收敛终值；真引擎 lightChunk 只增加传播。
        LevelLightEngine isolated = new LevelLightEngine(shim, true, true);
        net.minecraft.world.level.chunk.LevelChunkSection[] sections = chunk.getSections();
        int minSection = io.github.limuqy.mc.hassium.compat.LevelHeightCompat.getMinSection(chunk);
        for (int i = 0; i < chunk.getSectionsCount(); i++) {
            if (!sections[i].hasOnlyAir()) {
                isolated.updateSectionStatus(SectionPos.of(pos, minSection + i), false);
            }
        }
        isolated.setLightEnabled(pos, true);
        isolated.propagateLightSources(pos);
        isolated.runLightUpdates();
        return isolated;
    }

    /** 阶段①完成 → 全量重算等 8 邻建层后进阶段② {@code lightChunk}（光桥/增量不等邻）。 */
    private static void onInitializeComplete(ShadowSeedServer server, InflightLight inf, boolean converged) {
        if (!isEnabled() || (inflightLight.get(inf.key) != inf && waitingForLight.get(inf.key) != inf)) {
            return; // 断连 / 同 key 新屏障 REPLACE：旧链路短路
        }
        if (!converged) {
            completeLight(inf, false);
            return;
        }
        initializedLight.put(inf.key, Boolean.TRUE);
        try {
            synchronized (LIGHT_ENGINE_MUTEX) {
                if (inflightLight.get(inf.key) != inf || isSuperseded(inf)) {
                    completeLight(inf, false); // 新数据已接管：旧任务让位（finishLight 的 REPLACE 守卫会短路）
                    return;
                }
                ChunkPos pos = new ChunkPos(DimensionKey.chunkXOf(inf.key),
                        DimensionKey.chunkZOf(inf.key));
                if (canStartVanillaLightStageNow(server, pos, inf)) {
                    submitLightChunkLocked(server, inf);
                } else {
                    // park 的柱不推欠光包（对齐原版单人：区块包只在 LIGHT 完成后发一次）。
                    // 欠光预览要么整柱黑（RECOMPUTE init 后引擎层仍空），要么诱发客户端
                    // 自算+影子收敛的双重计算；超时由 sweep 兜底放行，不会无限挂。
                    parkForVanillaLightNeighbors(inf);
                }
                flushWaitingAroundLocked(server, DimensionKey.dimensionOf(inf.key), pos);
                // 末根邻柱 init 完成时，把 9 柱内因「邻未收敛」被 defer 的任务按真收敛态重推
                //（屋檐跨界 section 的后到光由此补齐；见 areVanillaLightNeighborsConverged）。
                promoteDeferredAfterNeighborsLocked(server, DimensionKey.dimensionOf(inf.key), pos);
            }
        } catch (Throwable ex) {
            completeLight(inf, false);
        }
        if (inflightLight.size() < PIPELINE_LOW_WATER
                && hasStartablePendingWork()
                && isEnabled()) {
            pump();
        }
    }

    private static boolean needsVanillaLightNeighborWait(LightTask task) {
        if (task.source == LightSource.LIGHT_ONLY || task.source == LightSource.DELTA) {
            return false;
        }
        return needsVanillaLightNeighborWait(task.metric != LightMetric.REUSE_CACHE);
    }

    private static void parkForVanillaLightNeighbors(InflightLight inf) {
        if (inf.packWaitStartMs == 0L) {
            inf.packWaitStartMs = System.currentTimeMillis();
        }
        if (!inflightLight.remove(inf.key, inf)) {
            return;
        }
        waitingForLight.put(inf.key, inf);
        ChunkPos pos = new ChunkPos(DimensionKey.chunkXOf(inf.key), DimensionKey.chunkZOf(inf.key));
        DebugLogger.info(DebugLogger.LogType.CHUNK_APPLY,
                "[SHADOW_LIGHT] Wait neighbors before lightChunk ({}, {})", pos.x, pos.z);
    }

    private static void submitLightChunkLocked(ShadowSeedServer server, InflightLight inf) {
        if (inflightLight.get(inf.key) != inf) {
            return;
        }
        if (isSuperseded(inf)) {
            completeLight(inf, false);
            return;
        }
        inf.lightChunkSubmitted = true;
        // 引擎 per-dimension：lightChunk 路由到目标 chunk 所属 level 的引擎（同 startLightBarrier）。
        net.minecraft.server.level.ThreadedLevelLightEngine engine =
                (net.minecraft.server.level.ThreadedLevelLightEngine)
                        (inf.level != null ? inf.level : server.overworld())
                                .getChunkSource().getLightEngine();
        boolean hasExistingLight = lightChunkHasExistingLight(inf.metric == LightMetric.REUSE_CACHE);
        ShadowLightProbe.onBeforeLightChunk(server, engine, inf.chunk);
        java.util.concurrent.CompletableFuture<net.minecraft.world.level.chunk.ChunkAccess> lightFuture =
                engine.lightChunk(inf.chunk, hasExistingLight);
        // 屋檐跨界：邻柱 lightChunk 未完时边界 section 可能欠光。那只影响 LIGHT_ONLY
        // 的 settled 证据与后续补光；首包不得等邻柱林火停（否则 R1 着火区长时间空洞）。
        lightFuture.whenComplete((chunkAccess, throwable) -> {
            // LIGHT_ONLY 收敛重算走注册表视图（并发 relight 波中引擎三表恒有在途）。
            // 首包只看本柱 lightChunk 成功：邻柱林火不得把尚未落地的柱标成未收敛。
            boolean lightOk = throwable == null;
            boolean neighborsIdle = inf.source == LightSource.LIGHT_ONLY
                    ? areRegistryNeighborsSettled(inf.key)
                    : areVanillaLightNeighborsConverged(server, inf);
            boolean publishReady = firstPacketLightReady(lightOk, neighborsIdle,
                    inf.source == LightSource.LIGHT_ONLY);
            ShadowLightProbe.onLightChunkComplete(engine, inf.chunk, throwable, neighborsIdle);
            if (throwable == null) {
                // E1 LIT 唯一事件源：覆盖 PENDING/GENERATED/DELTA/LIGHT_ONLY 全部屏障来源。
                // REUSE_CACHE 的值来自存档/既有层 → 记「未在会话内计算」，SURROUNDED 时
                // 恒触发一次性校验重算（错误定值不得随 type 126 存档复活）。
                LightReadinessRegistry.onLightComputed(inf.key,
                        inf.metric == LightMetric.REUSE_CACHE, lightOk && neighborsIdle,
                        System.currentTimeMillis());
            }
            completeLight(inf, publishReady);
        });
    }

    /**
     * 8 邻 lightChunk 均已完成（已 initializeLight 且不在等待/在途表）。
     * 只读三个 ConcurrentHashMap 快照，map 本身线程安全；调用方通常已持
     * {@link #LIGHT_ENGINE_MUTEX}（与 {@link #canStartVanillaLightStageNow} 同视图）。
     */
    private static boolean areVanillaLightNeighborsConverged(ShadowSeedServer server, InflightLight inf) {
        return areVanillaLightNeighborsConverged(inf.key);
    }

    /**
     * 方案 D：LIGHT_ONLY 收敛重算完成时的 converged 判定用注册表视图——
     * 并发 relight 波中邻柱常在 inflight/waiting（引擎三表），恒判 false 会使
     * settled=true 终值证据永不达成、传播死锁。registry 视图语义：
     * 8 邻 phase==LIT 且无 pendingConverge（park 未 LIT 的邻柱按未就绪计，
     * 与官方 INITIALIZE_LIGHT 硬依赖对齐）。跃迁波首跑仍用引擎三表严格判定。
     */
    private static boolean areRegistryNeighborsSettled(long key) {
        return LightReadinessRegistry.areNeighborsSettled(key);
    }

    /** 同上，按复合键判定（deferred 任务重推前使用）。 */
    private static boolean areVanillaLightNeighborsConverged(long key) {
        String dimension = DimensionKey.dimensionOf(key);
        int cx = DimensionKey.chunkXOf(key);
        int cz = DimensionKey.chunkZOf(key);
        for (int[] d : LIGHT_NEIGHBOR_OFFSETS) {
            long nKey = DimensionKey.key(dimension, cx + d[0], cz + d[1]);
            if (!initializedLight.containsKey(nKey)
                    || waitingForLight.containsKey(nKey)
                    || inflightLight.containsKey(nKey)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 本柱 init 完成后（调用方持 {@link #LIGHT_ENGINE_MUTEX}）：扫本柱+8 邻的
     * deferredLightPush，凡 8 邻 lightChunk 已齐（{@link #areVanillaLightNeighborsConverged}）
     * 的任务移出暂缓表并按 converged=true 重推——先完成柱此前以 false 推送/defer，
     * 其边界 section 的跨柱蔓延由末根邻柱完成时的这次重推补齐。
     */
    private static void promoteDeferredAfterNeighborsLocked(ShadowSeedServer server,
                                                            String dimension, ChunkPos pos) {
        if (server == null || deferredLightPush.isEmpty()) {
            return;
        }
        for (int[] d : LIGHT_STAGE_OFFSETS) {
            long key = DimensionKey.key(dimension, pos.x + d[0], pos.z + d[1]);
            LightTask task = deferredLightPush.get(key);
            if (task == null || isSuperseded(task)
                    || inflightLight.containsKey(key) || waitingForLight.containsKey(key)) {
                continue;
            }
            if (!areVanillaLightNeighborsConverged(key)) {
                continue;
            }
            if (!deferredLightPush.remove(key, task)) {
                continue;
            }
            enqueueDeferredPush(server, task, true);
        }
    }

    /** 在途槽位腾出时由 {@link #completeLight} 触发；邻柱 init 完成走 {@link #flushWaitingAroundLocked}。 */
    private static void tryFlushWaitingLight(ShadowSeedServer server) {
        if (server == null || waitingForLight.isEmpty()) {
            return;
        }
        synchronized (LIGHT_ENGINE_MUTEX) {
            for (InflightLight inf : new ArrayList<>(waitingForLight.values())) {
                if (inflightLight.size() >= PIPELINE_MAX_INFLIGHT) {
                    break;
                }
                promoteWaitingToLightChunkLocked(server, inf);
            }
        }
    }

    private static void flushWaitingAroundLocked(ShadowSeedServer server, String dimension, ChunkPos pos) {
        if (server == null || pos == null || dimension == null) {
            return;
        }
        for (int[] d : LIGHT_STAGE_OFFSETS) {
            if (inflightLight.size() >= PIPELINE_MAX_INFLIGHT) {
                return;
            }
            // 邻柱键与等待表同维：waitingForLight 键已携带维度，裸 pos 查找恒 miss
            long nKey = DimensionKey.key(dimension, pos.x + d[0], pos.z + d[1]);
            InflightLight waiter = waitingForLight.get(nKey);
            if (waiter != null) {
                promoteWaitingToLightChunkLocked(server, waiter);
            }
        }
    }

    private static void promoteWaitingToLightChunkLocked(ShadowSeedServer server, InflightLight inf) {
        if (inf == null || server == null) {
            return;
        }
        ChunkPos pos = new ChunkPos(DimensionKey.chunkXOf(inf.key), DimensionKey.chunkZOf(inf.key));
        if (!canStartVanillaLightStageNow(server, pos, inf)) {
            return;
        }
        if (isSuperseded(inf)) {
            waitingForLight.remove(inf.key, inf);
            return;
        }
        if (!waitingForLight.remove(inf.key, inf)) {
            return;
        }
        inflightLight.put(inf.key, inf);
        submitLightChunkLocked(server, inf);
    }

    private static boolean canStartVanillaLightStageNow(ShadowSeedServer server, ChunkPos pos,
                                                        InflightLight inf) {
        // 对齐原版 ChunkStatus.LIGHT：全量重算等 8 邻 initializeLight；超时按视距边缘。
        if (!needsVanillaLightNeighborWait(inf)) {
            return true;
        }
        long waitedMs = inf.packWaitStartMs <= 0L
                ? 0L
                : Math.max(0L, System.currentTimeMillis() - inf.packWaitStartMs);
        boolean timedOut = waitedMs >= NEIGHBOR_PACK_WAIT_MS
                || System.currentTimeMillis() >= inf.deadlineMs;
        String dimension = DimensionKey.dimensionOf(inf.key);
        int expected = 0;
        int ready = 0;
        for (int[] d : LIGHT_NEIGHBOR_OFFSETS) {
            int nx = pos.x + d[0];
            int nz = pos.z + d[1];
            if (!isVanillaLightNeighborExpected(server, dimension, nx, nz)) {
                continue;
            }
            expected++;
            if (isVanillaLightNeighborReadyNow(server, dimension, nx, nz)) {
                ready++;
            }
        }
        if (canStartVanillaLightStage(ready, expected, timedOut) && timedOut && ready < expected) {
            // 超时放行且仍有邻柱未就绪：propagateLightSources 对缺失邻柱回退 emptyChunkSources
            //（NEGATIVE_INFINITY → 该方向播种 flag 永不置位），边界列播种静默丢失（E=3 vs E=5 形态）。
            DebugLogger.warn(DebugLogger.LogType.ASYNC,
                    "[SHADOW_LIGHT] Neighbor wait timed out with {} of {} neighbors not ready at ({}, {}),"
                            + " border seeding may be incomplete",
                    expected - ready, expected, pos.x, pos.z);
        }
        return canStartVanillaLightStage(ready, expected, timedOut);
    }

    private static boolean isVanillaLightNeighborExpected(ShadowSeedServer server, String dimension,
                                                          int nx, int nz) {
        boolean holderPresent = server != null && server.hasVisibleChunkHolder(nx, nz);
        long nKey = DimensionKey.key(dimension, nx, nz);
        boolean inInjectOrPipeline = initializedLight.containsKey(nKey)
                || waitingForLight.containsKey(nKey)
                || inflightLight.containsKey(nKey)
                || pending.containsKey(nKey)
                || generated.containsKey(nKey)
                || (server != null && server.injectedChunk(dimension, nx, nz) != null);
        return isVanillaLightNeighborExpected(holderPresent, inInjectOrPipeline, withinLightTicket(nx, nz));
    }

    private static boolean isVanillaLightNeighborReadyNow(ShadowSeedServer server, String dimension,
                                                          int nx, int nz) {
        boolean holderParent = server != null && server.hasInitializeLightParent(nx, nz);
        boolean selfInit = initializedLight.containsKey(DimensionKey.key(dimension, nx, nz));
        return isVanillaLightNeighborReady(holderParent, selfInit);
    }

    /**
     * 原版 ChunkMap 用 ticket/holder 决定邻柱是否存在。票尚未被 pollTask 消化时
     * 用玩家视距方形窗口近似，避免同波包还在解码时 expected==0 立刻 lightChunk。
     */
    private static boolean withinLightTicket(int nx, int nz) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null) {
                return false;
            }
            int px = (int) Math.floor(mc.player.getX()) >> 4;
            int pz = (int) Math.floor(mc.player.getZ()) >> 4;
            int radius = io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService
                    .resolveEffectiveClientVD(mc) + 3;
            return Math.max(Math.abs(nx - px), Math.abs(nz - pz)) <= radius;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 锁外忙等：把 {@code ThreadedLevelLightEngine.lightTasks} 压回
     * {@link #ENGINE_TASK_LOW_WATER}。目标：任务总量恒 < 1000（vanilla addTask 的
     * sorter 线程并发 runUpdate 阈值）——sorter 线程与 taskMailbox 线程并发 runUpdate
     * 同一 lightTasks 列表（非线程安全）时任务错序（propagateLightSources 先于
     * updateSectionStatus / POST 先于 PRE）→ 播种在建层前执行 → 空光层打包 → 客户端
     * 黑块。忙等期间主动 {@code tryScheduleUpdate} 驱动 mailbox 消化（不必等影子端
     * 主循环 100µs 轮询）；5s 超时兜底防死锁（超时继续，由 sweepLightTimeouts
     * 欠光首包兜底，不阻塞投递链）。
     * <p>
     * 包可见：ShadowSeedServer 的大批量清光/重算投递路径（injectChunk 重注入、
     * relightChunk、invalidateLightSections）同样需要按柱 drain，防单次投递越阈值。
     * 忙等无锁依赖（只等 mailbox 消化），可在任意非引擎线程调用。
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
                    // 引擎关闭/断连竞态：忙等靠超时兜底退出
                }
                java.util.concurrent.locks.LockSupport.parkNanos(200_000L);
            }
        } catch (Throwable ignored) {
            // 类型转换/accessor 异常（版本差异）：跳过水位控制（保守降级为旧行为）
        }
    }

    /**
     * 光屏障完成入口（回调 = 引擎 Worker 线程；超时扫表 = 主线程/消费线程）：条件移除
     * 在途条目——移除成功 = 本条目胜出（exactly-once：完成回调 vs 超时兜底）；移除失败 =
     * 已被同 key 新屏障 REPLACE / 断连清理（{@link #onDisconnect} 清表）/ 超时已处理 →
     * 短路丢弃（旧回调不得覆盖新投递，断连竞态兜底）。
     * <p>
     * 只做簿记（微秒级）后移交后台池执行回传：{@code whenComplete} 运行在引擎
     * ForkJoinPool Worker 线程，直接 {@link #pushReady}（buildPacket 序列化 ~ms 级）
     * 会占住引擎算光线程拖垮吞吐，故由 HassiumTaskExecutor 承接打包。
     *
     * @return true=本条目胜出并移交回传（超时扫表据此打日志）
     */
    private static boolean completeLight(InflightLight inf, boolean converged) {
        boolean parked = waitingForLight.remove(inf.key, inf);
        boolean inflight = inflightLight.remove(inf.key, inf);
        if (!parked && !inflight) {
            return false; // 已被超时兜底 / 断连清理 / 同 key 新投递 REPLACE：短路丢弃
        }
        // 屏障胜出 = 该柱即将由 finishLight 回传全量光（chunk 包或全柱光包）——
        // 立即丢弃已收集未消费的光更新掩码，堵住「completeLight 移除在途后、
        // finishLight offer 前，drainLightMasks 把中间态（含空层）打成光包」的窗口。
        discardLightMask(inf.key);
        ShadowSeedServer wakeServer = ShadowServerRegistry.getInstance().get();
        if (wakeServer != null) {
            // 在途槽位腾出：触发式重试因 PIPELINE 满而没能 promote 的 waiter，
            // 不等 consumeLoop / 帧尾扫表。
            tryFlushWaitingLight(wakeServer);
        }
        HassiumTaskExecutor executor = HassiumTaskExecutor.getClient();
        if (executor == null || !executor.isRunning() || !isEnabled()) {
            return true; // 断连竞态：影子已关/队列已清（onDisconnect 清空 pending/generated/ready）→ 丢弃
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.isSameThread()) {
            // drainReady / sweepLightTimeouts 已在主线程：同步打包入 ready，
            // 同帧就能 apply+ACK。丢给线程池会让本帧 ready 仍空、early-return，
            // 服务端 10-batch 窗口卡到 delivery timeout。
            finishLight(inf, converged);
            if (inflightLight.size() < PIPELINE_LOW_WATER && hasStartablePendingWork() && isEnabled()) {
                pump();
            }
            return true;
        }
        try {
            executor.submit(() -> finishLight(inf, converged), TaskCategory.BEST_EFFORT);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // 池已停（断连竞态）：丢弃
        }
        return true;
    }

    /**
     * 回传执行（后台池）：{@code lightChunk} future / 超时兜底到达后立即首包。
     * 对齐单人：LIGHT 完成才发包，不再忙等播种或补跑 propagate。
     */
    private static void finishLight(LightTask task, boolean converged) {
        if (!isEnabled()) {
            return; // 断连竞态：影子已关（队列已清，守卫亦短路）
        }
        // 方块级新投递才作废旧回传。LightDelta 不取消整柱首包：林火只排队，
        // 本方法推完 chunk 后再 pump 触发 relight。
        if (isSuperseded(task)) {
            deferredLightPush.remove(task.key, task);
            return;
        }
        ChunkPos pos = new ChunkPos(DimensionKey.chunkXOf(task.key), DimensionKey.chunkZOf(task.key));
        ShadowSeedServer server = ShadowServerRegistry.getInstance().get();
        boolean pushConverged = converged;
        if (task.metric == LightMetric.RECOMPUTE) {
            long elapsedNs = task.submittedAtNs > 0L
                    ? Math.max(0L, System.nanoTime() - task.submittedAtNs)
                    : 0L;
            io.github.limuqy.mc.hassium.metrics.NetworkStats.recordLightRecomputeBackgroundTime(elapsedNs);
        }
        if (!shouldPublishToClient(haloKeys.containsKey(task.key))) {
            if (server != null && task.chunk != null) {
                server.syncLightCorrect(task.chunk, false);
            }
            if (inflightLight.size() < PIPELINE_LOW_WATER
                    && hasStartablePendingWork()
                    && isEnabled()) {
                pump();
            }
            return;
        }
        boolean alreadyApplied = alreadyShadowApplied(task.key);
        if (shouldDeferIncompleteClientOverwrite(alreadyApplied, pushConverged)) {
            deferredLightPush.put(task.key, task);
            if (server != null && task.chunk != null) {
                server.syncLightCorrect(task.chunk, false);
            }
            DebugLogger.warn(DebugLogger.LogType.ASYNC,
                    "[SHADOW_LIGHT] Defer incomplete overwrite ({}, {}): shadow chunk already applied",
                    pos.x, pos.z);
            if (inflightLight.size() < PIPELINE_LOW_WATER
                    && hasStartablePendingWork()
                    && isEnabled()) {
                pump();
            }
            return;
        }
        if (!pushConverged) {
            DebugLogger.warn(DebugLogger.LogType.ASYNC,
                    "[SHADOW_LIGHT] Light incomplete/timeout ({}, {}), pushing first packet with partial light",
                    pos.x, pos.z);

        }
        deferredLightPush.remove(task.key);
        try {
            if (task.source == LightSource.LIGHT_ONLY) {
                LightWork work = task.token instanceof LightWork w ? w : null;
                pushLightReady(pos, task.level, task.chunk, pushConverged, work);
            } else {
                pushReady(task.key, task.chunk, task.level, pushConverged, task.renderOnly,
                        task.traceOrigin);
            }
        } catch (Throwable t) {
            DebugLogger.warn(DebugLogger.LogType.ASYNC,
                    "[SHADOW_CHUNK] Build failed ({}, {})", pos.x, pos.z);
        }
        if (server != null && task.chunk != null) {
            server.syncLightCorrect(task.chunk,
                    pushConverged && LightReadinessRegistry.isSettled(task.key));
        }
        if (inflightLight.size() < PIPELINE_LOW_WATER
                && hasStartablePendingWork()
                && isEnabled()) {
            pump();
        }
    }

    /** 原版 LIGHT range=1：含对角的 8 邻。 */
    private static final int[][] LIGHT_NEIGHBOR_OFFSETS = {
            {-1, -1}, {-1, 0}, {-1, 1},
            {0, -1}, {0, 1},
            {1, -1}, {1, 0}, {1, 1}
    };
    /** 本柱 + 8 邻，用于唤醒等待 LIGHT 的柱。 */
    private static final int[][] LIGHT_STAGE_OFFSETS = {
            {0, 0},
            {-1, -1}, {-1, 0}, {-1, 1},
            {0, -1}, {0, 1},
            {1, -1}, {1, 0}, {1, 1}
    };
    /** 丢弃某柱已收集、尚未消费的光照更新掩码，以及回传队列里尚未落地的旧光包。 */

    private static void discardLightMask(long key) {
        LightMask mask = lightUpdates.remove(key);
        if (mask != null) {
            synchronized (mask) {
                mask.discarded = true;
                mask.skySections.clear();
                mask.blockSections.clear();
            }
        }
        ChunkPos pos = new ChunkPos(DimensionKey.chunkXOf(key), DimensionKey.chunkZOf(key));
        dropQueuedLights(pos);
    }

    /** 区块首包入队前丢掉该柱旧光，避免 FIFO 下旧空光排在新区块包之后盖暗。 */
    private static void dropQueuedLights(ChunkPos pos) {
        ready.removeIf(item -> item.lightPacket != null
                && item.lightPacket.getX() == pos.x && item.lightPacket.getZ() == pos.z);
    }

    /**
     * per-chunk 超时兜底扫表（提交 5s 未完成 → 仍推首包 + 欠光标脏，补光走光照更新桥梁）：
     * 主线程帧尾 {@link #drainReady} 为主扫描点 + 消费循环轮顶第二扫描点。
     * 与完成回调竞速同一 {@link #completeLight}（条件移除 exactly-once），谁先赢谁回传；
     * 不重跑屏障等待层齐全。
     */
    private static void sweepLightTimeouts() {
        ShadowSeedServer server = ShadowServerRegistry.getInstance().get();
        long now = System.currentTimeMillis();
        if (server != null && !waitingForLight.isEmpty()) {
            synchronized (LIGHT_ENGINE_MUTEX) {
                for (InflightLight inf : new ArrayList<>(waitingForLight.values())) {
                    if (now >= inf.deadlineMs) {
                        if (waitingForLight.remove(inf.key, inf)) {
                            completeLight(inf, false);
                            DebugLogger.warn(DebugLogger.LogType.ASYNC,
                                    "[SHADOW_LIGHT] Light timeout ({}ms) ({}, {}), pushing with partial light",
                                    CONVERGENCE_WAIT_TIMEOUT_MS, DimensionKey.chunkXOf(inf.key),
                                    DimensionKey.chunkZOf(inf.key));
                        }
                        continue;
                    }
                    if (inflightLight.size() >= PIPELINE_MAX_INFLIGHT) {
                        // 已等满邻居门槛等待期的 waiter 豁免在途上限：parked 不占引擎
                        // 任务位，饱和期无限顺延会把欠光窗口拉长到秒级（R1 随机黑放大器）。
                        long waitedMs = inf.packWaitStartMs <= 0L ? 0L
                                : Math.max(0L, now - inf.packWaitStartMs);
                        if (waitedMs < NEIGHBOR_PACK_WAIT_MS) {
                            continue;
                        }
                    }
                    promoteWaitingToLightChunkLocked(server, inf);
                }
            }
        }
        if (inflightLight.isEmpty()) {
            return;
        }
        for (InflightLight inf : inflightLight.values()) {
            if (now >= inf.deadlineMs && completeLight(inf, false)) {
                DebugLogger.warn(DebugLogger.LogType.ASYNC,
                        "[SHADOW_LIGHT] Light timeout ({}ms) ({}, {}), pushing with partial light",
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
        ClientboundLevelChunkWithLightPacket packet;
        synchronized (chunkLock(pos)) {
            packet = SeedGenChunkCodec.buildPacket(chunk, level);
        }
        offerReady(key, pos, packet, converged, renderOnly, traceOrigin);
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
        dropQueuedLights(pos);
        ready.offer(new ReadyItem(packet, null, renderOnly, traceOrigin, null, null),
                new KeyedPriorityQueue.Key(ChunkPos.asLong(pos.x, pos.z),
                        io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher.OP_CHUNK_APPLY,
                        DimensionKey.dimensionOf(key)),
                fifoApplyPriority(),
                KeyedPriorityQueue.OfferPolicy.REPLACE);
        if (!converged) {
            DebugLogger.warn(DebugLogger.LogType.ASYNC,
                    "[SHADOW_CHUNK] Converge pending ({}, {}), pushing with partial light",
                    pos.x, pos.z);
        }
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
        if (!converged && skyMask != null) {
            ShadowSeedServer server = ShadowServerRegistry.getInstance().get();
            omitUnlitEavesFromSkyMask(skyMask, pos, level.getLightEngine(),
                    level.getLightEngine().getMinLightSection(), server, level);
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
        tickChunkUnload();
        ShadowLightProbe.onEngineTick(); // T3 探针：引擎终态周期快照（debug.lightVerify 门控）
        confirmLightsCorrectIfConverged();
        flushDeferredLightPushes();
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
        if (hasStartablePendingWork()
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
                    if (!shadowApplyEpochs.containsKey(DimensionKey.key(
                            entry.key().dimension(), entry.key().posLong()))) {
                        // 尚无影子区块包：丢掉这条旧光，等带光首包。
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
        // ready 队列键 posLong 保持裸 ChunkPos.asLong（MainThreadDispatcher 距离优先级解包），
        // 维度在 Key.dimension；epoch/trace 表键为复合键 → 此处组复合键
        long chunkKey = DimensionKey.key(entry.key().dimension(), chunkX, chunkZ);
        boolean ovdRenderOnly = item.renderOnly();
        io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService viewDistance =
                io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService.getInstance();
        boolean isWithinReceiveWindow = ovdRenderOnly
                ? viewDistance.isWithinCurrentClientView(chunkPos)
                : viewDistance.isWithinCurrentClientCacheWindow(chunkPos);
        boolean shouldKeep = viewDistance.shouldKeepAsRenderOnly(chunkPos);
        boolean renderOnly = ovdRenderOnly || shouldKeep;
        if (!isWithinReceiveWindow) {
            ClientChunkHandler.logShadowChunkApplyEvent("shadow_out_of_view", chunkPos, renderOnly,
                    item.traceOrigin());
            DebugLogger.info(DebugLogger.LogType.CHUNK_APPLY,
                    "[SHADOW_CHUNK] Dropping out-of-view chunk ({}, {}) origin={} renderOnly={}",
                    chunkX, chunkZ, item.traceOrigin(), renderOnly);
            return true;
        }
        if (ovdRenderOnly && !shouldKeep) {
            DebugLogger.info(DebugLogger.LogType.CHUNK_APPLY,
                    "[OVD_GEN] Dropping stale light result ({}, {}) after player moved",
                    chunkX, chunkZ);
            ClientChunkHandler.logShadowChunkApplyEvent("shadow_stale", chunkPos, true,
                    item.traceOrigin());
            return true;
        }
        if (ovdRenderOnly) {
            viewDistance.ensureExpandedRadius();
        }
        ClientChunkHandler.logShadowChunkApplyEvent("shadow_attempt", chunkPos, renderOnly,
                item.traceOrigin());
        connection.handleLevelChunkWithLight(item.chunkPacket);
        if (hasClientChunk(mc, chunkX, chunkZ)) {
            ClientChunkHandler.logShadowChunkApplyEvent("shadow_applied", chunkPos, renderOnly,
                    item.traceOrigin());
            shadowApplyEpochs.put(chunkKey, shadowApplyEpoch.incrementAndGet());
            recordFullApplyTrace(chunkKey, renderOnly, item.traceOrigin());
            if (!renderOnly) {
                // 来源分母已在可见柱 inject 时记（accountVisibleNetworkIngress / consumeLoop）。
                // 这里只记落地去重快照；SERVER_PUSH 不得再走 recordServerPushApplied，
                // 否则 getClientAppliedChunkCount 会把同一柱计两次。
                io.github.limuqy.mc.hassium.metrics.NetworkStats.recordChunkApplied(chunkX, chunkZ);
                io.github.limuqy.mc.hassium.cache.client.ClientMainThreadBudget.noteChunkApplyActivity();
            }
            if (renderOnly) {
                viewDistance.onRenderOnlyApplied(chunkPos);
                ((io.github.limuqy.mc.hassium.cache.client.IClientLevelExtension) mc.level)
                        .hassium$addRenderOnlyChunk(chunkPos.toLong());
            }
            io.github.limuqy.mc.hassium.network.ClientChunkHandler.probeChunkState(
                    chunkPos, mc.level, renderOnly ? "ovd" : "shadow");
            return true;
        }
        ClientChunkHandler.logShadowChunkApplyEvent("shadow_ignored", chunkPos, renderOnly,
                item.traceOrigin());
        if (ovdRenderOnly) {
            viewDistance.onRenderOnlyMiss(chunkPos);
            DebugLogger.info(DebugLogger.LogType.CHUNK_APPLY,
                    "[SHADOW_CHUNK] Vanilla ignored chunk ({}, {}) — not marked as applied",
                    chunkX, chunkZ);
            return true;
        }
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
        if (mc == null || mc.level == null) {
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
     * 帧尾：引擎任务排空或单柱层齐全时，把暂缓的欠光覆盖补推出去。
     * 引擎已全局收敛仍不齐全 → 仍推当前光（不会再变好），避免永久卡住。
     */
    private static void flushDeferredLightPushes() {
        if (deferredLightPush.isEmpty()) {
            return;
        }
        ShadowSeedServer server = ShadowServerRegistry.getInstance().get();
        if (server == null) {
            deferredLightPush.clear();
            return;
        }
        boolean engineIdle = false;
        try {
            engineIdle = server.isLightConverged();
        } catch (Throwable ignored) {
            // 保守：不因探测失败清空暂缓表
        }
        for (Map.Entry<Long, LightTask> e : new ArrayList<>(deferredLightPush.entrySet())) {
            LightTask task = e.getValue();
            if (task == null || !deferredLightPush.containsKey(e.getKey())) {
                continue;
            }
            if (isSuperseded(task) || inflightLight.containsKey(task.key)
                    || waitingForLight.containsKey(task.key)) {
                deferredLightPush.remove(e.getKey(), task);
                continue;
            }
            ChunkPos pos = new ChunkPos(DimensionKey.chunkXOf(task.key), DimensionKey.chunkZOf(task.key));
            boolean complete = server.isChunkLightComplete(pos, task.chunk);
            if (!complete && !engineIdle) {
                continue;
            }
            if (!deferredLightPush.remove(e.getKey(), task)) {
                continue;
            }
            enqueueDeferredPush(server, task, complete);
        }
    }

    /**
     * 暂缓柱打包放到后台池：主线程帧尾不能做序列化。
     */
    private static void enqueueDeferredPush(ShadowSeedServer server, LightTask task, boolean complete) {
        Runnable job = () -> pushDeferredNow(server, task, complete);
        HassiumTaskExecutor executor = HassiumTaskExecutor.getClient();
        if (executor == null || !executor.isRunning()) {
            job.run();
            return;
        }
        try {
            executor.submit(job, TaskCategory.BEST_EFFORT);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            job.run();
        }
    }

    private static void pushDeferredNow(ShadowSeedServer server, LightTask task, boolean complete) {
        ChunkPos pos = new ChunkPos(DimensionKey.chunkXOf(task.key), DimensionKey.chunkZOf(task.key));
        try {
            if (!shouldPublishToClient(haloKeys.containsKey(task.key))) {
                server.syncLightCorrect(task.chunk, false);
                return;
            }
            if (task.source == LightSource.LIGHT_ONLY) {
                LightWork work = task.token instanceof LightWork w ? w : null;
                pushLightReady(pos, task.level, task.chunk, complete, work);
            } else {
                pushReady(task.key, task.chunk, task.level, complete, task.renderOnly,
                        task.traceOrigin);
            }
            server.syncLightCorrect(task.chunk,
                    complete && LightReadinessRegistry.isSettled(task.key));
        } catch (Throwable t) {
            DebugLogger.warn(DebugLogger.LogType.ASYNC,
                    "[SHADOW_LIGHT] Deferred push failed ({}, {})", pos.x, pos.z);
        }
    }

    /**
     * 帧尾：引擎队列已空时把内存柱 {@code isLightCorrect} 打回 true，
     * 使随后 saveAll 写入 {@code isLightOn}（原版 LIGHT 完成后的落盘语义）。
     * 未排空则不动，欠光柱保持 isLightOn 缺省，重载再跑 LIGHT。
     */
    private static void confirmLightsCorrectIfConverged() {
        ShadowSeedServer server = ShadowServerRegistry.getInstance().get();
        if (server == null) {
            return;
        }
        try {
            server.confirmLightsCorrectIfConverged();
        } catch (Throwable t) {
            DebugLogger.warn(DebugLogger.LogType.ASYNC, "[SHADOW_LIGHT] Convergence check failed", t);
        }
    }

    /**
     * 出界卸载检查（客户端主线程帧尾，drainReady 开头；节流扫描）。
     * <p>
     * 卸载边界：OVD 开启（配置开启且生效，{@code ViewDistanceExtensionService.isEnabled()}）
     * → 有效距离 = {@code resolveEffectiveClientVD(mc)}；未开启 → 服务端视距
     * （{@code getLastServerVD()}，由帧尾 update 先行解析；未知 → 不卸载兜底）。
     * 距离 = 切比雪夫 {@code max(|dx|, |dz|) ≤ boundary}（与 OVD 方形语义一致）。
     * <p>
     * 防抖（需求 8 规格 3）：只有离开边界才登记计时（putIfAbsent 已登记不动），
     * 回界内立即取消，到期才落盘卸载——边界来回移动不触发反复卸载/加载。
     * 卸载前释放回传队列中该柱条目（chunk 包 + light 包，待回传不卸载）；
     * 每扫描周期限速 {@link #UNLOAD_PER_SCAN_MAX} 柱（规格 8-16 柱/帧取下限）。
     */
    private static void tickChunkUnload() {
        if (++unloadScanTick % UNLOAD_SCAN_INTERVAL_TICKS != 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.getConnection() == null) {
            return; // 未进服：无卸载边界上下文
        }
        if (mc.level != null) {
            // 客户端柱被卸载（含原版服务端卸载路径）→ 撤销「影子已落地」标记：
            // 光桥 gate 依赖该标记，残留标记会让重连/重载后的原版直发柱再次收到增量光。
            shadowApplyEpochs.keySet().removeIf(key -> {
                ChunkPos pos = new ChunkPos(DimensionKey.chunkXOf(key), DimensionKey.chunkZOf(key));
                return !hasClientChunk(mc, pos.x, pos.z);
            });
        }
        ShadowSeedServer server = ShadowServerRegistry.getInstance().get();
        if (server == null) {
            return;
        }
        int delaySecs = io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance()
                .getChunkUnloadDelaySecs();
        if (delaySecs <= 0) {
            unloadPending.clear(); // 0=禁用回收：清掉历史登记，不卸载
            return;
        }
        int boundary = resolveUnloadBoundary(mc);
        if (boundary <= 0 || boundary == Integer.MAX_VALUE) {
            return; // 边界未知：不卸载（安全兜底）
        }
        long now = System.currentTimeMillis();
        long delayMs = delaySecs * 1000L;
        // review-fix: T3-48：负数坐标 (int) 向零截断 → Mth.floor 向下取整
        int pcx = Mth.floor(mc.player.getX()) >> 4;
        int pcz = Mth.floor(mc.player.getZ()) >> 4;
        // 1) 出界登记 / 回界取消（遍历注入表；弱一致迭代安全）
        for (Map.Entry<Long, LevelChunk> e : server.injectedChunkEntries()) {
            long key = e.getKey();
            ChunkPos pos = new ChunkPos(DimensionKey.chunkXOf(key), DimensionKey.chunkZOf(key));
            if (isWithinBoundary(pos, pcx, pcz, boundary)) {
                unloadPending.remove(key); // 回界内：取消计时（区块驻留）
            } else {
                unloadPending.putIfAbsent(key, now + delayMs); // 出界：开始计时（已登记不动）
            }
        }
        // 2) 到期卸载（限速）
        int unloaded = 0;
        java.util.Iterator<Map.Entry<Long, Long>> it = unloadPending.entrySet().iterator();
        while (it.hasNext() && unloaded < UNLOAD_PER_SCAN_MAX) {
            Map.Entry<Long, Long> e = it.next();
            long key = e.getKey();
            if (now < e.getValue()) {
                continue; // 未到期
            }
            ChunkPos pos = new ChunkPos(DimensionKey.chunkXOf(key), DimensionKey.chunkZOf(key));
            if (isWithinBoundary(pos, pcx, pcz, boundary)) {
                it.remove(); // 步骤 1 与 2 之间的移动竞态：回界 → 取消
                continue;
            }
            LevelChunk chunk = server.injectedChunk(DimensionKey.dimensionOf(key), pos.x, pos.z);
            if (chunk == null) {
                it.remove(); // 已不在注入表（并发清理/替换）
                continue;
            }
            // 卸载前释放回传队列中该柱条目（待回传不卸载；KeyedPriorityQueue.removeIf）
            releaseReadyEntries(key);
            if (!server.unloadChunk(DimensionKey.dimensionOf(key), pos, chunk)) {
                it.remove(); // 落盘失败：放弃本柱（内存驻留，断连 saveAll 兜底）
                continue;
            }
            it.remove();
            discardLightMask(key); // 已卸载柱的光更新掩码作废（防跨会话残留 + drain 空转）
            LightReadinessRegistry.remove(key); // E1：卸载列退出就绪状态机（再入重建）
            shadowApplyEpochs.remove(key); // 客户端柱已卸载：光桥不再向其发增量光
            unloaded++;
            DebugLogger.info(DebugLogger.LogType.ASYNC,
                    "[SHADOW_UNLOAD] Unloaded ({}, {}) after leaving boundary, unloadDelay={}s",
                    pos.x, pos.z, delaySecs);
        }
    }

    /** 卸载边界解析：OVD 生效 → 客户端有效视距；否则服务端视距；未知 → 不卸载。 */
    private static int resolveUnloadBoundary(Minecraft mc) {
        io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService vd =
                io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService.getInstance();
        if (vd.isEnabled()) {
            return io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService
                    .resolveEffectiveClientVD(mc);
        }
        int serverVD = vd.getLastServerVD();
        return serverVD > 0 ? serverVD : Integer.MAX_VALUE;
    }

    /** 切比雪夫距离判定（区块 vs 玩家所在区块，≤ boundary 视为界内）。 */
    private static boolean isWithinBoundary(ChunkPos pos, int pcx, int pcz, int boundary) {
        return Math.max(Math.abs(pos.x - pcx), Math.abs(pos.z - pcz)) <= boundary;
    }

    /** 卸载前释放回传队列中该柱全部条目（chunk 包 + light 包）。 */
    private static void releaseReadyEntries(long key) {
        int x = new ChunkPos(key).x;
        int z = new ChunkPos(key).z;
        ready.removeIf(item -> (item.chunkPacket != null
                && item.chunkPacket.getX() == x && item.chunkPacket.getZ() == z)
                || (item.lightPacket != null
                && item.lightPacket.getX() == x && item.lightPacket.getZ() == z));
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
            if (!canDrainLightMaskThisFrame(
                    inflightLight.containsKey(key) || waitingForLight.containsKey(key),
                    deferredLightPush.containsKey(key),
                    shadowApplyEpochs.containsKey(key),
                    false)) {
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
     * 仅超时/未收敛光包使用：不要把「源之下仍全空」的 sky section 打成 emptySkyYMask。
     * 收敛后的 {@code ClientboundLightUpdatePacket} 用引擎掩码，不再按天空源裁空层。
     */
    private static void omitUnlitEavesFromSkyMask(BitSet skyMask, ChunkPos pos, LevelLightEngine engine,
                                                  int minLightSection, ShadowSeedServer server,
                                                  net.minecraft.server.level.ServerLevel level) {
        if (skyMask.isEmpty() || server == null || pos == null) {
            return;
        }
        // per-dimension：天空剖面判断用目标 chunk 所属 level（nether 无天光直接短路）
        net.minecraft.server.level.ServerLevel dim = level != null ? level : server.overworld();
        LevelChunk chunk = server.injectedChunk(pos.x, pos.z);
        if (chunk == null || !dim.dimensionType().hasSkyLight()) {
            return;
        }
        net.minecraft.world.level.lighting.ChunkSkyLightSources sources = chunk.getSkyLightSources();
        int minBlockY = io.github.limuqy.mc.hassium.compat.LevelHeightCompat.getMinBlockY(dim);
        for (int bit = skyMask.nextSetBit(0); bit >= 0; bit = skyMask.nextSetBit(bit + 1)) {
            int sectionY = minLightSection + bit;
            DataLayer sky = engine.getLayerListener(LightLayer.SKY)
                    .getDataLayerData(SectionPos.of(pos, sectionY));
            boolean atOrAbove = ShadowSeedServer.sectionAtOrAboveAnySkySource(sources, sectionY, minBlockY);
            if (!shouldIncludeSkySectionInPacket(sky != null, sky != null && sky.isEmpty(), atOrAbove)) {
                skyMask.clear(bit);
            }
        }
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
        haloKeys.clear();
        pendingDeltas.clear();
        pendingDeltaRequests.clear();
        generated.clear();
        pendingLightUpdates.clear();
        inflightLight.clear(); // 在途光屏障：回调侧条件移除失败即短路丢弃（断连竞态）
        LightReadinessRegistry.clear(); // E1：就绪状态机对齐断连清理面
        waitingForLight.clear();
        initializedLight.clear();
        deferredLightPush.clear();
        ready.clear();
        lightUpdates.clear();
        unloadPending.clear();
        requestedMisses.clear();
        accountedCacheHits.clear();
        accountedLights.clear();
        shadowApplyEpochs.clear();
        fullApplyTraces.clear();
        deferredRemoteHashes.clear();
        remoteHashDrainRunning.set(false);
        consumeRunning.set(false);
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
        return readyCount() > 0 || hasPendingWork()
                || !inflightLight.isEmpty() || !waitingForLight.isEmpty();
    }

    /** 冒烟卡顿诊断：影子管线队列快照。 */
    public static String stallSnapshot() {
        return "ready=" + ready.size()
                + " pending=" + pending.size()
                + " gen=" + generated.size()
                + " delta=" + pendingDeltas.size()
                + " inflightLight=" + inflightLight.size()
                + " waiting=" + waitingForLight.size()
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

    /**
     * 隔离 {@link LevelLightEngine} 的 {@link LightChunkGetter}：只暴露本柱。
     * 邻柱 null → 引擎视作无入流（getState 回落基岩），预览欠估。
     */
    private static final class SingleChunkGetter implements LightChunkGetter {
        private final LevelChunk chunk;

        SingleChunkGetter(LevelChunk chunk) {
            this.chunk = chunk;
        }

        @Override
        public LightChunk getChunkForLighting(int x, int z) {
            ChunkPos pos = chunk.getPos();
            return x == pos.x && z == pos.z ? chunk : null;
        }

        @Override
        public BlockGetter getLevel() {
            BlockGetter level = chunk.getLevel();
            return level != null ? level : chunk;
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
