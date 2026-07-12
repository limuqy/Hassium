package io.github.limuqy.mc.hassium;

import io.github.limuqy.mc.hassium.network.NeoForgeNetworkManager;

#if MC_VER < MC_1_20_2
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
#else
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
#endif
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NeoForge 入口类
 * <p>
 * 1.20.1: NeoForge 仍使用 net.minecraftforge 包名
 * 1.20.2+: 切换到 net.neoforged 包名
 */
@Mod(Constants.MOD_ID)
public class HassiumNeoForge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/NeoForge");

    public HassiumNeoForge(IEventBus modEventBus) {
        CommonClass.init();
        
#if MC_VER < MC_1_20_2
        // 1.20.1: 使用 FMLCommonSetupEvent 注册网络
        modEventBus.addListener(this::commonSetup);
        // 命令注册通过 @Mod.EventBusSubscriber 处理
#else
        // 1.20.2+: 注册 Payload 处理器
        modEventBus.addListener(NeoForgeNetworkManager::registerPayloads);
        LOGGER.info("Hassium: Registered NeoForge payload handlers for 1.20.2+");
        
        // 显式注册命令处理器到 NeoForge 事件总线
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(
                io.github.limuqy.mc.hassium.command.NeoForgeHassiumCommand.class);
#endif
    }

#if MC_VER < MC_1_20_2
    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Hassium: Initializing NeoForge network channels for 1.20.1");
        NeoForgeNetworkManager networkManager = new NeoForgeNetworkManager();
        networkManager.registerChannels();
    }
#endif
}
