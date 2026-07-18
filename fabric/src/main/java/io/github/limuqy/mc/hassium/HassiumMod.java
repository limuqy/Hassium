package io.github.limuqy.mc.hassium;

import io.github.limuqy.mc.hassium.command.FabricHassiumCommand;
import io.github.limuqy.mc.hassium.network.ChunkSender;
import io.github.limuqy.mc.hassium.network.FabricNetworkManager;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HassiumMod implements ModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/Mod");

    @Override
    public void onInitialize() {
        io.github.limuqy.mc.hassium.config.FabricHassiumConfig.register();
        CommonClass.init();

        // 注册网络通道
        LOGGER.info("Hassium: Initializing Fabric network channels");
        FabricNetworkManager networkManager = new FabricNetworkManager();
        networkManager.registerChannels();

        // 设置区块发送器
        ChunkSender.setInstance((player, compressed) -> {
            FabricNetworkManager.sendCompressedChunk(player, compressed);
        });

        // 注册命令
        FabricHassiumCommand.register();
        LOGGER.info("Hassium: Commands registered");
    }
}
