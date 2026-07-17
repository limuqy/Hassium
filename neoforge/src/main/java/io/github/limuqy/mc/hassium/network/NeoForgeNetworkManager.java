package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.platform.Services;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

#if MC_VER < MC_1_20_2
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
#elif MC_VER < MC_1_20_5
import net.neoforged.neoforge.network.NetworkRegistry;
import net.neoforged.neoforge.network.PlayNetworkDirection;
import net.neoforged.neoforge.network.simple.SimpleChannel;
#else
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
#endif

import java.lang.reflect.Field;

/**
 * NeoForge 平台网络管理器实现。
 * <p>
 * 版本整段切分（见 docs/version-segments.md）：
 * <ul>
 *   <li>{@code MC_VER < MC_1_20_2}：SimpleChannel（1.20.1 仍用 forge 包名）</li>
 *   <li>{@code MC_1_20_2 <= MC_VER < MC_1_20_5}：SimpleChannel（neoforged 包名 + PlayNetworkDirection）</li>
 *   <li>{@code MC_VER >= MC_1_20_5}：Payload + StreamCodec</li>
 * </ul>
 * common 聚合能力由 {@link io.github.limuqy.mc.hassium.compat.NetworkCapability} 门控。
 */
public class NeoForgeNetworkManager implements NetworkManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/NeoForgeNetwork");
    private static final String PROTOCOL_VERSION = "1";

    // 缓存服务器实例
    private static volatile net.minecraft.server.MinecraftServer cachedServer;

    /**
     * 设置服务器实例
     */
    public static void setServerInstance(net.minecraft.server.MinecraftServer server) {
        cachedServer = server;
    }

    /**
     * 通过反射获取 Connection 的 channel 字段
     */
    private static io.netty.channel.Channel getConnectionChannel(net.minecraft.network.Connection connection) {
        try {
            Field channelField = net.minecraft.network.Connection.class.getDeclaredField("channel");
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
    private static net.minecraft.network.Connection getPlayerConnection(ServerPlayer player) {
        return io.github.limuqy.mc.hassium.compat.PlayerCompat.getConnection(player);
    }

#if MC_VER < MC_1_20_5
    // 1.20.1–1.20.4: SimpleChannel（包名随加载器切换）
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocationCompat.create(Constants.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int packetId = 0;

    // 防止重复注册（commonSetup 和 onClientSetup 都可能调用）
    private static boolean packetsRegistered = false;

    // 1.20.1 包装类定义
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

    public record CompressedChunkWrapper(byte[] data) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(data.length);
            buf.writeBytes(data);
        }
        public static CompressedChunkWrapper decode(FriendlyByteBuf buf) {
            int length = buf.readVarInt();
            byte[] data = new byte[length];
            buf.readBytes(data);
            return new CompressedChunkWrapper(data);
        }
    }

    public record ChunkDataRequestWrapper(byte[] data) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(data.length);
            buf.writeBytes(data);
        }
        public static ChunkDataRequestWrapper decode(FriendlyByteBuf buf) {
            int length = buf.readVarInt();
            byte[] data = new byte[length];
            buf.readBytes(data);
            return new ChunkDataRequestWrapper(data);
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

    public record HandshakeWrapper(
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
        public static HandshakeWrapper decode(FriendlyByteBuf buf) {
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
            return new HandshakeWrapper(protocolVersion, modVersion, algorithms, clientCache, chunkRevision, scheme127, globalPacketCompression, compactHeader);
        }
    }

    public record HandshakeResponseWrapper(
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
        public static HandshakeResponseWrapper decode(FriendlyByteBuf buf) {
            int protocolVersion = buf.readVarInt();
            boolean accepted = buf.readBoolean();
            boolean globalCompressionAccepted = buf.readBoolean();
            boolean compactHeaderAccepted = buf.readBoolean();
            return new HandshakeResponseWrapper(protocolVersion, accepted, globalCompressionAccepted, compactHeaderAccepted);
        }
    }

#else
    // 1.20.5+: 使用 Payload + StreamCodec
    public static final Object CHANNEL = null;

    /**
     * 握手请求 Payload (C2S)
     */
    public record HandshakePayload(
            int protocolVersion,
            String modVersion,
            String[] supportedAlgorithms,
            boolean clientCacheSupported,
            boolean chunkRevisionSupported,
            boolean scheme127Supported,
            boolean globalPacketCompressionSupported,
            boolean compactHeaderSupported
    ) implements CustomPacketPayload {

        public static final Type<HandshakePayload> TYPE = new Type<>(
                ResourceLocationCompat.create(Constants.MOD_ID, "handshake_c2s")
        );

        // StreamCodec.composite 最多 6 字段；握手有 8 字段，用手写编解码
        public static final StreamCodec<FriendlyByteBuf, HandshakePayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.protocolVersion());
                    buf.writeUtf(p.modVersion());
                    buf.writeVarInt(p.supportedAlgorithms().length);
                    for (String alg : p.supportedAlgorithms()) {
                        buf.writeUtf(alg);
                    }
                    buf.writeBoolean(p.clientCacheSupported());
                    buf.writeBoolean(p.chunkRevisionSupported());
                    buf.writeBoolean(p.scheme127Supported());
                    buf.writeBoolean(p.globalPacketCompressionSupported());
                    buf.writeBoolean(p.compactHeaderSupported());
                },
                buf -> {
                    int protocolVersion = buf.readVarInt();
                    String modVersion = buf.readUtf();
                    int algCount = buf.readVarInt();
                    String[] algorithms = new String[algCount];
                    for (int i = 0; i < algCount; i++) {
                        algorithms[i] = buf.readUtf();
                    }
                    return new HandshakePayload(
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
        );

        @Override
        public Type<HandshakePayload> type() {
            return TYPE;
        }
    }

    /**
     * 握手响应 Payload (S2C)
     */
    public record HandshakeResponsePayload(
            int protocolVersion,
            boolean accepted,
            boolean globalCompressionAccepted,
            boolean compactHeaderAccepted
    ) implements CustomPacketPayload {

        public static final Type<HandshakeResponsePayload> TYPE = new Type<>(
                ResourceLocationCompat.create(Constants.MOD_ID, "handshake_s2c")
        );

        public static final StreamCodec<FriendlyByteBuf, HandshakeResponsePayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, HandshakeResponsePayload::protocolVersion,
                ByteBufCodecs.BOOL, HandshakeResponsePayload::accepted,
                ByteBufCodecs.BOOL, HandshakeResponsePayload::globalCompressionAccepted,
                ByteBufCodecs.BOOL, HandshakeResponsePayload::compactHeaderAccepted,
                HandshakeResponsePayload::new
        );

        @Override
        public Type<HandshakeResponsePayload> type() {
            return TYPE;
        }
    }

    /**
     * 压缩区块数据 Payload (S2C)
     */
    public record CompressedChunkPayload(byte[] data) implements CustomPacketPayload {

        public static final Type<CompressedChunkPayload> TYPE = new Type<>(
                ResourceLocationCompat.create(Constants.MOD_ID, "chunk_payload_s2c")
        );

        public static final StreamCodec<FriendlyByteBuf, CompressedChunkPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BYTE_ARRAY, CompressedChunkPayload::data,
                CompressedChunkPayload::new
        );

        @Override
        public Type<CompressedChunkPayload> type() {
            return TYPE;
        }
    }

    /**
     * 区块数据请求 Payload (C2S)
     */
    public record ChunkDataRequestPayload(byte[] data) implements CustomPacketPayload {

        public static final Type<ChunkDataRequestPayload> TYPE = new Type<>(
                ResourceLocationCompat.create(Constants.MOD_ID, "chunk_data_request_c2s")
        );

        public static final StreamCodec<FriendlyByteBuf, ChunkDataRequestPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BYTE_ARRAY, ChunkDataRequestPayload::data,
                ChunkDataRequestPayload::new
        );

        @Override
        public Type<ChunkDataRequestPayload> type() {
            return TYPE;
        }
    }

    /**
     * 区块哈希 Payload (S2C)
     */
    public record ChunkHashPayload(byte[] data) implements CustomPacketPayload {

        public static final Type<ChunkHashPayload> TYPE = new Type<>(
                ResourceLocationCompat.create(Constants.MOD_ID, "chunk_hash_s2c")
        );

        public static final StreamCodec<FriendlyByteBuf, ChunkHashPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BYTE_ARRAY, ChunkHashPayload::data,
                ChunkHashPayload::new
        );

        @Override
        public Type<ChunkHashPayload> type() {
            return TYPE;
        }
    }

    /**
     * Section 哈希请求 Payload (C2S)
     */
    public record SectionHashRequestPayload(byte[] data) implements CustomPacketPayload {

        public static final Type<SectionHashRequestPayload> TYPE = new Type<>(
                ResourceLocationCompat.create(Constants.MOD_ID, "section_hash_request_c2s")
        );

        public static final StreamCodec<FriendlyByteBuf, SectionHashRequestPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BYTE_ARRAY, SectionHashRequestPayload::data,
                SectionHashRequestPayload::new
        );

        @Override
        public Type<SectionHashRequestPayload> type() {
            return TYPE;
        }
    }

    /**
     * Section Delta Payload (S2C)
     */
    public record SectionDeltaPayload(byte[] data) implements CustomPacketPayload {

        public static final Type<SectionDeltaPayload> TYPE = new Type<>(
                ResourceLocationCompat.create(Constants.MOD_ID, "section_delta_s2c")
        );

        public static final StreamCodec<FriendlyByteBuf, SectionDeltaPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BYTE_ARRAY, SectionDeltaPayload::data,
                SectionDeltaPayload::new
        );

        @Override
        public Type<SectionDeltaPayload> type() {
            return TYPE;
        }
    }

    /**
     * BlockEntity 请求 Payload (C2S)
     */
    public record BlockEntityRequestPayload(byte[] data) implements CustomPacketPayload {

        public static final Type<BlockEntityRequestPayload> TYPE = new Type<>(
                ResourceLocationCompat.create(Constants.MOD_ID, "block_entity_request_c2s")
        );

        public static final StreamCodec<FriendlyByteBuf, BlockEntityRequestPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BYTE_ARRAY, BlockEntityRequestPayload::data,
                BlockEntityRequestPayload::new
        );

        @Override
        public Type<BlockEntityRequestPayload> type() {
            return TYPE;
        }
    }

    /**
     * BlockEntity 数据 Payload (S2C)
     */
    public record BlockEntityDataPayload(byte[] data) implements CustomPacketPayload {

        public static final Type<BlockEntityDataPayload> TYPE = new Type<>(
                ResourceLocationCompat.create(Constants.MOD_ID, "block_entity_data_s2c")
        );

        public static final StreamCodec<FriendlyByteBuf, BlockEntityDataPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BYTE_ARRAY, BlockEntityDataPayload::data,
                BlockEntityDataPayload::new
        );

        @Override
        public Type<BlockEntityDataPayload> type() {
            return TYPE;
        }
    }

