package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.concurrent.HassiumTaskExecutor;
import io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher;
import io.github.limuqy.mc.hassium.concurrent.TaskCategory;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.ClientChunkHandler;
import io.github.limuqy.mc.hassium.network.ClientMetadataHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 客户端生命周期辅助类（非 Mixin）。
 * <p>
 * Mixin 0.8.7 不允许 Mixin 类中存在非 private 的静态方法，因此将需要在多个
 * Mixin 之间共享的断连清理 / 登录初始化逻辑放到此独立类中。
 * <p>
 * 供 {@code MixinClientPacketListener}（1.20.1）与
 * {@code MixinClientCommonPacketListenerImpl}（1.20.2+）共用。
 */
public final class ClientLifecycleHelper {

    private static volatile boolean initialized = false;
    private static final AtomicBoolean finalized = new AtomicBoolean(false);

    private ClientLifecycleHelper() {
    }

    /**
     * 玩家登录时初始化缓存系统。
     * <p>
     * M2: 将 ClientHassiumStorage 创建（含热度索引 / section 哈希初始化）异步化，
     * handleLogin 主线程不再阻塞在磁盘索引初始化上。
     */
    public static void onLogin() {
        if (initialized) {
            return;
        }
        // 初始化统一后台执行器（平台线程数由配置文件控制，虚拟线程模式下忽略）
        int threads = HassiumConfigService.getInstance().getLoadThreads();
        HassiumTaskExecutor.initClient(threads);

        // 尽早写入玩家坐标，避免首波 hash/payload 在首 tick 前用 (0,0) 算优先级
        try {
            MainThreadDispatcher.updatePlayerPosition();
        } catch (Exception ignored) {
            // ignore
        }

        // 进服吞吐加速：临时提高主线程时间预算
        ClientMainThreadBudget.startJoinBoost();

        // M2: 异步初始化存储（热度索引 / section 哈希在后台线程）
        initializeCacheAsync();
        // 并行光照引擎装配（幂等，重复登录无害）：Promethium MOD 缺席时静默跳过
        // （配置 / 指标 / 官方引擎原语钩子经反射 Proxy 注入）
        PromethiumLightBridge.configure();
        initialized = true;
    }

    /** 手动登出双触发防重入冷却（MixinMinecraft.disconnect HEAD 主线程 + listener onDisconnect Netty 线程各自注入）。 */
    private static final long CLEANUP_COOLDOWN_NS = 1_000_000_000L;
    private static final java.util.concurrent.atomic.AtomicLong LAST_CLEANUP_NANO =
            new java.util.concurrent.atomic.AtomicLong(0);

    /**
     * Netty 线程断连（被动断开/服务器踢）时，cleanupMain 经 {@code mc.execute} 排队、
     * 由主线程 pollTask 执行——与主线程 tick 链（onDisconnect → clearLevel TAIL 的
     * {@link #finalizeDisconnectIfTerminal()}）存在帧序竞态：若主线程先完成 finalize
     * （storage close + dirty clearAll）再 pollTask cleanupMain，dump 全被 dirty gate
     * 挡掉（实测 fabric R1 断连 R2 光照 0%）。此标志在 cleanupMain 排队期间置位，
     * 让 clearLevel TAIL 的同步 finalize 让位，等 pollTask 的 cleanupMain 完成、
     * 由断连方随后排队的 finalizeIfTerminal 收尾（15s 兜底防主线程永不执行）。
     */
    private static final java.util.concurrent.atomic.AtomicLong CLEANUP_MAIN_PENDING_NANO =
            new java.util.concurrent.atomic.AtomicLong(0L);

