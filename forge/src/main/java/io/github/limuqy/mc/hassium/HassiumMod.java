package io.github.limuqy.mc.hassium;

import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.config.HassiumConfigSpec;
import io.github.limuqy.mc.hassium.network.ChunkSender;
import io.github.limuqy.mc.hassium.network.ForgeNetworkManager;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
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
#if MC_VER < MC_1_20_5
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(
                ModConfig.Type.CLIENT, HassiumConfigSpec.CLIENT_SPEC, Constants.CONFIG_CLIENT_FILE);
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(
                ModConfig.Type.COMMON, HassiumConfigSpec.COMMON_SPEC, Constants.CONFIG_COMMON_FILE);
#else
        // 1.20.6：ModConfigSpec 来自 FCAP Forge；Type 仍用 Forge ModConfig.Type
        fuzs.forgeconfigapiport.forge.api.neoforge.v4.NeoForgeConfigRegistry.INSTANCE.register(
                Constants.MOD_ID, ModConfig.Type.CLIENT, HassiumConfigSpec.CLIENT_SPEC, Constants.CONFIG_CLIENT_FILE);
        fuzs.forgeconfigapiport.forge.api.neoforge.v4.NeoForgeConfigRegistry.INSTANCE.register(
                Constants.MOD_ID, ModConfig.Type.COMMON, HassiumConfigSpec.COMMON_SPEC, Constants.CONFIG_COMMON_FILE);
#endif
        CommonClass.init();

        ChunkSender.setInstance(ForgeNetworkManager::sendCompressedChunk);
        LOGGER.info("Hassium: ChunkSender registered for Forge");
    }

    @Mod.EventBusSubscriber(modid = Constants.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class ModBusEvents {
        @SubscribeEvent
        public static void commonSetup(FMLCommonSetupEvent event) {
            onCommonSetup();
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

    private static void onCommonSetup() {
        LOGGER.info("Hassium: Initializing Forge network channels");
        ForgeNetworkManager networkManager = new ForgeNetworkManager();
        networkManager.registerChannels();
    }
}