#endif

    // ========== 注册方法 ==========

    @Override
    public void registerChannels() {
        if (!HassiumConfigService.getInstance().isNetworkCompressionEnabled()) {
            LOGGER.warn("Hassium: network.enabled=false, skipping NeoForge channel registration");
            return;
        }
        LOGGER.debug("Hassium: NeoForge network channels will be registered via event");
#if MC_VER < MC_1_20_5
        registerSimpleChannelPackets();
#endif
    }

#if MC_VER < MC_1_20_5
    /**
     * 注册 SimpleChannel 数据包（1.20.1 forge / 1.20.2–1.20.4 neoforge）
     */
    private void registerSimpleChannelPackets() {
        if (packetsRegistered) {
            LOGGER.debug("Hassium: SimpleChannel packets already registered, skipping");
            return;
        }
        packetsRegistered = true;
        LOGGER.debug("Hassium: Registering SimpleChannel packets");

        // 必须 setPacketHandled(true)，否则会把包交给原版 → Unknown custom packet identifier: hassium:main
        // S2C / C2S 必须带方向枚举，避免方向校验失败
        // 注意：Forge 1.20.1 的 consumer 参数是 Supplier<Context>；NeoForge 20.2 直接传 Context

        // 0: 握手请求 C2S
        CHANNEL.registerMessage(packetId++, HandshakeWrapper.class,
                HandshakeWrapper::encode, HandshakeWrapper::decode,
#if MC_VER < MC_1_20_2
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> {
                        ServerPlayer player = ctx.get().getSender();
                        if (player == null) return;
                        handleHandshakeSimple(player, msg);
                    });
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER));
#else
                (msg, ctx) -> {
                    ctx.enqueueWork(() -> {
                        ServerPlayer player = ctx.getSender();
                        if (player == null) return;
                        handleHandshakeSimple(player, msg);
                    });
                    ctx.setPacketHandled(true);
                },
                java.util.Optional.of(PlayNetworkDirection.PLAY_TO_SERVER));
