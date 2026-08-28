package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.HassiumChannels;
import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.ServerChunkPushManager;
import io.github.limuqy.mc.hassium.platform.Services;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

#if MC_VER < MC_1_21_1
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
#else
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
#endif

import java.lang.reflect.Field;
import java.util.UUID;
import io.github.limuqy.mc.hassium.network.HassiumConnectionRegistry;
import io.github.limuqy.mc.hassium.network.HassiumAggregationManager;
import io.github.limuqy.mc.hassium.network.ZstdNegotiationTracker;
import io.netty.channel.Channel;

/**
 * NeoForge 平台网络管理器实现。
 * <p>
 * 版本整段切分（见 docs/version-segments.md；1.20.2–1.20.6 支路已随版本线收编退役）：
 * <ul>
 *   <li>{@code MC_VER < MC_1_21_1}：SimpleChannel（1.20.1 仍用 forge 包名）</li>
 *   <li>{@code MC_VER >= MC_1_21_1}：Payload + StreamCodec（API 自 1.21.1 前版本线起变化）</li>
 * </ul>
 * common 聚合能力由 {@link io.github.limuqy.mc.hassium.compat.NetworkCapability} 门控。
 */
public class NeoForgeNetworkManager implements NetworkManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/NeoForgeNetwork");
    private static final String PROTOCOL_VERSION = "1";
    // review-fix: T11-15 握手算法列表上限（防恶意 varint 超大分配 / NegativeArraySizeException）
    private static final int MAX_HANDSHAKE_ALGORITHMS = 64;

    // review-fix: T10-M2（同 REPORT T11-M2）：共享调度器，防每次握手新建单线程调度执行器泄漏线程；JVM 关闭钩子回收
    private static final java.util.concurrent.ScheduledExecutorService PENDING_TIMEOUT_SCHEDULER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Hassium-PendingTimeout");
                t.setDaemon(true);
                return t;
            });

    static {
        Runtime.getRuntime().addShutdownHook(
                new Thread(PENDING_TIMEOUT_SCHEDULER::shutdownNow, "Hassium-PendingTimeoutShutdown"));
    }

    // 缓存服务器实例
    private static volatile net.minecraft.server.MinecraftServer cachedServer;

    /**
     * 设置服务器实例
     */
    public static void setServerInstance(net.minecraft.server.MinecraftServer server) {
        cachedServer = server;
    }

    /**
     * 通过反射获取 Connection 的 channel 字段（委托 common 类型匹配实现，SRG/intermediary 安全）
     */
    private static io.netty.channel.Channel getConnectionChannel(net.minecraft.network.Connection connection) {
        return ZstdPipelineSwitcher.getConnectionChannel(connection);
    }

    /**
     * 通过反射获取 ServerPlayer 的 Connection
     */
    private static net.minecraft.network.Connection getPlayerConnection(ServerPlayer player) {
        return io.github.limuqy.mc.hassium.compat.PlayerCompat.getConnection(player);
    }

    /**
     * 客户端收到 HandshakeResponse(globalCompression=true) 后安装 ZSTD 管线。
     * 管线未就绪时由 {@link ZstdPipelineSwitcher#switchToZstdWhenReady} 短间隔重试。
     */
    private static void tryInstallClientZstdPipeline() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        var conn = mc.getConnection();
        if (conn == null) {
            LOGGER.warn("Hassium: Cannot install client ZSTD pipeline — no connection");
            return;
        }
        Channel channel = getConnectionChannel(conn.getConnection());
        if (channel == null) {
            LOGGER.warn("Hassium: Cannot install client ZSTD pipeline — no channel");
            return;
        }
        int level = HassiumConfigService.getInstance().getGlobalCompressionLevel();
        int threshold = HassiumConfigService.getInstance().getGlobalCompressionThreshold();
        // 安装成功后再 markNegotiated + ACK：服务端仍发未压缩帧，双方可安全对齐
        ZstdPipelineSwitcher.switchToZstdWhenReady(channel, threshold, level, () -> {
            ZstdNegotiationTracker.markNegotiated(channel);
            // 对齐 forge：装 ZSTD 后暂停出站压缩（只发明文帧），服务端装好
            // ZSTD 并发 IndexSync 后恢复——防客户端压缩大包（bloom manifest 等）
            // 在服务端就绪前到达被 zlib 解炸（1.21.11 fabric R2 已复现）
            ZstdPipelineSwitcher.pauseOutboundCompression(channel);
            sendCompressionReadyToServer();
            LOGGER.info("Hassium: Client ZSTD pipeline installed, sent ready ACK (outbound paused)");
        });
    }

    /**
     * 服务端在收到客户端 ZSTD ready ACK 后安装管线并同步 Dict/Index/chunkHash。
     */
    private static void installServerZstdAfterClientReady(ServerPlayer player, Connection connection, Channel channel) {
        int level = HassiumConfigService.getInstance().getGlobalCompressionLevel();
        int threshold = HassiumConfigService.getInstance().getGlobalCompressionThreshold();
        ZstdPipelineSwitcher.switchToZstdWhenReady(channel, threshold, level, () -> {
            ZstdNegotiationTracker.markNegotiated(channel);
            sendDictionarySyncPacket(player);
            sendIndexSyncPacket(player);
            ServerChunkPushManager.getInstance().resyncTrackedChunks(player);
            if (connection != null) {
                HassiumConnectionRegistry.markPending(connection);
                HassiumAggregationManager.init();
                schedulePendingTimeout(connection, player.getName().getString());
            }
            LOGGER.info("Hassium: Server ZSTD pipeline installed for {}", player.getName().getString());
        });
    }

    /**
     * 从当前已绑定 listener 与当前 TCP master 构造 append-only 数据面握手尾部。
     * 控制恢复与 UDP bind 独立协商，因此即使 UDP 未绑定也为 control-only 会话分配 epoch。
     */
    private static io.github.limuqy.mc.hassium.network.dataplane.UdpDataPlaneHandshakeTail.S2CTail
    createDataPlaneHandshakeTail(
            ServerPlayer player,
            boolean accepted,
            io.github.limuqy.mc.hassium.network.dataplane.UdpDataPlaneHandshakeTail.C2STail clientCapabilities) {
        if (!accepted) {
            return io.github.limuqy.mc.hassium.network.dataplane.UdpDataPlaneHandshakeTail.S2CTail.disabled();
        }
        io.github.limuqy.mc.hassium.network.dataplane.UdpDataPlaneHandshakeTail.C2STail capabilities =
                clientCapabilities == null
                        ? new io.github.limuqy.mc.hassium.network.dataplane.UdpDataPlaneHandshakeTail.C2STail(false, false)
                        : clientCapabilities;
        try {
            Connection master = getPlayerConnection(player);
            if (master == null) {
                throw new IllegalStateException("missing player connection");
            }
            long epoch = io.github.limuqy.mc.hassium.network.dataplane.DataPlaneUdpServer.beginControlConnection(
                    player.getUUID(), () -> master.disconnect(net.minecraft.network.chat.Component.empty()));
            boolean udpBound = io.github.limuqy.mc.hassium.network.dataplane.DataPlaneUdpServer.isBound();
            return io.github.limuqy.mc.hassium.network.dataplane.DataPlaneHandshakeAdvertisement.create(
                    io.github.limuqy.mc.hassium.network.dataplane.DataPlaneUdpServer.advertisedControlEndpoints(),
                    io.github.limuqy.mc.hassium.network.dataplane.DataPlaneUdpServer.boundEndpoints(),
                    // D-M1: per-player per-epoch bind token（epoch 变更即轮换；旧 token 直接失效）
                    udpBound
                            ? io.github.limuqy.mc.hassium.network.dataplane.DataPlaneUdpServer.getBindToken(
                                    player.getUUID(), epoch)
                            : null,
                    epoch,
                    capabilities.udpDataplaneSupported() && udpBound,
                    capabilities.controlFailoverSupported());
        } catch (RuntimeException ex) {
            LOGGER.warn("Hassium: Failed to append dataplane tail for {}", player.getName().getString(), ex);
            return io.github.limuqy.mc.hassium.network.dataplane.UdpDataPlaneHandshakeTail.S2CTail.disabled();
        }
    }

    /** 在各 NeoForge payload context 的主线程执行；非法 tail 绝不作为部分 UDP 配置继续使用。 */
    private static void startUdpFromHandshakeTail(byte[] rawTail) {
        if (rawTail == null || rawTail.length == 0) {
            return;
        }
        final io.github.limuqy.mc.hassium.network.dataplane.UdpDataPlaneHandshakeTail.S2CTail tail;
        try {
            tail = io.github.limuqy.mc.hassium.network.dataplane.UdpDataPlaneHandshakeTail.readS2C(
                    io.netty.buffer.Unpooled.wrappedBuffer(rawTail));
        } catch (IllegalArgumentException ex) {
            LOGGER.warn("Hassium: Invalid dataplane tail in handshake response", ex);
            return;
        }
        LOGGER.info("Hassium: Client dataplane handshake tail: udp={}, controlFailover={}, groups={}, playerReady={}",
                tail.hasUdpDataplane(), tail.hasControlFailover(), tail.udpListenerGroups().size(),
                net.minecraft.client.Minecraft.getInstance().player != null);
        // T6：客户端 failover 已退役——不再灌 advertised control 候选 / 不再确认真实恢复，
        // 仅保留 UDP 数据面启动（网关 outbound 传输层）。
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player == null) {
            io.github.limuqy.mc.hassium.network.dataplane.DataPlaneClientLifecycle.getInstance().deferUdpStart(tail);
            LOGGER.debug("Hassium: Deferred UDP dataplane start until client player exists");
            return;
        }
        try {
            io.github.limuqy.mc.hassium.network.dataplane.DataPlaneClientLifecycle.getInstance()
                    .startUdp(player.getUUID(), tail.connectionEpoch(), tail);
        } catch (RuntimeException ex) {
            LOGGER.warn("Hassium: UDP dataplane start failed", ex);
        }
        catch (LinkageError ex) {
            LOGGER.warn("Hassium: UDP dataplane linkage failed; keeping primary transport", ex);
        }
    }

