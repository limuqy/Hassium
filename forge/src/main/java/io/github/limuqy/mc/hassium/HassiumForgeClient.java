package io.github.limuqy.mc.hassium;

import io.github.limuqy.mc.hassium.cache.client.ClientLifecycleHelper;
import io.github.limuqy.mc.hassium.client.ClientSmokeTest;
import io.github.limuqy.mc.hassium.client.ForgeControlReconnectLauncher;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.DictionaryManager;
import io.github.limuqy.mc.hassium.network.ForgeNetworkManager;
import io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

#if MC_VER < MC_1_20_2
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.NetworkHooks;
#elif MC_VER > MC_1_21_5
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.network.ConnectionType;
import net.minecraftforge.network.NetworkContext;
#else
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.ConnectionType;
import net.minecraftforge.network.NetworkContext;
#endif

/**
 * Forge 客户端初始化：加载字典、进服握手、断开时清理缓存。
 * <p>
 * 客户端统计依赖握手成功后的元数据/压缩区块路径；未握手时 {@code /hassiumc stats} 会全为 0。
 */
#if MC_VER < MC_1_20_2
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
#else
@Mod.EventBusSubscriber(modid = Constants.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
#endif
public class HassiumForgeClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/ForgeClient");
    private static final ForgeNetworkManager networkManager = new ForgeNetworkManager();

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        ClientFailoverIdentity.initialize(
                io.github.limuqy.mc.hassium.platform.Services.PLATFORM.getConfigDirectory()
                        .resolve("hassium").resolve("failover-endpoints.properties"),
                new ForgeControlReconnectLauncher());
        ClientSmokeTest.initIfEnabled();

        DictionaryManager.loadChunkDictionary();

        event.enqueueWork(io.github.limuqy.mc.hassium.client.HassiumForgeConfigScreens::register);

        // ClientPlayerNetworkEvent 在 Forge 游戏总线，不在 Mod 总线
        MinecraftForge.EVENT_BUS.register(new ClientNetworkEventHandler());

        LOGGER.info("Hassium: Forge client-side initialization complete");
    }

    /**
     * 客户端网络事件（LoggingIn / LoggingOut）
     */
    public static class ClientNetworkEventHandler {

        @SubscribeEvent
        public void onPlayerLoggedIn(ClientPlayerNetworkEvent.LoggingIn event) {
            var connection = event.getConnection();
#if MC_VER < MC_1_20_2
            if (connection != null && NetworkHooks.isVanillaConnection(connection)) {
                LOGGER.warn("Hassium: 当前连接被识别为原版/非匹配模组服。"
                        + " hassium:main 通道已禁用，客户端统计会保持全 0；请用同加载器的 forge:runServer + forge:runClient 测试。");
                return;
            }
#else
            if (connection != null
                    && NetworkContext.get(connection).getType() == ConnectionType.VANILLA) {
                LOGGER.warn("Hassium: 当前连接被识别为原版连接。"
                        + " hassium:main 通道不可用，客户端统计会保持全 0；请确认服务端已装 Hassium。");
                return;
            }
#endif
            if (!HassiumConfigService.getInstance().isNetworkCompressionEnabled()) {
                LOGGER.debug("Hassium: Client joined server, network disabled — skip handshake");
                return;
            }
            if (ForgeNetworkManager.CHANNEL == null) {
                LOGGER.warn("Hassium: CHANNEL not registered yet, skip handshake");
                return;
            }
            networkManager.sendHandshakeRequest();
        }

        @SubscribeEvent
        public void onPlayerLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
            // 对齐 fabric/neoforge 的 DISCONNECT 回调：仅在 orchestrator 持有可 launch 的候选时
            // 才进入 failover 恢复态（smoke 主动断开无候选 → 不 launch）。
            // 关键：Forge 的 LoggingOut 事件跑在 Minecraft.disconnect() 栈内，而
            // ForgeControlReconnectLauncher.connect 的 mc.execute 在主线程是同步执行的，
            // startConnecting 内部又会 disconnect() → firePlayerLogout → 无限递归（StackOverflow）。
            // 因此恢复触发必须脱离当前栈：新线程 sleep 后再回主线程执行。
            try {
                io.github.limuqy.mc.hassium.network.dataplane.ControlReconnectOrchestrator orch =
                        io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity
                                .orchestrator();
                if (orch != null && orch.hasAdvertisedCandidates()) {
                    io.github.limuqy.mc.hassium.network.dataplane.ControlEndpoint active = null;
                    try {
                        var conn = event.getConnection();
                        if (conn != null
                                && conn.getRemoteAddress() instanceof java.net.InetSocketAddress isa) {
                            active = new io.github.limuqy.mc.hassium.network.dataplane.ControlEndpoint(
                                    isa.getHostString(), isa.getPort(), 0);
                        }
                    } catch (Throwable ignored) {}
                    final io.github.limuqy.mc.hassium.network.dataplane.ControlEndpoint act = active;
                    new Thread(() -> {
                        try {
                            Thread.sleep(200L);
                        } catch (InterruptedException ignored) {}
                        try {
                            ClientFailoverIdentity.onPrimaryDisconnected(act, "channel_inactive");
                            if (orch.isRecovering()) {
                                io.github.limuqy.mc.hassium.network.dataplane.ClientRecoveryState
                                        .getInstance().begin(java.lang.System.currentTimeMillis() + 60_000L);
                            }
                        } catch (Throwable t) {
                            LOGGER.warn("Hassium: reconnect orchestrator begin failed", t);
                        }
                    }, "hassium-failover-defer").start();
                }
            } catch (Throwable t) {
                LOGGER.warn("Hassium: reconnect orchestrator begin failed", t);
            }
            // cleanup + UDP 数据面关闭 + 延后 finalize（见上方恢复触发注释）
            ClientLifecycleHelper.cleanupOnDisconnect();
            // 关闭 UDP 数据面 bundle；恢复态下用温和关闭（保留磁盘缓存/executor）
            try {
                io.github.limuqy.mc.hassium.network.dataplane.DataPlaneClientLifecycle
                        .getInstance().stopUdp(
                                io.github.limuqy.mc.hassium.network.dataplane.ClientRecoveryState
                                        .getInstance().isRecovering());
            } catch (Throwable ignored) {}
            // 延后到下一 tick：等世界拆除；与 MixinMinecraft TAIL 幂等兜底；
            // 恢复态下短路 finalize（磁盘缓存/executor 留待重连回首）
            net.minecraft.client.Minecraft.getInstance()
                    .execute(ClientLifecycleHelper::finalizeDisconnectIfTerminal);
        }
    }
}
