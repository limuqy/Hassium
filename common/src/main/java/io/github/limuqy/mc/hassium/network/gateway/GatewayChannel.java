package io.github.limuqy.mc.hassium.network.gateway;

import io.github.limuqy.mc.hassium.network.HandshakeStateTail;
import io.github.limuqy.mc.hassium.network.PlayerStateReport;
import io.github.limuqy.mc.hassium.network.ResumeTicket;
import io.github.limuqy.mc.hassium.network.ResumeTicketValidator;
import io.github.limuqy.mc.hassium.network.ServerChunkPushManager;
import io.github.limuqy.mc.hassium.network.SkipAwareZstdEncoder;
import io.github.limuqy.mc.hassium.network.ZstdContextDecoder;
import io.github.limuqy.mc.hassium.network.core.outbound.ControlFrameCodec;
import io.github.limuqy.mc.hassium.network.core.outbound.ControlFrameType;
import io.github.limuqy.mc.hassium.network.core.outbound.HandshakeCodec;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 主控侧网关接入层：一条 outbound 帧连接（客户端 {@code OutboundConnection} 的对称端）。
 *
 * <p><b>帧协议</b>：复用 T4 {@link ControlFrameCodec}（varint 帧长 + type + payload），
 * 纯 Netty 零 MC 依赖。管道（注册序）：
 * <pre>
 *   [zstdDecoder(握手后)] [zstdEncoder(握手后)] [frameDecoder] [handler]
 * </pre>
 *
 * <p><b>握手流</b>（T11，HANDSHAKE_C2S 帧）：固定字段经
 * {@link HandshakeCodec#decodeClientRequest}（T11 新增 master 镜像），剩余 append-only
 * 尾经 {@link HandshakeStateTail#readC2S} 读 T7 状态尾。续流分支（关键决策，见
 * T11-TASK.md）：有票据 → {@link ResumeTicketValidator#verifyAndAccept} 验签 + epoch
 * 防重放 → 通过则 {@link ServerChunkPushManager#markPlayerResumeActive}（T7 已实现
 * 分支被帧侧触发）+ 注册 UUID-keyed 玩家会话 + S2C 尾 {@code resumeAccepted=true}；
 * 无票据/验票失败 → {@code resumeAccepted=false}，会话待登录桥
 * （{@link #attachPlayer}，T5 登录中继配对）附着。响应 = {@link HandshakeCodec#encodeServerResponse}
 * + T7 S2C 尾。
 *
 * <p><b>数据桥</b>：S2C 推送（区块/hash/delta/light/实体原版包编码后）经
 * {@link #sendS2CPayload} → PACKET_S2C 帧回网关；C2S 帧（T5 中继）经
 * {@link #handleC2SPayload} 分发到会话 sink（平台注入玩家处理链）；登录阶段
 * LOGIN_C2S 帧（T5 帧类型 9）分发到 {@link GatewayServer} 登录桥缝。
 *
 * <p><b>线程模型</b>：握手/C2S/帧处理在 Netty event loop 线程；{@link #sendS2CPayload}
 * 可任意线程（writeAndFlush 线程安全）。
 */
public final class GatewayChannel {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/GatewayChannel");

    private static final String DECODER_NAME = "frameDecoder";
    private static final String ZSTD_DECODER_NAME = "zstdDecoder";
    private static final String ZSTD_ENCODER_NAME = "zstdEncoder";

    private final GatewayServer server;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean zstdInstalled = new AtomicBoolean(false);
    private final AtomicReference<State> state = new AtomicReference<>(State.HANDSHAKE);
    private final AtomicLong s2cFramesSent = new AtomicLong();
    private final AtomicLong c2sFramesReceived = new AtomicLong();
    private final AtomicLong loginFramesReceived = new AtomicLong();
    private final AtomicLong configFramesReceived = new AtomicLong();

    private volatile Channel channel;
    private volatile HandshakeCodec.ClientRequestOptions handshakeOptions;
    private volatile HandshakeStateTail.C2S stateTail;
    private volatile boolean resumeAccepted;
    private volatile long resumeEpoch = Long.MIN_VALUE;
    private volatile GatewayPlayerSession playerSession;

    /** 连接阶段：握手 → 活跃；握手完成前/失败即关闭。 */
    public enum State {
        HANDSHAKE,
        ACTIVE,
        CLOSED
    }

    GatewayChannel(GatewayServer server, Channel channel) {
        this.server = server;
        this.channel = channel;
    }

    /**
     * 测试缝：EmbeddedChannel 跑完整帧/握手流（同包测试用）。
     * channelActive 在构造期触发；随后可直接 writeInbound 帧。
     */
    public static GatewayChannel openEmbedded(GatewayServer server) {
        GatewayChannel gc = new GatewayChannel(server, null);
        EmbeddedChannel embedded = new EmbeddedChannel(new FrameDecoder(), new GatewayChannelHandler(gc));
        gc.channel = embedded;
        return gc;
    }

    // ==================== 握手（event loop 线程） ====================

    void handleHandshake(ByteBuf payload) {
        if (!state.compareAndSet(State.HANDSHAKE, State.ACTIVE)) {
            LOGGER.warn("[GATEWAY] duplicate/late HANDSHAKE_C2S from {} (state={})", remote(), state.get());
            close("duplicate handshake");
            return;
        }
        final HandshakeCodec.ClientRequestOptions request;
        final HandshakeStateTail.C2S tail;
        try {
            request = HandshakeCodec.decodeClientRequest(payload);
            tail = HandshakeStateTail.readC2S(payload);
        } catch (Throwable t) {
            LOGGER.warn("[GATEWAY] malformed HANDSHAKE_C2S from {}: {}", remote(), t.toString());
            close("malformed handshake");
            return;
        }
        handshakeOptions = request;
        stateTail = tail;

        // ---- 续流验票：玩家身份 = 票据身份（帧连接无原版 ServerPlayer） ----
        UUID resumePlayer = null;
        long epoch = Long.MIN_VALUE;
        boolean resumeOk = false;
        if (tail != null && tail.resumeRequested() && tail.resumeTicket() != null) {
            try {
                ResumeTicket ticket = ResumeTicket.decode(tail.resumeTicket());
                resumePlayer = ticket.playerId();
                epoch = ticket.epoch();
                resumeOk = ResumeTicketValidator.verifyAndAccept(ticket); // 验签 + epoch 防重放
            } catch (IllegalArgumentException e) {
                LOGGER.warn("[GATEWAY] resume ticket decode failed from {}: {}", remote(), e.toString());
            }
        }

        GatewayServerInfoProvider.ServerHandshakeInfo info;
        try {
            info = server.infoProvider().resolve(this, request);
        } catch (Throwable t) {
            LOGGER.warn("[GATEWAY] info provider failed for {}: {}", remote(), t.toString());
            info = GatewayServerInfoProvider.acceptDefaults();
        }

        if (info.accepted() && resumeOk && resumePlayer != null) {
            // T7 分支被帧侧触发：验票通过 → 续流就绪 → 复用现有推送链（UUID-keyed 表）
            resumeAccepted = true;
            resumeEpoch = epoch;
            PlayerStateReport reported = (tail != null && tail.state() != null && tail.state().present())
                    ? tail.state()
                    : PlayerStateReport.fromXZ(request.posX(), request.posZ());
            ServerChunkPushManager push = ServerChunkPushManager.getInstance();
            push.setPlayerSeedGenSupported(resumePlayer, request.seedGenSupported());
            push.setInitialPlayerPosition(resumePlayer, reported);
            push.markPlayerResumeActive(resumePlayer, epoch);
            attachPlayer(resumePlayer, true, epoch, reported);
            LOGGER.info("[GATEWAY] Resume accepted for {} (epoch={}) — 续流就绪", resumePlayer, epoch);
        } else if (info.accepted() && resumePlayer != null) {
            LOGGER.info("[GATEWAY] Resume rejected for {} (epoch={}, ticket invalid/replay) — 回退新会话语义",
                    resumePlayer, epoch);
            // 不注册会话（验票失败契约：重放/伪造票据不得附着；标准流程附着仅走
            // resumePlayer == null 且握手尾携带身份的独立分支）
        } else if (info.accepted() && resumePlayer == null
                && tail != null && tail.playerId() != null && playerSession == null) {
            // T10 标准流程：握手尾携带玩家 UUID（非续流）——会话登记；C2S sink 待 vanilla
            // 玩家物化后挂载（平台 attach 钩子：已物化直挂 / 未物化排队重试）
            resumeAccepted = false;
            UUID playerId = tail.playerId();
            PlayerStateReport reported = (tail.state() != null && tail.state().present())
                    ? tail.state()
                    : PlayerStateReport.fromXZ(request.posX(), request.posZ());
            ServerChunkPushManager push = ServerChunkPushManager.getInstance();
            push.setPlayerSeedGenSupported(playerId, request.seedGenSupported());
            push.setInitialPlayerPosition(playerId, reported);
            attachPlayer(playerId, false, Long.MIN_VALUE, reported);
            LOGGER.info("[GATEWAY] Player ident {} from {} — 会话登记，待 vanilla 物化后挂 C2S sink",
                    playerId, remote());
        }

        // ---- S2C 响应：固定字段 + 数据面尾 + SeedGen 尾 + T7 resumeAccepted 尾 ----
        ByteBuf response = HandshakeCodec.encodeServerResponse(
                info.protocolVersion(), info.accepted(),
                info.globalCompressionAccepted(), info.compactHeaderAccepted(),
                info.udpTail(), info.worldSeed(), info.levelStemNbt(), info.seedGenEnabled());
        if (info.accepted()) {
            HandshakeStateTail.writeS2C(response, new HandshakeStateTail.S2C(resumeAccepted));
        }
        sendFrame(ControlFrameType.HANDSHAKE_S2C, response);

        if (info.accepted()) {
            if (info.globalCompressionAccepted()) {
                installZstd(server.zstdThreshold(), server.zstdLevel());
            }
            LOGGER.info("[GATEWAY] Handshake accepted from {} (proto={}, mod={}, udp={}, failover={}, resume={})",
                    remote(), request.protocolVersion(), request.modVersion(),
                    request.udpDataplaneSupported(), request.controlFailoverSupported(), resumeAccepted);
        } else {
            LOGGER.warn("[GATEWAY] Handshake rejected from {} (proto={})", remote(), request.protocolVersion());
            close("handshake rejected"); // review-fix: 拒绝后立即关闭，不留半开连接
        }
    }

    // ==================== 数据桥 ====================

    /**
     * 发送 S2C 包 payload 回网关客户端（PACKET_S2C 帧；任意线程安全）。
     * payload 所有权移交（本方法负责 release）。
     */
    public void sendS2CPayload(ByteBuf payload) {
        if (payload == null) {
            return;
        }
        int bytes = payload.readableBytes();
        s2cFramesSent.incrementAndGet();
        server.onS2CFrame();
        sendFrame(ControlFrameType.PACKET_S2C, payload);
        LOGGER.debug("[GATEWAY] S2C frame sent to {} ({}B, channel total={})",
                playerName(), bytes, s2cFramesSent.get());
    }

    /**
     * 发送 LOGIN_S2C 帧（登录阶段 S2C，T5 帧类型 10；任意线程安全）。
     * payload 所有权移交（本方法负责 release）。
     */
    public void sendLoginS2CPayload(ByteBuf payload) {
        if (payload == null) {
            return;
        }
        int bytes = payload.readableBytes();
        sendFrame(ControlFrameType.LOGIN_S2C, payload);
        LOGGER.debug("[GATEWAY] LOGIN_S2C frame sent to {} ({}B)", remote(), bytes);
    }

    /**
     * 发送 CONFIG_S2C 帧（配置阶段 S2C，T10 帧类型 12；任意线程安全）。
     * payload 所有权移交（本方法负责 release）。
     */
    public void sendConfigS2CPayload(ByteBuf payload) {
        if (payload == null) {
            return;
        }
        int bytes = payload.readableBytes();
        sendFrame(ControlFrameType.CONFIG_S2C, payload);
        LOGGER.debug("[GATEWAY] CONFIG_S2C frame sent to {} ({}B)", remote(), bytes);
    }

    /** CONFIG_C2S 帧（T10 帧类型 11；配置阶段，会话可未建立）→ 服务端登录桥缝（阶段感知分发）。 */
    void handleConfigPayload(ByteBuf payload) {
        configFramesReceived.incrementAndGet();
        server.onConfigFrame();
        LoginPayloadSink sink = server.loginSink();
        if (sink == null) {
            LOGGER.debug("[GATEWAY] CONFIG_C2S frame ({}B) from {} — no login bridge registered (T10 配置中继配对)",
                    payload.readableBytes(), remote());
            return;
        }
        try {
            sink.acceptConfig(this, payload.retainedDuplicate());
        } catch (Throwable t) {
            LOGGER.error("[GATEWAY] config sink failed for {}", remote(), t);
        }
    }

    /** PACKET_C2S 帧（PLAY 阶段，会话已附着）→ 会话 sink（event loop 线程）。 */
    void handleC2SPayload(ByteBuf payload) {
        c2sFramesReceived.incrementAndGet();
        server.onC2SFrame();
        GatewayPlayerSession session = playerSession;
        if (session == null) {
            LOGGER.debug("[GATEWAY] C2S frame ({}B) from {} — no player session attached", payload.readableBytes(), remote());
            return;
        }
        C2SPayloadSink sink = session.c2sSink();
        if (sink == null) {
            LOGGER.debug("[GATEWAY] C2S frame from {} ({}B) — no sink registered (platform bridge, T5 中继配对)",
                    session.playerId(), payload.readableBytes());
            return;
        }
        try {
            sink.accept(session.playerId(), payload.retainedDuplicate());
        } catch (Throwable t) {
            LOGGER.error("[GATEWAY] C2S sink failed for {}", session.playerId(), t);
        }
    }

    /** LOGIN_C2S 帧（T5 帧类型 9；登录阶段，会话未建立）→ 服务端登录桥缝。 */
    void handleLoginPayload(ByteBuf payload) {
        loginFramesReceived.incrementAndGet();
        server.onLoginFrame();
        LoginPayloadSink sink = server.loginSink();
        if (sink == null) {
            LOGGER.debug("[GATEWAY] LOGIN_C2S frame ({}B) from {} — no login bridge registered (T5 登录中继配对)",
                    payload.readableBytes(), remote());
            return;
        }
        try {
            sink.accept(this, payload.retainedDuplicate());
        } catch (Throwable t) {
            LOGGER.error("[GATEWAY] login sink failed for {}", remote(), t);
        }
    }

    // ==================== 玩家附着 ====================

    /**
     * 玩家会话附着（登录桥登录完成 / 续流握手内部调用）。
     *
     * @param reported 握手上报的玩家状态（登录桥路径可为 null）
     * @return 附着后的会话；已被其他 UUID 占用返回 null
     */
    public GatewayPlayerSession attachPlayer(UUID playerId, boolean resume, long epoch, PlayerStateReport reported) {
        if (playerId == null) {
            return null;
        }
        GatewayPlayerSession existing = playerSession;
        if (existing != null) {
            if (existing.playerId().equals(playerId)) {
                return existing;
            }
            LOGGER.warn("[GATEWAY] attachPlayer({}) rejected — channel already attached to {}",
                    playerId, existing.playerId());
            return null;
        }
        GatewayPlayerSession session = new GatewayPlayerSession(playerId, this, resume, epoch, reported);
        playerSession = session;
        server.registry().register(session);
        return session;
    }

    /** 会话被注册表移除时解绑（身份守卫：仅当仍指向该会话）。 */
    void detachPlayer(GatewayPlayerSession session) {
        if (playerSession == session) {
            playerSession = null;
        }
    }

    // ==================== 帧收发 ====================

    private void sendFrame(ControlFrameType type, ByteBuf payload) {
        Channel ch = channel;
        if (ch == null || !ch.isActive() || closed.get()) {
            payload.release();
            LOGGER.debug("[GATEWAY] frame {} dropped (channel not open)", type);
            return;
        }
        ch.writeAndFlush(ControlFrameCodec.encodeFrame(type, payload));
        payload.release();
    }

    /** 握手接受后装 ZSTD（复用现有编解码器；仅本网关管道新挂载）。 */
    public void installZstd(int threshold, int level) {
        Channel ch = channel;
        if (ch == null || !zstdInstalled.compareAndSet(false, true)) {
            return;
        }
        ch.pipeline().addBefore(DECODER_NAME, ZSTD_DECODER_NAME, new ZstdContextDecoder(threshold, true, false, true));
        ch.pipeline().addBefore(DECODER_NAME, ZSTD_ENCODER_NAME, new SkipAwareZstdEncoder(threshold, level, false));
        LOGGER.info("[GATEWAY] ZSTD installed for {} (threshold={}, level={})", remote(), threshold, level);
    }

    /** 幂等关闭：注销玩家会话（若附着）→ 关 channel → 服务端注销连接。 */
    public void close(String reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        state.set(State.CLOSED);
        GatewayPlayerSession session = playerSession;
        if (session != null) {
            server.registry().remove(session); // 清理 per-player 表 + 解绑
        }
        Channel ch = channel;
        if (ch != null) {
            ch.close();
        }
        server.onConnectionClosed(this);
        LOGGER.info("[GATEWAY] connection closed from {} (reason={})", remote(), reason);
    }

    public boolean isOpen() {
        Channel ch = channel;
        return !closed.get() && ch != null && ch.isActive();
    }

    // ==================== 状态查询 ====================

    public State state() {
        return state.get();
    }

    public GatewayServer server() {
        return server;
    }

    public Channel channel() {
        return channel;
    }

    public GatewayPlayerSession playerSession() {
        return playerSession;
    }

    public HandshakeCodec.ClientRequestOptions handshakeOptions() {
        return handshakeOptions;
    }

    public HandshakeStateTail.C2S stateTail() {
        return stateTail;
    }

    public boolean resumeAccepted() {
        return resumeAccepted;
    }

    public long resumeEpoch() {
        return resumeEpoch;
    }

    public long s2cFramesSent() {
        return s2cFramesSent.get();
    }

    public long c2sFramesReceived() {
        return c2sFramesReceived.get();
    }

    public long loginFramesReceived() {
        return loginFramesReceived.get();
    }

    public long configFramesReceived() {
        return configFramesReceived.get();
    }

    /** 远程地址（embedded 测试通道无地址 → "embedded"）。 */
    public String remote() {
        Channel ch = channel;
        if (ch == null) {
            return "unbound";
        }
        java.net.SocketAddress addr = ch.remoteAddress();
        return addr != null ? addr.toString() : "embedded";
    }

    private String playerName() {
        GatewayPlayerSession session = playerSession;
        return session != null ? session.playerId().toString() : "(unattached)";
    }

    // ==================== 内部 ====================

    private void onChannelActive(ChannelHandlerContext ctx) {
        channel = ctx.channel();
        LOGGER.info("[GATEWAY] TCP connection accepted from {}", remote());
    }

    private void onChannelInactive() {
        if (!closed.get()) {
            close("peer closed");
        }
    }

    private void onException(Throwable cause) {
        if (closed.get()) {
            return;
        }
        LOGGER.error("[GATEWAY] connection error from {}", remote(), cause);
        close("error: " + cause);
    }

    /** 帧拆分器：累积缓冲 → 完整帧（数据不足不消费；非法帧抛给 exceptionCaught）。 */
    static final class FrameDecoder extends ByteToMessageDecoder {
        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            while (in.isReadable()) {
                try {
                    ControlFrameCodec.Frame frame = ControlFrameCodec.tryDecodeFrame(in);
                    if (frame == null) {
                        break;
                    }
                    out.add(frame);
                } catch (IllegalArgumentException e) {
                    ctx.fireExceptionCaught(e);
                    break;
                }
            }
        }
    }

    static final class GatewayChannelHandler extends SimpleChannelInboundHandler<ControlFrameCodec.Frame> {

        private final GatewayChannel channel;

        GatewayChannelHandler(GatewayChannel channel) {
            this.channel = channel;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            channel.onChannelActive(ctx);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            channel.onChannelInactive();
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ControlFrameCodec.Frame frame) {
            try {
                // review-fix: state 门控——非 ACTIVE 只接受 HANDSHAKE_C2S/PING/HEARTBEAT，其余丢弃
                State st = channel.state();
                if (st != State.ACTIVE
                        && frame.type() != ControlFrameType.HANDSHAKE_C2S
                        && frame.type() != ControlFrameType.PING
                        && frame.type() != ControlFrameType.HEARTBEAT) {
                    LOGGER.warn("[GATEWAY] dropping inbound {} from {} (state={})", frame.type(), channel.remote(), st);
                    return;
                }
                switch (frame.type()) {
                    case HANDSHAKE_C2S -> channel.handleHandshake(frame.payload());
                    case PACKET_C2S -> channel.handleC2SPayload(frame.payload());
                    case LOGIN_C2S -> channel.handleLoginPayload(frame.payload());
                    case CONFIG_C2S -> channel.handleConfigPayload(frame.payload());
                    case PING -> {
                        // 心跳探测应答（主控 → 客户端 PING 的对称端）
                        ctx.writeAndFlush(ControlFrameCodec.encodeFrame(ControlFrameType.PONG, frame.payload()));
                    }
                    case HEARTBEAT -> {
                        // T8：心跳回显（客户端迁移引擎存活判定；与 PING→PONG 对称）
                        ctx.writeAndFlush(ControlFrameCodec.encodeFrame(
                                ControlFrameType.HEARTBEAT, io.netty.buffer.Unpooled.EMPTY_BUFFER));
                    }
                    case HANDSHAKE_S2C, PACKET_S2C, AGGREGATED, PONG, LOGIN_S2C, CONFIG_S2C ->
                            LOGGER.warn("[GATEWAY] unexpected inbound control frame {} from {}",
                                    frame.type(), channel.remote());
                }
            } finally {
                frame.payload().release();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            channel.onException(cause);
        }
    }
}