#if MC_VER < MC_1_21_1
    // 1.20.1: SimpleChannel（forge 包名；neoforged 包名支路已随 1.20.x 支持线退役）
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

    public record SeedRefWrapper(byte[] data) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(data.length);
            buf.writeBytes(data);
        }
        public static SeedRefWrapper decode(FriendlyByteBuf buf) {
            int length = buf.readVarInt();
            byte[] data = new byte[length];
            buf.readBytes(data);
            return new SeedRefWrapper(data);
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

    public record LightDeltaWrapper(byte[] data) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(data.length);
            buf.writeBytes(data);
        }
        public static LightDeltaWrapper decode(FriendlyByteBuf buf) {
            int length = buf.readVarInt();
            byte[] data = new byte[length];
            buf.readBytes(data);
            return new LightDeltaWrapper(data);
        }
    }

    public record DictionarySyncWrapper(byte[] data) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(data.length);
            buf.writeBytes(data);
        }
        public static DictionarySyncWrapper decode(FriendlyByteBuf buf) {
            int length = buf.readVarInt();
            byte[] data = new byte[length];
            buf.readBytes(data);
            return new DictionarySyncWrapper(data);
        }
    }

    public record IndexSyncWrapper(byte[] data) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(data.length);
            buf.writeBytes(data);
        }
        public static IndexSyncWrapper decode(FriendlyByteBuf buf) {
            int length = buf.readVarInt();
            byte[] data = new byte[length];
            buf.readBytes(data);
            return new IndexSyncWrapper(data);
        }
    }

    public record AggregationWrapper(byte[] data) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(data.length);
            buf.writeBytes(data);
        }
        public static AggregationWrapper decode(FriendlyByteBuf buf) {
            int length = buf.readVarInt();
            byte[] data = new byte[length];
            buf.readBytes(data);
            return new AggregationWrapper(data);
        }
    }

    public record CompressionReadyWrapper(boolean ready) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeBoolean(ready);
        }
        public static CompressionReadyWrapper decode(FriendlyByteBuf buf) {
            return new CompressionReadyWrapper(buf.readBoolean());
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

    public record ClientBloomSyncWrapper(byte[] data) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(data.length);
            buf.writeBytes(data);
        }
        public static ClientBloomSyncWrapper decode(FriendlyByteBuf buf) {
            int length = buf.readVarInt();
            byte[] data = new byte[length];
            buf.readBytes(data);
            return new ClientBloomSyncWrapper(data);
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
            boolean compactHeaderSupported,
            io.github.limuqy.mc.hassium.network.dataplane.UdpDataPlaneHandshakeTail.C2STail dataplaneCapabilities,
            double playerX,
            double playerZ,
            boolean seedGenSupported,
            boolean lightComputeSupported,
            HandshakeStateTail.C2S stateTail
    ) {
        public HandshakeWrapper(int protocolVersion, String modVersion, String[] supportedAlgorithms,
                                boolean clientCacheSupported, boolean chunkRevisionSupported,
                                boolean scheme127Supported, boolean globalPacketCompressionSupported,
                                boolean compactHeaderSupported) {
            this(protocolVersion, modVersion, supportedAlgorithms, clientCacheSupported, chunkRevisionSupported,
                    scheme127Supported, globalPacketCompressionSupported, compactHeaderSupported,
                    new io.github.limuqy.mc.hassium.network.dataplane.UdpDataPlaneHandshakeTail.C2STail(false, false),
                    0.0, 0.0, false, false, null);
        }

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
            io.github.limuqy.mc.hassium.network.dataplane.UdpDataPlaneHandshakeTail.writeC2S(buf, dataplaneCapabilities);
            buf.writeDouble(playerX);
            buf.writeDouble(playerZ);
            buf.writeBoolean(seedGenSupported);
            buf.writeBoolean(lightComputeSupported);
            // T7 状态尾部（append-only；旧服务端忽略尾字节）
            if (stateTail != null) {
                HandshakeStateTail.writeC2S(buf, stateTail);
            }
        }

        public static HandshakeWrapper decode(FriendlyByteBuf buf) {
            int protocolVersion = buf.readVarInt();
            String modVersion = buf.readUtf();
            // review-fix: T11-15 上限校验（防恶意 varint OOM/NegativeArraySizeException）
            int algoCount = buf.readVarInt();
            if (algoCount < 0 || algoCount > MAX_HANDSHAKE_ALGORITHMS) {
                throw new IllegalArgumentException("Handshake algorithm count out of range: " + algoCount);
            }
            String[] algorithms = new String[algoCount];
            for (int i = 0; i < algoCount; i++) {
                algorithms[i] = buf.readUtf();
            }
            boolean clientCache = buf.readBoolean();
            boolean chunkRevision = buf.readBoolean();
            boolean scheme127 = buf.readBoolean();
            boolean globalPacketCompression = buf.readBoolean();
            boolean compactHeader = buf.readBoolean();
            io.github.limuqy.mc.hassium.network.dataplane.UdpDataPlaneHandshakeTail.C2STail tail =
                    io.github.limuqy.mc.hassium.network.dataplane.UdpDataPlaneHandshakeTail.readC2S(buf);
            // 坐标在握手尾部（append-only；旧客户端无此字段）
            double playerX = 0.0;
            double playerZ = 0.0;
            if (buf.isReadable()) {
                try {
                    playerX = buf.readDouble();
                    playerZ = buf.readDouble();
                } catch (Exception ignored) {
                }
            }
            // SeedGen 能力（append-only；旧客户端无此字段）
            boolean seedGenSupported = false;
            if (buf.isReadable()) {
                try {
                    seedGenSupported = buf.readBoolean();
                } catch (Exception ignored) {
                }
            }
            // 光照计算能力（append-only；旧客户端无此字段 → false = 不剥光）
            boolean lightComputeSupported = false;
            if (buf.isReadable()) {
                try {
                    lightComputeSupported = buf.readBoolean();
                } catch (Exception ignored) {
                }
            }
            // T7 状态尾部（append-only；旧客户端无此字段 → null）
            HandshakeStateTail.C2S stateTail = null;
            if (buf.isReadable()) {
                stateTail = HandshakeStateTail.readC2S(buf);
            }
            return new HandshakeWrapper(protocolVersion, modVersion, algorithms, clientCache, chunkRevision,
                    scheme127, globalPacketCompression, compactHeader, tail, playerX, playerZ, seedGenSupported,
                    lightComputeSupported, stateTail);
        }
    }

    public record HandshakeResponseWrapper(
            int protocolVersion,
            boolean accepted,
            boolean globalCompressionAccepted,
            boolean compactHeaderAccepted,
            byte[] dataplaneTail,
            long worldSeed,
            byte[] seedGenTail,
            boolean seedGenEnabled,
            boolean newFormat,
            boolean resumeAccepted
    ) {
        public HandshakeResponseWrapper {
            dataplaneTail = dataplaneTail == null ? new byte[0] : dataplaneTail.clone();
            seedGenTail = seedGenTail == null ? new byte[0] : seedGenTail.clone();
        }

        public HandshakeResponseWrapper(int protocolVersion, boolean accepted,
                                        boolean globalCompressionAccepted, boolean compactHeaderAccepted) {
            this(protocolVersion, accepted, globalCompressionAccepted, compactHeaderAccepted, new byte[0],
                    0L, new byte[0], false, false, false);
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(protocolVersion);
            buf.writeBoolean(accepted);
            buf.writeBoolean(globalCompressionAccepted);
            buf.writeBoolean(compactHeaderAccepted);
            if (newFormat) {
                // 新格式（仅当客户端 C2S 上报 seedGenSupported=true 时使用）：
                // varint tailLen + tail + worldSeed + varint seedLen + seed + enabled + resumeAccepted
                buf.writeVarInt(dataplaneTail.length);
                buf.writeBytes(dataplaneTail);
                buf.writeLong(worldSeed);
                buf.writeVarInt(seedGenTail.length);
                buf.writeBytes(seedGenTail);
                buf.writeBoolean(seedGenEnabled);
                // T7 续流就绪标记（append-only；旧客户端忽略尾字节）
                buf.writeBoolean(resumeAccepted);
            } else {
                // 旧格式：tail 无长度前缀（旧客户端读剩余全部）
                buf.writeBytes(dataplaneTail);
            }
        }

        public static HandshakeResponseWrapper decode(FriendlyByteBuf buf) {
            int protocolVersion = buf.readVarInt();
            boolean accepted = buf.readBoolean();
            boolean globalCompressionAccepted = buf.readBoolean();
            boolean compactHeaderAccepted = buf.readBoolean();
            byte[] dataplaneTail;
            long worldSeed = 0L;
            byte[] seedGenTail = new byte[0];
            boolean seedGenEnabled = false;
            // 本客户端上报 true 时服务端保证用新格式；解析失败/旧服务端 → 回退旧格式
            if (io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance().isClientSeedGenEnabled()) {
                try {
                    int tailLen = buf.readVarInt();
                    if (tailLen >= 0 && tailLen <= buf.readableBytes()) {
                        dataplaneTail = new byte[tailLen];
                        buf.readBytes(dataplaneTail);
                        worldSeed = buf.readLong();
                        int seedLen = buf.readVarInt();
                        if (seedLen > 0 && seedLen <= buf.readableBytes()) {
                            seedGenTail = new byte[seedLen];
                            buf.readBytes(seedGenTail);
                        }
                        if (buf.readableBytes() >= 1) {
                            seedGenEnabled = buf.readBoolean();
                        }
                    } else {
                        dataplaneTail = new byte[buf.readableBytes()];
                        buf.readBytes(dataplaneTail);
                    }
                } catch (Exception e) {
                    dataplaneTail = new byte[buf.readableBytes()];
                    buf.readBytes(dataplaneTail);
                }
            } else {
                dataplaneTail = new byte[buf.readableBytes()];
                buf.readBytes(dataplaneTail);
            }
            // T7 续流就绪标记（append-only；旧服务端无此字段 → false）
            boolean resumeAccepted = false;
            if (buf.isReadable()) {
                try {
                    resumeAccepted = buf.readBoolean();
                } catch (Exception ignored) {
                }
            }
            return new HandshakeResponseWrapper(protocolVersion, accepted, globalCompressionAccepted,
                    compactHeaderAccepted, dataplaneTail, worldSeed, seedGenTail, seedGenEnabled, true,
                    resumeAccepted);
        }
    }