#endif

        // 1: 握手响应 S2C
        CHANNEL.registerMessage(packetId++, HandshakeResponseWrapper.class,
                HandshakeResponseWrapper::encode, HandshakeResponseWrapper::decode,
#if MC_VER < MC_1_20_2
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> handleHandshakeResponseSimple(msg));
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT));
#else
                (msg, ctx) -> {
                    ctx.enqueueWork(() -> handleHandshakeResponseSimple(msg));
                    ctx.setPacketHandled(true);
                },
                java.util.Optional.of(PlayNetworkDirection.PLAY_TO_CLIENT));
#endif

        // 2: 压缩区块 S2C
        CHANNEL.registerMessage(packetId++, CompressedChunkWrapper.class,
                CompressedChunkWrapper::encode, CompressedChunkWrapper::decode,
#if MC_VER < MC_1_20_2
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> {
                        try {
                            ClientChunkHandler.handleCompressedChunk(msg.data());
                        } catch (Exception e) {
                            LOGGER.error("[CLIENT] Failed to handle compressed chunk", e);
                        }
                    });
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT));
#else
                (msg, ctx) -> {
                    ctx.enqueueWork(() -> {
                        try {
                            ClientChunkHandler.handleCompressedChunk(msg.data());
                        } catch (Exception e) {
                            LOGGER.error("[CLIENT] Failed to handle compressed chunk", e);
                        }
                    });
                    ctx.setPacketHandled(true);
                },
                java.util.Optional.of(PlayNetworkDirection.PLAY_TO_CLIENT));
