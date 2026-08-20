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

    private final Object lock = new Object();
    private volatile ShadowSeedServer server;
    private volatile String boundServerId;
    private volatile boolean failed;
    private volatile boolean parked;
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
                ShadowSeedServer created = createShadowServerWithLockRetry(seed);
                server = created;
                parked = false;
                boundServerId = ClientChunkPipeline.getInstance().getServerId();
                cancelIdleTimeout();
                ClientChunkPipeline.getInstance().setShadowServerReady(true);
                ShadowLightCompute.onShadowServerReady();
                io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService.getInstance()
                        .onShadowReady();
                DebugLogger.info(DebugLogger.LogType.ASYNC,
                        "[SHADOW] Shadow server ready (seed={}) (+{}ms)",
                        seed, (System.nanoTime() - createStartNs) / 1_000_000L);
                SeedGenExecutor.getInstance().onShadowReady();
                scheduleBloomSync(created);
                return created;
            } catch (Exception e) {
                failShadowServer();
                Constants.LOG.error("Hassium: Shadow server creation failed; "
                        + "client cache/lighting/OVD disabled. Disable 'chunk.hassiumEngineEnabled' to suppress.", e);
                return null;
            }
        }
    }

    /** park → 活跃：取消 idle，标记 ready，唤醒消费者。 */
    private ShadowSeedServer unparkIfNeeded(ShadowSeedServer s) {
        if (parked) {
            parkEpoch.incrementAndGet(); // 使在途 park 线程的 clearHot/idle 失效
            parked = false;
            cancelIdleTimeout();
            ClientChunkPipeline.getInstance().setShadowServerReady(true);
            ShadowLightCompute.onShadowServerReady();
            io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService.getInstance()
                    .onShadowReady();
            SeedGenExecutor.getInstance().onShadowReady();
            DebugLogger.info(DebugLogger.LogType.ASYNC,
                    "[SHADOW] Reusing parked shadow server (serverId={})", boundServerId);
        }
        return s;
    }

    private void scheduleBloomSync(ShadowSeedServer created) {
        io.github.limuqy.mc.hassium.concurrent.HassiumTaskExecutor executor =
                io.github.limuqy.mc.hassium.concurrent.HassiumTaskExecutor.getClient();
        if (executor == null || !executor.isRunning()) {
            return;
        }
        executor.submit(() -> {
            try {
                io.github.limuqy.mc.hassium.cache.client.ChunkBloomFilter bloom = created.buildBloomFilter();
                byte[] bytes = bloom.toByteArray();
                io.github.limuqy.mc.hassium.network.ClientBloomSyncPacket packet =
                        new io.github.limuqy.mc.hassium.network.ClientBloomSyncPacket(true, bytes);
                io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.buffer();
                boolean sent = false;
                try {
                    net.minecraft.network.FriendlyByteBuf fbb = new net.minecraft.network.FriendlyByteBuf(buf);
                    packet.encode(fbb);
                    io.github.limuqy.mc.hassium.platform.Services.NETWORK_MANAGER.sendClientBloomSync(fbb);
                    sent = true;
                    DebugLogger.info(DebugLogger.LogType.ASYNC,
                            "[BLOOM_SYNC] Shadow bloom sent ({} bytes, {} chunks)",
                            bytes.length, bloom.getInsertCount());
                } finally {
                    if (!sent && buf.refCnt() > 0) {
                        buf.release();
                    }
                }
            } catch (Throwable t) {
                DebugLogger.warn(DebugLogger.LogType.ASYNC,
                        "[BLOOM_SYNC] Shadow bloom send failed", t);
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
