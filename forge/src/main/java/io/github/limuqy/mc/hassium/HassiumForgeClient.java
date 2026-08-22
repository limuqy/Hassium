package io.github.limuqy.mc.hassium;

import io.github.limuqy.mc.hassium.cache.client.ClientLifecycleHelper;
import io.github.limuqy.mc.hassium.client.ClientSmokeTest;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.DictionaryManager;
import io.github.limuqy.mc.hassium.network.dataplane.DataPlaneClientLifecycle;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

#if MC_VER < MC_1_21_1
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
 * Forge 客户端初始化：加载字典、断开时清理缓存。
 * <p>
 * T6：客户端 failover 已退役——不再初始化控制面重连单例 / 不再发送握手请求
 * （新架构客户端不发 vanilla 握手，服务端旧握手链休眠）；LoggingOut 直接全量清理。
 * <p>
 * 客户端统计依赖握手成功后的元数据/压缩区块路径；未握手时 {@code /hassiumc stats} 会全为 0。
 */
#if MC_VER < MC_1_21_1
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
#else
@Mod.EventBusSubscriber(modid = Constants.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
#endif
public class HassiumForgeClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/ForgeClient");

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
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
#if MC_VER < MC_1_21_1
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
            // 新架构（T5/T6）：客户端不再主动发送 Hassium 握手；网关握手经
            // MixinConnection.relayLoginToGateway → NetworkCore 中继，此处无需操作。
        }

        @SubscribeEvent
        public void onPlayerLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
            // 客户端 failover 已退役（T6）：无恢复态 begin / orchestrator 轮转，
            // 直接全量清理 + 关闭 UDP 数据面 + 延后 finalize（与 Mixin TAIL 幂等）。
            ClientLifecycleHelper.cleanupOnDisconnect();
            try {
                DataPlaneClientLifecycle.getInstance().stopUdp(false);
            } catch (Throwable ignored) {
                // UDP 数据面可选；关闭失败不得阻断断连清理
            }
            // 延后到下一 tick：等世界拆除；与 MixinMinecraft TAIL 幂等兜底
            net.minecraft.client.Minecraft.getInstance()
                    .execute(ClientLifecycleHelper::finalizeDisconnectIfTerminal);
        }
    }
}
