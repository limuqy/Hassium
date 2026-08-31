package io.github.limuqy.mc.hassium;

import io.github.limuqy.mc.hassium.cache.client.ClientLifecycleHelper;
import io.github.limuqy.mc.hassium.client.ClientSmokeTest;
import io.github.limuqy.mc.hassium.command.FabricHassiumCommand;
import io.github.limuqy.mc.hassium.network.ClientChunkHandler;
import io.github.limuqy.mc.hassium.network.ClientGatewayBootstrap;
import io.github.limuqy.mc.hassium.network.DictionaryManager;
import io.github.limuqy.mc.hassium.network.dataplane.DataPlaneClientLifecycle;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
#if MC_VER < MC_1_21_1
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.minecraft.network.FriendlyByteBuf;
#else
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
#endif
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HassiumClientMod implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/ClientMod");

    @Override
    public void onInitializeClient() {
        ClientSmokeTest.initIfEnabled();

        // 加载内置区块字典（打包在 mod 中，不需要从服务端传输）
        DictionaryManager.loadChunkDictionary();

        // 客户端断开事件：清理 + 关闭 UDP 数据面 bundle + 最终清理（幂等）。
        // Fabric 1.20.1：在 login query 阶段声明 Hassium，服务端可在 ServerPlayer 创建前完成分类。
        // 数据面/完整协商仍走 Play 阶段 gateway handshake；这里仅提前消除初始区块竞态。
#if MC_VER < MC_1_21_1
        ClientLoginNetworking.registerGlobalReceiver(
                io.github.limuqy.mc.hassium.network.FabricNetworkManager.PRE_HANDSHAKE_C2S,
                (client, handler, query, callbacks) -> {
                    FriendlyByteBuf response = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                    io.github.limuqy.mc.hassium.network.PreHandshakeProtocol.encodeFields(response);
                    return java.util.concurrent.CompletableFuture.completedFuture(response);
                });
#else
        ClientConfigurationConnectionEvents.START.register((handler, client) ->
                ClientConfigurationNetworking.send(
                        io.github.limuqy.mc.hassium.network.PreHandshakePayload.create()));
#endif
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientLifecycleHelper.cleanupOnDisconnect();
            try {
                DataPlaneClientLifecycle.getInstance().stopUdp(false);
            } catch (Throwable ignored) {
                // UDP 数据面可选；关闭失败不得阻断断连清理
            }
            // 延后到下一 tick：等 Minecraft.disconnect / clearLevel 拆除完成；与 Mixin TAIL 幂等
            client.execute(ClientLifecycleHelper::finalizeDisconnectIfTerminal);
        });

        // 注册客户端命令
        FabricHassiumCommand.registerClientCommands();
        // review-fix: T10-11 → T12+区块直收：HASSIUM 业务 S2C 通道（CHUNK_HASH/SECTION_DELTA/SEED_REF/
        // LIGHT_DELTA/BLOCK_ENTITY_DATA）经网关收口（kind=1 HASSIUM 帧 → NetworkCore.dispatchS2CBusiness），
        // 客户端无需 receiver；唯一例外是 CHUNK_PAYLOAD_S2C——服务端经 ServerPlayNetworking.send 以 vanilla
        // CustomPayload 发出，网关 GatewayPlayerBridge.routeS2C 按 kind=0 vanilla 帧转发 → 客户端
        // GatewayS2CRouter.dispatchToListener → 官方 handleCustomPayload → Fabric ClientPlayNetworking 分发，
        // 不走 dispatchS2CBusiness，因此必须保留客户端 receiver（缺失时全量压缩区块被静默丢弃，即「过期
        // 3007」根因；receiver 注册见下）。
        LOGGER.warn("Hassium: Fabric client registers no HASSIUM business S2C receivers (CHUNK_HASH/SECTION_DELTA/SEED_REF/LIGHT_DELTA/BLOCK_ENTITY_DATA); those packets are only consumed via the gateway topology (T12). CHUNK_PAYLOAD_S2C and SHADOW_PULL_RESPONSE_S2C are vanilla CustomPayload exceptions with receivers registered below.");
        // Fabric 1.20.1 会在 ClientPlayNetworking 层拦截未注册 custom payload；显式注册
        // gateway_info，保证 gateway bootstrap 不依赖 vanilla unknown-payload 路径。
#if MC_VER < MC_1_21_1
        ClientPlayNetworking.registerGlobalReceiver(io.github.limuqy.mc.hassium.network.FabricNetworkManager.GATEWAY_INFO_S2C,
                (client, handler, buf, responseSender) -> {
                    byte[] data = new byte[buf.readableBytes()];
                    buf.readBytes(data);
                    client.execute(() -> ClientGatewayBootstrap.handleGatewayInfoData(data));
                });
#endif


        // CHUNK_PAYLOAD_S2C 客户端 receiver：全量压缩区块直收（网关 kind=0 vanilla 帧转发，非 HASSIUM 帧）。
#if MC_VER < MC_1_21_1
        ClientPlayNetworking.registerGlobalReceiver(io.github.limuqy.mc.hassium.network.FabricNetworkManager.CHUNK_PAYLOAD_S2C,
                (client, handler, buf, responseSender) -> {
                    int len = buf.readVarInt();
                    byte[] data = new byte[len];
                    buf.readBytes(data);
                    ClientChunkHandler.handleCompressedChunk(data);
                });
#else
        ClientPlayNetworking.registerGlobalReceiver(io.github.limuqy.mc.hassium.network.FabricPayloadRegistry.CHUNK_PAYLOAD_S2C_TYPE,
                (payload, context) -> {
                    net.minecraft.network.FriendlyByteBuf buf = io.github.limuqy.mc.hassium.network.FabricPayloadRegistry.fromPayload(payload);
                    int len = buf.readVarInt();
                    byte[] data = new byte[len];
                    buf.readBytes(data);
                    ClientChunkHandler.handleCompressedChunk(data);
                });
#endif
        // shadowPullV1 FULL 回退：服务端返回的原版 chunk+light 线格式统一交给 GatewayS2CRouter 注入。
#if MC_VER < MC_1_21_1
        ClientPlayNetworking.registerGlobalReceiver(io.github.limuqy.mc.hassium.network.FabricNetworkManager.SHADOW_PULL_RESPONSE_S2C,
                (client, handler, buf, responseSender) -> {
                    byte[] data = new byte[buf.readableBytes()];
                    buf.readBytes(data);
                    client.execute(() -> {
                        net.minecraft.network.FriendlyByteBuf response = new net.minecraft.network.FriendlyByteBuf(
                                io.netty.buffer.Unpooled.wrappedBuffer(data));
                        try {
                            io.github.limuqy.mc.hassium.network.ShadowPullClient.handleResponse(
                                    io.github.limuqy.mc.hassium.network.ShadowPullResponseS2CPacket.decode(response));
                        } finally {
                            response.release();
                        }
                    });
                });
#else
        ClientPlayNetworking.registerGlobalReceiver(io.github.limuqy.mc.hassium.network.FabricPayloadRegistry.SHADOW_PULL_RESPONSE_S2C_TYPE,
                (payload, context) -> {
                    net.minecraft.network.FriendlyByteBuf response =
                            io.github.limuqy.mc.hassium.network.FabricPayloadRegistry.fromPayload(payload);
                    try {
                        io.github.limuqy.mc.hassium.network.ShadowPullClient.handleResponse(
                                io.github.limuqy.mc.hassium.network.ShadowPullResponseS2CPacket.decode(response));
                    } finally {
                        response.release();
                    }
                });
#endif

        LOGGER.info("Hassium: Fabric client-side initialization complete");
    }
}
