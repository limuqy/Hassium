package io.github.limuqy.mc.hassium.shadow.light;

import io.github.limuqy.mc.hassium.platform.client.ShadowClientApi;
import io.github.limuqy.mc.hassium.platform.client.ShadowClientBridge;
import io.github.limuqy.mc.hassium.platform.client.TraceOrigin;

import io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer;
import io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry;
import io.github.limuqy.mc.hassium.shadow.server.SeedGenExecutor;
import io.github.limuqy.mc.hassium.shadow.server.ShadowChunkPersistenceRole;
import io.github.limuqy.mc.hassium.shadow.track.ShadowTrackingSession;
import io.github.limuqy.mc.hassium.shadow.track.ShadowChunkSource;
import io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageHashes;

import io.github.limuqy.mc.hassium.compat.ClientLoadingScreenCompat;
import io.github.limuqy.mc.hassium.concurrent.HassiumTaskExecutor;
import io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue;
import io.github.limuqy.mc.hassium.concurrent.TaskCategory;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
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
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
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
 *   <li>消费（后台池单循环 CAS，管道化）：取批 → 注入 → {@code initializeLightImmediately}
 *       → 非 REUSE 且未 promote 时入 {@link LightNeighborhoodGate}（3×3 齐套，钉死）
 *       → {@link #startLightBarrier} 从零 initializeLight+light → {@link #finishLight}
 *       → {@link #pushReady} 打包。不走 ChunkMap LIGHT future / holder 单写者路径</li>
 *   <li>光照完成（native light future 回调）：{@link #completeLight} exactly-once 收口</li>
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

    private static ShadowClientApi client() {
        return ShadowClientBridge.get();
    }



    /** 投递队列：DimensionKey 复合键 -> 服务端 packet 与仅诊断来源（REPLACE）。 */
    private static final ConcurrentHashMap<Long, PendingEntry> pending =
            new ConcurrentHashMap<>();
    /** 分段增量队列：复合键 -> (dimension, DeltaEntry)。REPLACE 语义：服务端每份 delta
     *  都是「当前服务端状态 vs 客户端基线」的完整差异，后到覆盖先到（内容都正确）。 */
    private static final ConcurrentHashMap<Long, DeltaWork> pendingDeltas =
            new ConcurrentHashMap<>();
    /** 本地生成队列：复合键 -> (chunk, level)（SeedGen worldgen 完成 / 磁盘光脏 relight，打包回传）。 */
    private static final ConcurrentHashMap<Long, GenEntry> generated = new ConcurrentHashMap<>();
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
     * 影子→客户端回传队列（同柱同 op REPLACE）。距离优先级只在服务端推送与
     * {@link io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher} 缓存读取。
     */
    private static final KeyedPriorityQueue<ReadyItem> ready = new KeyedPriorityQueue<>(64);
    private static final AtomicLong applyOfferSeq = new AtomicLong();

    /** 回传队列元素：整柱包（blocks + light 一次带全）。 */
    private record ReadyItem(ClientboundLevelChunkWithLightPacket chunkPacket,
                             boolean renderOnly,
                             TraceOrigin traceOrigin) {}

    /**
     * 两阶段光照：已过 INITIALIZE_LIGHT 的柱（空 DataLayer 已安装）。
     * 对齐原版生成金字塔：邻柱先到 INITIALIZE_LIGHT，中心柱才跑 LIGHT——
     * 这样中心柱传播时可写入邻柱空层。key = DimensionKey 复合键。断连/卸载清除。
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

    /**
     * 【会话级 · 2026-09-19 任务 #36】本会话 LIGHT 步**已完成**的柱（**与 I2 无关**）。
     * <p>
     * 语义 = 原版 {@code ChunkStatus.LIGHT}「本会话已完成」这个**事实**，不是「权威」这个
     * **判断**（{@link #isColumnLightAuthoritative}）。原版推送触发点是
     * {@code ChunkMap.prepareTickingChunk} 的 {@code getChunkRangeFuture(holder, 1, FULL)}
     * ——该柱及 3×3 全部到 {@code FULL}（range=1）才发，**发包处不看光**；而状态链
     * {@code LIGHT → SPAWN → FULL} 蕴含「本会话 LIGHT 已完成」。
     * <p>
     * 为什么不能复用 {@code isLightCorrect} / {@code promotedClean}：两者都是**权威**语义，
     * 严格强于「LIGHT 已跑完」——前者被 I2 扣留，后者要求 3×3 真齐全。飞行中的前沿柱
     * （向外邻柱在窗外）结构性地永远拿不到它们 → 被推送门永久挡下 → 永久洞
     * （handoff §「飞行场景的两个空洞」①）。
     * <p>
     * 写入点 = {@link #completeLight}（LIGHT future 完成、唯一完成收口），保证早于同一柱的
     * {@code pushReady}。清除点 = 卸载（{@code cancelChunkWork}）/ 断连
     * （{@code onDisconnect}）——与 {@link #lightInitPassed} 同生命周期。
     */
    private static final java.util.Set<Long> lightRan = ConcurrentHashMap.newKeySet();

    /**
     * 【S2c 迟到重触发】登记「降级放行」的柱（{@link LightNeighborhoodGate#wasPromotedClean}
     * 为假 → 其 {@code lightChunk} 跑的时候 3×3 还没齐）→ 邻柱补齐后重新过门算光并重交付。
     * <p>
     * 为什么必须重算：降级放行时缺邻被引擎当 {@code Blocks.BEDROCK} 挡天光 → 该柱偏暗；
     * 而光一旦被标 {@code isLightCorrect} 就会**永久复用**（"R2 仍有黑柱"的候选机制）。
     * S2b 已把 {@code isLightCorrect} 对非权威柱扣下，本集合负责**把它补回来**——
     * 否则那些柱每会话都重算（只有惩罚没有修复）。
     * <p>
     * 触发点 = {@link #finishLight}（任一柱 LIGHT 完成时复查其 3×3 覆盖的登记项）。
     * 重算条件 = {@link LightNeighborhoodGate#isNeighborhoodLightReady}（此时再过门必是干净放行）。
     * <p>
     * <b>交付不受本集合门控</b>（刻意）：降级柱照常 {@code pushReady}——不交付会在客户端留一个
     * 空洞（虚空），比一个偏暗的柱更显眼，原版还会把它当未加载区。所以是「先交付 + 后覆盖」。
     */
    private static final ConcurrentHashMap<Long, PendingAuth> pendingAuthoritative =
            new ConcurrentHashMap<>();

    // ===== 影子算光「重复计算」量化探针 =====
    /**
     * 纯只读计数（不改变任何行为），随 {@code roundN.json} 的顶层 {@code lightProbe} 块输出，
     * 轮次边界由 {@link #resetLightProbeCounters()} 清零。口径：
     * <ul>
     *   <li>{@code initPhase1}/{@code initPhase1Skipped}/{@code initBarrier}：INITIALIZE_LIGHT
     *       引擎 pass 的发起次数（phase-1 实际发起 / 幂等门挡下 / 屏障内另行发起）。
     *       {@code initPhase1 + initBarrier} = 该轮 INITIALIZE 引擎 pass 总量。</li>
     *   <li>{@code barrierRecompute}/{@code barrierReuse}：进入屏障的柱数（按 metric）。</li>
     *   <li>{@code barrierReuseWithEngineLight}：REUSE 柱中**仍**以 {@code lightCorrect=true}
     *       建 native chunk 跑引擎两阶段的柱数（复用柱未跳过算光的量）。</li>
     *   <li>{@code barrierReusedPhase1}：复用 phase-1 native chunk 跳过 INITIALIZE 的次数。</li>
     *   <li>{@code reusableProbeCalls}：{@link #isLightReusable} 调用次数（每次含全柱层扫描）。</li>
     *   <li>{@code gatePumpCalls}/{@code gateTryPromoteCalls}/{@code gatePromoted}：齐套门
     *       扫描轮次 / 邻域求值次数 / 放行柱数。</li>
     * </ul>
     */
    private static final AtomicLong probeInitPhase1 = new AtomicLong();
    private static final AtomicLong probeInitPhase1Skipped = new AtomicLong();
    private static final AtomicLong probeInitBarrier = new AtomicLong();
    private static final AtomicLong probeBarrierRecompute = new AtomicLong();
    private static final AtomicLong probeBarrierReuse = new AtomicLong();
    private static final AtomicLong probeBarrierReuseWithEngineLight = new AtomicLong();
    /** 屏障内复用 phase-1 只跑 LIGHT（INITIALIZE 只算一遍）的次数。 */
    private static final AtomicLong probeBarrierReusedPhase1 = new AtomicLong();
    private static final AtomicLong probeReusableProbeCalls = new AtomicLong();
    private static final AtomicLong probeGatePumpCalls = new AtomicLong();
    private static final AtomicLong probeGateTryPromoteCalls = new AtomicLong();
    private static final AtomicLong probeGatePromoted = new AtomicLong();
    /** S2c：降级放行柱的**首次**登记次数 / 邻域补齐后真正重算重交付的次数。 */
    private static final AtomicLong probePendingAuthRegistered = new AtomicLong();
    private static final AtomicLong probePendingAuthRelight = new AtomicLong();

    /** 轮次边界清零（{@code ScenarioEngine.resetNetworkStatsForRound2}）。 */
    public static void resetLightProbeCounters() {
        probeInitPhase1.set(0);
        probeInitPhase1Skipped.set(0);
        probeInitBarrier.set(0);
        probeBarrierRecompute.set(0);
        probeBarrierReuse.set(0);
        probeBarrierReuseWithEngineLight.set(0);
        probeBarrierReusedPhase1.set(0);
        probeReusableProbeCalls.set(0);
        probeGatePumpCalls.set(0);
        probeGateTryPromoteCalls.set(0);
        probeGatePromoted.set(0);
        probePendingAuthRegistered.set(0);
        probePendingAuthRelight.set(0);
    }

    /** 追加顶层 {@code "lightProbe": {...},} 块（冒烟 JSON 顶层键只增不改名）。 */
    public static void appendLightProbeJson(StringBuilder sb) {
        // 光照调试开关（debug.lightLogging，别名 debug.lightVerify）关闭时不输出该块。
        if (!DebugLogger.isEnabled(DebugLogger.LogType.LIGHT)) {
            return;
        }
        sb.append("  \"lightProbe\": {\n");
        probeField(sb, "initPhase1", probeInitPhase1.get());
        probeField(sb, "initPhase1Skipped", probeInitPhase1Skipped.get());
        probeField(sb, "initBarrier", probeInitBarrier.get());
        probeField(sb, "barrierRecompute", probeBarrierRecompute.get());
        probeField(sb, "barrierReuse", probeBarrierReuse.get());
        probeField(sb, "barrierReuseWithEngineLight", probeBarrierReuseWithEngineLight.get());
        probeField(sb, "barrierReusedPhase1", probeBarrierReusedPhase1.get());
        probeField(sb, "reusableProbeCalls", probeReusableProbeCalls.get());
        probeField(sb, "gatePumpCalls", probeGatePumpCalls.get());
        probeField(sb, "gateTryPromoteCalls", probeGateTryPromoteCalls.get());
        probeField(sb, "gatePromoted", probeGatePromoted.get());
        probeField(sb, "pendingAuthRegistered", probePendingAuthRegistered.get());
        probeLastField(sb, "pendingAuthRelight", probePendingAuthRelight.get());
        sb.append("  },\n");
    }

    private static void probeField(StringBuilder sb, String name, long value) {
        sb.append("    \"").append(name).append("\": ").append(value).append(",\n");
    }

    private static void probeLastField(StringBuilder sb, String name, long value) {
        sb.append("    \"").append(name).append("\": ").append(value).append('\n');
    }

    private static final AtomicBoolean consumeRunning = new AtomicBoolean(false);

    /** 原版 LIGHT future 请求的提交顺序锁；不持有该锁等待 future 或执行打包。 */
    public static final Object LIGHT_ENGINE_MUTEX = new Object();


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
    /** 光照命中/重算已记账柱（复合键）：一柱一次命中或一次重算。 */
    private static final java.util.Set<Long> accountedLights = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** hash 分流计数（会话累计；断连清零）。冒烟探针字段兼容保留；热路径不再写入诊断集合（④）。 */
    private static final AtomicLong hashMemoryHits = new AtomicLong();
    private static final AtomicLong hashMemoryMismatches = new AtomicLong();
    private static final AtomicLong hashDiskHits = new AtomicLong();
    private static final AtomicLong hashDiskMismatches = new AtomicLong();
    private static final AtomicLong hashAbsents = new AtomicLong();
    private static final AtomicLong hashLeftovers = new AtomicLong();
    private static final AtomicLong hashEntriesProcessed = new AtomicLong();
    private static final AtomicLong hashEntryDuplicates = new AtomicLong();
    private static final AtomicLong hashMemoryMismatchDuplicates = new AtomicLong();
    private static final AtomicLong hashDiskMismatchDuplicates = new AtomicLong();
    private static final AtomicLong hashLeftoverDuplicates = new AtomicLong();

    /** ④：HashObservation 集合/observe* 已删；unique 类探针字段恒 0（兼容 schema）。 */
    public static long hashEntriesProcessedCount() {
        return hashEntriesProcessed.get();
    }

    public static long hashEntriesUniqueCount() {
        return 0L;
    }

    public static long hashChunkKeysUniqueCount() {
        return 0L;
    }

    public static long hashEntryDuplicatesCount() {
        return hashEntryDuplicates.get();
    }

    public static long hashMemoryMismatchUniqueCount() {
        return 0L;
    }

    public static long hashMemoryMismatchDuplicatesCount() {
        return hashMemoryMismatchDuplicates.get();
    }

    public static long hashDiskMismatchUniqueCount() {
        return 0L;
    }

    public static long hashDiskMismatchDuplicatesCount() {
        return hashDiskMismatchDuplicates.get();
    }

    public static long hashLeftoverUniqueCount() {
        return 0L;
    }

    public static long hashLeftoverUniqueChunkKeysCount() {
        return 0L;
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

    /** ShadowPull compare 路径是否可用（引擎 + 客户端缓存门 + 协商 SHADOW_PULL）。 */
    public static boolean isPullPathAvailable() {
        if (!isEnabled()) {
            return false;
        }
        if (!io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance().isClientCacheEnabled()) {
            return false;
        }
        try {
            return io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps.has(
                    io.github.limuqy.mc.hassium.protocol.handshake.ClientLoginNegotiation.current(),
                    io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps.SHADOW_PULL);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 重入待 compare：本地仍有基线（注入/盘 hash）且客户端已不持有落地凭据。
     * 此时禁止把本地柱当缓存命中盲 publish，必须先向真服 compare-pull；
     * 缓存全命中只在 UNCHANGED 响应后的 publishCached 路径记账。
     */
    public static boolean isReentryPendingCompare(String dimension, ChunkPos pos) {
        if (dimension == null || pos == null) {
            return false;
        }
        if (!isPullPathAvailable()) {
            return false;
        }
        if (hasClientApplyEpoch(dimension, pos)) {
            return false;
        }
        return hasLocalPullBaseline(dimension, pos);
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
        // 必须含齐套门：否则 halo->visible / redeliver 每泵重复 submitPreLight，
        // 会 REPLACE 门控条目、超时起点永远被重置 → 虚空/黑柱不自愈。
        return generated.containsKey(key)
                || inflightLight.containsKey(key)
                || LightNeighborhoodGate.isAwaiting(key);
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
        accountedLights.clear();
        shadowApplyEpochs.clear();
        ignoredApplyRetries.clear();
        // R2：上一会话 LIGHT/Promote 凭据作废，整柱 pack 须重新过齐套+两阶段（原版 isLighted 口径）
        LightNeighborhoodGate.clear();
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
        inflightLight.clear();
        networkInFlight.clear();
        requestedMisses.clear();
        accountedIngress.clear();
        shadowApplyEpochs.clear();
        ignoredApplyRetries.clear();
    }

    /** 直推已在影子管线里：hash miss 不得再打全量，否则和进服推送抢 4/tick 配额留下虚空。 */
    public static boolean isAuthoritativeIngressInFlight(long key) {
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
     * 不因层不全/全局收敛挡回传；超时仍 pushReady（标脏）。
     */
    private static final long CONVERGENCE_WAIT_TIMEOUT_MS = 10_000L;
    /** 每轮消费循环「光屏障提交」总量上限（含 pending/generated/delta 来源，
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
     * B5（2026-09-18 夜③）：清光后 sorter 水位由引擎任务调度自行消化；
     * {@code awaitEngineTaskDrain} no-op 与 drainAfterClear 调度已删除。
     */

    /** 管道低水位：在途低于此值才由完成回调重新 pump（= 1 批：低水位→满水位恰好补一批，
     *  避免每完成一块就一次 executor 往返）。 */
    private static final int PIPELINE_LOW_WATER = CONSUME_BATCH_LIMIT;

    private record DeltaWork(String dimension, io.github.limuqy.mc.hassium.protocol.SectionDeltaS2CPacket.DeltaEntry entry) {}

    private ShadowLightCompute() {}

    /**
     * 影子端暂不可用时的待处理权威区块处置。包内可见以守卫登录时序回归；
     * {@code false} 仅代表可恢复未就绪，不能与不可恢复失败混同。
     */
    public static boolean shouldRetainPendingWhenServerUnavailable(boolean isShadowServerFailed) {
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
    public static void onShadowServerReady() {
        if (shouldPumpAfterShadowServerReady(hasPendingWork())) {
            pump();
        }
    }

    /** 影子端 ready 后必须重新 pump 保留的权威工作。 */
    public static boolean shouldPumpAfterShadowServerReady(boolean hasPendingWork) {
        return hasPendingWork;
    }

    /**
     * 原版 {@code lightChunk(chunk, hasLight)} 的第二参：仅 {@link LightMetric#REUSE_CACHE}
     * （引擎内已有可用光）才跳过 {@code propagateLightSources}。
     * 不得单凭 {@code isLightCorrect()} 跳过——标志为真但层未装好时会打出空光包，
     * 盖掉客户端已自算的亮光（先亮后暗）。
     */
    public static boolean lightChunkHasExistingLight(boolean reuseCache) {
        return reuseCache;
    }

    /**
     * 客户端已落地过影子全量包时，相同方块不得再整柱重推。
     */
    public static boolean shouldSkipRedundantFullPush(boolean alreadyShadowApplied) {
        return alreadyShadowApplied;
    }

    /**
     * 可复用引擎光：{@code isLightCorrect} + 层已安装 + 引擎有非 0 光。
     * <p>
     * <b>不得</b>用「地表 sky≥15」作 REUSE 门：那会把大量本可复用的柱打成重算，
     * 齐套门控下 gen 队列只增不减 → 回程整片不落地（align5 loaded 612 vs 正常 ~1530）。
     * 地表 ≥15 只用于**落盘 isLightOn**（{@code persistAfterClientLightPush}）。
     */
    public static boolean isLightReusable(ShadowSeedServer server, ChunkPos pos,
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
        return server.hasUsableEngineLight(pos, chunk);
    }

    /**
     * <b>I2 判据</b>：该柱的光是否**权威**（3×3 真齐全时算出来的）。
     * <p>
     * = 该柱自身**完整**齐套放行（{@link LightNeighborhoodGate#wasPromotedClean}）。
     * <p>
     * <b>为什么不需要另查 8 邻</b>：{@code promotedClean} 在 promote 那一刻已断言
     * 「8 邻**全部**过了 INITIALIZE_LIGHT」——窗内无缺邻 <b>且</b> 无窗外邻柱
     * （{@code notInjected==0 && injectedNotInit==0 && outsideWindow==0}）。
     * 这正是原版 {@code ChunkStatus.LIGHT range=1 + hasLoadDependencies} 的依赖语义，
     * 也是 {@code ThreadedLevelLightEngine.lightChunk}「入口清假、引擎跑完才置真」的落盘形式
     * （缺邻时任务不会完成 → 落盘 {@code isLightOn=false} → 读档重算 LIGHT）。
     * <p>
     * <b>2026-09-19（S5）</b>：{@code outsideWindow} 也算缺邻。窗外邻柱虽不是我们的拉取责任，
     * 但引擎照样把它当基岩挡光 → 该柱的光在那个方向就是错的，**不得**标「光照完成」。
     * 直接后果：光环柱（计算域最外圈，外侧邻柱必然缺席）永不落盘，下次进服重算；
     * 权威柱（3×3 必在计算域内，见 {@code ChunkShapeCompat.containsDilated}）正常落盘。
     * <p>
     * <b>为什么不另查 8 邻 {@code wasPromoted}</b>（2026-09-19 修正）：REUSE 柱（读盘复用光）
     * 按设计不进齐套门（{@code startLightBarrier} 按 metric 跳过），永远不会 {@code wasPromoted}，
     * 于是 R2 里任何挨着缓存柱的柱都被判非权威。已删除该循环。
     * <p>
     * <b>三处统一判据</b>：标 {@code isLightCorrect} 落盘（{@code ShadowSeedServer.syncLightCorrect}
     * 唯一收口）/ 复用（{@link #isLightReusable} 经 {@code isLightCorrect} 传递）/
     * S2c 重算登记（{@link #pendingAuthoritative}）。
     * 降级放行的柱，`lightChunk` 时缺邻被引擎当 {@code Blocks.BEDROCK} 挡天光 → 光可能偏暗；
     * 一旦被标 {@code isLightCorrect} 就会**永久复用**（"R2 仍有黑柱"的候选机制，见 handoff §0.2）。
     * <p>
     * <b>刻意不连坐</b>：邻柱自身是否 clean 不参与判定——否则一个降级邻柱会污染 9 柱、成片不交付。
     * 代价：邻柱若自身偏暗，本柱从该方向进光也偏低（**檐下水平进光**正是这种场景），记为已知残差。
     * <p>
     * <b>刻意不门控交付</b>：见 {@link #pendingAuthoritative}。
     */
    public static boolean isColumnLightAuthoritative(String dimension, ChunkPos pos) {
        if (dimension == null || pos == null) {
            return false;
        }
        return LightNeighborhoodGate.wasPromotedClean(DimensionKey.key(dimension, pos.x, pos.z));
    }

    /**
     * 【#36】该柱**本会话是否跑完过 LIGHT 步**（与 I2 无关；见 {@link #lightRan}）。
     * <p>
     * 这是推送门的判据（{@code ShadowTrackingSession.isPushableToClient}）：前沿柱的
     * LIGHT 也跑完了 → 照推（与 vanilla {@code ChunkStatus.FULL} 蕴含 LIGHT 完成一致），
     * 不再出现「永远拿不到 promotedClean → 永久洞」。偏暗由 S2c/后续重交付覆盖。
     * <p>
     * 注意：它是**事实**判据，**不是**落盘判据——落盘（{@code isLightCorrect}）仍走
     * {@link #isColumnLightAuthoritative}（I2/S5），两者刻意不同口径。
     */
    public static boolean lightRanThisSession(String dimension, ChunkPos pos) {
        if (dimension == null || pos == null) {
            return false;
        }
        return lightRan.contains(DimensionKey.key(dimension, pos.x, pos.z));
    }

    /**
     * 整柱 REUSE/桥交付弱门：只看 {@code isLightCorrect()}。
     * {@code lightCompletedAfterLightTask} 集合已于 ④ 删除（只写不读）。
     */
    public static boolean isVanillaAlignedClientPackReady(String dimension, ChunkPos pos,
                                                          net.minecraft.world.level.chunk.LevelChunk chunk) {
        return pos != null && chunk != null && chunk.isLightCorrect();
    }

    /**
     * 远程 hash 已知且相同，或无远程 hash 时，已落地柱无需重复整柱推送。
     */
    public static boolean shouldSkipUnchangedRepush(boolean alreadyShadowApplied,
                                             boolean remoteHashPresent, boolean hashMatches,
                                             boolean lightComplete) {
        return alreadyShadowApplied && lightComplete
                && (!remoteHashPresent || hashMatches);
    }

    /** 在途屏障被新的整柱方块工作取代（同柱 pending/generated 重推）。 */
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
        return t != null && hasQueuedBlockWork(t.key);
    }

    /** 现在就能开屏障的投递。 */
    private static boolean hasStartablePendingWork() {
        return !pending.isEmpty() || !pendingDeltas.isEmpty() || !generated.isEmpty();
    }

    /** 服务端直推注入：同一柱的 hash miss 只记一次。 */
    public static boolean shouldAccountServerPushAsApplied(boolean alreadyRequested) {
        return !alreadyRequested;
    }

    /** 可见柱实际落到 ClientChunkCache 后的全量来源记账；光照另在光屏障提交时记。 */
    public static void accountVisibleNetworkIngress(String dimension, ChunkPos pos) {
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
    public static void accountVisibleNetworkIngress(String dimension, ChunkPos pos, boolean staleOrFallback) {
        if (pos == null) {
            return;
        }
        String resolved = dimension == null ? currentDimension() : dimension;
        long key = DimensionKey.key(resolved, pos.x, pos.z);
        // 网络侧仍按柱去重（同一柱的一次网络落地不重复计「新增/过期」）；
        // 缓存侧不做去重——往返重读按次计入命中（见 accountCacheFullHit）。
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

    /**
     * 缓存全命中按**次**记账，不做会话内去重：原版 A→B→A 会把 A 的柱再推一次，MOD 侧第二次
     * 交付走本地缓存同样替掉了一次网络推送——重复读取本身就是流量节省的一部分（用户 2026-09-19
     * 决策）。每次落地（{@link #accountAuthoritativeLanded}）恰好调用一次，不会重复计同一交付。
     */
    public static boolean accountCacheFullHit(String dimension, ChunkPos pos) {
        if (pos == null) {
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
        // SeedGen 方案 A：权威 compare 落地前禁止本地柱进真实客户端。
        // OVD renderOnly 不进本闸（无真服权威比对）：出权威后 AWAITING 残留不得挡住环带回填；
        // 顺带 clear，避免重进权威时 drainAuthorityAcquires 命中 isAwaiting 死等。
        String gateDim = dimension == null ? currentDimension() : dimension;
        if (renderOnly) {
            io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate.clear(gateDim, pos);
        } else if (io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate
                .blockClientDelivery(gateDim, pos.x, pos.z)) {
            notePublishBlocked(renderOnly, "seedGenAwaitingCompare", pos);
            return false;
        }
        // 【S6 推送门】不在此处判：本函数在**柱还没解析出来**（注入表未命中 → 读盘）时就会返回，
        // 在这里判只会把「状态未知」误判成「光照未完成」，把整条 OVD 读盘路径掐掉（实测 R2 被挡
        // 105 柱 = 整圈光环）。真正的门在拿得到柱的 `pushReady`。
        // 在途网络全量只拦「无本地柱」的缓存 publish：已有 injected 非占位时本地基线即 §3.2
        // 可交付数据（UNCHANGED/缓存回放），不因 bootGrid 空基线 pull 在途而误挡（phaseA
        // redeliver-publish-failed 实证）。OVD renderOnly 永不 pull，同样放行。
        String resolvedDim = dimension == null ? currentDimension() : dimension;
        ShadowSeedServer serverForFlight = ShadowServerRegistry.getInstance().getOrCreate();
        boolean hasLocalMaterial = serverForFlight != null
                && serverForFlight.injectedChunk(resolvedDim, pos.x, pos.z) != null;
        if (!renderOnly && !hasLocalMaterial && networkInFlight.contains(
                DimensionKey.key(resolvedDim, pos.x, pos.z))) {
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
        TraceOrigin origin = TraceOrigin.SHADOW_MEMORY_CACHE;
        if (chunk == null) {
            // 后台读盘：回程洪峰下 tryAcquireCacheRead 可能失败——**不得**因此返回 false。
            // UNCHANGED 路径 publish 失败会 retry 权威 FULL，把「缓存命中」打成网络新增
            // （用户往返「缓存空」实证：Cache baseline unavailable → retrying FULL）。
            if (scheduleAsyncDiskPublish(server, resolved, pos, localGeneration, renderOnly)) {
                return true;
            }
            // 异步调度失败（执行器停）才同步读盘；主线程执行器在跑时不堵 region 冷挂载
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
        // S3 光照缓存：publish 路径（R2 缓存回放等未必再进 scheduleChunkLoad）
        accountLightFromChunk(resolved, pos, chunk);
        if (localGeneration) {
            return submitGenerated(pos, chunk, level, false);
        }
        return submitPreLight(ShadowChunkSource.CACHE_SNAPSHOT, pos, chunk, level,
                traceOrigin(origin), renderOnly);
    }

    /** 内存 miss 后的在途异步读盘（按柱去重；配额/执行器不可用时返回 false 走同步兜底）。 */
    private static final java.util.Set<Long> DISK_PUBLISH_INFLIGHT =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 【#2】本会话「**已确认盘上没有**」的柱（= 上一次读盘返回 null）。
     * <p>
     * 语义（用户口径）：「只有**新进圈**的柱需要读一次，不用每次空槽位都去读一次」。
     * 命中本集合时 {@link #scheduleAsyncDiskPublish} 直接返回 {@code true}（= 已处理），
     * **不再调度读盘**，也不走「调度失败→同步读盘」兜底。
     * <p>
     * 清除点 = {@link #pruneDiskReadEmpty()}：该柱**离开等待窗**（计算域 ∪ OVD 带）即摘除，
     * 于是「脱离圈后再进圈」会重新获得一次读盘机会。断连清空。
     * <p>
     * 为什么必须有：空槽位若每次调用都重读，就是「读→null→再读」的稳态自旋
     * （实测 17:56:38 悬崖后 58 个虚拟线程恒定卡在读盘链、11 分钟无任何完成日志）。
     */
    private static final java.util.Set<Long> DISK_READ_EMPTY =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 断连清理：丢弃在途异步读盘标记，避免下一会话同柱被误判为已在途。 */
    public static void clearDiskPublishInFlight() {
        DISK_PUBLISH_INFLIGHT.clear();
    }

    /** 断连清理：丢弃「已确认盘上没有」标记（新会话可重新读一次）。 */
    public static void clearDiskReadEmpty() {
        DISK_READ_EMPTY.clear();
    }

    /**
     * 【#2】摘除已离开等待窗的「盘上没有」标记 —— 让「出圈再进圈」重新获得一次读盘机会。
     * <p>
     * 由 {@link #drainReady} 每帧在集合非空时调用；集合只含**读盘落空**的柱，规模有界。
     * 窗未知时 {@code isInNeighborhoodWindow} 保守返回 true → 不摘除（安全侧：宁可不重读）。
     */
    public static void pruneDiskReadEmpty() {
        if (DISK_READ_EMPTY.isEmpty()) {
            return;
        }
        ShadowTrackingSession session = ShadowTrackingSession.getInstance();
        if (session == null) {
            return; // 会话未就绪：不摘（安全侧：宁可不重读）
        }
        DISK_READ_EMPTY.removeIf(key -> !session.isInNeighborhoodWindow(
                DimensionKey.chunkXOf(key), DimensionKey.chunkZOf(key)));
    }

    private static boolean scheduleAsyncDiskPublish(ShadowSeedServer server, String dimension,
                                                    ChunkPos pos, boolean localGeneration,
                                                    boolean renderOnly) {
        long key = DimensionKey.key(dimension, pos.x, pos.z);
        if (DISK_READ_EMPTY.contains(key)) {
            return true; // 【#2】本圈已读过且盘上没有：不再反复读同一空槽位
        }
        if (!DISK_PUBLISH_INFLIGHT.add(key)) {
            return true; // 已在途：调用方按「会交付」处理，等回调
        }
        // 主线程 apply 预算与磁盘读配额：洪峰下允许超配额仍调度（读盘在后台池，
        // 不占 render apply 预算）。仅扣预算用于限流统计；失败不放弃调度。
        boolean budgeted = client().tryAcquireCacheRead();
        HassiumTaskExecutor executor = HassiumTaskExecutor.getClient();
        if (executor == null || !executor.isRunning()) {
            DISK_PUBLISH_INFLIGHT.remove(key);
            if (budgeted) {
                client().refundCacheRead();
            }
            return false;
        }
        server.loadFromDiskAsync(dimension, pos, loaded -> {
            DISK_PUBLISH_INFLIGHT.remove(key);
            if (budgeted) {
                client().refundCacheRead();
            }
            try {
                if (clientDimensionMismatch(dimension)) {
                    return;
                }
                // 【读到后复检（2026-09-19 用户口径）】读盘期间该柱可能已被别的路径注入——
                // 此时影子端内存里的那份才是最新，而盘上读回的是**尚未 flush 的脏槽旧副本**；
                // 注入会把它覆盖回去（内存里更新的柱被旧数据打回）。故一律丢弃本次盘上结果：
                // 不 inject、不记 DISK_READ_EMPTY（盘上其实有，只是不需要）、不算 miss。
                // 读前短路（ShadowStorageManager.readChunk 的 injected 判据）挡的是同一风险的
                // 另一侧窗口；两处都在才真正关掉 TOCTOU。
                if (server.injectedChunk(dimension, pos.x, pos.z) != null) {
                    DebugLogger.info(DebugLogger.LogType.LIGHT,
                            "[SHADOW_LIGHT] disk read discarded, column already in shadow memory "
                                    + "({}, {}) dim={}", pos.x, pos.z, dimension);
                    return;
                }
                if (loaded == null) {
                    // 【#2】记下「本圈盘上没有」：后续对同一柱不再反复调度读盘（出圈才摘除）。
                    DISK_READ_EMPTY.add(key);
                    onDiskPublishMiss(dimension, pos, localGeneration, renderOnly);
                    return;
                }
                // 读到了：撤掉「空」标记（该柱已注入，后续内存命中；将来被回收+再进圈可重读）。
                DISK_READ_EMPTY.remove(key);
                server.injectLoadedChunk(dimension, pos, loaded);
                accountLightFromChunk(dimension, pos, loaded);
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
        io.github.limuqy.mc.hassium.protocol.ShadowPullClient
                .requestAuthoritativeFull(dimension, List.of(pos));
    }

    /** publish 被门禁挡住时的节流日志（OVD/权威共用；R2 ovdLoaded=0 归因用）。 */
    private static void notePublishBlocked(boolean renderOnly, String reason, ChunkPos pos) {
        long now = System.currentTimeMillis();
        if (now - lastPublishBlockedMs < 2_000L) {
            return;
        }
        lastPublishBlockedMs = now;
        ShadowClientApi pipeline = client();
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
    }


    /**
     * 权威柱真正落到 ClientChunkCache 后按来源记账；在影子端注入或收到 packet 时不记。
     * 全量来源按键去重，避免同一柱的重传、磁盘与内存复用重复计数。
     * <p>
     * 分段增量已在 consumeLoop 记 {@code cacheDeltaCount}，落地不得再记全量 miss。
     * {@code origin == null}（应用日志关闭剥离诊断源）不推断来源，保守不计。
     */
    public static void accountAuthoritativeLanded(String dimension, ChunkPos pos, TraceOrigin origin) {
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
     * 有缓存区块但仍需传播的路径必须记重算。
     */
    public static boolean accountLightColumn(String dimension, ChunkPos pos, boolean reuse) {
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


    private static boolean alreadyShadowApplied(long key) {
        return shouldSkipRedundantFullPush(shadowApplyEpochs.containsKey(key));
    }

    /**
     * 影子回传入队序号：数值越小越先 apply。光包仍 FIFO；区块在有脚下焦点时
     * 由 {@link JoinWorldFocus#chunkApplyPriority} 把切比雪夫叠到序号上。
     */
    public static double fifoApplyPriority() {
        return applyOfferSeq.getAndIncrement();
    }


    /**
     * 磁盘命中后是否还需 LIGHT 续算：只看原版 {@code isLightCorrect()}
     * （NBT {@code isLightOn}）。未点亮则 {@code lightChunk(false)} 播种+传播；
     * 内容是否过期由 hash 表决定，不另维护光照脏表。
     */
    public static boolean diskNeedRelight(boolean lightCorrect) {
        return !lightCorrect;
    }

    /**
     * R2 磁盘 hash 命中是否必须重算：只看 NBT {@code isLightOn} 与光脏位。
     * 不得用引擎层是否已安装作判据——{@code ChunkSerializer.read} 的
     * {@code queueSectionData} 仍在光线程，立刻查层会把已落盘的屋檐光判成缺光，
     * 再 {@code lightChunk(false)} 丢掉邻柱入流。
     */
    public static boolean diskHitNeedRelight(boolean lightDirty, boolean lightCorrect) {
        return lightDirty || diskNeedRelight(lightCorrect);
    }


    private static boolean hasPendingWork() {
        return !pending.isEmpty() || !pendingDeltas.isEmpty() || !generated.isEmpty();
    }

    /**
     * 影子链路可用：引擎开启 && 握手完成（对端装了 Hassium）&& 影子未失败。
     * <p>
     * 未握手（含单人/局域网主机 memory、纯原版服）必须为 false——否则
     * {@code handleLevelChunkWithLight} 会 cancel 原版区块却无影子回填 → 虚空。
     */
    public static boolean isEnabled() {
        return HassiumConfigService.getInstance().isHassiumEngineEnabled()
                && client().isShadowEngineActive()
                && !client().isShadowServerFailed();
    }

    /** 原版区块可安全 cancel 并交给影子：还需影子实例已 ready（避免创建窗口虚空）。 */
    public static boolean shouldInterceptVanillaChunks() {
        return isEnabled() && client().isShadowServerReady();
    }

    /**
     * 单柱光照/打包失败不得关整台影子端。真服对 Hassium 客户端抑制原版区块包，
     * {@code failShadowServer} 之后切维（下界/末地/返主）会变成空 ClientChunkCache。
     */
    public static boolean shouldFailShadowOnSingleColumnFailure() {
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
    public static io.github.limuqy.mc.hassium.protocol.ShadowPullRequestC2SPacket.Entry localPullEntry(
            String dimension, ChunkPos pos) {
        Long localHash = io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageHashes.get(dimension, pos);
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
                io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageHashes.put(dimension, pos, h);
                return h;
            }).orElse(null);
            localHash = computed;
        }
        if (localHash == null) {
            return new io.github.limuqy.mc.hassium.protocol.ShadowPullRequestC2SPacket.Entry(
                    pos.x, pos.z, 0L, List.of(), 0);
        }
        if (chunk == null) {
            return new io.github.limuqy.mc.hassium.protocol.ShadowPullRequestC2SPacket.Entry(
                    pos.x, pos.z, localHash, List.of(), 0);
        }
        final long hashForEntry = localHash;
        java.util.Optional<io.github.limuqy.mc.hassium.protocol.ShadowPullRequestC2SPacket.Entry> locked =
                tryWithChunkLock(pos, () -> {
                    io.github.limuqy.mc.hassium.protocol.sectiondelta.SectionDeltaSnapshot snapshot =
                            io.github.limuqy.mc.hassium.protocol.sectiondelta.SectionDeltaSnapshots
                                    .getOrCapture(dimension, pos, chunk);
                    long[] hashes = snapshot.sectionHashes();
                    List<Long> sectionHashes = new ArrayList<>(hashes.length);
                    for (long hash : hashes) {
                        sectionHashes.add(hash);
                    }
                    return new io.github.limuqy.mc.hassium.protocol.ShadowPullRequestC2SPacket.Entry(
                            pos.x, pos.z, hashForEntry, sectionHashes, snapshot.planes(), 0);
                });
        return locked.orElseGet(() ->
                new io.github.limuqy.mc.hassium.protocol.ShadowPullRequestC2SPacket.Entry(
                        pos.x, pos.z, hashForEntry, List.of(), 0));
    }

    /** 已登记 hash 或驻留影子柱均可作为统一比较拉取的本地基线。 */
    public static boolean hasLocalPullBaseline(String dimension, ChunkPos pos) {
        if (dimension == null || pos == null) {
            return false;
        }
        if (io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageHashes.get(dimension, pos) != null) {
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
    public static void submitDelta(io.github.limuqy.mc.hassium.protocol.SectionDeltaS2CPacket packet) {
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
            io.github.limuqy.mc.hassium.protocol.ShadowPullClient.requestAuthoritativeFull(dimension, fallback);
        }
        pump();
    }

    // 【2026-09-19 已删除】原 submitLightDelta（LightDeltaS2CPacket 消费入口）+ pendingLightUpdates
    // + LightWork + LightSource.LIGHT_ONLY 全链：直连拓扑下 INetworkManagerService.sendLightDeltaPacket
    // 三端实现均无调用者 → 服务端从不发该包 → 三端 receiver 永不触达 → 该入口恒不可达（死代码）。
    // 真正的「方块更新 → 光回传」路径是 MixinClientPacketListener.hassium$onBlockUpdate（未取消，
    // 客户端本地也算）→ ClientMetadataHandler.forwardBlockUpdate → 影子端 setBlock → 引擎
    // onLightUpdate（引擎事件；光桥已删，无消费方——光只随整柱包下发）。

    /** 客户端当前维度 id（{@code namespace:path}）。
     * 优先 {@code mc.level}；切维窗口 level 为空时用影子 tracking 维度，**不得**静默回落
     * OVERWORLD——否则 TF 等自定义维柱会按主世界键注入/回放（脚下出现主世界地形）。 */
    public static String currentDimension() {
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
    public static void enqueueInjectedForLight(String dimension, ChunkPos pos, TraceOrigin origin) {
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
        TraceOrigin resolvedOrigin = origin == null ? TraceOrigin.SERVER_PUSH : origin;
        if (isNetworkOrigin(resolvedOrigin)) {
            networkInFlight.add(key);
            // 网络来源在注入时优先记账：同一次交付不得再被改写成缓存全命中（来源唯一）。
            // 注意：只约束「同一次交付」——往返重读是另一次交付，按次计入命中（见 accountCacheFullHit）。
            // REMOTE_PULL（compare-pull FULL）记过期桶；SERVER_PUSH/权威 FULL 记新增桶。
            accountVisibleNetworkIngress(resolved, pos,
                    resolvedOrigin == TraceOrigin.REMOTE_PULL);
        }
        // 【钉死】有引擎光且可复用 → REUSE；否则必须齐套门（禁止去门直算，见 MEMORY Rules）。
        if (isVanillaAlignedClientPackReady(resolved, pos, chunk)) {
            // 本分支**不跑** initializeLightImmediately，故必须自行补标 lightInitPassed：
            // 该集合会在卸载（cancelChunkWork）时被摘，而重注入若走本快路径就永远不会补回来
            // → 本柱对**所有邻柱**恒为「已注入但未过 INITIALIZE_LIGHT」('I') → 邻柱齐套门
            // 只能等软超时降级放行。实测（2026-09-19 20:29 run）：129 柱 waited≥5s、最长 25.2s，
            // 探针柱 (-41,-22) 卡 26.7s 的 'I' 邻柱 (-40,-21) 正是「73903 INIT done → 73903 被
            // unload 摘标 → 74034 重注入走 lit=true 快路径未补标」。同形于下方 reuseBarrier
            // 分支的既有修法。语义：isLightCorrect() ⟹ 引擎层是齐的 ⟹ 邻柱可写入，标记属实。
            lightInitPassed.add(key);
            generated.put(key, new GenEntry(chunk, level, isLightReusable(server, pos, chunk), false, resolvedOrigin));
            pump();
            return;
        }
        initializeLightImmediately(server, key, chunk, level);
        LightNeighborhoodGate.enqueue(key, resolved, pos,
                new GateContext(null, chunk, level,
                        LightMetric.RECOMPUTE, false, resolvedOrigin));
        pump();
    }

    /**
     * 光任务入队：可复用光才直接 REUSE pack；否则必须 {@link LightNeighborhoodGate} 齐套后再算光。
     * <b>禁止</b>改为「一律立刻 lightChunk」——屋檐黑实测（见 MEMORY Rules / LightNeighborhoodGate）。
     */
    static void submitLightReuseOrGate(long key, LevelChunk chunk,
                                              net.minecraft.server.level.ServerLevel level,
                                              boolean lightReuse, boolean renderOnly,
                                              TraceOrigin origin) {
        if (chunk == null || level == null) {
            return;
        }
        String dim = DimensionKey.dimensionOf(key);
        ChunkPos pos = new ChunkPos(DimensionKey.chunkXOf(key), DimensionKey.chunkZOf(key));
        if (lightReuse && chunk.isLightCorrect()) {
            // 同 enqueueInjectedForLight 的对齐快路径：本分支不跑 initializeLightImmediately，
            // 必须自行补标 lightInitPassed，否则该柱对邻柱恒为 'I'（卸载摘标 + 重注入走快路径
            // 不补标 → 邻柱齐套门白等超时）。见上方该分支的实测说明。
            lightInitPassed.add(key);
            generated.put(key, new GenEntry(chunk, level, true, renderOnly, origin));
            pump();
            return;
        }
        initializeLightImmediately(ShadowServerRegistry.getInstance().get(), key, chunk, level);
        LightNeighborhoodGate.enqueue(key, dim, pos,
                new GateContext(null, chunk, level, LightMetric.RECOMPUTE, renderOnly, origin));
        pump();
    }

    /**
     * S3 光照缓存口径（客户端统计，按柱去重）：
     * <ul>
     *   <li>{@code isLightCorrect()==true}（读盘完整光 / 注入前引擎已点亮）→ {@code lightReuseShadow}</li>
     *   <li>其它（网络注入后 {@code setLightCorrect(false)}、光未完成）→ {@code lightCacheMiss}</li>
     * </ul>
     * 调用点：{@code scheduleChunkLoad}、{@code injectChunk}、{@code injectLoadedChunk}、
     * {@code publishCachedChunk}（含异步读盘回调）。首记胜出，防双计。
     */
    public static void accountLightFromChunk(String dimension, ChunkPos pos,
                                             net.minecraft.world.level.chunk.LevelChunk chunk) {
        if (pos == null) {
            return;
        }
        String dim = dimension == null ? currentDimension() : dimension;
        if (dim == null) {
            return;
        }
        boolean complete = chunk != null && chunk.isLightCorrect();
        accountLightColumn(dim, pos, complete);
    }

    /**
     * {@code scheduleChunkLoad} 记账：注入表 → 读盘；无柱记重算。
     * 不短路 schedule future（S2 始终悬置 + Provider）。
     */
    public static void accountLightAtScheduleLoad(String dimension, ChunkPos pos) {
        if (pos == null) {
            return;
        }
        ShadowSeedServer server = ShadowServerRegistry.getInstance().get();
        if (server == null) {
            return;
        }
        String dim = dimension == null ? currentDimension() : dimension;
        if (dim == null) {
            return;
        }
        LevelChunk chunk = server.injectedChunk(dim, pos.x, pos.z);
        if (chunk == null) {
            LevelChunk disk = server.loadFromDisk(dim, pos);
            if (disk != null) {
                server.injectLoadedChunk(dim, pos, disk, false);
                chunk = disk;
            }
        }
        accountLightFromChunk(dim, pos, chunk);
    }

    /**
     * 两阶段光照第一阶段：立即跑 INITIALIZE_LIGHT（装空 DataLayer）。
     * <p>
     * 对齐原版生成金字塔：邻柱先到 INITIALIZE_LIGHT（空层已安装），中心柱才跑 LIGHT——
     * 这样中心柱传播时邻柱已有可写入的空层（缺邻时引擎按 Bedrock 挡光 → 屋檐/洞口黑）。
     * 交付侧不再有 section 级回传：光只随整柱包下发。
     * 幂等：已初始化的柱跳过。
     */
    public static void initializeLightImmediately(ShadowSeedServer server, long key,
                                           LevelChunk chunk,
                                           net.minecraft.server.level.ServerLevel level) {
        if (lightInitialized.contains(key)) {
            probeInitPhase1Skipped.incrementAndGet();
            return; // 已初始化：幂等
        }
        probeInitPhase1.incrementAndGet();
        try {
            net.minecraft.world.level.chunk.ProtoChunk nativeChunk =
                    io.github.limuqy.mc.hassium.compat.ShadowServerCompat
                            .createNativeLightChunk(level, chunk, false);
            io.github.limuqy.mc.hassium.compat.ShadowServerCompat
                    .initializeNativeLight(level, nativeChunk)
                    .whenComplete((ignored, throwable) -> {
                        if (throwable != null) {
                            DebugLogger.warn(DebugLogger.LogType.LIGHT,
                                    "[SHADOW_LIGHT] INITIALIZE_LIGHT failed ({}, {})",
                                    DimensionKey.chunkXOf(key), DimensionKey.chunkZOf(key));
                        } else {
                            nativeLightChunks.put(key, nativeChunk);
                            lightInitialized.add(key);
                            lightInitPassed.add(key); // 齐套门控判据（单调，见字段注释）
                            DebugLogger.info(DebugLogger.LogType.LIGHT,
                                    "[SHADOW_LIGHT] INITIALIZE_LIGHT done ({}, {})",
                                    DimensionKey.chunkXOf(key), DimensionKey.chunkZOf(key));
                        }
                    });
        } catch (Throwable failure) {
            DebugLogger.warn(DebugLogger.LogType.LIGHT,
                    "[SHADOW_LIGHT] INITIALIZE_LIGHT exception ({}, {})",
                    DimensionKey.chunkXOf(key), DimensionKey.chunkZOf(key), failure);
        }
    }

    /** 该柱是否已过 INITIALIZE_LIGHT（{@code lightInitialized} 的过渡态判据）。 */
    public static boolean isLightInitialized(long key) {
        return lightInitialized.contains(key);
    }

    /**
     * 该柱是否**曾经**跑完 INITIALIZE_LIGHT（齐套门控判据；单调，见 {@link #lightInitPassed}）。
     */
    public static boolean isLightInitPassed(long key) {
        return lightInitPassed.contains(key);
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
                io.github.limuqy.mc.hassium.client.ClientChunkPipeline pipeline =
                        io.github.limuqy.mc.hassium.client.ClientChunkPipeline.getInstance();
                pipeline.setApplyInProgress(true);
                try {
                    connection.handleLevelChunkWithLight(fPacket);
                } catch (Throwable t) {
                    DebugLogger.warn(DebugLogger.LogType.CHUNK_APPLY,
                            "[SHADOW_CHUNK] vanilla-direct apply failed ({}, {})", fPos.x, fPos.z);
                } finally {
                    pipeline.setApplyInProgress(false);
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
        // 原版交付门（ChunkStatus.LIGHT 完成前不整柱 pack）：未就绪 → lightReuse=false → 两阶段光。
        boolean lightReuse = isVanillaAlignedClientPackReady(dimension, pos, chunk)
                && isLightReusable(ShadowServerRegistry.getInstance().get(), pos, chunk);
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
                if (client().findStandingKey(generated) != null
                        && client().findStandingKey(pending) == null
                        && pendingLimit > 0) {
                    pendingLimit = limit - 1;
                }
                List<Map.Entry<Long, PendingEntry>> batch = new ArrayList<>(Math.max(4, pendingLimit));
                client().fillDistanceFirst(pending, batch, pendingLimit);
                for (Map.Entry<Long, PendingEntry> e : batch) {
                    io.github.limuqy.mc.hassium.utils.ChunkFlowTiming.recordConsume(e.getKey());
                }
                // 本轮提交总量（pending+gen+delta+light）也以 CONSUME_BATCH_LIMIT 封顶：
                // 防止 gen/delta/light 把单轮任务量叠回 1000 阈值。
                int remaining = limit - batch.size();
                List<Map.Entry<Long, GenEntry>> genBatch = new ArrayList<>();
                client().fillDistanceFirst(generated, genBatch, remaining);
                remaining -= genBatch.size();
                List<Map.Entry<Long, DeltaWork>> deltaBatch = new ArrayList<>();
                for (Map.Entry<Long, DeltaWork> e : pendingDeltas.entrySet()) {
                    if (deltaBatch.size() >= remaining) {
                        break;
                    }
                    deltaBatch.add(e);
                }
                remaining -= deltaBatch.size();
                if (batch.isEmpty() && genBatch.isEmpty() && deltaBatch.isEmpty()) {
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
                        .debug("consumeLoop batch={} gen={} delta={}",
                                batch.size(), genBatch.size(), deltaBatch.size());
                List<LightTask> lightTasks = new ArrayList<>();
                // 先扫描齐套队列：邻柱后到可能让等待中的柱齐套
                pumpGateReady(server, lightTasks);
                for (Map.Entry<Long, PendingEntry> e : batch) {
                    // 复合键解维：pending 键携带维度，服务端查询/引擎操作全部路由到该维度。
                    String dimension = DimensionKey.dimensionOf(e.getKey());
                    ChunkPos pos = new ChunkPos(DimensionKey.chunkXOf(e.getKey()),
                            DimensionKey.chunkZOf(e.getKey()));
                    PendingEntry pendingEntry = e.getValue();
                    long remoteHash = client().peekPendingContentHash(dimension, pos.x, pos.z);
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
                            // 已有正确光：直接 REUSE；无光/需重算：一轮光后 pack（compare 已确认）。
                            submitLightReuseOrGate(e.getKey(), existing,
                                    server.level(dimension), !needRelight, false,
                                    pendingEntry.traceOrigin() != null
                                            ? pendingEntry.traceOrigin()
                                            : traceOrigin(TraceOrigin.SHADOW_MEMORY_CACHE));
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
                    submitLightReuseOrGate(e.getKey(), injected, server.level(dimension),
                            false, false, pendingEntry.traceOrigin());
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
                                        io.github.limuqy.mc.hassium.protocol.SectionDeltaS2CPacket.changedCells(
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
                    || client().getGameDir() != null
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
                    case GATE:
                        // 占位邻柱 relight 等无队列 token 的任务：提交时无需条件移除
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
                    // S3：光照缓存计数改在 scheduleChunkLoad（accountLightAtScheduleLoad），
                    // 光屏障提交不再按 REUSE/RECOMPUTE 记账，避免双计。
                } catch (Throwable ex) {
                    abortLight(t.key, ex);
                }
            }
        }
    }

    /**
     * 屏障内是否复用 phase-1 的 {@link #nativeLightChunks} 只跑 LIGHT（**生产语义，默认 true**）。
     * <p>
     * 语义等价性：phase-1（{@link #initializeLightImmediately}）已对**同一 ChunkPos** 跑过
     * INITIALIZE，而引擎的层存储按 SectionPos 索引（不按 chunk 实例），所以屏障内再跑一遍
     * 是重复劳动，不是正确性来源。
     * <p>
     * <b>2026-09-19 解除红线</b>（原「必须从零 INITIALIZE_LIGHT + LIGHT」已作废）：
     * <ul>
     *   <li>原理：天光**播种在 LIGHT 步**——{@code propagateLightSources} 读
     *       {@code ChunkSkyLightSources} 高度图源逐列播种（源以上 15、向下衰减）再传播；
     *       INITIALIZE 只做「装空层 / 启用该柱光照数据 / retainData 记账」，**不算任何亮度值**。
     *       屋檐黑属于播种问题 → 与 INITIALIZE 是否复用无关。</li>
     *   <li>实证：2026-09-19 用户多轮手动 run 未复现屋檐黑。</li>
     *   <li>历史 2026-09-18 ⑤ 结论（复用 → 移动后屋檐柱全黑）**不再作为依据**：该实验是在
     *       {@code 9595ee9d} 去门回归与 {@code 7d335166} 光源表修复之前的树上做的，归因混杂。</li>
     * </ul>
     * {@code nativeLightChunks} 无条目（如 REUSE 柱、phase-1 未完成）时自动回落到从零
     * INITIALIZE+LIGHT。置 {@code false} 可整体退回历史语义做对照。
     * <p>
     * <b>不变量（仍钉死）</b>：{@link LightNeighborhoodGate} 齐套门不得拆；验收须含**移动中**
     * 的屋檐/洞口，冒烟 PASS 不能代替目视。
     */
    private static final boolean REUSE_PHASE1_INITIALIZE = true;

    /**
     * 影子区块光照第二阶段：跑 LIGHT（传播）。
     * <p>
     * 两阶段对齐原版生成金字塔：第一阶段（{@link #initializeLightImmediately}）在注入时
     * 立即跑 INITIALIZE_LIGHT 装空 DataLayer；本阶段等 3×3 邻域都过 INITIALIZE_LIGHT 后
     * 才跑 LIGHT——这样传播时邻柱已有可写入的空层（缺邻时引擎按 Bedrock 挡光 → 屋檐/洞口黑）。
     * <p>
     * 屏障内是否复用 phase-1 的 native chunk（省掉一遍重复 INITIALIZE）见
     * {@link #REUSE_PHASE1_INITIALIZE}；两条路径的 **LIGHT 步完全相同**，天光播种都在这里。
     */
    private static void startLightBarrier(ShadowSeedServer server,
                                          LightTask t, long deadlineMs) {
        ChunkPos barrierPos = new ChunkPos(DimensionKey.chunkXOf(t.key), DimensionKey.chunkZOf(t.key));
        String barrierDim = DimensionKey.dimensionOf(t.key);
        if (t.metric == LightMetric.REUSE_CACHE) {
            probeBarrierReuse.incrementAndGet();
        } else {
            probeBarrierRecompute.incrementAndGet();
        }
        // 【钉死】非 REUSE 且未 promote：必须入 LightNeighborhoodGate，等 3×3 INITIALIZE 再 light。
        // 2026-09-18 去门实验导致屋檐黑+无补光；用户目视回退后恢复——禁止再拆（见项目 MEMORY Rules）。
        if (t.metric != LightMetric.REUSE_CACHE
                && t.chunk != null
                && !LightNeighborhoodGate.wasPromoted(t.key)) {
            // 【顺序钉死】initializeLightImmediately 必须在 isAwaiting 短路**之前**：
            // 它是幂等的「确保 phase-1 INITIALIZE 跑过」副作用源，并负责 lightInitPassed 标记。
            // 卸载（cancelChunkWork）会同时摘掉 lightInitialized + lightInitPassed，此后重投
            // 必须靠这里重跑 INITIALIZE 来重标；若被短路挡在前面，该柱就对**所有邻柱**恒为
            // 「已注入但未过 INITIALIZE」('I') → 邻柱齐套门白等软超时降级放行。
            // 实测回归（2026-09-19 20:29 run）：(-40,-21) 于 73903 INIT done 后立刻被 unload
            // 摘标，73904 起其邻柱 (-41,-22) 一路读到 'I'，卡门 26.7s。
            if (t.level != null) {
                initializeLightImmediately(server, t.key, t.chunk, t.level);
            }
            // 【不得重复入队】enqueue 是 REPLACE 语义：会换掉 AwaitingEntry 对象，而
            // pumpGateReady 里 tryPromote 的放行是**条件移除** `awaiting.remove(key, entry)`
            // ——条目被换过即移除失败、本次 promote 静默丢弃。实测（2026-09-19 飞行）单柱
            // 30s 内被重入 149 次（稳定 5 次/s），该柱因此在等待期间永远 promote 不出去，
            // 只能等 NEIGHBORHOOD_HARD_TIMEOUT_MS，前沿柱首投延迟 +16~20s。
            // 等待中的条目由 pumpGateReady 每帧扫描（无独立事件源的设计），无需重新入队。
            if (LightNeighborhoodGate.isAwaiting(t.key)) {
                return;
            }
            LightNeighborhoodGate.enqueue(t.key, DimensionKey.dimensionOf(t.key),
                    new net.minecraft.world.level.ChunkPos(
                            DimensionKey.chunkXOf(t.key), DimensionKey.chunkZOf(t.key)),
                    new GateContext(t.token, t.chunk, t.level,
                            t.metric, t.renderOnly, t.traceOrigin));
            return;
        }
        InflightLight inf = new InflightLight(t.key, t.source, t.token,
                t.chunk, t.level, deadlineMs, t.metric, t.renderOnly, t.traceOrigin);
        inf.submittedAtNs = System.nanoTime();
        inflightLight.put(t.key, inf);
        try {
            net.minecraft.server.level.ServerLevel level = t.level != null
                    ? t.level : server.overworld();
            // 屏障内复用 phase-1 native chunk（2026-09-19 起为生产语义，见 REUSE_PHASE1_INITIALIZE）。
            // 历史 2026-09-18 ⑤ 曾判定「复用 → 移动后屋檐柱全黑」并回退；该判定已作废（归因混杂：
            // 当时树上正在做去门回归、且尚无读盘柱光源表修复）。齐套门仍钉死保留——门与本段是两回事。
            net.minecraft.world.level.chunk.ChunkAccess phase1 =
                    nativeLightChunks.remove(t.key);
            if (t.metric == LightMetric.REUSE_CACHE) {
                // REUSE 柱仍以 lightCorrect=true 建 native chunk 跑引擎两阶段（探针 B）。
                probeBarrierReuseWithEngineLight.incrementAndGet();
            }
            if (REUSE_PHASE1_INITIALIZE && phase1 != null
                    && t.metric != LightMetric.REUSE_CACHE) {
                // 生产语义（2026-09-19 起）：phase-1 已对同一 ChunkPos 跑过 INITIALIZE
                // （空层已装、section 状态已标记），屏障内只跑 LIGHT。
                // 天光播种在 LIGHT 步（propagateLightSources），不在 INITIALIZE —— 见常量 javadoc。
                probeBarrierReusedPhase1.incrementAndGet();
                DebugLogger.info(DebugLogger.LogType.LIGHT,
                        "[SHADOW_LIGHT] barrier reuse phase-1, skip INITIALIZE ({}, {}) dim={}",
                        DimensionKey.chunkXOf(t.key), DimensionKey.chunkZOf(t.key), barrierDim);
                inf.nativeChunk = phase1;
                final java.util.Map<net.minecraft.world.level.chunk.LevelChunk, Boolean> relaxed1 =
                        relaxNeighborLightFlags(inf);
                io.github.limuqy.mc.hassium.compat.ShadowServerCompat
                        .completeNativeLight(level, inf.nativeChunk)
                        .whenComplete((ignored, throwable) -> {
                            restoreNeighborLightFlags(relaxed1);
                            if (throwable != null) {
                                abortLight(t.key, throwable);
                            } else {
                                attachForeignLight(inf);
                                completeLight(inf, true);
                            }
                        });
                return;
            }
            probeInitBarrier.incrementAndGet();
            final java.util.Map<net.minecraft.world.level.chunk.LevelChunk, Boolean>[] relaxed2Box = new java.util.Map[1];
            inf.nativeChunk = io.github.limuqy.mc.hassium.compat.ShadowServerCompat
                    .createNativeLightChunk(level, t.chunk,
                            lightChunkHasExistingLight(t.metric == LightMetric.REUSE_CACHE));
            io.github.limuqy.mc.hassium.compat.ShadowServerCompat
                    .initializeNativeLight(level, inf.nativeChunk)
                    .thenRun(() -> {
                        // 本分支**确实**跑了 INITIALIZE（initializeNativeLight），故**无条件**标记：
                        // 不标记，邻柱的齐套判定就会把本柱当成「已注入但未过 INITIALIZE_LIGHT」，
                        // 白等软超时后降级放行（降级 → 非权威 → 光被扣下不落盘）。
                        // 【2026-09-19 修正】原实现只在 metric==REUSE_CACHE 时标记，靠
                        // startLightBarrier 门控分支的 initializeLightImmediately 为 RECOMPUTE
                        // 柱代标；但卸载会摘掉该标记，而代标路径可能被短路 → RECOMPUTE 柱恒为 'I'。
                        // 标记点就该在**真正跑完 INITIALIZE 的地方**，不依赖调用方是否代标。
                        lightInitPassed.add(t.key);
                    })
                    .thenCompose(ignored -> {
                        relaxed2Box[0] = relaxNeighborLightFlags(inf);
                        return io.github.limuqy.mc.hassium.compat.ShadowServerCompat
                                .completeNativeLight(level, inf.nativeChunk);
                    })
                    .whenComplete((ignored, throwable) -> {
                        restoreNeighborLightFlags(relaxed2Box[0]);
                        if (throwable != null) {
                            abortLight(t.key, throwable);
                        } else {
                            attachForeignLight(inf);
                            completeLight(inf, true);
                        }
                    });
        } catch (Throwable failure) {
            abortLight(t.key, failure);
        }
    }


    /** 该维影子端引擎是否被外部光照引擎（Starlight / ScalableLux）替换。 */
    private static boolean isForeignEngineActive(String dimension) {
        try {
            io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer server =
                    io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry.getInstance().get();
            net.minecraft.server.level.ServerLevel level = server == null ? null : server.level(dimension);
            return level != null && io.github.limuqy.mc.hassium.compat.mods.ForeignLightEngine
                    .isForeign(level.getLightEngine());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 外部光照引擎下，**LIGHT 步期间**把 3×3 邻柱临时标成 {@code isLightCorrect=true}，
     * 让 Starlight 的 {@code SkyStarLightEngine.canUseChunk} 放行它们；返回原值供还原。
     * <p>
     * <b>为什么</b>：`canUseChunk` 只接受 {@code status>=LIGHT && isLightCorrect()} 的柱，
     * {@code setupCaches} 对不满足的邻柱直接 {@code continue}（不进 cache）→ 本柱那一侧的横向光
     * 永远拿不到（屋檐 0，无 mod 为 13）。而 Hassium 的 S5 把 {@code isLightCorrect} 当**落盘判据**，
     * 只在 3×3 真齐全时置真 —— **同一个标志被两种语义共用**：Starlight 要的是「数据可用」，
     * S5 要的是「可安全落盘」。这里只为**算光期**提供前者，跑完立刻还原（不写 NBT、不改落盘判据）。
     * <p>
     * 邻柱 nibble 若确实还没算过（全 NULL），本柱照样拿不到那一侧的光——那是对的（无光可给）；
     * 一旦邻柱算过（哪怕未被标 clean），本柱就能拿到。非外部引擎为 no-op。
     */
    private static java.util.Map<net.minecraft.world.level.chunk.LevelChunk, Boolean>
            relaxNeighborLightFlags(InflightLight inf) {
        java.util.Map<net.minecraft.world.level.chunk.LevelChunk, Boolean> prev = new java.util.HashMap<>();
        try {
            if (inf.level == null || inf.chunk == null) {
                return prev;
            }
            String dimension = DimensionKey.dimensionOf(inf.key);
            if (!isForeignEngineActive(dimension)) {
                return prev;
            }
            io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer server =
                    io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry.getInstance().get();
            if (server == null) {
                return prev;
            }
            ChunkPos pos = inf.chunk.getPos();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    net.minecraft.world.level.chunk.LevelChunk n =
                            server.injectedChunk(dimension, pos.x + dx, pos.z + dz);
                    if (n == null) {
                        continue;
                    }
                    prev.put(n, n.isLightCorrect());
                    if (!n.isLightCorrect()) {
                        n.setLightCorrect(true);
                    }
                }
            }
        } catch (Throwable ignored) {
            // 诊断/兼容失败不得影响算光
        }
        return prev;
    }

    /** 还原 {@link #relaxNeighborLightFlags} 改过的标志。 */
    private static void restoreNeighborLightFlags(
            java.util.Map<net.minecraft.world.level.chunk.LevelChunk, Boolean> prev) {
        for (java.util.Map.Entry<net.minecraft.world.level.chunk.LevelChunk, Boolean> e : prev.entrySet()) {
            if (Boolean.FALSE.equals(e.getValue())) {
                e.getKey().setLightCorrect(false);
            }
        }
        prev.clear();
    }

    /**
     * 外部光照引擎（Starlight / ScalableLux）下把 LIGHT 步写在 native light chunk 上的
     * nibble 数组搬回**交付柱**（{@code inf.chunk}）。
     * <p>
     * 原版引擎把光存在引擎自己的 SectionPos 存储里，{@code getDataLayerData(sp)} 与柱实例无关，
     * 所以 {@code createNativeLightChunk} 造一次性 ProtoChunk 无所谓；Starlight 血缘把光存在
     * **柱实例自己的 {@code getSkyNibbles()}** 上，而交付包用的注入柱与 reader 看的都是关卡里那一份柱
     * → 不搬就是扔（实测 1.21.1 fabric + ScalableLux：1290/1400 包 {@code skyOmitted=26}，客户端全 15）。
     * 数组是 SWMR 对象，共享引用即可，无需深拷。非外部引擎为 no-op（反射拿不到访问器）。
     */
    private static void attachForeignLight(InflightLight inf) {
        try {
            io.github.limuqy.mc.hassium.compat.mods.ForeignLightEngine
                    .copyLightNibbles(inf.nativeChunk, inf.chunk);
        } catch (Throwable t) {
            DebugLogger.warn(DebugLogger.LogType.LIGHT,
                    "[SHADOW_LIGHT] attach foreign light failed ({}, {})",
                    inf.chunk == null ? 0 : inf.chunk.getPos().x,
                    inf.chunk == null ? 0 : inf.chunk.getPos().z);
        }
    }

    /** 原版 LIGHT future 完成后的唯一完成收口。 */
    private static boolean completeLight(InflightLight inf, boolean converged) {
        if (!inflightLight.remove(inf.key, inf)) {
            return false;
        }
        // 【#36】本会话 LIGHT 步已完成（与 I2 无关）：推送门用它，不再依赖 isLightCorrect。
        // 降级放行的柱同样在此置位——它们的光确实算过（只是缺邻，可能偏暗），必须照推。
        lightRan.add(inf.key);
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
            // 同柱已有更新的整柱投递在队：本柱不单独回传。
            // 【2026-09-19】原此处以纯光包（pushLightReady）兜底，防「客户端停在 standing 首包欠光
            //（skyTop=0）」。光桥整体删除后不再有 light-only 路径——若真出现欠光，属投递/算光侧问题，
            // 应修源头（见 docs/client-chunk-light-flow.md §7），不在交付侧补票。
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
            // 对齐原版 lightChunk POST：先记「本会话 LIGHT 完成」，再整柱打包。
            if (server != null && task.chunk != null && !task.chunk.isLightCorrect()
                    && server.hasCompleteLightLayers(pos, task.chunk)
                    && server.hasUsableEngineLight(pos, task.chunk)) {
                server.syncLightCorrect(task.chunk, true);
            }
            if (server != null && !isLightReusable(server, pos, task.chunk)) {
                DebugLogger.info(DebugLogger.LogType.LIGHT,
                        "[SHADOW_LIGHT] pack after light task (non-ideal light) ({}, {}) metric={}",
                        pos.x, pos.z, task.metric);
            }
            pushReady(task.key, task.chunk, task.level, converged, task.renderOnly,
                    task.traceOrigin);
        } catch (Throwable t) {
            DebugLogger.warn(DebugLogger.LogType.ASYNC,
                    "[SHADOW_CHUNK] Build failed ({}, {})", pos.x, pos.z);
        }
        if (server != null && task.chunk != null) {
            server.persistAfterClientLightPush(task.chunk, converged);
        }
        // 【S2c 迟到重触发】本柱降级放行 → 登记，等邻域补齐后重算重交付；
        // 干净放行 → 撤销登记（可能是重算后的第二轮）。再复查 3×3 覆盖到的登记项：
        // 本柱（刚过 INITIALIZE 或刚算完光）可能正是邻柱等的最后一块拼图。
        if (server != null) {
            boolean repaired = false;
            if (task.metric != LightMetric.REUSE_CACHE) {
                // REUSE 柱不进齐套门 → wasPromotedClean 恒假，但它的光是读盘复用来的、
                // 本来就权威（chunk.isLightCorrect() 为真）。不能拿 I2 判它，更不能登记重算。
                if (isColumnLightAuthoritative(dimension, pos)) {
                    // 【事件源】曾登记为降级、现在干净了 = 「降级被修复」（见 recheckAuthoritativeNeighbors）。
                    repaired = pendingAuthoritative.remove(task.key) != null;
                } else {
                    registerPendingAuthoritative(dimension, pos, task.renderOnly, task.traceOrigin);
                }
            }
            recheckPendingAuthoritative(server, dimension, pos);
            if (repaired) {
                recheckAuthoritativeNeighbors(server, dimension, pos);
            }
        }
        if (inflightLight.size() < PIPELINE_LOW_WATER && hasStartablePendingWork() && isEnabled()) {
            pump();
        }
    }

    /** 【S2c】登记降级放行的柱（首次登记打点；REPLACE 覆盖，保持后到者上下文）。 */
    private static void registerPendingAuthoritative(String dimension, ChunkPos pos,
                                                     boolean renderOnly, TraceOrigin traceOrigin) {
        if (dimension == null || pos == null) {
            return;
        }
        PendingAuth entry = new PendingAuth(dimension, new ChunkPos(pos.x, pos.z),
                renderOnly, traceOrigin,
                LightNeighborhoodGate.inWindowReadyNeighborCount(dimension, pos));
        if (pendingAuthoritative.put(DimensionKey.key(dimension, pos.x, pos.z), entry) == null) {
            probePendingAuthRegistered.incrementAndGet();
            DebugLogger.info(DebugLogger.LogType.LIGHT,
                    "[SHADOW_LIGHT] pending authoritative ({}, {}) dim={} pending={}",
                    pos.x, pos.z, dimension, pendingAuthoritative.size());
        }
    }

    /**
     * 【S2c】复查 {@code center} 的 3×3 覆盖到的登记项：邻域已可传播的柱 → 重新过齐套门
     * （{@link LightNeighborhoodGate#rearm} + {@code enqueue} + {@code tryPromote}）→ 干净放行
     * → LIGHT → {@link #finishLight} 再走一遍（此时 I2 为真 → 落盘 + 重交付）。
     * <p>
     * 只复查 3×3：一个登记项只可能因**自己 8 邻之一**的状态变化而变成可传播，而状态变化只发生在
     * 该邻柱的 INITIALIZE / LIGHT 完成时——那些时刻的 {@link #finishLight} 都会复查到自己头上。
     * <p>
     * 就地 promote（不靠 {@code pump} + {@code pumpGateReady}）：邻域已就绪，{@code tryPromote}
     * 必立即放行；若交给消费循环，本柱既不在 pending/generated/delta 任一队列、而
     * {@code hasStartablePendingWork()} 又不看齐套队列 → 可能等不到下一次 pump（活性缺口）。
     */
    private static void recheckPendingAuthoritative(ShadowSeedServer server,
                                                    String dimension, ChunkPos center) {
        if (server == null || dimension == null || center == null || pendingAuthoritative.isEmpty()) {
            return;
        }
        List<LightTask> tasks = null;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                long key = DimensionKey.key(dimension, center.x + dx, center.z + dz);
                PendingAuth pending = pendingAuthoritative.get(key);
                if (pending == null) {
                    continue;
                }
                net.minecraft.world.level.chunk.LevelChunk chunk = server.injectedChunk(
                        pending.dimension(), pending.pos().x, pending.pos().z);
                if (chunk == null) {
                    // 已卸载 / 尚未注入：条目作废。重新加载会走完整投递 + 算光路径。
                    pendingAuthoritative.remove(key, pending);
                    continue;
                }
                if (!LightNeighborhoodGate.isNeighborhoodLightReady(pending.dimension(), pending.pos())) {
                    continue; // 还缺邻（窗内）：现在重算必然再降级一轮，等下一次复查
                }
                // 【进展门（2026-09-19 任务 #37）】邻域**自登记以来**没有新进展就不重算。
                // isNeighborhoodLightReady 是**单调**判据（窗内邻柱 init 过就永真），而本柱若
                // 仍带窗外缺邻，重算后 promotedClean 依旧为假 → finishLight 会再次登记 → 无门
                // 即「finishLight → recheck → re-light → finishLight」自激（互触同样成立）。
                // 计数单调不减，故每柱最多因 8 个邻柱各自「新过 INITIALIZE」而重算，有界。
                if (LightNeighborhoodGate.inWindowReadyNeighborCount(
                        pending.dimension(), pending.pos()) <= pending.readyNeighbors()) {
                    continue;
                }
                if (!pendingAuthoritative.remove(key, pending)) {
                    continue;
                }
                net.minecraft.server.level.ServerLevel level = server.level(pending.dimension());
                if (level == null) {
                    continue;
                }
                // rearm 后重入齐套门：promoted 已清，tryPromote 会用当前（已齐套）邻域重算
                // promotedClean → I2 转真；屏障内 LIGHT 重跑，finishLight 落盘 + 重交付。
                LightNeighborhoodGate.rearm(key);
                LightNeighborhoodGate.enqueue(key, pending.dimension(), pending.pos(),
                        new GateContext(null, chunk, level, LightMetric.RECOMPUTE,
                                pending.renderOnly(), pending.traceOrigin()));
                Object context = LightNeighborhoodGate.tryPromote(server, key);
                if (!(context instanceof GateContext gateCtx)) {
                    // 并发 REPLACE / cancel：本轮放弃，重新登记等下次复查
                    registerPendingAuthoritative(pending.dimension(), pending.pos(),
                            pending.renderOnly(), pending.traceOrigin());
                    continue;
                }
                if (tasks == null) {
                    tasks = new ArrayList<>(4);
                }
                tasks.add(gateCtx.toLightTask(key));
                probePendingAuthRelight.incrementAndGet();
                DebugLogger.info(DebugLogger.LogType.LIGHT,
                        "[SHADOW_LIGHT] re-light after neighborhood ready ({}, {}) dim={}",
                        pending.pos().x, pending.pos().z, pending.dimension());
            }
        }
        if (tasks != null) {
            submitLightBatch(server, tasks);
        }
    }

    /**
     * 【降级修复 → 单跳通知权威邻居重投】（2026-09-19 用户拍板：epoch 判据、不加开关、
     * 不级联、3×3 不齐即弃）。
     * <p>
     * <b>为什么需要</b>：一柱能成为权威柱（{@code promotedClean}）只要求 promote 时邻柱
     * **过了 INITIALIZE**，**不要求邻柱自身 clean**。于是权威柱 A 可能是在邻柱 N 还是
     * 「降级低光」时算出来的；N 后来被 S2c 重算成干净光后，**A 不会被重算**
     * （A 不在 {@link #pendingAuthoritative}）→ A 从 N 那侧进来的光仍偏低
     * （檐下水平进光正是这种场景，handoff §0.2b 未决项）。
     * <p>
     * <b>触发面刻意收窄</b>（用户口径）：
     * <ul>
     *   <li>事件源 = **降级被修复**（{@link #finishLight} 里 {@code pendingAuthoritative.remove}
     *       命中且本柱变 clean），**不是**「任何柱完成 LIGHT」；</li>
     *   <li>**单跳、不级联**：只通知本柱 8 邻；被通知者重投完成后**天然不构成新事件**
     *       （权威柱不在 {@link #pendingAuthoritative} 里）→ 零额外标记即无传播；</li>
     *   <li>**不齐即弃**：邻居 3×3 不真齐就跳过——不登记、不等待，由「另外的缺位触发」
     *       （那个缺柱自己将来变 clean 时的事件）再通知它一次。</li>
     * </ul>
     * <b>有界性</b>：时序判据 {@code cleanAt(邻居) < 事件序号} 单调 → 每个"曾降级的邻柱"
     * 对同一 A 最多触发一次 ⟹ A 被重投 ≤ 8 次（实际按降级率远小于此）。
     */
    private static void recheckAuthoritativeNeighbors(ShadowSeedServer server,
                                                      String dimension, ChunkPos center) {
        Long eventEpoch = LightNeighborhoodGate.cleanEpochOf(
                DimensionKey.key(dimension, center.x, center.z));
        if (eventEpoch == null) {
            return; // 事件源自身没有 clean 序号（异常）：不触发
        }
        List<LightTask> tasks = null;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int nx = center.x + dx;
                int nz = center.z + dz;
                long nKey = DimensionKey.key(dimension, nx, nz);
                ChunkPos nPos = new ChunkPos(nx, nz);
                if (!isColumnLightAuthoritative(dimension, nPos)) {
                    continue; // 只对**已权威**的邻居（非权威走既有 S2c 路径）
                }
                Long at = LightNeighborhoodGate.cleanEpochOf(nKey);
                if (at == null || at >= eventEpoch) {
                    continue; // 它在本事件之后才 clean（或刚被 rearm）：已用最终光，不必重投
                }
                if (!LightNeighborhoodGate.isNeighborhoodFullyReady(dimension, nPos)) {
                    continue; // 不齐即弃：不登记、不等待（由另外的缺位触发）
                }
                net.minecraft.world.level.chunk.LevelChunk chunk =
                        server.injectedChunk(dimension, nx, nz);
                if (chunk == null) {
                    continue;
                }
                net.minecraft.server.level.ServerLevel level = server.level(dimension);
                if (level == null) {
                    continue;
                }
                LightNeighborhoodGate.rearm(nKey);
                LightNeighborhoodGate.enqueue(nKey, dimension, nPos,
                        new GateContext(null, chunk, level, LightMetric.RECOMPUTE,
                                false, TraceOrigin.SHADOW_MEMORY_CACHE));
                Object context = LightNeighborhoodGate.tryPromote(server, nKey);
                if (!(context instanceof GateContext gateCtx)) {
                    // 并发（另一线程换过条目 / cancel）：放弃本次，等下次事件
                    LightNeighborhoodGate.cancel(nKey);
                    continue;
                }
                if (tasks == null) {
                    tasks = new ArrayList<>(4);
                }
                tasks.add(gateCtx.toLightTask(nKey));
                DebugLogger.info(DebugLogger.LogType.LIGHT,
                        "[SHADOW_LIGHT] re-light authoritative neighbor after repair ({}, {}) dim={}",
                        nx, nz, dimension);
            }
        }
        if (tasks != null) {
            submitLightBatch(server, tasks);
        }
    }

    /** 区块卸载前取消该柱所有尚未完成的影子光照/回传工作。 */
    public static void cancelChunkWork(long key) {
        pending.remove(key);
        generated.remove(key);
        pendingDeltas.remove(key);
        inflightLight.remove(key);
        shadowApplyEpochs.remove(key);
        lightInitialized.remove(key);
        lightInitPassed.remove(key);
        lightRan.remove(key);
        nativeLightChunks.remove(key);
        pendingAuthoritative.remove(key);
        LightNeighborhoodGate.cancel(key);
        DebugLogger.info(DebugLogger.LogType.LIGHT,
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
        // B6：统一交付门 = ServerVD+余量；更远柱不进 ready（原版会 Ignore）
        if (!renderOnly && !standingPreview
                && !ShadowTrackingSession.isDeliverableToClient(pos.x, pos.z)) {
            DebugLogger.info(DebugLogger.LogType.LIGHT,
                    "[SHADOW_LIGHT] skip beyond view+margin ({}, {}) dim={}",
                    pos.x, pos.z, DimensionKey.dimensionOf(key));
            return;
        }
        // 【S6 推送门（原版口径；2026-09-19 任务 #36 换判据）】交付窗内 且
        // 「本会话 LIGHT 步已完成」= isLightCorrect ∪ lightRanThisSession。
        // 此处**柱在手**，直接传它的 isLightCorrect()（侧查注入表会在柱已摘表/未入表时假阴性）。
        // 只挂在本方法：B 族 `offerBuiltChunkPacket`（官方包直通）拿不到影子柱，且既有决策是
        // 「不因光未对齐丢弃官方整柱包，欠光首包由后续整柱重交付补」，不在那里加门。
        if (!io.github.limuqy.mc.hassium.shadow.track.ShadowTrackingSession
                .isPushableToClient(DimensionKey.dimensionOf(key), pos, chunk.isLightCorrect())) {
            DebugLogger.info(DebugLogger.LogType.LIGHT,
                    "[SHADOW_LIGHT] pushReady blocked lightIncomplete ({}, {}) dim={} {}",
                    pos.x, pos.z, DimensionKey.dimensionOf(key),
                    io.github.limuqy.mc.hassium.shadow.track.ShadowTrackingSession
                            .lightGateDetail(DimensionKey.dimensionOf(key), pos));
            return;
        }
        // S3：原版光——引擎产出即打包交付，无 park / NeighborhoodGate 门。
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
        // OVD（renderOnly）不进 compare 闸 —— 与 `publishCachedChunk` 的既有决策同一口径
        // （源码注释原话：「OVD renderOnly 不进本闸（无真服权威比对）」）。环带是本地源服务，
        // AWAITING 残留不得挡回填。实测 R2：`disk-serve ... pub=true` 之后 Promote 又把该柱
        // mark 成 AWAITING → 本闸把同一柱挡下（`clientHas` 恒 false、整圈光环不进客户端）。
        if (!renderOnly && io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate
                .blockClientDelivery(DimensionKey.dimensionOf(key), pos.x, pos.z)) {
            DebugLogger.info(DebugLogger.LogType.LIGHT,
                    "[SHADOW_LIGHT] offerReady blocked seedGenAwaitingCompare ({}, {})",
                    pos.x, pos.z);
            return;
        }
        io.github.limuqy.mc.hassium.utils.ChunkFlowTiming.recordReady(key);
        if (!renderOnly) {
            SmokeChunkTrace.recordShadowReady(DimensionKey.dimensionOf(key), pos);
        }
        ready.offer(new ReadyItem(packet, renderOnly, traceOrigin),
                new KeyedPriorityQueue.Key(ChunkPos.asLong(pos.x, pos.z),
                        io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher.OP_CHUNK_APPLY,
                        DimensionKey.dimensionOf(key)),
                client().chunkApplyPriority(pos.x, pos.z, fifoApplyPriority()),
                standingPreview
                        ? KeyedPriorityQueue.OfferPolicy.SKIP_IF_PRESENT
                        : KeyedPriorityQueue.OfferPolicy.REPLACE);
    }

    /**
     * 交付一个**已由影子 vanilla 管线构建好**的整柱包（B 族：官方包直通）。
     * <p>
     * <b>【2026-09-19 统一交付出口】</b>B 族原走
     * {@code mc.execute -> listener.handleLevelChunkWithLight} **直落**：不经几何门、不受客户端
     * apply 时间预算、不记 landed 指标、不参与 {@code ignoredApplyRetries} 重试上限。现改为与
     * A 族（{@code publishCachedChunk} 路径）共用同一条
     * {@code ready -> drainReady -> applyReadyChunk} 出口，从而白拿：
     * <ul>
     *   <li>B6 几何门（与 {@link #pushReady} 同一判据）</li>
     *   <li>{@code applyReadyChunk} 的维度闸复检 / {@code setApplyInProgress} /
     *       {@code ignoredApplyRetries} 重试上限 / {@code shadowApplyEpoch} 落地凭据 /
     *       landed 记账 / post-apply 探针</li>
     *   <li>{@code drainReady} 的客户端渲染线程 apply 时间预算（洪峰摊平）</li>
     * </ul>
     * <p>
     * 记账口径：整柱包由影子内存中的柱构建 → {@link TraceOrigin#SHADOW_MEMORY_CACHE}，与 A 族
     * {@code publishCachedChunk} 命中注入表时同口径。该指标本就<b>按交付次数计、允许重复</b>
     * （见 {@code cacheFullHitAccountsPerDelivery} 回归契约），故不构成重复记账。
     *
     * @param dimension 产出该包的影子端维度 id（调用方须传实际来源维；null 视为未知 → 拒绝）
     * @return true = 已入 ready 队列（异步落地）；false = 被门拦下或参数非法
     */
    public static boolean offerBuiltChunkPacket(String dimension,
                                                ClientboundLevelChunkWithLightPacket packet) {
        if (packet == null || dimension == null) {
            return false;
        }
        int x = packet.getX();
        int z = packet.getZ();
        // B6 统一交付门：与 pushReady 同一判据（ServerVD + 余量 ∪ OVD）
        if (!ShadowTrackingSession.isDeliverableToClient(x, z)) {
            DebugLogger.info(DebugLogger.LogType.LIGHT,
                    "[SHADOW_LIGHT] skip built packet beyond view+margin ({}, {}) dim={}",
                    x, z, dimension);
            return false;
        }
        if (io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate
                .blockClientDelivery(dimension, x, z)) {
            DebugLogger.info(DebugLogger.LogType.LIGHT,
                    "[SHADOW_LIGHT] built packet blocked seedGenAwaitingCompare ({}, {}) dim={}",
                    x, z, dimension);
            return false;
        }
        long key = DimensionKey.key(dimension, x, z);
        io.github.limuqy.mc.hassium.utils.ChunkFlowTiming.recordReady(key);
        SmokeChunkTrace.recordShadowReady(dimension, new ChunkPos(x, z));
        ready.offer(new ReadyItem(packet, false, TraceOrigin.SHADOW_MEMORY_CACHE),
                new KeyedPriorityQueue.Key(ChunkPos.asLong(x, z),
                        io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher.OP_CHUNK_APPLY,
                        dimension),
                client().chunkApplyPriority(x, z, fifoApplyPriority()),
                KeyedPriorityQueue.OfferPolicy.REPLACE);
        return true;
    }

    private static boolean hasClientChunk(Minecraft mc, int chunkX, int chunkZ) {        return mc != null && mc.level != null
                && ((io.github.limuqy.mc.hassium.mixin.client.ClientLevelAccessor) mc.level)
                        .hassium$getChunkSource().hasChunk(chunkX, chunkZ);
    }

    /** 诊断：真实客户端 ClientChunkCache 是否驻留该柱。 */
    public static boolean clientHasChunk(int chunkX, int chunkZ) {
        return hasClientChunk(Minecraft.getInstance(), chunkX, chunkZ);
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
     *                   已就绪整柱包。
     */
    public static void drainReady(long deadlineNs) {
        io.github.limuqy.mc.hassium.utils.ChunkFlowTiming.noteFrame(); // T0b 诊断：每帧 apply 计数
        pruneDiskReadEmpty(); // 【#2】出圈的「盘上没有」标记摘除（集合非空才做事）
        client().updateFocusFromClient();
        // 黑块判定复检（1 帧后 post-apply 真值）：光包探针即时读数是光队列 flush 前的旧值
        //（handleLightUpdatePacket 只入队），判定状态只由复检写入（handoff §7.1 假阳性）。
        client().runProbeRecheck(Minecraft.getInstance() == null
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
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc != null ? mc.getConnection() : null;
        if (connection == null) {
            ready.clear();
            ignoredApplyRetries.clear();
            logStallDrain(0, deadlineNs);
            return;
        }
        // 服务端 ACK 窗口在 100/s 平台期已满：一旦本帧 ready 空、又不再 submit()，
        // consumeLoop 不会被 pump，pending 会一直趴着直到 30s delivery timeout。
        // 只用可开工的投递唤醒：在整柱屏障后排队的工作等 finishLight 触发，
        // 不在每帧 drain 里空转扫描。
        if ((hasStartablePendingWork() || !ready.isEmpty())
                && inflightLight.size() < PIPELINE_MAX_INFLIGHT
                && isEnabled()) {
            pump();
        }
        if (ready.isEmpty()) {
            logStallDrain(0, deadlineNs);
            return;
        }
        List<KeyedPriorityQueue.Entry<ReadyItem>> deferredRetries = new ArrayList<>();
        List<KeyedPriorityQueue.Entry<ReadyItem>> deferredFarChunks = new ArrayList<>();
        boolean forceOne = true;
        int chunksAppliedThisFrame = 0;
        boolean loadingScreenVisible = ClientLoadingScreenCompat.isVisible();
        while (true) {
            KeyedPriorityQueue.Entry<ReadyItem> entry = ready.poll();
            if (entry == null) {
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
            ChunkPos chunkPos = new ChunkPos(entry.key().posLong());
            if (client().shouldDeferFarChunk(chunkPos.x, chunkPos.z, loadingScreenVisible)) {
                deferredFarChunks.add(entry);
                continue;
            }
            boolean releaseEntry = true;
            try {
                forceOne = false;
                chunksAppliedThisFrame++;
                long applyStartNs = System.nanoTime();
                releaseEntry = applyReadyChunk(mc, connection, entry, item);
                io.github.limuqy.mc.hassium.utils.ChunkFlowTiming.recordApplyWall(
                        System.nanoTime() - applyStartNs, ready.size());
            } catch (Throwable t) {
                client().logShadowChunkApplyEvent("shadow_failed",
                        chunkPos, item.renderOnly(), item.traceOrigin());
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
                client().stallSnapshot(),
                client().isJoinBoostActive(),
                client().joinBoostRemainingMs(),
                client().getBudgetNs() / 1_000_000L,
                io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher.getClientQueueSize(),
                client().isServerSeedGenEnabled());
    }

    /** @return true=本条目可 release；false=权威包被原版忽略，帧尾重入队 */
    private static boolean applyReadyChunk(Minecraft mc, ClientPacketListener connection,
                                           KeyedPriorityQueue.Entry<ReadyItem> entry, ReadyItem item) {
        int chunkX = item.chunkPacket.getX();
        int chunkZ = item.chunkPacket.getZ();
        // 维度闸（投递点复检）：publish 时已按客户端维拒过，但柱可能在 ready 队列里等到
        // 客户端切维之后才被投递——原版区块包**不带维度字段**，handleLevelChunkWithLight
        // 会把它落进「当前」level（实测：TP 进 TF 后 overworld 柱被落进暮色森林）。
        // 丢弃即可：影子虚拟玩家离开旧维度会 untrackChunk，重进时重新 track → 重新投递。
        if (clientDimensionMismatch(entry.key().dimension())) {
            DebugLogger.info(DebugLogger.LogType.CHUNK_APPLY,
                    "[SHADOW_CHUNK] drop cross-dimension chunk ({}, {}) dim={} (client switched)",
                    chunkX, chunkZ, entry.key().dimension());
            return true;
        }
        ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
        long chunkKey = DimensionKey.key(entry.key().dimension(), chunkX, chunkZ);
        client().logShadowChunkApplyEvent("shadow_attempt", chunkPos, item.renderOnly(), item.traceOrigin());
        ShadowClientApi pipeline = client();
        pipeline.setApplyInProgress(true);
        try {
            connection.handleLevelChunkWithLight(item.chunkPacket);
        } finally {
            pipeline.setApplyInProgress(false);
        }
        if (hasClientChunk(mc, chunkX, chunkZ)) {
            ignoredApplyRetries.remove(chunkKey);
            client().logShadowChunkApplyEvent("shadow_applied", chunkPos, item.renderOnly(), item.traceOrigin());
            shadowApplyEpochs.put(chunkKey, shadowApplyEpoch.incrementAndGet());
            SmokeChunkTrace.recordClientApplied(entry.key().dimension(), chunkPos);
            io.github.limuqy.mc.hassium.metrics.NetworkStats.recordChunkApplied(chunkX, chunkZ);
            accountAuthoritativeLanded(entry.key().dimension(), chunkPos, item.traceOrigin());
            client().noteChunkApplyActivity();
            // 与原版对齐：apply 后标 dirty 触发 mesh 重建。isApplyInProgress 期间
            // 原版 handler 可能跳过 dirty 标记，导致柱进缓存不进渲染队列（虚空）。
            if (mc.level != null) {
                client().markChunkSectionsDirty(mc.level, chunkX, chunkZ);
            }
            client().probeChunkState(chunkPos, mc.level, "shadow");
            // 整柱包自带光、通常无后续 LightUpdate：必须排队 post-apply 复检，
            // 否则 darkRegression 只盯 source=light，回程空光整柱在门禁里不可见。
            client().scheduleProbeRecheck(chunkPos);
            return true;
        }
        client().logShadowChunkApplyEvent("shadow_ignored", chunkPos, false, item.traceOrigin());
        if (item.renderOnly()) {
            DebugLogger.info(DebugLogger.LogType.CHUNK_APPLY,
                    "[OVD_PATH] apply-ignored renderOnly ({}, {}) nextRetryWouldBe={}",
                    chunkX, chunkZ, ignoredApplyRetries.getOrDefault(chunkKey, 0) + 1);
        }
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

    /**
     * 客户端原版 unload 钩子：**只**作废该柱的落地凭据（{@code shadowApplyEpochs}）。
     * <p>
     * 原版对齐：客户端卸载 ≠ 影子服务端卸载。影子注入表的回收只跟影子 tracking /
     * {@code RECLAIM_GRACE_MS} 硬编码宽限走；这里不得 {@code unloadChunk} 拆表，也不得登记
     * 「已请求」防抖——否则重进范围无法再交付，形成永久洞。
     * <p>
     * 【2026-09-19】原「未发 light 掩码依赖」（{@code lightFollowUps}）已随光桥删除。
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
        if (dim == null) {
            return;
        }
        long chunkKey = DimensionKey.key(dim, pos.x, pos.z);
        Long removedEpoch = shadowApplyEpochs.remove(chunkKey);
        // 黑块探针：该柱已不可见，「仍黑」统计不得再包含它（「曾亮」标记保留，回程变黑要算回归）。
        client().onProbeChunkUnloaded(pos);
        // 允许对真实服再 compare（卸载后基线可能已过期）；不挡本地 publish
        requestedMisses.remove(chunkKey);
        // 往返：本会话曾网络加载过的柱，客户端 unload 后必须允许再记缓存命中
        // （accountCacheFullHit 已改为按次记账，此处仍清网络占位以免影响新增/过期分桶）。
        if (removedEpoch != null) {
            accountedIngress.remove(chunkKey);
            networkInFlight.remove(chunkKey);
            // 清掉未完成的光/回传工作与网络来源 GenEntry：否则回程 publishCached 的
            // submitPreLight 会因「queued 是 REMOTE_PULL」被静默 return true 而不交付。
            cancelChunkWork(chunkKey);
            DebugLogger.info(DebugLogger.LogType.LIGHT,
                    "[SHADOW_LIGHT] Client unload invalidated ({}, {}) epoch={} cache-flags-cleared",
                    pos.x, pos.z, removedEpoch);
        }
    }

    /** 断连清理：清空投递/生成/回传（影子服务端由 registry 统一关停保存）。 */
    public static void onDisconnect() {
        pending.clear();
        pendingDeltas.clear();
        generated.clear();
        inflightLight.clear(); // 在途光屏障：回调侧条件移除失败即短路丢弃（断连竞态）
        ready.clear();
        lightInitialized.clear();
        lightInitPassed.clear();
        lightRan.clear();
        pendingAuthoritative.clear();
        LightNeighborhoodGate.clear();
        nativeLightChunks.clear();
        requestedMisses.clear();
        accountedIngress.clear();
        networkInFlight.clear();
        accountedLights.clear();
        resetHashClassify();
        shadowApplyEpochs.clear();
        ignoredApplyRetries.clear();
        consumeRunning.set(false);
        io.github.limuqy.mc.hassium.protocol.ShadowPullClient.reset();
    }

    /**
     * 磁盘/内存 contentHash 与远程权威比对：表命中 TRUE 直接信；表缺失或 FALSE
     * 再从活柱现算（避免脏表把整柱判成增量）。
     */
    private static boolean diskHashMatches(String dimension, LevelChunk chunk, ChunkPos pos, long remoteHash) {
        Boolean tableMatch =
                io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageHashes.matchesRemote(dimension, pos, remoteHash);
        if (tableMatch == Boolean.TRUE) {
            return true;
        }
        try {
            long diskHash = io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil
                    .combineSectionHashes(io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil
                            .computeSectionHashes(chunk));
            io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageHashes.put(dimension, pos, diskHash);
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
                + " pendingAuth=" + pendingAuthoritative.size()
                + " consume=" + consumeRunning.get();
    }

    /** 光屏障来源：决定 submitLightBatch 提交时的队列条件移除与 finishLight 回传前 REPLACE 校验方式。
     *  预览算光仅 PENDING / GENERATED（且 LightMetric.RECOMPUTE）；DELTA 跳过。 */
    private enum LightSource {
        /** 远程全量注入（{@link #submit} → pending）。预览覆盖。 */
        PENDING,
        /** 本地生成 / 磁盘命中 / relight（{@link #submitGenerated} → generated）。预览覆盖。 */
        GENERATED,
        /** 分段增量（{@link #submitDelta} → pendingDeltas）。不做预览。 */
        DELTA,
        /** 占位邻柱 relight 等无队列 token 的任务：submitLightBatch 不做条件移除。 */
        GATE
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
     * 扫描齐套队列：对每个待齐套柱尝试 {@link LightNeighborhoodGate#tryPromote}，
     * 齐套则构建 LightTask 加入本批；同时把同批「已注入但未算光」的邻柱一并提交，
     * 确保 3×3 邻域在同一消费批次内同时算光（引擎跨边界传播依赖同批 DataLayer）。
     */
    private static void pumpGateReady(ShadowSeedServer server, List<LightTask> lightTasks) {
        if (LightNeighborhoodGate.pendingCount() == 0) {
            return;
        }
        probeGatePumpCalls.incrementAndGet();
        for (long key : LightNeighborhoodGate.snapshotKeys()) {
            probeGateTryPromoteCalls.incrementAndGet();
            Object context = LightNeighborhoodGate.tryPromote(server, key);
            if (!(context instanceof GateContext gateCtx)) {
                continue;
            }
            probeGatePromoted.incrementAndGet();
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
                        probeGatePromoted.incrementAndGet();
                        lightTasks.add(neighborGateCtx.toLightTask(nKey));
                    }
                }
            }
        }
    }

    /** 光照统计口径：REUSE_CACHE = 命中点已记 shadow reuse（跳过预览）；
     *  RECOMPUTE = 光屏障完成后记 miss + 重算耗时（PENDING/GENERATED 提交隔离预览）。 */
    public enum LightMetric {
        REUSE_CACHE,
        RECOMPUTE
    }

    private static class LightTask {
        final long key;
        final LightSource source;
        /** PENDING: 提交的 packet；GENERATED: 提交的 GenEntry；DELTA: null。 */
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

    /**
     * 【S2c】降级放行的柱：重算时重建 LightTask 所需的上下文（chunk 每次现查，不缓存实例）。
     * <p>
     * {@code readyNeighbors} = **登记时刻**「窗内且已过 INITIALIZE_LIGHT」的邻柱数
     * （{@link LightNeighborhoodGate#inWindowReadyNeighborCount}）。重算**进展门**：
     * 只有该计数比登记时增加才重算——否则重算必然得到同一结果（见
     * {@link LightNeighborhoodGate#isNeighborhoodLightReady} 的 churn 说明）。
     */
    private record PendingAuth(String dimension, ChunkPos pos,
                               boolean renderOnly, TraceOrigin traceOrigin,
                               int readyNeighbors) {}

    /** lightReuse=true：存档/引擎光可复用（lightChunk 第二参 true）；false：LIGHT 续算播种+传播。 */
    private record GenEntry(net.minecraft.world.level.chunk.LevelChunk chunk,
                            net.minecraft.server.level.ServerLevel level,
                            boolean lightReuse,
                            boolean renderOnly,
                            TraceOrigin traceOrigin) {}
}



