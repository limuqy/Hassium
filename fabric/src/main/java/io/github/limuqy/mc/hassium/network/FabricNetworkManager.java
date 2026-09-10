package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.HassiumChannels;
import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.handshake.LoginHandshake;
import io.github.limuqy.mc.hassium.network.handshake.ServerHandshakeActivation;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DebugLogger.LogType;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
#if MC_VER >= MC_1_21_1
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
#endif
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
#if MC_VER < MC_1_21_11
import net.minecraft.resources.ResourceLocation;
#else
import net.minecraft.resources.Identifier;
#endif
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Fabric 平台网络管理器实现（直连拓扑）。
 * <p>
 * 版本整段切分（见 docs/version-segments.md）：
 * <ul>
 *   <li>{@code MC_VER < MC_1_21_1}：Identifier + FriendlyByteBuf 收发</li>
 *   <li>{@code MC_VER >= MC_1_21_1}：CustomPacketPayload + StreamCodec（{@link FabricPayloadRegistry}）</li>
 * </ul>
 * 能力协商在 login/config 阶段由 common（{@code LoginHandshakeManager} / mixin）完成；
 * Play 期激活（{@code play_init_s2c} 下发、compression_ready ACK、ZSTD 切换、Dict/Index）
 * 由 common {@link ServerHandshakeActivation} 收口，本类只做 loader 边界收发与
 * SPI 委托点（{@code FabricNetworkManagerService} 转调静态方法）。
 */
