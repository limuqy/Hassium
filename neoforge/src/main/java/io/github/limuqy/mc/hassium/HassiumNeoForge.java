package io.github.limuqy.mc.hassium;

import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.platform.NeoForgeConfigBackend;
import io.github.limuqy.mc.hassium.platform.NeoForgeConfigRegistration;
import io.github.limuqy.mc.hassium.network.NeoForgeNetworkManager;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.bus.api.IEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NeoForge 入口类
 * <p>
 * NeoForge ≥1.21.1：net.neoforged 包名 + Payload/StreamCodec
 */
@Mod(Constants.MOD_ID)
public class HassiumNeoForge {


    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/NeoForge");
    private static final NeoForgeConfigBackend CONFIG = (NeoForgeConfigBackend) io.github.limuqy.mc.hassium.platform.Services.CONFIG;

    public HassiumNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        NeoForgeConfigRegistration.register(modContainer, CONFIG, Constants.CONFIG_CLIENT_FILE, Constants.CONFIG_SERVER_FILE);
        hassium$init(modEventBus);
    }
    private void hassium$init(IEventBus modEventBus) {
        modEventBus.addListener(this::onConfigLoad);
        modEventBus.addListener(this::onConfigReload);

        CommonClass.init();

        modEventBus.addListener(NeoForgeNetworkManager::registerPayloads);
        modEventBus.addListener(NeoForgeNetworkManager::onRegisterConfigurationTasks);
        LOGGER.info("Hassium: Registered NeoForge payload handlers");
    }

    private void onConfigLoad(ModConfigEvent.Loading event) {
        if (Constants.MOD_ID.equals(event.getConfig().getModId())) {
            HassiumConfigService.getInstance().syncFromSpec();
        }
    }

    private void onConfigReload(ModConfigEvent.Reloading event) {
        if (Constants.MOD_ID.equals(event.getConfig().getModId())) {
            HassiumConfigService.getInstance().syncFromSpec();
        }
    }
}