#else
    // 1.21.1+: 使用 Payload + StreamCodec

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
            boolean compactHeaderSupported,
            io.github.limuqy.mc.hassium.network.dataplane.UdpDataPlaneHandshakeTail.C2STail dataplaneCapabilities,
            double playerX,
            double playerZ,
            boolean seedGenSupported,
            boolean lightComputeSupported,
            HandshakeStateTail.C2S stateTail
    ) implements CustomPacketPayload {

        public static final Type<HandshakePayload> TYPE = new Type<>(
                ResourceLocationCompat.create(Constants.MOD_ID, "handshake_c2s")
        );

        // StreamCodec.composite 最多 6 字段；握手有附加 capability tail，使用手写编解码。
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
                    io.github.limuqy.mc.hassium.network.dataplane.UdpDataPlaneHandshakeTail.writeC2S(
                            buf, p.dataplaneCapabilities());
                    buf.writeDouble(p.playerX());
                    buf.writeDouble(p.playerZ());
                    buf.writeBoolean(p.seedGenSupported());
                    buf.writeBoolean(p.lightComputeSupported());
                    // T7 状态尾部（append-only；旧服务端忽略尾字节）
                    if (p.stateTail() != null) {
                        HandshakeStateTail.writeC2S(buf, p.stateTail());
                    }
                },
                buf -> {
                    int protocolVersion = buf.readVarInt();
                    String modVersion = buf.readUtf();
                    // review-fix: T11-15 上限校验（防恶意 varint OOM/NegativeArraySizeException）
                    int algCount = buf.readVarInt();
                    if (algCount < 0 || algCount > MAX_HANDSHAKE_ALGORITHMS) {
                        throw new IllegalArgumentException("Handshake algorithm count out of range: " + algCount);
                    }
                    String[] algorithms = new String[algCount];
                    for (int i = 0; i < algCount; i++) {
                        algorithms[i] = buf.readUtf();
                    }
                    boolean clientCache = buf.readBoolean();
                    boolean chunkRevision = buf.readBoolean();
                    boolean scheme127 = buf.readBoolean();
                    boolean globalCompression = buf.readBoolean();
                    boolean compactHeader = buf.readBoolean();
                    io.github.limuqy.mc.hassium.network.dataplane.UdpDataPlaneHandshakeTail.C2STail tail =
                            io.github.limuqy.mc.hassium.network.dataplane.UdpDataPlaneHandshakeTail.readC2S(buf);
                    // 坐标在握手尾部（append-only；旧客户端无此字段）
                    double playerX = 0.0;
                    double playerZ = 0.0;
                    if (buf.isReadable()) {
                        try {
                            playerX = buf.readDouble();
                            playerZ = buf.readDouble();
                        } catch (Exception ignored) {
                        }
                    }
                    // SeedGen 能力（append-only；旧客户端无此字段）
                    boolean seedGenSupported = false;
                    if (buf.isReadable()) {
                        try {
                            seedGenSupported = buf.readBoolean();
                        } catch (Exception ignored) {
                        }
                    }
                    // 光照计算能力（append-only；旧客户端无此字段 → false = 不剥光）
                    boolean lightComputeSupported = false;
                    if (buf.isReadable()) {
                        try {
                            lightComputeSupported = buf.readBoolean();
                        } catch (Exception ignored) {
                        }
                    }
                    // T7 状态尾部（append-only；旧客户端无此字段 → null）
                    HandshakeStateTail.C2S stateTail = null;
                    if (buf.isReadable()) {
                        stateTail = HandshakeStateTail.readC2S(buf);
                    }
                    return new HandshakePayload(protocolVersion, modVersion, algorithms, clientCache, chunkRevision,
                            scheme127, globalCompression, compactHeader, tail, playerX, playerZ, seedGenSupported,
                            lightComputeSupported, stateTail);
                }
        );

        public HandshakePayload(int protocolVersion, String modVersion, String[] supportedAlgorithms,
                                boolean clientCacheSupported, boolean chunkRevisionSupported,
                                boolean scheme127Supported, boolean globalPacketCompressionSupported,
                                boolean compactHeaderSupported) {
            this(protocolVersion, modVersion, supportedAlgorithms, clientCacheSupported, chunkRevisionSupported,
                    scheme127Supported, globalPacketCompressionSupported, compactHeaderSupported,
                    new io.github.limuqy.mc.hassium.network.dataplane.UdpDataPlaneHandshakeTail.C2STail(false, false),
                    0.0, 0.0, false, false, null);
        }

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
            boolean compactHeaderAccepted,
            byte[] dataplaneTail,
            long worldSeed,
            byte[] seedGenTail,
            boolean seedGenEnabled,
            boolean newFormat,
            boolean resumeAccepted
    ) implements CustomPacketPayload {

        public static final Type<HandshakeResponsePayload> TYPE = new Type<>(
                ResourceLocationCompat.create(Constants.MOD_ID, "handshake_s2c")
        );

        public static final StreamCodec<FriendlyByteBuf, HandshakeResponsePayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.protocolVersion());
                    buf.writeBoolean(p.accepted());
                    buf.writeBoolean(p.globalCompressionAccepted());
                    buf.writeBoolean(p.compactHeaderAccepted());
                    if (p.newFormat()) {
                        buf.writeVarInt(p.dataplaneTail().length);
                        buf.writeBytes(p.dataplaneTail());
                        buf.writeLong(p.worldSeed());
                        buf.writeVarInt(p.seedGenTail().length);
                        buf.writeBytes(p.seedGenTail());
                        buf.writeBoolean(p.seedGenEnabled());
                        // T7 续流就绪标记（append-only；旧客户端忽略尾字节）
                        buf.writeBoolean(p.resumeAccepted());
                    } else {
                        buf.writeBytes(p.dataplaneTail());
                    }
                },
                buf -> {
                    int protocolVersion = buf.readVarInt();
                    boolean accepted = buf.readBoolean();
                    boolean globalCompressionAccepted = buf.readBoolean();
                    boolean compactHeaderAccepted = buf.readBoolean();
                    byte[] dataplaneTail;
                    long worldSeed = 0L;
                    byte[] seedGenTail = new byte[0];
                    boolean seedGenEnabled = false;
                    if (io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance().isClientSeedGenEnabled()) {
                        try {
                            int tailLen = buf.readVarInt();
                            if (tailLen >= 0 && tailLen <= buf.readableBytes()) {
                                dataplaneTail = new byte[tailLen];
                                buf.readBytes(dataplaneTail);
                                worldSeed = buf.readLong();
                                int seedLen = buf.readVarInt();
                                if (seedLen > 0 && seedLen <= buf.readableBytes()) {
                                    seedGenTail = new byte[seedLen];
                                    buf.readBytes(seedGenTail);
                                }
                                if (buf.readableBytes() >= 1) {
                                    seedGenEnabled = buf.readBoolean();
                                }
                            } else {
                                dataplaneTail = new byte[buf.readableBytes()];
                                buf.readBytes(dataplaneTail);
                            }
                        } catch (Exception e) {
                            dataplaneTail = new byte[buf.readableBytes()];
                            buf.readBytes(dataplaneTail);
                        }
                    } else {
                        dataplaneTail = new byte[buf.readableBytes()];
                        buf.readBytes(dataplaneTail);
                    }
                    // T7 续流就绪标记（append-only；旧服务端无此字段 → false）
                    boolean resumeAccepted = false;
                    if (buf.readableBytes() >= 1) {
                        try {
                            resumeAccepted = buf.readBoolean();
                        } catch (Exception ignored) {
                        }
                    }
                    return new HandshakeResponsePayload(protocolVersion, accepted, globalCompressionAccepted,
                            compactHeaderAccepted, dataplaneTail, worldSeed, seedGenTail, seedGenEnabled, true,
                            resumeAccepted);
                }
        );

        public HandshakeResponsePayload {
            dataplaneTail = dataplaneTail == null ? new byte[0] : dataplaneTail.clone();
            seedGenTail = seedGenTail == null ? new byte[0] : seedGenTail.clone();
        }

        public HandshakeResponsePayload(int protocolVersion, boolean accepted,
                                        boolean globalCompressionAccepted, boolean compactHeaderAccepted) {
            this(protocolVersion, accepted, globalCompressionAccepted, compactHeaderAccepted, new byte[0],
                    0L, new byte[0], false, false, false);
        }

        @Override
        public byte[] dataplaneTail() {
            return dataplaneTail.clone();
        }

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
     * 客户端缓存 Bloom 位图同步 Payload (C2S)
     */
    public record ClientBloomSyncPayload(byte[] data) implements CustomPacketPayload {

        public static final Type<ClientBloomSyncPayload> TYPE = new Type<>(ResourceLocationCompat.vanilla(HassiumChannels.CLIENT_BLOOM_SYNC_C2S));

        public static final StreamCodec<FriendlyByteBuf, ClientBloomSyncPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BYTE_ARRAY, ClientBloomSyncPayload::data,
                ClientBloomSyncPayload::new
        );

        @Override
        public Type<ClientBloomSyncPayload> type() {
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
     * SeedRef Payload (S2C，1.21.1+)
     */
    public record SeedRefPayload(byte[] data) implements CustomPacketPayload {

        public static final Type<SeedRefPayload> TYPE = new Type<>(
                ResourceLocationCompat.create(Constants.MOD_ID, "seed_ref_s2c")
        );

        public static final StreamCodec<FriendlyByteBuf, SeedRefPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BYTE_ARRAY, SeedRefPayload::data,
                SeedRefPayload::new
        );

        @Override
        public Type<SeedRefPayload> type() {
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

    /**
     * 光照增量通知 Payload (S2C)
     */
    public record LightDeltaPayload(byte[] data) implements CustomPacketPayload {

        public static final Type<LightDeltaPayload> TYPE = new Type<>(
                ResourceLocationCompat.create(Constants.MOD_ID, "light_delta_s2c")
        );

        public static final StreamCodec<FriendlyByteBuf, LightDeltaPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BYTE_ARRAY, LightDeltaPayload::data,
                LightDeltaPayload::new
        );

        @Override
        public Type<LightDeltaPayload> type() {
            return TYPE;
        }
    }

    public record DictionarySyncNeoPayload(byte[] data) implements CustomPacketPayload {
        public static final Type<DictionarySyncNeoPayload> TYPE = new Type<>(ResourceLocationCompat.vanilla(HassiumChannels.DICTIONARY_SYNC));
        public static final StreamCodec<FriendlyByteBuf, DictionarySyncNeoPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BYTE_ARRAY, DictionarySyncNeoPayload::data,
                DictionarySyncNeoPayload::new
        );
        @Override
        public Type<DictionarySyncNeoPayload> type() {
            return TYPE;
        }
    }

    public record IndexSyncNeoPayload(byte[] data) implements CustomPacketPayload {
        public static final Type<IndexSyncNeoPayload> TYPE = new Type<>(
                ResourceLocationCompat.create(Constants.MOD_ID, "index_sync_s2c")
        );
        public static final StreamCodec<FriendlyByteBuf, IndexSyncNeoPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BYTE_ARRAY, IndexSyncNeoPayload::data,
                IndexSyncNeoPayload::new
        );
        @Override
        public Type<IndexSyncNeoPayload> type() {
            return TYPE;
        }
    }

    public record CompressionReadyNeoPayload(boolean ready) implements CustomPacketPayload {
        public static final Type<CompressionReadyNeoPayload> TYPE = new Type<>(ResourceLocationCompat.vanilla(HassiumChannels.COMPRESSION_READY_C2S));
        public static final StreamCodec<FriendlyByteBuf, CompressionReadyNeoPayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> buf.writeBoolean(p.ready()),
                buf -> new CompressionReadyNeoPayload(buf.readBoolean())
        );
        @Override
        public Type<CompressionReadyNeoPayload> type() {
            return TYPE;
        }
    }

