package io.github.limuqy.mc.hassium;

import io.github.limuqy.mc.hassium.cache.client.ClientLifecycleHelper;
import io.github.limuqy.mc.hassium.client.ClientSmokeTest;
import io.github.limuqy.mc.hassium.client.NeoForgeControlReconnectLauncher;
import io.github.limuqy.mc.hassium.network.DictionaryManager;
import io.github.limuqy.mc.hassium.network.NeoForgeNetworkManager;
import io.github.limuqy.mc.hassium.network.dataplane.ClientRecoveryState;
import io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity;
import io.github.limuqy.mc.hassium.network.dataplane.ControlEndpoint;
import io.github.limuqy.mc.hassium.network.dataplane.ControlReconnectOrchestrator;
import io.github.limuqy.mc.hassium.network.dataplane.DataPlaneClientLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

#if MC_VER < MC_1_20_2
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.network.NetworkHooks;
#elif MC_VER < MC_1_20_5
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
#else
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
#endif

/**
 * NeoForge 客户端初始化
 * 
 * <p>
 * 处理客户端特定的事件和网络初始化。
 * 侧边隔离由 {@code @EventBusSubscriber(value = Dist.CLIENT)} 负责，勿再使用已失效的 {@code @OnlyIn}。
 */
#if MC_VER < MC_1_20_5
@Mod.EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
#elif MC_VER < MC_1_21_6
@EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
#else
// 1.21.6+：bus 参数已移除，FML 按事件类型自动挂到 Mod / Game 总线
@EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT)
#endif
public class HassiumNeoForgeClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/NeoForgeClient");
    private static final NeoForgeNetworkManager networkManager = new NeoForgeNetworkManager();

    // Task 9 — control-reconnect orchestrator singleton; launcher 与 Fabric 同形（vanilla ConnectScreen 入口）。
    // advertised candidates are populated from the S2C handshake tail; bootstrap active comes
    // from the prior live Connection's remote address on disconnect.
    private static volatile ControlReconnectOrchestrator reconnectOrchestrator;

    /** 客户端 access — NeoForgeNetworkManager S2C handshake tail handler 用以灌候选与 onHandshakeAccepted 回调。 */
    public static ControlReconnectOrchestrator reconnectOrchestrator() {
        return reconnectOrchestrator;
    }

    /**
     * 客户端设置事件处理
     */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // Task 9 — control-reconnect orchestrator (singleton; launcher wired here, candidates
        // populated from S2C handshake tails as advertised backup endpoints arrive).
        if (reconnectOrchestrator == null) {
            ClientFailoverIdentity.initialize(
                    io.github.limuqy.mc.hassium.platform.Services.PLATFORM.getConfigDirectory()
                            .resolve("hassium").resolve("failover-endpoints.properties"),
                    new NeoForgeControlReconnectLauncher());
            reconnectOrchestrator = ClientFailoverIdentity.orchestrator();
        }

        ClientSmokeTest.initIfEnabled();

        // 加载内置区块字典
        DictionaryManager.loadChunkDictionary();

        // 模组列表「配置」按钮
        event.enqueueWork(io.github.limuqy.mc.hassium.client.HassiumNeoForgeConfigScreens::register);

        // 注册到 Forge 事件总线监听玩家网络事件（这些事件不在 Mod 总线）
#if MC_VER < MC_1_20_2
        MinecraftForge.EVENT_BUS.register(new ClientNetworkEventHandler());
#else
        NeoForge.EVENT_BUS.register(new ClientNetworkEventHandler());
#endif

        LOGGER.info("Hassium: NeoForge client-side initialization complete");
    }

    /**
     * 客户端网络事件处理器
     * 
     * <p>
     * ClientPlayerNetworkEvent 事件在 Forge 事件总线而非 Mod 总线，需要单独注册
     */
    public static class ClientNetworkEventHandler {

        /**
         * 玩家登录服务器事件
         */
        @SubscribeEvent
        public void onPlayerLoggedIn(ClientPlayerNetworkEvent.LoggingIn event) {
#if MC_VER < MC_1_20_2
            var connection = event.getConnection();
            if (connection != null && NetworkHooks.isVanillaConnection(connection)) {
                LOGGER.warn("Hassium: 当前连接被识别为原版/非匹配模组服。"
                        + " hassium:main 通道已禁用，客户端统计会保持全 0；请用同加载器的客户端+服务端测试。");
                return;
            }
#endif
            // 段 C 门控关闭网络时通道未注册，发握手会导致断连
            if (!io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance()
                    .isNetworkCompressionEnabled()) {
                LOGGER.debug("Hassium: Client joined server, network disabled — skip handshake");
                return;
            }
            networkManager.sendHandshakeRequest();
        }

        /**
         * 玩家断开连接事件
         */
        @SubscribeEvent
        public void onPlayerLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
            // Task 9 — 客户端断开时先让 orchestrator 把恢复态开起来，然后 cleanup / finalize。
            // 恢复中 finalizeDisconnectIfTerminal 会短路 one-time terminal；磁盘缓存/executor
            // 留待下一候选握手成功 markRecovered；候选耗尽时 orchestrator 自身触发一次 terminal.
            try {
                ControlReconnectOrchestrator orch = reconnectOrchestrator;
                if (orch != null && orch.hasAdvertisedCandidates()) {
                    ControlEndpoint active = activeControlEndpoint(event);
                    ClientFailoverIdentity.onPrimaryDisconnected(active, "channel_inactive");
                    // 仅当 orchestrator 仍持有可 launch 的恢复态时进入 ClientRecoveryState；
                    // 候选耗尽走 terminal，不再 begin（避免 stopUdp(keepLease) 空 bundle）。
                    if (orch.isRecovering()) {
                        ClientRecoveryState.getInstance().begin(
                                java.lang.System.currentTimeMillis() + 60_000L);
                    }
                }
            } catch (Throwable t) {
                LOGGER.warn("Hassium: reconnect orchestrator begin failed", t);
            }

            ClientLifecycleHelper.cleanupOnDisconnect();
            // 关闭 UDP 数据面 bundle；恢复态下用温和关闭（保留磁盘缓存/executor）
            try {
                DataPlaneClientLifecycle.getInstance().stopUdp(
                        ClientRecoveryState.getInstance().isRecovering());
            } catch (Throwable ignored) {}
            // 延后到下一 tick：等 Minecraft.disconnect / clearLevel 拆除完成；与 Mixin TAIL 幂等
            // 但仅在非恢复态下真跑 finalize；恢复态交给 MixinMinecraft TAIL 在 markTerminal 后触发一次
            net.minecraft.client.Minecraft.getInstance().execute(ClientLifecycleHelper::finalizeDisconnectIfTerminal);
        }
    }

    /** 从 LoggingOut 事件的当前 Connection remote 提取主控 endpoint，作为 orchestrator 的 bootstrap active 候选。 */
    private static ControlEndpoint activeControlEndpoint(ClientPlayerNetworkEvent.LoggingOut event) {
        try {
            net.minecraft.network.Connection conn = event.getConnection();
            if (conn == null) {
                return null;
            }
            java.net.SocketAddress addr = conn.getRemoteAddress();
            if (addr instanceof java.net.InetSocketAddress isa) {
                return new ControlEndpoint(isa.getHostString(), isa.getPort(), /*priority*/ 0);
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
