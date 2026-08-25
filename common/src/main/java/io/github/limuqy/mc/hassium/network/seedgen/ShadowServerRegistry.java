package io.github.limuqy.mc.hassium.network.seedgen;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.network.ClientChunkPipeline;
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
 * 断连默认 {@link #parkForReuse()}：save 脏柱 + 清热表，保留实例与 session.lock；
 * 同 serverId 重进直接复用（跳过 WorldLoader）。空闲约 {@link #IDLE_TIMEOUT_MS} 或换服
 * 再走 {@link #shutdown()}。
 */
public final class ShadowServerRegistry {

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


    private final Object lock = new Object();
    private volatile ShadowSeedServer server;
    private volatile String boundServerId;
    private volatile boolean failed;
    private volatile boolean parked;
    /** 当前实例装配用的 world seed（投机创建时可为 0；seed 到达后触发重建判定）。 */
    private volatile long assembledSeed;
    /** createShadowServerWithLockRetry 进行中（onServerSeedArrived 重试等待该标志）。 */
    private volatile boolean creating;
    /** 本次关停后台任务（关停完成时 complete；保存必须落完才能重开同一存档目录）。 */
    private volatile java.util.concurrent.CompletableFuture<Void> shutdownFuture;
    /** 上一次关停的 future（由每次 {@link #shutdown()} 更新为本次 future）——
     * R2 会话期写 gate（{@link #isPreviousShutdownComplete()}）与 saver 前置等待读取：
     * 同一存档目录禁止并发写（数据安全红线）。 */
    private volatile java.util.concurrent.CompletableFuture<Void> previousShutdownFuture;
    /** 关停是否仍在进行（写 gate 主状态；shutdown 开始置 false，saver 结束置 true）。 */
    private volatile boolean previousShutdownComplete = true;

    private final AtomicLong speculativeWatchdogEpoch = new AtomicLong();
    private final AtomicLong idleEpoch = new AtomicLong();
    /** park 代际：unpark / 新 park 递增，使在途 park 线程的 clearHot 失效。 */
    private final AtomicLong parkEpoch = new AtomicLong();
    /**
     * Bloom C2S 尚未发出：投机创建 / onLogin 早于网关 ACTIVE 时
     * {@link #scheduleBloomSync} 只记 pending，握手后再发。
     */
    private volatile boolean bloomSyncPending;
    private volatile ScheduledFuture<?> speculativeWatchdogFuture;
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
        String wantId = ClientChunkPipeline.getInstance().getServerId();
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
        if (ClientChunkPipeline.getInstance().getGameDir() == null) {
            return null;
        }
        awaitPreviousShutdownComplete();
        awaitServerSeedIfExpected();
        synchronized (lock) {
            existing = server;
            if (existing != null) {
                wantId = ClientChunkPipeline.getInstance().getServerId();
                if (shouldReuseParkedInstance(boundServerId, wantId, true)) {
                    return unparkIfNeeded(existing);
                }
            }
            if (failed) {
                return null;
            }
            if (ClientChunkPipeline.getInstance().getGameDir() == null) {
                return null;
            }
            long seed = ClientChunkPipeline.getInstance().getServerSeed();
            long createStartNs = System.nanoTime();
            DebugLogger.info(DebugLogger.LogType.ASYNC,
                    "[SHADOW] Creating shadow server (seed={}, handshakeDone={})",
                    seed, ClientChunkPipeline.getInstance().isHassiumHandshakeDone());
            try {
                creating = true;
                ShadowSeedServer created = createShadowServerWithLockRetry(seed);
                server = created;
                assembledSeed = seed;
                parked = false;
                boundServerId = ClientChunkPipeline.getInstance().getServerId();
                cancelIdleTimeout();
                io.github.limuqy.mc.hassium.storage.ShadowStorageManager.resumeEncoding();
                ClientChunkPipeline.getInstance().setShadowServerReady(true);
                ShadowLightCompute.onShadowServerReady();
                io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService.getInstance()
                        .onShadowReady();
                DebugLogger.info(DebugLogger.LogType.ASYNC,
                        "[SHADOW] Shadow server ready (seed={}) (+{}ms)",
                        seed, (System.nanoTime() - createStartNs) / 1_000_000L);
                SeedGenExecutor.getInstance().onShadowReady();
                scheduleBloomSync(created);
                creating = false;
                return created;
            } catch (Exception e) {
                creating = false;
                failShadowServer();
                Constants.LOG.error("Hassium: Shadow server creation failed; "
                        + "client cache/lighting/OVD disabled. Disable 'chunk.hassiumEngineEnabled' to suppress.", e);
                return null;
            }
        }
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
        ClientChunkPipeline pipeline = ClientChunkPipeline.getInstance();
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
    static boolean shouldRebuildForSeed(long assembledSeed, long arrivedSeed,
                                        boolean clientSeedGenEnabled) {
        return clientSeedGenEnabled && arrivedSeed != 0L && assembledSeed != arrivedSeed;
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
            if (creating || !shouldRebuildForSeed(assembledSeed, arrivedSeed, enabled)) {
                return; // 创建中超时放弃 / 已被其他线程重建
            }
            Constants.LOG.info("Hassium: Shadow rebuild for real seed {} (assembled seed={})",
                    arrivedSeed, assembledSeed);
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
     * 断连 {@code pauseEncoding} 窗口内禁止 unpark：hash 比对 / consumeLoop 的
     * {@link #getOrCreate()} 不得把刚 park 的实例拉回 ACTIVE（resumeEncoding
     * 会让 RegionWorker 再抢注册表读锁，卡住 clearLevel 写锁）。
     */
    static boolean shouldUnpark(boolean parked, boolean encodingPaused) {
        return parked && !encodingPaused;
    }

    /** park → 活跃：取消 idle，标记 ready，唤醒消费者。 */
    private ShadowSeedServer unparkIfNeeded(ShadowSeedServer s) {
        if (shouldUnpark(parked, io.github.limuqy.mc.hassium.storage.ShadowStorageManager.isEncodingPaused())) {
            parkEpoch.incrementAndGet(); // 使在途 park 线程的 clearHot/idle 失效
            parked = false;
            cancelIdleTimeout();
            io.github.limuqy.mc.hassium.storage.ShadowStorageManager.resumeEncoding();
            ClientChunkPipeline.getInstance().setShadowServerReady(true);
            ShadowLightCompute.onShadowServerReady();
            io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService.getInstance()
                    .onShadowReady();
            SeedGenExecutor.getInstance().onShadowReady();
            DebugLogger.info(DebugLogger.LogType.ASYNC,
                    "[SHADOW] Reusing parked shadow server (serverId={})", boundServerId);
            scheduleBloomSync(s);
        }
        return s;
    }

    /**
     * Bloom 必须走 PLAY C2S 且网关已 ACTIVE：更早发出时要么 getSender()==null 被丢，
     * 要么 HANDSHAKING passthrough 打到尚未物化的玩家。服务端 Bloom 未就绪会把
     * 1.20.1 paced FULL_VISIBLE 折成 hash，冷 R1 miss→FORCE_FULL 抢配额。
     */
    static boolean canSendBloomSync(boolean handshakeDone, boolean hasPlayer,
                                    boolean hasConnection, boolean networkActive) {
        return handshakeDone && hasPlayer && hasConnection && networkActive;
    }

    /** 握手 ACTIVE 后补发被推迟的 Bloom（幂等：无 pending 则跳过）。 */
    public void flushPendingBloomSync() {
        if (!bloomSyncPending) {
            return;
        }
        ShadowSeedServer s = server;
        if (s == null || parked) {
            return;
        }
        scheduleBloomSync(s);
    }

    private boolean canSendBloomSyncNow() {
        Minecraft mc = Minecraft.getInstance();
        boolean hasPlayer = mc != null && mc.player != null;
        boolean hasConnection = mc != null && mc.getConnection() != null;
        boolean handshakeDone = ClientChunkPipeline.getInstance().isHassiumHandshakeDone();
        boolean networkActive = io.github.limuqy.mc.hassium.network.core.NetworkCore.getInstance()
                .state() == io.github.limuqy.mc.hassium.network.core.NetworkCoreState.ACTIVE;
        return canSendBloomSync(handshakeDone, hasPlayer, hasConnection, networkActive);
    }

    private void scheduleBloomSync(ShadowSeedServer created) {
        io.github.limuqy.mc.hassium.concurrent.HassiumTaskExecutor executor =
                io.github.limuqy.mc.hassium.concurrent.HassiumTaskExecutor.getClient();
        if (executor == null || !executor.isRunning()) {
            bloomSyncPending = true;
            return;
        }
        executor.submit(() -> {
            if (!canSendBloomSyncNow()) {
                bloomSyncPending = true;
                Constants.LOG.info("Hassium: Shadow bloom deferred (waiting handshake/player/ACTIVE)");
                return;
            }
            bloomSyncPending = false;
            // per-dimension bloom：三维度各构建一帧 full bloom（服务端按维度查询）。
            for (String dimension : new String[] {
                    io.github.limuqy.mc.hassium.utils.DimensionKey.OVERWORLD,
                    io.github.limuqy.mc.hassium.utils.DimensionKey.NETHER,
                    io.github.limuqy.mc.hassium.utils.DimensionKey.END}) {
                try {
                    io.github.limuqy.mc.hassium.cache.client.ChunkBloomFilter bloom =
                            created.buildBloomFilter(dimension);
                    byte[] bytes = bloom.toByteArray();
                    io.github.limuqy.mc.hassium.network.ClientBloomSyncPacket packet =
                            new io.github.limuqy.mc.hassium.network.ClientBloomSyncPacket(true, dimension, bytes);
                    io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.buffer();
                    boolean sent = false;
                    try {
                        net.minecraft.network.FriendlyByteBuf fbb = new net.minecraft.network.FriendlyByteBuf(buf);
                        packet.encode(fbb);
                        io.github.limuqy.mc.hassium.platform.Services.NETWORK_MANAGER.sendClientBloomSync(fbb);
                        sent = true;
                        Constants.LOG.info("Hassium: Shadow bloom sent (dimension={}, {} bytes, {} chunks)",
                                dimension, bytes.length, bloom.getInsertCount());
                    } finally {
                        if (!sent && buf.refCnt() > 0) {
                            buf.release();
                        }
                    }
                } catch (Throwable t) {
                    DebugLogger.warn(DebugLogger.LogType.ASYNC,
                            "[BLOOM_SYNC] Shadow bloom send failed (dimension={})", t);
                }
            }
        }, io.github.limuqy.mc.hassium.concurrent.TaskCategory.BEST_EFFORT);
    }

    /**
     * 置降级态（本会话不再尝试；与创建失败同级的关闭核心逻辑）：
     * {@code shadowServerFailed} → 缓存/超视渲染/SeedGen/影子链路全关 + 游戏内提示。
     */
    public void failShadowServer() {
        synchronized (lock) {
            failed = true;
            ClientChunkPipeline.getInstance().setShadowServerFailed(true);
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.player != null) {
                mc.execute(() -> {
                    if (mc.player != null) {
                        mc.player.displayClientMessage(
                                net.minecraft.network.chat.Component.literal(
                                        "[Hassium] Hassium 引擎异常：客户端缓存/超视渲染/SeedGen 已关闭。"
                                                + "可在配置中关闭 chunk.hassiumEngineEnabled 抑制本提示。"),
                                false);
                    }
                });
            }
        } catch (Throwable ignored) {
            // 报错提示失败不影响降级态
        }
    }

    /**
     * 投机创建看门狗：超时仍无 Hassium 握手 → 关停刚拉起的影子（原版服）。
     * 握手到达或实例已关则 noop。
     */
    public void armSpeculativeHandshakeWatchdog(long timeoutMs) {
        long epoch = speculativeWatchdogEpoch.incrementAndGet();
        ScheduledFuture<?> prev = speculativeWatchdogFuture;
        if (prev != null) {
            prev.cancel(false);
        }
        speculativeWatchdogFuture = scheduler.schedule(() -> {
            if (epoch != speculativeWatchdogEpoch.get()) {
                return;
            }
            if (ClientChunkPipeline.getInstance().isHassiumHandshakeDone()) {
                return;
            }
            if (server == null) {
                return;
            }
            if (ShadowLightCompute.shouldShutdownSpeculativeShadow(
                    false, timeoutMs, timeoutMs)) {
                DebugLogger.info(DebugLogger.LogType.ASYNC,
                        "[SHADOW] Speculative shadow shutdown (no handshake in {}ms)", timeoutMs);
                shutdown();
            }
        }, Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
    }

    /**
     * 断连保活：save 脏柱 + 清 injected/dirty 热表，不 halt、不放 session.lock。
     * 同 serverId 重进经 {@link #getOrCreate()} 复用。空闲 {@link #IDLE_TIMEOUT_MS} 后
     * {@link #shutdown()}。
     */
    public void parkForReuse() {
        cancelSpeculativeWatchdog();
        final ShadowSeedServer s;
        final long epoch;
        synchronized (lock) {
            s = server;
            if (s == null) {
                return;
            }
            parked = true;
            epoch = parkEpoch.incrementAndGet();
            ClientChunkPipeline.getInstance().setShadowServerReady(false);
        }
        DebugLogger.info(DebugLogger.LogType.ASYNC,
                "[SHADOW] Parking shadow server for reuse (serverId={})", boundServerId);
        Thread parker = new Thread(() -> {
            try {
                // previousShutdownComplete 仍为 true：无需 beginShutdownSave 即可落盘
                s.saveAll();
                synchronized (lock) {
                    // 快速重进已 unpark（parkEpoch 已变）→ 不得清新会话热表
                    if (parked && parkEpoch.get() == epoch) {
                        s.clearHotStateAfterPark();
                    }
                }
            } catch (Throwable t) {
                Constants.LOG.warn("Hassium: Shadow park save/clear failed; keeping instance", t);
            }
            if (parked && parkEpoch.get() == epoch) {
                scheduleIdleTimeout(IDLE_TIMEOUT_MS);
            }
        }, "hassium-shadow-park");
        parker.setDaemon(true);
        parker.start();
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

    private void cancelSpeculativeWatchdog() {
        speculativeWatchdogEpoch.incrementAndGet();
        ScheduledFuture<?> f = speculativeWatchdogFuture;
        if (f != null) {
            f.cancel(false);
            speculativeWatchdogFuture = null;
        }
    }

    /** 握手到达：取消投机看门狗（公开给 ClientChunkPipeline）。 */
    public void cancelSpeculativeWatchdogPublic() {
        cancelSpeculativeWatchdog();
    }

    /**
     * 断连清理（幂等；重连后允许重建）。保存全链（saveAll → halt → chunkMap.close）
     * 提交后台守护线程执行——登出/断连不卡主线程。
     */
    public void shutdown() {
        cancelSpeculativeWatchdog();
        cancelIdleTimeout();
        final ShadowSeedServer s;
        final java.util.concurrent.CompletableFuture<Void> previous;
        synchronized (lock) {
            s = server;
            server = null;
            boundServerId = null;
            parked = false;
            failed = false;
            bloomSyncPending = false;
            previous = previousShutdownFuture;
            if (s == null) {
                return;
            }
            previousShutdownComplete = false;
            ClientChunkPipeline.getInstance().setShadowServerReady(false);
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

    /** 有界等待上次关停 saveAll 完成（30s 与 saver 同界；超时继续创建）。 */
    private static void awaitPreviousShutdownComplete() {
        final long deadline = System.currentTimeMillis() + 30_000L;
        while (!ShadowServerRegistry.getInstance().isPreviousShutdownComplete()) {
            if (System.currentTimeMillis() > deadline) {
                DebugLogger.warn(DebugLogger.LogType.ASYNC,
                        "[SHADOW] Previous shutdown saveAll not complete in 30s; creating shadow server anyway");
                return;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
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
    static boolean shouldReuseParkedInstance(String boundId, String wantId, boolean hasServer) {
        if (!hasServer) {
            return false;
        }
        if (wantId == null || boundId == null) {
            return true; // 身份未齐：保守复用已有实例
        }
        return wantId.equals(boundId);
    }
}
