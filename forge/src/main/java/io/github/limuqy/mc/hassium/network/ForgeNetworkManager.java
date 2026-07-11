package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;

/**
 * Forge 平台网络管理器实现
 */
public class ForgeNetworkManager implements NetworkManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/Network");
    private static final String PROTOCOL_VERSION = "1";

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
        try {
            Field connectionField = player.connection.getClass().getDeclaredField("connection");
            connectionField.setAccessible(true);
            return (Connection) connectionField.get(player.connection);
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to get connection from player", e);
            return null;
        }
    }

    // 创建网络通道
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Constants.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int packetId = 0;

    @Override
    public void registerChannels() {
        LOGGER.info("Hassium: Registering Forge network channels");

        // 注册握手请求数据包
        CHANNEL.<HandshakePacket>registerMessage(
                packetId++,
                HandshakePacket.class,
                HandshakePacket::encode,
                HandshakePacket::decode,
                (msg, ctx) -> ctx.get().enqueueWork(() -> {
                    ServerPlayer player = ctx.get().getSender();
                    if (player == null) {
                        LOGGER.error("Hassium: Received handshake from non-player");
                        return;
                    }

                    LOGGER.info("Hassium: Received handshake from client {}, protocol: {}, globalCompression: {}, compactHeader: {}",
                            player.getName().getString(), msg.protocolVersion(), msg.globalPacketCompressionSupported(), msg.compactHeaderSupported());

                    // 启用该玩家的压缩
                    PlayerCompressionTracker.enableCompression(player);

                    // 检查是否支持全局压缩
                    boolean serverSupportsGlobalCompression = HassiumConfigService.getInstance().isGlobalPacketCompressionEnabled();
                    boolean useGlobalCompression = serverSupportsGlobalCompression && msg.globalPacketCompressionSupported();

                    // 检查是否支持紧凑包头
                    boolean serverSupportsCompactHeader = HassiumConfigService.getInstance().isCompactHeaderEnabled();
                    boolean useCompactHeader = serverSupportsCompactHeader && msg.compactHeaderSupported();

                    // 发送握手响应
                    HandshakeResponsePacket response = new HandshakeResponsePacket(
                            Constants.CURRENT_PROTOCOL_VERSION,
                            true, // 接受
                            useGlobalCompression,
                            useCompactHeader
                    );
                    CHANNEL.sendTo(response, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
                    LOGGER.info("Hassium: Sent handshake response to client {}, globalCompression: {}, compactHeader: {}",
                            player.getName().getString(), useGlobalCompression, useCompactHeader);

                    // 如果协商成功，在事件循环立即切换 pipeline
                    if (useGlobalCompression) {
                        Connection connection = getPlayerConnection(player);
                        if (connection != null) {
                            io.netty.channel.Channel channel = getConnectionChannel(connection);
                            if (channel != null) {
                                channel.eventLoop().execute(() -> {
                                    int threshold = HassiumConfigService.getInstance().getGlobalCompressionThreshold();
                                    int level = HassiumConfigService.getInstance().getGlobalCompressionLevel();
                                    ZstdNegotiationTracker.markNegotiated(channel);
                                    ZstdPipelineSwitcher.switchToZstd(channel, threshold, level);
                                    LOGGER.info("Hassium: Switched to ZSTD compression for player {}", player.getName().getString());
                                });
                            }
                        }
                    }
                })
        );

        // 注册握手响应数据包（客户端接收）
        CHANNEL.<HandshakeResponsePacket>registerMessage(
                packetId++,
                HandshakeResponsePacket.class,
                HandshakeResponsePacket::encode,
                HandshakeResponsePacket::decode,
                (msg, ctx) -> {
                    LOGGER.info("Hassium: Received handshake response, accepted: {}, globalCompression: {}, compactHeader: {}",
                            msg.accepted(), msg.globalCompressionAccepted(), msg.compactHeaderAccepted());

                    // 如果全局压缩被接受，立即切换 pipeline
                    if (msg.accepted() && msg.globalCompressionAccepted()) {
                        if (Minecraft.getInstance().getConnection() != null) {
                            Connection connection = Minecraft.getInstance().getConnection().getConnection();
                            if (connection != null) {
                                io.netty.channel.Channel channel = getConnectionChannel(connection);
                                if (channel != null) {
                                    int threshold = HassiumConfigService.getInstance().getGlobalCompressionThreshold();
                                    int level = HassiumConfigService.getInstance().getGlobalCompressionLevel();
                                    ZstdNegotiationTracker.markNegotiated(channel);
                                    ZstdPipelineSwitcher.switchToZstd(channel, threshold, level);
                                    LOGGER.info("Hassium: Client switched to ZSTD compression");
                                }
                            }
                        }
                    }
                    ctx.get().setPacketHandled(true);
                }
        );

        // 注册压缩区块数据包
        CHANNEL.<CompressedPayloadWrapper>registerMessage(
                packetId++,
                CompressedPayloadWrapper.class,
                CompressedPayloadWrapper::encode,
                CompressedPayloadWrapper::decode,
                (msg, ctx) -> ctx.get().enqueueWork(() -> {
                    try {
                        ClientChunkHandler.handleCompressedChunk(msg.data());
                    } catch (Exception e) {
                        LOGGER.error("Hassium: Failed to handle compressed payload", e);
                    }
                })
        );

        // 注册区块元数据包（服务端 -> 客户端）
        CHANNEL.<MetadataWrapper>registerMessage(
                packetId++,
                MetadataWrapper.class,
                MetadataWrapper::encode,
                MetadataWrapper::decode,
                (msg, ctx) -> ctx.get().enqueueWork(() -> {
                    try {
                        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
                        ChunkMetadataS2CPacket packet = ChunkMetadataS2CPacket.decode(buf);
                        ClientMetadataHandler.handleMetadataPacket(packet);
                    } catch (Exception e) {
                        LOGGER.error("Hassium: Failed to handle metadata packet", e);
                    }
                    ctx.get().setPacketHandled(true);
                })
        );

        // 注册区块数据请求包（客户端 -> 服务端）
        CHANNEL.<DataRequestWrapper>registerMessage(
                packetId++,
                DataRequestWrapper.class,
                DataRequestWrapper::encode,
                DataRequestWrapper::decode,
                (msg, ctx) -> ctx.get().enqueueWork(() -> {
                    try {
                        ServerPlayer player = ctx.get().getSender();
                        if (player == null) return;

                        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
                        ChunkDataRequestC2SPacket request = ChunkDataRequestC2SPacket.decode(buf);
                        ServerChunkPushManager.getInstance().enqueueDataRequest(
                                player, request.dimension(), request.chunks());
                    } catch (Exception e) {
                        LOGGER.error("Hassium: Failed to handle chunk data request", e);
                    }
                    ctx.get().setPacketHandled(true);
                })
        );

        // 注册区块哈希广播包（阶段一，服务端 -> 客户端）
        CHANNEL.<ChunkHashWrapper>registerMessage(
                packetId++,
                ChunkHashWrapper.class,
                ChunkHashWrapper::encode,
                ChunkHashWrapper::decode,
                (msg, ctx) -> ctx.get().enqueueWork(() -> {
                    try {
                        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
                        ChunkHashS2CPacket packet = ChunkHashS2CPacket.decode(buf);
                        ClientMetadataHandler.handleChunkHashPacket(packet);
                    } catch (Exception e) {
                        LOGGER.error("Hassium: Failed to handle chunk hash packet", e);
                    }
                    ctx.get().setPacketHandled(true);
                })
        );

        // 注册 section 哈希请求包（阶段二，客户端 -> 服务端）
        CHANNEL.<SectionHashRequestWrapper>registerMessage(
                packetId++,
                SectionHashRequestWrapper.class,
                SectionHashRequestWrapper::encode,
                SectionHashRequestWrapper::decode,
                (msg, ctx) -> ctx.get().enqueueWork(() -> {
                    try {
                        ServerPlayer player = ctx.get().getSender();
                        if (player == null) return;

                        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
                        SectionHashRequestC2SPacket request = SectionHashRequestC2SPacket.decode(buf);
                        ServerChunkPushManager.getInstance().handleSectionHashRequest(player, request);
                    } catch (Exception e) {
                        LOGGER.error("Hassium: Failed to handle section hash request", e);
                    }
                    ctx.get().setPacketHandled(true);
                })
        );

        // 注册 section delta 响应包（阶段二，服务端 -> 客户端）
        CHANNEL.<SectionDeltaWrapper>registerMessage(
                packetId++,
                SectionDeltaWrapper.class,
                SectionDeltaWrapper::encode,
                SectionDeltaWrapper::decode,
                (msg, ctx) -> ctx.get().enqueueWork(() -> {
                    try {
                        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
                        SectionDeltaS2CPacket packet = SectionDeltaS2CPacket.decode(buf);
                        ClientMetadataHandler.handleSectionDeltaPacket(packet);
                    } catch (Exception e) {
                        LOGGER.error("Hassium: Failed to handle section delta packet", e);
                    }
                    ctx.get().setPacketHandled(true);
                })
        );

        // 注册 blockEntity 数据请求包（客户端 -> 服务端）
        CHANNEL.<BlockEntityRequestWrapper>registerMessage(
                packetId++,
                BlockEntityRequestWrapper.class,
                BlockEntityRequestWrapper::encode,
                BlockEntityRequestWrapper::decode,
                (msg, ctx) -> ctx.get().enqueueWork(() -> {
                    try {
                        ServerPlayer player = ctx.get().getSender();
                        if (player != null) {
                            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
                            BlockEntityRequestC2SPacket request = BlockEntityRequestC2SPacket.decode(buf);
                            ServerChunkPushManager.getInstance().handleBlockEntityRequest(player, request);
                        }
                    } catch (Exception e) {
                        LOGGER.error("Hassium: Failed to handle block entity request", e);
                    }
                    ctx.get().setPacketHandled(true);
                })
        );

        // 注册 blockEntity 数据响应包（服务端 -> 客户端）
        CHANNEL.<BlockEntityDataWrapper>registerMessage(
                packetId++,
                BlockEntityDataWrapper.class,
                BlockEntityDataWrapper::encode,
                BlockEntityDataWrapper::decode,
                (msg, ctx) -> ctx.get().enqueueWork(() -> {
                    try {
                        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
                        BlockEntityDataS2CPacket packet = BlockEntityDataS2CPacket.decode(buf);
                        ClientMetadataHandler.handleBlockEntityDataPacket(packet);
                    } catch (Exception e) {
                        LOGGER.error("Hassium: Failed to handle block entity data packet", e);
                    }
                    ctx.get().setPacketHandled(true);
                })
        );

        LOGGER.info("Hassium: Registered {} network packets", packetId);
    }

    @Override
    public void sendHandshakeRequest() {
        String compressionAlgorithm = HassiumConfigService.getInstance().getCompressionAlgorithm();
        String dictAlgorithm = compressionAlgorithm + "_dict";
        HandshakePacket packet = new HandshakePacket(
                Constants.CURRENT_PROTOCOL_VERSION,
                Constants.MOD_VERSION,
                new String[]{compressionAlgorithm, dictAlgorithm},
                true,  // clientCacheSupported
                true,  // chunkRevisionSupported
                false, // scheme127Supported
                true,  // globalPacketCompressionSupported
                true   // compactHeaderSupported
        );
        CHANNEL.sendToServer(packet);
        LOGGER.debug("Hassium: Sent handshake request to server");
    }

    @Override
    public void sendMetadataPacket(FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
        // 服务端调用，需要指定玩家
        throw new UnsupportedOperationException("Use ForgeNetworkManagerService.sendMetadataPacket() instead");
    }

    @Override
    public void sendChunkDataRequest(FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
        CHANNEL.sendToServer(new DataRequestWrapper(data));
        LOGGER.debug("Hassium: Sent chunk data request");
    }

    @Override
    public void sendCompressedPayload(CompressedPayloadPacket packet) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void sendChunkHashPacket(ServerPlayer player, FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
        CHANNEL.sendTo(new ChunkHashWrapper(data), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
    }

    @Override
    public void sendSectionHashRequest(FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
        CHANNEL.sendToServer(new SectionHashRequestWrapper(data));
    }

    @Override
    public void sendSectionDeltaPacket(ServerPlayer player, FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
        CHANNEL.sendTo(new SectionDeltaWrapper(data), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
    }

    @Override
    public void sendBlockEntityRequest(FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
        CHANNEL.sendToServer(new BlockEntityRequestWrapper(data));
    }

    @Override
    public void sendBlockEntityData(ServerPlayer player, FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
        CHANNEL.sendTo(new BlockEntityDataWrapper(data), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
    }

    /**
     * 发送压缩的区块数据到指定玩家
     */
    public static void sendCompressedChunk(ServerPlayer player, ChunkCompressionHandler.CompressedChunkData compressed) {
        try {
            byte[] data = compressed.encode();
            CHANNEL.sendTo(new CompressedPayloadWrapper(data), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
            LOGGER.debug("Hassium: Sent compressed chunk [{}, {}] to player {}",
                    compressed.chunkX, compressed.chunkZ, player.getName().getString());
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to send compressed chunk to player {}", player.getName().getString(), e);
        }
    }

    // ========== 数据包记录 ==========

    /**
     * 握手请求数据包（客户端 -> 服务端）
     */
    public record HandshakePacket(
            int protocolVersion,
            String modVersion,
            String[] supportedAlgorithms,
            boolean clientCacheSupported,
            boolean chunkRevisionSupported,
            boolean scheme127Supported,
            boolean globalPacketCompressionSupported,
            boolean compactHeaderSupported
    ) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(protocolVersion);
            buf.writeUtf(modVersion);
            buf.writeVarInt(supportedAlgorithms.length);
            for (String algo : supportedAlgorithms) {
                buf.writeUtf(algo);
            }
            buf.writeBoolean(clientCacheSupported);
            buf.writeBoolean(chunkRevisionSupported);
            buf.writeBoolean(scheme127Supported);
            buf.writeBoolean(globalPacketCompressionSupported);
            buf.writeBoolean(compactHeaderSupported);
        }

        public static HandshakePacket decode(FriendlyByteBuf buf) {
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
            return new HandshakePacket(protocolVersion, modVersion, algorithms, clientCache, chunkRevision, scheme127, globalPacketCompression, compactHeader);
        }
    }

    /**
     * 握手响应数据包（服务端 -> 客户端）
     */
    public record HandshakeResponsePacket(
            int protocolVersion,
            boolean accepted,
            boolean globalCompressionAccepted,
            boolean compactHeaderAccepted
    ) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(protocolVersion);
            buf.writeBoolean(accepted);
            buf.writeBoolean(globalCompressionAccepted);
            buf.writeBoolean(compactHeaderAccepted);
        }

        public static HandshakeResponsePacket decode(FriendlyByteBuf buf) {
            int protocolVersion = buf.readVarInt();
            boolean accepted = buf.readBoolean();
            boolean globalCompressionAccepted = buf.readBoolean();
            boolean compactHeaderAccepted = buf.readBoolean();
            return new HandshakeResponsePacket(protocolVersion, accepted, globalCompressionAccepted, compactHeaderAccepted);
        }
    }

    /**
     * 压缩区块数据包装器
     */
    public record CompressedPayloadWrapper(byte[] data) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(data.length);
            buf.writeBytes(data);
        }

        public static CompressedPayloadWrapper decode(FriendlyByteBuf buf) {
            int length = buf.readVarInt();
            byte[] data = new byte[length];
            buf.readBytes(data);
            return new CompressedPayloadWrapper(data);
        }
    }

    /**
     * 区块元数据包装器（服务端 -> 客户端）
     */
    public record MetadataWrapper(byte[] data) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(data.length);
            buf.writeBytes(data);
        }

        public static MetadataWrapper decode(FriendlyByteBuf buf) {
            int length = buf.readVarInt();
            byte[] data = new byte[length];
            buf.readBytes(data);
            return new MetadataWrapper(data);
        }
    }

    /**
     * 区块数据请求包装器（客户端 -> 服务端）
     */
    public record DataRequestWrapper(byte[] data) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(data.length);
            buf.writeBytes(data);
        }

        public static DataRequestWrapper decode(FriendlyByteBuf buf) {
            int length = buf.readVarInt();
            byte[] data = new byte[length];
            buf.readBytes(data);
            return new DataRequestWrapper(data);
        }
    }

    /**
     * 区块哈希广播包装器（阶段一，服务端 -> 客户端）
     */
    public record ChunkHashWrapper(byte[] data) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(data.length);
            buf.writeBytes(data);
        }

        public static ChunkHashWrapper decode(FriendlyByteBuf buf) {
            int length = buf.readVarInt();
            byte[] data = new byte[length];
            buf.readBytes(data);
            return new ChunkHashWrapper(data);
        }
    }

    /**
     * section 哈希请求包装器（阶段二，客户端 -> 服务端）
     */
    public record SectionHashRequestWrapper(byte[] data) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(data.length);
            buf.writeBytes(data);
        }

        public static SectionHashRequestWrapper decode(FriendlyByteBuf buf) {
            int length = buf.readVarInt();
            byte[] data = new byte[length];
            buf.readBytes(data);
            return new SectionHashRequestWrapper(data);
        }
    }

    /**
     * section delta 响应包装器（阶段二，服务端 -> 客户端）
     */
    public record SectionDeltaWrapper(byte[] data) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(data.length);
            buf.writeBytes(data);
        }

        public static SectionDeltaWrapper decode(FriendlyByteBuf buf) {
            int length = buf.readVarInt();
            byte[] data = new byte[length];
            buf.readBytes(data);
            return new SectionDeltaWrapper(data);
        }
    }

    /**
     * blockEntity 数据请求包装器（客户端 -> 服务端）
     */
    public record BlockEntityRequestWrapper(byte[] data) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(data.length);
            buf.writeBytes(data);
        }

        public static BlockEntityRequestWrapper decode(FriendlyByteBuf buf) {
            int length = buf.readVarInt();
            byte[] data = new byte[length];
            buf.readBytes(data);
            return new BlockEntityRequestWrapper(data);
        }
    }

    /**
     * blockEntity 数据响应包装器（服务端 -> 客户端）
     */
    public record BlockEntityDataWrapper(byte[] data) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(data.length);
            buf.writeBytes(data);
        }

        public static BlockEntityDataWrapper decode(FriendlyByteBuf buf) {
            int length = buf.readVarInt();
            byte[] data = new byte[length];
            buf.readBytes(data);
            return new BlockEntityDataWrapper(data);
        }
    }
}