#endif

        // 4: 区块数据请求 C2S
        CHANNEL.registerMessage(packetId++, ChunkDataRequestWrapper.class,
                ChunkDataRequestWrapper::encode, ChunkDataRequestWrapper::decode,
#if MC_VER < MC_1_20_2
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> {
                        ServerPlayer player = ctx.get().getSender();
                        if (player == null) return;
                        try {
                            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
                            ChunkDataRequestC2SPacket request = ChunkDataRequestC2SPacket.decode(buf);
                            ServerChunkPushManager.getInstance().enqueueDataRequest(player, request.dimension(), request.chunks());
                        } catch (Exception e) {
                            LOGGER.error("[SERVER] Failed to handle chunk data request", e);
                        }
                    });
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER));
#else
                (msg, ctx) -> {
                    ctx.enqueueWork(() -> {
                        ServerPlayer player = ctx.getSender();
                        if (player == null) return;
                        try {
                            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
                            ChunkDataRequestC2SPacket request = ChunkDataRequestC2SPacket.decode(buf);
                            ServerChunkPushManager.getInstance().enqueueDataRequest(player, request.dimension(), request.chunks());
                        } catch (Exception e) {
                            LOGGER.error("[SERVER] Failed to handle chunk data request", e);
                        }
                    });
                    ctx.setPacketHandled(true);
                },
                java.util.Optional.of(PlayNetworkDirection.PLAY_TO_SERVER));
#endif

        // 5: 区块哈希 S2C
        CHANNEL.registerMessage(packetId++, ChunkHashWrapper.class,
                ChunkHashWrapper::encode, ChunkHashWrapper::decode,
#if MC_VER < MC_1_20_2
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> {
                        try {
                            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
                            ChunkHashS2CPacket packet = ChunkHashS2CPacket.decode(buf);
                            ClientMetadataHandler.handleChunkHashPacket(packet);
                        } catch (Exception e) {
                            LOGGER.error("[CLIENT] Failed to handle chunk hash", e);
                        }
                    });
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT));
#else
                (msg, ctx) -> {
                    ctx.enqueueWork(() -> {
                        try {
                            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
                            ChunkHashS2CPacket packet = ChunkHashS2CPacket.decode(buf);
                            ClientMetadataHandler.handleChunkHashPacket(packet);
                        } catch (Exception e) {
                            LOGGER.error("[CLIENT] Failed to handle chunk hash", e);
                        }
                    });
                    ctx.setPacketHandled(true);
                },
                java.util.Optional.of(PlayNetworkDirection.PLAY_TO_CLIENT));
#endif

        // 6: Section 哈希请求 C2S
        CHANNEL.registerMessage(packetId++, SectionHashRequestWrapper.class,
                SectionHashRequestWrapper::encode, SectionHashRequestWrapper::decode,
#if MC_VER < MC_1_20_2
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> {
                        ServerPlayer player = ctx.get().getSender();
                        if (player == null) return;
                        try {
                            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
                            SectionHashRequestC2SPacket request = SectionHashRequestC2SPacket.decode(buf);
                            ServerChunkPushManager.getInstance().handleSectionHashRequest(player, request);
                        } catch (Exception e) {
                            LOGGER.error("[SERVER] Failed to handle section hash request", e);
                        }
                    });
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER));
#else
                (msg, ctx) -> {
                    ctx.enqueueWork(() -> {
                        ServerPlayer player = ctx.getSender();
                        if (player == null) return;
                        try {
                            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
                            SectionHashRequestC2SPacket request = SectionHashRequestC2SPacket.decode(buf);
                            ServerChunkPushManager.getInstance().handleSectionHashRequest(player, request);
                        } catch (Exception e) {
                            LOGGER.error("[SERVER] Failed to handle section hash request", e);
                        }
                    });
                    ctx.setPacketHandled(true);
                },
                java.util.Optional.of(PlayNetworkDirection.PLAY_TO_SERVER));
