package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.concurrent.HassiumTaskExecutor;
import io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher;
import io.github.limuqy.mc.hassium.concurrent.TaskCategory;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.ClientChunkHandler;
import io.github.limuqy.mc.hassium.network.ClientMetadataHandler;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;

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

    private ClientLifecycleHelper() {
    }

    /**
     * 玩家登录时初始化缓存系统。
     * <p>
     * M2: 将 ClientHassiumStorage 创建（含 SQLite 初始化）异步化，
     * handleLogin 主线程不再阻塞在数据库初始化上。
     */
    public static void onLogin() {
        if (initialized) {
            return;
        }
        // 初始化统一后台执行器（平台线程数由配置文件控制，虚拟线程模式下忽略）
        int threads = HassiumConfigService.getInstance().getClientChunkLoadThreads();
        HassiumTaskExecutor.initClient(threads);

        // 进服吞吐加速：临时提高主线程时间预算
        ClientMainThreadBudget.startJoinBoost();

        // M2: 异步初始化存储（SQLite 初始化在后台线程）
        initializeCacheAsync();
        initialized = true;
    }

    /**
     * 断开连接时清理客户端缓存状态。
     * <p>
     * 必须在 clearLevel() 之前刷新缓存保存队列，否则 level 被置空后保存会失败。
     * <p>
     * 时序：flushAsync → cancelAll(SAFE_TO_CANCEL) → shutdownClient → clearClient
     * <p>
     * S2: flushAsync 替代同步 flush，避免主线程阻塞。
     */
    public static void cleanupOnDisconnect() {
        initialized = false;
        ClientMainThreadBudget.clearJoinBoost();

        // S2: 异步刷新缓存保存队列（最多等待 3 秒）
        CacheSaveQueue.getInstance().flushAsync(3000);
        CacheSaveQueue.getInstance().clear();
        ClientCacheLoadQueue.getInstance().clear();

        // 取消可安全丢弃的后台任务（光照扫描、网络解压等）
        HassiumTaskExecutor clientExecutor = HassiumTaskExecutor.getClient();
        if (clientExecutor != null) {
            clientExecutor.cancelAll(TaskCategory.SAFE_TO_CANCEL);
        }

        // 关闭统一后台执行器，等待最多 5 秒完成 MISSION_CRITICAL + BEST_EFFORT
        HassiumTaskExecutor.shutdownClient(5000);

        // 清空主线程回调队列（不保留任何任务）
        MainThreadDispatcher.clearClient(false);

        ClientMetadataHandler.clearPendingState();
        ClientHassiumStorage.closeSharedDatabase();
        ClientChunkHandler.resetStorage();
        Constants.LOG.debug("Hassium: Cleared client cache state on disconnect");
    }

    /**
     * 异步初始化客户端缓存系统（M2）
     * <p>
     * ClientHassiumStorage 构造函数中包含 SQLite 初始化（~5-20ms），
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

            // M2: 异步初始化存储（SQLite 在后台线程）
            HassiumTaskExecutor executor = HassiumTaskExecutor.getClient();
            if (executor != null && executor.isRunning()) {
                executor.submit(() -> {
                    ClientChunkHandler.initStorage(gameDir, serverId, finalDimension);
                    ClientMetadataHandler.onStorageReady();
                    Constants.LOG.info("Hassium: Async initialized client cache for server {} dim {}",
                            serverIp, finalDimension);
                }, TaskCategory.BEST_EFFORT);
            } else {
                // 回退：同步初始化
                ClientChunkHandler.initStorage(gameDir, serverId, finalDimension);
                ClientMetadataHandler.onStorageReady();
                Constants.LOG.info("Hassium: Initialized client cache for server {} dim {}", serverIp, finalDimension);
            }
        } catch (Exception e) {
            Constants.LOG.error("Hassium: Failed to initialize client cache", e);
        }
    }
}
