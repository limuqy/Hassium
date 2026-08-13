package io.github.limuqy.mc.hassium.network.seedgen;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.network.ClientChunkPipeline;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import net.minecraft.client.Minecraft;

/**
 * 影子服务端共享单例（SeedGen 本地生成与影子光照共用一个进程内 ServerLevel）。
 * <p>
 * 创建条件：Hassium 能力握手已到达（{@code ClientChunkPipeline#isHassiumHandshakeDone()}，
 * = 服务端已装 MOD 且 worldSeed 已下发）——影子端是世界级计算后端，无真实 seed 时
 * 不创建（服务端未装 MOD 场景：不启影子端，光照由 packet 自带、缓存/OVD/导出保留）。
 * <p>
 * seed 直接使用握手下发的 worldSeed（0 是合法种子，不做随机兜底——创建只发生在
 * 握手之后，getServerSeed() 即真实种子）。影子端本身不 worldgen（区块数据全部来自
 * 服务端 packet 注入，{@link ShadowSeedServer#injectChunk}）；worldgen 仅在
 * OVD 本地生成（{@code SeedGenExecutor}）需要同 seed 一致性时才发生。
 * <p>
 * 创建失败置 failed：影子端不再尝试，{@code ClientChunkPipeline#setShadowServerFailed}
 * 生效（降级态：缓存/OVD/SeedGen/影子光照全关 + 游戏内报错）。
 */
public final class ShadowServerRegistry {

    private static final ShadowServerRegistry INSTANCE = new ShadowServerRegistry();

    private final Object lock = new Object();
    private volatile ShadowSeedServer server;
    private volatile boolean failed;
    /** 本次关停后台任务（关停完成时 complete；保存必须落完才能重开同一存档目录）。 */
    private volatile java.util.concurrent.CompletableFuture<Void> shutdownFuture;
    /** 上一次关停的 future（由每次 {@link #shutdown()} 更新为本次 future）——
     * R2 会话期写 gate（{@link #isPreviousShutdownComplete()}）与 saver 前置等待读取：
     * 同一存档目录禁止并发写（数据安全红线）。 */
    private volatile java.util.concurrent.CompletableFuture<Void> previousShutdownFuture;
    /** 关停是否仍在进行（写 gate 主状态；shutdown 开始置 false，saver 结束置 true）。 */
    private volatile boolean previousShutdownComplete = true;

    private ShadowServerRegistry() {}

    public static ShadowServerRegistry getInstance() {
        return INSTANCE;
    }

    /** 当前影子端（未创建返回 null）。 */
    public ShadowSeedServer get() {
        return server;
    }

    /** 创建失败（本会话不再尝试；重连后 {@link #shutdown()} 复位）。 */
    public boolean isFailed() {
        return failed;
    }