#endif

        // 7: Section Delta S2C
        CHANNEL.registerMessage(packetId++, SectionDeltaWrapper.class,
                SectionDeltaWrapper::encode, SectionDeltaWrapper::decode,
#if MC_VER < MC_1_20_2
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> {
                        try {
                            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
                            SectionDeltaS2CPacket packet = SectionDeltaS2CPacket.decode(buf);
                            ClientMetadataHandler.handleSectionDeltaPacket(packet);
                        } catch (Exception e) {
                            LOGGER.error("[CLIENT] Failed to handle section delta", e);
                        }
                    });
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT));
#else
                (msg, ctx) -> {
                    ctx.enqueueWork(() -> {
                        try {
                            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
                            SectionDeltaS2CPacket packet = SectionDeltaS2CPacket.decode(buf);
                            ClientMetadataHandler.handleSectionDeltaPacket(packet);
                        } catch (Exception e) {
                            LOGGER.error("[CLIENT] Failed to handle section delta", e);
                        }
                    });
                    ctx.setPacketHandled(true);
                },
                java.util.Optional.of(PlayNetworkDirection.PLAY_TO_CLIENT));
#endif

        // 8: BlockEntity 请求 C2S
        CHANNEL.registerMessage(packetId++, BlockEntityRequestWrapper.class,
                BlockEntityRequestWrapper::encode, BlockEntityRequestWrapper::decode,
#if MC_VER < MC_1_20_2
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> {
                        ServerPlayer player = ctx.get().getSender();
                        if (player == null) return;
                        try {
                            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
                            BlockEntityRequestC2SPacket request = BlockEntityRequestC2SPacket.decode(buf);
                            ServerChunkPushManager.getInstance().handleBlockEntityRequest(player, request);
                        } catch (Exception e) {
                            LOGGER.error("[SERVER] Failed to handle block entity request", e);
                        }
                    });
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER));
#else
                (msg, ctx) -> {
                    ctx.enqueueWork(() -> {
                        ServerPlayer player = ctx.getSender();
                        if (player == null) return;
                        try {
                            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
                            BlockEntityRequestC2SPacket request = BlockEntityRequestC2SPacket.decode(buf);
                            ServerChunkPushManager.getInstance().handleBlockEntityRequest(player, request);
                        } catch (Exception e) {
                            LOGGER.error("[SERVER] Failed to handle block entity request", e);
                        }
                    });
                    ctx.setPacketHandled(true);
                },
                java.util.Optional.of(PlayNetworkDirection.PLAY_TO_SERVER));
#endif

        // 9: BlockEntity 数据 S2C
        CHANNEL.registerMessage(packetId++, BlockEntityDataWrapper.class,
                BlockEntityDataWrapper::encode, BlockEntityDataWrapper::decode,
#if MC_VER < MC_1_20_2
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> {
                        try {
                            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
                            BlockEntityDataS2CPacket packet = BlockEntityDataS2CPacket.decode(buf);
                            ClientMetadataHandler.handleBlockEntityDataPacket(packet);
                        } catch (Exception e) {
                            LOGGER.error("[CLIENT] Failed to handle block entity data", e);
                        }
                    });
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT));
#else
                (msg, ctx) -> {
                    ctx.enqueueWork(() -> {
                        try {
                            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
                            BlockEntityDataS2CPacket packet = BlockEntityDataS2CPacket.decode(buf);
                            ClientMetadataHandler.handleBlockEntityDataPacket(packet);
                        } catch (Exception e) {
                            LOGGER.error("[CLIENT] Failed to handle block entity data", e);
                        }
                    });
                    ctx.setPacketHandled(true);
                },
                java.util.Optional.of(PlayNetworkDirection.PLAY_TO_CLIENT));
