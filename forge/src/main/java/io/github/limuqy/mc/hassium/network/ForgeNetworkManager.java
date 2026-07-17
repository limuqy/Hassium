package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DebugLogger.LogType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

#if MC_VER < MC_1_20_2
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
#else
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.minecraftforge.network.Channel;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.SimpleChannel;

import java.util.function.BiConsumer;
import java.util.function.Function;
#endif

/**
 * Forge 平台网络管理器实现。
 * <p>
 * 版本整段切分（见 docs/version-segments.md）：
 * <ul>
 *   <li>{@code MC_VER < MC_1_20_2}：旧 SimpleChannel（NetworkRegistry.newSimpleChannel）</li>
 *   <li>{@code MC_VER >= MC_1_20_2}：Forge 50+ ChannelBuilder + play() Payload 风格 SimpleChannel</li>
 * </ul>
 */
public class ForgeNetworkManager implements NetworkManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/Network");
    private static final String PROTOCOL_VERSION = "1";
    private static final int PROTOCOL_VERSION_INT = 1;

#if MC_VER < MC_1_20_2
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocationCompat.create(Constants.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );
    private static int packetId = 0;
#else
    /** Forge 50+：在 {@link #registerChannels()} 中构建并赋值 */
    public static SimpleChannel CHANNEL;
#endif

    @Override
    public void registerChannels() {
        if (!HassiumConfigService.getInstance().isNetworkCompressionEnabled()) {
            LOGGER.warn("Hassium: network.enabled=false, skipping Forge channel registration");
            return;
        }
        LOGGER.debug("Hassium: Registering Forge network channels");
#if MC_VER < MC_1_20_2
        registerLegacyChannels();
#else
        registerModernChannels();
#endif
    }

#if MC_VER < MC_1_20_2
    private void registerLegacyChannels() {
        // 必须 setPacketHandled(true)（在 enqueueWork 外），否则 Forge 会把包交给原版
        // S2C / C2S 必须带 NetworkDirection，避免方向校验失败

        CHANNEL.<HandshakePacket>registerMessage(
                packetId++,
                HandshakePacket.class,
                HandshakePacket::encode,
                HandshakePacket::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> handleHandshakeC2S(msg, ctx.get().getSender(),
                            resp -> CHANNEL.sendTo(resp, ctx.get().getSender().connection.connection,
                                    NetworkDirection.PLAY_TO_CLIENT)));
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );

        CHANNEL.<HandshakeResponsePacket>registerMessage(
                packetId++,
                HandshakeResponsePacket.class,
                HandshakeResponsePacket::encode,
                HandshakeResponsePacket::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> handleHandshakeS2C(msg));
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

        CHANNEL.<CompressedPayloadWrapper>registerMessage(
                packetId++,
                CompressedPayloadWrapper.class,
                CompressedPayloadWrapper::encode,
                CompressedPayloadWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> handleCompressedPayload(msg));
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

        CHANNEL.<DataRequestWrapper>registerMessage(
                packetId++,
                DataRequestWrapper.class,
                DataRequestWrapper::encode,
                DataRequestWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> handleDataRequest(msg, ctx.get().getSender()));
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );

        CHANNEL.<ChunkHashWrapper>registerMessage(
                packetId++,
                ChunkHashWrapper.class,
                ChunkHashWrapper::encode,
                ChunkHashWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> handleChunkHash(msg));
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

        CHANNEL.<SectionHashRequestWrapper>registerMessage(
                packetId++,
                SectionHashRequestWrapper.class,
                SectionHashRequestWrapper::encode,
                SectionHashRequestWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> handleSectionHashRequest(msg, ctx.get().getSender()));
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );

        CHANNEL.<SectionDeltaWrapper>registerMessage(
                packetId++,
                SectionDeltaWrapper.class,
                SectionDeltaWrapper::encode,
                SectionDeltaWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> handleSectionDelta(msg));
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

        CHANNEL.<BlockEntityRequestWrapper>registerMessage(
                packetId++,
                BlockEntityRequestWrapper.class,
                BlockEntityRequestWrapper::encode,
                BlockEntityRequestWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> handleBlockEntityRequest(msg, ctx.get().getSender()));
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );

        CHANNEL.<BlockEntityDataWrapper>registerMessage(
                packetId++,
                BlockEntityDataWrapper.class,
                BlockEntityDataWrapper::encode,
                BlockEntityDataWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> handleBlockEntityData(msg));
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

        LOGGER.info("Hassium: Registered {} network packets", packetId);
    }
