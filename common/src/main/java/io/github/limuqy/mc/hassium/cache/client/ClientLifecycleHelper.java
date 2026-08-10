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
     * 玩家登录时初始化缓存系统（新架构：无 HBT1 客户端存储，影子端承担保存；
     * 此处仅初始化后台执行器 + OVD 环带重扫）。
     */
    public static void onLogin() {
        if (initialized) {
            return;
        }
        // 初始化统一后台执行器（chunk.loadThreads 配置已删除，固定默认线程数；虚拟线程模式下忽略）
        HassiumTaskExecutor.initClient(HassiumTaskExecutor.DEFAULT_CLIENT_THREADS);

        // 尽早写入玩家坐标，避免首波 hash/payload 在首 tick 前用 (0,0) 算优先级
        try {
            MainThreadDispatcher.updatePlayerPosition();
        } catch (Exception ignored) {
            // ignore
        }

        // 进服吞吐加速：临时提高主线程时间预算
        ClientMainThreadBudget.startJoinBoost();

        // 影子端世界根定位：gameDir/serverId 同步记录（异步任务与影子端预创建竞态，
        // 影子端装配需要此信息——先于 initializeCacheAsync/onLogin 完成）。
        recordCacheLocationSync();

        // M2: 异步初始化存储（热度索引 / section 哈希在后台线程）
        initializeCacheAsync();
        // 影子端预创建（后台；非网络向功能总开关）：握手到达后启动，失败自动降级。
        // 服务端未装 MOD（无握手）→ 不创建（缓存/OVD/导出保留，光由 packet 自带）。
        io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.onLogin();
        // 网络核心（网关）：进入 CONNECTING 并尽力自动建立 outbound（T4 骨架）
        io.github.limuqy.mc.hassium.network.core.NetworkCore.getInstance().onLogin();
        initialized = true;
    }

    /** 同步记录 gameDir/serverId（影子端世界目录定位；与 initializeCacheAsync 同口径）。 */
    private static void recordCacheLocationSync() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getConnection() == null || mc.getConnection().getServerData() == null) {
                return;
            }
            final String serverIp = mc.getConnection().getServerData().ip;
            final Path gameDir = mc.gameDirectory.toPath();
            // review-fix: T8-27: serverId sanitize 收敛到 utils/ServerIdUtil（三处复制统一）
            final String serverId = io.github.limuqy.mc.hassium.utils.ServerIdUtil.sanitize(serverIp);
            io.github.limuqy.mc.hassium.network.ClientChunkPipeline.getInstance()
                    .setCacheLocation(gameDir, serverId);
        } catch (Exception ignored) {
            // 记录失败不阻断登录
        }
    }

    /** 手动登出双触发防重入冷却（MixinMinecraft.disconnect HEAD 主线程 + listener onDisconnect Netty 线程各自注入）。 */
    private static final long CLEANUP_COOLDOWN_NS = 1_000_000_000L;
    private static final java.util.concurrent.atomic.AtomicLong LAST_CLEANUP_NANO =
            new java.util.concurrent.atomic.AtomicLong(0);

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
        // 网络核心（网关）：关 outbound → IDLE（T4 骨架）
        io.github.limuqy.mc.hassium.network.core.NetworkCore.getInstance().onDisconnect();

        Minecraft mc = Minecraft.getInstance();
        if (mc != null && !mc.isSameThread()) {
            // 被动断开（服务器踢/断网）：onDisconnect 在 Netty 线程触发。
            // 新架构断连落盘由影子端 saveAll 承担（SeedGenLevelCompat.shutdown），
            // 客户端无 dump 队列，无需等主线程。
        }

        // 主线程无关的清理（线程安全容器）
        // ③ 影子端存档由 SeedGenLevelCompat.shutdown(saveAll) 承担，无客户端加载队列
        ViewDistanceExtensionService.getInstance().clearAllRenderOnly();

        // ④ 取消后台任务（但不关闭 executor，save 还需要它）
        HassiumTaskExecutor clientExecutor = HassiumTaskExecutor.getClient();
        if (clientExecutor != null) {
            clientExecutor.cancelAll(TaskCategory.SAFE_TO_CANCEL);
        }

        // ⑤ 清空主线程回调队列 + 玩家坐标缓存
        MainThreadDispatcher.clearClient(false);
        MainThreadDispatcher.clearPlayerPosition();
        ClientMetadataHandler.clearPendingState();

        // ⑥ finalizeDisconnect：MixinMinecraft disconnect/clearLevel TAIL，或加载器 DISCONNECT 兜底

        Constants.LOG.info("Hassium: Disconnect cleanup done (shadow saveAll via SeedGenLevelCompat.shutdown)");
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
        // 会话真正终止：影子端由断连链 SeedGenLevelCompat.shutdown 关闭（含 saveAll）；
        // 无客户端恢复期预填充。
        // ⑩ 登出自动重置指标：恢复中此方法被短路，故仅在真正终止时清零；
        // failover 恢复成功后计数跨断线保留，符合「同一会话」语义。
        // 与冒烟测试 ROUND2 入口的重置保持一致：NetworkStats + DataPlane PoC 计数器一并清。
        if (HassiumConfigService.getInstance().isMetricsAutoResetEnabled()) {
            io.github.limuqy.mc.hassium.metrics.NetworkStats.reset();
            io.github.limuqy.mc.hassium.network.dataplane.DataPlaneClientBundle.resetDataBulkCounters();
        }

        // ⑥ 关闭 executor（影子端已在断连链关闭；无客户端 save 线程/storage）
        HassiumTaskExecutor.shutdownClient(5000);

        // ⑦ 清理会话状态
        ClientChunkHandler.resetStorage();
    }

    /**
     * 最终清理入口（恢复感知已退役：客户端 failover 删除后无条件直接关闭）。
     * <p>
     * 保持方法名/调用点不变：{@link MixinMinecraft} 与各加载器 DISCONNECT 事件
     * 均调用此方法；幂等由 {@link #finalizeDisconnect()} 的 AtomicBoolean 保证。
     */
    public static void finalizeDisconnectIfTerminal() {
        finalizeDisconnect();
    }

    /**
     * 异步初始化客户端缓存系统（新架构：无 HBT1 存储初始化，影子端存档目录由
     * SeedGenLevelCompat 推导；此处仅触发 OVD 环带重扫）。
     */
    private static void initializeCacheAsync() {
        try {
            // 降级态（shadowEngineEnabled=false / 影子端创建失败）：不建 storage，
            // 缓存读回/写盘/导出经 getClientStorage()==null 全 gate；服务端未装 MOD 时保留。
            if (!HassiumConfigService.getInstance().isClientFeatureGateOpen()) {
                Constants.LOG.info("Hassium: Client cache disabled (shadow engine gate closed)");
                return;
            }
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
            // review-fix: T8-27: serverId sanitize 收敛到 utils/ServerIdUtil
            final String serverId = io.github.limuqy.mc.hassium.utils.ServerIdUtil.sanitize(serverIp);

            // 新架构：客户端无 HBT1 存储；影子端存档目录由 SeedGenLevelCompat 按
            // gameDir/serverId 推导（hassium_cache/<serverId>/world），此处仅保留
            // OVD 环带重扫（影子模式就绪后强制补扫）。
            ViewDistanceExtensionService.getInstance().onClientStorageReady();
        } catch (Exception e) {
            Constants.LOG.error("Hassium: Failed to initialize client cache", e);
        }
    }
}
