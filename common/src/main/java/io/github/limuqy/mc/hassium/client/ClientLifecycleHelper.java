package io.github.limuqy.mc.hassium.client;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.concurrent.HassiumTaskExecutor;
import io.github.limuqy.mc.hassium.concurrent.MainThreadDispatcher;
import io.github.limuqy.mc.hassium.concurrent.TaskCategory;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.client.ClientChunkHandler;
import io.github.limuqy.mc.hassium.client.ClientMetadataHandler;
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
    /**
     * {@link #onStartConnecting} 到 {@link #onLogin} / 连服失败之间为真。
     * 用于取消连服时 park 投机影子，避免标题画面常驻一份 WorldLoader 实例。
     */
    private static volatile boolean connectInFlight = false;
    private static final AtomicBoolean finalized = new AtomicBoolean(false);
    /** 本次会话拆除的影子端保存是否已跑（{@link #saveShadowOnDisconnect()} 幂等门）。 */
    private static final AtomicBoolean shadowSaveDone = new AtomicBoolean(false);
    /**
     * 本次会话拆除已跑过 {@link #cleanupOnDisconnect()}。{@link #finalizeDisconnectIfTerminal()}
     * 只在此标志为真时关执行器——避免 {@code ConnectScreen.startConnecting} 的空 {@code clearLevel}
     * 把投机影子的 executor 杀掉。
     */
    private static final AtomicBoolean disconnectCleanupArmed = new AtomicBoolean(false);

    /**
     * 断连时同步等影子端保存结束的上界（毫秒）。
     * <p>
     * 实测正常关停是毫秒级（saveAll 0–36ms；过远柱已由 unloadChunk→flushColumn 提前落盘），
     * 这里给足余量覆盖「视距内大量脏柱 + 磁盘慢」的最坏情况；超时即放弃等待并记 warn
     * （数据靠下次会话 compare miss 重推），不无限拖住退出。
     */
    private static final long DISCONNECT_SAVE_WAIT_MS = 10_000L;

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
     * 此处仅初始化后台执行器 + 影子虚拟玩家 tracking 会话）。
     */
    public static void onLogin() {
        io.github.limuqy.mc.hassium.utils.LoginTiming.markLogin(); // T0b 诊断：handleLogin 时刻（总耗时起点）
        connectInFlight = false;
        // 单人/集成服本机：纯原版路径——不启影子、不重置 tracking（避免 cancel 原版区块）
        if (isLocalIntegratedSession()) {
            if (!initialized) {
                ClientMainThreadBudget.startJoinBoost();
                initialized = true;
            }
            return;
        }
        JoinWorldFocus.updateFromClient();
        // 影子虚拟玩家 tracking 会话随新连接重置：R2 复用 park 实例时旧虚拟玩家仍在
        // 影子世界且位置未变 → 不会重新选柱 → R2 黑洞；登录即重建会话重新 tracking
        io.github.limuqy.mc.hassium.shadow.track.ShadowTrackingSession.reset();
        // 新会话列级记账去重必须清零：否则 R1 的 accountedIngress 会挡住 R2 UNCHANGED
        // 的 publishCachedChunk / accountCacheFullHit（R2 全命中恒 0）。
        io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute.resetRequestDedupForReconnect();
        // connect 的 clearLevel 可能已 pauseEncoding；handleLogin 时 revert 已结束，必须放行
        // drainReady / hash 抽干 / unpark（否则 NeoForge 易卡在暂停态 → landed=0）。
        io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageManager.resumeEncoding();
        if (!initialized) {
            // 连服投机已 ensureClient 时不得 shutdown 重建——会拆掉正在 WorldLoader 的 getOrCreate。
            HassiumTaskExecutor.ensureClient();

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
            io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute.onCacheLocationReady();
            io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry.getInstance().permitUnparkForLogin();
            io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute.onLogin();
            // handleLogin 当下就发布位置：不等下一客户端 tick 才武装虚拟玩家。
            try {
                io.github.limuqy.mc.hassium.shadow.track.ShadowTrackingSession
                        .onClientTick(Minecraft.getInstance());
            } catch (Exception ignored) {
                // 影子未就绪时 skip；后续 tick 会再发布
            }

            // M2: 异步初始化存储（热度索引 / section 哈希在后台线程）
            // 影子端只在 Hassium 能力握手确认后启动；原版服务端保持纯原版客户端路径。
        }
        if (initialized) {
            return;
        }
        // 单 25565 原版基线：客户端保持原版连接。
        initialized = true;
    }

    /** 集成服本机会话（单人；局域网主机本机 memory 也走此门，远程玩家才是真服路径）。 */
    private static boolean isLocalIntegratedSession() {
        try {
            Minecraft mc = Minecraft.getInstance();
            return mc != null && mc.getSingleplayerServer() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 配置开启即装配影子端（与握手/login 并行）。
     * <p>
     * 触发点：{@link io.github.limuqy.mc.hassium.mixin.client.MixinConnectScreen} /
     * {@link #onLogin()} / play_init。幂等；无 gameDir/serverIp 时跳过（调用方稍后重试）。
     */
    public static void startShadowIfConfigured() {
        startShadowIfConfigured(null);
    }

    /**
     * {@code ConnectScreen.startConnecting} TAIL：ensure 执行器并投机 WorldLoader。
     * SeedGen 开着时不在连服瞬间装配（seed=0 会随后重建，重叠无收益）。
     */
    public static void onStartConnecting(net.minecraft.client.multiplayer.ServerData serverData) {
        connectInFlight = true;
        io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry.getInstance()
                .beginSpeculativeConnect();
        if (!HassiumConfigService.getInstance().isHassiumEngineEnabled()) {
            return;
        }
        HassiumTaskExecutor.ensureClient();
        recordCacheLocationForConnect(serverData);
        if (HassiumConfigService.getInstance().isClientSeedGenEnabled()) {
            io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute.onCacheLocationReady();
            return;
        }
        startShadowIfConfigured(serverData);
    }

    /**
     * 离开 ConnectScreen。进入地形加载屏则放行；取消/失败则 park 投机实例。
     */
    public static void onConnectScreenDismissed(net.minecraft.client.gui.screens.Screen next) {
        if (io.github.limuqy.mc.hassium.compat.ClientLoadingScreenCompat.isTerrainLoadingScreen(next)) {
            return;
        }
        if (initialized) {
            connectInFlight = false;
            return;
        }
        connectInFlight = false;
        io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry.getInstance()
                .abandonSpeculativeConnect();
    }

    static boolean isConnectInFlight() {
        return connectInFlight;
    }

    /**
     * @param serverData 连服意图上的 ServerData（ConnectScreen）；null 则回退
     *                   {@link #currentServerIp()}
     */
    public static void startShadowIfConfigured(net.minecraft.client.multiplayer.ServerData serverData) {
        if (!HassiumConfigService.getInstance().isHassiumEngineEnabled()) {
            return;
        }
        // 单人集成服：不启影子（原版 memory 路径）
        if (isLocalIntegratedSession()) {
            return;
        }
        recordCacheLocationForConnect(serverData);
        io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute.onCacheLocationReady();
        io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute.startShadowSpeculative();
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
            io.github.limuqy.mc.hassium.client.ClientChunkPipeline.getInstance()
                    .setCacheLocation(gameDir, serverId);
        } catch (Exception ignored) {
            // 记录失败不阻断连接
        }
    }

    /**
     * 同步记录 gameDir/serverId（影子端世界目录定位；与 initializeCacheAsync 同口径）。
     * <p>
     * P3 修复：首连时 vanilla 监听器可能晚于影子创建（{@code mc.getConnection()}
     * 的 serverData 不可用），此前此处静默跳过 → 影子端 worldRoot 回落 TEMP（进程退出
     * 即丢）→ 重连读空盘全量 miss。现经 {@link #currentServerIp()} 兜底，
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
            io.github.limuqy.mc.hassium.client.ClientChunkPipeline.getInstance()
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
        return null;
    }

    /** 手动登出双触发防重入冷却（MixinMinecraft.disconnect HEAD 主线程 + listener onDisconnect Netty 线程各自注入）。 */
    private static final long CLEANUP_COOLDOWN_NS = 1_000_000_000L;
    private static final java.util.concurrent.atomic.AtomicLong LAST_CLEANUP_NANO =
            new java.util.concurrent.atomic.AtomicLong(0);

    /**
     * 断开连接时清理（世界拆除之前）：清客户端队列。
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
        shadowSaveDone.set(false);
        disconnectCleanupArmed.set(true);
        connectInFlight = false;
        JoinWorldFocus.clear();
        ClientMainThreadBudget.clearJoinBoost();
        io.github.limuqy.mc.hassium.protocol.handshake.ClientLoginNegotiation.clear();
        io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute.resetRequestDedupForReconnect();
        io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute.clearDiskPublishInFlight();
        io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute.clearDiskReadEmpty();
        io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate.clearAll();
        io.github.limuqy.mc.hassium.protocol.PullResponseDecodeQueue.discard();

        ChunkMeshCompileLog.reset();

        HassiumTaskExecutor clientExecutor = HassiumTaskExecutor.getClient();
        if (clientExecutor != null) {
            clientExecutor.cancelAll(TaskCategory.SAFE_TO_CANCEL);
        }

        MainThreadDispatcher.clearClient(false);
        MainThreadDispatcher.clearPlayerPosition();

        Constants.LOG.info("Hassium: Disconnect cleanup done (shadow flush deferred to teardown TAIL)");
    }

    /**
     * 断开连接清理（幂等；可能在 {@code clearLevel} 中途或 TAIL 被调用）。
     * <p>
     * 只做与影子保存无关的收尾：指标复位、关客户端 executor、清客户端 chunk 存储。
     * <p>
     * <b>不在此碰影子端保存</b>：1.20.1 forge/neoforge 此处仍处注册表重建窗口内
     * （{@code revertToFrozen} 未结束、编码暂停），此时启动 saver 会因窗口跳过编码而丢脏柱。
     * 保存必须等 {@code clearLevel} TAIL 关窗后，由
     * {@link #saveShadowOnDisconnect()} 在**主线程同步**执行。
     */
    public static void finalizeDisconnect() {
        if (!finalized.compareAndSet(false, true)) return;
        disconnectCleanupArmed.set(false);
        if (HassiumConfigService.getInstance().isMetricsAutoResetEnabled()) {
            io.github.limuqy.mc.hassium.metrics.NetworkStats.reset();
        }
        HassiumTaskExecutor.shutdownClient(5000);
        ClientChunkHandler.resetStorage();
    }

    /**
     * 断连保存：**主线程同步**跑影子端保存（有界），须在 {@code clearLevel} TAIL
     * （注册表重建窗口已关、编码已放行）之后调用。
     * <p>
     * 原实现把「启动 saver + 同步等待」放在 {@code finalizeDisconnect}（1.20.1 会由
     * {@code onPlayerLoggedOut} 在 {@code clearLevel} 中途同步执行），于是 Render 线程在
     * <b>持注册表写锁</b>期间等 saver：saver 等 {@code flushLock}，在途 flush 任务持
     * {@code flushLock} 等注册表读锁 —— ABBA，退出卡满 10s 等待超时。现在改为：
     * 窗口用「跳过」而非锁（见 {@code ShadowRegistryWindow}），保存挪到关窗之后的主线程，
     * 结构上不存在环。
     * <p>
     * {@code finalized} 门：仅真实会话拆除（{@link #finalizeDisconnect()} 跑过）才保存；
     * ConnectScreen 空 {@code clearLevel} 直接返回。等待有界（{@link #DISCONNECT_SAVE_WAIT_MS}），
     * 超时即放弃并记 warn（数据靠下次会话 compare miss 重推），不无限拖住退出。
     */
    public static void saveShadowOnDisconnect() {
        if (!finalized.get()) {
            return;
        }
        if (!shadowSaveDone.compareAndSet(false, true)) {
            return;
        }
        // 关窗 + 放行编码：保存要真正编码脏柱，必须在 revert 窗口结束后。
        io.github.limuqy.mc.hassium.compat.ShadowServerCompat.closeRegistryWindow();
        io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageManager.resumeEncoding();
        io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry registry =
                io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry.getInstance();
        registry.parkForReuse();
        registry.awaitShutdownComplete(DISCONNECT_SAVE_WAIT_MS);
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

}
