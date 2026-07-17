package io.github.limuqy.mc.hassium;

import io.github.limuqy.mc.hassium.network.ChunkSender;
import io.github.limuqy.mc.hassium.network.NeoForgeNetworkManager;

#if MC_VER < MC_1_20_2
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.eventbus.api.IEventBus;
#else
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.bus.api.IEventBus;
#endif
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NeoForge 入口类
 * <p>
 * 1.20.1: NeoForge 仍使用 net.minecraftforge 包名 + SimpleChannel
 * 1.20.2–1.20.4: net.neoforged 包名 + SimpleChannel
 * 1.20.5+: net.neoforged 包名 + Payload/StreamCodec
 */
@Mod(Constants.MOD_ID)
public class HassiumNeoForge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/NeoForge");

    public HassiumNeoForge(IEventBus modEventBus) {
        CommonClass.init();

        // 区块推送队列依赖 ChunkSender；未注册会导致队列堆满、移动后虚空
        ChunkSender.setInstance(NeoForgeNetworkManager::sendCompressedChunk);
        LOGGER.info("Hassium: ChunkSender registered for NeoForge");

#if MC_VER < MC_1_20_5
        // SimpleChannel：在 FMLCommonSetupEvent 中注册
        modEventBus.addListener(this::commonSetup);
#else
        // 1.20.5+：注册 Payload 处理器
        modEventBus.addListener(NeoForgeNetworkManager::registerPayloads);
        LOGGER.info("Hassium: Registered NeoForge payload handlers for 1.20.5+");
#endif
        // 命令通过 @Mod.EventBusSubscriber 自动注册（见 NeoForgeHassiumCommand / ClientCommand）
    }

#if MC_VER < MC_1_20_5
    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Hassium: Initializing NeoForge SimpleChannel network");
        NeoForgeNetworkManager networkManager = new NeoForgeNetworkManager();
        networkManager.registerChannels();
    }
#endif
}
