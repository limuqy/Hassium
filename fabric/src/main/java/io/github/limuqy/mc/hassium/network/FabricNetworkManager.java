package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.ServerChunkPushManager;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DebugLogger.LogType;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
#if MC_VER < MC_1_21_11
import net.minecraft.resources.ResourceLocation;
#else
import net.minecraft.resources.Identifier;
#endif
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;

/**
 * Fabric 平台网络管理器实现。
 * <p>
 * 版本整段切分（见 docs/version-segments.md）：
 * <ul>
 *   <li>{@code MC_VER < MC_1_20_5}：Identifier + FriendlyByteBuf 收发</li>
 *   <li>{@code MC_VER >= MC_1_20_5}：CustomPacketPayload + StreamCodec（{@link FabricPayloadRegistry}）</li>
 * </ul>
 * 禁止在每个 send/receive 再引入碎片分界；common 侧聚合能力由 {@link io.github.limuqy.mc.hassium.compat.NetworkCapability} 门控。
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

    /**
     * 通过反射获取 Connection 的 channel 字段
     */
    private static io.netty.channel.Channel getConnectionChannel(Connection connection) {
        try {
            Field channelField = Connection.class.getDeclaredField("channel");
            channelField.setAccessible(true);
            return (io.netty.channel.Channel) channelField.get(connection);
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to get channel from connection", e);
            return null;
        }
    }

    /**
     * 通过反射获取 ServerPlayer 的 Connection
     */
    private static Connection getPlayerConnection(ServerPlayer player) {
        return io.github.limuqy.mc.hassium.compat.PlayerCompat.getConnection(player);
    }

    // 资源位置定义
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
HANDSHAKE_C2S = ResourceLocationCompat.create(Constants.MOD_ID, "handshake_c2s");
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
HANDSHAKE_S2C = ResourceLocationCompat.create(Constants.MOD_ID, "handshake_s2c");
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
COMPRESSION_READY_C2S = ResourceLocationCompat.create(Constants.MOD_ID, "compression_ready_c2s");
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
DICTIONARY_SYNC_S2C = DictionarySyncPayload.CHANNEL;
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
CHUNK_DATA_REQUEST_C2S = ChunkDataRequestC2SPacket.CHANNEL;
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
CHUNK_HASH_S2C = ChunkHashS2CPacket.CHANNEL;
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
SECTION_HASH_REQUEST_C2S = SectionHashRequestC2SPacket.CHANNEL;
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
SECTION_DELTA_S2C = SectionDeltaS2CPacket.CHANNEL;
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
BLOCK_ENTITY_REQUEST_C2S = BlockEntityRequestC2SPacket.CHANNEL;
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
BLOCK_ENTITY_DATA_S2C = BlockEntityDataS2CPacket.CHANNEL;
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
CHUNK_PAYLOAD_S2C = ResourceLocationCompat.create(Constants.MOD_ID, "chunk_payload_s2c");
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
INDEX_SYNC_S2C = ResourceLocationCompat.create(Constants.MOD_ID, "index_sync_s2c");
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
LIGHT_DELTA_S2C = LightDeltaS2CPacket.CHANNEL;

    @Override
    public void registerChannels() {
        if (!HassiumConfigService.getInstance().isNetworkCompressionEnabled()) {
            LOGGER.warn("Hassium: network.enabled=false, skipping Fabric channel registration");
            return;
        }
        LOGGER.debug("Hassium: Registering Fabric network channels");
#if MC_VER >= MC_1_20_5
        FabricPayloadRegistry.registerAll();
#endif
        registerServerChannels();

        // 设置聚合包发送器
        HassiumAggregationManager.setSender((connection, buf) -> {
            if (connection.getPacketListener() instanceof net.minecraft.server.network.ServerGamePacketListenerImpl handler) {
                ServerPlayer player = handler.getPlayer();
#if MC_VER < MC_1_20_5
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

    /**
     * 注册客户端网络通道
     */
    public void registerClientChannels() {
        if (!HassiumConfigService.getInstance().isNetworkCompressionEnabled()) {
            LOGGER.warn("Hassium: network.enabled=false, skipping Fabric client channel registration");
            return;
        }
        // 注册客户端接收压缩区块数据
#if MC_VER < MC_1_20_5
        ClientPlayNetworking.registerGlobalReceiver(CHUNK_PAYLOAD_S2C, (client, handler, buf, responseSender) -> {
            int length = buf.readVarInt();
            byte[] data = new byte[length];
            buf.readBytes(data);

            DebugLogger.info(LogType.NETWORK, "[CLIENT] Received compressed chunk payload ({} bytes)", data.length);

            client.execute(() -> {
                try {
                    DebugLogger.debug(LogType.COMPRESSION, "[CLIENT] Processing compressed chunk on main thread");
                    ClientChunkHandler.handleCompressedChunk(data);
                } catch (Exception e) {
                    LOGGER.error("[CLIENT] Failed to handle compressed chunk data", e);
                }
            });
        });
#else
        ClientPlayNetworking.registerGlobalReceiver(FabricPayloadRegistry.CHUNK_PAYLOAD_S2C_TYPE, (payload, context) -> {
            FriendlyByteBuf buf = FabricPayloadRegistry.fromPayload(payload);
            try {
                int length = buf.readVarInt();
                byte[] data = new byte[length];
                buf.readBytes(data);
                context.client().execute(() -> {
                    try {
                        ClientChunkHandler.handleCompressedChunk(data);
                    } catch (Exception e) {
                        LOGGER.error("[CLIENT] Failed to handle compressed chunk data", e);
                    }
                });
            } finally {
                buf.release();
            }
        });
#endif

        // 注册字典同步响应
#if MC_VER < MC_1_20_5
        ClientPlayNetworking.registerGlobalReceiver(DICTIONARY_SYNC_S2C, (client, handler, buf, responseSender) -> {
            try {
                FriendlyByteBuf packetBuf = new FriendlyByteBuf(buf.copy());
                DictionarySyncPayload payload = DictionarySyncPayload.decode(packetBuf);
                byte[] dict = payload.dictionary();

                client.execute(() -> {
                    DictionaryManager.setAggregationDict(dict);
                    DebugLogger.debug(LogType.NETWORK, "Hassium: Received aggregation dictionary from server ({} bytes)", dict.length);
                });
            } catch (Exception e) {
                LOGGER.error("Hassium: Failed to decode dictionary sync packet", e);
            }
        });
#else
        ClientPlayNetworking.registerGlobalReceiver(FabricPayloadRegistry.DICTIONARY_SYNC_S2C_TYPE, (payload, context) -> {
            FriendlyByteBuf buf = FabricPayloadRegistry.fromPayload(payload);
            try {
                DictionarySyncPayload dictPayload = DictionarySyncPayload.decode(buf);
                byte[] dict = dictPayload.dictionary();
                context.client().execute(() -> {
                    DictionaryManager.setAggregationDict(dict);
                    DebugLogger.debug(LogType.NETWORK, "Hassium: Received aggregation dictionary from server ({} bytes)", dict.length);
                });
            } catch (Exception e) {
                LOGGER.error("Hassium: Failed to decode dictionary sync packet", e);
            } finally {
                buf.release();
            }
        });
#endif

        // 注册握手响应
#if MC_VER < MC_1_20_5
        ClientPlayNetworking.registerGlobalReceiver(HANDSHAKE_S2C, (client, handler, buf, responseSender) -> {
            buf.readVarInt(); // protocolVersion
            boolean accepted = buf.readBoolean();
            boolean globalCompressionAccepted = buf.readBoolean();
            boolean compactHeaderAccepted = buf.readBoolean();
            LOGGER.info("Hassium: Client handshake response: accepted={}, globalCompression={}, compactHeader={}",
                    accepted, globalCompressionAccepted, compactHeaderAccepted);
        });
#else
        ClientPlayNetworking.registerGlobalReceiver(FabricPayloadRegistry.HANDSHAKE_S2C_TYPE, (payload, context) -> {
            FriendlyByteBuf buf = FabricPayloadRegistry.fromPayload(payload);
            try {
                buf.readVarInt(); // protocolVersion
                boolean accepted = buf.readBoolean();
                boolean globalCompressionAccepted = buf.readBoolean();
                boolean compactHeaderAccepted = buf.readBoolean();
                LOGGER.info("Hassium: Client handshake response: accepted={}, globalCompression={}, compactHeader={}",
                        accepted, globalCompressionAccepted, compactHeaderAccepted);
            } finally {
                buf.release();
            }
        });
#endif

        // 注册索引同步响应
#if MC_VER < MC_1_20_5
        ClientPlayNetworking.registerGlobalReceiver(INDEX_SYNC_S2C, (client, handler, buf, responseSender) -> {
            try {
                int dataLength = buf.readVarInt();
                byte[] data = new byte[dataLength];
                buf.readBytes(data);

                IndexSyncPacket syncPacket = IndexSyncPacket.decode(data);

                client.execute(() -> {
                    try {
                        String connectionId = "client";
                        IndexSyncManager indexSyncManager = IndexSyncManager.getInstance();
                        NamespaceIndexManager clientIndexManager = indexSyncManager.handleSyncPacket(connectionId, syncPacket);

                        Connection connection = Minecraft.getInstance().getConnection().getConnection();
                        HassiumConnectionRegistry.markEnabled(connection);
                        HassiumAggregationManager.init();

                        FriendlyByteBuf readyBuf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                        new CompressionReadyPayload(true).encode(readyBuf);
                        ClientPlayNetworking.send(CompressionReadyPayload.CHANNEL, readyBuf);
                        DebugLogger.debug(LogType.NETWORK,
                                "Hassium: Received index sync ({} types), sent compression ready",
                                clientIndexManager.size());
                    } catch (Exception e) {
                        LOGGER.error("Hassium: Failed to process index sync packet", e);
                    }
                });
            } catch (Exception e) {
                LOGGER.error("Hassium: Failed to decode index sync packet", e);
            }
        });
#else
        ClientPlayNetworking.registerGlobalReceiver(FabricPayloadRegistry.INDEX_SYNC_S2C_TYPE, (payload, context) -> {
            FriendlyByteBuf buf = FabricPayloadRegistry.fromPayload(payload);
            try {
                int dataLength = buf.readVarInt();
                byte[] data = new byte[dataLength];
                buf.readBytes(data);

                IndexSyncPacket syncPacket = IndexSyncPacket.decode(data);

                context.client().execute(() -> {
                    try {
                        String connectionId = "client";
                        IndexSyncManager indexSyncManager = IndexSyncManager.getInstance();
                        NamespaceIndexManager clientIndexManager = indexSyncManager.handleSyncPacket(connectionId, syncPacket);

                        Connection connection = Minecraft.getInstance().getConnection().getConnection();
                        HassiumConnectionRegistry.markEnabled(connection);
                        HassiumAggregationManager.init();

                        FriendlyByteBuf readyBuf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                        new CompressionReadyPayload(true).encode(readyBuf);
                        byte[] readyData = new byte[readyBuf.readableBytes()];
                        readyBuf.readBytes(readyData);
                        readyBuf.release();
                        ClientPlayNetworking.send(FabricPayloadRegistry.createPayload(FabricPayloadRegistry.COMPRESSION_READY_C2S_TYPE, readyData));
                        DebugLogger.debug(LogType.NETWORK,
                                "Hassium: Received index sync ({} types), sent compression ready",
                                clientIndexManager.size());
                    } catch (Exception e) {
                        LOGGER.error("Hassium: Failed to process index sync packet", e);
                    }
                });
            } catch (Exception e) {
                LOGGER.error("Hassium: Failed to decode index sync packet", e);
            } finally {
                buf.release();
            }
        });
#endif

        // 注册聚合包接收
#if MC_VER < MC_1_20_5
        ClientPlayNetworking.registerGlobalReceiver(AGGREGATION_S2C, (client, handler, buf, responseSender) -> {
            try {
                FriendlyByteBuf packetBuf = new FriendlyByteBuf(buf.copy());
                IndexSyncManager indexSyncManager = IndexSyncManager.getInstance();
                NamespaceIndexManager indexManager = indexSyncManager.getClientIndexManager();

                if (indexManager == null) {
                    LOGGER.error("Received aggregation packet but client index manager not initialized");
                    return;
                }

                HassiumAggregationPacket aggregationPacket = HassiumAggregationPacket.decode(packetBuf, indexManager);

                client.execute(() -> {
                    aggregationPacket.handle(handler.getConnection());
                });
            } catch (Exception e) {
                LOGGER.error("Failed to handle aggregation packet", e);
            }
        });
#else
        ClientPlayNetworking.registerGlobalReceiver(FabricPayloadRegistry.AGGREGATION_S2C_TYPE, (payload, context) -> {
            FriendlyByteBuf buf = FabricPayloadRegistry.fromPayload(payload);
            try {
                IndexSyncManager indexSyncManager = IndexSyncManager.getInstance();
                NamespaceIndexManager indexManager = indexSyncManager.getClientIndexManager();

                if (indexManager == null) {
                    LOGGER.error("Received aggregation packet but client index manager not initialized");
                    return;
                }

                HassiumAggregationPacket aggregationPacket = HassiumAggregationPacket.decode(buf, indexManager);

                context.client().execute(() -> {
                    aggregationPacket.handle(context.player().connection.getConnection());
                });
            } catch (Exception e) {
                LOGGER.error("Failed to handle aggregation packet", e);
            } finally {
                buf.release();
            }
        });
#endif

        // 注册区块哈希广播接收（阶段一）
#if MC_VER < MC_1_20_5
        ClientPlayNetworking.registerGlobalReceiver(CHUNK_HASH_S2C, (client, handler, buf, responseSender) -> {
            try {
                ChunkHashS2CPacket packet = ChunkHashS2CPacket.decode(buf);
                client.execute(() -> ClientMetadataHandler.handleChunkHashPacket(packet));
            } catch (Exception e) {
                LOGGER.error("[CLIENT] Failed to handle chunk hash packet", e);
            }
        });
#else
        ClientPlayNetworking.registerGlobalReceiver(FabricPayloadRegistry.CHUNK_HASH_S2C_TYPE, (payload, context) -> {
            FriendlyByteBuf buf = FabricPayloadRegistry.fromPayload(payload);
            try {
                ChunkHashS2CPacket packet = ChunkHashS2CPacket.decode(buf);
                context.client().execute(() -> ClientMetadataHandler.handleChunkHashPacket(packet));
            } catch (Exception e) {
                LOGGER.error("[CLIENT] Failed to handle chunk hash packet", e);
            } finally {
                buf.release();
            }
        });
#endif

        // 注册分段增量响应接收（阶段二）
#if MC_VER < MC_1_20_5
        ClientPlayNetworking.registerGlobalReceiver(SECTION_DELTA_S2C, (client, handler, buf, responseSender) -> {
            try {
                SectionDeltaS2CPacket packet = SectionDeltaS2CPacket.decode(buf);
                client.execute(() -> ClientMetadataHandler.handleSectionDeltaPacket(packet));
            } catch (Exception e) {
                LOGGER.error("[CLIENT] Failed to handle section delta packet", e);
            }
        });
#else
        ClientPlayNetworking.registerGlobalReceiver(FabricPayloadRegistry.SECTION_DELTA_S2C_TYPE, (payload, context) -> {
            FriendlyByteBuf buf = FabricPayloadRegistry.fromPayload(payload);
            try {
                SectionDeltaS2CPacket packet = SectionDeltaS2CPacket.decode(buf);
                context.client().execute(() -> ClientMetadataHandler.handleSectionDeltaPacket(packet));
            } catch (Exception e) {
                LOGGER.error("[CLIENT] Failed to handle section delta packet", e);
            } finally {
                buf.release();
            }
        });
#endif

        // 注册 blockEntity 数据响应接收
#if MC_VER < MC_1_20_5
        ClientPlayNetworking.registerGlobalReceiver(BLOCK_ENTITY_DATA_S2C, (client, handler, buf, responseSender) -> {
            try {
                BlockEntityDataS2CPacket packet = BlockEntityDataS2CPacket.decode(buf);
                client.execute(() -> ClientMetadataHandler.handleBlockEntityDataPacket(packet));
            } catch (Exception e) {
                LOGGER.error("[CLIENT] Failed to handle block entity data packet", e);
            }
        });
#else
        ClientPlayNetworking.registerGlobalReceiver(FabricPayloadRegistry.BLOCK_ENTITY_DATA_S2C_TYPE, (payload, context) -> {
            FriendlyByteBuf buf = FabricPayloadRegistry.fromPayload(payload);
            try {
                BlockEntityDataS2CPacket packet = BlockEntityDataS2CPacket.decode(buf);
                context.client().execute(() -> ClientMetadataHandler.handleBlockEntityDataPacket(packet));
            } catch (Exception e) {
                LOGGER.error("[CLIENT] Failed to handle block entity data packet", e);
            } finally {
                buf.release();
            }
        });
#endif

        // 注册光照增量通知接收
#if MC_VER < MC_1_20_5
        ClientPlayNetworking.registerGlobalReceiver(LIGHT_DELTA_S2C, (client, handler, buf, responseSender) -> {
            try {
                LightDeltaS2CPacket packet = LightDeltaS2CPacket.decode(buf);
                client.execute(() -> ClientMetadataHandler.handleLightDeltaPacket(packet));
            } catch (Exception e) {
                LOGGER.error("[CLIENT] Failed to handle light delta packet", e);
            }
        });
#else
        ClientPlayNetworking.registerGlobalReceiver(FabricPayloadRegistry.LIGHT_DELTA_S2C_TYPE, (payload, context) -> {
            FriendlyByteBuf buf = FabricPayloadRegistry.fromPayload(payload);
            try {
                LightDeltaS2CPacket packet = LightDeltaS2CPacket.decode(buf);
                context.client().execute(() -> ClientMetadataHandler.handleLightDeltaPacket(packet));
            } catch (Exception e) {
                LOGGER.error("[CLIENT] Failed to handle light delta packet", e);
            } finally {
                buf.release();
            }
        });
#endif
    }

    @Override
    public void sendHandshakeRequest() {
        if (!HassiumConfigService.getInstance().isNetworkCompressionEnabled()) {
            LOGGER.debug("Hassium: Skip handshake — network.enabled=false");
            return;
        }
        if (Minecraft.getInstance().getConnection() != null) {
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            buf.writeVarInt(Constants.CURRENT_PROTOCOL_VERSION);
            buf.writeUtf(Constants.MOD_VERSION);
            String compressionAlgorithm = HassiumConfigService.getInstance().getCompressionAlgorithm();
            String dictAlgorithm = compressionAlgorithm + "_dict";
            buf.writeVarInt(2); // 支持的算法数量
            buf.writeUtf(compressionAlgorithm);
            buf.writeUtf(dictAlgorithm);
            buf.writeBoolean(true);  // clientCacheSupported
            buf.writeBoolean(true);  // chunkRevisionSupported
            buf.writeBoolean(false); // scheme127Supported
            buf.writeBoolean(true);  // globalPacketCompressionSupported
            buf.writeBoolean(true);  // compactHeaderSupported

#if MC_VER < MC_1_20_5
            ClientPlayNetworking.send(HANDSHAKE_C2S, buf);
#else
            ClientPlayNetworking.send(FabricPayloadRegistry.toPayload(FabricPayloadRegistry.HANDSHAKE_C2S_TYPE, buf));
#endif
            LOGGER.debug("Hassium: Sent handshake request to server");
        }
    }

    @Override
    public void sendChunkDataRequest(FriendlyByteBuf buf) {
        if (Minecraft.getInstance().getConnection() != null) {
#if MC_VER < MC_1_20_5
            ClientPlayNetworking.send(CHUNK_DATA_REQUEST_C2S, buf);
#else
            ClientPlayNetworking.send(FabricPayloadRegistry.toPayload(FabricPayloadRegistry.CHUNK_DATA_REQUEST_C2S_TYPE, buf));
#endif
            LOGGER.debug("Hassium: Sent chunk data request");
        } else {
            // 连接不存在，释放缓冲区
            buf.release();
        }
    }

    @Override
    public void sendCompressedPayload(CompressedPayloadPacket packet) {
        throw new UnsupportedOperationException("Use sendCompressedChunk() instead");
    }

    @Override
    public void sendChunkHashPacket(ServerPlayer player, FriendlyByteBuf buf) {
#if MC_VER < MC_1_20_5
        ServerPlayNetworking.send(player, CHUNK_HASH_S2C, buf);
#else
        ServerPlayNetworking.send(player, FabricPayloadRegistry.toPayload(FabricPayloadRegistry.CHUNK_HASH_S2C_TYPE, buf));
#endif
    }

    @Override
    public void sendSectionHashRequest(FriendlyByteBuf buf) {
        if (Minecraft.getInstance().getConnection() != null) {
#if MC_VER < MC_1_20_5
            ClientPlayNetworking.send(SECTION_HASH_REQUEST_C2S, buf);
#else
            ClientPlayNetworking.send(FabricPayloadRegistry.toPayload(FabricPayloadRegistry.SECTION_HASH_REQUEST_C2S_TYPE, buf));
#endif
        } else {
            buf.release();
        }
    }

    @Override
    public void sendSectionDeltaPacket(ServerPlayer player, FriendlyByteBuf buf) {
#if MC_VER < MC_1_20_5
        ServerPlayNetworking.send(player, SECTION_DELTA_S2C, buf);
#else
        ServerPlayNetworking.send(player, FabricPayloadRegistry.toPayload(FabricPayloadRegistry.SECTION_DELTA_S2C_TYPE, buf));
#endif
    }

    @Override
    public void sendBlockEntityRequest(FriendlyByteBuf buf) {
        if (Minecraft.getInstance().getConnection() != null) {
#if MC_VER < MC_1_20_5
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
#if MC_VER < MC_1_20_5
        ServerPlayNetworking.send(player, BLOCK_ENTITY_DATA_S2C, buf);
#else
        ServerPlayNetworking.send(player, FabricPayloadRegistry.toPayload(FabricPayloadRegistry.BLOCK_ENTITY_DATA_S2C_TYPE, buf));
#endif
    }

    @Override
    public void sendLightDeltaPacket(ServerPlayer player, FriendlyByteBuf buf) {
#if MC_VER < MC_1_20_5
        ServerPlayNetworking.send(player, LIGHT_DELTA_S2C, buf);
#else
        ServerPlayNetworking.send(player, FabricPayloadRegistry.toPayload(FabricPayloadRegistry.LIGHT_DELTA_S2C_TYPE, buf));
#endif
    }

    /**
     * 发送压缩的区块数据到指定玩家
     */
    public static void sendCompressedChunk(ServerPlayer player, ChunkCompressionHandler.CompressedChunkData compressed) {
        try {
            DebugLogger.info(LogType.COMPRESSION,
                    "[SEND_CHUNK] Sending compressed chunk [{}, {}] to player {} (compressedSize={}, algorithm={})",
                    compressed.chunkX, compressed.chunkZ, player.getName().getString(),
                    compressed.compressedData.length, compressed.algorithm);

            byte[] data = compressed.encode();
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            buf.writeVarInt(data.length);
            buf.writeBytes(data);

            DebugLogger.debug(LogType.NETWORK, "[SEND_CHUNK] Encoded chunk data ({} bytes), sending via network", data.length);
#if MC_VER < MC_1_20_5
            ServerPlayNetworking.send(player, CHUNK_PAYLOAD_S2C, buf);
            DebugLogger.debug(LogType.NETWORK, "[SEND_CHUNK] Successfully sent compressed chunk [{}, {}] to player {}",
                    compressed.chunkX, compressed.chunkZ, player.getName().getString());
#else
            ServerPlayNetworking.send(player, FabricPayloadRegistry.toPayload(FabricPayloadRegistry.CHUNK_PAYLOAD_S2C_TYPE, buf));
            DebugLogger.debug(LogType.NETWORK, "[SEND_CHUNK] Successfully sent compressed chunk [{}, {}] to player {}",
                    compressed.chunkX, compressed.chunkZ, player.getName().getString());
#endif
        } catch (Exception e) {
            LOGGER.error("[SEND_CHUNK] Failed to send compressed chunk to player {}", player.getName().getString(), e);
        }
    }

    /**
     * 发送字典同步包到指定玩家
     */
    private void sendDictionarySyncPacket(ServerPlayer player) {
        try {
            byte[] aggregationDict = DictionaryManager.getAggregationDict();

            DictionarySyncPayload payload = new DictionarySyncPayload(aggregationDict, false);
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            payload.encode(buf);
#if MC_VER < MC_1_20_5
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
    private void sendDictionarySyncPacket(ServerPlayer player, byte[] dictionary) {
        try {
            DictionarySyncPayload payload = new DictionarySyncPayload(dictionary, false);
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            payload.encode(buf);
#if MC_VER < MC_1_20_5
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
    private void sendIndexSyncPacket(ServerPlayer player) {
        try {
            IndexSyncManager indexSyncManager = IndexSyncManager.getInstance();
            indexSyncManager.initializeServerIndex();

            IndexSyncPacket syncPacket = indexSyncManager.createSyncPacket();
            byte[] data = syncPacket.encode();

            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            buf.writeVarInt(data.length);
            buf.writeBytes(data);
#if MC_VER < MC_1_20_5
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

    /**
     * 注册服务端网络通道
     */
    private void registerServerChannels() {
        // 注册握手请求
#if MC_VER < MC_1_20_5
        ServerPlayNetworking.registerGlobalReceiver(HANDSHAKE_C2S, (server, player, handler, buf, sender) -> {
            int protocolVersion = buf.readVarInt();
            String modVersion = buf.readUtf();
            int algoCount = buf.readVarInt();
            String[] algorithms = new String[algoCount];
            for (int i = 0; i < algoCount; i++) {
                algorithms[i] = buf.readUtf();
            }
            boolean clientCache = buf.readBoolean();
            boolean chunkRevision = buf.readBoolean();
            boolean scheme127 = buf.readBoolean();
            boolean globalPacketCompression = buf.readBoolean();
            boolean compactHeader = buf.readBoolean();

            DebugLogger.debug(LogType.NETWORK,
                    "[HANDSHAKE] Details from {}: protocol={}, modVersion={}, algorithms={}, clientCache={}, globalCompression={}, compactHeader={}",
                    player.getName().getString(), protocolVersion, modVersion, String.join(", ", algorithms),
                    clientCache, globalPacketCompression, compactHeader);

            // 启用该玩家的压缩
            PlayerCompressionTracker.enableCompression(player);
            // 初始 trackChunk 常早于握手：主线程补发视距内 chunkHash
            server.execute(() -> ServerChunkPushManager.getInstance().resyncTrackedChunks(player));

            // 检查是否支持全局压缩
            boolean serverSupportsGlobalCompression = HassiumConfigService.getInstance().isGlobalPacketCompressionEnabled();
            boolean useGlobalCompression = serverSupportsGlobalCompression && globalPacketCompression;

            // 检查是否支持紧凑包头
            boolean serverSupportsCompactHeader = HassiumConfigService.getInstance().isCompactHeaderEnabled();
            boolean useCompactHeader = serverSupportsCompactHeader && compactHeader;

            boolean accepted = true;

            // 发送握手响应
            server.execute(() -> {
                FriendlyByteBuf response = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                response.writeVarInt(Constants.CURRENT_PROTOCOL_VERSION);
                response.writeBoolean(accepted);
                response.writeBoolean(useGlobalCompression);
                response.writeBoolean(useCompactHeader);
                ServerPlayNetworking.send(player, HANDSHAKE_S2C, response);
                LOGGER.info("Hassium: Server handshake for {}: accepted={}, globalCompression={}, compactHeader={}",
                        player.getName().getString(), accepted, useGlobalCompression, useCompactHeader);

                // 如果启用全局压缩，发送字典和索引同步
                if (useGlobalCompression) {
                    DictionaryManager.init();
                    sendDictionarySyncPacket(player);

                    IndexSyncManager indexSyncManager = IndexSyncManager.getInstance();
                    indexSyncManager.initializeServerIndex();
                    sendIndexSyncPacket(player);

                    Connection connection = getPlayerConnection(player);
                    if (connection != null) {
                        HassiumConnectionRegistry.markPending(connection);
                        HassiumAggregationManager.init();
                        DebugLogger.debug(LogType.NETWORK,
                                "Hassium: Marked connection as PENDING for player {}", player.getName().getString());

                        // 安全超时
                        String playerName = player.getName().getString();
                        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                            Thread t = new Thread(r, "Hassium-PendingTimeout");
                            t.setDaemon(true);
                            return t;
                        }).schedule(() -> {
                            if (HassiumConnectionRegistry.tryDemoteFromPending(connection)) {
                                HassiumAggregationManager.discardConnection(connection);
                                LOGGER.warn("Hassium: Ack timeout for {}, disabling aggregation", playerName);
                            }
                        }, 5, java.util.concurrent.TimeUnit.SECONDS);
                    }
                }
            });
        });
#else
        ServerPlayNetworking.registerGlobalReceiver(FabricPayloadRegistry.HANDSHAKE_C2S_TYPE, (payload, context) -> {
            FriendlyByteBuf buf = FabricPayloadRegistry.fromPayload(payload);
            try {
                int protocolVersion = buf.readVarInt();
                String modVersion = buf.readUtf();
                int algoCount = buf.readVarInt();
                String[] algorithms = new String[algoCount];
                for (int i = 0; i < algoCount; i++) {
                    algorithms[i] = buf.readUtf();
                }
                boolean clientCache = buf.readBoolean();
                boolean chunkRevision = buf.readBoolean();
                boolean scheme127 = buf.readBoolean();
                boolean globalPacketCompression = buf.readBoolean();
                boolean compactHeader = buf.readBoolean();

                ServerPlayer player = context.player();
                net.minecraft.server.MinecraftServer server = io.github.limuqy.mc.hassium.compat.PlayerCompat.getMinecraftServer(player);

                DebugLogger.debug(LogType.NETWORK,
                        "[HANDSHAKE] Details from {}: protocol={}, modVersion={}, algorithms={}, clientCache={}, globalCompression={}, compactHeader={}",
                        player.getName().getString(), protocolVersion, modVersion, String.join(", ", algorithms),
                        clientCache, globalPacketCompression, compactHeader);

                // 启用该玩家的压缩
                PlayerCompressionTracker.enableCompression(player);
                // 初始 trackChunk 常早于握手：主线程补发视距内 chunkHash
                server.execute(() -> ServerChunkPushManager.getInstance().resyncTrackedChunks(player));

                // 检查是否支持全局压缩
                boolean serverSupportsGlobalCompression = HassiumConfigService.getInstance().isGlobalPacketCompressionEnabled();
                boolean useGlobalCompression = serverSupportsGlobalCompression && globalPacketCompression;

                // 检查是否支持紧凑包头
                boolean serverSupportsCompactHeader = HassiumConfigService.getInstance().isCompactHeaderEnabled();
                boolean useCompactHeader = serverSupportsCompactHeader && compactHeader;

                boolean accepted = true;

                // 发送握手响应
                server.execute(() -> {
                    FriendlyByteBuf response = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                    response.writeVarInt(Constants.CURRENT_PROTOCOL_VERSION);
                    response.writeBoolean(accepted);
                    response.writeBoolean(useGlobalCompression);
                    response.writeBoolean(useCompactHeader);
                    ServerPlayNetworking.send(player, FabricPayloadRegistry.toPayload(FabricPayloadRegistry.HANDSHAKE_S2C_TYPE, response));
                    LOGGER.info("Hassium: Server handshake for {}: accepted={}, globalCompression={}, compactHeader={}",
                            player.getName().getString(), accepted, useGlobalCompression, useCompactHeader);

                    // 如果启用全局压缩，发送字典和索引同步
                    if (useGlobalCompression) {
                        DictionaryManager.init();
                        sendDictionarySyncPacket(player);

                        IndexSyncManager indexSyncManager = IndexSyncManager.getInstance();
                        indexSyncManager.initializeServerIndex();
                        sendIndexSyncPacket(player);

                        Connection connection = getPlayerConnection(player);
                        if (connection != null) {
                            HassiumConnectionRegistry.markPending(connection);
                            HassiumAggregationManager.init();
                            DebugLogger.debug(LogType.NETWORK,
                                    "Hassium: Marked connection as PENDING for player {}", player.getName().getString());

                            // 安全超时
                            String playerName = player.getName().getString();
                            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                                Thread t = new Thread(r, "Hassium-PendingTimeout");
                                t.setDaemon(true);
                                return t;
                            }).schedule(() -> {
                                if (HassiumConnectionRegistry.tryDemoteFromPending(connection)) {
                                    HassiumAggregationManager.discardConnection(connection);
                                    LOGGER.warn("Hassium: Ack timeout for {}, disabling aggregation", playerName);
                                }
                            }, 5, java.util.concurrent.TimeUnit.SECONDS);
                        }
                    }
                });
            } catch (Exception e) {
                LOGGER.error("[HANDSHAKE] Failed to handle handshake packet", e);
            } finally {
                buf.release();
            }
        });
#endif

        // 注册压缩就绪确认
#if MC_VER < MC_1_20_5
        ServerPlayNetworking.registerGlobalReceiver(CompressionReadyPayload.CHANNEL, (server, player, handler, buf, sender) -> {
            CompressionReadyPayload payload = CompressionReadyPayload.decode(buf);
            DebugLogger.debug(LogType.NETWORK, "Hassium: Received compression ready from player {}, ready: {}",
                    player.getName().getString(), payload.isReady());

            if (payload.isReady()) {
                Connection connection = getPlayerConnection(player);
                if (connection != null) {
                    HassiumConnectionRegistry.markEnabled(connection);
                    HassiumAggregationManager.flushConnection(connection);
                    DebugLogger.debug(LogType.NETWORK,
                            "Hassium: Marked connection as ENABLED for player {}, flushing buffered packets",
                            player.getName().getString());
                }
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
                    Connection connection = getPlayerConnection(context.player());
                    if (connection != null) {
                        HassiumConnectionRegistry.markEnabled(connection);
                        HassiumAggregationManager.flushConnection(connection);
                        DebugLogger.debug(LogType.NETWORK,
                                "Hassium: Marked connection as ENABLED for player {}, flushing buffered packets",
                                context.player().getName().getString());
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Failed to handle compression ready packet", e);
            } finally {
                buf.release();
            }
        });
#endif

        // 注册区块数据请求（新协议）
#if MC_VER < MC_1_20_5
        ServerPlayNetworking.registerGlobalReceiver(CHUNK_DATA_REQUEST_C2S, (server, player, handler, buf, sender) -> {
            try {
                DebugLogger.debug(LogType.NETWORK, "[SERVER] Received chunk data request from player {}",
                        player.getName().getString());
                ChunkDataRequestC2SPacket request = ChunkDataRequestC2SPacket.decode(
                        new net.minecraft.network.FriendlyByteBuf(buf.copy()));
                DebugLogger.debug(LogType.NETWORK, "[SERVER] Decoded chunk data request: {} chunks, dimension={}",
                        request.chunks().size(), request.dimension());

                server.execute(() -> {
                    try {
                        ServerChunkPushManager.getInstance().enqueueDataRequest(
                                player, request.dimension(), request.chunks());
                    } catch (Exception e) {
                        LOGGER.error("[SERVER] Failed to handle chunk data request", e);
                    }
                });
            } catch (Exception e) {
                LOGGER.error("[SERVER] Failed to decode chunk data request", e);
            }
        });
#else
        ServerPlayNetworking.registerGlobalReceiver(FabricPayloadRegistry.CHUNK_DATA_REQUEST_C2S_TYPE, (payload, context) -> {
            FriendlyByteBuf buf = FabricPayloadRegistry.fromPayload(payload);
            try {
                ServerPlayer player = context.player();
                net.minecraft.server.MinecraftServer server = io.github.limuqy.mc.hassium.compat.PlayerCompat.getMinecraftServer(player);
                DebugLogger.debug(LogType.NETWORK, "[SERVER] Received chunk data request from player {}",
                        player.getName().getString());
                ChunkDataRequestC2SPacket request = ChunkDataRequestC2SPacket.decode(buf);
                DebugLogger.debug(LogType.NETWORK, "[SERVER] Decoded chunk data request: {} chunks, dimension={}",
                        request.chunks().size(), request.dimension());

                server.execute(() -> {
                    try {
                        ServerChunkPushManager.getInstance().enqueueDataRequest(
                                player, request.dimension(), request.chunks());
                    } catch (Exception e) {
                        LOGGER.error("[SERVER] Failed to handle chunk data request", e);
                    }
                });
            } catch (Exception e) {
                LOGGER.error("[SERVER] Failed to decode chunk data request", e);
            } finally {
                buf.release();
            }
        });
#endif

        // 注册 section 哈希请求（阶段二）
#if MC_VER < MC_1_20_5
        ServerPlayNetworking.registerGlobalReceiver(SECTION_HASH_REQUEST_C2S, (server, player, handler, buf, sender) -> {
            try {
                SectionHashRequestC2SPacket request = SectionHashRequestC2SPacket.decode(
                        new net.minecraft.network.FriendlyByteBuf(buf.copy()));

                server.execute(() -> {
                    try {
                        ServerChunkPushManager.getInstance().handleSectionHashRequest(
                                player, request);
                    } catch (Exception e) {
                        LOGGER.error("[SERVER] Failed to handle section hash request", e);
                    }
                });
            } catch (Exception e) {
                LOGGER.error("[SERVER] Failed to decode section hash request", e);
            }
        });
#else
        ServerPlayNetworking.registerGlobalReceiver(FabricPayloadRegistry.SECTION_HASH_REQUEST_C2S_TYPE, (payload, context) -> {
            FriendlyByteBuf buf = FabricPayloadRegistry.fromPayload(payload);
            try {
                ServerPlayer player = context.player();
                net.minecraft.server.MinecraftServer server = io.github.limuqy.mc.hassium.compat.PlayerCompat.getMinecraftServer(player);
                SectionHashRequestC2SPacket request = SectionHashRequestC2SPacket.decode(buf);

                server.execute(() -> {
                    try {
                        ServerChunkPushManager.getInstance().handleSectionHashRequest(
                                player, request);
                    } catch (Exception e) {
                        LOGGER.error("[SERVER] Failed to handle section hash request", e);
                    }
                });
            } catch (Exception e) {
                LOGGER.error("[SERVER] Failed to decode section hash request", e);
            } finally {
                buf.release();
            }
        });
#endif

        // 注册 blockEntity 数据请求
#if MC_VER < MC_1_20_5
        ServerPlayNetworking.registerGlobalReceiver(BLOCK_ENTITY_REQUEST_C2S, (server, player, handler, buf, sender) -> {
            try {
                BlockEntityRequestC2SPacket request = BlockEntityRequestC2SPacket.decode(
                        new net.minecraft.network.FriendlyByteBuf(buf.copy()));

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
