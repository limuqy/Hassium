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
    /** 上次关停后台任务（重连重建前等待完成：保存必须落完才能重开同一存档目录）。 */
    private volatile java.util.concurrent.CompletableFuture<Void> shutdownFuture;

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
        synchronized (lock) {
            s = server;
            if (s != null) {
                return s;
            }
            if (failed) {
                return null;
            }
            // 重连重建：等待上次断连的后台关停（saveAll 落盘）完成——同一存档目录
            // 不能并发开两个 LevelStorageAccess（文件锁）。
            awaitShutdownComplete();
            long seed = ClientChunkPipeline.getInstance().getServerSeed();
            DebugLogger.info(DebugLogger.LogType.ASYNC, "[SHADOW] Creating shadow server (seed={})", seed);
            try {
                s = SeedGenLevelCompat.createShadowServer(seed);
                final ShadowSeedServer created = s;
                server = created;
                ClientChunkPipeline.getInstance().setShadowServerReady(true);
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
                        + "client cache/lighting/OVD disabled. Disable 'clientCache.hassiumEngineEnabled' to suppress.", e);
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
                                                + "可在配置中关闭 clientCache.hassiumEngineEnabled 抑制本提示。"),
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
     * 提交后台池执行——登出/断连不卡主线程（用户可感知的登出停顿）。重连重建
     * （{@link #getOrCreate}）前等待该任务完成（同一存档目录不能并发开两个 LevelStorageAccess）。
     * 游戏进程退出路径（JVM 终止）后台任务可能被中断——保存丢失可接受
     * （R2 比对 miss → 数据请求重推，正确降级）。
     */
    public void shutdown() {
        final ShadowSeedServer s;
        synchronized (lock) {
            s = server;
            server = null;
            failed = false;
            shutdownFuture = null;
        }
        if (s == null) {
            return;
        }
        DebugLogger.info(DebugLogger.LogType.ASYNC, "[SHADOW] Shutting down shadow server (async save)");
        // 保存用独立守护线程：HassiumTaskExecutor 随断连链关停（5s 强制），
        // 保存任务放上面会被杀（R1 盘上数据不全 → R2 大量 miss 重推）。
        // 独立线程生命周期 = 关停任务本身；游戏进程退出（JVM 终止）被杀可接受
        // （R2 比对 miss → 数据重推，正确降级）。
        java.util.concurrent.CompletableFuture<Void> future = new java.util.concurrent.CompletableFuture<>();
        shutdownFuture = future;
        Thread saver = new Thread(() -> {
            try {
                // 注意：不能先 stopMainLoop——saveAll 期间主循环仍在驱动光照任务，
                // isLightConverged 才可能为 true（提前停会误判未收敛 → 全量标脏 →
                // R2 hash 命中全被拦截）。mainLoop 由 shutdown 内部的 halt 停止。
                SeedGenLevelCompat.shutdown(s);
                future.complete(null);
            } catch (Throwable t) {
                Constants.LOG.warn("Hassium: Shadow server async shutdown failed", t);
                future.completeExceptionally(t);
            }
        }, "hassium-shadow-shutdown");
        saver.setDaemon(true);
        saver.start();
    }

    /** 等待上次关停完成（getOrCreate 重建前；最多等 10s，超时放弃等待——保存可能丢，比对 miss 降级）。 */
    private void awaitShutdownComplete() {
        java.util.concurrent.CompletableFuture<Void> f = shutdownFuture;
        if (f == null) {
            return;
        }
        try {
            f.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            DebugLogger.warn(DebugLogger.LogType.ASYNC, "[SHADOW] Wait shutdown complete failed", e);
        }
    }
}
