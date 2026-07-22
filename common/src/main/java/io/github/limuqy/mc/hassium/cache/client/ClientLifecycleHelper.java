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

        // 进服吞吐加速：临时提高主线程时间预算
        ClientMainThreadBudget.startJoinBoost();

        // M2: 异步初始化存储（热度索引 / section 哈希在后台线程）
        initializeCacheAsync();
        initialized = true;
    }

    /**
     * 断开连接时清理（HEAD 注入，vanilla clearLevel 之前）。
     * <p>
     * 轻量清理：拉高预算消费加载队列，排空已有 save 队列，取消后台任务。
     * 保留 save 线程和 executor 存活——vanilla clearLevel() 会触发所有 chunk 的 unload，
     * unload Mixin 会 enqueue 到 save 队列，由仍在运行的 save 线程消费。
     * <p>
     * 重量清理（executor shutdown、storage close）推迟到 {@link #finalizeDisconnect()}。
     */
    public static void cleanupOnDisconnect() {
        initialized = false;
        finalized.set(false);
        ClientMainThreadBudget.clearJoinBoost();

        // ① 拉高预算，尽可能消费加载队列中的缓存区块
        drainLoadQueueWithRaisedBudget();

        // ② 批量 enqueue 所有已加载区块并 flush。
        // clearLevel() 不保证逐个 unload，不能依赖 unload Mixin 落盘。
        // mc.level 此时可能已 null，优先用 tick 跟踪的 trackedLevel。
        CacheSaveQueue saveQueue = CacheSaveQueue.getInstance();
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level != null ? mc.level : saveQueue.getTrackedLevel();
        if (level != null) {
            saveQueue.enqueueAllFromLevel(level);
        } else {
            Constants.LOG.warn("Hassium: No ClientLevel available on disconnect — chunks may not be cached");
        }
        saveQueue.flushAsync(5000);

        // ③ 清空加载队列（不再有新区块需要加载）
        ClientCacheLoadQueue.getInstance().clear();
        ViewDistanceExtensionService.getInstance().clearAllRenderOnly();

        // ④ 取消后台任务（但不关闭 executor，save 还需要它）
        HassiumTaskExecutor clientExecutor = HassiumTaskExecutor.getClient();
        if (clientExecutor != null) {
            clientExecutor.cancelAll(TaskCategory.SAFE_TO_CANCEL);
        }

        // ⑤ 清空主线程回调队列
        MainThreadDispatcher.clearClient(false);
        ClientLightRecomputeService.clear();
        ClientMetadataHandler.clearPendingState();

        // ⑥ finalizeDisconnect 由 MixinMinecraft.clearLevel TAIL 触发

        Constants.LOG.info("Hassium: Disconnect cleanup done (chunks enqueued + flushed before clearLevel)");
    }

    /**
     * 断开连接最终清理（主线程下一 tick，vanilla clearLevel 之后）。
     * <p>
     * 由 {@link #cleanupOnDisconnect()} 通过 {@link MainThreadDispatcher} 调度，
     * 确保在 vanilla clearLevel() 触发的 chunk unload → enqueue 之后执行。
     * <p>
     * 排空 clearLevel() 触发的 unload → enqueue 的 save 任务，然后关闭所有基础设施。
     */
    public static void finalizeDisconnect() {
        if (!finalized.compareAndSet(false, true)) return;
        // ⑥ finalDrain：排空 clearLevel 产生的 save 任务
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
        Constants.LOG.info("Hassium: Client disconnected, cache cleaned up");
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
            final Path gameDir = mc.gameDirectory.toPath();
            final String serverId = "server_" + serverIp.replaceAll("[^a-zA-Z0-9._-]", "_");

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
                    // 超视渲染：清 miss 耗尽状态并强制下一 tick 全环带重扫
                    ViewDistanceExtensionService.getInstance().onClientStorageReady();
                    Constants.LOG.info("Hassium: Async initialized client cache for server {} dim {}",
                            serverIp, finalDimension);
                }, TaskCategory.BEST_EFFORT);
            } else {
                // 回退：同步初始化
                ClientChunkHandler.initStorage(gameDir, serverId, finalDimension);
                initializeEntitySnapshots(gameDir, serverId, finalDimension);
                ClientMetadataHandler.onStorageReady();
                ViewDistanceExtensionService.getInstance().onClientStorageReady();
                Constants.LOG.info("Hassium: Initialized client cache for server {} dim {}", serverIp, finalDimension);
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
