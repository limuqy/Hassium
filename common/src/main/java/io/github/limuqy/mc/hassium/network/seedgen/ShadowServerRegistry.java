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
            long seed = ClientChunkPipeline.getInstance().getServerSeed();
            DebugLogger.info(DebugLogger.LogType.ASYNC, "[SHADOW] Creating shadow server (seed={})", seed);
            try {
                s = SeedGenLevelCompat.createShadowServer(seed);
                server = s;
                ClientChunkPipeline.getInstance().setShadowServerReady(true);
                DebugLogger.info(DebugLogger.LogType.ASYNC,
                        "[SHADOW] Shadow server ready (seed={})", seed);
                return s;
            } catch (Exception e) {
                failed = true;
                ClientChunkPipeline.getInstance().setShadowServerFailed(true);
                Constants.LOG.error("Hassium: Shadow server creation failed; "
                        + "client cache/lighting/OVD disabled. Disable 'clientCache.hassiumEngineEnabled' to suppress.", e);
                try {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc != null && mc.player != null) {
                        mc.execute(() -> {
                            if (mc.player != null) {
                                mc.player.displayClientMessage(
                                        net.minecraft.network.chat.Component.literal(
                                                "[Hassium] Hassium 引擎启动失败：客户端缓存/超视渲染/SeedGen 已关闭。"
                                                        + "可在配置中关闭 clientCache.hassiumEngineEnabled 抑制本提示。"),
                                        false);
                            }
                        });
                    }
                } catch (Throwable ignored) {
                    // 报错提示失败不影响降级态
                }
                return null;
            }
        }
    }

    /** 断连清理（幂等；重连后允许重建）。 */
    public void shutdown() {
        synchronized (lock) {
            ShadowSeedServer s = server;
            server = null;
            failed = false;
            if (s != null) {
                DebugLogger.info(DebugLogger.LogType.ASYNC, "[SHADOW] Shutting down shadow server");
                s.stopMainLoop();
                SeedGenLevelCompat.shutdown(s);
            }
        }
    }
}