public class FabricNetworkManager implements NetworkManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/Network");

    // 缓存服务器实例
    private static volatile net.minecraft.server.MinecraftServer cachedServer;

    /**
     * 设置服务器实例（在服务器启动时调用）
     */
    public static void setServerInstance(net.minecraft.server.MinecraftServer server) {
        cachedServer = server;
    }

    // 资源位置定义（仅 loader 收发边界持有 vanilla 类型；业务常量见 compat.HassiumChannels）
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
COMPRESSION_READY_C2S = ResourceLocationCompat.vanilla(HassiumChannels.COMPRESSION_READY_C2S);
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
AGGREGATION_S2C = ResourceLocationCompat.create(Constants.MOD_ID, "aggregation");
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
DICTIONARY_SYNC_S2C = ResourceLocationCompat.vanilla(HassiumChannels.DICTIONARY_SYNC);
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
BLOCK_ENTITY_REQUEST_C2S = ResourceLocationCompat.vanilla(HassiumChannels.BLOCK_ENTITY_REQUEST_C2S);
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
BLOCK_ENTITY_DATA_S2C = ResourceLocationCompat.vanilla(HassiumChannels.BLOCK_ENTITY_DATA_S2C);
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
INDEX_SYNC_S2C = ResourceLocationCompat.vanilla(HassiumChannels.INDEX_SYNC_S2C);
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
LIGHT_DELTA_S2C = ResourceLocationCompat.vanilla(HassiumChannels.LIGHT_DELTA_S2C);
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
SHADOW_PULL_REQUEST_C2S = ResourceLocationCompat.vanilla(HassiumChannels.SHADOW_PULL_REQUEST_C2S);
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
SHADOW_PULL_RESPONSE_S2C = ResourceLocationCompat.vanilla(HassiumChannels.SHADOW_PULL_RESPONSE_S2C);
    /** Play 期激活（登录协商结果 + SeedGen 种子；S2C）。 */
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
PLAY_INIT_S2C = ResourceLocationCompat.vanilla(HassiumChannels.PLAY_INIT_S2C);

    @Override
    public void registerChannels() {
        if (!HassiumConfigService.getInstance().isNetworkCompressionEnabled()
                && !HassiumConfigService.getInstance().isClientCacheEnabled()) {
            LOGGER.warn("Hassium: master.enabled=false and chunk.enabled=false, skipping channel registration");
            return;
        }
        LOGGER.debug("Hassium: Registering Fabric network channels");
#if MC_VER >= MC_1_21_1
        FabricPayloadRegistry.registerAll();
#endif
        registerServerChannels();

        // 设置聚合包发送器
        HassiumAggregationManager.setSender((connection, buf) -> {
            if (connection.getPacketListener() instanceof net.minecraft.server.network.ServerGamePacketListenerImpl handler) {
                ServerPlayer player = handler.getPlayer();
#if MC_VER < MC_1_21_1
                ServerPlayNetworking.send(player, AGGREGATION_S2C, buf);
#else
                ServerPlayNetworking.send(player, FabricPayloadRegistry.toPayload(FabricPayloadRegistry.AGGREGATION_S2C_TYPE, buf));
#endif
            } else {
                LOGGER.error("Cannot send aggregation packet: connection has no player");
                buf.release();
            }
        });

        // 设置字典推送回调
        DictionaryManager.setPushCallback((dictionary) -> {
            try {
                net.minecraft.server.MinecraftServer server = cachedServer;
                if (server != null) {
                    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                        sendDictionarySyncPacket(player, dictionary);
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Failed to push dictionary to clients", e);
            }
        });
    }

    @Override
    public void sendShadowPullRequest(FriendlyByteBuf buf) {
        if (Minecraft.getInstance().getConnection() != null) {
#if MC_VER < MC_1_21_1
            ClientPlayNetworking.send(SHADOW_PULL_REQUEST_C2S, buf);
#else
            ClientPlayNetworking.send(FabricPayloadRegistry.toPayload(FabricPayloadRegistry.SHADOW_PULL_REQUEST_C2S_TYPE, buf));
#endif
        } else if (buf != null && buf.refCnt() > 0) {
            buf.release();
        }
    }

    @Override
    public void sendShadowPullResponse(ServerPlayer player, FriendlyByteBuf buf) {
#if MC_VER < MC_1_21_1
        ServerPlayNetworking.send(player, SHADOW_PULL_RESPONSE_S2C, buf);
#else
        ServerPlayNetworking.send(player, FabricPayloadRegistry.toPayload(FabricPayloadRegistry.SHADOW_PULL_RESPONSE_S2C_TYPE, buf));
#endif
    }


    @Override
    public void sendBlockEntityRequest(FriendlyByteBuf buf) {
        if (Minecraft.getInstance().getConnection() != null) {
#if MC_VER < MC_1_21_1
            ClientPlayNetworking.send(BLOCK_ENTITY_REQUEST_C2S, buf);
#else
            ClientPlayNetworking.send(FabricPayloadRegistry.toPayload(FabricPayloadRegistry.BLOCK_ENTITY_REQUEST_C2S_TYPE, buf));
#endif
        } else {
            buf.release();
        }
    }

    @Override
    public void sendBlockEntityData(ServerPlayer player, FriendlyByteBuf buf) {
#if MC_VER < MC_1_21_1
        ServerPlayNetworking.send(player, BLOCK_ENTITY_DATA_S2C, buf);
#else
        ServerPlayNetworking.send(player, FabricPayloadRegistry.toPayload(FabricPayloadRegistry.BLOCK_ENTITY_DATA_S2C_TYPE, buf));
#endif
    }

    @Override
    public void sendLightDeltaPacket(ServerPlayer player, FriendlyByteBuf buf) {
        // 直连拓扑：光照增量经 vanilla play S2C 通道直发，客户端 receiver 直收。
#if MC_VER < MC_1_21_1
        ServerPlayNetworking.send(player, LIGHT_DELTA_S2C, buf);
#else
        ServerPlayNetworking.send(player, FabricPayloadRegistry.toPayload(FabricPayloadRegistry.LIGHT_DELTA_S2C_TYPE, buf));
#endif
    }

    /**
     * 发送字典同步包到指定玩家
     */
    private static void sendDictionarySyncPacket(ServerPlayer player) {
        try {
            byte[] aggregationDict = DictionaryManager.getAggregationDict();

            DictionarySyncPayload payload = new DictionarySyncPayload(aggregationDict, false);
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            payload.encode(buf);
#if MC_VER < MC_1_21_1
            ServerPlayNetworking.send(player, DICTIONARY_SYNC_S2C, buf);
#else
            ServerPlayNetworking.send(player, FabricPayloadRegistry.toPayload(FabricPayloadRegistry.DICTIONARY_SYNC_S2C_TYPE, buf));
#endif
            DebugLogger.debug(LogType.NETWORK, "Hassium: Sent aggregation dictionary sync to player {} ({} bytes)",
                    player.getName().getString(),
                    aggregationDict != null ? aggregationDict.length : 0);
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to send dictionary sync packet", e);
        }
    }

    /**
     * 发送指定字典到玩家
     */
    private static void sendDictionarySyncPacket(ServerPlayer player, byte[] dictionary) {
        try {
            DictionarySyncPayload payload = new DictionarySyncPayload(dictionary, false);
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            payload.encode(buf);
#if MC_VER < MC_1_21_1
            ServerPlayNetworking.send(player, DICTIONARY_SYNC_S2C, buf);
#else
            ServerPlayNetworking.send(player, FabricPayloadRegistry.toPayload(FabricPayloadRegistry.DICTIONARY_SYNC_S2C_TYPE, buf));
#endif
            DebugLogger.debug(LogType.NETWORK, "Hassium: Pushed new aggregation dictionary to player {} ({} bytes)",
                    player.getName().getString(), dictionary != null ? dictionary.length : 0);
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to push dictionary to player {}", player.getName().getString(), e);
        }
    }

    /**
     * 发送索引同步包到指定玩家
     */
    private static void sendIndexSyncPacket(ServerPlayer player) {
        try {
            IndexSyncManager indexSyncManager = IndexSyncManager.getInstance();
            indexSyncManager.initializeServerIndex();

            IndexSyncPacket syncPacket = indexSyncManager.createSyncPacket();
            byte[] data = syncPacket.encode();

            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            buf.writeVarInt(data.length);
            buf.writeBytes(data);
#if MC_VER < MC_1_21_1
            ServerPlayNetworking.send(player, INDEX_SYNC_S2C, buf);
#else
            ServerPlayNetworking.send(player, FabricPayloadRegistry.toPayload(FabricPayloadRegistry.INDEX_SYNC_S2C_TYPE, buf));
#endif
            DebugLogger.debug(LogType.NETWORK, "Hassium: Sent index sync packet to player {} ({} packet types)",
                    player.getName().getString(), indexSyncManager.getServerIndexManager().size());
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to send index sync packet", e);
        }
    }

    // ===== SPI 实现委托点（FabricNetworkManagerService 转调；common ServerHandshakeActivation 消费） =====

    /**
     * SPI：发送聚合字典同步到客户端（服务端调用；Play 期 ZSTD 安装后）。
     */
    public static void sendDictionarySync(ServerPlayer player) {
        sendDictionarySyncPacket(player);
    }

    /**
     * SPI：发送包索引同步到客户端（服务端调用；Play 期 ZSTD 安装后）。
     */
    public static void sendIndexSync(ServerPlayer player) {
        sendIndexSyncPacket(player);
    }

    /**
     * SPI：发送 Play 期激活包到客户端（服务端调用；登录协商完成后玩家就绪时）。
     * <p>
     * body = {@link LoginHandshake.PlayInitPayload}（协商位 + SeedGen 种子/LevelStem），
     * 客户端 receiver 解码后转调 common {@code PlayInitClient.handle}。
     */
    public static void sendPlayInit(ServerPlayer player, int negotiatedCaps, long worldSeed,
                                    byte[] stemNbt, boolean seedGenEnabled) {
        try {
            LoginHandshake.PlayInitPayload payload =
                    new LoginHandshake.PlayInitPayload(negotiatedCaps, worldSeed, stemNbt, seedGenEnabled);
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            payload.encode(buf);
#if MC_VER < MC_1_21_1
            ServerPlayNetworking.send(player, PLAY_INIT_S2C, buf);
#else
            ServerPlayNetworking.send(player, FabricPayloadRegistry.toPayload(FabricPayloadRegistry.PLAY_INIT_S2C_TYPE, buf));
#endif
            DebugLogger.info(LogType.NETWORK, "[PLAY_INIT] Sent play_init to {} (caps={})",
                    player.getName().getString(), LoginHandshake.describeCaps(negotiatedCaps));
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to send play init packet to {}", player.getName().getString(), e);
        }
    }

    /**
     * 客户端激活 ACK（index_sync 收到后回发；C2S）。
     * <p>
     * body = {@link CompressionReadyPayload}（ready=true），服务端 receiver 转调
     * common {@code ServerHandshakeActivation.handleActivationReady}（聚合 PENDING→ENABLED）。
     */
    public static void sendCompressionReady() {
        try {
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            new CompressionReadyPayload(true).encode(buf);
#if MC_VER < MC_1_21_1
            ClientPlayNetworking.send(COMPRESSION_READY_C2S, buf);
#else
            ClientPlayNetworking.send(FabricPayloadRegistry.toPayload(FabricPayloadRegistry.COMPRESSION_READY_C2S_TYPE, buf));
#endif
            DebugLogger.debug(LogType.NETWORK, "Hassium: Sent compression ready ACK");
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to send compression ready ACK", e);
        }
    }

    /**
     * 服务端预握手注册：仅 1.21.1+ 配置阶段 payload 接收。
     * <p>
     * 1.20.1 login query 载体由 common mixin（{@code MixinServerLoginPacketListenerImpl} /
     * {@code MixinClientPacketListener}）处理，loader 无需注册；收到后仅
     * {@code LoginHandshakeManager.markNegotiated}（按 UUID 登记），ServerPlayer
     * 创建时由 {@link ServerHandshakeActivation#onPlayerInit} 消费。
     */
    private void registerPreHandshakeServer() {
#if MC_VER >= MC_1_21_1
        // 1.21.1+：配置阶段 payload 接收。
        // payload type 注册统一在 FabricPayloadRegistry.registerAll（registerChannels 时调用，幂等）
        ServerConfigurationNetworking.registerGlobalReceiver(PreHandshakePayload.TYPE,
                (payload, context) -> {
                    UUID playerId = io.github.limuqy.mc.hassium.compat.PlayerCompat.getProfileId(context.networkHandler().getOwner());
                    PreHandshakeProtocol.handlePreHandshake(playerId, payload);
                });
#endif
    }

    /**
     * 注册服务端网络通道
     */
    private void registerServerChannels() {
        // ===== 预握手（配置阶段）：提前登记 Hassium 客户端能力位 =====
        registerPreHandshakeServer();

        // 注册激活 ACK（客户端 index_sync 确认 → common 激活：聚合 PENDING→ENABLED）
#if MC_VER < MC_1_21_1
        ServerPlayNetworking.registerGlobalReceiver(COMPRESSION_READY_C2S, (server, player, handler, buf, sender) -> {
            CompressionReadyPayload payload = CompressionReadyPayload.decode(buf);
            DebugLogger.debug(LogType.NETWORK, "Hassium: Received compression ready from player {}, ready: {}",
                    player.getName().getString(), payload.isReady());

            if (payload.isReady()) {
                ServerHandshakeActivation.handleActivationReady(player);
            }
        });
#else
        ServerPlayNetworking.registerGlobalReceiver(FabricPayloadRegistry.COMPRESSION_READY_C2S_TYPE, (payload, context) -> {
            FriendlyByteBuf buf = FabricPayloadRegistry.fromPayload(payload);
            try {
                CompressionReadyPayload readyPayload = CompressionReadyPayload.decode(buf);
                DebugLogger.debug(LogType.NETWORK, "Hassium: Received compression ready from player {}, ready: {}",
                        context.player().getName().getString(), readyPayload.isReady());

                if (readyPayload.isReady()) {
                    ServerHandshakeActivation.handleActivationReady(context.player());
                }
            } catch (Exception e) {
                LOGGER.error("Failed to handle compression ready packet", e);
            } finally {
                buf.release();
            }
        });
#endif


        // shadowPullV1：common handler 负责校验、幂等和逐区块权威响应。
#if MC_VER < MC_1_21_1
        ServerPlayNetworking.registerGlobalReceiver(SHADOW_PULL_REQUEST_C2S, (server, player, handler, buf, sender) -> {
            try {
                ShadowPullRequestC2SPacket request = ShadowPullRequestC2SPacket.decode(buf);
                server.execute(() -> {
                    String dimension = io.github.limuqy.mc.hassium.compat.LevelCompat.getDimensionId(player.level());
                    LOGGER.info("[SHADOW_PULL] server request player={} count={} epoch={}", player.getUUID(),
                            request.entries().size(), request.epoch());
                    ShadowPullResponseS2CPacket response = new ShadowPullHandler(new ShadowPullRequestLedger()).handle(
                            player.getUUID(), request, dimension, request.epoch(), player.chunkPosition().x,
                            player.chunkPosition().z, io.github.limuqy.mc.hassium.compat.PlayerCompat.getViewDistance(player)
                                    + io.github.limuqy.mc.hassium.network.ShadowPullRadii.AUTHORITY_MARGIN,
                            true,
                            player.isAlive() && !player.hasDisconnected(),
                            (req, entry) -> ServerChunkPushManager.getInstance().resolveShadowPull(player, req, entry, dimension));
                    FriendlyByteBuf out = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                    response.encode(out);
                    ServerPlayNetworking.send(player, SHADOW_PULL_RESPONSE_S2C, out);
                });
            } catch (Throwable t) {
                LOGGER.error("[SERVER] Failed to handle shadowPullV1", t);
            }
        });
#else
        ServerPlayNetworking.registerGlobalReceiver(FabricPayloadRegistry.SHADOW_PULL_REQUEST_C2S_TYPE, (payload, context) -> {
            FriendlyByteBuf buf = FabricPayloadRegistry.fromPayload(payload);
            try {
                ShadowPullRequestC2SPacket request = ShadowPullRequestC2SPacket.decode(buf);
                context.server().execute(() -> {
                    ServerPlayer player = (ServerPlayer) context.player();
                    String dimension = io.github.limuqy.mc.hassium.compat.LevelCompat.getDimensionId(player.level());
                    ShadowPullResponseS2CPacket response = new ShadowPullHandler(new ShadowPullRequestLedger()).handle(
                            player.getUUID(), request, dimension, request.epoch(), player.chunkPosition().x,
                            player.chunkPosition().z, io.github.limuqy.mc.hassium.compat.PlayerCompat.getViewDistance(player)
                                    + io.github.limuqy.mc.hassium.network.ShadowPullRadii.AUTHORITY_MARGIN,
                            true,
                            player.isAlive() && !player.hasDisconnected(),
                            (req, entry) -> ServerChunkPushManager.getInstance().resolveShadowPull(player, req, entry, dimension));
                    FriendlyByteBuf out = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                    response.encode(out);
                    ServerPlayNetworking.send(player, FabricPayloadRegistry.toPayload(
                            FabricPayloadRegistry.SHADOW_PULL_RESPONSE_S2C_TYPE, out));
                });
            } finally {
                buf.release();
            }
        });
#endif


        // 注册 blockEntity 数据请求
#if MC_VER < MC_1_21_1
        ServerPlayNetworking.registerGlobalReceiver(BLOCK_ENTITY_REQUEST_C2S, (server, player, handler, buf, sender) -> {
            try {
                // review-fix: T10-1: 直接 decode 原 buf（Fabric 回调结束后负责释放），避免副本泄漏
                BlockEntityRequestC2SPacket request = BlockEntityRequestC2SPacket.decode(buf);

                server.execute(() -> {
                    try {
                        ServerChunkPushManager.getInstance().handleBlockEntityRequest(
                                player, request);
                    } catch (Exception e) {
                        LOGGER.error("[SERVER] Failed to handle block entity request", e);
                    }
                });
            } catch (Exception e) {
                LOGGER.error("[SERVER] Failed to decode block entity request", e);
            }
        });
#else
        ServerPlayNetworking.registerGlobalReceiver(FabricPayloadRegistry.BLOCK_ENTITY_REQUEST_C2S_TYPE, (payload, context) -> {
            FriendlyByteBuf buf = FabricPayloadRegistry.fromPayload(payload);
            try {
                ServerPlayer player = context.player();
                net.minecraft.server.MinecraftServer server = io.github.limuqy.mc.hassium.compat.PlayerCompat.getMinecraftServer(player);
                BlockEntityRequestC2SPacket request = BlockEntityRequestC2SPacket.decode(buf);

                server.execute(() -> {
                    try {
                        ServerChunkPushManager.getInstance().handleBlockEntityRequest(
                                player, request);
                    } catch (Exception e) {
                        LOGGER.error("[SERVER] Failed to handle block entity request", e);
                    }
                });
            } catch (Exception e) {
                LOGGER.error("[SERVER] Failed to decode block entity request", e);
            } finally {
                buf.release();
            }
        });
#endif
    }
}


