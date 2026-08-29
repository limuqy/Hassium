package io.github.limuqy.mc.hassium.server;

import io.github.limuqy.mc.hassium.compat.PacketCodecCompat;
import io.github.limuqy.mc.hassium.compat.LevelCompat;
import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;
import io.github.limuqy.mc.hassium.server.GatewayConnectionAccessor;
import io.github.limuqy.mc.hassium.network.HandshakeStateTail;
import io.github.limuqy.mc.hassium.network.PlayerCompressionTracker;
import io.github.limuqy.mc.hassium.network.PlayerStateReport;
import io.github.limuqy.mc.hassium.network.ServerChunkPushManager;
import io.github.limuqy.mc.hassium.network.core.GatewayPacketCodec;
import io.github.limuqy.mc.hassium.network.core.outbound.ControlFrameCodec;
import io.github.limuqy.mc.hassium.network.gateway.C2SPayloadSink;
import io.github.limuqy.mc.hassium.network.gateway.GatewayChannel;
import io.github.limuqy.mc.hassium.network.gateway.GatewayPlayerSession;
import io.github.limuqy.mc.hassium.network.gateway.GatewayServer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.PacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 主控侧网关玩家桥（T12）：把网关帧连接（{@link GatewayChannel}）桥接进原版服务器状态机。
 *
 * <p><b>物化形态 = Connection 桥</b>（T0 事实表 handler 直调/等价路径选型）：
 * 为每个需要物化的玩家伪造一个 {@link Connection}（真实 {@code PacketFlow.SERVERBOUND}
 * 实例 + EmbeddedChannel，管道装 dummy splitter/decoder/prepender/encoder——vanilla
 * {@code setEncryptionKey/setupCompression/setupInboundProtocol} 按名操作管道不炸；
 * 不装 packet_handler 防 setupInboundProtocol 重名）。S2C 经
 * {@link MixinConnectionGatewayServer} 在 {@code Connection.sendPacket} HEAD 拦截 →
 * {@link #routeS2C}：查桥状态表，网关玩家编码为 kind=0 帧（登录阶段 LOGIN_S2C /
 * play 阶段 PACKET_S2C，协议按包类判定）；muted（续流物化窗口）时吞包。vanilla 侧
 * 管线零流量（拦截点即出口），C2S 走 {@link C2SPayloadSink} 注入（decode →
 * {@code packet.handle(listener)}，主线程执行）。
 *
 * <p><b>登录路径</b>（1.20.1 全链）：LOGIN_C2S 帧 → 解码 LOGIN SERVERBOUND →
 * {@code packet.handle(ServerLoginPacketListenerImpl)}；监听器 tick 由
 * {@link #tick} 泵（MixinMinecraftServer.onServerTick 调用，VERIFYING→压缩/finished
 * 推进靠泵）。物化检测：tick 后 {@code playerList.getPlayer(profileId)} 非空 →
 * attachPlayer + setC2SSink。1.20.2+ vanilla 在 LoginAcknowledged 后自动切入 config
 * 阶段（listener 自动切换 ServerConfigurationPacketListenerImpl）——T10 起 CONFIG_C2S
 * 帧按桥当前监听器阶段解码注入（配置协议），配置完成（handleConfigurationFinished →
 * placeNewPlayer）后走同一物化检测；桥 S2C 亦按监听器阶段编解码（config 协议包 →
 * CONFIG_S2C 帧）。标准流程（握手身份附着）下登录桥不参与：vanilla 登录/config 完成
 * 后会话经 attach 钩子直挂 vanilla 玩家 C2S sink（见 materializeResumeOnMain）。
 *
 * <p><b>续流路径</b>（全锚点）：会话登记（attach hook）→ {@link #materializeResumePlayer}
 * 主线程执行：玩家名经 usercache/nameToIdCache 解析（兜底占位名 Hassium#&lt;uuid8&gt;），
 * 磁盘 playerdata 数据加载（A6，先于 placeNewPlayer、muted 窗口内）→
 * 按上报位置/维度创建 ServerPlayer →
 * muted placeNewPlayer（join S2C 风暴吞掉——续流客户端已持有世界，收到 Login 包会
 * 重载；server 侧注册/tracking/scoreboard 簿记全走 vanilla）→ unmute →
 * {@code ServerChunkPushManager.resyncTrackedChunks}（[RESUME] 日志可验证）→
 * setC2SSink。能力标记（enableCompression/seedGen/lightCompute）先于物化设置——
 * MixinPlayerChunkSender 等拦截 gate = PlayerCompressionTracker.isCompressionEnabled。
 *
 * <p><b>S2C 推送链收口</b>（T2 选型）：平台 service 层 5 个 send 方法先调
 * {@link #tryRouteS2C}——网关玩家走 kind=1 HASSIUM 帧（客户端 GatewayPacketCodec
 * decodeHassium 同款）；返回 true = 本 helper 已消费 buf。
 *
 * <p>线程：routeS2C/帧注入任意线程；登录监听器 tick 与物化在主线程
 * （server.execute）；桥状态表线程安全。
 */
public final class GatewayPlayerBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/GatewayPlayerBridge");

    /** 未配置 controlReachableEndpoints 时的网关监听端口兜底（与 vanilla 端口错开）。 */
    public static final int DEFAULT_GATEWAY_PORT = 25566;

    private static final Map<Connection, BridgeState> BRIDGED = new ConcurrentHashMap<>();
    private static final Map<GatewayChannel, LoginBridgeState> LOGIN_BRIDGES = new ConcurrentHashMap<>();

    // [BATCH-INJECT] 诊断探针：网关 C2S 帧注入 vanilla 批 ACK 计数（临时，闭环后移除）
    private static final java.util.concurrent.atomic.AtomicLong BATCH_ACK_INJECTED =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong MOVEMENT_INJECTED =
            new java.util.concurrent.atomic.AtomicLong();

    /** T10 标准流程 pending attach：握手身份已登记但 vanilla 玩家未物化（tick 泵轮询）。 */
    private static final Map<GatewayPlayerSession, Long> PENDING_ATTACH = new ConcurrentHashMap<>();
    private static final long PENDING_ATTACH_TTL_MS = 10_000L;

    private static void schedulePendingAttach(GatewayPlayerSession session) {
        if (session != null) {
            PENDING_ATTACH.put(session, System.currentTimeMillis());
        }
    }

    /** 主线程（tick 泵）：vanilla 物化后挂 C2S sink；TTL 超时放弃（登录桥/重连兜底）。 */
    private static void pumpPendingAttach(MinecraftServer server) {
        long now = System.currentTimeMillis();
        for (GatewayPlayerSession session : PENDING_ATTACH.keySet()) {
            try {
                if (!session.channel().isOpen()) {
                    PENDING_ATTACH.remove(session);
                    continue;
                }
                ServerPlayer player = server.getPlayerList().getPlayer(session.playerId());
                if (player != null) {
                    if (session.c2sSink() == null) {
                        session.setC2SSink(createC2SSink(server, player));
                        LOGGER.info("[GATEWAY] Player {} — pending attach resolved (C2S sink 挂载)",
                                session.playerId());
                    }
                    PENDING_ATTACH.remove(session);
                } else if (now - PENDING_ATTACH.get(session) > PENDING_ATTACH_TTL_MS) {
                    LOGGER.warn("[GATEWAY] Player {} pending attach timed out (vanilla 登录未完成) — "
                                    + "登录桥/重连兜底",
                            session.playerId());
                    PENDING_ATTACH.remove(session);
                }
            } catch (Throwable t) {
                LOGGER.error("[GATEWAY] pending attach pump failed for {}", session.playerId(), t);
                PENDING_ATTACH.remove(session);
            }
        }
    }

    /** 一条桥接 Connection 的运行时状态。 */
    public static final class BridgeState {
        public final GatewayChannel channel;
        public final MinecraftServer server;
        /** 1.21.1+ PLAY 包编码用（server.registryAccess()）；&lt;1.21.1 恒 null。 */
        public final RegistryAccess registryAccess;
        /** muted = 吞 S2C 不发送（续流 placeNewPlayer join 风暴窗口）。 */
        public volatile boolean muted;

        BridgeState(GatewayChannel channel, MinecraftServer server) {
            this.channel = channel;
            this.server = server;
            this.registryAccess = PacketCodecCompat.serverRegistryAccess(server);
        }
    }

    /** 一条帧连接的登录桥状态（登录监听器 + 物化完成标记）。 */
    public static final class LoginBridgeState {
        public final GatewayChannel channel;
        public final Connection connection;
        public volatile ServerLoginPacketListenerImpl listener;
        /** 登录期解析出的玩家 UUID（登录监听器阶段捕获；配置阶段物化检测沿用）。 */
        public volatile UUID profileId;
        /** true = 已物化/已停驻/已放弃，泵不再推进。 */
        public volatile boolean done;

        LoginBridgeState(GatewayChannel channel, Connection connection) {
            this.channel = channel;
            this.connection = connection;
        }
    }

    private GatewayPlayerBridge() {
    }

    // ==================== Connection 桥 ====================

    /**
     * 创建一条网关桥接 Connection：真实 Connection(PacketFlow.SERVERBOUND) +
     * EmbeddedChannel（dummy 管道 handler）。channel/address 私有字段经 mixin
     * accessor 注入（{@link MixinConnectionGatewayServer}）。
     *
     * <p>EmbeddedChannel 常开 → isConnected()/hasDisconnected() 语义 = 已连接
     * （推送链的 {@code player.hasDisconnected()} gate 不误杀）；send 全被
     * {@link #routeS2C} 拦截，管线零流量。
     */
    public static Connection createGatewayConnection() {
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel embedded = new EmbeddedChannel();
        // vanilla 按名操作管道（setEncryptionKey/setupCompression/setupInboundProtocol）：
        // splitter/decoder/prepender/encoder 必须存在；不装 packet_handler（防重名）。
        embedded.pipeline().addLast("splitter", new ChannelInboundHandlerAdapter() {
        });
        embedded.pipeline().addLast("decoder", new ChannelInboundHandlerAdapter() {
        });
        embedded.pipeline().addLast("prepender", new ChannelOutboundHandlerAdapter() {
        });
        embedded.pipeline().addLast("encoder", new ChannelOutboundHandlerAdapter() {
        });
        ((GatewayConnectionAccessor) (Object) connection).hassium$setGatewayChannel(embedded);
        ((GatewayConnectionAccessor) (Object) connection).hassium$setGatewayAddress(
                new InetSocketAddress("127.0.0.1", 0));
        return connection;
    }

    /** 登记桥接（登录桥 / 续流物化）；muted 初始值。 */
    public static BridgeState bridgeConnection(Connection connection, GatewayChannel channel,
                                               MinecraftServer server, boolean muted) {
        BridgeState st = new BridgeState(channel, server);
        st.muted = muted;
        BRIDGED.put(connection, st);
        return st;
    }

    public static BridgeState bridgeOf(Connection connection) {
        return connection == null ? null : BRIDGED.get(connection);
    }

    public static void unbridgeConnection(Connection connection) {
        if (connection != null) {
            BRIDGED.remove(connection);
        }
    }

    /**
     * S2C 拦截路由（MixinConnectionGatewayServer 调用）：网关玩家 → kind=0 帧；
     * muted → 吞包。返回 true = 已消费（调用方须 cancel vanilla 发送）。
     */
    public static boolean routeS2C(Connection connection, Packet<?> packet) {
        BridgeState st = BRIDGED.get(connection);
        if (st == null) {
            return false; // 非网关连接：vanilla 原路径
        }
        if (packet == null) {
            return true;
        }
        if (st.muted) {
            return true; // 物化窗口：join 风暴吞掉
        }
        // 展开 bundle（1.19.4+ 原子包容器）：ClientboundBundlePacket 无独立协议 id
        // （< MC_1_21_1 走 BundleDelimiterPacket 分隔机制，getPacketId 恒 -1），直接
        // encodeVanilla 会产出 vanillaId=-1 的坏帧（M3 冒烟：客户端 decode
        // createPacket(-1) → IndexOutOfBounds → NPE 风暴）。逐子包递归编码发送，
        // 语义等价（丢失的仅渲染期原子性，登录/实体更新无正确性影响）。
        if (packet instanceof net.minecraft.network.protocol.BundlePacket<?> bundle) {
            for (Object sub : bundle.subPackets()) {
                routeS2C(connection, (net.minecraft.network.protocol.Packet<?>) sub);
            }
            return true;
        }
        try {
            GatewayPacketCodec.GatewayProtocol proto = detectProtocol(connection, packet);
            ByteBuf payload = GatewayPacketCodec.encodeVanilla(
                    packet, PacketFlow.CLIENTBOUND, proto, st.registryAccess);
            if (LOGGER.isDebugEnabled()) {
                StringBuilder sb = new StringBuilder();
                int n = Math.min(payload.readableBytes(), 12);
                for (int i = 0; i < n; i++) {
                    sb.append(String.format("%02X ", payload.getByte(payload.readerIndex() + i) & 0xFF));
                }
                LOGGER.debug("[GATEWAY] T6DBG routeS2C {} proto={} payloadSize={} head={}",
                        packet.getClass().getSimpleName(), proto, payload.readableBytes(), sb);
            }
            if (proto == GatewayPacketCodec.GatewayProtocol.LOGIN) {
                st.channel.sendLoginS2CPayload(payload);
            } else if (proto == GatewayPacketCodec.GatewayProtocol.CONFIG) {
                st.channel.sendConfigS2CPayload(payload);
            } else {
                st.channel.sendS2CPayload(payload);
            }
            return true;
        } catch (Throwable t) {
            // 编码失败：吞掉 + 日志，不炸 vanilla
            LOGGER.debug("[GATEWAY] S2C {} encode failed — dropped: {}", packet.getClass().getSimpleName(), t.toString());
            return true;
        }
    }

    /**
     * 协议判定：登录协议包（小闭集）→ LOGIN；桥连接当前监听器为配置监听器
     * （1.20.2+ vanilla 在 LoginAcknowledged 后自动切换）→ CONFIG（configuration
     * 协议编解码，registry 数据包走 CONFIG_S2C 帧）；其余 → PLAY。
     */
    private static GatewayPacketCodec.GatewayProtocol detectProtocol(Connection connection, Packet<?> packet) {
        if (packet instanceof net.minecraft.network.protocol.login.ClientboundHelloPacket
                || packet instanceof net.minecraft.network.protocol.login.ClientboundLoginCompressionPacket
                || packet instanceof net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket
                || PacketCodecCompat.isLoginFinishedPacket(packet)
                || packet instanceof net.minecraft.network.protocol.login.ClientboundCustomQueryPacket) {
            return GatewayPacketCodec.GatewayProtocol.LOGIN;
        }
#if MC_VER >= MC_1_21_1
        if (connection != null) {
            PacketListener listener = connection.getPacketListener();
            if (listener instanceof net.minecraft.server.network.ServerConfigurationPacketListenerImpl) {
                return GatewayPacketCodec.GatewayProtocol.CONFIG;
            }
        }
#endif
        return GatewayPacketCodec.GatewayProtocol.PLAY;
    }

    // ==================== 登录桥（setLoginSink 实现体） ====================

    /**
     * LOGIN_C2S / CONFIG_C2S 帧分发（Netty event loop 线程；GatewayPlatformWiring 的
     * LoginPayloadSink 调用）：解码协议由桥连接当前监听器阶段决定（登录监听器期 → 登录
     * 协议；配置监听器期 → configuration 协议，1.20.2+），主线程注入 vanilla 监听器。
     */
    public static void dispatchLoginFrame(GatewayChannel channel, ByteBuf payload, MinecraftServer server) {
        byte[] bytes = new byte[payload.readableBytes()];
        payload.readBytes(bytes);
        payload.release();
        if (server == null || channel == null) {
            return;
        }
        server.execute(() -> {
            try {
                LoginBridgeState st = LOGIN_BRIDGES.get(channel);
                if (st == null) {
                    st = startLoginBridge(channel, server);
                }
                PacketListener current = st.connection.getPacketListener();
                if (current == null) {
                    return;
                }
                if (current instanceof ServerLoginPacketListenerImpl loginListener
                        && loginListener == st.listener) {
                    // 登录阶段：登录协议解码 → handle；捕获 profile（物化检测/配置阶段用）
                    Packet<?> packet = decodeLoginServerbound(bytes);
                    if (packet == null) {
                        return;
                    }
                    @SuppressWarnings({"rawtypes", "unchecked"})
                    PacketListener l = loginListener;
                    ((Packet) packet).handle(l);
                    if (st.profileId == null) {
                        st.profileId = resolveProfileId(loginListener);
                    }
                    return;
                }
#if MC_VER >= MC_1_21_1
                if (current instanceof net.minecraft.server.network.ServerConfigurationPacketListenerImpl configListener) {
                    // T10 配置阶段：vanilla 在 handleLoginAcknowledgement 自动切换监听器——
                    // 登录监听器退役，配置协议解码 → handle（配置完成后 vanilla 物化玩家）
                    st.listener = null;
                    if (st.done) {
                        return;
                    }
                    Packet<?> packet = decodeConfigServerbound(bytes);
                    if (packet == null) {
                        return;
                    }
                    @SuppressWarnings({"rawtypes", "unchecked"})
                    PacketListener l = configListener;
                    ((Packet) packet).handle(l);
                    // 物化检测：handleConfigurationFinished → placeNewPlayer（1.20.2+）
                    UUID playerId = st.profileId != null
                            ? st.profileId
                            : resolveProfileId(configListener);
                    if (playerId != null) {
                        st.profileId = playerId;
                        if (server.getPlayerList().getPlayer(playerId) != null) {
                            finishLoginBridge(st, server, playerId);
                        }
                    }
                    return;
                }
#endif
                // 未知/已退役监听器：停驻（不炸）
                st.done = true;
                LOGGER.info("[GATEWAY] Login bridge parked at phase {} for {} (未知监听器阶段)",
                        current.getClass().getSimpleName(), channel.remote());
            } catch (Throwable t) {
                LOGGER.error("[GATEWAY] LOGIN/CONFIG_C2S dispatch failed from {}", channel.remote(), t);
            }
        });
    }

    private static LoginBridgeState startLoginBridge(GatewayChannel channel, MinecraftServer server) {
        return LOGIN_BRIDGES.computeIfAbsent(channel, c -> {
            Connection connection = createGatewayConnection();
            bridgeConnection(connection, channel, server, false);
#if MC_VER < MC_1_21_1
            ServerLoginPacketListenerImpl listener = new ServerLoginPacketListenerImpl(server, connection);
#else
            ServerLoginPacketListenerImpl listener = new ServerLoginPacketListenerImpl(server, connection, false);
#endif
            // setListener 在 1.21.11 非 public → 统一走 mixin accessor（各锚点字段同名 packetListener）
            ((GatewayConnectionAccessor) (Object) connection).hassium$setGatewayPacketListener(listener);
            LoginBridgeState st = new LoginBridgeState(channel, connection);
            st.listener = listener;
            LOGGER.info("[GATEWAY] Login bridge started for {} — 等待 LOGIN_C2S 登录链", channel.remote());
            return st;
        });
    }

    /**
     * 每 server tick 泵（MixinMinecraftServer.onServerTick 调用，主线程）：
     * 登录/配置监听器 tick（VERIFYING → 压缩/finished 推进靠泵）+ 物化检测 +
     * 断连清理 + 标准流程 pending attach 重试。
     */
    public static void tick(MinecraftServer server) {
        for (Map.Entry<GatewayChannel, LoginBridgeState> e : LOGIN_BRIDGES.entrySet()) {
            LoginBridgeState st = e.getValue();
            try {
                if (!st.channel.isOpen()) {
                    LOGIN_BRIDGES.remove(e.getKey(), st);
                    unbridgeConnection(st.connection);
                    continue;
                }
                if (st.done) {
                    continue;
                }
                if (st.listener != null) {
                    // 登录阶段（1.20.1 全链 / 1.20.2+ ack 前）
                    st.listener.tick();
                    // 物化检测（1.20.1：tick 内 handleAcceptedLogin → placeNewPlayer）
                    UUID playerId = resolveProfileId(st.listener);
                    if (playerId != null) {
                        st.profileId = playerId;
                        if (server.getPlayerList().getPlayer(playerId) != null) {
                            finishLoginBridge(st, server, playerId);
                        }
                    }
                } else {
#if MC_VER >= MC_1_21_1
                    // 配置阶段（1.20.2+）：vanilla 在 LoginAcknowledged 后自动切换监听器
                    PacketListener current = st.connection.getPacketListener();
                    if (current instanceof net.minecraft.server.network.ServerConfigurationPacketListenerImpl configListener) {
                        // keepConnectionAlive（配置任务推进由配置包驱动，无需额外泵）
                        ((net.minecraft.network.TickablePacketListener) configListener).tick();
                        UUID playerId = st.profileId != null
                                ? st.profileId
                                : resolveProfileId(configListener);
                        if (playerId != null) {
                            st.profileId = playerId;
                            if (server.getPlayerList().getPlayer(playerId) != null) {
                                finishLoginBridge(st, server, playerId);
                            }
                        }
                    }
#endif
                }
            } catch (Throwable t) {
                LOGGER.error("[GATEWAY] login tick failed for {}", st.channel.remote(), t);
            }
        }
        pumpPendingAttach(server);
        // T4 B3：预热会话 TTL 清扫（registry 层；无续流完成的 resume 会话到期走 finishRemoval
        // 完整清理链；TTL 读 HassiumConfigService.getMigrationPrewarmTtlMs()）
        try {
            GatewayServer.getInstance().registry().sweepExpired(System.currentTimeMillis(),
                    io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance()
                            .getMigrationPrewarmTtlMs());
        } catch (Throwable t) {
            LOGGER.error("[GATEWAY] prewarm TTL sweep failed", t);
        }
    }

    /** 登录完成（1.20.1：ServerPlayer 已物化）：附着会话 + C2S sink。 */
    private static void finishLoginBridge(LoginBridgeState st, MinecraftServer server, UUID playerId) {
        st.done = true;
        GatewayPlayerSession session = st.channel.attachPlayer(playerId, false, Long.MIN_VALUE, null);
        if (session == null) {
            LOGGER.warn("[GATEWAY] login attach failed for {} — 通道已附着其他会话", playerId);
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            PlayerCompressionTracker.enableCompression(player);
            ServerChunkPushManager.getInstance().resyncTrackedChunks(player);
            session.setC2SSink(createC2SSink(server, player));
        }
        LOGGER.info("[GATEWAY] Login bridge completed for {} — 会话附着 + C2S sink 挂载", playerId);
    }

    /** 监听器 GameProfile 反射（Fabric resolveLoginPlayerId 同款：按类型取字段；登录/配置监听器通用）。 */
    private static UUID resolveProfileId(Object listener) {
        try {
            com.mojang.authlib.GameProfile profile = (com.mojang.authlib.GameProfile)
                    io.github.limuqy.mc.hassium.compat.ReflectionCompat.getFieldByType(
                            listener, com.mojang.authlib.GameProfile.class, true);
            if (profile == null) {
                return null;
            }
            UUID id = io.github.limuqy.mc.hassium.compat.PlayerCompat.getProfileId(profile);
            if (id != null) {
                return id;
            }
            // 离线模式：profile 不完整（id=null），派生 OfflinePlayer UUID
            String name = io.github.limuqy.mc.hassium.compat.PlayerCompat.getProfileName(profile);
            if (name != null) {
                return net.minecraft.core.UUIDUtil.createOfflinePlayerUUID(name);
            }
        } catch (Throwable t) {
            LOGGER.debug("[GATEWAY] login profile resolve failed: {}", t.toString());
        }
        return null;
    }

    // ==================== 续流物化 ====================

    /**
     * 续流会话物化调度（attach hook 调用，任意线程）：主线程创建 ServerPlayer +
     * muted placeNewPlayer + resyncTrackedChunks + C2S sink。
     */
    public static void materializeResumePlayer(MinecraftServer server, GatewayPlayerSession session) {
        if (server == null || session == null) {
            return;
        }
        server.execute(() -> {
            try {
                materializeResumeOnMain(server, session);
            } catch (Throwable t) {
                LOGGER.error("[GATEWAY] resume materialization failed for {}", session.playerId(), t);
            }
        });
    }

    private static void materializeResumeOnMain(MinecraftServer server, GatewayPlayerSession session) {
        UUID playerId = session.playerId();
        if (!session.channel().isOpen()) {
            LOGGER.debug("[GATEWAY] resume materialization skipped for {} — channel closed", playerId);
            return;
        }
        if (server.getPlayerList().getPlayer(playerId) != null) {
            // T10 标准流程口径：vanilla 玩家已物化 → 网关会话直接挂 vanilla 玩家的 C2S sink
            // （双 ServerPlayer 冲突结构性不可达，见 T10-TASK.md 论证；此分支亦兜续流握手
            // 早于 vanilla 物化的时序竞态）
            ServerPlayer existing = server.getPlayerList().getPlayer(playerId);
            // T5b：与 finishLoginBridge 对齐 —— 压缩启用 + 区块重同步（resyncTrackedChunks
            // 内守卫 isCompressionEnabled=false 直接 return，缺 enableCompression 则推送链
            // 零 hash 零推送 → 客户端数据面全 0）；两调用幂等，先于 sink 挂载执行
            PlayerCompressionTracker.enableCompression(existing);
            ServerChunkPushManager.getInstance().resyncTrackedChunks(existing);
            if (session.c2sSink() == null) {
                session.setC2SSink(createC2SSink(server, existing));
                LOGGER.info("[GATEWAY] Player {} — attached to existing vanilla player (C2S sink 挂载)",
                        playerId);
            }
            return;
        }
        if (!session.resume()) {
            // T10 标准流程：握手身份附着时 vanilla 玩家尚未物化（握手早于登录完成）——
            // 排队等待（tick 泵轮询，TTL 后放弃回登录桥/重连兜底）
            schedulePendingAttach(session);
            LOGGER.info("[GATEWAY] Player {} — vanilla player not materialized yet, pending attach queued",
                    playerId);
            return;
        }
        PlayerStateReport state = session.stateReport();
        boolean present = state != null && state.present();
        // 名称来源定案（A6）：playerdata NBT 无 name 字段 → 玩家名缓存解析
        // （<1.21.9 usercache.json GameProfileCache；1.21.9+ services().nameToIdCache()）；
        // 无记录时兜底占位名 Hassium#<uuid8>（日志明确标记 fallback）
        String name = PlayerDataStorage.resolveName(server, playerId);
        boolean fallbackName = name == null;
        if (fallbackName) {
            name = "Hassium#" + playerId.toString().substring(0, 8);
        }
        ServerLevel level = resolveLevel(server, state);
        com.mojang.authlib.GameProfile profile = new com.mojang.authlib.GameProfile(playerId, name);
        ServerPlayer player = createServerPlayer(server, level, profile);
        // 磁盘 playerdata 加载（先于 placeNewPlayer，muted 窗口内）：背包/末影箱/经验/
        // 位置/维度（EntitySnapshotCompat.loadFromTag 封装 1.21.6+ ValueInput 差异）
        PlayerDataStorage.LoadResult data = PlayerDataStorage.loadInto(server, player, name);
        if (data.loaded()) {
            LOGGER.info("[GATEWAY] Player {} resume data loaded (name={}) — 背包/末影箱/位置从 playerdata 恢复",
                    playerId, name);
        } else {
            LOGGER.info("[GATEWAY] Player {} no playerdata on disk (name={}{}) — resume with empty data",
                    playerId, name, fallbackName ? ", fallback name" : "");
        }
        // 维度：续流上报优先；上报缺失时回退磁盘存档维度（resolveLevel 已兜底主世界）
        if (!present) {
            ResourceKey<Level> diskDim = PlayerDataStorage.parseDimension(data.tag());
            if (diskDim != null) {
                ServerLevel diskLevel = server.getLevel(diskDim);
                if (diskLevel != null) {
                    level = diskLevel;
                    player.setServerLevel(diskLevel);
                }
            }
        }
        // 位置：断线上报快照优先（更新鲜）；缺失时用磁盘 NBT 的 Pos（loadFromTag 已应用）
        double y = present
                ? state.y()
                : (data.loaded()
                ? player.getY()
                : LevelCompat.spawnPos(level).getY());
        player.setPos(present ? state.x() : player.getX(), y,
                present ? state.z() : player.getZ());
        player.setYRot(present ? state.yaw() : player.getYRot());
        player.setXRot(present ? state.pitch() : player.getXRot());

        // 能力标记先于物化：MixinPlayerChunkSender/MixinChunkHolder 拦截 gate =
        // PlayerCompressionTracker.isCompressionEnabled；推送链按 flag 走 hash 主链路
        ServerChunkPushManager push = ServerChunkPushManager.getInstance();
        push.setPlayerSeedGenSupported(playerId,
                session.channel().handshakeOptions() != null
                        && session.channel().handshakeOptions().seedGenSupported());
        // A7：帧握手尾携带 lightComputeSupported（旧端/缺尾默认 false）→ 剥光 gate 按客户端能力
        HandshakeStateTail.C2S stateTail = session.channel().stateTail();
        push.setPlayerLightComputeSupported(playerId, stateTail != null && stateTail.lightComputeSupported());
        PlayerCompressionTracker.enableCompression(player);

        // muted placeNewPlayer：join S2C 风暴吞掉（续流客户端已持有世界）；server 侧簿记全走 vanilla
        Connection connection = createGatewayConnection();
        BridgeState bridge = bridgeConnection(connection, session.channel(), server, true);
        // listener 占位（placeNewPlayer 会覆盖为正式 listener；1.21.11 setListener 非 public → accessor）
        if (player.connection != null) {
            ((GatewayConnectionAccessor) (Object) connection).hassium$setGatewayPacketListener(player.connection);
        }
        placeNewPlayer(server, connection, player);
        bridge.muted = false;

        // 续流就绪：按上报位置重发视距 hash（[RESUME] 日志；initialPlayerChunkPos 已由握手设置）
        push.resyncTrackedChunks(player);
        session.setC2SSink(createC2SSink(server, player));
        LOGGER.info("[GATEWAY] Player {} materialized at ({}, {}, {}) dim={} — resyncTrackedChunks 触发",
                playerId, player.getX(), player.getY(), player.getZ(),
                LevelCompat.getDimensionId(level));
    }

    private static ServerPlayer createServerPlayer(MinecraftServer server, ServerLevel level,
                                                   com.mojang.authlib.GameProfile profile) {
#if MC_VER < MC_1_21_1
        return new ServerPlayer(server, level, profile);
#else
        return new ServerPlayer(server, level, profile, net.minecraft.server.level.ClientInformation.createDefault());
#endif
    }

    private static void placeNewPlayer(MinecraftServer server, Connection connection, ServerPlayer player) {
#if MC_VER < MC_1_21_1
        server.getPlayerList().placeNewPlayer(connection, player);
#else
        server.getPlayerList().placeNewPlayer(connection, player,
                net.minecraft.server.network.CommonListenerCookie.createInitial(player.getGameProfile(), false));
#endif
    }

    /** 上报维度解析；未知/缺失回退主世界。 */
    private static ServerLevel resolveLevel(MinecraftServer server, PlayerStateReport state) {
        if (state != null && state.present()) {
            try {
                ResourceKey<Level> key = ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION,
                        ResourceLocationCompat.create(state.dimension()));
                ServerLevel level = server.getLevel(key);
                if (level != null) {
                    return level;
                }
            } catch (Throwable t) {
                LOGGER.debug("[GATEWAY] dimension {} unresolved — fallback overworld: {}",
                        state.dimension(), t.toString());
            }
        }
        return server.overworld();
    }

    // ==================== C2S 注入（PLAY / 登录） ====================

    /** 会话 C2S sink：decode kind=0 SERVERBOUND → 主线程 packet.handle(player.connection)。 */
    public static C2SPayloadSink createC2SSink(MinecraftServer server, ServerPlayer player) {
        return (playerId, payload) -> {
            byte[] bytes = new byte[payload.readableBytes()];
            payload.readBytes(bytes);
            payload.release();
            server.execute(() -> {
                try {
                    if (player.hasDisconnected()) {
                        return;
                    }
                    Packet<?> packet = decodeServerboundPlay(bytes, server.registryAccess());
                    if (packet == null) {
                        return;
                    }
                    boolean movementPacket = packet instanceof net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
                    String packetName = packet.getClass().getName();
                    if (movementPacket) {
                        long n = MOVEMENT_INJECTED.incrementAndGet();
                        if (n <= 5 || n % 20 == 0) {
                            LOGGER.info("[GATEWAY-MOVE] before #{} player={} packet={} pos=({}, {}, {})",
                                    n, playerId, packetName, player.getX(), player.getY(), player.getZ());
                        }
                    }
#if MC_VER >= MC_1_21_1
                    if (packet instanceof net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket ack) {
                        long n = BATCH_ACK_INJECTED.incrementAndGet();
                        if (n <= 5 || n % 20 == 0) {
                            LOGGER.info("[BATCH-INJECT] server injected ack#{} f={}", n, ack.desiredChunksPerTick());
                        }
                    }
#endif
                    @SuppressWarnings({"rawtypes", "unchecked"})
                    PacketListener listener = player.connection;
                    ((Packet) packet).handle(listener);
                    if (movementPacket) {
                        long n = MOVEMENT_INJECTED.get();
                        if (n <= 5 || n % 20 == 0) {
                            LOGGER.info("[GATEWAY-MOVE] after #{} player={} pos=({}, {}, {}) chunk=({}, {})",
                                    n, playerId, player.getX(), player.getY(), player.getZ(),
                                    player.chunkPosition().x, player.chunkPosition().z);
                        }
                    }
                } catch (Throwable t) {
                    LOGGER.error("[GATEWAY] C2S inject failed for {}", playerId, t);
                }
            });
        };
    }

    /** PLAY SERVERBOUND 解码（kind=0：[varint 0][varint id][body]）。 */
    private static Packet<?> decodeServerboundPlay(byte[] framePayload, RegistryAccess registryAccess) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(framePayload));
        try {
            int kind = buf.readVarInt();
            if (kind != GatewayPacketCodec.KIND_VANILLA) {
                LOGGER.warn("[GATEWAY] unexpected C2S kind {} — dropped", kind);
                return null;
            }
            int id = buf.readVarInt();
            byte[] body = new byte[buf.readableBytes()];
            buf.readBytes(body);
            return PacketCodecCompat.deserializePacketById(PacketFlow.SERVERBOUND, id, body, registryAccess);
        } finally {
            buf.release();
        }
    }

    /** LOGIN SERVERBOUND 解码（kind=0；GatewayPacketCodec.decodeVanilla 的 SERVERBOUND 镜像）。 */
    private static Packet<?> decodeLoginServerbound(byte[] framePayload) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(framePayload));
        try {
            int kind = buf.readVarInt();
            if (kind != GatewayPacketCodec.KIND_VANILLA) {
                return null;
            }
            int id = buf.readVarInt();
            byte[] body = new byte[buf.readableBytes()];
            buf.readBytes(body);
#if MC_VER < MC_1_21_1
            FriendlyByteBuf pBuf = new FriendlyByteBuf(Unpooled.wrappedBuffer(body));
            try {
                return net.minecraft.network.ConnectionProtocol.LOGIN.createPacket(PacketFlow.SERVERBOUND, id, pBuf);
            } finally {
                pBuf.release();
            }
#else
            FriendlyByteBuf lBuf = new FriendlyByteBuf(Unpooled.buffer(body.length + 5));
            try {
                lBuf.writeVarInt(id);
                lBuf.writeBytes(body);
                return (Packet<?>) ((net.minecraft.network.codec.StreamCodec)
                        net.minecraft.network.protocol.login.LoginProtocols.SERVERBOUND.codec()).decode(lBuf);
            } finally {
                lBuf.release();
            }
#endif
        } finally {
            buf.release();
        }
    }

    /** CONFIG SERVERBOUND 解码（kind=0；1.20.2+；1.20.1 无配置协议——调用方不会触发）。 */
    private static Packet<?> decodeConfigServerbound(byte[] framePayload) {
        ByteBuf buf = Unpooled.wrappedBuffer(framePayload);
        try {
            return GatewayPacketCodec.decodeVanilla(
                    buf, PacketFlow.SERVERBOUND, GatewayPacketCodec.GatewayProtocol.CONFIG,
                    RegistryAccess.EMPTY);
        } finally {
            buf.release();
        }
    }

    // ==================== S2C 推送链收口（kind=1 HASSIUM） ====================

    /**
     * 网关玩家 S2C 推送路由（平台 service 层 5 个 send 方法调用）：
     * 会话存在 → 编码 kind=1 HASSIUM 帧（[varint 1][varint subId][body]）→
     * {@link GatewayPlayerSession#sendS2CPayload}。返回 true = 已消费 buf
     * （成功或失败均消费——网关玩家回落 CustomPayload 是死路径：客户端 receiver 已退役）。
     */
    public static boolean tryRouteS2C(ServerPlayer player, int hassiumSubId, FriendlyByteBuf buf) {
        if (player == null || buf == null) {
            return false;
        }
        GatewayPlayerSession session = GatewayServer.getInstance().registry().get(player.getUUID());
        if (session == null) {
            return false; // 非网关玩家：平台原路径
        }
        ByteBuf payload = Unpooled.buffer();
        try {
            payload.writeByte(GatewayPacketCodec.KIND_HASSIUM);
            ControlFrameCodec.writeVarInt(payload, hassiumSubId);
            // review-fix: 原 buf.getBytes(readerIndex, payload, payload.writerIndex(), len) 把 payload
            // 当固定容量 dst（Unpooled.buffer() 初始 256B，getBytes 不扩容，只查 dstIndex+len<=capacity）
            // → 大业务包（SECTION_DELTA >254B 等）越界 IndexOutOfBoundsException 被吞掉丢弃。
            // 改 writeBytes 自动扩容 + 追加到 writerIndex，大包正确写入。
            payload.writeBytes(buf, buf.readerIndex(), buf.readableBytes());
            session.sendS2CPayload(payload); // 所有权移交
            buf.release();
            return true;
        } catch (Throwable t) {
            LOGGER.error("[GATEWAY] tryRouteS2C(sub={}) failed for {} — dropped",
                    hassiumSubId, player.getUUID(), t);
            if (payload.refCnt() > 0) {
                payload.release();
            }
            if (buf.refCnt() > 0) {
                buf.release();
            }
            return true;
        }
    }

    // ==================== 清理 ====================

    /** 网关停机：清空桥状态（GatewayServer.stop 已逐会话清理注册表）。 */
    public static void clearAll() {
        for (LoginBridgeState st : LOGIN_BRIDGES.values()) {
            try {
                st.connection.disconnect(Component.literal("gateway shutdown"));
            } catch (Throwable t) {
                LOGGER.debug("[GATEWAY] login bridge disconnect failed: {}", t.toString());
            }
        }
        LOGIN_BRIDGES.clear();
        BRIDGED.clear();
        PENDING_ATTACH.clear();
    }

    /** 平台 per-player 清理钩子实现体（GatewayPlatformWiring 注册）：vanilla 侧完整移除。 */
    public static void onPlayerSessionRemoved(MinecraftServer server, UUID playerId) {
        if (server == null || playerId == null) {
            return;
        }
        server.execute(() -> {
            try {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player == null) {
                    return;
                }
                PlayerCompressionTracker.removePlayer(player);
                // vanilla 完整移除（save/broadcast/untrack）；S2C 经桥丢弃（帧连接已关）
                server.getPlayerList().remove(player);
                LOGGER.info("[GATEWAY] Player {} removed from world (gateway session closed)", playerId);
            } catch (Throwable t) {
                LOGGER.error("[GATEWAY] session removal cleanup failed for {}", playerId, t);
            }
        });
    }

    /** 桥接 Connection 数量（测试/诊断）。 */
    public static int bridgedConnectionCount() {
        return BRIDGED.size();
    }

    /** 活跃登录桥数量（测试/诊断）。 */
    public static int loginBridgeCount() {
        return LOGIN_BRIDGES.size();
    }
}
