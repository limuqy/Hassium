package io.github.limuqy.mc.hassium;

import io.github.limuqy.mc.hassium.command.FabricHassiumCommand;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.ClientChunkHandler;
import io.github.limuqy.mc.hassium.network.DictionaryManager;
import io.github.limuqy.mc.hassium.network.FabricNetworkManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HassiumClientMod implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/ClientMod");
    private static final FabricNetworkManager networkManager = new FabricNetworkManager();

    @Override
    public void onInitializeClient() {
        LOGGER.info("Hassium: Initializing client-side network channels");

        // 加载内置区块字典（打包在 mod 中，不需要从服务端传输）
        DictionaryManager.loadChunkDictionary();

        // 注册客户端网络通道
        networkManager.registerClientChannels();

        // 监听客户端加入服务器事件，发送握手请求
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            // 段 C 门控关闭网络时通道未注册，发握手会 EncoderException 断连
            if (!HassiumConfigService.getInstance().isNetworkCompressionEnabled()) {
                LOGGER.info("Hassium: Client joined server, network disabled — skip handshake");
                return;
            }
            LOGGER.info("Hassium: Client joined server, sending handshake request");
            networkManager.sendHandshakeRequest();
        });

        // 监听客户端断开连接事件，关闭缓存存储并重置状态
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            var storage = ClientChunkHandler.getClientStorage();
            if (storage != null) {
                storage.close();
                LOGGER.info("Hassium: Closed client cache storage");
            }
            // 重置缓存存储，确保下次登入时重新初始化
            ClientChunkHandler.resetStorage();
        });

        // 注册客户端命令
        FabricHassiumCommand.registerClientCommands();
        LOGGER.info("Hassium: Client commands registered");
    }
}