#else
    private void registerModernChannels() {
        if (CHANNEL != null) {
            LOGGER.debug("Hassium: Forge channel already registered");
            return;
        }

        CHANNEL = ChannelBuilder
                .named(ResourceLocationCompat.create(Constants.MOD_ID, "main"))
                .networkProtocolVersion(PROTOCOL_VERSION_INT)
                .acceptedVersions(Channel.VersionTest.exact(PROTOCOL_VERSION_INT))
                .simpleChannel()
                .play()
                    .serverbound()
                        .addMain(HandshakePacket.class, playCodec(HandshakePacket::encode, HandshakePacket::decode),
                                ForgeNetworkManager::onHandshakeC2S)
                        .addMain(DataRequestWrapper.class, playCodec(DataRequestWrapper::encode, DataRequestWrapper::decode),
                                ForgeNetworkManager::onDataRequest)
                        .addMain(SectionHashRequestWrapper.class,
                                playCodec(SectionHashRequestWrapper::encode, SectionHashRequestWrapper::decode),
                                ForgeNetworkManager::onSectionHashRequest)
                        .addMain(BlockEntityRequestWrapper.class,
                                playCodec(BlockEntityRequestWrapper::encode, BlockEntityRequestWrapper::decode),
                                ForgeNetworkManager::onBlockEntityRequest)
                    .clientbound()
                        .addMain(HandshakeResponsePacket.class,
                                playCodec(HandshakeResponsePacket::encode, HandshakeResponsePacket::decode),
                                ForgeNetworkManager::onHandshakeS2C)
                        .addMain(CompressedPayloadWrapper.class,
                                playCodec(CompressedPayloadWrapper::encode, CompressedPayloadWrapper::decode),
                                ForgeNetworkManager::onCompressedPayload)
                        .addMain(ChunkHashWrapper.class, playCodec(ChunkHashWrapper::encode, ChunkHashWrapper::decode),
                                ForgeNetworkManager::onChunkHash)
                        .addMain(SectionDeltaWrapper.class, playCodec(SectionDeltaWrapper::encode, SectionDeltaWrapper::decode),
                                ForgeNetworkManager::onSectionDelta)
                        .addMain(BlockEntityDataWrapper.class,
                                playCodec(BlockEntityDataWrapper::encode, BlockEntityDataWrapper::decode),
                                ForgeNetworkManager::onBlockEntityData)
                .build();

        LOGGER.info("Hassium: Registered Forge 50+ ChannelBuilder play channel (4 C2S + 5 S2C)");
    }

    private static <M> StreamCodec<RegistryFriendlyByteBuf, M> playCodec(
            BiConsumer<M, FriendlyByteBuf> encode,
            Function<FriendlyByteBuf, M> decode
    ) {
        return StreamCodec.of(
                (buf, msg) -> encode.accept(msg, buf),
                buf -> decode.apply(buf)
        );
    }

    private static void onHandshakeC2S(HandshakePacket msg, CustomPayloadEvent.Context ctx) {
        handleHandshakeC2S(msg, ctx.getSender(), resp -> CHANNEL.reply(resp, ctx));
    }

    private static void onHandshakeS2C(HandshakeResponsePacket msg, CustomPayloadEvent.Context ctx) {
        handleHandshakeS2C(msg);
    }

    private static void onCompressedPayload(CompressedPayloadWrapper msg, CustomPayloadEvent.Context ctx) {
        handleCompressedPayload(msg);
    }

    private static void onDataRequest(DataRequestWrapper msg, CustomPayloadEvent.Context ctx) {
        handleDataRequest(msg, ctx.getSender());
    }

    private static void onChunkHash(ChunkHashWrapper msg, CustomPayloadEvent.Context ctx) {
        handleChunkHash(msg);
    }

    private static void onSectionHashRequest(SectionHashRequestWrapper msg, CustomPayloadEvent.Context ctx) {
        handleSectionHashRequest(msg, ctx.getSender());
    }

    private static void onSectionDelta(SectionDeltaWrapper msg, CustomPayloadEvent.Context ctx) {
        handleSectionDelta(msg);
    }

    private static void onBlockEntityRequest(BlockEntityRequestWrapper msg, CustomPayloadEvent.Context ctx) {
        handleBlockEntityRequest(msg, ctx.getSender());
    }

    private static void onBlockEntityData(BlockEntityDataWrapper msg, CustomPayloadEvent.Context ctx) {
        handleBlockEntityData(msg);
    }

    private static void sendToPlayer(ServerPlayer player, Object msg) {
        if (CHANNEL == null) {
            LOGGER.warn("Hassium: CHANNEL not registered, drop packet to {}", player.getName().getString());
            return;
        }
        CHANNEL.send(msg, PacketDistributor.PLAYER.with(player));
    }

    private static void sendToServer(Object msg) {
        if (CHANNEL == null) {
            LOGGER.warn("Hassium: CHANNEL not registered, drop client packet");
            return;
        }
        CHANNEL.send(msg, PacketDistributor.SERVER.noArg());
    }
