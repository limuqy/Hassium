package io.github.limuqy.mc.hassium;

import io.github.limuqy.mc.hassium.cache.client.ClientLifecycleHelper;
import io.github.limuqy.mc.hassium.client.ClientSmokeTest;
import io.github.limuqy.mc.hassium.command.FabricHassiumCommand;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.DictionaryManager;
import io.github.limuqy.mc.hassium.network.FabricNetworkManager;
import io.github.limuqy.mc.hassium.network.dataplane.DataPlaneClientLifecycle;
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

        // 监听客户端加入服务器事件：发送握手请求。
        // Task 5 — UDP 数据面 bundle 由 S2C 握手尾部（FabricNetworkManager）驱动，
        // 通过 DataPlaneClientLifecycle.startUdp 在 accepted 响应中自动启动；
        // 此处不再硬编码直连 PoC Data 副端口（旧 PoC connectAndBind() 已移除）。
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
            // Task 5 — 关闭 UDP 数据面 bundle，并重置 PoC 兼容的静态计数器
            try { DataPlaneClientLifecycle.getInstance().stopUdp(false); }
            catch (Throwable ignored) {}
            // 延后到下一 tick：等 Minecraft.disconnect / clearLevel 拆除完成；与 Mixin TAIL 幂等
            client.execute(ClientLifecycleHelper::finalizeDisconnect);
        });

        // 注册客户端命令
        FabricHassiumCommand.registerClientCommands();

        LOGGER.info("Hassium: Fabric client-side initialization complete");
    }
}