#endif

    // ========== 注册方法 ==========

    @Override
    public void registerChannels() {
        if (!HassiumConfigService.getInstance().isNetworkCompressionEnabled()) {
            LOGGER.warn("Hassium: master.enabled=false, skipping NeoForge channel registration");
            return;
        }
        LOGGER.debug("Hassium: NeoForge network channels will be registered via event");
#if MC_VER < MC_1_21_1
        registerSimpleChannelPackets();
#endif
    }

#if MC_VER < MC_1_21_1
    /**
     * 注册 SimpleChannel 数据包（1.20.1 forge）
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
        // 注意：Forge 1.20.1 的 consumer 参数是 Supplier<Context>

        // 0: 握手请求 C2S
        CHANNEL.registerMessage(packetId++, HandshakeWrapper.class,
                HandshakeWrapper::encode, HandshakeWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> {
                        ServerPlayer player = ctx.get().getSender();
                        if (player == null) return;
                        handleHandshakeSimple(player, msg);
                    });
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER));

        // 1: 握手响应 S2C
        CHANNEL.registerMessage(packetId++, HandshakeResponseWrapper.class,
                HandshakeResponseWrapper::encode, HandshakeResponseWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> handleHandshakeResponseSimple(msg));
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT));

        // 2: 压缩区块 S2C
        CHANNEL.registerMessage(packetId++, CompressedChunkWrapper.class,
                CompressedChunkWrapper::encode, CompressedChunkWrapper::decode,
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

        // 4: 区块数据请求 C2S
        CHANNEL.registerMessage(packetId++, ChunkDataRequestWrapper.class,
                ChunkDataRequestWrapper::encode, ChunkDataRequestWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> {
                        ServerPlayer player = ctx.get().getSender();
                        if (player == null) return;
                        try {
                            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
                            ChunkDataRequestC2SPacket request = ChunkDataRequestC2SPacket.decode(buf);
                            ServerChunkPushManager.getInstance()
                                    .handleClientChunkDataRequest(player, request);
                        } catch (Exception e) {
                            LOGGER.error("[SERVER] Failed to handle chunk data request", e);
                        }
                    });
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER));

        // 5: 区块哈希 S2C
        CHANNEL.registerMessage(packetId++, ChunkHashWrapper.class,
                ChunkHashWrapper::encode, ChunkHashWrapper::decode,
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

        // 5b: SeedRef S2C
        CHANNEL.registerMessage(packetId++, SeedRefWrapper.class,
                SeedRefWrapper::encode, SeedRefWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> {
                        try {
                            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
                            SeedRefS2CPacket packet = SeedRefS2CPacket.decode(buf);
                            ClientMetadataHandler.handleSeedRefPacket(packet);
                        } catch (Exception e) {
                            LOGGER.error("[CLIENT] Failed to handle seed ref", e);
                        }
                    });
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT));

        // 6: Section 哈希请求 C2S
        CHANNEL.registerMessage(packetId++, SectionHashRequestWrapper.class,
                SectionHashRequestWrapper::encode, SectionHashRequestWrapper::decode,
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

        // 7: Section Delta S2C
        CHANNEL.registerMessage(packetId++, SectionDeltaWrapper.class,
                SectionDeltaWrapper::encode, SectionDeltaWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> {
                        try {
                            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
                            SectionDeltaS2CPacket packet = SectionDeltaS2CPacket.decode(buf);
                            io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.submitDelta(packet);
                        } catch (Exception e) {
                            LOGGER.error("[CLIENT] Failed to handle section delta", e);
                        }
                    });
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT));

        // 8: BlockEntity 请求 C2S
        CHANNEL.registerMessage(packetId++, BlockEntityRequestWrapper.class,
                BlockEntityRequestWrapper::encode, BlockEntityRequestWrapper::decode,
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

        // 9: BlockEntity 数据 S2C
        CHANNEL.registerMessage(packetId++, BlockEntityDataWrapper.class,
                BlockEntityDataWrapper::encode, BlockEntityDataWrapper::decode,
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

        // 10: 光照增量更新 S2C
        CHANNEL.registerMessage(packetId++, LightDeltaWrapper.class,
                LightDeltaWrapper::encode, LightDeltaWrapper::decode,
                (msg, ctx) -> {
                    // 方案 A：客户端不消费 LightDelta，no-op
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT));

        // 11: 字典同步 S2C
        CHANNEL.registerMessage(packetId++, DictionarySyncWrapper.class,
                DictionarySyncWrapper::encode, DictionarySyncWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> handleDictionarySyncClient(msg.data()));
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT));

        // 12: 索引同步 S2C
        CHANNEL.registerMessage(packetId++, IndexSyncWrapper.class,
                IndexSyncWrapper::encode, IndexSyncWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> handleIndexSyncClient(msg.data()));
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT));

        // 13: CompressionReady C2S
        CHANNEL.registerMessage(packetId++, CompressionReadyWrapper.class,
                CompressionReadyWrapper::encode, CompressionReadyWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> {
                        ServerPlayer player = ctx.get().getSender();
                        if (player != null) {
                            handleCompressionReadyServer(player, msg.ready());
                        }
                    });
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER));

        // 14: 应用层聚合 S2C
        CHANNEL.registerMessage(packetId++, AggregationWrapper.class,
                AggregationWrapper::encode, AggregationWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> handleAggregationClient(msg.data()));
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT));

        // 15: 客户端缓存 Bloom 位图同步 C2S
        CHANNEL.registerMessage(packetId++, ClientBloomSyncWrapper.class,
                ClientBloomSyncWrapper::encode, ClientBloomSyncWrapper::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> handleClientBloomSync(msg, ctx.get().getSender()));
                    ctx.get().setPacketHandled(true);
                },
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER));

        HassiumAggregationManager.setSender((connection, buf) -> {
            try {
                if (connection.getPacketListener() instanceof net.minecraft.server.network.ServerGamePacketListenerImpl handler) {
                    byte[] data = new byte[buf.readableBytes()];
                    buf.readBytes(data);
                    CHANNEL.sendTo(new AggregationWrapper(data), handler.getPlayer().connection.connection,
                            NetworkDirection.PLAY_TO_CLIENT);
                } else {
                    LOGGER.error("Cannot send aggregation packet: connection has no player-side packet listener");
                }
            } catch (Exception e) {
                LOGGER.error("Hassium: Failed to send aggregation packet", e);
            } finally {
                buf.release();
            }
        });

        DictionaryManager.setPushCallback(dictionary -> {
            try {
                net.minecraft.server.MinecraftServer server = cachedServer;
                if (server != null) {
                    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                        sendDictionarySyncPacket(player);
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Failed to push dictionary to clients", e);
            }
        });

        LOGGER.info("Hassium: Registered {} SimpleChannel packets", packetId);
    }

    /**
     * SimpleChannel 段（MC_VER &lt; MC_1_21_1）：处理客户端缓存 Bloom 位图同步。
     */
    private void handleClientBloomSync(ClientBloomSyncWrapper msg, ServerPlayer player) {
        if (player == null) {
            LOGGER.warn("Hassium: Dropped client bloom sync (sender null — PLAY player not ready)");
            return;
        }
        try {
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(msg.data()));
            ClientBloomSyncPacket packet = ClientBloomSyncPacket.decode(buf);
            ServerChunkPushManager.getInstance().handleClientBloomSync(player, packet);
        } catch (Exception e) {
            LOGGER.error("[SERVER] Failed to handle client bloom sync", e);
        }
    }

    private void handleHandshakeSimple(ServerPlayer player, HandshakeWrapper msg) {
        // 客户端上报位置：校正 resync 视距中心（failover/重连时服务端玩家对象位置滞后）
        // T7 位置上报扩展：完整玩家状态（y/yaw/pitch/维度）
        HandshakeStateTail.C2S stateTail = msg.stateTail();
        PlayerStateReport reportedState = stateTail != null && stateTail.state() != null && stateTail.state().present()
                ? stateTail.state()
                : PlayerStateReport.fromXZ(msg.playerX(), msg.playerZ());
        ServerChunkPushManager.getInstance().setInitialPlayerPosition(player, reportedState);
        // T7 续流验票（验签 + epoch 防重放）→ 续流就绪 → 复用现有推送链
        boolean resumeAccepted = false;
        if (stateTail != null && stateTail.resumeRequested()) {
            ResumeTicketValidator.Verification resume =
                    ResumeTicketValidator.verifyRequest(player.getUUID(), stateTail.resumeTicket());
            if (resume.accepted()) {
                resumeAccepted = true;
                ServerChunkPushManager.getInstance().markPlayerResumeActive(player.getUUID(), resume.epoch());
                LOGGER.info("Hassium: [RESUME] {} ticket verified (epoch={}) — 续流就绪，跳过 login/维度初始化",
                        player.getName().getString(), resume.epoch());
            } else {
                LOGGER.warn("Hassium: [RESUME] {} ticket REJECTED (签名无效/epoch 重放) — 回退完整握手",
                        player.getName().getString());
            }
        }
        // SeedGen 能力记录
        ServerChunkPushManager.getInstance().setPlayerSeedGenSupported(player.getUUID(), msg.seedGenSupported());
        ServerChunkPushManager.getInstance().setPlayerLightComputeSupported(player.getUUID(), msg.lightComputeSupported());
        PlayerCompressionTracker.enableCompression(player);
        boolean serverSupportsGlobalCompression = HassiumConfigService.getInstance().isGlobalPacketCompressionEnabled();
        boolean useGlobalCompression = serverSupportsGlobalCompression && msg.globalPacketCompressionSupported();
        boolean serverSupportsCompactHeader = HassiumConfigService.getInstance().isCompactHeaderEnabled();
        boolean useCompactHeader = serverSupportsCompactHeader && msg.compactHeaderSupported();

        boolean accepted = true;
        // SeedGen 尾部：仅当客户端上报能力时用新格式（带长度前缀），旧客户端保持旧格式
        long worldSeed = 0L;
        byte[] seedGenTail = new byte[0];
        boolean seedGenEnabled = false;
        if (msg.seedGenSupported()) {
            try {
                net.minecraft.server.level.ServerLevel seedLevel = player.serverLevel();
                seedGenEnabled = HassiumConfigService.getInstance().isSeedGenEnabled();
                worldSeed = SeedGenTail.handshakeWorldSeed(seedLevel, seedGenEnabled);
                io.netty.buffer.ByteBuf sb = io.netty.buffer.Unpooled.buffer();
                SeedGenTail.writeS2C(new FriendlyByteBuf(sb), seedLevel, seedGenEnabled);
                seedGenTail = new byte[sb.readableBytes()];
                sb.readBytes(seedGenTail);
                sb.release();
            } catch (Throwable e) {
                LOGGER.warn("Hassium: Failed to create NeoForge seedGen handshake tail", e);
            }
        }
        var advertisedTail = createDataPlaneHandshakeTail(player, accepted, msg.dataplaneCapabilities());
        io.netty.buffer.ByteBuf tailBuffer = io.netty.buffer.Unpooled.buffer();
        io.github.limuqy.mc.hassium.network.dataplane.UdpDataPlaneHandshakeTail.writeS2C(tailBuffer, advertisedTail);
        byte[] dataplaneTail = new byte[tailBuffer.readableBytes()];
        tailBuffer.readBytes(dataplaneTail);
        LOGGER.info("Hassium: Server dataplane handshake tail for {}: udp={}, controlFailover={}, groups={}, bytes={}",
                player.getName().getString(), advertisedTail.hasUdpDataplane(), advertisedTail.hasControlFailover(),
                advertisedTail.udpListenerGroups().size(), dataplaneTail.length);
        HandshakeResponseWrapper response = new HandshakeResponseWrapper(
                Constants.CURRENT_PROTOCOL_VERSION,
                accepted,
                useGlobalCompression,
                useCompactHeader,
                dataplaneTail,
                worldSeed,
                seedGenTail,
                seedGenEnabled,
                msg.seedGenSupported(),
                resumeAccepted
        );
        if (useGlobalCompression) {
            DictionaryManager.init();
            IndexSyncManager.getInstance().initializeServerIndex();
            Connection connection = getPlayerConnection(player);
            Channel channel = connection != null ? getConnectionChannel(connection) : null;
            if (channel != null) {
                // 先暂停出站压缩，再发 HandshakeResponse，避免响应后的包仍走 Zlib
                ZstdPipelineSwitcher.pauseOutboundCompression(channel);
            }
        }
        CHANNEL.sendTo(response, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
        LOGGER.info("Hassium: Server handshake for {}: accepted={}, globalCompression={}, compactHeader={}",
                player.getName().getString(), accepted, useGlobalCompression, useCompactHeader);
        // globalCompression=false 时不会走 ZSTD ready→Dict/Index 路径，直接补发 chunkHash
        if (accepted && !useGlobalCompression) {
            ServerChunkPushManager.getInstance().resyncTrackedChunks(player);
        }
    }

    private void handleHandshakeResponseSimple(HandshakeResponseWrapper msg) {

        LOGGER.info("Hassium: Client handshake response: accepted={}, globalCompression={}, compactHeader={}, resumeAccepted={}",
                msg.accepted(), msg.globalCompressionAccepted(), msg.compactHeaderAccepted(), msg.resumeAccepted());
        if (msg.resumeAccepted()) {
            LOGGER.info("Hassium: [RESUME] Server accepted resume — 续流就绪，网关可跳过 login/维度初始化");
        }
        // SeedGen 信息（新格式下服务端保证带尾部；旧服务端默认不启用）
        if (msg.seedGenTail().length > 0) {
            try {
                io.netty.buffer.ByteBuf sb = io.netty.buffer.Unpooled.wrappedBuffer(msg.seedGenTail());
                FriendlyByteBuf seedBuf = new FriendlyByteBuf(sb);
                seedBuf.readLong(); // 布局内 worldSeed（与 msg.worldSeed() 相同，跳过）
                long stemLen = seedBuf.readVarInt();
                byte[] stemNbt = null;
                if (stemLen > 0 && stemLen <= seedBuf.readableBytes()) {
                    stemNbt = new byte[(int) stemLen];
                    seedBuf.readBytes(stemNbt);
                }
                boolean seedGenEnabled = seedBuf.readableBytes() >= 1 && seedBuf.readBoolean();
                ClientChunkPipeline.getInstance().setServerSeedInfo(msg.worldSeed(), stemNbt, seedGenEnabled);
                sb.release();
            } catch (Throwable e) {
                LOGGER.debug("Hassium: failed to decode SeedGen tail (legacy server?)", e);
            }
        }
        if (msg.accepted() && msg.globalCompressionAccepted()) {
            tryInstallClientZstdPipeline();
        }
        if (msg.accepted()) {
            startUdpFromHandshakeTail(msg.dataplaneTail());
        }
    }

#else
    /**
     * 注册所有 Payload (1.21.1+)
     */
    private static void handlePreHandshake(PreHandshakePayload payload, net.neoforged.neoforge.network.handling.IPayloadContext context) {
        // 配置阶段无 ServerPlayer：按 listener owner（GameProfile）UUID 标记，
        // ServerPlayer 创建时（MixinServerPlayer TAIL）自动提升为压缩启用。
        // 完整协商（ZSTD/聚合/数据面/位置）仍在 Play 阶段 handleHandshake 完成。
        UUID playerId = null;
        if (context.listener() instanceof net.minecraft.server.network.ServerConfigurationPacketListenerImpl configListener) {
            playerId = io.github.limuqy.mc.hassium.compat.PlayerCompat.getProfileId(configListener.getOwner());
        }
        PreHandshakeProtocol.handlePreHandshake(playerId, payload);
    }

    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        // gateway_info S2C 必须无条件注册（先于下方守卫）：守卫任一路径关闭
        // （net/master 全关，或 NetworkCapability 能力降级经 setNetworkCompressionEnabled(false)
        // 强制关注册）时，ServerGatewayInfoSender.canSend（dedicated + master.enabled）
        // 与注册状态可能脱钩——NeoForge checkPacket 对未注册 S2C payload 直接抛异常炸 tick。
        // 服务端不发送时注册无副作用。vanilla 通道直发（tick 内 drainPending）；
        // 与 fabric FabricPayloadRegistry 同模式：RawCustomPayload 字节流 codec，
        // 客户端消费走同一 common 链（GatewayInfoCodec.decode → NetworkCore.onGatewayInfo）。
        var registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToClient(
                io.github.limuqy.mc.hassium.compat.PacketPayloadCompat.payloadType(
                        ResourceLocationCompat.create(io.github.limuqy.mc.hassium.network.HassiumPacketIds.GATEWAY_INFO_S2C)),
                io.github.limuqy.mc.hassium.compat.PacketPayloadCompat.rawPayloadCodec(
                        ResourceLocationCompat.create(io.github.limuqy.mc.hassium.network.HassiumPacketIds.GATEWAY_INFO_S2C)),
                (payload, context) -> context.enqueueWork(() -> {
                    try {
                        GatewayInfoCodec.GatewayInfo info =
                                GatewayInfoCodec.decode(payload.data());
                        io.github.limuqy.mc.hassium.network.core.NetworkCore.getInstance()
                                .onGatewayInfo(info);
                    } catch (Exception e) {
                        LOGGER.error("[CLIENT] Failed to handle gateway_info", e);
                    }
                })
        );

        if (!HassiumConfigService.getInstance().isNetworkCompressionEnabled()) {
            LOGGER.warn("Hassium: network core disabled, skipping remaining NeoForge Payload registration");
            return;
        }
        LOGGER.debug("Hassium: Registering NeoForge Payload handlers");


        // 注册预握手 (C2S, 配置阶段)：提前标记 Hassium 客户端，
        // ServerPlayer 创建时自动提升压缩 → 进服第一圈 sendChunk 全走 Hassium 链
        registrar.configurationToServer(
                PreHandshakePayload.TYPE,
                PreHandshakePayload.STREAM_CODEC,
                (payload, context) -> handlePreHandshake(payload, context)
        );


        // 注册握手请求 (C2S)
        registrar.playToServer(
                HandshakePayload.TYPE,
                HandshakePayload.STREAM_CODEC,
                NeoForgeNetworkManager::handleHandshake
        );

        // 注册区块数据请求 (C2S)
        registrar.playToServer(
                ChunkDataRequestPayload.TYPE,
                ChunkDataRequestPayload.STREAM_CODEC,
                NeoForgeNetworkManager::handleChunkDataRequest
        );

        // 注册客户端缓存 Bloom 位图同步 (C2S)
        registrar.playToServer(
                ClientBloomSyncPayload.TYPE,
                ClientBloomSyncPayload.STREAM_CODEC,
                NeoForgeNetworkManager::handleClientBloomSync
        );

        // 注册 Section 哈希请求 (C2S)
        registrar.playToServer(
                SectionHashRequestPayload.TYPE,
                SectionHashRequestPayload.STREAM_CODEC,
                NeoForgeNetworkManager::handleSectionHashRequest
        );

        // 注册 BlockEntity 请求 (C2S)
        registrar.playToServer(
                BlockEntityRequestPayload.TYPE,
                BlockEntityRequestPayload.STREAM_CODEC,
                NeoForgeNetworkManager::handleBlockEntityRequest
        );

        registrar.playToServer(
                CompressionReadyNeoPayload.TYPE,
                CompressionReadyNeoPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer player) {
                        handleCompressionReadyServer(player, payload.ready());
                    }
                })
        );

        // ===== S2C（客户端处理；与服务端发送方向一一对应）=====
        // review-fix: T11-M1：此前只注册 C2S，9 个 S2C payload 客户端零注册 → 直连回退路径功能性死亡

        // 握手响应 S2C
        registrar.playToClient(HandshakeResponsePayload.TYPE, HandshakeResponsePayload.STREAM_CODEC,
                NeoForgeNetworkManager::handleHandshakeResponseS2C);

        // 压缩区块 S2C
        registrar.playToClient(CompressedChunkPayload.TYPE, CompressedChunkPayload.STREAM_CODEC,
                NeoForgeNetworkManager::handleCompressedChunkS2C);

        // 区块哈希 S2C
        registrar.playToClient(ChunkHashPayload.TYPE, ChunkHashPayload.STREAM_CODEC,
                NeoForgeNetworkManager::handleChunkHashS2C);

        // SeedRef S2C
        registrar.playToClient(SeedRefPayload.TYPE, SeedRefPayload.STREAM_CODEC,
                NeoForgeNetworkManager::handleSeedRefS2C);

        // SectionDelta S2C
        registrar.playToClient(SectionDeltaPayload.TYPE, SectionDeltaPayload.STREAM_CODEC,
                NeoForgeNetworkManager::handleSectionDeltaS2C);

        // BlockEntityData S2C
        registrar.playToClient(BlockEntityDataPayload.TYPE, BlockEntityDataPayload.STREAM_CODEC,
                NeoForgeNetworkManager::handleBlockEntityDataS2C);

        // LightDelta S2C（方案 A：客户端不消费，no-op 标记已处理）
        registrar.playToClient(LightDeltaPayload.TYPE, LightDeltaPayload.STREAM_CODEC,
                (payload, ctx) -> {
                });

        // 字典同步 S2C
        registrar.playToClient(DictionarySyncNeoPayload.TYPE, DictionarySyncNeoPayload.STREAM_CODEC,
                NeoForgeNetworkManager::handleDictionarySyncS2C);

        // 索引同步 S2C
        registrar.playToClient(IndexSyncNeoPayload.TYPE, IndexSyncNeoPayload.STREAM_CODEC,
                NeoForgeNetworkManager::handleIndexSyncS2C);

        LOGGER.info("Hassium: Registered all NeoForge payload handlers");
    }

    private static void handleHandshake(HandshakePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                // 客户端上报位置：校正 resync 视距中心（failover/重连时服务端玩家对象位置滞后）
                // T7 位置上报扩展：完整玩家状态（y/yaw/pitch/维度）
                HandshakeStateTail.C2S stateTail = payload.stateTail();
                PlayerStateReport reportedState = stateTail != null && stateTail.state() != null && stateTail.state().present()
                        ? stateTail.state()
                        : PlayerStateReport.fromXZ(payload.playerX(), payload.playerZ());
                ServerChunkPushManager.getInstance().setInitialPlayerPosition(player, reportedState);
                // T7 续流验票（验签 + epoch 防重放）→ 续流就绪 → 复用现有推送链
                boolean resumeAccepted = false;
                if (stateTail != null && stateTail.resumeRequested()) {
                    ResumeTicketValidator.Verification resume =
                            ResumeTicketValidator.verifyRequest(player.getUUID(), stateTail.resumeTicket());
                    if (resume.accepted()) {
                        resumeAccepted = true;
                        ServerChunkPushManager.getInstance().markPlayerResumeActive(player.getUUID(), resume.epoch());
                        LOGGER.info("Hassium: [RESUME] {} ticket verified (epoch={}) — 续流就绪，跳过 login/维度初始化",
                                player.getName().getString(), resume.epoch());
                    } else {
                        LOGGER.warn("Hassium: [RESUME] {} ticket REJECTED (签名无效/epoch 重放) — 回退完整握手",
                                player.getName().getString());
                    }
                }
                // SeedGen 能力记录
                ServerChunkPushManager.getInstance().setPlayerSeedGenSupported(player.getUUID(), payload.seedGenSupported());
                ServerChunkPushManager.getInstance().setPlayerLightComputeSupported(player.getUUID(), payload.lightComputeSupported());
                PlayerCompressionTracker.enableCompression(player);
                boolean useGlobalCompression = HassiumConfigService.getInstance().isGlobalPacketCompressionEnabled()
                        && payload.globalPacketCompressionSupported();
                boolean useCompactHeader = HassiumConfigService.getInstance().isCompactHeaderEnabled()
                        && payload.compactHeaderSupported();
                boolean accepted = true;

                // SeedGen 尾部：仅当客户端上报能力时用新格式（带长度前缀），旧客户端保持旧格式
                long worldSeed = 0L;
                byte[] seedGenTail = new byte[0];
                boolean seedGenEnabled = false;
                if (payload.seedGenSupported()) {
                    try {
                        net.minecraft.server.level.ServerLevel seedLevel =
                                io.github.limuqy.mc.hassium.compat.PlayerCompat.getServerLevel(player);
                        seedGenEnabled = HassiumConfigService.getInstance().isSeedGenEnabled();
                        worldSeed = SeedGenTail.handshakeWorldSeed(seedLevel, seedGenEnabled);
                        io.netty.buffer.ByteBuf sb = io.netty.buffer.Unpooled.buffer();
                        SeedGenTail.writeS2C(new FriendlyByteBuf(sb), seedLevel, seedGenEnabled);
                        seedGenTail = new byte[sb.readableBytes()];
                        sb.readBytes(seedGenTail);
                        sb.release();
                    } catch (Throwable e) {
                        LOGGER.warn("Hassium: Failed to create NeoForge seedGen handshake tail", e);
                    }
                }
                io.netty.buffer.ByteBuf tailBuffer = io.netty.buffer.Unpooled.buffer();
                io.github.limuqy.mc.hassium.network.dataplane.UdpDataPlaneHandshakeTail.writeS2C(tailBuffer,
                        createDataPlaneHandshakeTail(player, accepted, payload.dataplaneCapabilities()));
                byte[] dataplaneTail = new byte[tailBuffer.readableBytes()];
                tailBuffer.readBytes(dataplaneTail);
                HandshakeResponsePayload response = new HandshakeResponsePayload(
                        Constants.CURRENT_PROTOCOL_VERSION, accepted, useGlobalCompression, useCompactHeader,
                        dataplaneTail, worldSeed, seedGenTail, seedGenEnabled, payload.seedGenSupported(),
                        resumeAccepted);
                // 先暂停出站压缩，再发 HandshakeResponse，避免响应后的包仍走 Zlib
                if (useGlobalCompression) {
                    DictionaryManager.init();
                    IndexSyncManager.getInstance().initializeServerIndex();
                    Connection connection = getPlayerConnection(player);
                    Channel channel = connection != null ? getConnectionChannel(connection) : null;
                    if (channel != null) {
                        ZstdPipelineSwitcher.pauseOutboundCompression(channel);
                    }
                }
                player.connection.send(response);
                LOGGER.info("Hassium: Server handshake for {}: accepted={}, globalCompression={}, compactHeader={}",
                        player.getName().getString(), accepted, useGlobalCompression, useCompactHeader);
                // globalCompression=false 时不会走 ZSTD ready→Dict/Index 路径，直接补发 chunkHash
                if (accepted && !useGlobalCompression) {
                    ServerChunkPushManager.getInstance().resyncTrackedChunks(player);
                }
            }
        });
    }

    private static void handleChunkDataRequest(ChunkDataRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                if (context.player() instanceof ServerPlayer player) {
                    FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(payload.data()));
                    ChunkDataRequestC2SPacket request = ChunkDataRequestC2SPacket.decode(buf);
                    ServerChunkPushManager.getInstance()
                            .handleClientChunkDataRequest(player, request);
                }
            } catch (Exception e) {
                LOGGER.error("[SERVER] Failed to handle chunk data request", e);
            }
        });
    }

    private static void handleClientBloomSync(ClientBloomSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                if (context.player() instanceof ServerPlayer player) {
                    FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(payload.data()));
                    ClientBloomSyncPacket packet = ClientBloomSyncPacket.decode(buf);
                    ServerChunkPushManager.getInstance().handleClientBloomSync(player, packet);
                }
            } catch (Exception e) {
                LOGGER.error("[SERVER] Failed to handle client bloom sync", e);
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

    // ===== S2C 客户端处理（1.21.1+；处理逻辑对齐 SimpleChannel 注册块）=====

    private static void handleHandshakeResponseS2C(HandshakeResponsePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            LOGGER.info("Hassium: Client handshake response: accepted={}, globalCompression={}, compactHeader={}, resumeAccepted={}",
                    payload.accepted(), payload.globalCompressionAccepted(), payload.compactHeaderAccepted(), payload.resumeAccepted());
            if (payload.resumeAccepted()) {
                LOGGER.info("Hassium: [RESUME] Server accepted resume — 续流就绪，网关可跳过 login/维度初始化");
            }
            // SeedGen 信息（新格式下服务端保证带尾部；旧服务端默认不启用）
            if (payload.seedGenTail().length > 0) {
                try {
                    io.netty.buffer.ByteBuf sb = io.netty.buffer.Unpooled.wrappedBuffer(payload.seedGenTail());
                    FriendlyByteBuf seedBuf = new FriendlyByteBuf(sb);
                    seedBuf.readLong(); // 布局内 worldSeed（与 payload.worldSeed() 相同，跳过）
                    long stemLen = seedBuf.readVarInt();
                    byte[] stemNbt = null;
                    if (stemLen > 0 && stemLen <= seedBuf.readableBytes()) {
                        stemNbt = new byte[(int) stemLen];
                        seedBuf.readBytes(stemNbt);
                    }
                    boolean seedGenEnabled = seedBuf.readableBytes() >= 1 && seedBuf.readBoolean();
                    ClientChunkPipeline.getInstance().setServerSeedInfo(payload.worldSeed(), stemNbt, seedGenEnabled);
                    sb.release();
                } catch (Throwable e) {
                    LOGGER.debug("Hassium: failed to decode SeedGen tail (legacy server?)", e);
                }
            }
            if (payload.accepted() && payload.globalCompressionAccepted()) {
                tryInstallClientZstdPipeline();
            }
            if (payload.accepted()) {
                startUdpFromHandshakeTail(payload.dataplaneTail());
            }
        });
    }

    private static void handleCompressedChunkS2C(CompressedChunkPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                ClientChunkHandler.handleCompressedChunk(payload.data());
            } catch (Exception e) {
                LOGGER.error("[CLIENT] Failed to handle compressed chunk", e);
            }
        });
    }

    private static void handleChunkHashS2C(ChunkHashPayload payload, IPayloadContext context) {
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

    private static void handleSeedRefS2C(SeedRefPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(payload.data()));
                SeedRefS2CPacket packet = SeedRefS2CPacket.decode(buf);
                ClientMetadataHandler.handleSeedRefPacket(packet);
            } catch (Exception e) {
                LOGGER.error("[CLIENT] Failed to handle seed ref", e);
            }
        });
    }

    private static void handleSectionDeltaS2C(SectionDeltaPayload payload, IPayloadContext context) {
        // 与 SimpleChannel 注册块一致：submitDelta 自带线程封送，不再包 enqueueWork
        io.github.limuqy.mc.hassium.network.seedgen.ShadowLightCompute.submitDelta(
                SectionDeltaS2CPacket.decode(new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(payload.data()))));
    }

    private static void handleBlockEntityDataS2C(BlockEntityDataPayload payload, IPayloadContext context) {
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

    private static void handleDictionarySyncS2C(DictionarySyncNeoPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> handleDictionarySyncClient(payload.data()));
    }

    private static void handleIndexSyncS2C(IndexSyncNeoPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> handleIndexSyncClient(payload.data()));
    }
