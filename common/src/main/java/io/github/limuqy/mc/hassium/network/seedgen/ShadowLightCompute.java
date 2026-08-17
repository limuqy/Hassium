package io.github.limuqy.mc.hassium.network.seedgen;

import io.github.limuqy.mc.hassium.concurrent.ChunkDistancePriority;
import io.github.limuqy.mc.hassium.concurrent.HassiumTaskExecutor;
import io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue;
import io.github.limuqy.mc.hassium.concurrent.TaskCategory;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.ClientChunkHandler;
import io.github.limuqy.mc.hassium.network.ClientChunkPipeline;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
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
 *   <li>消费（后台池单循环 CAS，管道化）：取批 → 注入 → 对每柱提交官方两阶段屏障
 *       （initializeLight → lightChunk，原版 ChunkStatus.INITIALIZE_LIGHT→LIGHT 本体）→
 *       立即取下一批（批间零等待）；per-chunk future 完成即回调打包回传
 *       （在途上限 32 防引擎队列爆炸）；5s 超时兜底欠光标脏，由光照更新桥梁事件驱动补发</li>
 *   <li>光照收集（{@link #collectLightUpdate}）：影子端 light 线程（引擎每完成
 *       一个 section 的光计算）</li>
 *   <li>客户端主线程：{@link #drainReady}（帧尾，MixinClientTick）先攒批 light 包
 *       （{@link #drainLightMasks}）再按优先级队列官方通道落地，每帧硬顶消费</li>
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

    /** 投递队列：pos -> packet（REPLACE）。 */
    private static final ConcurrentHashMap<Long, ClientboundLevelChunkWithLightPacket> pending =
            new ConcurrentHashMap<>();
    /** 分段增量队列：pos -> (dimension, DeltaEntry)。REPLACE 语义：服务端每份 delta
     *  都是「当前服务端状态 vs 客户端基线」的完整差异，后到覆盖先到（内容都正确）。 */
    private static final ConcurrentHashMap<Long, DeltaWork> pendingDeltas =
            new ConcurrentHashMap<>();
    /** 本地生成队列：pos -> (chunk, level)（SeedGen worldgen 完成 / 磁盘光脏 relight，打包回传）。 */
    private static final ConcurrentHashMap<Long, GenEntry> generated = new ConcurrentHashMap<>();
    /**
     * 增量算光队列（LightDelta 消费）：pos -> 变更掩码。REPLACE/合并不冲突——
     * 服务端 LightDelta 是「哪几个 section 刚变完」的增量信号，同柱后到与先到的
     * 掩码取并集即可（影子端按并集清光重算，最终一致性不变）。
     */
    private static final ConcurrentHashMap<Long, LightWork> pendingLightUpdates =
            new ConcurrentHashMap<>();
    /** 管道在途光屏障：pos -> 提交上下文（submitLightBatch 提交，completeLight/超时扫表
     *  条件移除；size = 在途计数，上限 {@link #PIPELINE_MAX_INFLIGHT}，断连清空）。 */
    private static final ConcurrentHashMap<Long, InflightLight> inflightLight = new ConcurrentHashMap<>();
    /**
     * 客户端已通过影子链路落地过全量区块的 key 集合：光桥只允许对这些柱发增量光，
     * 杜绝「原版直发已加载的柱 / 影子区块包还没落地」时把中间态光包先打进去。
     * 全量区块包落地时 add；卸载 / 断连时移除。
     */
    private static final java.util.Set<Long> shadowAppliedChunks = ConcurrentHashMap.newKeySet();

    /**
     * 回传队列（优先级队列 + REPLACE）：chunk 包（op={@code OP_CHUNK_APPLY}）与
     * light 包（op={@code OP_LIGHT_UPDATE}）同队列按距离优先级消费；同位置同语义
     * 新任务取代旧任务（旧包摘出堆，杜绝老数据覆盖新数据）。主线程帧尾官方通道消费，
     * 每帧 poll ≤ {@code max(1, ClientMainThreadBudget.getHardCap())}。
     */
    private static final KeyedPriorityQueue<ReadyItem> ready = new KeyedPriorityQueue<>(64);

    /** 回传队列元素：chunk 包 / light 包二选一（消费侧按非 null 分发）。 */
    private record ReadyItem(ClientboundLevelChunkWithLightPacket chunkPacket,
                             ClientboundLightUpdatePacket lightPacket) {}

    /**
     * 光照更新收集表：chunkKey → LightMask（影子端 light 线程写，客户端主线程读）。
     * 引擎每完成一个 section 的光计算写数据层 → {@code onLightUpdate}（MixinServerChunkCache
     * 拦截，T2）→ {@link #collectLightUpdate} 收集绝对 sectionY；主线程帧尾
     * {@link #drainLightMasks} 攒批打包入回传队列。
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
     * 出界卸载延迟表（T5）：chunkKey → 到期毫秒。区块离开卸载边界后登记计时
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

    /** miss 已请求集合（会话内防抖：直推与请求并存时不重复请求；断连清空）。 */
    private static final java.util.Set<Long> requestedMisses = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * P1（T7）：注入 chunk section 容器（PalettedContainer）并发锁——hash 比对线程
     * （processRemoteHashes→chunkHashOf / requestSectionDeltas→computeSectionHashes）与
     * consumeLoop 打包线程（pushReady→SeedGenChunkCodec.buildPacket / applySectionDelta）
     * 对同一注入 LevelChunk 的容器并发触碰 → 1.21.11 ThreadingDetector 崩溃（全 miss 触发
     * delta 洪峰时）。按 chunk 粒度互斥：key = ChunkPos.asLong；短临界区（无 IO、无跨 chunk
     * 嵌套锁），HassiumTaskExecutor 虚拟线程池内无死锁风险；不同 chunk 不同 monitor 无争用放大。
     * 静态存活（不随断连清理）：键数 = 会话内触碰 chunk 数（每键 ~40B），可忽略；清空反而
     * 引入新旧 monitor 交错窗口。
     */
    private static final ConcurrentHashMap<Long, Object> chunkLocks = new ConcurrentHashMap<>();

    private static Object chunkLock(ChunkPos pos) {
        return chunkLocks.computeIfAbsent(chunkPosKey(pos), k -> new Object());
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
     *  与 processRemoteHashes 的 miss 请求同一会话内防抖集合）。true = 首次登记（应发送请求）。 */
    public static boolean tryRequestMiss(ChunkPos pos) {
        return requestedMisses.add(chunkPosKey(pos));
    }

    /** P2（T7）：回退请求去重过滤——只保留 requestedMisses 首次登记的 chunk（杜绝同 chunk 重复回退）。 */
    private static List<ChunkPos> dedupeFallback(List<ChunkPos> chunks) {
        List<ChunkPos> toRequest = new ArrayList<>(chunks.size());
        for (ChunkPos pos : chunks) {
            if (tryRequestMiss(pos)) {
                toRequest.add(pos);
            }
        }
        return toRequest;
    }

    /** 同步光照（原版 FULL 语义）：per-chunk 光就绪等待总超时（毫秒，5s，旧版收敛屏障同款）。 */
    private static final long CONVERGENCE_WAIT_TIMEOUT_MS = 5_000L;
    /**
     * 光屏障完成后「整柱光数据层不全」的重试上限。future 完成只保证当前任务队列
     * 排空一轮；水柱/边缘柱在邻柱尚未注入时可能仍未产出全部 DataLayer，直接打包
     * 官方包会静默省略这些 section（客户端新柱 = 黑块，旧柱 = 旧光残留）。重试 =
     * 重新投递一轮两阶段屏障（ensure + initializeLight → lightChunk）；超限才按欠光
     * 打包 + 标脏，由光照更新桥梁兜底。
     */
    private static final int MAX_LIGHT_COMPLETE_ATTEMPTS = 6;
    /**
     * 5s 屏障超时后的续投上限。超时多数是影子端引擎瞬时积压（R2 重连波次），
     * 续投 1-2 轮通常即可完成；宁可持续等待（客户端表现为未加载而非黑块），
     * 也不要把欠光区块包推成可见黑块。续投耗尽才退回旧欠光兜底。
     */
    private static final int MAX_TIMEOUT_COMPLETE_RETRIES = 2;
    /** 每轮消费循环「光屏障提交」总量上限（含 pending/generated/delta/light 全部来源，
     *  不再仅限 pending 批）。两阶段屏障每柱仅 ~3 个引擎任务（initializeLight PRE+POST、
     *  lightChunk PRE+POST 的提交单元），24 柱 ≈ 72 任务，远低于 1000 并发阈值。 */
    private static final int CONSUME_BATCH_LIMIT = 24;
    /** 管道在途光屏障上限（提交后未完成的两阶段 future 计数）：1.33 个消费循环满批 = 32 柱，
     *  预播种 ~15 + initializeLight/lightChunk ~4 ≈ 19 任务/柱，32 柱 ≈ 608 任务；
     *  叠加 re-inject 清光（按柱排水到 450 水位）仍恒 < 1000（vanilla sorter 并发
     *  runUpdate 阈值）。完成回调在低水位重新 pump 持续灌入。 */
    private static final int PIPELINE_MAX_INFLIGHT = 32;
    /** 清光路径（re-inject / relight）按柱排水水位：每柱清光前水位 ≤450，一柱清光后
     *  ≤502；随后整批两阶段首阶段任务 ≤12×35=420，二者叠加恒 < 1000（vanilla sorter
     *  并发 runUpdate 阈值），且比旧值 300 更少触发 5s 忙等。仅由 ShadowSeedServer 的
     *  清光路径使用。 */
    private static final int ENGINE_TASK_LOW_WATER = 450;
    /** 管道低水位：在途低于此值才由完成回调重新 pump（= 1 批：低水位→满水位恰好补一批，
     *  避免每完成一块就一次 executor 往返）。 */
    private static final int PIPELINE_LOW_WATER = CONSUME_BATCH_LIMIT;
    /** 已发出、未收到 delta 响应的请求（pos → 维度 + 截止时间）；超时回退全量。 */
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

    /** 影子链路可用（引擎开启 && 握手完成 && 影子端未失败）。 */
    public static boolean isEnabled() {
        return HassiumConfigService.getInstance().isHassiumEngineEnabled()
                && ClientChunkPipeline.getInstance().isHassiumHandshakeDone()
                && !ClientChunkPipeline.getInstance().isShadowServerFailed();
    }

    /**
     * 登录初始化：影子端预创建（后台，不卡主线程）。握手未到时等待；
     * 超时放弃（首个投递触发消费循环时再创建）。
     */
    public static void onLogin() {
        if (!HassiumConfigService.getInstance().isHassiumEngineEnabled()) {
            return;
        }
        HassiumTaskExecutor executor = HassiumTaskExecutor.getClient();
        if (executor == null || !executor.isRunning()) {
            return;
        }
        executor.submit(() -> {
            long waitStartMs = System.currentTimeMillis(); // T0b 诊断：等待握手实际耗时
            long deadline = System.currentTimeMillis() + 3_000L;
            while (!ClientChunkPipeline.getInstance().isHassiumHandshakeDone()
                    && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(20L);
                } catch (InterruptedException e) {
                    return;
                }
            }
            DebugLogger.info(DebugLogger.LogType.NETWORK,
                    "[LOGIN-DIAG] onLogin waited {}ms for handshake (deadline 3000ms)",
                    System.currentTimeMillis() - waitStartMs);
            ShadowServerRegistry.getInstance().getOrCreate();
        }, TaskCategory.BEST_EFFORT);
    }

    /**
     * 影子端 hash 比对（架构语义：服务端 bloom hit 只发 hash——由影子端决定是否需要
     * 推区块数据）。Netty 线程调用（{@code ClientMetadataHandler.handleChunkHashPacket}）；
     * 查盘/比对/请求全部提交后台池（与 consumeLoop 同池，不阻塞 Netty）。
     * <p>
     * 判定顺序（每块）：
     * <ol>
     *   <li>内存已加载（injectedChunks）→ hash 比对（ShadowStorageHashes 表优先，
     *       无表现算）→ 命中 → 直接回传（已加载区块，含收敛光）；</li>
     *   <li>未加载 → 读影子端存档比对（loadFromDisk，存档 hash）→ 命中 →
     *       加载进影子端（injectLoadedChunk，后续直接内存命中）+ 回传；</li>
     *   <li>不中 / 存档无此柱 → 请求数据（requestFullChunks → 服务端
     *       enqueueDataRequest 推送 → 数据到达走 submit/consumeLoop 注入链）。</li>
     * </ol>
     * 影子端创建失败 / 不可用 → 全部请求（降级态客户端直连 apply 兜底，数据必须到）。
     * 未握手（服务端无 MOD）→ 无 hash 包，本方法不触发。
     */
    public static void handleRemoteHashes(String dimension,
                                          List<io.github.limuqy.mc.hassium.network.ChunkHashS2CPacket.Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        if (!ClientChunkPipeline.getInstance().isHassiumHandshakeDone()) {
            return; // 未握手：原版直发，无 hash 包
        }
        HassiumTaskExecutor executor = HassiumTaskExecutor.getClient();
        if (executor == null || !executor.isRunning()) {
            return;
        }
        try {
            executor.submit(() -> processRemoteHashes(dimension, entries), TaskCategory.BEST_EFFORT);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // 池已停（断连竞态）：hash 丢弃，数据由直推/请求兜底
        }
    }

    private static void processRemoteHashes(String dimension,
                                            List<io.github.limuqy.mc.hassium.network.ChunkHashS2CPacket.Entry> entries) {
        ShadowSeedServer server = ShadowServerRegistry.getInstance().getOrCreate();
        List<ChunkPos> misses = new ArrayList<>();
        List<ChunkPos> deltaCandidates = new ArrayList<>();
        for (io.github.limuqy.mc.hassium.network.ChunkHashS2CPacket.Entry entry : entries) {
            ChunkPos pos = new ChunkPos(entry.chunkX(), entry.chunkZ());
            long remoteHash = entry.chunkHash();
            try {
                if (server != null) {
                    // 1) 内存已加载：hash 表优先（注入时已算），无表现算（OVD 生成块）
                    LevelChunk loaded = server.injectedChunk(pos.x, pos.z);
                    if (loaded != null) {
                        // P1（T7）：hash 比对读注入 chunk section 容器，与 consumeLoop 打包
                        // （pushReady→buildPacket）/ delta 应用（applySectionDelta）同 chunk 锁互斥。
                        boolean hashHit;
                        synchronized (chunkLock(pos)) {
                            hashHit = chunkHashOf(loaded, pos, remoteHash);
                        }
                        if (hashHit) {
                            org.slf4j.LoggerFactory.getLogger("Hassium/ShadowDisk")
                                    .info("Shadow hash cache hit ({}, {}), memory push", pos.x, pos.z);
                            // T5g：区块缓存全命中记账——内存命中 = 影子端直接服务客户端、未走网络拉取，
                            // 与 consumeLoop 磁盘直推口径同构（count+bytes，key cacheHitFullChunk*）。
                            io.github.limuqy.mc.hassium.metrics.NetworkStats.recordCacheLoadEligible(
                                    io.github.limuqy.mc.hassium.metrics.NetworkStats.ESTIMATED_CHUNK_BYTES);
                            io.github.limuqy.mc.hassium.metrics.NetworkStats.recordCacheFullHit(
                                    io.github.limuqy.mc.hassium.metrics.NetworkStats.ESTIMATED_CHUNK_BYTES);
                            // T5g：OVD 统计侧影子命中直接服务计数（超视渲染行「影子复用」展示用）。
                            io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService
                                    .getInstance().noteShadowServed();
                            // P2：光照记账——光干净 → 复用（key light.reuse.shadow.*）；
                            // 光标脏（如盲预生成注入）→ 本地重算，miss 由光屏障完成时统一记。
                            boolean lightDirty =
                                    io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.isLightDirty(pos);
                            if (!lightDirty) {
                                io.github.limuqy.mc.hassium.metrics.NetworkStats.recordLightReuseShadow(
                                        io.github.limuqy.mc.hassium.metrics.NetworkStats.ESTIMATED_LIGHT_BYTES);
                            }
                            // 回传统一走 generated → consumeLoop 光屏障（submitLightBatch）：
                            // buildPacket 从 LevelLightEngine 收集光，注入表 chunk 的光在引擎
                            // 内（本会话算过）但需确认收敛，直接 push 可能推欠光（黑块）。
                            generated.put(chunkPosKey(pos), new GenEntry(loaded, server.overworld(), !lightDirty));
                            pump();
                            continue;
                        }
                        // 内存数据过期（hash MISMATCH）且光干净 → 分段增量候选：
                        // 本地 section hashes 上报，服务端只回变更 section。
                        if (deltaEnabled()
                                && !io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.isLightDirty(pos)) {
                            deltaCandidates.add(pos);
                            continue;
                        }
                        misses.add(pos);
                        continue;
                    }
                    // 2) 存档：读盘比对（存档 hash 收敛光），命中 → 加载进影子端 + 回传；
                    //    hash 一致但光标脏（保存时未收敛落盘）→ 本地重算不重拉
                    //    （relightChunk 清光重算 + generated 回传链，不请求网络全量）。
                    LevelChunk fromDisk = server.loadFromDisk(pos);
                    if (fromDisk != null) {
                        if (diskHashMatches(fromDisk, remoteHash)) {
                            server.injectLoadedChunk(pos, fromDisk);
                            if (io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.isLightDirty(pos)) {
                                // 磁盘命中 + hash 一致 + 光脏：内容与远程一致，仅光欠——
                                // 本地重算（relightChunk 内标脏，收敛后 pushReady 清除），
                                // 回传链走 generated（consumeLoop 打包 pushReady），
                                // 欠光补发由光照更新桥梁承担。
                                org.slf4j.LoggerFactory.getLogger("Hassium/ShadowDisk")
                                        .info("Shadow hash cache hit ({}, {}), light dirty, relight locally",
                                                pos.x, pos.z);
                                server.relightChunk(pos, fromDisk);
                                // 指标：区块数据同样是客户端缓存全命中（少推一次全量区块）；
                                // 光照走本地重算而非复用，lightCacheMiss 由光屏障完成时统一记。
                                io.github.limuqy.mc.hassium.metrics.NetworkStats.recordCacheLoadEligible(
                                        io.github.limuqy.mc.hassium.metrics.NetworkStats.ESTIMATED_CHUNK_BYTES);
                                io.github.limuqy.mc.hassium.metrics.NetworkStats.recordCacheFullHit(
                                        io.github.limuqy.mc.hassium.metrics.NetworkStats.ESTIMATED_CHUNK_BYTES);
                                io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService
                                        .getInstance().noteShadowServed();
                                generated.put(chunkPosKey(pos), new GenEntry(fromDisk, server.overworld(), false));
                                pump();
                            } else {
                                org.slf4j.LoggerFactory.getLogger("Hassium/ShadowDisk")
                                        .info("Shadow hash cache hit ({}, {}), disk push", pos.x, pos.z);
                                // T5g：区块缓存全命中记账——磁盘命中（disk push）= 影子端读盘直接服务，
                                // 未走网络拉取；与 consumeLoop 磁盘直推同口径（count+bytes）。
                                io.github.limuqy.mc.hassium.metrics.NetworkStats.recordCacheLoadEligible(
                                        io.github.limuqy.mc.hassium.metrics.NetworkStats.ESTIMATED_CHUNK_BYTES);
                                io.github.limuqy.mc.hassium.metrics.NetworkStats.recordCacheFullHit(
                                        io.github.limuqy.mc.hassium.metrics.NetworkStats.ESTIMATED_CHUNK_BYTES);
                                // T5g：OVD 统计侧影子命中直接服务计数（超视渲染行「影子复用」展示用）。
                                io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService
                                        .getInstance().noteShadowServed();
                                // P2：影子链路光复用记账——磁盘命中（存档收敛光）即复用；
                                // 上方「磁盘命中 + 光脏 → relight 本地重算」分支不记账（光新算非复用）。
                                io.github.limuqy.mc.hassium.metrics.NetworkStats.recordLightReuseShadow(
                                        io.github.limuqy.mc.hassium.metrics.NetworkStats.ESTIMATED_LIGHT_BYTES);
                                // 回传统一走 generated → consumeLoop 光屏障（submitLightBatch）：
                                // buildPacket 从 LevelLightEngine 收集光，磁盘加载的 chunk 光在
                                // 存档（ChunkSerializer.read 恢复）不在引擎，直接 push 必黑块。
                                generated.put(chunkPosKey(pos), new GenEntry(fromDisk, server.overworld(), true));
                                pump();
                            }
                            continue;
                        }
                        // 存档数据过期（hash MISMATCH）且光干净 → 分段增量候选：
                        // 基线 = 磁盘旧数据，加载进影子端后等 delta 补 section。
                        if (deltaEnabled()
                                && !io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.isLightDirty(pos)) {
                            server.injectLoadedChunk(pos, fromDisk);
                            deltaCandidates.add(pos);
                            continue;
                        }
                        misses.add(pos);
                        continue;
                    }
                }
            } catch (Throwable t) {
                DebugLogger.warn(DebugLogger.LogType.ASYNC,
                        "[SHADOW_HASH] Compare failed ({}, {})", pos.x, pos.z);
            }
            // 3) 不中 / 影子端不可用：请求数据
            misses.add(pos);
        }
        if (!deltaCandidates.isEmpty()) {
            requestSectionDeltas(dimension, deltaCandidates);
        }
        if (!misses.isEmpty()) {
            List<ChunkPos> toRequest = new ArrayList<>(misses.size());
            for (ChunkPos pos : misses) {
                if (requestedMisses.add(chunkPosKey(pos))) {
                    toRequest.add(pos);
                }
            }
            if (!toRequest.isEmpty()) {
                io.github.limuqy.mc.hassium.network.ClientMetadataHandler
                        .requestFullChunksPublic(dimension, toRequest, false);
            }
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
            LevelChunk chunk = server.injectedChunk(pos.x, pos.z);
            if (chunk == null) {
                continue; // 已被移除/竞态：数据由服务端直推兜底
            }
            long[] sectionHashes;
            // P1（T7）：computeSectionHashes 读注入 chunk section 容器，与 consumeLoop
            // 打包/写路径（buildPacket/applySectionDelta）同 chunk 锁互斥。
            synchronized (chunkLock(pos)) {
                sectionHashes = io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil
                        .sectionHashesToArray(io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil
                                .computeSectionHashes(chunk));
            }
            entries.add(new io.github.limuqy.mc.hassium.network.SectionHashRequestC2SPacket.Entry(
                    pos.x, pos.z, sectionHashes));
            pendingDeltaRequests.put(chunkPosKey(pos), new PendingDelta(dimension, deadline));
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
                pendingDeltaRequests.remove(chunkPosKey(new net.minecraft.world.level.ChunkPos(e.chunkX(), e.chunkZ())));
            }
            DebugLogger.warn(DebugLogger.LogType.ASYNC,
                    "[SHADOW_DELTA] Request send failed, fallback full ({} chunks)", entries.size());
            // P2（T7）：失败回退走 new 路径（hash-miss 正轨）+ requestedMisses 去重
            List<ChunkPos> fallback = dedupeFallback(chunks);
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
            long key = chunkPosKey(new net.minecraft.world.level.ChunkPos(entry.chunkX(), entry.chunkZ()));
            pendingDeltas.put(key, new DeltaWork(dimension, entry));
            pendingDeltaRequests.remove(key);
        }
        // 全量等价流量不在此处记：apply 失败会回退全量，收到即记会在「delta + 回退全量」
        // 场景把同一区块计两次。成功应用后由 consumeLoop 记 recordSectionDeltaReceived。
        if (!packet.skipped().isEmpty()) {
            List<net.minecraft.world.level.ChunkPos> skipped = new ArrayList<>(packet.skipped().size());
            for (var s : packet.skipped()) {
                long key = chunkPosKey(new net.minecraft.world.level.ChunkPos(s.chunkX(), s.chunkZ()));
                pendingDeltaRequests.remove(key);
                skipped.add(new net.minecraft.world.level.ChunkPos(s.chunkX(), s.chunkZ()));
            }
            DebugLogger.info(DebugLogger.LogType.ASYNC,
                    "[SHADOW_DELTA] {} chunks skipped by server, fallback full", skipped.size());
            // P2（T7）：失败回退走 new 路径 + requestedMisses 去重
            List<net.minecraft.world.level.ChunkPos> fallback = dedupeFallback(skipped);
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
            long key = chunkPosKey(new ChunkPos(entry.chunkX(), entry.chunkZ()));
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
                net.minecraft.world.level.ChunkPos pos = new net.minecraft.world.level.ChunkPos(e.getKey());
                timedOut.computeIfAbsent(e.getValue().dimension(), k -> new ArrayList<>()).add(pos);
                it.remove();
            }
        }
        for (var e : timedOut.entrySet()) {
            DebugLogger.warn(DebugLogger.LogType.ASYNC,
                    "[SHADOW_DELTA] {} delta requests timed out, fallback full", e.getValue().size());
            // P2（T7）：失败回退走 new 路径 + requestedMisses 去重
            List<net.minecraft.world.level.ChunkPos> fallback = dedupeFallback(e.getValue());
            if (!fallback.isEmpty()) {
                io.github.limuqy.mc.hassium.network.ClientMetadataHandler
                        .requestFullChunksPublic(e.getKey(), fallback, false);
            }
        }
    }

    /** 内存区块 hash：ShadowStorageHashes 表优先（注入时已算），无表现算（与 diskHashMatches 同算法）。 */
    private static boolean chunkHashOf(LevelChunk chunk, ChunkPos pos, long remoteHash) {
        Long tableHash = io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.get(pos);
        if (tableHash != null) {
            return tableHash.longValue() == remoteHash;
        }
        return diskHashMatches(chunk, remoteHash);
    }

    /**
     * 投递一个远程区块（任意线程；启用态 gate）。同柱 REPLACE 覆盖旧数据。
     *
     * @param packet 还原的服务端区块包（空光：剥光数据）
     */
    public static void submit(ChunkPos pos, ClientboundLevelChunkWithLightPacket packet) {
        if (pos == null || packet == null || !isEnabled()) {
            return;
        }
        // 全量数据到达 = 该柱不再等 delta 响应（delta 请求超时登记清除）
        pendingDeltaRequests.remove(chunkPosKey(pos));
        pending.put(chunkPosKey(pos), packet);
        pump();
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
        if (pos == null || chunk == null || !isEnabled()) {
            return false;
        }
        generated.put(chunkPosKey(pos), new GenEntry(chunk, level, false));
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
     * {@link #PIPELINE_MAX_INFLIGHT}=32，达上限退出等待，由完成回调在低水位重新
     * {@link #pump()}（连续灌入）。per-chunk 完成回调独立回传
     * （{@link #completeLight} → {@link #finishLight}）。
     */
    private static void consumeLoop() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                ShadowSeedServer server = ShadowServerRegistry.getInstance().getOrCreate();
                if (server == null) {
                    // 未握手（无 MOD/握手竞态）或创建失败：registry 已置 shadowServerFailed，
                    // 整体降级（关闭缓存/OVD/SeedGen）；队列由降级 gate 停止，直接退出。
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
                List<Map.Entry<Long, ClientboundLevelChunkWithLightPacket>> batch =
                        new ArrayList<>(Math.min(CONSUME_BATCH_LIMIT, room));
                for (Map.Entry<Long, ClientboundLevelChunkWithLightPacket> e : pending.entrySet()) {
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
                    lightBatch.add(e);
                }
                if (batch.isEmpty() && genBatch.isEmpty() && deltaBatch.isEmpty() && lightBatch.isEmpty()) {
                    return; // 全部消费完（在途光屏障由完成回调独立回传）
                }
                org.slf4j.LoggerFactory.getLogger("Hassium/ShadowDisk")
                        .info("consumeLoop batch={} gen={} delta={} light={}",
                                batch.size(), genBatch.size(), deltaBatch.size(), lightBatch.size());
                List<LightTask> lightTasks = new ArrayList<>();
                for (Map.Entry<Long, ClientboundLevelChunkWithLightPacket> e : batch) {
                    ChunkPos pos = new ChunkPos(e.getKey());
                    long remoteHash = io.github.limuqy.mc.hassium.network.ClientChunkPipeline
                            .getInstance().peekPendingContentHash(pos.x, pos.z);
                    // 磁盘缓存优先：R1/R2 重推区块若与影子端存档 contentHash 一致
                    // （远程权威 hash 比对），直接用存档收敛光打包推送，跳过注入/重算/等收敛。
                    LevelChunk fromDisk = server.loadFromDisk(pos);
                    if (fromDisk != null) {
                        org.slf4j.LoggerFactory.getLogger("Hassium/ShadowDisk")
                                .debug("Shadow disk loaded ({}, {}), remoteHash={}", pos.x, pos.z, remoteHash);
                        // 光标脏（保存时未收敛落盘的欠光数据）不直接打包——落到注入清光重算链
                        if (remoteHash != 0L && diskHashMatches(fromDisk, remoteHash)
                                && !io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.isLightDirty(pos)) {
                            org.slf4j.LoggerFactory.getLogger("Hassium/ShadowDisk")
                                    .info("Shadow disk cache hit ({}, {}), direct push", pos.x, pos.z);
                            try {
                                io.github.limuqy.mc.hassium.metrics.NetworkStats.recordCacheLoadEligible(
                                        io.github.limuqy.mc.hassium.metrics.NetworkStats.ESTIMATED_CHUNK_BYTES);
                                io.github.limuqy.mc.hassium.metrics.NetworkStats.recordCacheFullHit(
                                        io.github.limuqy.mc.hassium.metrics.NetworkStats.ESTIMATED_CHUNK_BYTES);
                                // 该区块的完整数据包已经实际到线（vanillaBytesReceived 已记），
                                // 客户端只是改用本地缓存 apply；流量节省不能把它再算作「少推」。
                                io.github.limuqy.mc.hassium.metrics.NetworkStats.recordCacheFullHitNetworkReplaced(
                                        io.github.limuqy.mc.hassium.metrics.NetworkStats.ESTIMATED_CHUNK_BYTES);
                                // P2：影子链路光复用记账——磁盘命中直推（存档即收敛光）。
                                io.github.limuqy.mc.hassium.metrics.NetworkStats.recordLightReuseShadow(
                                        io.github.limuqy.mc.hassium.metrics.NetworkStats.ESTIMATED_LIGHT_BYTES);
                                // 回传统一走 generated → consumeLoop 光屏障（submitLightBatch）：
                                // buildPacket 从 LevelLightEngine 收集光，磁盘 chunk 光在存档
                                // （ChunkSerializer.read 恢复）不在引擎，直接 push 必黑块。
                                server.injectLoadedChunk(pos, fromDisk);
                                generated.put(e.getKey(), new GenEntry(fromDisk, server.overworld(), true));
                                pump();
                                pending.remove(e.getKey());
                                continue;
                            } catch (Throwable t) {
                                DebugLogger.warn(DebugLogger.LogType.ASYNC,
                                        "[SHADOW_CHUNK] Disk push failed ({}, {})", pos.x, pos.z);
                            }
                        }
                    }
                    if (!server.injectChunk(pos, e.getValue())) {
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
                    lightTasks.add(new LightTask(e.getKey(), LightSource.PENDING, e.getValue(),
                            server.injectedChunk(pos.x, pos.z), server.overworld(), LightMetric.RECOMPUTE));
                }
                // 分段增量应用：本地基线 chunk 上就地覆盖变更 section + heightmaps + BE，
                // 变更 section 清光（applySectionDelta 内）→ 与注入共享下方光屏障。
                for (Map.Entry<Long, DeltaWork> e : deltaBatch) {
                    long key = e.getKey();
                    if (!pendingDeltas.containsKey(key)) {
                        continue; // REPLACE 后旧条目已被新 batch 接管 / 断连清理
                    }
                    pendingDeltas.remove(key);
                    ChunkPos pos = new ChunkPos(key);
                    DeltaWork work = e.getValue();
                    // P1（T7）：applySectionDelta 就地覆盖注入 chunk 的 section 容器
                    // （LevelChunkSection.read → PalettedContainer 写）——与 hash 比对线程
                    // （chunkHashOf / computeSectionHashes）同 chunk 锁互斥（T7 崩溃同机制）。
                    boolean applied;
                    synchronized (chunkLock(pos)) {
                        applied = server.applySectionDelta(pos, work.entry().changedSections(),
                                work.entry().heightmaps(), work.entry().blockEntities());
                    }
                    if (!applied) {
                        // 基线缺失 / 应用失败 → 回退全量（正确性优先）；跳过本 chunk 回传
                        DebugLogger.warn(DebugLogger.LogType.ASYNC,
                                "[SHADOW_DELTA] Apply failed ({}, {}), fallback full", pos.x, pos.z);
                        // P2（T7）：失败回退走 new 路径 + requestedMisses 去重
                        if (tryRequestMiss(pos)) {
                            List<ChunkPos> fallback = new ArrayList<>(1);
                            fallback.add(pos);
                            io.github.limuqy.mc.hassium.network.ClientMetadataHandler
                                    .requestFullChunksPublic(work.dimension(), fallback, false);
                        }
                    } else {
                        // 成功应用：记增量统计（估算原版全量等价值）。
                        // 分片「无 MOD 应收」= 原版 Zlib 全量 chunk wire（旧实现漏做 Zlib 估算，
                        // 直接以 16KB 原始字节入账，且收到即记、失败回退后会与全量包重复计数）。
                        io.github.limuqy.mc.hassium.metrics.NetworkStats.recordCacheDeltaSaved(
                                io.github.limuqy.mc.hassium.metrics.NetworkStats.ESTIMATED_CHUNK_BYTES);
                        io.github.limuqy.mc.hassium.metrics.NetworkStats.recordSectionDeltaReceived(1,
                                io.github.limuqy.mc.hassium.metrics.VanillaZlibEstimator.estimate(
                                        (int) io.github.limuqy.mc.hassium.metrics.NetworkStats.ESTIMATED_CHUNK_BYTES));
                        lightTasks.add(new LightTask(key, LightSource.DELTA, null,
                                server.injectedChunk(pos.x, pos.z), server.overworld(), LightMetric.RECOMPUTE));
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
                            gen.lightReuse ? LightMetric.REUSE_CACHE : LightMetric.RECOMPUTE));
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
                    ChunkPos pos = new ChunkPos(key);
                    boolean invalidated = server.invalidateLightSections(pos,
                            work.skyMask(), work.blockMask(), work.emptySkyMask(), work.emptyBlockMask());
                    if (!invalidated) {
                        // 柱未注入：不能重算；保守标脏，R2 读盘命中会走 relight 链。
                        // 不请求全量：方块数据未变，hash 命中时本柱很快会被正常路径回填。
                        pendingLightUpdates.remove(key, work);
                        io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.markLightDirty(pos, true);
                        continue;
                    }
                    io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.markLightDirty(pos, true);
                    lightTasks.add(new LightTask(key, LightSource.LIGHT_ONLY, work,
                            server.injectedChunk(pos.x, pos.z), server.overworld(), LightMetric.RECOMPUTE));
                }
                // 管道化：提交后不等待，立即回循环取下一批（批间零空转）；在途上限由轮顶检查约束。
                submitLightBatch(server, lightTasks);
            }
        } finally {
            consumeRunning.set(false);
            // 竞态：退出瞬间有新投递 → 重新触发。管道已满（在途=上限）时不 pump——
            // 由完成回调在低水位重新 pump（避免空转自旋）。
            if ((!pending.isEmpty() || !pendingDeltas.isEmpty() || !generated.isEmpty()
                    || !pendingLightUpdates.isEmpty())
                    && isEnabled()
                    && inflightLight.size() < PIPELINE_MAX_INFLIGHT) {
                pump();
            }
        }
    }

    /**
     * 管道化两阶段光屏障提交（原 {@code awaitBatchLight} 的提交半段；批级 allOf 全等已移除）：
     * 对每柱先提交官方 {@code initializeLight(chunk, true)}，其 future 完成后再提交
     * {@code lightChunk(chunk, false)}（见 {@link #startLightBarrier}）。最终 future
     * 完成 = 建层 PRE → 一轮传播排空 → setLightEnabled/retainData POST → lightChunk
     * PRE（propagateLightSources）→ 传播排空 → POST（setLightCorrect），即该柱光已收敛
     * （非全局收敛；边界欠光由光照更新桥梁 collectLightUpdate → drainLightMasks 补发）。
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
     * exactly-once + 断连短路）→ 移交后台池 {@link #finishLight} 打包回传
     * （buildPacket ~ms 级，不得占引擎 Worker 线程）；提交失败 → 欠光兜底
     * （converged=false）。5s 未完成由 {@link #sweepLightTimeouts} 统一扫表兜底。
     */
    private static void submitLightBatch(ShadowSeedServer server, List<LightTask> tasks) {
        if (server == null || tasks == null || tasks.isEmpty()) {
            return;
        }
        net.minecraft.server.level.ThreadedLevelLightEngine engine =
                (net.minecraft.server.level.ThreadedLevelLightEngine) server.overworld()
                        .getChunkSource().getLightEngine();
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
                            new ChunkPos(t.key).x, new ChunkPos(t.key).z);
                    continue;
                }
                try {
                    startLightBarrier(server, engine, t, deadlineMs, 0);
                    // 光照统计在提交成功时记（而非回传完成时）：冒烟快照窗口内光屏障可能还在
                    // 在途，等 finishLight 再记会让「分片增量已发生但光照重算仍显示 0」。
                    if (t.metric == LightMetric.RECOMPUTE) {
                        io.github.limuqy.mc.hassium.metrics.NetworkStats.recordLightCacheMiss(
                                io.github.limuqy.mc.hassium.metrics.NetworkStats.ESTIMATED_LIGHT_BYTES);
                    }
                } catch (Throwable ex) {
                    // 提交失败：欠光兜底（旧 result.put(key, FALSE) 同语义）——同步守卫+回传
                    finishLight(t, false);
                }
            }
        }
    }

    /**
     * 两阶段光屏障起始（vanilla ChunkStatus.INITIALIZE_LIGHT → LIGHT 同构）：
     * ① ensureChunkLightLayers 只投递缺层初始化任务（不再直接 setLightEnabled）；
     * ② {@code initializeLight} 官方屏障（updateSectionStatus PRE → runLightUpdates →
     * POST：setLightEnabled(true) + retainData(false)）；future 完成后才进入
     * ③ {@code lightChunk}（propagateLightSources + 传播 + POST setLightCorrect）。
     * <p>
     * 这样「建层」与「播种」被 future 边界强制串行：清光排队的共享空层先由
     * markNewInconsistencies 安装，lightChunk 的播种/传播随后写在已安装的空层上，
     * 不再发生「播种先写旧层 → markNewInconsistencies 后装空层把结果覆盖」的第一轮
     * 空层打包；也不会出现 propagateLightSources 先于 updateSectionStatus 执行。
     * <p>
     * 调用方必须已持有 {@link #LIGHT_ENGINE_MUTEX}（提交循环内）；本方法只投递任务。
     */
    private static void startLightBarrier(ShadowSeedServer server,
                                          net.minecraft.server.level.ThreadedLevelLightEngine engine,
                                          LightTask t, long deadlineMs, int attempts) {
        ChunkPos pos = new ChunkPos(t.key);
        server.ensureChunkLightLayers(pos, t.chunk);
        java.util.concurrent.CompletableFuture<net.minecraft.world.level.chunk.ChunkAccess> initFuture =
                engine.initializeLight(t.chunk, true);
        InflightLight inf = new InflightLight(t.key, t.source, t.token,
                t.chunk, t.level, deadlineMs, attempts, t.metric);
        inf.submittedAtNs = System.nanoTime();
        // REPLACE：同 key 新屏障盖旧——旧链路的 stage 回调 inflightLight.get(key) != old
        // 条件失败即短路，旧投递不回传、不覆盖新投递。
        inflightLight.put(t.key, inf);
        initFuture.whenComplete((chunkAccess, throwable) ->
                onInitializeComplete(server, inf, throwable == null));
    }

    /** 阶段①完成 → 阶段②提交（仍在引擎回调线程，仅 addTask 微秒级）。 */
    private static void onInitializeComplete(ShadowSeedServer server, InflightLight inf, boolean converged) {
        if (!isEnabled() || inflightLight.get(inf.key) != inf) {
            return; // 断连 / 同 key 新屏障 REPLACE：旧链路短路
        }
        if (!converged) {
            completeLight(inf, false);
            return;
        }
        try {
            synchronized (LIGHT_ENGINE_MUTEX) {
                if (inflightLight.get(inf.key) != inf || hasNewerWork(inf.key)) {
                    completeLight(inf, false); // 新数据已接管：旧任务让位（finishLight 的 REPLACE 守卫会短路）
                    return;
                }
                net.minecraft.server.level.ThreadedLevelLightEngine engine =
                        (net.minecraft.server.level.ThreadedLevelLightEngine) server.overworld()
                                .getChunkSource().getLightEngine();
                java.util.concurrent.CompletableFuture<net.minecraft.world.level.chunk.ChunkAccess> lightFuture =
                        engine.lightChunk(inf.chunk, false);
                lightFuture.whenComplete((chunkAccess, throwable) ->
                        completeLight(inf, throwable == null));
            }
        } catch (Throwable ex) {
            completeLight(inf, false);
        }
    }

    /**
     * 锁外忙等：把 {@code ThreadedLevelLightEngine.lightTasks} 压回
     * {@link #ENGINE_TASK_LOW_WATER}。目标：任务总量恒 < 1000（vanilla addTask 的
     * sorter 线程并发 runUpdate 阈值）——sorter 线程与 taskMailbox 线程并发 runUpdate
     * 同一 lightTasks 列表（非线程安全）时任务错序（propagateLightSources 先于
     * updateSectionStatus / POST 先于 PRE）→ 播种在建层前执行 → 空光层打包 → 客户端
     * 黑块。忙等期间主动 {@code tryScheduleUpdate} 驱动 mailbox 消化（不必等影子端
     * 主循环 100µs 轮询）；5s 超时兜底防死锁（超时继续，由 sweepLightTimeouts /
     * 重试链兜底，不阻塞投递链）。
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
        if (!inflightLight.remove(inf.key, inf)) {
            return false; // 已被超时兜底 / 断连清理 / 同 key 新投递 REPLACE：短路丢弃
        }
        // 屏障胜出 = 该柱即将由 finishLight 回传全量光（chunk 包或全柱光包）——
        // 立即丢弃已收集未消费的光更新掩码，堵住「completeLight 移除在途后、
        // finishLight offer 前，drainLightMasks 把中间态（含空层）打成光包」的窗口。
        discardLightMask(inf.key);
        HassiumTaskExecutor executor = HassiumTaskExecutor.getClient();
        if (executor == null || !executor.isRunning() || !isEnabled()) {
            return true; // 断连竞态：影子已关/队列已清（onDisconnect 清空 pending/generated/ready）→ 丢弃
        }
        try {
            executor.submit(() -> finishLight(inf, converged), TaskCategory.BEST_EFFORT);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // 池已停（断连竞态）：丢弃
        }
        return true;
    }

    /**
     * 回传执行（后台池）：pushReady 前用 {@link #hasNewerWork} 统一校验该 key 任一投递
     * 队列无新数据（REPLACE 语义保持——同 key 新投递不被旧回调覆盖；条目已在提交时移除，
     * 故 containsKey = 屏障期间到达的新投递 → 旧包不回传，新包由下一轮屏障回传，等价旧
     * {@code pending.remove(key, value)} 条件删除）。光层不全时先按上限续投重试；重试耗尽
     * 且无新投递接管才欠光标脏 + 回传，由光照更新桥梁 collectLightUpdate → drainLightMasks
     * 补发兜底。
     */
    private static void finishLight(LightTask task, boolean converged) {
        if (!isEnabled()) {
            return; // 断连竞态：影子已关（队列已清，守卫亦短路）
        }
        // REPLACE 统一守卫：任一投递队列同 key 出现新数据 = 旧任务不得回传
        // （新数据会由下一轮屏障回传；旧包回传可能先黑一帧/覆盖新光）。
        if (hasNewerWork(task.key)) {
            return;
        }
        ChunkPos pos = new ChunkPos(task.key);
        ShadowSeedServer server = ShadowServerRegistry.getInstance().get();
        boolean lightComplete = server != null && server.isChunkLightComplete(pos, task.chunk);
        if (!lightComplete) {
            int attempts = task instanceof InflightLight inf ? inf.attempts : 0;
            int maxAttempts = converged ? MAX_LIGHT_COMPLETE_ATTEMPTS : MAX_TIMEOUT_COMPLETE_RETRIES;
            if (attempts < maxAttempts && retryIncompleteLight(task)) {
                DebugLogger.info(DebugLogger.LogType.ASYNC,
                        "[SHADOW_LIGHT] Light layers incomplete ({}, {}), retry {}/{} (converged={})",
                        pos.x, pos.z, attempts + 1, maxAttempts, converged);
                return;
            }
            if (hasNewerWork(task.key)) {
                return; // 续投失败原因 = 新数据接管：不推旧欠光包（新屏障会回传）
            }
            // 重试耗尽/新投递接管：按欠光回传 + 标脏，光照更新桥梁补发。
            DebugLogger.warn(DebugLogger.LogType.ASYNC,
                    "[SHADOW_LIGHT] Light layers incomplete after retries ({}, {}), pushing with partial light",
                    pos.x, pos.z);
        }
        // 中间态光更新掩码已在 completeLight（屏障胜出）时丢弃；这里不再重复。
        // 光照重算耗时在最终回传前记（miss 计数已在 submitLightBatch 提交成功时记）。
        if (task.metric == LightMetric.RECOMPUTE) {
            long elapsedNs = task.submittedAtNs > 0L
                    ? Math.max(0L, System.nanoTime() - task.submittedAtNs)
                    : 0L;
            io.github.limuqy.mc.hassium.metrics.NetworkStats.recordLightRecomputeBackgroundTime(elapsedNs);
        }
        try {
            if (task.source == LightSource.LIGHT_ONLY) {
                LightWork work = task.token instanceof LightWork w ? w : null;
                pushLightReady(pos, task.level, task.chunk, converged && lightComplete, work);
            } else {
                pushReady(task.key, task.chunk, task.level, converged && lightComplete);
            }
        } catch (Throwable t) {
            DebugLogger.warn(DebugLogger.LogType.ASYNC,
                    "[SHADOW_CHUNK] Build failed ({}, {})", pos.x, pos.z);
        }
        // 管道唤醒：在途低于低水位 + 队列有未投递工作 → 重新灌入（低水位 = 1 批，
        // 避免每完成一块就一次 executor 往返；consumeLoop 运行中 pump 为 no-op）。
        if (inflightLight.size() < PIPELINE_LOW_WATER
                && (!pending.isEmpty() || !pendingDeltas.isEmpty() || !generated.isEmpty()
                    || !pendingLightUpdates.isEmpty())
                && isEnabled()) {
            pump();
        }
    }

    /**
     * 光层不全 → 重新投递一轮两阶段屏障（ensure + initializeLight → lightChunk）。
     * 新屏障仍走 {@code inflightLight} REPLACE/exactly-once；若同柱已有新投递则放弃旧任务。
     */
    private static boolean retryIncompleteLight(LightTask task) {
        if (!isEnabled() || hasNewerWork(task.key)) {
            return false;
        }
        ShadowSeedServer server = ShadowServerRegistry.getInstance().get();
        if (server == null) {
            return false;
        }
        try {
            synchronized (LIGHT_ENGINE_MUTEX) {
                if (inflightLight.containsKey(task.key) || hasNewerWork(task.key)) {
                    return false; // 已有新屏障 / 新数据接管：本任务不再续投
                }
                net.minecraft.server.level.ThreadedLevelLightEngine engine =
                        (net.minecraft.server.level.ThreadedLevelLightEngine) server.overworld()
                                .getChunkSource().getLightEngine();
                int attempts = task instanceof InflightLight inf ? inf.attempts : 0;
                startLightBarrier(server, engine, task,
                        System.currentTimeMillis() + CONVERGENCE_WAIT_TIMEOUT_MS,
                        attempts + 1);
                return true;
            }
        } catch (Throwable t) {
            DebugLogger.warn(DebugLogger.LogType.ASYNC,
                    "[SHADOW_LIGHT] Retry submit failed ({}, {})", new ChunkPos(task.key).x, new ChunkPos(task.key).z);
            return false;
        }
    }

    /** 同柱任一投递队列出现新数据 = 旧任务不得续投/回传。 */
    private static boolean hasNewerWork(long key) {
        return pending.containsKey(key) || generated.containsKey(key)
                || pendingDeltas.containsKey(key) || pendingLightUpdates.containsKey(key);
    }

    /**
     * 2026-08-15 移除（原 scheduleNeighborRelight）：每柱收敛 × 4 邻柱整柱清光重算 +
     * 全柱光包回传是「亮→黑→亮」跳变与光包风暴的主放大器——跑图时成百上千
     * 「Queued full light update」占据在途屏障与回传队列，区块注入（pending）反被光工作
     * 挤占（虚空区块迟迟未补全）。邻柱边界补光本就由跨柱传播 + collectLightUpdate →
     * drainLightMasks 桥梁事件驱动覆盖（邻柱到货时其传播会写进已存在柱的边界 section，
     * 触发光照更新包补发）；sky 是 per-column 播种，与邻柱无关——反向补光纯属冗余。
     */

    /** 丢弃某柱已收集、尚未消费的光照更新掩码（最终全量光回传前调用）。 */
    private static void discardLightMask(long key) {
        LightMask mask = lightUpdates.remove(key);
        if (mask != null) {
            synchronized (mask) {
                mask.discarded = true;
                mask.skySections.clear();
                mask.blockSections.clear();
            }
        }
    }

    /**
     * per-chunk 超时兜底扫表（提交 5s 未完成 → 欠光标脏 + 回传 converged=false，由光照
     * 更新桥梁补发）：主线程帧尾 {@link #drainReady} 为主扫描点 + 消费循环轮顶第二扫描点。
     * <p>
     * 选「主线程帧尾统一扫」而非 per-key 延时任务（工程上最稳）：零调度器/取消竞态
     * （无 per-key ScheduledFuture 泄漏、无取消窗口内重复触发）、无额外线程唤醒；
     * 帧粒度（60fps ≈ 16ms）相对 5s 兜底精度足够。与完成回调竞速同一
     * {@link #completeLight}（条件移除 exactly-once），谁先赢谁回传。
     */
    private static void sweepLightTimeouts() {
        if (inflightLight.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (InflightLight inf : inflightLight.values()) {
            if (now >= inf.deadlineMs && completeLight(inf, false)) {
                DebugLogger.warn(DebugLogger.LogType.ASYNC,
                        "[SHADOW_LIGHT] Light timeout ({}ms) ({}, {}), pushing with partial light",
                        CONVERGENCE_WAIT_TIMEOUT_MS, new ChunkPos(inf.key).x, new ChunkPos(inf.key).z);
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
                                  net.minecraft.server.level.ServerLevel level, boolean converged) {
        ChunkPos pos = chunk.getPos();
        // P1（T7）：buildPacket 读注入 chunk section 容器（extractChunkData →
        // LevelChunkSection.write → PalettedContainer.acquire）——与 hash 比对线程
        // （chunkHashOf / computeSectionHashes）同 chunk 锁互斥，消除 1.21.11
        // ThreadingDetector 崩溃（T7 线程转储：consumeLoop pushReady 打包 vs hash 线程）。
        ClientboundLevelChunkWithLightPacket packet;
        synchronized (chunkLock(pos)) {
            // 打包前天空光只读校验：源之上空层 → 官方包 empty 掩码 → 客户端显式置
            // 全 0 = 黑块。实际播种已由屏障前预播种任务（Threaded queueSectionData）
            // 完成；这里只核验告警（见 ShadowSeedServer.fillSkySectionsForPacket）。
            ShadowSeedServer healServer = ShadowServerRegistry.getInstance().get();
            if (healServer != null) {
                healServer.fillSkySectionsForPacket(pos, chunk);
            }
            packet = SeedGenChunkCodec.buildPacket(chunk, level);
        }
        if (packet == null) {
            DebugLogger.warn(DebugLogger.LogType.ASYNC,
                    "[SHADOW_CHUNK] Build packet failed ({}, {})", pos.x, pos.z);
            return;
        }
        io.github.limuqy.mc.hassium.utils.ChunkFlowTiming.recordReady(key);
        ready.offer(new ReadyItem(packet, null),
                io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher.chunkKey(
                        pos, io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher.OP_CHUNK_APPLY),
                io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher.authoritativePriority(pos),
                KeyedPriorityQueue.OfferPolicy.REPLACE);
        // 运行期光照标脏（用户语义：只有 converge 未达的欠光块标记，R2 读盘命中
        // 不得直接打包，必须重算）：converged=false → 标脏；true → 清除（重算收敛）。
        // 标脏表跨影子端关停保留（进程内），R2 继承 R1 的欠光状态。
        // 欠光补发不再登记收敛补发 watcher——由光照更新桥梁
        // （collectLightUpdate → drainLightMasks → OP_LIGHT_UPDATE）事件驱动承担。
        io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.markLightDirty(pos, !converged);
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
        // 打包前天空光只读校验（预播种应已保证源之上全 15；缺失仅告警，不再直写
        // queuedSections——直写与引擎线程 markNewInconsistencies 的 fastutil 迭代器并发，
        // 是 Worker-Main NPE / runLightUpdates 中断 / POST 永不执行的根因）。
        if (chunk != null) {
            synchronized (chunkLock(pos)) {
                ShadowSeedServer healServer = ShadowServerRegistry.getInstance().get();
                if (healServer != null) {
                    healServer.fillSkySectionsForPacket(pos, chunk);
                }
            }
        }
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
        ready.offer(new ReadyItem(null, packet),
                io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher.chunkKey(
                        pos, io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher.OP_LIGHT_UPDATE),
                io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher.authoritativePriority(pos)
                        + ChunkDistancePriority.TIER_BIAS,
                KeyedPriorityQueue.OfferPolicy.REPLACE);
        io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.markLightDirty(pos, !converged);
        DebugLogger.info(DebugLogger.LogType.CHUNK_APPLY,
                "[SHADOW_LIGHT] Queued full light update ({}, {}), converged={}",
                pos.x, pos.z, converged);
    }

    /**
     * 帧尾（MixinClientTick，渲染前）：先做出界卸载检查（{@link #tickChunkUnload}，
     * 需求 8 T5），再攒批光照更新（{@link #drainLightMasks}），
     * 最后按优先级消费回传队列——官方通道（{@code ClientPacketListener.handleLevelChunkWithLight}
     * / {@code handleLightUpdatePacket}）直接主线程调用，客户端原版 apply 路径。
     * 每帧 poll ≤ {@code max(1, ClientMainThreadBudget.getHardCap())}（投送限流），
     * 剩余留待下一帧；poll 后执行前被同 key 新任务 REPLACE 的旧条目丢弃
     * （isCurrent 校验，杜绝老数据覆盖新数据）。
     */
    public static void drainReady() {
        io.github.limuqy.mc.hassium.utils.ChunkFlowTiming.noteFrame(); // T0b 诊断：每帧 apply 计数
        tickChunkUnload();
        clearDirtyIfConverged();
        sweepLightTimeouts(); // per-chunk 光屏障 5s 超时兜底（主扫描点；低帧率由消费轮顶兜底）
        drainLightMasks();
        if (ready.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc != null ? mc.getConnection() : null;
        if (connection == null) {
            ready.clear(); // 断连竞态：丢弃（重连后由数据包路径重新提交）
            return;
        }
        int hardCap = Math.max(1, io.github.limuqy.mc.hassium.cache.client.ClientMainThreadBudget.getHardCap());
        int polls = 0;
        while (polls < hardCap) {
            KeyedPriorityQueue.Entry<ReadyItem> entry = ready.poll();
            if (entry == null) {
                break;
            }
            polls++;
            if (!ready.isCurrent(entry)) {
                continue; // 已被同 key 新任务取代：丢弃旧数据
            }
            ReadyItem item = entry.item();
            try {
                if (item.chunkPacket != null) {
                    int chunkX = item.chunkPacket.getX();
                    int chunkZ = item.chunkPacket.getZ();
                    connection.handleLevelChunkWithLight(item.chunkPacket);
                    // vanilla 可能因区块不在视距内而忽略（ClientChunkManager.loadChunkFromPacket
                    // 返回 null 并打印 Ignoring chunk...）。只有真正落地才登记/计数/探针；
                    // 否则不加入 shadowAppliedChunks，避免光桥误以为该柱已落地而继续发 light 包。
                    if (mc != null && mc.level != null && mc.level.hasChunk(chunkX, chunkZ)) {
                        shadowAppliedChunks.add(ChunkPos.asLong(chunkX, chunkZ));
                        io.github.limuqy.mc.hassium.metrics.NetworkStats.recordChunkApplied(chunkX, chunkZ);
                        io.github.limuqy.mc.hassium.utils.ChunkFlowTiming.recordApply(entry.key().posLong());
                        // 诊断探针（debug.chunkApplyLogging 开启时输出）：影子回传区块落地后
                        // 光照/方块采样（apply#/skyTop=0 即黑块嫌疑）
                        io.github.limuqy.mc.hassium.network.ClientChunkHandler.probeChunkState(
                                new ChunkPos(chunkX, chunkZ), mc.level, "shadow");
                    } else {
                        DebugLogger.info(DebugLogger.LogType.CHUNK_APPLY,
                                "[SHADOW_CHUNK] Vanilla ignored chunk ({}, {}) — not marked as applied",
                                chunkX, chunkZ);
                    }
                } else if (item.lightPacket != null) {
                    connection.handleLightUpdatePacket(item.lightPacket);
                    // 诊断探针：影子端光照补发落地（欠光补光节奏）
                    DebugLogger.info(DebugLogger.LogType.CHUNK_APPLY,
                            "[CHUNK_PROBE] source=light pos=({},{}) applied",
                            item.lightPacket.getX(), item.lightPacket.getZ());
                }
            } catch (Throwable t) {
                DebugLogger.warn(DebugLogger.LogType.ASYNC,
                        "[SHADOW_CHUNK] Official channel apply failed ({}, {})",
                        new ChunkPos(entry.key().posLong()).x, new ChunkPos(entry.key().posLong()).z);
            } finally {
                ready.release(entry);
            }
        }
    }

    /**
     * 光照收敛完成回调（帧尾，客户端主线程）：覆盖 consumeLoop 退出后引擎才收敛的窗口
     * （R1 全量注入 → consumeLoop 各批退出 → 引擎异步算光后收敛，此时无任何 pushReady 重推
     * 清脏）——确认全局收敛（{@code ShadowSeedServer.isLightConverged}）即清空光照标脏表，
     * 保证收敛光落盘（saveAll）不带脏标 → R2 读盘命中可跳过重算直接复用。
     * 标脏表未标脏时零开销短路（不查引擎）；未收敛时绝不清（欠光数据必须保持脏）。
     */
    private static void clearDirtyIfConverged() {
        if (!io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.hasLightDirty()) {
            return;
        }
        ShadowSeedServer server = ShadowServerRegistry.getInstance().get();
        if (server == null) {
            return;
        }
        try {
            if (server.isLightConverged()) {
                io.github.limuqy.mc.hassium.storage.ShadowStorageHashes.clearLightDirty();
                DebugLogger.info(DebugLogger.LogType.ASYNC,
                        "[SHADOW_LIGHT] Global convergence confirmed, cleared light-dirty marks");
            }
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
            shadowAppliedChunks.removeIf(key -> {
                ChunkPos pos = new ChunkPos(key);
                return !mc.level.hasChunk(pos.x, pos.z);
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
            ChunkPos pos = new ChunkPos(key);
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
            ChunkPos pos = new ChunkPos(key);
            if (isWithinBoundary(pos, pcx, pcz, boundary)) {
                it.remove(); // 步骤 1 与 2 之间的移动竞态：回界 → 取消
                continue;
            }
            LevelChunk chunk = server.injectedChunk(pos.x, pos.z);
            if (chunk == null) {
                it.remove(); // 已不在注入表（并发清理/替换）
                continue;
            }
            // 卸载前释放回传队列中该柱条目（待回传不卸载；KeyedPriorityQueue.removeIf）
            releaseReadyEntries(key);
            if (!server.unloadChunk(pos, chunk)) {
                it.remove(); // 落盘失败：放弃本柱（内存驻留，断连 saveAll 兜底）
                continue;
            }
            it.remove();
            discardLightMask(key); // 已卸载柱的光更新掩码作废（防跨会话残留 + drain 空转）
            shadowAppliedChunks.remove(key); // 客户端柱已卸载：光桥不再向其发增量光
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

    /** 卸载前释放回传队列中该柱全部条目（chunk 包 + light 包；按条目谓词移除）。 */
    private static void releaseReadyEntries(long key) {
        int x = new ChunkPos(key).x;
        int z = new ChunkPos(key).z;
        ready.removeIf(item -> (item.chunkPacket != null
                        && item.chunkPacket.getX() == x && item.chunkPacket.getZ() == z)
                || (item.lightPacket != null
                        && item.lightPacket.getX() == x && item.lightPacket.getZ() == z));
    }

    /**
     * 光照更新攒批打包（客户端主线程帧尾，{@link #drainReady} 先调）：
     * light 线程收集的绝对 sectionY 掩码 → 按 {@code engine.getMinLightSection()} 偏移
     * 转 BitSet（mask 位 = sectionY − minLightSection，与 ClientboundLightUpdatePacketData
     * 遍历语义一致，两版零适配）→ 构造官方 {@link ClientboundLightUpdatePacket} 入回传队列
     * （op={@code OP_LIGHT_UPDATE}，REPLACE，优先级 {@code authoritativePriority(pos)}）。
     * <p>
     * 每帧构建硬顶 = {@code max(1, getHardCap())}（与消费顶同源，防主线程帧尖峰：
     * 包构造逐 section 拷贝 2048B DataLayer，传播风暴时不可无界）；剩余掩码留待下一帧，
     * 构建成功才清除（synchronized(mask) 内清空 + 条件移除，light 线程并发收集不丢失）。
     */
    private static void drainLightMasks() {
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
        net.minecraft.server.level.ServerLevel level = server.overworld();
        LevelLightEngine engine = level.getLightEngine();
        int minLightSection = engine.getMinLightSection();
        int lightSectionCount = engine.getLightSectionCount();
        List<Long> allKeys = new ArrayList<>(lightUpdates.keySet());
        allKeys.sort(Comparator.comparingDouble(
                k -> io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher
                        .authoritativePriority(new ChunkPos(k))));
        int buildCap = Math.max(1, io.github.limuqy.mc.hassium.cache.client.ClientMainThreadBudget.getHardCap());
        // 先按距离排序，再只取「客户端已落地」的前 buildCap 柱：未落地柱的光包
        // 不占本帧构建预算（否则排序前列全是未落地柱 → 已落地柱的光包永远排不上）。
        List<Long> keys = new ArrayList<>(Math.min(buildCap, allKeys.size()));
        for (Long key : allKeys) {
            if (keys.size() >= buildCap) {
                break;
            }
            ChunkPos candidate = new ChunkPos(key);
            if (mc.level == null || !mc.level.hasChunk(candidate.x, candidate.z)
                    || !shadowAppliedChunks.contains(key)) {
                continue; // 光桥只对「影子全量区块包已落地」的柱发送，未落地柱光包
                          // 会排在区块包前面（主线程预算被吃满 → 区块不加载），
                          // 或打到原版直发已加载的旧柱上（先亮 → 影子包到达覆盖成暗）。
            }
            keys.add(key);
        }
        for (Long key : keys) {
            ChunkPos pos = new ChunkPos(key);
            if (inflightLight.containsKey(key)) {
                // 光屏障未完成：先攒着，等 finishLight 的最终全量光一起回传。
                // 中间传播波次立即落地会造成「先亮→再黑→终亮」的跳变。
                continue;
            }
            LightMask mask = lightUpdates.get(key);
            if (mask == null) {
                continue;
            }
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
                ClientboundLightUpdatePacket packet =
                        new ClientboundLightUpdatePacket(pos, engine, skyMask, blockMask);
                ready.offer(new ReadyItem(null, packet),
                        io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher.chunkKey(
                                pos, io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher.OP_LIGHT_UPDATE),
                        io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher.authoritativePriority(pos)
                                + ChunkDistancePriority.TIER_BIAS,
                        KeyedPriorityQueue.OfferPolicy.REPLACE);
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
     */
    public static void collectLightUpdate(LightLayer layer, SectionPos sectionPos) {
        if (layer == null || sectionPos == null || !isEnabled()) {
            return;
        }
        long key = ChunkPos.asLong(sectionPos.x(), sectionPos.z());
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

    /** 断连清理：清空投递/生成/回传/光照收集（影子服务端由 registry 统一关停保存）。 */
    public static void onDisconnect() {
        pending.clear();
        pendingDeltas.clear();
        pendingDeltaRequests.clear();
        generated.clear();
        pendingLightUpdates.clear();
        inflightLight.clear(); // 在途光屏障：回调侧条件移除失败即短路丢弃（断连竞态）
        ready.clear();
        lightUpdates.clear();
        unloadPending.clear();
        requestedMisses.clear();
        shadowAppliedChunks.clear();
        consumeRunning.set(false);
    }

    /** 磁盘区块 contentHash 与远程权威 hash 比对（同 ChunkContentHashUtil 算法）。 */
    private static boolean diskHashMatches(LevelChunk chunk, long remoteHash) {
        try {
            long diskHash = io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil
                    .combineSectionHashes(io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil
                            .computeSectionHashes(chunk));
            if (diskHash != remoteHash) {
                org.slf4j.LoggerFactory.getLogger("Hassium/ShadowDisk")
                        .debug("Shadow disk hash MISMATCH disk={} remote={}", diskHash, remoteHash);
            }
            return diskHash == remoteHash;
        } catch (Throwable t) {
            return false;
        }
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

    /** 诊断：回传队列大小。 */
    public static int readyCount() {
        return ready.size();
    }

    /** 光屏障来源：决定 submitLightBatch 提交时的队列条件移除与 finishLight 回传前 REPLACE 校验方式。 */
    private enum LightSource {
        /** 远程全量注入（{@link #submit} → pending）。 */
        PENDING,
        /** 本地生成 / 磁盘命中 / relight（{@link #submitGenerated} → generated）。 */
        GENERATED,
        /** 分段增量（{@link #submitDelta} → pendingDeltas）。 */
        DELTA,
        /** 增量算光 / 邻柱补光：只回传光包（{@link #submitLightDelta} → pendingLightUpdates）。 */
        LIGHT_ONLY
    }

    /** 光照统计口径：REUSE_CACHE = 命中点已记 shadow reuse；RECOMPUTE = 光屏障完成后记 miss + 重算耗时。 */
    private enum LightMetric {
        REUSE_CACHE,
        RECOMPUTE
    }

    /** 光屏障提交单元（consumeLoop 装配，submitLightBatch 消费）。 */
    private static class LightTask {
        final long key;
        final LightSource source;
        /** PENDING: 提交的 packet；GENERATED: 提交的 GenEntry；DELTA: null；LIGHT_ONLY: LightWork。 */
        final Object token;
        final net.minecraft.world.level.chunk.LevelChunk chunk;
        final net.minecraft.server.level.ServerLevel level;
        final LightMetric metric;
        /** 最近一次屏障提交时刻（重试时刷新）；光屏障完成时用于记重算耗时。 */
        volatile long submittedAtNs;

        LightTask(long key, LightSource source, Object token,
                  net.minecraft.world.level.chunk.LevelChunk chunk,
                  net.minecraft.server.level.ServerLevel level, LightMetric metric) {
            this.key = key;
            this.source = source;
            this.token = token;
            this.chunk = chunk;
            this.level = level;
            this.metric = metric;
        }
    }

    /** 在途光屏障条目：LightTask + 超时截止/完整度重试计数（completeLight / sweepLightTimeouts
     *  共用，条件移除 {@code inflightLight.remove(key, inf)} 保证 exactly-once）。 */
    private static final class InflightLight extends LightTask {
        final long deadlineMs;
        final int attempts;

        InflightLight(long key, LightSource source, Object token,
                      net.minecraft.world.level.chunk.LevelChunk chunk,
                      net.minecraft.server.level.ServerLevel level, long deadlineMs,
                      LightMetric metric) {
            this(key, source, token, chunk, level, deadlineMs, 0, metric);
        }

        InflightLight(long key, LightSource source, Object token,
                      net.minecraft.world.level.chunk.LevelChunk chunk,
                      net.minecraft.server.level.ServerLevel level, long deadlineMs,
                      int attempts, LightMetric metric) {
            super(key, source, token, chunk, level, metric);
            this.deadlineMs = deadlineMs;
            this.attempts = attempts;
        }
    }

    /** lightReuse=true：区块光来自缓存复用（命中点已记 shadow reuse）；false：本会话要重算光。 */
    private record GenEntry(net.minecraft.world.level.chunk.LevelChunk chunk,
                            net.minecraft.server.level.ServerLevel level,
                            boolean lightReuse) {}
}
