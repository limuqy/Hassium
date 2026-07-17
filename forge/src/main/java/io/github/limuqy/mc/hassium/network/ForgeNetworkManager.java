package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import net.minecraft.network.FriendlyByteBuf;
#if MC_VER < MC_1_21_11
import net.minecraft.resources.ResourceLocation;
#else
import net.minecraft.resources.Identifier;
#endif
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

#if MC_VER < MC_1_20_2
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
#endif

/**
 * Forge 平台网络管理器实现。
 * <p>
 * 版本整段切分（见 docs/version-segments.md）：
 * <ul>
 *   <li>{@code MC_VER < MC_1_20_2}：SimpleChannel 完整实现</li>
 *   <li>{@code MC_VER >= MC_1_20_2}：SimpleChannel 已移除，整段 stub（本加载器不在多数高版本 builds_for 中）</li>
 * </ul>
 */
public class ForgeNetworkManager implements NetworkManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/Network");
    private static final String PROTOCOL_VERSION = "1";

    // 创建网络通道
#if MC_VER < MC_1_20_2
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocationCompat.create(Constants.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );
#else
    // 1.20.2+: SimpleChannel API removed, networking disabled
    public static final Object CHANNEL = null;
#endif

    private static int packetId = 0;

    @Override
    public void registerChannels() {
        if (!HassiumConfigService.getInstance().isNetworkCompressionEnabled()) {
            LOGGER.warn("Hassium: network.enabled=false, skipping Forge channel registration");
            return;
        }
        LOGGER.info("Hassium: Registering Forge network channels");
#if MC_VER < MC_1_20_2
        // 必须 setPacketHandled(true)（在 enqueueWork 外），否则 Forge 会把包交给原版
        // S2C / C2S 必须带 NetworkDirection，避免方向校验失败（与 NeoForge 1.20.1 对齐）

        // 0: 握手请求 C2S
        CHANNEL.<HandshakePacket>registerMessage(
                packetId++,
                HandshakePacket.class,
                HandshakePacket::encode,
                HandshakePacket::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> {
                        ServerPlayer player = ctx.get().getSender();
                        if (player == null) {
                            LOGGER.error("Hassium: Received handshake from non-player");
                            return;
                        }

                        LOGGER.info("Hassium: Received handshake from client {}, protocol: {}, globalCompression: {}, compactHeader: {}",
                                player.getName().getString(), msg.protocolVersion(), msg.globalPacketCompressionSupported(), msg.compactHeaderSupported());

                        PlayerCompressionTracker.enableCompression(player);

                        boolean serverSupportsGlobalCompression = HassiumConfigService.getInstance().isGlobalPacketCompressionEnabled();
                        boolean useGlobalCompression = serverSupportsGlobalCompression && msg.globalPacketCompressionSupported();
                        boolean serverSupportsCompactHeader = HassiumConfigService.getInstance().isCompactHeaderEnabled();
                        boolean useCompactHeader = serverSupportsCompactHeader && msg.compactHeaderSupported();

                        HandshakeResponsePacket response = new HandshakeResponsePacket(
                                Constants.CURRENT_PROTOCOL_VERSION,
                                true,
                                useGlobalCompression,
                                useCompactHeader
                        );
                        CHANNEL.sendTo(response, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
                        LOGGER.info("Hassium: Sent handshake response to client {}, globalCompression: {}, compactHeader: {}",
                                player.getName().getString(), useGlobalCompression, useCompactHeader);

                        // 不在此处 switchToZstd：握手后立即切管道会与仍在飞行的原版包竞态，
                        // 且 Forge 客户端 pipeline 常找不到 decoder/encoder（装到末尾）→
                        // AggregatedZstdDecoder: Unsupported frame parameter → 断连。
                        // 与 NeoForge 一致：全局 ZSTD 管道切换暂不启用，区块仍走 hassium:main 自定义通道。
                        if (useGlobalCompression) {
                            LOGGER.info("Hassium: Global ZSTD pipeline switch deferred on Forge (custom channel only)");
                        }
                    });
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );

        // 1: 握手响应 S2C
        CHANNEL.<HandshakeResponsePacket>registerMessage(
                packetId++,
                HandshakeResponsePacket.class,
                HandshakeResponsePacket::encode,
                HandshakeResponsePacket::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> {
                        LOGGER.info("Hassium: Received handshake response, accepted: {}, globalCompression: {}, compactHeader: {}",
                                msg.accepted(), msg.globalCompressionAccepted(), msg.compactHeaderAccepted());

                        // 同上：Forge 暂不切换全局 ZSTD 管道（见服务端握手处理注释）
                        if (msg.accepted() && msg.globalCompressionAccepted()) {
                            LOGGER.info("Hassium: Global ZSTD accepted but pipeline switch deferred on Forge");
                        }
                    });
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

        // 2: 压缩区块 S2C
        CHANNEL.<CompressedPayloadWrapper>registerMessage(
                packetId++,
                CompressedPayloadWrapper.class,
                CompressedPayloadWrapper::encode,
                CompressedPayloadWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> {
                        try {
                            ClientChunkHandler.handleCompressedChunk(msg.data());
                        } catch (Exception e) {
                            LOGGER.error("Hassium: Failed to handle compressed payload", e);
                        }
                    });
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

        // 3: 区块元数据 S2C
        CHANNEL.<MetadataWrapper>registerMessage(
                packetId++,
                MetadataWrapper.class,
                MetadataWrapper::encode,
                MetadataWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> {
                        try {
                            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
                            ChunkMetadataS2CPacket packet = ChunkMetadataS2CPacket.decode(buf);
                            ClientMetadataHandler.handleMetadataPacket(packet);
                        } catch (Exception e) {
                            LOGGER.error("Hassium: Failed to handle metadata packet", e);
                        }
                    });
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

        // 4: 区块数据请求 C2S
        CHANNEL.<DataRequestWrapper>registerMessage(
                packetId++,
                DataRequestWrapper.class,
                DataRequestWrapper::encode,
                DataRequestWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> {
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
                    });
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );

        // 5: 区块哈希 S2C
        CHANNEL.<ChunkHashWrapper>registerMessage(
                packetId++,
                ChunkHashWrapper.class,
                ChunkHashWrapper::encode,
                ChunkHashWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> {
                        try {
                            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
                            ChunkHashS2CPacket packet = ChunkHashS2CPacket.decode(buf);
                            ClientMetadataHandler.handleChunkHashPacket(packet);
                        } catch (Exception e) {
                            LOGGER.error("Hassium: Failed to handle chunk hash packet", e);
                        }
                    });
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

        // 6: section 哈希请求 C2S
        CHANNEL.<SectionHashRequestWrapper>registerMessage(
                packetId++,
                SectionHashRequestWrapper.class,
                SectionHashRequestWrapper::encode,
                SectionHashRequestWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> {
                        try {
                            ServerPlayer player = ctx.get().getSender();
                            if (player == null) return;

                            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
                            SectionHashRequestC2SPacket request = SectionHashRequestC2SPacket.decode(buf);
                            ServerChunkPushManager.getInstance().handleSectionHashRequest(player, request);
                        } catch (Exception e) {
                            LOGGER.error("Hassium: Failed to handle section hash request", e);
                        }
                    });
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );

        // 7: section delta S2C
        CHANNEL.<SectionDeltaWrapper>registerMessage(
                packetId++,
                SectionDeltaWrapper.class,
                SectionDeltaWrapper::encode,
                SectionDeltaWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> {
                        try {
                            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
                            SectionDeltaS2CPacket packet = SectionDeltaS2CPacket.decode(buf);
                            ClientMetadataHandler.handleSectionDeltaPacket(packet);
                        } catch (Exception e) {
                            LOGGER.error("Hassium: Failed to handle section delta packet", e);
                        }
                    });
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

        // 8: blockEntity 请求 C2S
        CHANNEL.<BlockEntityRequestWrapper>registerMessage(
                packetId++,
                BlockEntityRequestWrapper.class,
                BlockEntityRequestWrapper::encode,
                BlockEntityRequestWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> {
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
                    });
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );

        // 9: blockEntity 数据 S2C
        CHANNEL.<BlockEntityDataWrapper>registerMessage(
                packetId++,
                BlockEntityDataWrapper.class,
                BlockEntityDataWrapper::encode,
                BlockEntityDataWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> {
                        try {
                            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
                            BlockEntityDataS2CPacket packet = BlockEntityDataS2CPacket.decode(buf);
                            ClientMetadataHandler.handleBlockEntityDataPacket(packet);
                        } catch (Exception e) {
                            LOGGER.error("Hassium: Failed to handle block entity data packet", e);
                        }
                    });
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

        LOGGER.info("Hassium: Registered {} network packets", packetId);