#endif

        LOGGER.info("Hassium: Registered {} SimpleChannel packets", packetId);
    }

    private void handleHandshakeSimple(ServerPlayer player, HandshakeWrapper msg) {
        PlayerCompressionTracker.enableCompression(player);

        boolean serverSupportsGlobalCompression = HassiumConfigService.getInstance().isGlobalPacketCompressionEnabled();
        boolean useGlobalCompression = serverSupportsGlobalCompression && msg.globalPacketCompressionSupported();
        boolean serverSupportsCompactHeader = HassiumConfigService.getInstance().isCompactHeaderEnabled();
        boolean useCompactHeader = serverSupportsCompactHeader && msg.compactHeaderSupported();

        boolean accepted = true;
        HandshakeResponseWrapper response = new HandshakeResponseWrapper(
                Constants.CURRENT_PROTOCOL_VERSION,
                accepted,
                useGlobalCompression,
                useCompactHeader
        );
#if MC_VER < MC_1_20_2
        CHANNEL.sendTo(response, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
#else
        CHANNEL.sendTo(response, player.connection.connection, PlayNetworkDirection.PLAY_TO_CLIENT);
#endif
        LOGGER.info("Hassium: Server handshake for {}: accepted={}, globalCompression={}, compactHeader={}",
                player.getName().getString(), accepted, useGlobalCompression, useCompactHeader);
    }

    private void handleHandshakeResponseSimple(HandshakeResponseWrapper msg) {
        LOGGER.info("Hassium: Client handshake response: accepted={}, globalCompression={}, compactHeader={}",
                msg.accepted(), msg.globalCompressionAccepted(), msg.compactHeaderAccepted());
    }

#else
    /**
     * 注册所有 Payload (1.20.5+)
     */
    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        if (!HassiumConfigService.getInstance().isNetworkCompressionEnabled()) {
            LOGGER.warn("Hassium: network.enabled=false, skipping NeoForge Payload registration");
            return;
        }
        LOGGER.debug("Hassium: Registering NeoForge Payload handlers");

        var registrar = event.registrar(PROTOCOL_VERSION);

        // 注册握手请求 (C2S)
        registrar.playToServer(
                HandshakePayload.TYPE,
                HandshakePayload.STREAM_CODEC,
                NeoForgeNetworkManager::handleHandshake
        );

        // 注册握手响应 (S2C)
        registrar.playToClient(
                HandshakeResponsePayload.TYPE,
                HandshakeResponsePayload.STREAM_CODEC,
                NeoForgeNetworkManager::handleHandshakeResponse
        );

        // 注册压缩区块数据 (S2C)
        registrar.playToClient(
                CompressedChunkPayload.TYPE,
                CompressedChunkPayload.STREAM_CODEC,
                NeoForgeNetworkManager::handleCompressedChunk
        );

        // 注册区块数据请求 (C2S)
        registrar.playToServer(
                ChunkDataRequestPayload.TYPE,
                ChunkDataRequestPayload.STREAM_CODEC,
                NeoForgeNetworkManager::handleChunkDataRequest
        );

        // 注册区块哈希 (S2C)
        registrar.playToClient(
                ChunkHashPayload.TYPE,
                ChunkHashPayload.STREAM_CODEC,
                NeoForgeNetworkManager::handleChunkHash
        );

        // 注册 Section 哈希请求 (C2S)
        registrar.playToServer(
                SectionHashRequestPayload.TYPE,
                SectionHashRequestPayload.STREAM_CODEC,
                NeoForgeNetworkManager::handleSectionHashRequest
        );

        // 注册 Section Delta (S2C)
        registrar.playToClient(
                SectionDeltaPayload.TYPE,
                SectionDeltaPayload.STREAM_CODEC,
                NeoForgeNetworkManager::handleSectionDelta
        );

        // 注册 BlockEntity 请求 (C2S)
        registrar.playToServer(
                BlockEntityRequestPayload.TYPE,
                BlockEntityRequestPayload.STREAM_CODEC,
                NeoForgeNetworkManager::handleBlockEntityRequest
        );

        // 注册 BlockEntity 数据 (S2C)
        registrar.playToClient(
                BlockEntityDataPayload.TYPE,
                BlockEntityDataPayload.STREAM_CODEC,
                NeoForgeNetworkManager::handleBlockEntityData
        );

        LOGGER.info("Hassium: Registered all NeoForge payload handlers");
    }

    private static void handleHandshake(HandshakePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                PlayerCompressionTracker.enableCompression(player);
                boolean useGlobalCompression = HassiumConfigService.getInstance().isGlobalPacketCompressionEnabled()
                        && payload.globalPacketCompressionSupported();
                boolean useCompactHeader = HassiumConfigService.getInstance().isCompactHeaderEnabled()
                        && payload.compactHeaderSupported();
                boolean accepted = true;
                HandshakeResponsePayload response = new HandshakeResponsePayload(
                        Constants.CURRENT_PROTOCOL_VERSION, accepted, useGlobalCompression, useCompactHeader);
                player.connection.send(response);
                LOGGER.info("Hassium: Server handshake for {}: accepted={}, globalCompression={}, compactHeader={}",
                        player.getName().getString(), accepted, useGlobalCompression, useCompactHeader);
            }
        });
    }

    private static void handleHandshakeResponse(HandshakeResponsePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            LOGGER.info("Hassium: Client handshake response: accepted={}, globalCompression={}, compactHeader={}",
                    payload.accepted(), payload.globalCompressionAccepted(), payload.compactHeaderAccepted());
        });
    }

    private static void handleCompressedChunk(CompressedChunkPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                ClientChunkHandler.handleCompressedChunk(payload.data());
            } catch (Exception e) {
                LOGGER.error("[CLIENT] Failed to handle compressed chunk", e);
            }
        });
    }

    private static void handleChunkDataRequest(ChunkDataRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                if (context.player() instanceof ServerPlayer player) {
                    FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(payload.data()));
                    ChunkDataRequestC2SPacket request = ChunkDataRequestC2SPacket.decode(buf);
                    ServerChunkPushManager.getInstance().enqueueDataRequest(player, request.dimension(), request.chunks());
                }
            } catch (Exception e) {
                LOGGER.error("[SERVER] Failed to handle chunk data request", e);
            }
        });
    }

    private static void handleChunkHash(ChunkHashPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(payload.data()));
                ChunkHashS2CPacket packet = ChunkHashS2CPacket.decode(buf);
                ClientMetadataHandler.handleChunkHashPacket(packet);
            } catch (Exception e) {
                LOGGER.error("[CLIENT] Failed to handle chunk hash", e);
            }
        });
    }

    private static void handleSectionHashRequest(SectionHashRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                if (context.player() instanceof ServerPlayer player) {
                    FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(payload.data()));
                    SectionHashRequestC2SPacket request = SectionHashRequestC2SPacket.decode(buf);
                    ServerChunkPushManager.getInstance().handleSectionHashRequest(player, request);
                }
            } catch (Exception e) {
                LOGGER.error("[SERVER] Failed to handle section hash request", e);
            }
        });
    }

    private static void handleSectionDelta(SectionDeltaPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(payload.data()));
                SectionDeltaS2CPacket packet = SectionDeltaS2CPacket.decode(buf);
                ClientMetadataHandler.handleSectionDeltaPacket(packet);
            } catch (Exception e) {
                LOGGER.error("[CLIENT] Failed to handle section delta", e);
            }
        });
    }

    private static void handleBlockEntityRequest(BlockEntityRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                if (context.player() instanceof ServerPlayer player) {
                    FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(payload.data()));
                    BlockEntityRequestC2SPacket request = BlockEntityRequestC2SPacket.decode(buf);
                    ServerChunkPushManager.getInstance().handleBlockEntityRequest(player, request);
                }
            } catch (Exception e) {
                LOGGER.error("[SERVER] Failed to handle block entity request", e);
            }
        });
    }

    private static void handleBlockEntityData(BlockEntityDataPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(payload.data()));
                BlockEntityDataS2CPacket packet = BlockEntityDataS2CPacket.decode(buf);
                ClientMetadataHandler.handleBlockEntityDataPacket(packet);
            } catch (Exception e) {
                LOGGER.error("[CLIENT] Failed to handle block entity data", e);
            }
        });
    }
