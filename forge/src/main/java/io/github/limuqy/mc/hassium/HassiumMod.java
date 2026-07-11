package io.github.limuqy.mc.hassium;

import io.github.limuqy.mc.hassium.network.ForgeNetworkManager;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
#if MC_VER < MC_1_21_6
import net.minecraftforge.eventbus.api.SubscribeEvent;
#else
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
#endif
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(Constants.MOD_ID)
public class HassiumMod {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/Mod");

    public HassiumMod() {
        CommonClass.init();
    }

    @Mod.EventBusSubscriber(modid = Constants.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class ModBusEvents {
        @SubscribeEvent
        public static void commonSetup(FMLCommonSetupEvent event) {
            onCommonSetup();
        }
    }

    private static void onCommonSetup() {
        LOGGER.info("Hassium: Initializing Forge network channels");
        ForgeNetworkManager networkManager = new ForgeNetworkManager();
        networkManager.registerChannels();
    }
}
