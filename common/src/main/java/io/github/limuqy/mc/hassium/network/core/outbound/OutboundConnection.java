package io.github.limuqy.mc.hassium.network.core.outbound;

import io.github.limuqy.mc.hassium.network.HandshakeStateTail;
import io.github.limuqy.mc.hassium.network.ZstdContextDecoder;
import io.github.limuqy.mc.hassium.network.SkipAwareZstdEncoder;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 网关 outbound 连接：到主控的 TCP 控制面 + UDP 数据面（bulk 区块）。
 *
 * <p><b>控制面帧协议</b>：{@link ControlFrameCodec}（varint 帧长 + type + payload），
 * 纯 Netty 零 MC 依赖。管道（注册序）：
 * <pre>
 *   [zstdDecoder(握手后)] [zstdEncoder(握手后)] [frameDecoder] [inboundHandler]
 * </pre>
 * 入站：zstdDecoder → frameDecoder → handler；出站（handler → 反向）：frameDecoder(仅入站跳过)
 * → zstdEncoder → socket。ZSTD 复用 {@link ZstdContextDecoder}/{@link SkipAwareZstdEncoder}
 * （{@link #installZstd}），不改其原挂载（原挂载 = 原版 Connection 管道，T6 前保留）。
 *
 * <p><b>握手流</b>：channelActive → 发 HANDSHAKE_C2S（明文）→ listener.onOpen；
 * HANDSHAKE_S2C → 解码 → accepted ? onHandshakeAccepted（随后装 ZSTD）: onHandshakeRejected。
 *
 * <p><b>UDP 数据面</b>：握手尾部带数据面时经 {@link UdpDataPlane} 登记启动；bulk 区块接收走
 * 现有 DataPlaneClientBundle（ChunkDispatcher 缝，T5 指向 dispatchS2C）。
 *
 * <p><b>线程模型</b>：connect 由主线程调用；onOpen/onHandshakeAccepted/onError 在 Netty
 * event loop 回调（NetworkCore 状态机为原子操作，线程安全）。
 *
 * <p>测试缝：{@link #openEmbedded} 用 EmbeddedChannel 跑完整帧/握手流（同包测试）。
 */
public final class OutboundConnection {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/OutboundGateway");

    /** 握手结果回调（NetworkCore 实现）。 */
    public interface Listener {
        /** 控制面已建立、握手请求已发出（event loop 线程）。 */
        void onOpen(OutboundConnection connection);

        /** 握手接受（event loop 线程）；此时可装 ZSTD、启动 UDP 数据面。 */
        void onHandshakeAccepted(HandshakeCodec.ServerResponse response);

        /**
         * 握手接受 + T7 S2C 尾续流结果（resumeAccepted；T8 迁移引擎续流发起）。
         * 默认实现转发单参版本——既有监听器不受影响。
         */
        default void onHandshakeAccepted(HandshakeCodec.ServerResponse response, boolean resumeAccepted) {
            onHandshakeAccepted(response);
        }

        /** 握手被主控拒绝（线格式无原因字段）。 */
        void onHandshakeRejected(String reason);

        /** 连接/协议错误（event loop 线程）。 */
        void onError(Throwable cause);
    }

    private static final String DECODER_NAME = "frameDecoder";
    private static final String ZSTD_DECODER_NAME = "zstdDecoder";
    private static final String ZSTD_ENCODER_NAME = "zstdEncoder";
    private static final String HANDLER_NAME = "gatewayInbound";
    private static final String READ_TIMEOUT_NAME = "readTimeout";

    private final Listener listener;
    private final HandshakeCodec.ClientRequestOptions handshakeOptions;
    private final HandshakeStateTail.C2S handshakeTail;
    private final EventLoopGroup ownedGroup;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean zstdInstalled = new AtomicBoolean(false);
    private final AtomicBoolean readTimeoutInstalled = new AtomicBoolean(false);

    private volatile Channel channel;
    private volatile java.util.function.Consumer<ByteBuf> s2cPayloadConsumer;
    private volatile java.util.function.Consumer<ByteBuf> loginS2cPayloadConsumer;
    private volatile java.util.function.Consumer<ByteBuf> configS2cPayloadConsumer;
    private volatile java.lang.Runnable inboundActivityListener;
    private volatile boolean lastResumeAccepted;
    /** Netty 读超时（N2；0=未启用）。 */
    private volatile long readTimeoutMs;
    /** 读超时处理器（调用方映射到迁移 fault 路径；禁止直降 onError→IDLE，契约风险 5）。 */
    private volatile java.lang.Runnable readTimeoutHandler;
    /** T0b 诊断：控制通道 active 时刻（wall ms；握手 accepted 延迟基准）。 */
    private volatile long channelActiveAtMs;

    /** 连接级握手鉴权 token（M1 bootstrap 下发；null = 未指定，sendHandshake 回退 config）。 */
    private final String authToken;

    private OutboundConnection(EventLoopGroup ownedGroup,
                               HandshakeCodec.ClientRequestOptions handshakeOptions,
                               HandshakeStateTail.C2S handshakeTail,
                               String authToken,
                               Listener listener) {
        this.ownedGroup = ownedGroup;
        this.handshakeOptions = handshakeOptions;
        this.handshakeTail = handshakeTail;
        this.authToken = authToken;
        this.listener = listener;
    }
    public static OutboundConnection connect(String host, int port,
                                             HandshakeCodec.ClientRequestOptions options,
                                             Listener listener) {
        return connect(host, port, options, listener, null, null);
    }

    /**
     * 异步建立到主控的 TCP 控制面连接，握手请求携带 T7 续流状态尾
     * （T8 迁移引擎续流发起；tail 为 null 时不携带）。
     */
    public static OutboundConnection connect(String host, int port,
                                             HandshakeCodec.ClientRequestOptions options,
                                             Listener listener,
                                             HandshakeStateTail.C2S tail) {
        return connect(host, port, options, listener, tail, null);
    }

    /**
     * 异步建立到主控的 TCP 控制面连接，握手请求携带 T7 续流状态尾 + 连接级鉴权 token
     * （M1 bootstrap 下发 token；authToken 为 null 时 sendHandshake 回退
     * {@link io.github.limuqy.mc.hassium.network.core.NetworkCore#bootstrapAuthToken()}，
     * 空串 = 显式不鉴权，线格式不追加 token 字节）。
     */
    public static OutboundConnection connect(String host, int port,
                                             HandshakeCodec.ClientRequestOptions options,
                                             Listener listener,
                                             HandshakeStateTail.C2S tail,
                                             String authToken) {
        NioEventLoopGroup group = new NioEventLoopGroup(1, r -> {
            Thread t = new Thread(r, "Hassium-GatewayOutbound");
            t.setDaemon(true);
            return t;
        });
        OutboundConnection conn = new OutboundConnection(group, options, tail, authToken, listener);
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ch.pipeline().addLast(DECODER_NAME, new FrameDecoder());
                        ch.pipeline().addLast(HANDLER_NAME, new InboundHandler(conn));
                    }
                });
        bootstrap.connect(new InetSocketAddress(host, port)).addListener(future -> {
            if (!future.isSuccess()) {
                Throwable cause = future.cause() != null ? future.cause()
                        : new IOException("connect failed: " + host + ":" + port);
                conn.listener.onError(cause);
            }
        });
        return conn;
    }

    /**
     * 测试缝：EmbeddedChannel 跑完整帧/握手流（core 包测试用）。
     * channelActive 在构造期触发——自动发握手并回调 onOpen，随后可直接 writeInbound 帧。
     */
    public static OutboundConnection openEmbedded(HandshakeCodec.ClientRequestOptions options, Listener listener) {
        return openEmbedded(options, listener, null);
    }

    /** 测试缝：EmbeddedChannel 变体，握手请求携带续流状态尾（T8 预热/迁移测试）。 */
    public static OutboundConnection openEmbedded(HandshakeCodec.ClientRequestOptions options,
                                                  Listener listener,
                                                  HandshakeStateTail.C2S tail) {
        OutboundConnection conn = new OutboundConnection(null, options, tail, null, listener);
        EmbeddedChannel embedded = new EmbeddedChannel(new FrameDecoder(), new InboundHandler(conn));
        conn.channel = embedded;
        return conn;
    }

    /** 入站 S2C payload 消费者（T5 注册：解码为原版 Packet 后交 NetworkCore#dispatchS2C）。 */
    public void setS2CPayloadConsumer(java.util.function.Consumer<ByteBuf> consumer) {
        this.s2cPayloadConsumer = consumer;
    }

    /** 入站登录 S2C payload 消费者（T5 注册：登录桥接，与 {@link #s2cPayloadConsumer} 独立解码协议）。 */
    public void setLoginS2CPayloadConsumer(java.util.function.Consumer<ByteBuf> consumer) {
        this.loginS2cPayloadConsumer = consumer;
    }

    /** 入站配置阶段 S2C payload 消费者（T10 注册：CONFIG_S2C 帧 → NetworkCore#onConfigS2CPayload）。 */
    public void setConfigS2CPayloadConsumer(java.util.function.Consumer<ByteBuf> consumer) {
        this.configS2cPayloadConsumer = consumer;
    }

    /**
     * 最近一次握手响应的续流接受标记（T7 S2C 尾；未握手/拒绝 → false）。
     * 与 {@link Listener#onHandshakeAccepted(HandshakeCodec.ServerResponse, boolean)} 同源，供事后查询。
     */
    public boolean lastResumeAccepted() {
        return lastResumeAccepted;
    }

    /** 构造时携带的握手状态尾（T6 位置回退快照源：续流握手上报位置；未指定 → null）。 */
    public HandshakeStateTail.C2S handshakeTail() {
        return handshakeTail;
    }

    /** 握手接受后装 ZSTD（复用现有编解码器；仅本网关管道新挂载）。阈值/等级与现有全局压缩同源。 */
    public void installZstd(int threshold, int level) {
        Channel ch = channel;
        if (ch == null || !zstdInstalled.compareAndSet(false, true)) {
            return;
        }
        ch.pipeline().addBefore(DECODER_NAME, ZSTD_DECODER_NAME, new ZstdContextDecoder(threshold, true, false, true));
        ch.pipeline().addBefore(DECODER_NAME, ZSTD_ENCODER_NAME, new SkipAwareZstdEncoder(threshold, level, false));
        LOGGER.info("Hassium: Gateway outbound ZSTD installed (threshold={}, level={})", threshold, level);
    }

    /**
     * 发送 C2S 握手请求（channelActive 自动发送；显式调用用于重发）。
     * 构造时若指定续流状态尾（T8），请求一并携带。
     * D-M2: 握手帧携带鉴权 token——连接级（M1 bootstrap 下发）优先，
     * 缺省回退 {@link io.github.limuqy.mc.hassium.network.core.NetworkCore#bootstrapAuthToken()}
     * （gateway_info；空 = 不鉴权）。
     */
    public void sendHandshake(HandshakeCodec.ClientRequestOptions options) {
        String token = authToken != null ? authToken
                : io.github.limuqy.mc.hassium.network.core.NetworkCore.getInstance().bootstrapAuthToken();
        sendFrame(ControlFrameType.HANDSHAKE_C2S, HandshakeCodec.encodeClientRequest(options, handshakeTail, token));
    }

    /**
     * 发送应用层心跳（HEARTBEAT 帧，空 payload；T8 迁移引擎故障检测）。
     * 主控侧 GatewayChannel 回显（与 PING→PONG 对称）。
     */
    public void sendHeartbeat() {
        sendFrame(ControlFrameType.HEARTBEAT, Unpooled.EMPTY_BUFFER);
    }

    /**
     * 发送 authoritative apply ACK（客户端最终落地逻辑由调用方决定）。
     * payload 所有权在本方法内创建并由 {@link #sendFrame} 释放。
     */
    public void sendChunkApplyAck(ChunkApplyAck ack) {
        java.util.Objects.requireNonNull(ack, "ack");
        ByteBuf payload = Unpooled.buffer(1 + ack.size() * Long.BYTES);
        ack.encode(payload);
        sendFrame(ControlFrameType.CHUNK_APPLY_ACK, payload);
    }

    /**
     * 注册入站活动监听（任意入站帧触发；T8 心跳超时监测的 liveness 信号）。
     * 幂等（重复注册覆盖）。
     */
    public void setInboundActivityListener(java.lang.Runnable listener) {
        this.inboundActivityListener = listener;
    }

    /**
     * 启用 Netty 读超时（N2 快速失效；幂等，重复调用覆盖参数）。
     * <p>
     * 入站静默超过 {@code idleMs}（任意帧均重置计时）→ 在 event loop 触发
     * {@code onReadTimeout}。调用方必须把读超时映射到<b>迁移 fault 路径</b>
     * （Sink.onFault → 迁移/快速失败），<b>禁止</b>直降 onError → IDLE——
     * 否则快速失效识别会把玩家踢下线而非迁移（契约风险 5）。
     * <p>
     * 连接尚未 active 时仅记录参数，channelActive 后自动安装。
     */
    public void enableReadTimeout(long idleMs, java.lang.Runnable onReadTimeout) {
        if (idleMs <= 0 || onReadTimeout == null) {
            return;
        }
        this.readTimeoutMs = idleMs;
        this.readTimeoutHandler = onReadTimeout;
        installReadTimeoutIfPossible();
    }

    private void installReadTimeoutIfPossible() {
        long idleMs = readTimeoutMs;
        if (idleMs <= 0 || readTimeoutHandler == null) {
            return;
        }
        Channel ch = channel;
        if (ch == null || !ch.isActive()) {
            return; // onChannelActive 时再装
        }
        // review-fix: T1-69 热更新——已安装时移除重建（否则新阈值被忽略，
        // B2 配置热更新场景失效）；pipeline 增删线程安全（Netty 内部调度到 event loop）
        if (readTimeoutInstalled.get()) {
            ch.pipeline().remove(READ_TIMEOUT_NAME);
            readTimeoutInstalled.set(false);
        }
        if (readTimeoutInstalled.compareAndSet(false, true)) {
            ch.pipeline().addFirst(READ_TIMEOUT_NAME, new IdleStateHandler(0, 0, idleMs, TimeUnit.MILLISECONDS));
            LOGGER.debug("Hassium: Gateway outbound read timeout installed ({}ms)", idleMs);
        }
    }

    /**
     * 发送 C2S 包 payload（routeC2S 经编码器产出；本方法接管 payload 所有权并释放）。
     */
    public void sendC2S(ByteBuf payload) {
        sendFrame(ControlFrameType.PACKET_C2S, payload);
    }

    /**
     * 发送登录阶段 C2S 包 payload（登录桥接，T5；本方法接管 payload 所有权并释放）。
     */
    public void sendLoginC2S(ByteBuf payload) {
        sendFrame(ControlFrameType.LOGIN_C2S, payload);
    }

    /**
     * 发送配置阶段 C2S 包 payload（config 中继，T10；本方法接管 payload 所有权并释放）。
     */
    public void sendConfigC2S(ByteBuf payload) {
        sendFrame(ControlFrameType.CONFIG_C2S, payload);
    }

    /** 幂等关闭：关 channel + 释放自有 event loop。 */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Channel ch = channel;
        if (ch != null) {
            ch.close();
        }
        EventLoopGroup group = ownedGroup;
        if (group != null) {
            group.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        }
        LOGGER.info("Hassium: Gateway outbound closed");
    }

    public boolean isOpen() {
        Channel ch = channel;
        return !closed.get() && ch != null && ch.isActive();
    }

    public Channel channel() {
        return channel;
    }

    // ---- 内部 ----

    private void sendFrame(ControlFrameType type, ByteBuf payload) {
        Channel ch = channel;
        if (ch == null || !ch.isActive()) {
            payload.release();
            LOGGER.debug("Hassium: outbound frame {} dropped (channel not open)", type);
            return;
        }
        ch.writeAndFlush(ControlFrameCodec.encodeFrame(type, payload));
        payload.release();
    }

    private void onChannelActive(ChannelHandlerContext ctx) {
        channel = ctx.channel();
        channelActiveAtMs = System.currentTimeMillis();
        LOGGER.info("Hassium: Gateway outbound control channel active ({})", ctx.channel().remoteAddress());
        installReadTimeoutIfPossible();
        if (handshakeOptions != null) {
            sendHandshake(handshakeOptions);
        }
        listener.onOpen(this);
    }

    /** Netty 读超时（READER_IDLE）：转发注册的 fault 处理器（不在本层触发 onError）。 */
    private void onReadTimeout() {
        java.lang.Runnable handler = readTimeoutHandler;
        if (handler == null) {
            LOGGER.warn("Hassium: Gateway outbound read timeout, no handler registered");
            return;
        }
        try {
            handler.run();
        } catch (Throwable t) {
            LOGGER.error("Hassium: Gateway read-timeout handler failed", t);
        }
    }

    private void onHandshakeFrame(ByteBuf payload) {
        HandshakeCodec.ServerResponse response = HandshakeCodec.decodeServerResponse(payload);
        // T7 S2C 尾（resumeAccepted）：解码后保留尾字节不消费（GatewayServerTest 依赖尾保留）
        boolean resumeAccepted = HandshakeStateTail.readS2C(payload).resumeAccepted();
        this.lastResumeAccepted = resumeAccepted;
        if (response.accepted()) {
            long hsDeltaMs = channelActiveAtMs > 0 ? System.currentTimeMillis() - channelActiveAtMs : -1L;
            LOGGER.info("Hassium: Gateway handshake accepted (proto={}, globalCompression={}, compactHeader={}, udp={}, resume={}){}",
                    response.protocolVersion(), response.globalCompressionAccepted(),
                    response.compactHeaderAccepted(),
                    response.udpTail() != null && response.udpTail().hasUdpDataplane(),
                    resumeAccepted,
                    hsDeltaMs >= 0 ? " (+" + hsDeltaMs + "ms since channel active)" : "");
            listener.onHandshakeAccepted(response, resumeAccepted);
        } else {
            LOGGER.warn("Hassium: Gateway handshake rejected (proto={})", response.protocolVersion());
            listener.onHandshakeRejected("master rejected handshake");
        }
    }

    private void onS2CPayload(ByteBuf payload) {
        java.util.function.Consumer<ByteBuf> consumer = s2cPayloadConsumer;
        if (consumer == null) {
            LOGGER.debug("Hassium: S2C payload received, no consumer registered yet (T5) — {} bytes",
                    payload.readableBytes());
            return;
        }
        consumer.accept(payload.retainedDuplicate());
    }

    private void onLoginS2CPayload(ByteBuf payload) {
        java.util.function.Consumer<ByteBuf> consumer = loginS2cPayloadConsumer;
        if (consumer == null) {
            LOGGER.debug("Hassium: LOGIN_S2C payload received, no login consumer registered yet (T5) — {} bytes",
                    payload.readableBytes());
            return;
        }
        consumer.accept(payload.retainedDuplicate());
    }

    private void onConfigS2CPayload(ByteBuf payload) {
        java.util.function.Consumer<ByteBuf> consumer = configS2cPayloadConsumer;
        if (consumer == null) {
            LOGGER.debug("Hassium: CONFIG_S2C payload received, no config consumer registered yet (T10) — {} bytes",
                    payload.readableBytes());
            return;
        }
        consumer.accept(payload.retainedDuplicate());
    }

    private void onPing(ChannelHandlerContext ctx, ByteBuf payload) {
        ctx.writeAndFlush(ControlFrameCodec.encodeFrame(ControlFrameType.PONG, payload));
    }

    private void onChannelInactive() {
        if (!closed.get()) {
            LOGGER.warn("Hassium: Gateway outbound control channel closed by peer");
            listener.onError(new IOException("outbound control channel closed by peer"));
        }
    }

    private void onException(Throwable cause) {
        if (closed.get()) {
            return;
        }
        LOGGER.error("Hassium: Gateway outbound error", cause);
        listener.onError(cause);
    }

    /** 帧拆分器：累积缓冲 → 完整帧（数据不足不消费；非法帧抛给 exceptionCaught）。 */
    private static final class FrameDecoder extends ByteToMessageDecoder {
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

    private static final class InboundHandler extends SimpleChannelInboundHandler<ControlFrameCodec.Frame> {

        private final OutboundConnection connection;

        InboundHandler(OutboundConnection connection) {
            this.connection = connection;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            connection.onChannelActive(ctx);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            connection.onChannelInactive();
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt instanceof IdleStateEvent idle && idle.state() == IdleState.READER_IDLE) {
                connection.onReadTimeout();
            } else {
                ctx.fireUserEventTriggered(evt);
            }
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ControlFrameCodec.Frame frame) {
            java.lang.Runnable activity = connection.inboundActivityListener;
            if (activity != null) {
                try {
                    activity.run();
                } catch (Throwable t) {
                    LOGGER.warn("Hassium: inbound activity listener failed", t);
                }
            }
            try {
                switch (frame.type()) {
                    case HANDSHAKE_S2C -> connection.onHandshakeFrame(frame.payload());
                    case PACKET_S2C -> connection.onS2CPayload(frame.payload());
                    case LOGIN_S2C -> connection.onLoginS2CPayload(frame.payload());
                    case CONFIG_S2C -> connection.onConfigS2CPayload(frame.payload());
                    case PING -> connection.onPing(ctx, frame.payload());
                    case HEARTBEAT -> {
                        // T7：迁移引擎存活判定输入（心跳定时器）
                    }
                    case PACKET_C2S, AGGREGATED, HANDSHAKE_C2S, LOGIN_C2S, CONFIG_C2S, PONG, CHUNK_APPLY_ACK ->
                            LOGGER.warn("Hassium: unexpected inbound control frame {}", frame.type());
                }
            } finally {
                frame.payload().release();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            connection.onException(cause);
        }
    }
}
