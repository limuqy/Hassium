package io.github.limuqy.mc.hassium;

import io.github.limuqy.mc.hassium.cache.client.ClientLifecycleHelper;
import io.github.limuqy.mc.hassium.client.ClientSmokeTest;
import io.github.limuqy.mc.hassium.command.FabricHassiumCommand;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
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
        ClientSmokeTest.initIfEnabled();

        // 加载内置区块字典（打包在 mod 中，不需要从服务端传输）
        DictionaryManager.loadChunkDictionary();

        // 注册客户端网络通道
        networkManager.registerClientChannels();

        // 监听客户端加入服务器事件，发送握手请求
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (!HassiumConfigService.getInstance().isNetworkCompressionEnabled()) {
                LOGGER.debug("Hassium: Client joined server, network disabled — skip handshake");
                return;
            }
            networkManager.sendHandshakeRequest();
        });

        // 监听客户端断开连接事件，统一走 ClientLifecycleHelper.cleanupOnDisconnect
        // （MixinClientCommonPacketListenerImpl.onDisconnect 在 1.20.2+ 可能不被触发，
        //  必须在此主动清理，否则 initialized 标志残留导致重连后 onLogin early-return）
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientLifecycleHelper.cleanupOnDisconnect();
            // 延后到下一 tick：等 Minecraft.disconnect / clearLevel 拆除完成；与 Mixin TAIL 幂等
            client.execute(ClientLifecycleHelper::finalizeDisconnect);
        });

        // 注册客户端命令
        FabricHassiumCommand.registerClientCommands();

        LOGGER.info("Hassium: Fabric client-side initialization complete");
    }
}
