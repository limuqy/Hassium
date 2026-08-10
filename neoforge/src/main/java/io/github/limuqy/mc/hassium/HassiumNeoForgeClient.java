package io.github.limuqy.mc.hassium;

import io.github.limuqy.mc.hassium.cache.client.ClientLifecycleHelper;
import io.github.limuqy.mc.hassium.client.ClientSmokeTest;
import io.github.limuqy.mc.hassium.network.DictionaryManager;
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
 * <p>
 * T6：客户端 failover 已退役——不再初始化控制面重连单例 / 不再发送握手请求
 * （新架构客户端不发 vanilla 握手，服务端旧握手链休眠）；LoggingOut 直接全量清理。
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

    /**
     * 客户端设置事件处理
     */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
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
         * 玩家断开连接事件
         */
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
            net.minecraft.client.Minecraft.getInstance().execute(ClientLifecycleHelper::finalizeDisconnectIfTerminal);
        }
    }
}
