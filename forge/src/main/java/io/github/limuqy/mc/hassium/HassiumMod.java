package io.github.limuqy.mc.hassium;

import io.github.limuqy.mc.hassium.network.ForgeNetworkManager;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(Constants.MOD_ID)
public class HassiumMod {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/Mod");

    public HassiumMod() {
        CommonClass.init();

        // 注册网络通道
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::commonSetup);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Hassium: Initializing Forge network channels");
        ForgeNetworkManager networkManager = new ForgeNetworkManager();
        networkManager.registerChannels();
    }
}