    /**
     * 断开连接时清理（{@code onDisconnect} HEAD，世界拆除之前）。
     * <p>
     * 轻量清理：拉高预算消费加载队列，排空已有 save 队列，取消后台任务。
     * 保留 save 线程和 executor 存活，供随后的 unload / 批量 enqueue 消费。
     * <p>
     * 重量清理（executor shutdown、storage close）推迟到 {@link #finalizeDisconnect()}。
     * <p>
     * 手动登出（PauseScreen 保存并退出）由 {@code MixinMinecraft.disconnect(Screen[,Z])} /
     * {@code clearLevel} HEAD 注入触发，主线程同步执行——必须早于 vanilla 拆除流程：
     * 否则经 {@code mc.execute} 排队会晚于 {@code disconnect} TAIL 的
     * {@code finalizeDisconnect}（dirty clearAll + storage close），dump 全被 dirty gate
     * 挡住（曾实测 {@code queued=0, skippedClean=1452, dirtyLeft=0}，光照/方块不落盘）。
     */
    public static void cleanupOnDisconnect() {
        // 手动登出会经两条路径触发（主线程 disconnect HEAD + Netty onDisconnect），
        // 冷却防二次 dump/flush 与 save 线程停启竞态。
        long now = System.nanoTime();
        long prev = LAST_CLEANUP_NANO.get();
        if (now - prev < CLEANUP_COOLDOWN_NS) {
            Constants.LOG.debug("Hassium: cleanupOnDisconnect skipped (cooldown, last={}ms ago)",
                    (now - prev) / 1_000_000);
            return;
        }
        if (!LAST_CLEANUP_NANO.compareAndSet(prev, now)) {
            return;
        }

        initialized = false;
        finalized.set(false);
        ClientMainThreadBudget.clearJoinBoost();

        Minecraft mc = Minecraft.getInstance();
        if (mc != null && !mc.isSameThread()) {
            // 被动断开（服务器踢/断网）：onDisconnect 在 Netty 线程触发。dump 必须同步等
            // 主线程完成（enqueue 强制主线程序列化），否则异步转移晚于 clearLevel/clearAll 全丢。
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            CLEANUP_MAIN_PENDING_NANO.set(System.nanoTime());
            mc.execute(() -> {
                try {
                    cleanupOnDisconnectMainThread();
                } finally {
                    CLEANUP_MAIN_PENDING_NANO.set(0L);
                    latch.countDown();
                }
            });
            try {
                latch.await(15, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else {
            // 手动登出：MixinMinecraft.disconnect(Screen[,Z])/clearLevel HEAD，主线程同步执行
            cleanupOnDisconnectMainThread();
        }

        // 主线程无关的清理（线程安全容器）
        // ③ 清空加载队列（不再有新区块需要加载）
        ClientCacheLoadQueue.getInstance().clear();
        ViewDistanceExtensionService.getInstance().clearAllRenderOnly();

        // ④ 取消后台任务（但不关闭 executor，save 还需要它）
        HassiumTaskExecutor clientExecutor = HassiumTaskExecutor.getClient();
        if (clientExecutor != null) {
            clientExecutor.cancelAll(TaskCategory.SAFE_TO_CANCEL);
        }

        // ⑤ 清空主线程回调队列 + 玩家坐标缓存
        MainThreadDispatcher.clearClient(false);
        MainThreadDispatcher.clearPlayerPosition();
        PromethiumLightBridge.clear();
        ClientMetadataHandler.clearPendingState();

        // ⑥ finalizeDisconnect：MixinMinecraft disconnect/clearLevel TAIL，或加载器 DISCONNECT 兜底

        Constants.LOG.info("Hassium: Disconnect cleanup done (chunks enqueued + flushed before teardown)");
    }

    /**
     * 主线程上的断连落盘：① 消费加载队列 → ② 排空光照缓冲（消费 = 引擎重算 + markDirty，
     * 否则缓冲在清理时被直接丢弃，已重算块不会入队写盘）→ ③ 批量入队 + 等待写盘完成。
     */
    private static void cleanupOnDisconnectMainThread() {
        // ① 拉高预算，尽可能消费加载队列中的缓存区块
        drainLoadQueueWithRaisedBudget();

        // ② 排空光照缓冲：把「已重算待消费」任务全部落地（每块 = 引擎传播 + markDirty），
        // 预算 2s 封顶；超时残余丢弃（缓存无光 → R2 重算兜底，与既有策略一致）。
        ClientLightBufferQueue.getInstance().drainAll(2_000_000_000L);

        // ③ 批量 enqueue 所有已加载区块并等待写盘完成。
        CacheSaveQueue saveQueue = CacheSaveQueue.getInstance();
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level != null ? mc.level : saveQueue.getTrackedLevel();
        if (level != null) {
            saveQueue.enqueueAllFromLevel(level);
        } else {
            Constants.LOG.warn("Hassium: No ClientLevel available on disconnect — chunks may not be cached");
        }
        saveQueue.flushAsync(5000);

        // ④ 清光照缓冲残余（未消费的直接丢弃——重连后由数据包路径重新提交）
        ClientLightBufferQueue.getInstance().clear();
    }

    /**
     * 断开连接最终清理（vanilla 世界拆除之后）。
     * <p>
     * 由 {@link io.github.limuqy.mc.hassium.mixin.MixinMinecraft}（clearLevel / disconnect TAIL）
     * 与各加载器 DISCONNECT / LoggingOut 事件共同触发；{@code AtomicBoolean} 保证幂等。
     * <p>
     * 排空残余 save 任务，然后关闭所有基础设施。
     */
    public static void finalizeDisconnect() {
        if (!finalized.compareAndSet(false, true)) return;
        // 会话真正终止：停止恢复期预填充（残留 pending 由下次 start 清空）
        RecoveryChunkPrefill.getInstance().stop();
        // ⑩ 登出自动重置指标：恢复中此方法被短路，故仅在真正终止时清零；
        // failover 恢复成功后计数跨断线保留，符合「同一会话」语义。
        // 与冒烟测试 ROUND2 入口的重置保持一致：NetworkStats + DataPlane PoC 计数器一并清。
        if (HassiumConfigService.getInstance().isMetricsAutoResetEnabled()) {
            io.github.limuqy.mc.hassium.metrics.NetworkStats.reset();
            io.github.limuqy.mc.hassium.network.dataplane.DataPlaneClientBundle.resetDataBulkCounters();
        }
        // ⑥ finalDrain：排空拆除阶段产生的 save 任务
        CacheSaveQueue.getInstance().drainRemaining(5000);

        // ⑦ 关闭 executor
        HassiumTaskExecutor.shutdownClient(5000);

        // ⑧ 停止 save 线程
        CacheSaveQueue.getInstance().shutdown();

        // ⑨ 关闭 storage
        ClientHassiumStorage storage = ClientChunkHandler.getClientStorage();
        if (storage != null) {
            try {
                storage.close();
            } catch (Exception e) {
                Constants.LOG.warn("Hassium: Failed to close client storage on disconnect", e);
            }
        }
        ClientEntitySnapshotStore.closeCurrent();
        ClientHassiumStorage.closeSharedDatabase();
        ClientChunkHandler.resetStorage();
        ClientChunkDirtyTracker.clearAll();
    }

    /**
     * 恢复感知的 finalizer：仅在 {@link ClientRecoveryState#isRecovering()} 返回 false
     * 时执行真正的最终清理；否则保留磁盘缓存 / executor / save 线程不动 —— 这些资源
     * 仍需要承接重连后的回首会话（plan §793）。
     * <p>
     * 一旦恢复状态进入 TERMINAL，{@link ClientRecoveryState#consumeTerminalCleanup()}
     * 返回恰好一次 true 来允许 {@link #finalizeDisconnect()} 跑一次性关闭；正常 logout (
     * recovery 状态保持 NONE) 时直接执行 finalize。
     */
    public static void finalizeDisconnectIfTerminal() {
        // Netty 断连路径的 cleanupMain 排队期间让位：主线程 tick 链（onDisconnect →
        // clearLevel TAIL）的同步 finalize 若抢先执行会关 storage + clearAll dirty，
        // 使随后 pollTask 的 cleanupMain dump 全部落空（fabric R1 断连 R2 光照 0%）。
        // 让位后由断连方在 cleanupMain 完成后排队的 finalizeIfTerminal 收尾；
        // 15s 兜底：主线程 pollTask 异常/卡死时不永久丢失 finalize。
        long pendingSince = CLEANUP_MAIN_PENDING_NANO.get();
        if (pendingSince != 0L && System.nanoTime() - pendingSince < 15_000_000_000L) {
            Constants.LOG.debug("Hassium: finalize deferred — cleanup main pending ({}ms ago)",
                    (System.nanoTime() - pendingSince) / 1_000_000);
            return;
        }
        io.github.limuqy.mc.hassium.network.dataplane.ClientRecoveryState state =
                io.github.limuqy.mc.hassium.network.dataplane.ClientRecoveryState.getInstance();
        if (state.isRecovering()) {
            Constants.LOG.debug("Hassium: finalize suppressed — client in recovery phase {}", state.phase());
            return;
        }
        if (state.phase() == io.github.limuqy.mc.hassium.network.dataplane.ClientRecoveryState.Phase.TERMINAL) {
            if (!state.consumeTerminalCleanup()) {
                // 已被并行 caller 一次性消费
                return;
            }
        }
        finalizeDisconnect();
        // 进入 TERMINAL 后 finalize 已落地：恢复终态由后续 begin 重新进入 NONE 的语义清理
    }

    /**
     * 断连时拉高预算，尽可能消费加载队列中的缓存区块。
     * <p>
     * 未 apply 的区块在断连后丢失（可接受），但 apply 过的区块在卸载时会被 save。
     */
    private static void drainLoadQueueWithRaisedBudget() {
        ClientCacheLoadQueue loadQueue = ClientCacheLoadQueue.getInstance();
        int pending = loadQueue.getPendingSize() + loadQueue.getReadySize();
        if (pending <= 0) {
            return;
        }

        // 1.21.11 Fabric：onDisconnect 可能在 Netty IO 线程触发。
        // applyChunkData → handleLevelChunkWithLight 要求主线程，否则
        // RunningOnDifferentThreadException（冒烟 R2 已复现）。非主线程只放弃 apply。
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) {
            Constants.LOG.info(
                    "Hassium: Disconnect drain skipped off main thread (pending={}, thread={})",
                    pending, Thread.currentThread().getName());
            return;
        }

        // 断连时序防护（neoforge 1.20.2+ NPE 根因）：Minecraft 的断开流程先执行
        // connection.close()（ClientPacketListener.level=null），而 mc.level 尚未清空。
        // 此时队列中的缓存区块已无法进入世界（apply 必失败），且 updateLevelChunk 会因
        // this.level 为 null 抛 NPE —— 直接放弃 drain（队列随后由 cleanupOnDisconnect 清空）。
        ClientPacketListener connection = mc.getConnection();
        if (connection == null || connection.getLevel() == null) {
            Constants.LOG.info(
                    "Hassium: Disconnect drain skipped - ClientPacketListener level already torn down (pending={})",
                    pending);
            return;
        }

        Constants.LOG.info("Hassium: Disconnect drain - {} chunks pending, raising budget", pending);

        long deadlineNs = System.nanoTime() + 5_000_000_000L; // 5秒总超时
        while (System.nanoTime() < deadlineNs) {
            int ready = loadQueue.getReadySize();
            int pendingTasks = loadQueue.getPendingSize();
            if (ready == 0 && pendingTasks == 0) {
                break;
            }

            // 消费 ready 队列（主线程 apply + 光照重算）
            if (ready > 0) {
                long frameBudgetNs = 50_000_000L; // 每帧 50ms（正常 ~10ms）
                loadQueue.processQueueUntil(System.nanoTime() + frameBudgetNs);
            }

            // 等待 pending → ready（后台解压 + NBT 重组）
            if (loadQueue.getReadySize() == 0 && loadQueue.getPendingSize() > 0) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        Constants.LOG.info("Hassium: Disconnect drain complete");
    }

    /**
     * 异步初始化客户端缓存系统（M2）
     * <p>
     * ClientHassiumStorage 构造函数中包含热度索引 / section 哈希初始化，
     * 将这部分移到后台线程，避免阻塞主线程。
     * <p>
     * 初始化完成前，元数据包处理会通过同步回退路径。
     */
    private static void initializeCacheAsync() {
        try {
            Minecraft mc = Minecraft.getInstance();

            // 单人游戏不需要客户端缓存
            if (mc.getSingleplayerServer() != null) {
                Constants.LOG.debug("Hassium: Skipping client cache for single-player");
                return;
            }

            if (mc.getConnection() == null || mc.player == null) {
                Constants.LOG.warn("Hassium: Cannot initialize cache - connection={}, player={}",
                        mc.getConnection(), mc.player);
                return;
            }

            final String serverIp = mc.getConnection().getServerData().ip;
            final String cacheAddress = io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity
                    .cacheIdentity(serverIp);
            final Path gameDir = mc.gameDirectory.toPath();
            final String serverId = "server_" + cacheAddress.replaceAll("[^a-zA-Z0-9._-]", "_");

            String dimension = "minecraft:overworld";
            if (mc.player.level() != null) {
                dimension = mc.player.level().dimension()
#if MC_VER < MC_1_21_11
                        .location()
#else
                        .identifier()
#endif
                        .toString();
            }
            final String finalDimension = dimension;

            // M2: 异步初始化存储（热度索引 / section 哈希在后台线程）
            HassiumTaskExecutor executor = HassiumTaskExecutor.getClient();
            if (executor != null && executor.isRunning()) {
                executor.submit(() -> {
                    ClientChunkHandler.initStorage(gameDir, serverId, finalDimension);
                    initializeEntitySnapshots(gameDir, serverId, finalDimension);
                    ClientMetadataHandler.onStorageReady();
                    io.github.limuqy.mc.hassium.network.ClientBloomSyncTracker.onStorageReady();
                    // 超视渲染：清 miss 耗尽状态并强制下一 tick 全环带重扫
                    ViewDistanceExtensionService.getInstance().onClientStorageReady();
                    Constants.LOG.info("Hassium: Async initialized client cache for server {} (connected {}) dim {}",
                            cacheAddress, serverIp, finalDimension);
                }, TaskCategory.BEST_EFFORT);
            } else {
                // 回退：同步初始化
                ClientChunkHandler.initStorage(gameDir, serverId, finalDimension);
                initializeEntitySnapshots(gameDir, serverId, finalDimension);
                ClientMetadataHandler.onStorageReady();
                io.github.limuqy.mc.hassium.network.ClientBloomSyncTracker.onStorageReady();
                ViewDistanceExtensionService.getInstance().onClientStorageReady();
                Constants.LOG.info("Hassium: Initialized client cache for server {} (connected {}) dim {}",
                        cacheAddress, serverIp, finalDimension);
            }
        } catch (Exception e) {
            Constants.LOG.error("Hassium: Failed to initialize client cache", e);
        }
    }

    private static void initializeEntitySnapshots(Path gameDir, String serverId, String dimension) {
        if (!HassiumConfigService.getInstance().isEntitySnapshotsEnabled()) {
            return;
        }
        try {
            ClientEntitySnapshotStore.initialize(gameDir, serverId, dimension);
        } catch (Exception e) {
            Constants.LOG.warn("Hassium: Failed to initialize entity snapshot store for {}", dimension, e);
        }
    }
}
