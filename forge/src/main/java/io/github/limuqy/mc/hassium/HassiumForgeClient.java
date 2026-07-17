package io.github.limuqy.mc.hassium;

import io.github.limuqy.mc.hassium.network.ClientChunkHandler;
import io.github.limuqy.mc.hassium.network.DictionaryManager;
import io.github.limuqy.mc.hassium.network.ForgeNetworkManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

#if MC_VER < MC_1_20_2
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.network.NetworkHooks;
#endif

/**
 * Forge 客户端初始化
 *
 * <p>
 * 处理客户端特定的事件和网络初始化。
 * 仅在 1.20.1 生效；1.20.2+ Forge SimpleChannel 网络已禁用，此类编译为空壳。
 * </p>
 */
#if MC_VER < MC_1_20_2
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
#endif
public class HassiumForgeClient {

#if MC_VER < MC_1_20_2
    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/ForgeClient");
    private static final ForgeNetworkManager networkManager = new ForgeNetworkManager();

    /**
     * 客户端设置事件处理
     */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        LOGGER.info("Hassium: Initializing Forge client-side");

        // 加载内置区块字典（打包在 mod 中，不需要从服务端传输）
        DictionaryManager.loadChunkDictionary();

        // 注册到 Forge 事件总线监听玩家网络事件（这些事件不在 Mod 总线）
        MinecraftForge.EVENT_BUS.register(new ClientNetworkEventHandler());

        LOGGER.info("Hassium: Forge client-side initialization complete");
    }

    /**
     * 客户端网络事件处理器
     *
     * <p>
     * ClientPlayerNetworkEvent 事件在 Forge 事件总线而非 Mod 总线，需要单独注册
     * </p>
     */
    public static class ClientNetworkEventHandler {

        /**
         * 玩家登录服务器事件
         */
        @SubscribeEvent
        public void onPlayerLoggedIn(ClientPlayerNetworkEvent.LoggingIn event) {
            var connection = event.getConnection();
            if (connection != null && NetworkHooks.isVanillaConnection(connection)) {
                LOGGER.warn("Hassium: 当前连接被识别为原版/非匹配模组服（如连到了 NeoForge/Fabric 或未装 Hassium 的服）。"
                        + " hassium:main 通道已禁用，客户端统计会保持全 0；请用同加载器的 forge:runServer + forge:runClient 测试。");
                return;
            }
            if (!io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance()
                    .isNetworkCompressionEnabled()) {
                LOGGER.info("Hassium: Client joined server, network disabled — skip handshake");
                return;
            }
            LOGGER.info("Hassium: Client joined server, sending handshake request");
            networkManager.sendHandshakeRequest();
        }

        /**
         * 玩家断开连接事件
         */
        @SubscribeEvent
        public void onPlayerLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
            LOGGER.info("Hassium: Client disconnected from server");
            var storage = ClientChunkHandler.getClientStorage();
            if (storage != null) {
                storage.close();
                LOGGER.info("Hassium: Closed client cache storage");
            }
            // 重置缓存存储，确保下次登入时重新初始化
            ClientChunkHandler.resetStorage();
        }
    }
#endif
}