#endif

    // ========== 发送方法实现 ==========

    @Override
    public void sendChunkDataRequest(FriendlyByteBuf buf) {
#if MC_VER < MC_1_21_1
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

#if MC_VER >= MC_1_21_1
    /** NeoForge payload 发送必须经服务端主线程，避免异步推送批次丢失。 */
    private static void sendServerPayload(ServerPlayer player, CustomPacketPayload payload) {
        player.getServer().execute(() -> player.connection.send(payload));
    }
#endif


    @Override
    public void sendChunkHashPacket(ServerPlayer player, FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
#if MC_VER < MC_1_21_1
        CHANNEL.sendTo(new ChunkHashWrapper(data), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
#else
        ChunkHashPayload payload = new ChunkHashPayload(data);
        sendServerPayload(player, payload);
        LOGGER.debug("Hassium: Sent chunk hash packet to {}", player.getName().getString());
#endif
    }

    @Override
    public void sendSeedRef(ServerPlayer player, FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
#if MC_VER < MC_1_21_1
        CHANNEL.sendTo(new SeedRefWrapper(data), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
#else
        SeedRefPayload payload = new SeedRefPayload(data);
        sendServerPayload(player, payload);
        LOGGER.debug("Hassium: Sent seed ref to {}", player.getName().getString());
#endif
    }

    @Override
    public void sendSectionHashRequest(FriendlyByteBuf buf) {
#if MC_VER < MC_1_21_1
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
#if MC_VER < MC_1_21_1
        CHANNEL.sendTo(new SectionDeltaWrapper(data), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
#else
        SectionDeltaPayload payload = new SectionDeltaPayload(data);
        sendServerPayload(player, payload);
        LOGGER.debug("Hassium: Sent section delta packet to {}", player.getName().getString());
#endif
    }

    @Override
    public void sendBlockEntityRequest(FriendlyByteBuf buf) {
#if MC_VER < MC_1_21_1
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
#if MC_VER < MC_1_21_1
        CHANNEL.sendTo(new BlockEntityDataWrapper(data), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
#else
        BlockEntityDataPayload payload = new BlockEntityDataPayload(data);
        sendServerPayload(player, payload);
        LOGGER.debug("Hassium: Sent block entity data packet to {}", player.getName().getString());
#endif
    }

    @Override
    public void sendLightDeltaPacket(ServerPlayer player, FriendlyByteBuf buf) {
        // 三端一致收口（2026-08-23 裁决）：vanilla 通道 LightDelta 三端客户端均不消费
        // （legacy Wrapper / modern Payload 接收端本就是 no-op），唯一消费在网关帧链路；
        // 本实现仅消费 buf 所有权，不再发 payload（两段注册保留，最小 diff）。
        buf.release();
    }

    @Override
    public void sendClientBloomSync(FriendlyByteBuf buf) {
#if MC_VER < MC_1_21_1
        if (net.minecraft.client.Minecraft.getInstance().getConnection() != null) {
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            buf.release();
            CHANNEL.sendToServer(new ClientBloomSyncWrapper(data));
        } else {
            buf.release();
        }
#else
        if (net.minecraft.client.Minecraft.getInstance().getConnection() != null) {
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            buf.release();
            ClientBloomSyncPayload payload = new ClientBloomSyncPayload(data);
            // NeoForge 1.21+ 的 Connection.send(CustomPacketPayload) 需在客户端主线程排队；
            // Bloom 在影子端线程连续发送三维度时，工作线程直调会导致前两帧未可靠进入
            // vanilla Connection，服务端直到 R2 才收到 overworld Bloom。
            net.minecraft.client.Minecraft.getInstance().execute(() ->
                    net.minecraft.client.Minecraft.getInstance().getConnection().send(payload));
            LOGGER.debug("Hassium: Sent client bloom sync");
        } else {
            buf.release();
        }
#endif
    }

    /**
     * 发送已编码的压缩区块负载到指定玩家（payload 由调用方 encode 一次；review-fix: T11-19）
     */
    public static void sendCompressedChunk(ServerPlayer player, byte[] data) {
        try {
            LOGGER.debug("[SEND_CHUNK] Sending compressed chunk to player {} (size={})",
                    player.getName().getString(), data.length);


#if MC_VER < MC_1_21_1
            CHANNEL.sendTo(new CompressedChunkWrapper(data), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
#else
            CompressedChunkPayload payload = new CompressedChunkPayload(data);
            sendServerPayload(player, payload);
#endif
            LOGGER.debug("[SEND_CHUNK] Successfully sent chunk to {}",
                    player.getName().getString());
        } catch (Exception e) {
            LOGGER.error("[SEND_CHUNK] Failed to send chunk to {}", player.getName().getString(), e);
        }
    }

    private static void sendDictionarySyncPacket(ServerPlayer player) {
        try {
            byte[] aggregationDict = DictionaryManager.getAggregationDict();
            if (aggregationDict == null) {
                aggregationDict = new byte[0];
            }
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            new DictionarySyncPayload(aggregationDict, false).encode(buf);
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            buf.release();
#if MC_VER < MC_1_21_1
            CHANNEL.sendTo(new DictionarySyncWrapper(data), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
#else
            player.connection.send(new DictionarySyncNeoPayload(data));
#endif
            LOGGER.debug("Hassium: Sent dictionary sync ({} bytes) to {}", aggregationDict.length, player.getName().getString());
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to send dictionary sync packet", e);
        }
    }

    private static void sendIndexSyncPacket(ServerPlayer player) {
        try {
            IndexSyncManager indexSyncManager = IndexSyncManager.getInstance();
            indexSyncManager.initializeServerIndex();
            IndexSyncPacket syncPacket = indexSyncManager.createSyncPacket();
            byte[] encoded = syncPacket.encode();
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            buf.writeVarInt(encoded.length);
            buf.writeBytes(encoded);
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            buf.release();
#if MC_VER < MC_1_21_1
            CHANNEL.sendTo(new IndexSyncWrapper(data), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
#else
            player.connection.send(new IndexSyncNeoPayload(data));
#endif
            LOGGER.debug("Hassium: Sent index sync to {}", player.getName().getString());
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to send index sync packet", e);
        }
    }

    private static void handleDictionarySyncClient(byte[] data) {
        try {
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(data));
            DictionarySyncPayload payload = DictionarySyncPayload.decode(buf);
            DictionaryManager.setAggregationDict(payload.dictionary());
            LOGGER.debug("Hassium: Received aggregation dictionary ({} bytes)",
                    payload.dictionary() != null ? payload.dictionary().length : 0);
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to handle dictionary sync", e);
        }
    }

    private static void handleIndexSyncClient(byte[] data) {
        try {
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(data));
            int dataLength = buf.readVarInt();
            byte[] packetData = new byte[dataLength];
            buf.readBytes(packetData);
            IndexSyncPacket syncPacket = IndexSyncPacket.decode(packetData);
            IndexSyncManager indexSyncManager = IndexSyncManager.getInstance();
            NamespaceIndexManager clientIndexManager = indexSyncManager.handleSyncPacket("client", syncPacket);

            var conn = net.minecraft.client.Minecraft.getInstance().getConnection();
            if (conn != null) {
                Connection connection = conn.getConnection();
                HassiumConnectionRegistry.markEnabled(connection);
                HassiumAggregationManager.init();
                // 服务端已装 ZSTD（能发来 IndexSync）：恢复客户端出站压缩阈值
                //（装 ZSTD 时暂停，见 tryInstallClientZstdPipeline；对齐 forge）
                io.netty.channel.Channel channel = getConnectionChannel(connection);
                if (channel != null) {
                    int threshold = HassiumConfigService.getInstance().getGlobalCompressionThreshold();
                    ZstdPipelineSwitcher.setOutboundCompressionThreshold(channel, threshold);
                }
                sendCompressionReadyToServer();
            }
            LOGGER.debug("Hassium: Received index sync ({} types), sent compression ready",
                    clientIndexManager.size());
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to handle index sync", e);
        }
    }

    private static void handleAggregationClient(byte[] data) {
        FriendlyByteBuf packetBuf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(data));
        try {
            var clientConn = net.minecraft.client.Minecraft.getInstance().getConnection();
            if (clientConn == null) {
                LOGGER.error("Received aggregation packet but no client connection");
                return;
            }
            NamespaceIndexManager indexManager = IndexSyncManager.getInstance().getClientIndexManager();
            if (indexManager == null) {
                LOGGER.error("Received aggregation packet but client index manager not initialized");
                return;
            }
            HassiumAggregationPacket.decode(packetBuf, indexManager).handle(clientConn.getConnection());
        } catch (Throwable e) { // review-fix: T13-C1（decode 校验抛 IllegalArgumentException/Error 均须收敛，防 OOM 后链路悬挂）
            LOGGER.error("Failed to handle aggregation packet", e);
        } finally {
            packetBuf.release();
        }
    }

    private static void sendCompressionReadyToServer() {
        try {
#if MC_VER < MC_1_21_1
            CHANNEL.sendToServer(new CompressionReadyWrapper(true));
#else
            var connection = net.minecraft.client.Minecraft.getInstance().getConnection();
            if (connection != null) {
                connection.send(new CompressionReadyNeoPayload(true));
            }
#endif
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to send compression ready", e);
        }
    }

    private static void handleCompressionReadyServer(ServerPlayer player, boolean ready) {
        if (!ready) {
            return;
        }
        Connection connection = getPlayerConnection(player);
        Channel channel = connection != null ? getConnectionChannel(connection) : null;
        // 第一次 ready：客户端已装 ZSTD → 服务端切管线并同步 Dict/Index
        if (channel != null && !ZstdPipelineSwitcher.isZstdInstalled(channel)) {
            installServerZstdAfterClientReady(player, connection, channel);
            return;
        }
        // 第二次 ready：IndexSync 已处理 → 启用聚合
        if (connection != null) {
            HassiumConnectionRegistry.markEnabled(connection);
            HassiumAggregationManager.flushConnection(connection);
            LOGGER.debug("Hassium: Marked connection ENABLED for {}", player.getName().getString());
        }
    }

    private static void schedulePendingTimeout(Connection connection, String playerName) {
        PENDING_TIMEOUT_SCHEDULER.schedule(() -> {
            if (HassiumConnectionRegistry.tryDemoteFromPending(connection)) {
                HassiumAggregationManager.discardConnection(connection);
                LOGGER.warn("Hassium: Ack timeout for {}, disabling aggregation", playerName);
            }
        }, 5, java.util.concurrent.TimeUnit.SECONDS);
    }
}
