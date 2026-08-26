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
    /**
     * 本次会话拆除已跑过 {@link #cleanupOnDisconnect()}。{@link #finalizeDisconnectIfTerminal()}
     * 只在此标志为真时关执行器——避免 {@code ConnectScreen.startConnecting} 的空 {@code clearLevel}
     * 把投机影子的 executor 杀掉。
     */
    private static final AtomicBoolean disconnectCleanupArmed = new AtomicBoolean(false);

    private ClientLifecycleHelper() {
    }

    /**
     * 是否有可拆除的客户端会话。{@code ConnectScreen.startConnecting} 一进来就
     * {@code minecraft.clearLevel()}，标题画面 level/player 皆空且尚未 {@link #onLogin()}——
     * 那不是断连，不能 pause 编码、不能关 executor。
     */
    public static boolean hasActiveClientSession() {
        if (initialized) {
            return true;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return false;
        }
        return hasActiveClientSession(false, mc.level != null, mc.player != null);
    }

    /** 测试缝：会话门纯函数（不碰 Minecraft）。 */
    static boolean hasActiveClientSession(boolean sessionInitialized, boolean hasLevel, boolean hasPlayer) {
        return sessionInitialized || hasLevel || hasPlayer;
    }

    /**
     * 玩家登录时初始化缓存系统（新架构：无 HBT1 客户端存储，影子端承担保存；
     * 此处仅初始化后台执行器 + OVD 环带重扫）。
     */
    public static void onLogin() {
        io.github.limuqy.mc.hassium.utils.LoginTiming.markLogin(); // T0b 诊断：handleLogin 时刻（总耗时起点）
        // connect 的 clearLevel 可能已 pauseEncoding；handleLogin 时 revert 已结束，必须放行
        // drainReady / hash 抽干 / unpark（否则 NeoForge 易卡在暂停态 → landed=0）。
        io.github.limuqy.mc.hassium.storage.ShadowStorageManager.resumeEncoding();
        if (!initialized) {
            // 先重建执行器再 unpark：投机 ConnectScreen 不得在 park 实例上抢跑 pump。
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
            io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.onCacheLocationReady();

            // M2: 异步初始化存储（热度索引 / section 哈希在后台线程）
            initializeCacheAsync();
            // 影子端预创建（可能已在 ConnectScreen/CONNECTING 投机启动；此处幂等补齐）。
            // 握手只开 isEnabled() 消费闸；无握手约 3s 后关停投机影子。
            startShadowIfConfigured();
        }
        io.github.limuqy.mc.hassium.network.seedgen.ShadowServerRegistry.getInstance().permitUnparkForLogin();
        io.github.limuqy.mc.hassium.network.seedgen.ShadowServerRegistry.getInstance().getOrCreate();
        if (initialized) {
            return;
        }
        // 网络核心（网关）：进入 CONNECTING 并尽力自动建立 outbound（T4 骨架）
        io.github.limuqy.mc.hassium.network.core.NetworkCore.getInstance().onLogin();
        initialized = true;
    }

    /**
     * 配置开启即装配影子端（与握手/login 并行）。
     * <p>
     * 触发点：{@link io.github.limuqy.mc.hassium.mixin.MixinConnectScreen} /
     * NetworkCore 进入 CONNECTING / {@link #onLogin()}。幂等；无 gameDir/serverIp
     * 时跳过（调用方稍后重试）。
     */
    public static void startShadowIfConfigured() {
        startShadowIfConfigured(null);
    }

    /**
     * @param serverData 连服意图上的 ServerData（ConnectScreen）；null 则回退
     *                   {@link #currentServerIp()}
     */
    public static void startShadowIfConfigured(net.minecraft.client.multiplayer.ServerData serverData) {
        if (!HassiumConfigService.getInstance().isHassiumEngineEnabled()) {
            return;
        }
        HassiumTaskExecutor executor = HassiumTaskExecutor.getClient();
        if (executor == null || !executor.isRunning()) {
            HassiumTaskExecutor.initClient(HassiumTaskExecutor.DEFAULT_CLIENT_THREADS);
        }
        recordCacheLocationForConnect(serverData);
        io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.onCacheLocationReady();
        io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.startShadowSpeculative();
    }

    /** ConnectScreen / 早期连接：用 ServerData.ip 或 currentServerIp 写入 cache 定位。 */
    private static void recordCacheLocationForConnect(net.minecraft.client.multiplayer.ServerData serverData) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                return;
            }
            String serverIp = null;
            if (serverData != null && serverData.ip != null && !serverData.ip.isBlank()) {
                serverIp = serverData.ip;
            }
            if (serverIp == null) {
                serverIp = currentServerIp();
            }
            if (serverIp == null) {
                return;
            }
            final Path gameDir = mc.gameDirectory.toPath();
            final String serverId = io.github.limuqy.mc.hassium.utils.ServerIdUtil.sanitize(serverIp);
            io.github.limuqy.mc.hassium.network.ClientChunkPipeline.getInstance()
                    .setCacheLocation(gameDir, serverId);
        } catch (Exception ignored) {
            // 记录失败不阻断连接
        }
    }

    /**
     * 同步记录 gameDir/serverId（影子端世界目录定位；与 initializeCacheAsync 同口径）。
     * <p>
     * P3 修复：gateway-only 首连时 vanilla 监听器晚于影子创建（{@code mc.getConnection()}
     * 的 serverData 不可用），此前此处静默跳过 → 影子端 worldRoot 回落 TEMP（进程退出
     * 即丢）→ 重连读空盘全量 miss。现经网关会话监听器兜底（{@link #currentServerIp()}），
     * 保证握手完成前 serverId 即已记录——影子创建前置条件（握手完成）恒晚于本记录。
     */
    private static void recordCacheLocationSync() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                return;
            }
            final String serverIp = currentServerIp();
            if (serverIp == null) {
                return;
            }
            final Path gameDir = mc.gameDirectory.toPath();
            // review-fix: T8-27: serverId sanitize 收敛到 utils/ServerIdUtil（三处复制统一）
            final String serverId = io.github.limuqy.mc.hassium.utils.ServerIdUtil.sanitize(serverIp);
            io.github.limuqy.mc.hassium.network.ClientChunkPipeline.getInstance()
                    .setCacheLocation(gameDir, serverId);
        } catch (Exception ignored) {
            // 记录失败不阻断登录
        }
    }

    /**
     * 当前会话服务器地址（影子端 worldRoot 定位用；不可得 → null）。
     * <p>
     * 来源优先级（与 SeedGenLevelCompat.resolveShadowWorldRoot 的 serverIp 兜底同源）：
     * <ol>
     *   <li>{@code mc.getConnection().getServerData().ip}——正常路径（vanilla 监听器已挂载）</li>
     *   <li>{@code NetworkCore.gatewayOnlyLoginListener()} 的 serverData.ip——仅网关登录
     *       期 vanilla 监听器未挂载时的兜底：网关会话从连接意图（MixinConnectScreen）起即持有
     *       用户输入的 ServerData，handleGameProfile 后其监听器为携带该 ServerData 的
     *       {@link ClientPacketListener}</li>
     * </ol>
     */
    public static String currentServerIp() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return null;
        }
        if (mc.getConnection() != null && mc.getConnection().getServerData() != null) {
            String ip = mc.getConnection().getServerData().ip;
            if (ip != null && !ip.isBlank()) {
                return ip;
            }
        }
        // 仅网关登录兜底：网关会话本地壳连接的当前监听器
        net.minecraft.network.PacketListener gateway =
                io.github.limuqy.mc.hassium.network.core.NetworkCore.getInstance().gatewayOnlyLoginListener();
        if (gateway instanceof net.minecraft.client.multiplayer.ClientPacketListener cpl) {
            net.minecraft.client.multiplayer.ServerData sd = cpl.getServerData();
            if (sd != null && sd.ip != null && !sd.ip.isBlank()) {
                return sd.ip;
            }
        }
        return null;
    }

    /** 手动登出双触发防重入冷却（MixinMinecraft.disconnect HEAD 主线程 + listener onDisconnect Netty 线程各自注入）。 */
    private static final long CLEANUP_COOLDOWN_NS = 1_000_000_000L;
    private static final java.util.concurrent.atomic.AtomicLong LAST_CLEANUP_NANO =
            new java.util.concurrent.atomic.AtomicLong(0);

    /**
     * 断开连接时清理（世界拆除之前）：关网关、清客户端队列。
     * <p>
     * 不在这里 {@code pauseEncoding}，也不 park 影子端。1.20.1 Forge 注册表窗口
     * 由 {@code MixinMinecraft.clearLevel} HEAD/TAIL 短暂停编码；落盘在拆除
     * <b>之后</b>（{@link #finalizeDisconnectIfTerminal()}）从还活着的 ChunkMap 刷脏。
     */
    public static void cleanupOnDisconnect() {
        if (!hasActiveClientSession()) {
            Constants.LOG.debug("Hassium: cleanupOnDisconnect skipped (no active client session)");
            return;
        }
        // 手动登出会经两条路径触发（主线程 disconnect HEAD + Netty onDisconnect）。
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
        disconnectCleanupArmed.set(true);
        ClientMainThreadBudget.clearJoinBoost();
        io.github.limuqy.mc.hassium.network.core.NetworkCore.getInstance().onDisconnect();

        ViewDistanceExtensionService.getInstance().clearAllRenderOnly();
        ChunkMeshCompileLog.reset();

        HassiumTaskExecutor clientExecutor = HassiumTaskExecutor.getClient();
        if (clientExecutor != null) {
            clientExecutor.cancelAll(TaskCategory.SAFE_TO_CANCEL);
        }

        MainThreadDispatcher.clearClient(false);
        MainThreadDispatcher.clearPlayerPosition();
        ClientMetadataHandler.clearPendingState();

        Constants.LOG.info("Hassium: Disconnect cleanup done (shadow flush deferred to teardown TAIL)");
    }

    /**
     * 断开连接最终清理（vanilla 世界拆除之后）。
     * <p>
     * 先恢复编码并 park 影子端（调用线程从还活着的 ChunkMap 刷脏落盘），再关客户端 executor。
     */
    public static void finalizeDisconnect() {
        if (!finalized.compareAndSet(false, true)) return;
        disconnectCleanupArmed.set(false);
        io.github.limuqy.mc.hassium.storage.ShadowStorageManager.resumeEncoding();
        io.github.limuqy.mc.hassium.network.seedgen.ShadowServerRegistry.getInstance().parkForReuse();
        if (HassiumConfigService.getInstance().isMetricsAutoResetEnabled()) {
            io.github.limuqy.mc.hassium.metrics.NetworkStats.reset();
            io.github.limuqy.mc.hassium.network.dataplane.DataPlaneClientBundle.resetDataBulkCounters();
        }
        HassiumTaskExecutor.shutdownClient(5000);
        ClientChunkHandler.resetStorage();
    }

    /**
     * 最终清理入口。仅当事先跑过 {@link #cleanupOnDisconnect()}（真实会话拆除）时关执行器；
     * {@code ConnectScreen.startConnecting} 的空 {@code clearLevel} 不得走这条路径。
     */
    public static void finalizeDisconnectIfTerminal() {
        if (!disconnectCleanupArmed.get()) {
            return;
        }
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
