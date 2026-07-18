package io.github.limuqy.mc.hassium;

import io.github.limuqy.mc.hassium.network.ClientChunkHandler;
import io.github.limuqy.mc.hassium.network.DictionaryManager;
import io.github.limuqy.mc.hassium.network.NeoForgeNetworkManager;
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

    /**
     * 客户端设置事件处理
     */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
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
            var storage = ClientChunkHandler.getClientStorage();
            if (storage != null) {
                storage.close();
            }
            ClientChunkHandler.resetStorage();
            LOGGER.info("Hassium: Client disconnected, cache cleaned up");
        }
    }
}
