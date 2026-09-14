package io.github.limuqy.mc.hassium;

import io.github.limuqy.mc.hassium.config.ForgeConfigBackend;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.ForgeNetworkManager;
#if MC_VER < MC_1_21_6
import net.minecraftforge.eventbus.api.SubscribeEvent;
#else
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
#endif
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(Constants.MOD_ID)
public class HassiumMod {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/Mod");
    private static final ForgeConfigBackend CONFIG = (ForgeConfigBackend) io.github.limuqy.mc.hassium.platform.Services.CONFIG;

    public HassiumMod(FMLJavaModLoadingContext context) {
        // 物理客户端：CLIENT + COMMON 双注册——client.toml 配客户端行为，server.toml
        // 供集成服务器/局域网读取（UI 不展示服务端键，仅文件可编辑）。
        // 专用服：仅注册 server 侧（COMMON）。
        if (io.github.limuqy.mc.hassium.platform.Services.PLATFORM.isPhysicalClient()) {
            context.registerConfig(ModConfig.Type.CLIENT, CONFIG.clientSpec(), Constants.CONFIG_CLIENT_FILE);
            context.registerConfig(ModConfig.Type.COMMON, CONFIG.serverSpec(), Constants.CONFIG_SERVER_FILE);
        } else {
            context.registerConfig(ModConfig.Type.COMMON, CONFIG.serverSpec(), Constants.CONFIG_SERVER_FILE);
        }
        CommonClass.init();
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