#endif

    // ========== 发送方法实现 ==========

    @Override
    public void sendHandshakeRequest() {
        if (!HassiumConfigService.getInstance().isNetworkCompressionEnabled()) {
            LOGGER.debug("Hassium: Skip handshake — network.enabled=false");
            return;
        }
#if MC_VER < MC_1_20_5
        if (net.minecraft.client.Minecraft.getInstance().getConnection() != null) {
            String compressionAlgorithm = HassiumConfigService.getInstance().getCompressionAlgorithm();
            String dictAlgorithm = compressionAlgorithm + "_dict";
            CHANNEL.sendToServer(new HandshakeWrapper(
                    Constants.CURRENT_PROTOCOL_VERSION,
                    Constants.MOD_VERSION,
                    new String[]{compressionAlgorithm, dictAlgorithm},
                    true, true, false, true, true
            ));
            LOGGER.debug("Hassium: Sent handshake request to server");
        } else {
            LOGGER.warn("Hassium: Cannot send handshake request, connection is null");
        }
#else
        if (net.minecraft.client.Minecraft.getInstance().getConnection() != null) {
            String compressionAlgorithm = HassiumConfigService.getInstance().getCompressionAlgorithm();
            String dictAlgorithm = compressionAlgorithm + "_dict";
            HandshakePayload payload = new HandshakePayload(
                    Constants.CURRENT_PROTOCOL_VERSION,
                    Constants.MOD_VERSION,
                    new String[]{compressionAlgorithm, dictAlgorithm},
                    true, true, false, true, true
            );
            net.minecraft.client.Minecraft.getInstance().getConnection().send(payload);
            LOGGER.debug("Hassium: Sent handshake request (Payload)");
        }
#endif
    }

    @Override
    public void sendChunkDataRequest(FriendlyByteBuf buf) {
#if MC_VER < MC_1_20_5
        if (net.minecraft.client.Minecraft.getInstance().getConnection() != null) {
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            buf.release();
            CHANNEL.sendToServer(new ChunkDataRequestWrapper(data));
            LOGGER.debug("Hassium: Sent chunk data request (SimpleChannel)");
        } else {
            buf.release();
        }
#else
        if (net.minecraft.client.Minecraft.getInstance().getConnection() != null) {
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            buf.release();
            ChunkDataRequestPayload payload = new ChunkDataRequestPayload(data);
            net.minecraft.client.Minecraft.getInstance().getConnection().send(payload);
            LOGGER.debug("Hassium: Sent chunk data request (Payload)");
        } else {
            buf.release();
        }
#endif
    }

    @Override
    public void sendCompressedPayload(CompressedPayloadPacket packet) {
        throw new UnsupportedOperationException("Use sendCompressedChunk() instead");
    }

    @Override
    public void sendChunkHashPacket(ServerPlayer player, FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
#if MC_VER < MC_1_20_5
#if MC_VER < MC_1_20_2
        CHANNEL.sendTo(new ChunkHashWrapper(data), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
#else
        CHANNEL.sendTo(new ChunkHashWrapper(data), player.connection.connection, PlayNetworkDirection.PLAY_TO_CLIENT);
#endif
#else
        ChunkHashPayload payload = new ChunkHashPayload(data);
        player.connection.send(payload);
        LOGGER.debug("Hassium: Sent chunk hash packet to {}", player.getName().getString());
#endif
    }

    @Override
    public void sendSectionHashRequest(FriendlyByteBuf buf) {
#if MC_VER < MC_1_20_5
        if (net.minecraft.client.Minecraft.getInstance().getConnection() != null) {
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            buf.release();
            CHANNEL.sendToServer(new SectionHashRequestWrapper(data));
        } else {
            buf.release();
        }
#else
        if (net.minecraft.client.Minecraft.getInstance().getConnection() != null) {
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            buf.release();
            SectionHashRequestPayload payload = new SectionHashRequestPayload(data);
            net.minecraft.client.Minecraft.getInstance().getConnection().send(payload);
            LOGGER.debug("Hassium: Sent section hash request");
        } else {
            buf.release();
        }
#endif
    }

    @Override
    public void sendSectionDeltaPacket(ServerPlayer player, FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
#if MC_VER < MC_1_20_5
#if MC_VER < MC_1_20_2
        CHANNEL.sendTo(new SectionDeltaWrapper(data), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
#else
        CHANNEL.sendTo(new SectionDeltaWrapper(data), player.connection.connection, PlayNetworkDirection.PLAY_TO_CLIENT);
#endif
#else
        SectionDeltaPayload payload = new SectionDeltaPayload(data);
        player.connection.send(payload);
        LOGGER.debug("Hassium: Sent section delta packet to {}", player.getName().getString());
#endif
    }

    @Override
    public void sendBlockEntityRequest(FriendlyByteBuf buf) {
#if MC_VER < MC_1_20_5
        if (net.minecraft.client.Minecraft.getInstance().getConnection() != null) {
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            buf.release();
            CHANNEL.sendToServer(new BlockEntityRequestWrapper(data));
        } else {
            buf.release();
        }
#else
        if (net.minecraft.client.Minecraft.getInstance().getConnection() != null) {
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            buf.release();
            BlockEntityRequestPayload payload = new BlockEntityRequestPayload(data);
            net.minecraft.client.Minecraft.getInstance().getConnection().send(payload);
            LOGGER.debug("Hassium: Sent block entity request");
        } else {
            buf.release();
        }
#endif
    }

    @Override
    public void sendBlockEntityData(ServerPlayer player, FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
#if MC_VER < MC_1_20_5
#if MC_VER < MC_1_20_2
        CHANNEL.sendTo(new BlockEntityDataWrapper(data), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
#else
        CHANNEL.sendTo(new BlockEntityDataWrapper(data), player.connection.connection, PlayNetworkDirection.PLAY_TO_CLIENT);
#endif
#else
        BlockEntityDataPayload payload = new BlockEntityDataPayload(data);
        player.connection.send(payload);
        LOGGER.debug("Hassium: Sent block entity data packet to {}", player.getName().getString());
#endif
    }

    /**
     * 发送压缩区块数据到指定玩家
     */
    public static void sendCompressedChunk(ServerPlayer player, ChunkCompressionHandler.CompressedChunkData compressed) {
        try {
            LOGGER.debug("[SEND_CHUNK] Sending compressed chunk [{}, {}] to player {} (size={})",
                    compressed.chunkX, compressed.chunkZ, player.getName().getString(),
                    compressed.compressedData.length);

            byte[] data = compressed.encode();

#if MC_VER < MC_1_20_5
#if MC_VER < MC_1_20_2
            CHANNEL.sendTo(new CompressedChunkWrapper(data), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
#else
            CHANNEL.sendTo(new CompressedChunkWrapper(data), player.connection.connection, PlayNetworkDirection.PLAY_TO_CLIENT);
#endif
#else
            CompressedChunkPayload payload = new CompressedChunkPayload(data);
            player.connection.send(payload);
#endif
            LOGGER.debug("[SEND_CHUNK] Successfully sent chunk [{}, {}] to {}",
                    compressed.chunkX, compressed.chunkZ, player.getName().getString());
        } catch (Exception e) {
            LOGGER.error("[SEND_CHUNK] Failed to send chunk to {}", player.getName().getString(), e);
        }
    }
}
