package io.github.limuqy.mc.hassium;

import io.github.limuqy.mc.hassium.config.ForgeConfigBackend;
import io.github.limuqy.mc.hassium.config.ForgeConfigRegistration;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.ChunkSender;
import io.github.limuqy.mc.hassium.network.ForgeNetworkManager;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(Constants.MOD_ID)
public class HassiumMod {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/Mod");
    private static final ForgeConfigBackend CONFIG = new ForgeConfigBackend();

    public HassiumMod() {
        ForgeConfigRegistration.register(CONFIG, Constants.CONFIG_CLIENT_FILE, Constants.CONFIG_SERVER_FILE);
        CommonClass.init();

        ChunkSender.setInstance(ForgeNetworkManager::sendCompressedChunk);
        LOGGER.info("Hassium: ChunkSender registered for Forge");
    }

    @Mod.EventBusSubscriber(modid = Constants.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class ModBusEvents {
        @SubscribeEvent
        public static void commonSetup(FMLCommonSetupEvent event) {
            LOGGER.info("Hassium: Initializing Forge network channels");
            ForgeNetworkManager networkManager = new ForgeNetworkManager();
            networkManager.registerChannels();
        }

        @SubscribeEvent
        public static void onConfigLoad(ModConfigEvent.Loading event) {
            if (Constants.MOD_ID.equals(event.getConfig().getModId())) {
                HassiumConfigService.getInstance().syncFromSpec();
            }
        }

        @SubscribeEvent
        public static void onConfigReload(ModConfigEvent.Reloading event) {
            if (Constants.MOD_ID.equals(event.getConfig().getModId())) {
                HassiumConfigService.getInstance().syncFromSpec();
            }
        }
    }
}
