package io.github.limuqy.mc.hassium;

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
 * 1.20.1: NeoForge 仍使用 net.minecraftforge 包名
 * 1.20.2+: 切换到 net.neoforged 包名
 */
@Mod(Constants.MOD_ID)
public class HassiumNeoForge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/NeoForge");

    public HassiumNeoForge(IEventBus modEventBus) {
        CommonClass.init();
        modEventBus.addListener(this::commonSetup);
#if MC_VER < MC_1_20_2
        // @Mod.EventBusSubscriber on NeoForgeHassiumCommand handles registration
#else
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(
                io.github.limuqy.mc.hassium.command.NeoForgeHassiumCommand.class);
#endif
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Hassium: Initializing NeoForge network channels");
        NeoForgeNetworkManager networkManager = new NeoForgeNetworkManager();
        networkManager.registerChannels();
    }
}
