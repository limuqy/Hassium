package io.github.limuqy.mc.hassium.shadow.server;

import io.github.limuqy.mc.hassium.platform.client.ShadowClientApi;
import io.github.limuqy.mc.hassium.platform.client.ShadowClientBridge;
import io.github.limuqy.mc.hassium.platform.client.TraceOrigin;

import io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute;
import io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageManager;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import net.minecraft.client.Minecraft;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 影子服务端共享单例（SeedGen 本地生成与影子光照共用一个进程内 ServerLevel）。
 * <p>
 * 创建条件：引擎开启 + gameDir 已记录（连服意图即可）。握手<strong>不</strong>再挡创建——
 * WorldLoader 与 login/握手并行；消费闸仍由 {@link ShadowLightCompute#isEnabled()}
 * （需握手）把守。无握手约 3s 后关停投机实例（原版服不常驻）。
 * <p>
 * 断连默认 {@link #parkForReuse()}（现=完整 {@link #shutdown()}，2026-09-18 起不做滞留保活）；
 * 同 serverId 重进直接复用（跳过 WorldLoader）。空闲约 {@link #IDLE_TIMEOUT_MS} 或换服
 * 再走 {@link #shutdown()}。
 */
public final class ShadowServerRegistry {

    private static ShadowClientApi client() {
        return ShadowClientBridge.get();
    }

    private static final ShadowServerRegistry INSTANCE = new ShadowServerRegistry();

    /** 登出保活空闲超时：无人重进同服则关停释放 RAM。 */
    static final long IDLE_TIMEOUT_MS = 60_000L;
    /**
     * SeedGen 预期下（客户端配置开启）投机创建对握手 seed 的有界等待。
     * 投机创建（{@link ShadowLightCompute#startShadowSpeculative}）早于握手完成，
     * 曾以 seed=0 装配 → 本地生成与服务端世界不一致被门禁拦截（seedgen e2e 实证：
     * 影子 started(seed=0) 早于 Handshake accepted 2s，locallyGenerated=0）。
     */
    static final long SEED_WAIT_TIMEOUT_MS = 2_000L;
    static final long SEED_WAIT_POLL_MS = 25L;

    /**
     * 重进时等待上次关停完成的上界（毫秒）。该等待跑在 {@link #getOrCreate()} 的**调用线程**上，
     * 而调用线程可能是渲染/主线程（publishCachedChunk、方块包转发都经 getOrCreate）——旧值 30s
     * 意味着「断连后立即重进」最坏冻屏 30s。实测正常关停是毫秒级（saveAll 0–17ms；过远柱已由
     * unloadChunk→flushColumn 提前逐柱落盘），故收到秒级。
     */
    static final long PREVIOUS_SHUTDOWN_WAIT_MS = 3_000L;
    /** 等待超过该阈值记一行 INFO：用于判定「重进卡顿」是否落在这条路径上。 */
    static final long PREVIOUS_SHUTDOWN_SLOW_MS = 500L;


    private final Object lock = new Object();
    private volatile ShadowSeedServer server;
    private volatile String boundServerId;
    private volatile boolean failed;
    private volatile boolean parked;
    /**
     * 仅 {@link #permitUnparkForLogin()}（onLogin）允许把 park 实例拉回 ACTIVE。
     * ConnectScreen 投机 {@code getOrCreate} 不得在 {@code onLogin} 前 unpark。
     * 连服期 {@code ensureClient} 不再拆执行器，但 park 实例仍须等登录会话再拉活。
     */
    private volatile boolean unparkPermitted;
    /** 当前实例装配用的 world seed（投机创建时可为 0；seed 到达后触发重建判定）。 */
    private volatile long assembledSeed;
    /** createShadowServerWithLockRetry 进行中（onServerSeedArrived 重试等待该标志）。 */
    private volatile boolean creating;
    /** 连服取消：装配完成后 park，避免标题画面常驻影子端。 */
    private volatile boolean speculativeAbandoned;
    /** 本次关停后台任务（关停完成时 complete；保存必须落完才能重开同一存档目录）。 */
    private volatile java.util.concurrent.CompletableFuture<Void> shutdownFuture;
    /** 上一次关停的 future（由每次 {@link #shutdown()} 更新为本次 future）——
     * R2 会话期写 gate（{@link #isPreviousShutdownComplete()}）与 saver 前置等待读取：
     * 同一存档目录禁止并发写（数据安全红线）。 */
    private volatile java.util.concurrent.CompletableFuture<Void> previousShutdownFuture;
    /** 关停是否仍在进行（写 gate 主状态；shutdown 开始置 false，saver 结束置 true）。 */
    private volatile boolean previousShutdownComplete = true;

    private final AtomicLong idleEpoch = new AtomicLong();
    /** park 代际：unpark / 新 park 递增，使在途 park 线程的 clearHot 失效。 */
    private final AtomicLong parkEpoch = new AtomicLong();
    private volatile ScheduledFuture<?> idleTimeoutFuture;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "hassium-shadow-lifecycle");
        t.setDaemon(true);
        return t;
    });

    private ShadowServerRegistry() {}

    public static ShadowServerRegistry getInstance() {
        return INSTANCE;
    }

    /** 当前影子端（未创建返回 null；含 park 保活实例）。 */
    public ShadowSeedServer get() {
        return server;
    }

    /** 创建失败（本会话不再尝试；重连后 {@link #shutdown()} 复位）。 */
    public boolean isFailed() {
        return failed;
    }

    /** 是否处于登出保活（已 save + 清热表，等待同 serverId 重进）。 */
    public boolean isParked() {
        return parked;
    }

    /** 当前绑定的 serverId（创建/复用时记录；未创建 null）。 */
    public String boundServerId() {
        return boundServerId;
    }

    /**
     * 懒创建或复用（任意线程可调；失败返回 null 并置 failed + 游戏内报错）。
     * <p>
     * 同 {@code serverId} 且实例仍在（含 park）→ 直接复用，跳过 WorldLoader。
     * 换服（serverId 不同）→ 先 {@link #shutdown()} 再创建。
     * 无握手亦可创建（seed 可为 0）；消费仍需握手。
     */
    public ShadowSeedServer getOrCreate() {
        String wantId = client().getServerId();
        ShadowSeedServer existing = server;
        if (existing != null) {
            if (shouldReuseParkedInstance(boundServerId, wantId, true)) {
                return unparkIfNeeded(existing);
            }
            // 换服：关旧再往下创建
            DebugLogger.info(DebugLogger.LogType.ASYNC,
                    "[SHADOW] serverId switch {} -> {}; shutting down prior instance",
                    boundServerId, wantId);
            shutdown();
        }
        if (failed) {
            return null;
        }
        if (client().getGameDir() == null) {
            return null;
        }
        awaitPreviousShutdownComplete();
        awaitServerSeedIfExpected();
        synchronized (lock) {
            existing = server;
            if (existing != null) {
                wantId = client().getServerId();
                if (shouldReuseParkedInstance(boundServerId, wantId, true)) {
                    return unparkIfNeeded(existing);
                }
            }
            if (failed) {
                return null;
            }
            if (client().getGameDir() == null) {
                return null;
            }
            long seed = client().getServerSeed();
            long createStartNs = System.nanoTime();
            DebugLogger.info(DebugLogger.LogType.ASYNC,
                    "[SHADOW] Creating shadow server (seed={}, handshakeDone={})",
                    seed, client().isHassiumHandshakeDone());
            try {
                creating = true;
                ShadowSeedServer created = createShadowServerWithLockRetry(seed);
                server = created;
                assembledSeed = seed;
                parked = false;
                boundServerId = client().getServerId();
                cancelIdleTimeout();
                io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageManager.resumeEncoding();
                client().setShadowServerReady(true);
                ShadowLightCompute.onShadowServerReady();
                DebugLogger.info(DebugLogger.LogType.ASYNC,
                        "[SHADOW] Shadow server ready (seed={}) (+{}ms)",
                        seed, (System.nanoTime() - createStartNs) / 1_000_000L);
                creating = false;
                boolean abandon = speculativeAbandoned && !unparkPermitted;
                if (!abandon) {
                    return created;
                }
            } catch (Exception e) {
                creating = false;
                failShadowServer();
                Constants.LOG.error("Hassium: Shadow server creation failed; "
                        + "client cache/lighting/OVD disabled. Disable 'chunk.enabled' to suppress.", e);
                return null;
            }
        }
        parkForReuse();
        return server;
    }

    /** 新的连服意图：清掉上一轮取消标记。 */
    public void beginSpeculativeConnect() {
        speculativeAbandoned = false;
    }

    /**
     * 连服取消/失败：若尚未 onLogin unpark，把投机实例 park 掉。
     * 装配仍在进行时只打标，{@link #getOrCreate()} 完成后自行 park。
     */
    public void abandonSpeculativeConnect() {
        speculativeAbandoned = true;
        if (unparkPermitted) {
            return;
        }
        if (creating) {
            return;
        }
        parkForReuse();
    }

    /**
     * SeedGen 预期下（客户端配置开启）等待握手 seed 到达，有界超时后按现状以 seed=0
     * 装配降级（消费侧本地生成仍被既有 gate 拦截，不产出错误地形）。
     * 退出条件任一：seed 就绪 / 握手完成（seed 为最终值，0 = 服务端 SeedGen 关，
     * 保持「关闭时不泄露真实 seed」语义）/ 超时 / 中断。
     * 必须在 {@code synchronized (lock)} 之外等待：避免阻塞其他 getOrCreate 调用方
     * （consumeLoop / OVD drain 等后台管线）。
     */
    private void awaitServerSeedIfExpected() {
        if (!io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance()
                .isClientSeedGenEnabled()) {
            return;
        }
        ShadowClientApi pipeline = client();
        long deadline = System.currentTimeMillis() + SEED_WAIT_TIMEOUT_MS;
        while (pipeline.getServerSeed() == 0L
                && !pipeline.isHassiumHandshakeDone()
                && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(SEED_WAIT_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        DebugLogger.info(DebugLogger.LogType.ASYNC,
                "[SHADOW] Seed wait done: seed={} handshakeDone={}",
                pipeline.getServerSeed(), pipeline.isHassiumHandshakeDone());
    }

    /**
     * 重建判定（纯函数测试缝）：SeedGen 预期下，真实 seed 到达且当前实例装配种子不符
     * （投机创建的 seed=0 实例）→ 需要重建。
     */
    public static boolean shouldRebuildForSeed(long assembledSeed, long arrivedSeed,
                                        boolean clientSeedGenEnabled) {
        return clientSeedGenEnabled && arrivedSeed != 0L && assembledSeed != arrivedSeed;
    }

    /**
     * 重建判定：握手维度清单含本地可装配的自定义维，而当前影子实例尚未装配该维
     * （投机创建早于 play_init，seedGen 关闭时 seed 恒 0 不会走 seed 重建）。
     */
    public static boolean shouldRebuildForDimensions(
            java.util.Collection<String> arrivedIds,
            java.util.Collection<String> assembledDims) {
        if (arrivedIds == null || arrivedIds.isEmpty() || assembledDims == null) {
            return false;
        }
        for (String id : arrivedIds) {
            if (id == null || id.isEmpty()
                    || io.github.limuqy.mc.hassium.utils.DimensionKey.OVERWORLD.equals(id)
                    || io.github.limuqy.mc.hassium.utils.DimensionKey.NETHER.equals(id)
                    || io.github.limuqy.mc.hassium.utils.DimensionKey.END.equals(id)) {
                continue;
            }
            if (!assembledDims.contains(id)) {
                return true;
            }
        }
        return false;
    }

    /** 重建重试上界：创建进行中最多等 ~4s（20 × 200ms），超时放弃本会话重建。 */
    static final int SEED_REBUILD_MAX_RETRIES = 20;
    static final long SEED_REBUILD_RETRY_MS = 200L;

    /**
     * 握手真实 seed 到达（{@code ClientChunkPipeline.setServerSeedInfo} 调用）：
     * 若现有实例是投机创建的 seed=0 装配 → 关停并按真实 seed 重建。
     * <p>
     * 为什么等待路径不够：投机创建可发生在 ConnectScreen（早于 TCP 连接），实例随即
     * park；登录后 getOrCreate 恒走复用分支，永远不会再进 seed 等待。故 seed 到达时
     * 必须主动判重建。SeedGen 关闭（arrivedSeed=0）不触发——保持「关闭时不泄露
     * 真实 seed」语义；park 复用的旧会话实例 assembledSeed 已是真实 seed，判定为
     * 相等 → 不重建，R1/R2 缓存复用不受影响。
     */
    public void onServerSeedArrived(long arrivedSeed) {
        boolean enabled = io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance()
                .isClientSeedGenEnabled();
        if (!shouldRebuildForSeed(assembledSeed, arrivedSeed, enabled)) {
            return;
        }
        scheduleRebuild("real seed " + arrivedSeed + " (assembled seed=" + assembledSeed + ")",
                () -> shouldRebuildForSeed(assembledSeed, arrivedSeed, enabled));
    }

    /**
     * 握手维度清单到达（{@code ClientChunkPipeline.setServerSeedInfo} 调用）：
     * 投机影子常早于 play_init 装配（seedGen 关闭时 seed 恒 0，不走 seed 重建），
     * 自定义维度未进 WorldDimensions → 该维只能原版透传。清单含未装配自定义维时
     * 关停重建，使缓存/SeedGen 能覆盖 TF/AoA 等维度。
     */
    public void onServerDimensionIdsArrived(java.util.List<String> dimensionIds) {
        ShadowSeedServer current = server;
        if (current == null || !shouldRebuildForDimensions(dimensionIds, current.storageDimensions())) {
            return;
        }
        java.util.Set<String> assembled = current.storageDimensions();
        Constants.LOG.info("Hassium: Shadow rebuild for custom dimensions (assembled={}, arrived={})",
                assembled, dimensionIds);
        scheduleRebuild("custom dimensions " + dimensionIds,
                () -> {
                    ShadowSeedServer s = server;
                    return s != null && shouldRebuildForDimensions(dimensionIds, s.storageDimensions());
                });
    }

    private void scheduleRebuild(String reason, java.util.function.BooleanSupplier stillNeeded) {
        Runnable rebuild = () -> {
            for (int i = 0; i < SEED_REBUILD_MAX_RETRIES; i++) {
                if (!creating) {
                    break;
                }
                try {
                    Thread.sleep(SEED_REBUILD_RETRY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (creating || !stillNeeded.getAsBoolean()) {
                return; // 创建中超时放弃 / 已被其他线程重建
            }
            Constants.LOG.info("Hassium: Shadow rebuild for {}", reason);
            shutdown();
            getOrCreate();
        };
        var executor = io.github.limuqy.mc.hassium.concurrent.HassiumTaskExecutor.getClient();
        if (executor != null && executor.isRunning()) {
            executor.submit(rebuild, io.github.limuqy.mc.hassium.concurrent.TaskCategory.BEST_EFFORT);
        } else {
            scheduler.submit(rebuild);
        }
    }
    /**
     * 登录前 / 注册表窗口内禁止 unpark。
     * <p>
     * {@code encodingPaused}：1.20.1 Forge {@code revertToFrozen} 窗口（{@code pauseEncoding}）。
     * {@code unparkPermitted}：仅 onLogin 放行——去掉 HEAD 全局 pause 后，投机
     * {@code getOrCreate} 不能再靠 {@code !encodingPaused} 立刻把 park 实例拉活。
     */
    public static boolean shouldUnpark(boolean parked, boolean encodingPaused, boolean unparkPermitted) {
        return parked && !encodingPaused && unparkPermitted;
    }

    /** 登录会话已就绪：允许 {@link #getOrCreate()} 复用 park 实例。 */
    public void permitUnparkForLogin() {
        unparkPermitted = true;
    }

    /** park → 活跃：取消 idle，标记 ready，唤醒消费者。 */
    private ShadowSeedServer unparkIfNeeded(ShadowSeedServer s) {
        if (shouldUnpark(parked,
                io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageManager.isEncodingPaused(),
                unparkPermitted)) {
            parkEpoch.incrementAndGet(); // 使在途 park 线程的 clearHot/idle 失效
            parked = false;
            cancelIdleTimeout();
            io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageManager.resumeEncoding();
            client().setShadowServerReady(true);
            // 会话级降级随重进清除：R1 单柱失败/误置 failed 会让 R2 OVD publish 恒 false
            // （test1 实证 ovdLoaded=0），park 复用不得继承上一会话的 failed。
            client().setShadowServerFailed(false);
            // resetStorage 会 resetCacheable()；park 实例仍持有自定义维 storage，
            // 必须重新 markCacheable，否则 R2 该维退回原版透传、缓存全 miss。
            for (String dim : s.storageDimensions()) {
                io.github.limuqy.mc.hassium.utils.DimensionKey.markCacheable(dim);
            }
            ShadowLightCompute.onShadowServerReady();
            DebugLogger.info(DebugLogger.LogType.ASYNC,
                    "[SHADOW] Reusing parked shadow server (serverId={})", boundServerId);
        }
        return s;
    }


    /**
     * 置降级态（本会话不再尝试；与创建失败同级的关闭核心逻辑）：
     * {@code shadowServerFailed} → 缓存/超视渲染/SeedGen/影子链路全关 + 游戏内提示。
     */
    public void failShadowServer() {
        synchronized (lock) {
            failed = true;
            client().setShadowServerFailed(true);
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.player != null) {
                mc.execute(() -> {
                    if (mc.player != null) {
                        mc.player.displayClientMessage(
                                net.minecraft.network.chat.Component.literal(
                                        "[Hassium] Hassium 引擎异常：客户端缓存/超视渲染/SeedGen 已关闭。"
                                                + "可在配置中关闭 chunk.enabled 抑制本提示。"),
                                false);
                    }
                });
            }
        } catch (Throwable ignored) {
            // 报错提示失败不影响降级态
        }
    }


    /**
     * 断连：**不再 park 保活**（2026-09-18 用户：退出服务器后影子端不做滞留）。
     * 直接完整 {@link #shutdown()}：saveAll → halt → 关存储；重连经 getOrCreate 新建并读盘。
     * 保留方法名以兼容全部调用点（ClientLifecycleHelper / 投机取消等）。
     */
    public void parkForReuse() {
        DebugLogger.info(DebugLogger.LogType.ASYNC,
                "[SHADOW] Disconnect: full shutdown (no park/reuse) serverId={}", boundServerId);
        shutdown();
    }

    private void scheduleIdleTimeout(long timeoutMs) {
        long epoch = idleEpoch.incrementAndGet();
        ScheduledFuture<?> prev = idleTimeoutFuture;
        if (prev != null) {
            prev.cancel(false);
        }
        idleTimeoutFuture = scheduler.schedule(() -> {
            if (epoch != idleEpoch.get()) {
                return;
            }
            if (!parked || server == null) {
                return;
            }
            DebugLogger.info(DebugLogger.LogType.ASYNC,
                    "[SHADOW] Idle timeout ({}ms); shutting down parked shadow", timeoutMs);
            shutdown();
        }, Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
    }

    private void cancelIdleTimeout() {
        idleEpoch.incrementAndGet();
        ScheduledFuture<?> f = idleTimeoutFuture;
        if (f != null) {
            f.cancel(false);
            idleTimeoutFuture = null;
        }
    }


    /**
     * 断连清理（幂等；重连后允许重建）。保存全链（saveAll → halt → chunkMap.close）
     * 提交后台守护线程执行——登出/断连不卡主线程。
     */
    public void shutdown() {
        final ShadowSeedServer s;
        final java.util.concurrent.CompletableFuture<Void> previous;
        synchronized (lock) {
            s = server;
            server = null;
            boundServerId = null;
            parked = false;
            unparkPermitted = false;
            failed = false;
            previous = previousShutdownFuture;
            if (s == null) {
                return;
            }
            previousShutdownComplete = false;
            client().setShadowServerReady(false);
        }
        DebugLogger.info(DebugLogger.LogType.ASYNC, "[SHADOW] Shutting down shadow server (async save)");
        java.util.concurrent.CompletableFuture<Void> future = new java.util.concurrent.CompletableFuture<>();
        shutdownFuture = future;
        previousShutdownFuture = future;
        Thread saver = new Thread(() -> {
            boolean saveAllowed = true;
            try {
                if (previous != null && !previous.isDone()) {
                    try {
                        previous.get(30, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (java.util.concurrent.TimeoutException te) {
                        saveAllowed = false;
                        Constants.LOG.warn("Hassium: Previous shadow shutdown not finished in 30s; "
                                + "skipping this save to avoid concurrent writes to the same world dir");
                    } catch (Exception e) {
                        DebugLogger.warn(DebugLogger.LogType.ASYNC,
                                "[SHADOW] Wait previous shutdown failed", e);
                    }
                }
                SeedGenLevelCompat.shutdown(s, !saveAllowed);
                ShadowSeedServer current = server;
                if (current != null) {
                    try {
                        current.refreshRegionFiles();
                    } catch (Throwable t) {
                        DebugLogger.warn(DebugLogger.LogType.ASYNC,
                                "[SHADOW] Region file refresh failed", t);
                    }
                }
                future.complete(null);
            } catch (Throwable t) {
                Constants.LOG.warn("Hassium: Shadow server async shutdown failed", t);
                future.completeExceptionally(t);
            } finally {
                previousShutdownComplete = true;
            }
        }, "hassium-shadow-shutdown");
        saver.setDaemon(true);
        saver.start();
    }

    /** 上次关停（R1 saveAll）是否已结束：R2 会话期写 gate（false = 禁写盘）。 */
    public boolean isPreviousShutdownComplete() {
        return previousShutdownComplete;
    }

    /**
     * 断连路径**同步等**本次关停保存结束（有界）。
     * <p>
     * 关停本身仍是异步 daemon 线程（{@link #shutdown()}）；这里只是把「等」挪到断连路径上——
     * 用户在此处本就预期等待（对齐原版单人「保存世界中」语义），且等待期间 saver 已在跑。
     * 关游戏窗口（共享 ioPool 已关）时 saveAll 会跳过，但 closeStorage 仍会写出已编码的 region
     * 映像，所以等待仍有意义。
     * <p>
     * 超时即放弃等待并记一行 warn：数据不因此丢，靠下次会话 compare miss 重推。
     *
     * @return true = 在超时前等到关停结束（或无在途关停）
     */
    public boolean awaitShutdownComplete(long timeoutMs) {
        java.util.concurrent.CompletableFuture<Void> future = shutdownFuture;
        if (future == null || future.isDone()) {
            return true;
        }
        long startMs = System.currentTimeMillis();
        try {
            future.get(Math.max(0L, timeoutMs), java.util.concurrent.TimeUnit.MILLISECONDS);
            Constants.LOG.info("Hassium: shadow shutdown waited {}ms on disconnect",
                    System.currentTimeMillis() - startMs);
            return true;
        } catch (java.util.concurrent.TimeoutException e) {
            Constants.LOG.warn("Hassium: shadow shutdown still running after {}ms on disconnect; "
                    + "leaving it to finish in background (data re-pushed on next session)", timeoutMs);
            return false;
        } catch (Exception e) {
            DebugLogger.warn(DebugLogger.LogType.ASYNC, "[SHADOW] await shutdown failed", e);
            return false;
        }
    }

    /**
     * 有界等待上次关停 saveAll 完成，超时继续创建。
     * <p>
     * 上界 {@link #PREVIOUS_SHUTDOWN_WAIT_MS}：本方法在 {@link #getOrCreate()} 的调用线程上跑，
     * 而该线程可能是渲染/主线程，故只给秒级（saver 侧等上一次关停仍是 30s 上界——那是后台线程，
     * 不冻游戏）。超时不是致命：{@code ShadowSeedServer.canWriteStorage()} 要求
     * {@link #isPreviousShutdownComplete()}，R2 的写盘会一直被挡到上次关停真正结束，不会并发写
     * 同一存档目录。
     */
    private static void awaitPreviousShutdownComplete() {
        final long startMs = System.currentTimeMillis();
        final long deadline = startMs + PREVIOUS_SHUTDOWN_WAIT_MS;
        while (!ShadowServerRegistry.getInstance().isPreviousShutdownComplete()) {
            if (System.currentTimeMillis() > deadline) {
                // 无条件输出：这条是「重进静默卡顿」唯一的现场证据，不得受 debug 门控
                Constants.LOG.warn("Hassium: Previous shadow shutdown not complete in {}ms; creating "
                                + "shadow server anyway (writes stay gated until it finishes)",
                        PREVIOUS_SHUTDOWN_WAIT_MS);
                return;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        long waitedMs = System.currentTimeMillis() - startMs;
        if (waitedMs >= PREVIOUS_SHUTDOWN_SLOW_MS) {
            Constants.LOG.info("Hassium: waited {}ms for previous shadow shutdown before creating", waitedMs);
        }
    }

    private ShadowSeedServer createShadowServerWithLockRetry(long seed) throws Exception {
        final int maxAttempts = 50; // 200ms × 50 = 10s
        int attempt = 0;
        while (true) {
            try {
                return SeedGenLevelCompat.createShadowServer(seed);
            } catch (Exception e) {
                if (++attempt < maxAttempts && isLockBusy(e)) {
                    try {
                        Thread.sleep(200L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                    continue;
                }
                throw e;
            }
        }
    }

    private static boolean isLockBusy(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof java.nio.channels.OverlappingFileLockException) {
                return true;
            }
            if (c instanceof java.io.IOException) {
                String m = c.getMessage();
                if (m != null) {
                    String lm = m.toLowerCase(java.util.Locale.ROOT);
                    if (lm.contains("already locked") || lm.contains("failed to acquire lock")
                            || lm.contains("failed to open lock") || lm.contains("session.lock")
                            || lm.contains("locked a portion") || lm.contains("used by another process")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 测试缝：同 serverId 应复用；不同应关旧。 */
    public static boolean shouldReuseParkedInstance(String boundId, String wantId, boolean hasServer) {
        if (!hasServer) {
            return false;
        }
        if (wantId == null || boundId == null) {
            return false; // review-fix: 身份未齐拒绝复用，防换服错配上一服影子实例（原为保守复用）
        }
        return wantId.equals(boundId);
    }
}