    /**
     * 懒创建（任意线程可调；失败返回 null 并置 failed + 游戏内报错）。
     * 未握手（服务端未装 MOD / 握手未到）→ null（不创建、不置 failed）。
     */
    public ShadowSeedServer getOrCreate() {
        ShadowSeedServer s = server;
        if (s != null) {
            return s;
        }
        if (failed) {
            return null;
        }
        if (!ClientChunkPipeline.getInstance().isHassiumHandshakeDone()) {
            return null; // 无服务端 seed：不创建（无种子关闭生成，含 OVD 本地生成）
        }
        // P3：世界根决议前置条件（与 SeedGenLevelCompat.resolveShadowWorldRoot 一致）——
        // gameDir 未记录（登录早期）时无法决议稳定目录，创建会抛异常/落丢档目录；
        // 不创建，调用方（区块包/Ovd/SeedGen）随后自然重试。
        if (ClientChunkPipeline.getInstance().getGameDir() == null) {
            return null;
        }
        synchronized (lock) {
            s = server;
            if (s != null) {
                return s;
            }
            if (failed) {
                return null;
            }
            // 重连重建：不再等待上次关停的 saveAll 落盘完成——R1 关停线程第一步即释放
            // 世界目录锁（session.lock，见 SeedGenLevelCompat.shutdown），R2 可并发创建
            // 影子端并读盘（loadFromDisk 只读；torn read 退化为比对 miss → 数据重推）。
            // 锁仍被占用（关停线程尚未执行到 access.close() 的毫秒级竞态窗口）时由
            // createShadowServerWithLockRetry 短暂重试，绝不把「锁忙」当成创建失败直接
            // 降级——旧行为：saveAll 慢时 awaitShutdownComplete 10s 超时 → tryLock
            // OverlappingFileLockException → failShadowServer → 缓存/OVD/光照整会话全关。
            long seed = ClientChunkPipeline.getInstance().getServerSeed();
            DebugLogger.info(DebugLogger.LogType.ASYNC, "[SHADOW] Creating shadow server (seed={})", seed);
            try {
                s = createShadowServerWithLockRetry(seed);
                final ShadowSeedServer created = s;
                server = created;
                ClientChunkPipeline.getInstance().setShadowServerReady(true);
                // 影子端就绪 → OVD 环带重扫对齐（ready 前重扫必读空盘，见
                // ViewDistanceExtensionService.onShadowReady；P3/P5 关联项）。
                io.github.limuqy.mc.hassium.cache.client.ViewDistanceExtensionService.getInstance()
                        .onShadowReady();
                DebugLogger.info(DebugLogger.LogType.ASYNC,
                        "[SHADOW] Shadow server ready (seed={})", seed);
                // 影子端就绪后上报存档布隆位图（后台扫描 region 头部位图，不卡创建线程）：
                // 服务端 bloom miss（确定无缓存）→ 数据直推；bloom hit → 只发 hash 由影子端比对。
                io.github.limuqy.mc.hassium.concurrent.HassiumTaskExecutor executor =
                        io.github.limuqy.mc.hassium.concurrent.HassiumTaskExecutor.getClient();
                if (executor != null && executor.isRunning()) {
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
                return s;
            } catch (Exception e) {
                failShadowServer();
                Constants.LOG.error("Hassium: Shadow server creation failed; "
                        + "client cache/lighting/OVD disabled. Disable 'chunk.hassiumEngineEnabled' to suppress.", e);
                return null;
            }
        }
    }

    /**
     * 置降级态（本会话不再尝试；与创建失败同级的关闭核心逻辑）：
     * {@code shadowServerFailed} → 缓存/超视渲染/SeedGen/影子链路全关 + 游戏内提示。
     * 任意线程可调（注入失败 / 创建失败共用）。
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
     * 断连清理（幂等；重连后允许重建）。保存全链（saveAll → halt → chunkMap.close）
     * 提交后台守护线程执行——登出/断连不卡主线程（用户可感知的登出停顿）。
     * <p>
     * T5cShadowReady：重建（{@link #getOrCreate}）不再等待本关停的 saveAll 落盘完成
     * ——本关停线程第一步即释放世界目录锁（session.lock，见
     * {@code SeedGenLevelCompat.shutdown}），R2 并发创建影子端只读盘（torn read 退化
     * 为比对 miss → 数据重推）。同一存档目录禁止并发写：本次保存（saver）前置等待
     * 上次关停完成（有界 30s），R2 会话期写路径经 {@link #isPreviousShutdownComplete()}
     * gate 串行化（{@code ShadowSeedServer.saveChunkToDisk / deleteChunk}）。
     * 游戏进程退出路径（JVM 终止）后台任务可能被中断——保存丢失可接受
     * （R2 比对 miss → 数据请求重推，正确降级）。
     */
    public void shutdown() {
        final ShadowSeedServer s;
        final java.util.concurrent.CompletableFuture<Void> previous;
        synchronized (lock) {
            s = server;
            server = null;
            failed = false;
            // 捕获上次关停的 future（本次保存开始前等待其完成——禁并发写同一 mca）；
            // shutdownFuture 保持原值直到下面替换，getOrCreate 已不再等待它。
            previous = previousShutdownFuture;
            if (s == null) {
                return; // 幂等：无端可关，写 gate 状态保持
            }
            // 关停开始：写 gate 关闭（R2 会话期禁止写盘，直至本关停结束）
            previousShutdownComplete = false;
        }
        DebugLogger.info(DebugLogger.LogType.ASYNC, "[SHADOW] Shutting down shadow server (async save)");
        // 保存用独立守护线程：HassiumTaskExecutor 随断连链关停（5s 强制），
        // 保存任务放上面会被杀（R1 盘上数据不全 → R2 大量 miss 重推）。
        // 独立线程生命周期 = 关停任务本身；游戏进程退出（JVM 终止）被杀可接受
        // （R2 比对 miss → 数据重推，正确降级）。
        java.util.concurrent.CompletableFuture<Void> future = new java.util.concurrent.CompletableFuture<>();
        shutdownFuture = future;
        previousShutdownFuture = future; // 本次 future = 下一会话的「上次关停」（写 gate 依据）
        Thread saver = new Thread(() -> {
            boolean saveAllowed = true;
            try {
                // 注意：不能先 stopMainLoop——saveAll 期间主循环仍在驱动光照任务，
                // isLightConverged 才可能为 true（提前停会误判未收敛 → 全量标脏 →
                // R2 hash 命中全被拦截）。mainLoop 由 shutdown 内部的 halt 停止。
                if (previous != null && !previous.isDone()) {
                    try {
                        previous.get(30, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (java.util.concurrent.TimeoutException te) {
                        // 数据安全红线：上次关停异常挂起 → 跳过本次保存（仅回收资源），
                        // 绝不与挂起的 saveAll 并发写同一 mca。
                        saveAllowed = false;
                        Constants.LOG.warn("Hassium: Previous shadow shutdown not finished in 30s; "
                                + "skipping this save to avoid concurrent writes to the same world dir");
                    } catch (Exception e) {
                        DebugLogger.warn(DebugLogger.LogType.ASYNC,
                                "[SHADOW] Wait previous shutdown failed", e);
                    }
                }
                SeedGenLevelCompat.shutdown(s, !saveAllowed);
                future.complete(null);
            } catch (Throwable t) {
                Constants.LOG.warn("Hassium: Shadow server async shutdown failed", t);
                future.completeExceptionally(t);
            } finally {
                previousShutdownComplete = true; // 关停结束：写 gate 重新打开
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
     * 创建影子端；世界目录锁被上次关停占用（毫秒级竞态窗口：关停线程尚未执行到
     * access.close()）时短暂重试——绝不把「锁忙」当成创建失败直接降级（旧行为：
     * saveAll 慢时 awaitShutdownComplete 10s 超时 → tryLock OverlappingFileLockException
     * → failShadowServer → 缓存/OVD/光照整会话全关）。锁释放是关停线程第一步，
     * 实际 1-2 次重试即成功；10s 上限后仍失败才抛给调用方走降级。
     */
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

    /** 目录锁占用判定：同 JVM 重复 tryLock（OverlappingFileLockException）或跨进程
     * 占用（"already locked"/"Failed to acquire lock"/Windows 锁冲突 IOException）。 */
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
}