#else
        LOGGER.warn("Hassium: Forge SimpleChannel networking not supported on 1.20.6+, networking disabled");
#endif
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
                true,  // clientCacheSupported
                true,  // chunkRevisionSupported
                false, // scheme127Supported
                true,  // globalPacketCompressionSupported
                true   // compactHeaderSupported
        );
#if MC_VER < MC_1_20_2
        CHANNEL.sendToServer(packet);
#else
        LOGGER.warn("Hassium: Forge networking not supported on 1.20.6+, dropping handshake request");
#endif
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
#if MC_VER < MC_1_20_2
        CHANNEL.sendToServer(new DataRequestWrapper(data));
#else
        LOGGER.warn("Hassium: Forge networking not supported on 1.20.6+, dropping chunk data request");
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
        LOGGER.warn("Hassium: Forge networking not supported on 1.20.6+, dropping chunk hash packet");
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
        LOGGER.warn("Hassium: Forge networking not supported on 1.20.6+, dropping section hash request");
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
        LOGGER.warn("Hassium: Forge networking not supported on 1.20.6+, dropping section delta packet");
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
        LOGGER.warn("Hassium: Forge networking not supported on 1.20.6+, dropping block entity request");
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
        LOGGER.warn("Hassium: Forge networking not supported on 1.20.6+, dropping block entity data");
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
            LOGGER.debug("Hassium: Sent compressed chunk [{}, {}] to player {}",
                    compressed.chunkX, compressed.chunkZ, player.getName().getString());
#else
            LOGGER.warn("Hassium: Forge networking not supported on 1.20.6+, dropping compressed chunk [{}, {}]",
                    compressed.chunkX, compressed.chunkZ);
#endif
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