#endif

    // ========== 共享处理逻辑 ==========

    private static void handleHandshakeC2S(
            HandshakePacket msg,
            ServerPlayer player,
            java.util.function.Consumer<HandshakeResponsePacket> reply
    ) {
        if (player == null) {
            LOGGER.error("Hassium: Received handshake from non-player");
            return;
        }

        DebugLogger.debug(LogType.NETWORK,
                "[HANDSHAKE] Received from client {}, protocol={}, globalCompression={}, compactHeader={}",
                player.getName().getString(), msg.protocolVersion(),
                msg.globalPacketCompressionSupported(), msg.compactHeaderSupported());

        PlayerCompressionTracker.enableCompression(player);

        boolean serverSupportsGlobalCompression = HassiumConfigService.getInstance().isGlobalPacketCompressionEnabled();
        boolean useGlobalCompression = serverSupportsGlobalCompression && msg.globalPacketCompressionSupported();
        boolean serverSupportsCompactHeader = HassiumConfigService.getInstance().isCompactHeaderEnabled();
        boolean useCompactHeader = serverSupportsCompactHeader && msg.compactHeaderSupported();

        boolean accepted = true;
        HandshakeResponsePacket response = new HandshakeResponsePacket(
                Constants.CURRENT_PROTOCOL_VERSION,
                accepted,
                useGlobalCompression,
                useCompactHeader
        );
        reply.accept(response);
        LOGGER.info("Hassium: Server handshake for {}: accepted={}, globalCompression={}, compactHeader={}",
                player.getName().getString(), accepted, useGlobalCompression, useCompactHeader);

        if (useGlobalCompression) {
            DebugLogger.debug(LogType.NETWORK,
                    "Hassium: Global ZSTD pipeline switch deferred on Forge (custom channel only)");
        }
    }

    private static void handleHandshakeS2C(HandshakeResponsePacket msg) {
        LOGGER.info("Hassium: Client handshake response: accepted={}, globalCompression={}, compactHeader={}",
                msg.accepted(), msg.globalCompressionAccepted(), msg.compactHeaderAccepted());
        if (msg.accepted() && msg.globalCompressionAccepted()) {
            DebugLogger.debug(LogType.NETWORK,
                    "Hassium: Global ZSTD accepted but pipeline switch deferred on Forge");
        }
    }

    private static void handleCompressedPayload(CompressedPayloadWrapper msg) {
        try {
            ClientChunkHandler.handleCompressedChunk(msg.data());
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to handle compressed payload", e);
        }
    }

    private static void handleDataRequest(DataRequestWrapper msg, ServerPlayer player) {
        try {
            if (player == null) {
                return;
            }
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
            ChunkDataRequestC2SPacket request = ChunkDataRequestC2SPacket.decode(buf);
            ServerChunkPushManager.getInstance().enqueueDataRequest(
                    player, request.dimension(), request.chunks());
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to handle chunk data request", e);
        }
    }

    private static void handleChunkHash(ChunkHashWrapper msg) {
        try {
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
            ChunkHashS2CPacket packet = ChunkHashS2CPacket.decode(buf);
            ClientMetadataHandler.handleChunkHashPacket(packet);
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to handle chunk hash packet", e);
        }
    }

    private static void handleSectionHashRequest(SectionHashRequestWrapper msg, ServerPlayer player) {
        try {
            if (player == null) {
                return;
            }
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
            SectionHashRequestC2SPacket request = SectionHashRequestC2SPacket.decode(buf);
            ServerChunkPushManager.getInstance().handleSectionHashRequest(player, request);
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to handle section hash request", e);
        }
    }

    private static void handleSectionDelta(SectionDeltaWrapper msg) {
        try {
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
            SectionDeltaS2CPacket packet = SectionDeltaS2CPacket.decode(buf);
            ClientMetadataHandler.handleSectionDeltaPacket(packet);
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to handle section delta packet", e);
        }
    }

    private static void handleBlockEntityRequest(BlockEntityRequestWrapper msg, ServerPlayer player) {
        try {
            if (player == null) {
                return;
            }
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
            BlockEntityRequestC2SPacket request = BlockEntityRequestC2SPacket.decode(buf);
            ServerChunkPushManager.getInstance().handleBlockEntityRequest(player, request);
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to handle block entity request", e);
        }
    }

    private static void handleBlockEntityData(BlockEntityDataWrapper msg) {
        try {
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
            BlockEntityDataS2CPacket packet = BlockEntityDataS2CPacket.decode(buf);
            ClientMetadataHandler.handleBlockEntityDataPacket(packet);
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to handle block entity data packet", e);
        }
    }

    @Override
    public void sendHandshakeRequest() {
        if (!HassiumConfigService.getInstance().isNetworkCompressionEnabled()) {
            LOGGER.debug("Hassium: Skip handshake — network.enabled=false");
            return;
        }
        String compressionAlgorithm = HassiumConfigService.getInstance().getCompressionAlgorithm();
        String dictAlgorithm = compressionAlgorithm + "_dict";
        HandshakePacket packet = new HandshakePacket(
                Constants.CURRENT_PROTOCOL_VERSION,
                Constants.MOD_VERSION,
                new String[]{compressionAlgorithm, dictAlgorithm},
                true,
                true,
                false,
                true,
                true
        );
#if MC_VER < MC_1_20_2
        CHANNEL.sendToServer(packet);
#else
        sendToServer(packet);
#endif
        LOGGER.debug("Hassium: Sent handshake request to server");
    }

    @Override
    public void sendChunkDataRequest(FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
#if MC_VER < MC_1_20_2
        CHANNEL.sendToServer(new DataRequestWrapper(data));
#else
        sendToServer(new DataRequestWrapper(data));
#endif
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
#if MC_VER < MC_1_20_2
        CHANNEL.sendTo(new ChunkHashWrapper(data), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
#else
        sendToPlayer(player, new ChunkHashWrapper(data));
#endif
    }

    @Override
    public void sendSectionHashRequest(FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
#if MC_VER < MC_1_20_2
        CHANNEL.sendToServer(new SectionHashRequestWrapper(data));
#else
        sendToServer(new SectionHashRequestWrapper(data));
#endif
    }

    @Override
    public void sendSectionDeltaPacket(ServerPlayer player, FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
#if MC_VER < MC_1_20_2
        CHANNEL.sendTo(new SectionDeltaWrapper(data), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
#else
        sendToPlayer(player, new SectionDeltaWrapper(data));
#endif
    }

    @Override
    public void sendBlockEntityRequest(FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
#if MC_VER < MC_1_20_2
        CHANNEL.sendToServer(new BlockEntityRequestWrapper(data));
#else
        sendToServer(new BlockEntityRequestWrapper(data));
#endif
    }

    @Override
    public void sendBlockEntityData(ServerPlayer player, FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
#if MC_VER < MC_1_20_2
        CHANNEL.sendTo(new BlockEntityDataWrapper(data), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
#else
        sendToPlayer(player, new BlockEntityDataWrapper(data));
#endif
    }

    /**
     * 发送压缩的区块数据到指定玩家
     */
    public static void sendCompressedChunk(ServerPlayer player, ChunkCompressionHandler.CompressedChunkData compressed) {
        try {
            byte[] data = compressed.encode();
#if MC_VER < MC_1_20_2
            CHANNEL.sendTo(new CompressedPayloadWrapper(data), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
#else
            sendToPlayer(player, new CompressedPayloadWrapper(data));
#endif
            LOGGER.debug("Hassium: Sent compressed chunk [{}, {}] to player {}",
                    compressed.chunkX, compressed.chunkZ, player.getName().getString());
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to send compressed chunk to player {}", player.getName().getString(), e);
        }
    }

    // ========== 数据包记录 ==========

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
            return new HandshakePacket(
                    protocolVersion,
                    modVersion,
                    algorithms,
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readBoolean()
            );
        }
    }

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
            return new HandshakeResponsePacket(
                    buf.readVarInt(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readBoolean()
            );
        }
    }

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
