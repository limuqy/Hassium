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
#else
        ClientPlayNetworking.registerGlobalReceiver(
                io.github.limuqy.mc.hassium.network.FabricPayloadRegistry.GATEWAY_INFO_S2C_TYPE,
                (payload, context) -> context.client().execute(
                        () -> ClientGatewayBootstrap.handleGatewayInfoData(payload.data())));
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
        // SEED_REF_S2C 客户端 receiver：无网关拓扑下 SeedRef（pristine 区块引用）直收 → 本地生成。
#if MC_VER < MC_1_21_1
        ClientPlayNetworking.registerGlobalReceiver(io.github.limuqy.mc.hassium.network.FabricNetworkManager.SEED_REF_S2C,
                (client, handler, buf, responseSender) -> {
                    byte[] data = new byte[buf.readableBytes()];
                    buf.readBytes(data);
                    client.execute(() -> {
                        net.minecraft.network.FriendlyByteBuf seedRefBuf = new net.minecraft.network.FriendlyByteBuf(
                                io.netty.buffer.Unpooled.wrappedBuffer(data));
                        try {
                            io.github.limuqy.mc.hassium.network.ClientMetadataHandler.handleSeedRefPacket(
                                    io.github.limuqy.mc.hassium.network.SeedRefS2CPacket.decode(seedRefBuf));
                        } finally {
                            seedRefBuf.release();
                        }
                    });
                });
#else
        ClientPlayNetworking.registerGlobalReceiver(io.github.limuqy.mc.hassium.network.FabricPayloadRegistry.SEED_REF_S2C_TYPE,
                (payload, context) -> {
                    net.minecraft.network.FriendlyByteBuf seedRefBuf =
                            io.github.limuqy.mc.hassium.network.FabricPayloadRegistry.fromPayload(payload);
                    try {
                        io.github.limuqy.mc.hassium.network.ClientMetadataHandler.handleSeedRefPacket(
                                io.github.limuqy.mc.hassium.network.SeedRefS2CPacket.decode(seedRefBuf));
                    } finally {
                        seedRefBuf.release();
                    }
                });
#endif
        // HANDSHAKE_S2C 客户端 receiver：无网关拓扑下握手响应直收（SeedGen 种子/LevelStem 尾 + UDP 数据面尾）。
#if MC_VER < MC_1_21_1
        ClientPlayNetworking.registerGlobalReceiver(io.github.limuqy.mc.hassium.network.FabricNetworkManager.HANDSHAKE_S2C,
                (client, handler, buf, responseSender) -> {
                    byte[] data = new byte[buf.readableBytes()];
                    buf.readBytes(data);
                    client.execute(() -> handleHandshakeS2C(data));
                });
#else
        ClientPlayNetworking.registerGlobalReceiver(io.github.limuqy.mc.hassium.network.FabricPayloadRegistry.HANDSHAKE_S2C_TYPE,
                (payload, context) -> {
                    net.minecraft.network.FriendlyByteBuf buf =
                            io.github.limuqy.mc.hassium.network.FabricPayloadRegistry.fromPayload(payload);
                    byte[] data = new byte[buf.readableBytes()];
                    buf.readBytes(data);
                    buf.release();
                    context.client().execute(() -> handleHandshakeS2C(data));
                });
#endif

        LOGGER.info("Hassium: Fabric client-side initialization complete");
    }
    /** 无网关拓扑：解码服务端握手响应（与 Forge handleHandshakeS2C 同语义；尾段 append-only）。 */
    private static void handleHandshakeS2C(byte[] data) {
        net.minecraft.network.FriendlyByteBuf buf = new net.minecraft.network.FriendlyByteBuf(
                io.netty.buffer.Unpooled.wrappedBuffer(data));
        try {
            int protocolVersion = buf.readVarInt();
            boolean accepted = buf.readBoolean();
            boolean globalCompression = buf.readBoolean();
            boolean compactHeader = buf.readBoolean();
            LOGGER.info("Hassium: Client handshake response: accepted={}, globalCompression={}, compactHeader={}",
                    accepted, globalCompression, compactHeader);
            if (!accepted) {
                return;
            }
            // UDP 数据面尾（control-only 服务器同样下发；客户端未启用数据面时 hasUdpDataplane=false）
            io.github.limuqy.mc.hassium.network.dataplane.UdpDataPlaneHandshakeTail.S2CTail tail =
                    io.github.limuqy.mc.hassium.network.dataplane.UdpDataPlaneHandshakeTail.readS2C(buf);
            if (tail.hasUdpDataplane()) {
                var mc = net.minecraft.client.Minecraft.getInstance();
                if (mc.player != null) {
                    java.util.UUID pid = mc.player.getUUID();
                    long epoch = tail.connectionEpoch();
                    mc.execute(() -> {
                        try {
                            io.github.limuqy.mc.hassium.network.dataplane.DataPlaneClientLifecycle
                                    .getInstance().startUdp(pid, epoch, tail);
                        } catch (Throwable t) {
                            LOGGER.warn("Hassium: UDP dataplane start failed", t);
                        }
                    });
                }
            }
            // SeedGen 尾（worldSeed + LevelStem NBT + enabled）
            long worldSeed = buf.readLong();
            long stemLen = buf.readVarInt();
            byte[] stemNbt = null;
            if (stemLen > 0 && stemLen <= buf.readableBytes()) {
                stemNbt = new byte[(int) stemLen];
                buf.readBytes(stemNbt);
            }
            boolean seedGenEnabled = buf.readableBytes() >= 1 && buf.readBoolean();
            io.github.limuqy.mc.hassium.network.ClientChunkPipeline.getInstance()
                    .setServerSeedInfo(worldSeed, stemNbt, seedGenEnabled);
            // 续流尾（append-only；未请求时为 false）
            boolean resumeAccepted = buf.readableBytes() >= 1 && buf.readBoolean();
            if (resumeAccepted) {
                LOGGER.info("Hassium: [RESUME] Server accepted resume — 续流就绪");
            }
        } catch (Throwable e) {
            LOGGER.debug("Hassium: failed to decode handshake tail (legacy server?)", e);
        } finally {
            buf.release();
        }
    }
}
