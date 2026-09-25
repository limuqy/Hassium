package io.github.limuqy.mc.hassium.protocol;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compression.ZstdRuntimeBridge;
import net.minecraft.network.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ZSTD 字典生命周期管理器
 * <p>
 * 支持两种字典：
 * 1. 区块字典（静态）：用区块数据训练，所有用户通用，内置或预训练
 * 2. 聚合包字典（动态）：运行时采样训练，因 mod 组合而异
 * <p>
 * 聚合字典状态是<b>带版本的快照</b>（{@link DictionarySnapshot}：epoch + 内容 hash），
 * 不再是裸 {@code byte[]}：
 * <ul>
 *   <li>服务端编码只读 {@link #activeAggregation}（全局唯一激活版）；</li>
 *   <li>热更走 rollout：训练完成 → 落盘 → offer 推送（不动激活版）→ 逐连接
 *       {@code aggregation_ready(epoch,id)} ACK → <b>全部活跃连接确认后</b>才切换激活版；
 *       超时/存在旧协议连接则放弃本次 offer（保持旧版，绝不靠 sleep 赌时序）；</li>
 *   <li>客户端按 epoch 安装/查找（激活版 + 保留历史，合计 ≤5 份，超出挤出最旧），
 *       切换窗口内的在途旧帧仍可解码。</li>
 * </ul>
 * 参考 NEB 的 DictionaryManager：采样收集、异步训练、持久化、分发。
 */
public class DictionaryManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/Dictionary");

    /**
     * 训练所需样本数量（首训运行时采样与每日重训练共用；从 2000 翻倍至 4000）
     */
    private static final int SAMPLE_THRESHOLD = 4000;

    /**
     * 字典大小（64KB）
     */
    private static final int DICT_SIZE = 64 * 1024;

    /**
     * 单个样本最大大小（16KB）
     */
    private static final int MAX_SAMPLE_SIZE = 16 * 1024;

    /**
     * 样本总字节数上限（64MB；与样本阈值同步翻倍，保持单样本 16KB 量级的预算不变）
     */
    private static final long MAX_SAMPLE_BYTES = 64L * 1024 * 1024;

    /**
     * 语料目标轮换周期（秒）：每分钟随机取一个活跃玩家，捕获其下一帧聚合包落盘
     */
    private static final int CORPUS_ROTATE_SECONDS = 60;

    /**
     * 重训练触发检查周期（秒）：按<b>服务器日历天</b>触发，每天最多尝试一次
     * （marker 只负责跨重启的「今天是否已训」种子，不承担周期计算）
     */
    private static final int RETRAIN_CHECK_SECONDS = 60;

    /**
     * 每日重训练的最低数据集：总字节与样本数双门槛。总字节不足训不出有意义的字典；
     * 样本数下限来自 zstd 训练器自身的约束（fastCover 对样本数有下限，6 个大样本
     * 也会被拒——见 {@code DictTrainerMinimumCorpusTest} 经验边界）。不达标直接拒绝
     * 本次训练（保持现行字典），下一天或冒烟触发路径再试。
     */
    private static final long MIN_RETRAIN_TOTAL_BYTES = 256L * 1024;
    private static final int MIN_RETRAIN_SAMPLES = 32;

    /**
     * 字典保留上限（含激活版）：解码按帧头 epoch 在这份数里查找；超出挤出最旧。
     * 覆盖多轮热更并发的在途旧帧场景，同时封住内存上界（5 × 64KB）
     */
    private static final int MAX_RETAINED_SNAPSHOTS = 5;

    // ===== 冒烟模式（hassium.serverSmokeScenario=dictionary 时注入，仅 dev 冒烟） =====

    /**
     * 冒烟语料攒批落盘阈值：帧进内存攒批合并成单个语料文件，几分钟窗口内凑够
     * 最低数据集（正常路径每分钟一帧，3 分钟凑不齐 256KB）。
     * 8KB 档位使 256KB 语料 ≈ 33 个样本，高于 zstd 训练器的样本数下限
     * （{@code DictTrainerMinimumCorpusTest} 钉死）。
     */
    private static final int SMOKE_BATCH_FLUSH_BYTES = 8 * 1024;

    /** 冒烟重训练触发检查周期（秒）：语料达标即主动触发一次「重训练 → ACK → 切换」全链 */
    private static final int SMOKE_RETRAIN_CHECK_SECONDS = 5;

    /**
     * 冒烟模式开关（dictionary 场景专用）：语料攒批高频采集 + 达标主动触发重训练。
     * 仅影响采集节奏与触发时机，rollout 门控（全员 ACK 才切换）不变。
     */
    private static final boolean SMOKE_MODE =
            "dictionary".equals(System.getProperty("hassium.serverSmokeScenario", ""));

    /**
     * offer 后等待全连接字典 ACK 的超时（秒）。超时放弃本次版本（保持旧激活版），
     * 由后续触发点（epoch 感知客户端激活 / 阻断连接离开）重试。
     */
    private static final int OFFER_ACK_TIMEOUT_SECONDS = 10;

    /**
     * 聚合包字典持久化路径（2.0.X 冻结兼容面：
     * {@code <serverRunDirectory>/config/<modId>/hassium_aggregation_dict.bin}）。
     * <p>
     * 由 {@link #init(Path)} 以 server run directory 锚定；未初始化时为 {@code null}，
     * 读写点空值降级为 warn + return。{@link #resetServerSession()} 清空后允许
     * 同 JVM 内的下一个服务端实例重新锚定。
     */
    private static volatile Path aggregationDictPath = null;

    /**
     * 区块字典（静态，所有用户通用）
     */
    private static volatile byte[] chunkDict;

    /**
     * 当前激活的聚合字典快照（服务端编码 / 客户端「最新版」解码均读此值）
     */
    private static volatile DictionarySnapshot activeAggregation;

    /**
     * 历史字典快照（新→旧，<b>不含</b>激活版）：客户端解码按帧头 epoch 在
     * 「激活版 + 本列表」中查找，热切换窗口内的在途旧帧不误用新字典。
     * 连激活版共 ≤ {@link #MAX_RETAINED_SNAPSHOTS} 份，超出挤出最旧（CopyOnWrite：
     * 写入只在字典切换时发生，读是每 DICT 帧热路径）。
     */
    private static final java.util.concurrent.CopyOnWriteArrayList<DictionarySnapshot> retainedSnapshots =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * 服务端 rollout：已推送、等待全连接 ACK 的候选字典（null = 无进行中的 offer）
     */
    private static volatile DictionarySnapshot pendingOffer;

    /**
     * 最近一次训练产出（含已激活 / 已放弃的版本）；{@link #maybeOfferDictionary()} 的候选来源
     */
    private static volatile DictionarySnapshot lastTrained;

    /**
     * 客户端：服务端是否在 DICT 聚合帧头写 epoch（由 dictionary_sync 是否携带扩展字段判定）。
     * 决定 {@link HassiumAggregationPacket#decode} 是否读取 epoch 变长字段。
     */
    private static volatile boolean serverEpochAware = false;

    /**
     * 是否是服务端
     */
    private static volatile boolean serverSide = false;

    /**
     * 服务端：epoch 计数器（单次服务端进程内从 1 单调递增）
     */
    private static final AtomicInteger nextEpoch = new AtomicInteger(1);

    /**
     * 服务端 rollout：已对当前 pendingOffer 回过字典 ACK 的连接（弱引用，断连自动清）
     */
    private static final Set<Connection> OFFER_ACKS =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /**
     * 训练是否曾完成（含从磁盘加载）。防止「offer 被放弃后 active 仍为 null →
     * isSampling() 又为真 → 重新采样 → 再训练」的死循环。
     */
    private static final AtomicBoolean trainedAtLeastOnce = new AtomicBoolean(false);

    /**
     * 字典推送回调接口（由平台层设置；携带完整快照，loader 侧编码 dictionary_sync body）
     */
    public interface DictionaryPushCallback {
        void pushToAllClients(DictionarySnapshot snapshot);
    }

    /**
     * 字典推送回调（由平台层设置）
     */
    private static volatile DictionaryPushCallback pushCallback;

    /**
     * 训练样本（仅用于聚合包字典）
     */
    private static final List<byte[]> samples = new ArrayList<>();

    /**
     * 样本总字节数
     */
    private static int totalSampleBytes = 0;

    /**
     * 是否正在训练
     */
    private static final AtomicBoolean training = new AtomicBoolean(false);

    /**
     * rollout 超时定时器（懒建；单线程守护）。复用为语料轮换 / 每日重训练 / 语料落盘
     * 的执行器（低频小任务；重训练本体经 ForkJoin 异步，不占调度线程）
     */
    private static volatile ScheduledExecutorService offerTimer;

    // ===== 字典更新机制：语料采集（存在字典后）+ 每日重训练（走 ACK 门控切换） =====

    /** 语料库（init 时随字典路径锚定；resetServerSession 清空） */
    private static volatile DictionaryCorpus corpusCollector;

    /** 本分钟语料采集目标（每分钟随机轮换一个活跃连接；null = 本分钟不采集） */
    private static volatile Connection corpusTarget;

    /** 语料目标代次（每次轮换递增；每代最多捕获一帧） */
    private static volatile long corpusTargetGen;

    /** 已捕获帧的代次 */
    private static volatile long corpusConsumedGen = -1;

    private static volatile ScheduledFuture<?> corpusRotateHandle;
    private static volatile ScheduledFuture<?> retrainHandle;
    private static volatile ScheduledFuture<?> smokeRetrainHandle;

    /**
     * 每日重训练「当天已尝试」标记（本地日历天 epoch day）。init 时由 marker 种子
     * （marker = 最近一次训练完成时刻，跨重启对齐「今天是否已训」）；每次尝试立即置位，
     * 无论是否产出——保证每天最多触发一次。冒烟触发路径绕过本门（显式验证用）。
     */
    private static volatile long lastAttemptDay = -1;

    /**
     * 初始化字典管理器（服务端激活玩家时调用；幂等）。
     * <p>
     * 以 server run directory 锚定聚合包字典落盘路径
     * （2.0.X 冻结兼容面：{@code <serverRunDirectory>/config/<modId>/hassium_aggregation_dict.bin}）。
     * 首个非 null 路径生效；null 调用仅 warn 跳过，不改写已锚定路径。
     *
     * @param serverRunDirectory 服务器运行目录（可为 null：脱离世界/断连窗口）
     */
    public static void init(Path serverRunDirectory) {
        if (aggregationDictPath != null) {
            // 首个成功激活的玩家决定路径；后续玩家重复激活不重载，避免并发窗口冲掉已锚定路径
            return;
        }
        if (serverRunDirectory == null) {
            // 玩家脱离世界/断连窗口取不到 server；不得清空已有 path（未初始化时保持 null，读写点已有降级）
            LOGGER.warn("Aggregation dictionary init skipped: server run directory unavailable");
            return;
        }
        aggregationDictPath = serverRunDirectory
                .resolve("config")
                .resolve(Constants.MOD_ID)
                .resolve("hassium_aggregation_dict.bin");
        serverSide = true;
        loadAggregationDictionary();
        startCorpusMaintenance();
    }

    /**
     * 启动语料维护（每分钟轮换采集目标 + 每日重训练调度）。
     * 幂等：先取消上一会话的调度（同 JVM 内服务端重启场景）。
     */
    private static void startCorpusMaintenance() {
        ScheduledFuture<?> prevRotate = corpusRotateHandle;
        if (prevRotate != null) {
            prevRotate.cancel(false);
        }
        ScheduledFuture<?> prevRetrain = retrainHandle;
        if (prevRetrain != null) {
            prevRetrain.cancel(false);
        }
        corpusCollector = new DictionaryCorpus(
                aggregationDictPath.getParent().resolve("aggregation_corpus"));
        corpusRotateHandle = offerTimer().scheduleWithFixedDelay(
                DictionaryManager::rotateCorpusTarget,
                CORPUS_ROTATE_SECONDS, CORPUS_ROTATE_SECONDS, TimeUnit.SECONDS);
        // 每天最多尝试一次：按本地日历天判定；marker（最近训练完成时刻）只作「今天是否已训」
        // 的跨重启种子。首次检查在启动后一个检查周期，全天任意时刻重试都是同一天最多一次
        long lastTrain = corpusCollector.readMarker();
        lastAttemptDay = lastTrain <= 0L ? -1L
                : java.time.Instant.ofEpochMilli(lastTrain).atZone(java.time.ZoneId.systemDefault())
                        .toLocalDate().toEpochDay();
        retrainHandle = offerTimer().scheduleWithFixedDelay(
                DictionaryManager::retrainIfDue, RETRAIN_CHECK_SECONDS, RETRAIN_CHECK_SECONDS, TimeUnit.SECONDS);
        if (SMOKE_MODE) {
            // 冒烟：语料攒批高频采集 + 达标主动触发一次重训练（验证「重训练 → ACK → 切换」全链）
            smokeRetrainHandle = offerTimer().scheduleWithFixedDelay(
                    DictionaryManager::smokeRetrainCheck,
                    SMOKE_RETRAIN_CHECK_SECONDS, SMOKE_RETRAIN_CHECK_SECONDS, TimeUnit.SECONDS);
            LOGGER.info("Aggregation corpus maintenance started (SMOKE mode: batch capture, retrain check every {}s)",
                    SMOKE_RETRAIN_CHECK_SECONDS);
        } else {
            LOGGER.info("Aggregation corpus maintenance started (rotate={}s, retrain check={}s)",
                    CORPUS_ROTATE_SECONDS, RETRAIN_CHECK_SECONDS);
        }
    }

    /**
     * 加载区块字典（静态，内置在 mod 中）
     * <p>
     * 区块字典打包在 mod 的 resources/assets/hassium/hassium-dictionary.bin 中，
     * 客户端和服务端都有，不需要通过网络传输。
     */
    public static void loadChunkDictionary() {
        try (var stream = DictionaryManager.class.getResourceAsStream("/assets/hassium/hassium-dictionary.bin")) {
            if (stream != null) {
                chunkDict = stream.readAllBytes();
                LOGGER.info("Loaded built-in chunk dictionary ({} bytes)", chunkDict.length);
            } else {
                LOGGER.info("No built-in chunk dictionary found at /assets/hassium/hassium-dictionary.bin");
            }
        } catch (IOException e) {
            LOGGER.error("Failed to load chunk dictionary", e);
        }
    }

    /**
     * 加载聚合包字典（动态训练；启动时从磁盘恢复为 epoch 1 激活快照）
     */
    private static void loadAggregationDictionary() {
        Path path = aggregationDictPath;
        if (path == null) {
            LOGGER.warn("Aggregation dictionary path not initialized, skip loading from disk");
            return;
        }
        try {
            if (Files.exists(path)) {
                byte[] dict = Files.readAllBytes(path);
                if (dict.length == 0) {
                    LOGGER.warn("Aggregation dictionary file on disk is empty, ignoring (will retrain from samples)");
                    return;
                }
                // epoch 仅在单次服务端进程内有意义；epoch 1 留给磁盘恢复版，训练新版从 2 递增
                DictionarySnapshot snapshot = DictionarySnapshot.of(nextEpoch.getAndIncrement(), dict);
                activeAggregation = snapshot;
                lastTrained = snapshot;
                trainedAtLeastOnce.set(true);
                LOGGER.info("Loaded trained aggregation dictionary from disk ({} bytes, epoch={}, id={})",
                        dict.length, snapshot.epoch(), snapshot.id());
            } else {
                LOGGER.info("No trained aggregation dictionary found, will train from samples");
            }
        } catch (IOException e) {
            LOGGER.error("Failed to load aggregation dictionary from disk", e);
        }
    }

    /**
     * 获取区块字典
     *
     * @return 区块字典数据，如果没有返回 null
     */
    public static byte[] getChunkDict() {
        return chunkDict;
    }

    /**
     * 获取聚合包字典（激活版字节；兼容旧读点）
     *
     * @return 聚合包字典数据，如果没有返回 null
     */
    public static byte[] getAggregationDict() {
        DictionarySnapshot snapshot = activeAggregation;
        return snapshot != null ? snapshot.data() : null;
    }

    /**
     * 获取当前激活的聚合字典快照（服务端编码 / 客户端 ACK 回填用）
     */
    public static DictionarySnapshot getActiveSnapshot() {
        return activeAggregation;
    }

    /**
     * 按 epoch 查找可解码字典（激活版 + 保留历史，共 ≤5 份）。
     * <p>
     * 热切换窗口内服务端可能仍在发送旧 epoch 帧（切换前已入队），客户端凭帧头 epoch
     * 选字典解压；全部查不到时返回 null（调用方拒绝该帧）。
     */
    public static byte[] getAggregationDictForEpoch(int epoch) {
        DictionarySnapshot current = activeAggregation;
        if (current != null && current.epoch() == epoch) {
            return current.data();
        }
        for (DictionarySnapshot retained : retainedSnapshots) {
            if (retained.epoch() == epoch) {
                return retained.data();
            }
        }
        return null;
    }

    /**
     * 客户端：登记服务端下发的聚合字典快照（hash 校验通过后调用）。
     * <p>
     * 同 epoch 同 id 重复下发幂等跳过（激活重同步 / 热推重发）；新版本安装时把当前快照
     * 降级进保留列表（新→旧 ≤{@link #MAX_RETAINED_SNAPSHOTS} 份，超出挤出最旧），
     * 在途旧帧仍可按 epoch 解码。空快照 = 服务端明确告知无字典（换服场景），清空全部状态。
     */
    public static void installAggregationSnapshot(DictionarySnapshot snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            activeAggregation = null;
            retainedSnapshots.clear();
            LOGGER.debug("Aggregation dictionary cleared (empty sync)");
            return;
        }
        DictionarySnapshot current = activeAggregation;
        if (current != null && current.matches(snapshot.epoch(), snapshot.id())) {
            return;
        }
        if (current != null) {
            retainSnapshot(current);
        }
        activeAggregation = snapshot;
        LOGGER.debug("Aggregation dictionary installed (epoch={}, id={}, {} bytes, retained={})",
                snapshot.epoch(), snapshot.id(), snapshot.data().length, retainedSnapshots.size());
    }

    /** 历史快照入列（新→旧）；保留列表上限 = 总上限 − 激活版，超出挤出最旧。 */
    private static void retainSnapshot(DictionarySnapshot snapshot) {
        while (retainedSnapshots.size() >= MAX_RETAINED_SNAPSHOTS - 1) {
            retainedSnapshots.remove(retainedSnapshots.size() - 1);
        }
        retainedSnapshots.add(0, snapshot);
    }

    /**
     * 客户端：登记服务端是否在 DICT 帧头写 epoch（dictionary_sync 携带扩展字段 ⇒ 携带）。
     * 断连复位（{@link #resetClientSession()}）与换服（旧格式 sync 显式置 false）都会改写。
     */
    public static void markServerEpochAware(boolean value) {
        serverEpochAware = value;
    }

    /** 客户端：服务端 DICT 帧头是否带 epoch（解码侧读帧头的依据）。 */
    public static boolean isServerEpochAware() {
        return serverEpochAware;
    }

    /**
     * 设置字典推送回调
     *
     * @param callback 回调实现
     */
    public static void setPushCallback(DictionaryPushCallback callback) {
        pushCallback = callback;
    }

    // ===== 服务端：字典热更 rollout（offer → 逐连接 ACK → 全员确认后切换） =====

    /**
     * 训练完成入口（替代旧「push + sleep(100) + 全局赋值」）：登记候选并尝试发起 offer。
     * 激活版在此处<b>不</b>变——切换只发生在全连接 ACK 之后（{@link #activateSnapshot}）。
     * 同时写重训练 marker（「最近一次字典训练完成时刻」是每日重训练周期的基线，
     * 首训与每日重训共用本入口，跨重启按 marker 对齐下一次重训时刻）。
     */
    private static void offerTrainedDictionary(byte[] dict) {
        lastTrained = DictionarySnapshot.of(nextEpoch.getAndIncrement(), dict);
        DictionaryCorpus collector = corpusCollector;
        if (collector != null) {
            collector.writeMarker(System.currentTimeMillis());
        }
        maybeOfferDictionary();
    }

    /**
     * 有待激活候选且可安全切换时发起 offer：
     * <ul>
     *   <li>无活跃聚合连接：直接切换（无人需要门控；后续激活经 dictionary_sync 拿到新版）；</li>
     *   <li>全部活跃连接都已协商 epoch 帧（新协议客户端）：推送 offer，等逐连接 ACK；</li>
     *   <li>存在旧协议活跃连接（aggregation_ready 无字典字段，无法 ACK）：不发 offer——
     *   切换会让其在途帧按旧字典误解析；待其断开后由 {@link #onConnectionGone} 重试。</li>
     * </ul>
     */
    private static synchronized void maybeOfferDictionary() {
        if (!serverSide) {
            return;
        }
        DictionarySnapshot candidate = lastTrained;
        if (candidate == null || candidate.isEmpty() || candidate == pendingOffer || candidate == activeAggregation) {
            return;
        }
        List<Connection> actives = HassiumConnectionRegistry.activeConnections();
        if (actives.isEmpty()) {
            activateSnapshot(candidate, "no active aggregation connections");
            return;
        }
        if (hasLegacyActive(actives)) {
            LOGGER.debug("Aggregation dictionary update deferred: legacy (pre-epoch) connections active");
            return;
        }
        pendingOffer = candidate;
        OFFER_ACKS.clear();
        pushDictionaryToClients(candidate);
        LOGGER.info("Aggregation dictionary offer sent (epoch={}, id={}, {} bytes; waiting for {} connection ACKs)",
                candidate.epoch(), candidate.id(), candidate.data().length, actives.size());
        offerTimer().schedule(() -> onOfferTimeout(candidate), OFFER_ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** 活跃连接中是否存在旧协议（未协商 epoch 帧）连接。 */
    private static boolean hasLegacyActive(List<Connection> actives) {
        for (Connection connection : actives) {
            if (!HassiumConnectionRegistry.isEpochAware(connection)) {
                return true;
            }
        }
        return false;
    }

    private static ScheduledExecutorService offerTimer() {
        ScheduledExecutorService timer = offerTimer;
        if (timer == null) {
            synchronized (DictionaryManager.class) {
                timer = offerTimer;
                if (timer == null) {
                    timer = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "Hassium-DictRollout");
                        t.setDaemon(true);
                        return t;
                    });
                    offerTimer = timer;
                }
            }
        }
        return timer;
    }

    private static synchronized void onOfferTimeout(DictionarySnapshot offered) {
        if (pendingOffer != offered) {
            return;
        }
        DictionarySnapshot active = activeAggregation;
        pendingOffer = null;
        OFFER_ACKS.clear();
        LOGGER.warn("Aggregation dictionary offer timed out after {}s (epoch={}); keeping epoch={} active; "
                        + "will retry when an epoch-aware client (re)activates or a blocking connection leaves",
                OFFER_ACK_TIMEOUT_SECONDS, offered.epoch(), active != null ? active.epoch() : -1);
    }

    /** 字典回执 (epoch,id) 的归类：rollout 计数与初始激活校验共用的单一判据。 */
    public enum AckKind {
        /** 匹配进行中的 pendingOffer（热更候选）。 */
        OFFER,
        /** 匹配当前激活版。 */
        ACTIVE,
        /** 两者都不匹配（同步丢失 / hash 拒装 / 跨服残留）。 */
        NONE
    }

    /**
     * 分类一个字典回执 (epoch, id)。
     * <p>
     * 初始激活校验与 rollout 计数<b>共用本判据</b>（review P2：两路 ACK 的校验基准
     * 不再各自为政——初始激活接受激活版或 pendingOffer 快照，客户端在 offer 窗口内
     * 入服并装了候选版时不再触发无谓的字典回退/重发）。
     */
    public static AckKind classifyDictionaryAck(int epoch, long id) {
        DictionarySnapshot offer = pendingOffer;
        if (offer != null && offer.matches(epoch, id)) {
            return AckKind.OFFER;
        }
        DictionarySnapshot active = activeAggregation;
        if (active != null && active.matches(epoch, id)) {
            return AckKind.ACTIVE;
        }
        // 双方都无字典（服务端未首训 + 客户端未安装）：激活校验视为一致
        if (active == null && epoch == 0 && id == 0) {
            return AckKind.ACTIVE;
        }
        return AckKind.NONE;
    }

    /**
     * 收到客户端字典 ACK（aggregation_ready 携带 epoch+id；rollout 计数入口）。
     * <p>
     * 匹配 pendingOffer → 计入门控并尝试切换；匹配激活版且窗口内有 offer → 对全体
     * 连接补发（激活期只装了激活版，push 竞态遗漏 / join 晚于 push 时由此补齐）；
     * 其余（{@link AckKind#NONE}）交由激活校验与 offer 超时兜底。
     */
    public static synchronized void onDictionaryAck(Connection connection, int epoch, long id) {
        AckKind kind = classifyDictionaryAck(epoch, id);
        if (kind == AckKind.OFFER) {
            OFFER_ACKS.add(connection);
            evaluateFlip();
            return;
        }
        if (kind == AckKind.ACTIVE && pendingOffer != null) {
            LOGGER.debug("Aggregation dictionary: re-offering pending epoch {} to a freshly activated connection",
                    pendingOffer.epoch());
            pushDictionaryToClients(pendingOffer);
        }
    }

    /**
     * 连接生命周期事件：完成初次激活并成为 epoch-aware（{@code ServerHandshakeActivation}
     * 在激活收尾时调用）。
     * <p>
     * 若存在搁置的候选字典（offer 超时放弃 / legacy 阻断期间入服的新 epoch-aware 连接），
     * 由此重试 offer——修复「超时后已有连接保持在线、无断开事件、新连接加入也无人重发」
     * 的恢复缺口（review P1）。
     */
    public static synchronized void onConnectionActivated(Connection connection) {
        maybeOfferDictionary();
    }

    /** 全部活跃连接都已 ACK pendingOffer 时切换激活版（断连连接已离开活跃集，不再阻断）。 */
    private static void evaluateFlip() {
        DictionarySnapshot offer = pendingOffer;
        if (offer == null) {
            return;
        }
        List<Connection> actives = HassiumConnectionRegistry.activeConnections();
        for (Connection connection : actives) {
            if (!OFFER_ACKS.contains(connection)) {
                return;
            }
        }
        activateSnapshot(offer, "all " + actives.size() + " active connection(s) acknowledged");
    }

    /**
     * 真正切换全局编码字典。旧激活版降级进保留列表（新→旧 ≤5 份，超出挤出最旧；
     * 客户端按帧头 epoch 在保留集中解码在途旧帧）。
     */
    private static void activateSnapshot(DictionarySnapshot snapshot, String reason) {
        DictionarySnapshot old = activeAggregation;
        if (old != null) {
            retainSnapshot(old);
        }
        activeAggregation = snapshot;
        pendingOffer = null;
        OFFER_ACKS.clear();
        LOGGER.info("Aggregation dictionary activated (epoch={}, id={}, {} bytes; retained={}; {})",
                snapshot.epoch(), snapshot.id(), snapshot.data().length,
                retainedSnapshots.size(), reason);
    }

    /**
     * 连接断开：解除其 offer ACK 计数并重估切换；offer 未进行时重试挂起的候选
     * （典型：唯一阻断切换的旧协议连接退场）。
     */
    public static synchronized void onConnectionGone(Connection connection) {
        if (OFFER_ACKS.remove(connection)) {
            evaluateFlip();
            return;
        }
        if (pendingOffer != null) {
            // offer 进行中：断连可能移除了最后一个未确认的阻断者
            evaluateFlip();
            return;
        }
        maybeOfferDictionary();
    }

    // ===== 字典更新机制：语料采集（存在字典后）+ 每日重训练（走 ACK 门控切换） =====

    /**
     * 每分钟轮换语料采集目标：随机挑一个活跃连接（无活跃连接则本分钟不采集）。
     * 目标代次随轮换递增——「每代最多捕获一帧」的判据。
     */
    private static void rotateCorpusTarget() {
        if (!serverSide) {
            return;
        }
        List<Connection> actives = HassiumConnectionRegistry.activeConnections();
        if (actives.isEmpty()) {
            corpusTarget = null;
            return;
        }
        corpusTarget = actives.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(actives.size()));
        corpusTargetGen++;
        Constants.LOG.debug("Hassium: corpus sampling target rotated (gen={})", corpusTargetGen);
    }

    /**
     * 聚合冲刷热路径钩子（每帧调用；仅两三次 volatile 读的开销）：
     * 当前连接是本分钟语料目标且本代未捕获时，把该帧压缩前原始字节异步落盘。
     * <p>
     * 采集条件：存在激活字典（首训前的采样走 {@code collectSample}，不与本路径重叠）、
     * 帧非空且在 {@link DictionaryCorpus#MAX_FRAME_BYTES} 界内；写盘经 offerTimer
     * 异步执行，冲刷线程零 IO。
     */
    public static void collectCorpusSample(Connection connection, byte[] rawFrame) {
        DictionaryCorpus collector = corpusCollector;
        if (collector == null || collector.isDisabled() || !serverSide || activeAggregation == null) {
            return;
        }
        if (rawFrame == null || rawFrame.length == 0 || rawFrame.length > DictionaryCorpus.MAX_FRAME_BYTES) {
            return;
        }
        if (SMOKE_MODE) {
            // 冒烟：攒批合并成少量大语料文件，尽快凑够最低数据集（验证更新全链）
            byte[] blob = collector.appendBatch(rawFrame.clone(), SMOKE_BATCH_FLUSH_BYTES);
            if (blob != null) {
                try {
                    offerTimer().execute(() -> collector.writeSample(blob));
                } catch (Exception ignored) {
                    // 调度器拒绝（关停窗口）：语料是尽力而为
                }
            }
            return;
        }
        if (connection == null || corpusTarget != connection || corpusConsumedGen == corpusTargetGen) {
            return;
        }
        corpusConsumedGen = corpusTargetGen;
        byte[] copy = rawFrame.clone();
        try {
            offerTimer().execute(() -> collector.writeSample(copy));
        } catch (Exception e) {
            // 调度器拒绝（关停窗口）：语料是尽力而为，忽略
        }
    }

    /**
     * 每日重训练触发检查（固定周期）：按服务器<b>日历天</b>判定，每天最多尝试一次。
     * 尝试即置位当天标记（无论语料是否达标——达标与否由 {@link #dailyRetrain} 内的
     * 最低数据集校验决定，拒绝则保持现行字典到下一天）。
     */
    private static void retrainIfDue() {
        if (!serverSide) {
            return;
        }
        long today = java.time.LocalDate.now().toEpochDay();
        if (lastAttemptDay == today) {
            return;
        }
        lastAttemptDay = today;
        LOGGER.info("Aggregation dictionary daily retrain due (first attempt today)");
        CompletableFuture.runAsync(DictionaryManager::dailyRetrain);
    }

    /**
     * 冒烟触发（dictionary 场景专用，每 5s 检查一次）：语料达到最低数据集即主动触发
     * 一次「重训练 → offer → ACK → 切换」全链（绕过每日门，仅冒烟验证用）。
     * <b>必须在首训激活之后</b>（activeAggregation 非空）：否则残留语料会让重训练
     * 抢占首训，产物成为 epoch 1，更新链路（epoch 2）无从验证。
     */
    private static void smokeRetrainCheck() {
        if (!serverSide || activeAggregation == null) {
            return;
        }
        DictionaryCorpus collector = corpusCollector;
        if (collector == null
                || collector.totalBytes() < MIN_RETRAIN_TOTAL_BYTES
                || collector.sampleCount() < MIN_RETRAIN_SAMPLES) {
            return;
        }
        ScheduledFuture<?> self = smokeRetrainHandle;
        if (self != null) {
            self.cancel(false);
            smokeRetrainHandle = null;
        }
        LOGGER.info("Aggregation dictionary SMOKE retrain trigger: corpus ready ({} bytes), firing retrain",
                collector.totalBytes());
        CompletableFuture.runAsync(DictionaryManager::dailyRetrain);
    }

    /**
     * 重训练本体：语料快照 → <b>最低数据集校验</b>（总字节不足直接拒绝，保持现行字典）→
     * 训练 → 与激活版逐字节比对（语料未变化时 zstd 确定性产出可能与现行字典相同，
     * 跳过无意义升版）→ 原子落盘 → {@link #offerTrainedDictionary} 走 ACK 门控 rollout
     * （全员确认才切换，超时/旧协议连接阻断则保持旧字典）。语料在消费后清空（防无限增长，
     * 保留上限兜底）；训练异常不清（数据仍有价值）。
     */
    private static void dailyRetrain() {
        if (!serverSide) {
            return;
        }
        DictionaryCorpus collector = corpusCollector;
        if (collector == null || !training.compareAndSet(false, true)) {
            return;
        }
        try {
            long startNs = System.nanoTime();
            List<byte[]> corpus = collector.snapshotForTraining(SAMPLE_THRESHOLD, MAX_SAMPLE_BYTES);
            long corpusBytes = 0;
            for (byte[] sample : corpus) {
                corpusBytes += sample.length;
            }
            if (corpus.size() < MIN_RETRAIN_SAMPLES || corpusBytes < MIN_RETRAIN_TOTAL_BYTES) {
                LOGGER.info("Aggregation dictionary retrain refused: dataset below minimum ({} bytes from {} samples, min {} bytes / {} samples); keeping current dictionary",
                        corpusBytes, corpus.size(), MIN_RETRAIN_TOTAL_BYTES, MIN_RETRAIN_SAMPLES);
                return;
            }
            LOGGER.info("Aggregation dictionary retrain: training from {} corpus samples ({} bytes)...",
                    corpus.size(), corpusBytes);
            var trainer = ZstdRuntimeBridge.newDictTrainer((int) Math.min(corpusBytes, Integer.MAX_VALUE), DICT_SIZE);
            for (byte[] sample : corpus) {
                trainer.addSample(sample);
            }
            byte[] dict = trainer.trainSamples();

            DictionarySnapshot current = activeAggregation;
            if (current != null && java.util.Arrays.equals(current.data(), dict)) {
                LOGGER.info("Aggregation dictionary retrain produced identical dictionary, keeping epoch {}",
                        current.epoch());
                collector.clear();
                return;
            }
            saveAggregationDict(dict);
            offerTrainedDictionary(dict);
            collector.clear();
            LOGGER.info("Aggregation dictionary retrain done ({} samples, {} bytes, offered epoch {}, {} ms)",
                    corpus.size(), dict.length, lastTrained != null ? lastTrained.epoch() : -1,
                    (System.nanoTime() - startNs) / 1_000_000);
        } catch (Exception e) {
            LOGGER.error("Aggregation dictionary retrain failed", e);
        } finally {
            training.set(false);
        }
    }

    /**
     * 推送字典快照给所有已连接的客户端（经平台回调；接收端幂等安装）
     */
    private static void pushDictionaryToClients(DictionarySnapshot snapshot) {        DictionaryPushCallback callback = pushCallback;
        if (callback != null && snapshot != null && !snapshot.isEmpty()) {
            try {
                callback.pushToAllClients(snapshot);
                LOGGER.debug("Pushed aggregation dictionary to all connected clients (epoch={}, {} bytes)",
                        snapshot.epoch(), snapshot.data().length);
            } catch (Exception e) {
                LOGGER.error("Failed to push dictionary to clients", e);
            }
        }
    }

    /**
     * 是否正在采样（没有聚合包字典、无进行中 offer、且本进程从未训练/加载过）
     */
    public static boolean isSampling() {
        if (training.get()) return false;
        return serverSide && activeAggregation == null && pendingOffer == null && !trainedAtLeastOnce.get();
    }

    /**
     * 获取聚合包字典大小
     */
    public static int getAggregationDictSize() {
        DictionarySnapshot snapshot = activeAggregation;
        return snapshot != null ? snapshot.data().length : 0;
    }

    /**
     * 获取样本数量
     */
    public static int getSampleCount() {
        synchronized (samples) {
            return samples.size();
        }
    }

    /**
     * 获取样本阈值
     */
    public static int getSampleThreshold() {
        return SAMPLE_THRESHOLD;
    }

    /**
     * 收集训练样本（仅用于聚合包字典）
     *
     * @param raw 原始数据（压缩前的聚合包数据）
     */
    public static void collectSample(byte[] raw) {
        if (!isSampling() || raw.length > MAX_SAMPLE_SIZE) {
            return;
        }
        synchronized (samples) {
            // 双重检查：训练可能在 isSampling() 和这里之间开始
            if (activeAggregation != null || pendingOffer != null || training.get() || trainedAtLeastOnce.get()) {
                return;
            }
            if (totalSampleBytes >= MAX_SAMPLE_BYTES) {
                return;
            }
            samples.add(raw);
            totalSampleBytes += raw.length;
            int count = samples.size();
            // 每 25% 里程碑记录采样进度（debug 级别）
            if (count == SAMPLE_THRESHOLD / 4
                    || count == SAMPLE_THRESHOLD / 2
                    || count == SAMPLE_THRESHOLD * 3 / 4
                    || count == SAMPLE_THRESHOLD) {
                LOGGER.debug("Aggregation dictionary sampling: {}/{} samples ({} KB)",
                        count, SAMPLE_THRESHOLD, totalSampleBytes / 1024);
            }
            if (count >= SAMPLE_THRESHOLD) {
                trainAsync();
            }
        }
    }

    /**
     * 异步训练聚合包字典
     */
    private static void trainAsync() {
        if (!training.compareAndSet(false, true)) {
            return;
        }
        final List<byte[]> snapshot;
        synchronized (samples) {
            snapshot = new ArrayList<>(samples);
            samples.clear();
            totalSampleBytes = 0;
        }
        CompletableFuture.runAsync(() -> {
            long startNs = System.nanoTime();
            try {
                LOGGER.debug("Training aggregation dictionary from {} samples...", snapshot.size());
                int sampleSum = snapshot.stream().mapToInt(s -> s.length).sum();
                var trainer = ZstdRuntimeBridge.newDictTrainer(sampleSum, DICT_SIZE);
                for (byte[] sample : snapshot) {
                    trainer.addSample(sample);
                }
                byte[] dict = trainer.trainSamples();

                // 先落盘（原子写：临时文件 + ATOMIC_MOVE，防进程崩溃产生截断字典）
                saveAggregationDict(dict);

                // 再走 offer/ACK 门控：激活版在全连接确认前不变，无需任何时序等待
                offerTrainedDictionary(dict);
                LOGGER.info("Aggregation dictionary trained and offered ({} bytes from {} samples, {} ms)",
                        dict.length, snapshot.size(), (System.nanoTime() - startNs) / 1_000_000);
            } catch (Exception e) {
                LOGGER.error("Aggregation dictionary training failed", e);
            } finally {
                training.set(false);
            }
        });
    }

    /**
     * 持久化聚合包字典到磁盘（临时文件 + 原子替换；进程崩溃不会留下截断字典）
     */
    private static void saveAggregationDict(byte[] dict) {
        Path path = aggregationDictPath;
        if (path == null) {
            LOGGER.warn("Aggregation dictionary path not initialized, skip saving to disk");
            return;
        }
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            Files.write(tmp, dict);
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            LOGGER.debug("Aggregation dictionary saved to {}", path);
        } catch (IOException e) {
            LOGGER.error("Failed to save aggregation dictionary to disk", e);
        }
    }

    /**
     * 客户端会话复位（断连/换服）：清字典快照（激活 + 保留）与「服务端带 epoch 帧」标记。
     * <p>
     * 激活期 dictionary_sync 总会重装状态（空 sync 明确清空）；此处防止跨服残留
     * 旧字典与旧 epoch 判定。
     */
    public static void resetClientSession() {
        activeAggregation = null;
        retainedSnapshots.clear();
        serverEpochAware = false;
    }

    /**
     * 服务端会话复位（stopServer）：路径、快照、rollout、语料、样本、epoch 计数全清，
     * 允许同 JVM 内下一个服务端实例（集成服/LAN 重开世界）重新锚定与训练。
     */
    public static synchronized void resetServerSession() {
        activeAggregation = null;
        retainedSnapshots.clear();
        pendingOffer = null;
        lastTrained = null;
        OFFER_ACKS.clear();
        aggregationDictPath = null;
        serverSide = false;
        nextEpoch.set(1);
        trainedAtLeastOnce.set(false);
        synchronized (samples) {
            samples.clear();
            totalSampleBytes = 0;
        }
        ScheduledFuture<?> rotate = corpusRotateHandle;
        if (rotate != null) {
            rotate.cancel(false);
            corpusRotateHandle = null;
        }
        ScheduledFuture<?> retrain = retrainHandle;
        if (retrain != null) {
            retrain.cancel(false);
            retrainHandle = null;
        }
        ScheduledFuture<?> smoke = smokeRetrainHandle;
        if (smoke != null) {
            smoke.cancel(false);
            smokeRetrainHandle = null;
        }
        corpusCollector = null;
        corpusTarget = null;
        lastAttemptDay = -1;
        LOGGER.info("Aggregation dictionary server session reset");
    }
}
