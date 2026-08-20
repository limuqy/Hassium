package io.github.limuqy.mc.hassium.network.gateway;

import io.github.limuqy.mc.hassium.network.core.outbound.HandshakeCodec;
import io.github.limuqy.mc.hassium.network.core.outbound.ChunkApplyAck;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * 主控侧网关接入服务（T11）：接受客户端 {@code NetworkCore} outbound 帧连接
 * （复用 T4 ControlFrameCodec/HandshakeCodec，common 包直接引用），把帧连接桥接为
 * UUID-keyed 玩家会话（登录桥/续流双路径，见 {@link GatewayChannel}）。
 *
 * <p><b>生命周期</b>：服务端平台在 MinecraftServer 启动/停止时
 * {@link #start}/{@link #stop}（接线点 = 平台 MixinMinecraftServer，与
 * DataPlaneUdpServer 同模式；监听端口建议取
 * {@code MasterCoreConfig.controlReachableEndpoints()[0].port()}，客户端
 * outbound 地址源为 T7/T8 迁移引擎）。停止时逐会话走
 * {@link GatewayPlayerRegistry} 完整清理（T3：removePlayer 一键清空）。
 *
 * <p><b>缝</b>：
 * <ul>
 *   <li>{@link #setInfoProvider}：S2C 握手响应服务端字段（压缩/种子/SeedGen/UDP 端点表）。</li>
 *   <li>{@link #setLoginSink}：LOGIN_C2S 帧（T5 帧类型 9）→ 登录桥（登录完成后
 *       {@link GatewayChannel#attachPlayer} 附着会话）。</li>
 *   <li>{@link #registry()}：玩家会话表；平台在其上挂 per-player 清理钩子
 *       （{@code PlayerCompressionTracker.removePlayer}）。</li>
 * </ul>
 *
 * <p><b>线程模型</b>：accept/帧处理在 Netty event loop；S2C 发送任意线程；
 * start/stop 任意线程（幂等）。
 */
public final class GatewayServer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/GatewayServer");

    private static final int MAX_CONNECTIONS = 256; // review-fix: 接入连接数上限
    private static final long READ_IDLE_SECONDS = 10; // review-fix: readIdle 超时（秒）

    private static final GatewayServer INSTANCE = new GatewayServer();

    public static GatewayServer getInstance() {
        return INSTANCE;
    }

    private final ConcurrentHashMap<Channel, GatewayChannel> connections = new ConcurrentHashMap<>();
    private final GatewayPlayerRegistry registry = new GatewayPlayerRegistry();
    private final AtomicLong s2cFramesTotal = new AtomicLong();
    private final AtomicLong c2sFramesTotal = new AtomicLong();
    private final AtomicLong loginFramesTotal = new AtomicLong();
    private final AtomicLong configFramesTotal = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<GatewayServerInfoProvider> infoProvider =
            new AtomicReference<>((channel, request) -> GatewayServerInfoProvider.acceptDefaults());

    private volatile EventLoopGroup bossGroup;
    private volatile EventLoopGroup workerGroup;
    private volatile Channel serverChannel;
    private volatile LoginPayloadSink loginSink;
    private volatile int zstdThreshold = 256;
    private volatile int zstdLevel = 3;
    /** D-M2: 可选握手鉴权 token（master.authToken；默认空 = 不鉴权）。 */
    private volatile String authToken = "";
    private volatile BiConsumer<java.util.UUID, ChunkApplyAck> chunkApplyAckSink;
    /** Session-aware ACK sink used by platform wiring to reject ACKs from replaced sessions. */
    private volatile BiConsumer<GatewayPlayerSession, ChunkApplyAck> chunkApplyAckSessionSink;

    private GatewayServer() {
    }

    // ==================== 生命周期 ====================

    /** 绑定监听（默认 127.0.0.1 回环；T5-M3 安全默认）。异步 bind；失败经日志报告并复位 running。 */
    public void start(int port) {
        start("127.0.0.1", port);
    }

    /** 绑定监听（指定 bind host）。异步 bind；失败经日志报告并复位 running。 */
    public void start(String bindHost, int port) {
        if (!running.compareAndSet(false, true)) {
            LOGGER.warn("[GATEWAY] already running ({}:{})", bindHost, port);
            return;
        }
        NioEventLoopGroup boss = new NioEventLoopGroup(1, r -> {
            Thread t = new Thread(r, "Hassium-GatewayServer-Boss");
            t.setDaemon(true);
            return t;
        });
        NioEventLoopGroup worker = new NioEventLoopGroup(0, r -> {
            Thread t = new Thread(r, "Hassium-GatewayServer-Worker");
            t.setDaemon(true);
            return t;
        });
        bossGroup = boss;
        workerGroup = worker;
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        // review-fix: 连接数上限——超限直接关闭，不进入连接表
                        if (connections.size() >= MAX_CONNECTIONS) {
                            LOGGER.warn("[GATEWAY] connection limit {} reached — rejecting {}", MAX_CONNECTIONS, ch.remoteAddress());
                            ch.close();
                            return;
                        }
                        GatewayChannel gc = new GatewayChannel(GatewayServer.this, ch);
                        connections.put(ch, gc);
                        ch.pipeline().addLast("frameDecoder", new GatewayChannel.FrameDecoder());
                        // review-fix: readIdle 10s 关闭（IdleStateHandler 置于 frameDecoder 之后）
                        ch.pipeline().addLast("idleState", new IdleStateHandler(0, READ_IDLE_SECONDS, 0, TimeUnit.SECONDS));
                        ch.pipeline().addLast("idleGuard", new ChannelInboundHandlerAdapter() {
                            @Override
                            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                                if (evt instanceof IdleStateEvent && ((IdleStateEvent) evt).state() == IdleState.READER_IDLE) {
                                    LOGGER.warn("[GATEWAY] read idle {}s from {} — closing", READ_IDLE_SECONDS, ctx.channel().remoteAddress());
                                    gc.close("read idle");
                                } else {
                                    ctx.fireUserEventTriggered(evt);
                                }
                            }
                        });
                        ch.pipeline().addLast("gatewayInbound", new GatewayChannel.GatewayChannelHandler(gc));
                    }
                });
        bootstrap.bind(new InetSocketAddress(bindHost, port)).addListener(future -> {
            if (!future.isSuccess()) {
                LOGGER.error("[GATEWAY] bind failed on {}:{}", bindHost, port, future.cause());
                running.set(false);
                shutdownGroups(boss, worker);
                return;
            }
            Channel boundChannel = ((ChannelFuture) future).channel();
            // review-fix: T5-95 stop() 与异步 bind 竞态——成功回调内复查 running：
            // stop 已执行（group 已关）时立即关闭新 channel，避免 channel/group 泄漏
            if (!running.get()) {
                boundChannel.close();
                shutdownGroups(boss, worker);
                LOGGER.info("[GATEWAY] bind completed after stop — closed bound channel");
                return;
            }
            serverChannel = boundChannel;
            // review-fix: T5-95 复查残留窗口——stop 恰在检查与赋值之间执行时再核一次
            if (!running.get()) {
                serverChannel = null;
                boundChannel.close();
                shutdownGroups(boss, worker);
                LOGGER.info("[GATEWAY] stop raced bind completion — closed bound channel");
                return;
            }
            LOGGER.info("[GATEWAY] listening on {}:{}", bindHost, port);
        });
    }

    /** 停机：关监听 + 逐连接关闭（完整玩家会话清理）+ 释放 event loop。幂等。 */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        Channel sc = serverChannel;
        if (sc != null) {
            sc.close();
        }
        serverChannel = null;
        for (GatewayChannel gc : List.copyOf(connections.values())) {
            gc.close("server stop");
        }
        connections.clear();
        registry.clear();
        EventLoopGroup boss = bossGroup;
        EventLoopGroup worker = workerGroup;
        bossGroup = null;
        workerGroup = null;
        shutdownGroups(boss, worker);
        LOGGER.info("[GATEWAY] stopped");
    }

    public boolean isRunning() {
        return running.get();
    }

    /** 监听 channel（异步 bind 完成前为 null；测试取实际绑定端口用）。 */
    public Channel serverChannel() {
        return serverChannel;
    }

    private static void shutdownGroups(EventLoopGroup... groups) {
        for (EventLoopGroup g : groups) {
            if (g != null) {
                g.shutdownGracefully(0, 5, TimeUnit.SECONDS);
            }
        }
    }

    // ==================== 缝 ====================

    /** S2C 握手响应信息提供缝（平台注入真实服务端字段）。 */
    public void setInfoProvider(GatewayServerInfoProvider provider) {
        infoProvider.set(provider != null ? provider : (c, r) -> GatewayServerInfoProvider.acceptDefaults());
    }

    public GatewayServerInfoProvider infoProvider() {
        return infoProvider.get();
    }

    /** LOGIN_C2S 帧登录桥缝（T5 登录中继配对；登录完成后经通道附着会话）。 */
    public void setLoginSink(LoginPayloadSink sink) {
        loginSink = sink;
    }

    public LoginPayloadSink loginSink() {
        return loginSink;
    }

    /**
     * 兼容旧接线的 UUID ACK 接收缝。新平台接线必须使用
     * {@link #setChunkApplyAckSessionSink(BiConsumer)}，以免旧会话的已排队 ACK 命中重置后的 id。
     */
    public void setChunkApplyAckSink(BiConsumer<java.util.UUID, ChunkApplyAck> sink) {
        chunkApplyAckSink = sink;
    }

    /**
     * 注册携带不可变会话身份的 ACK 接收缝；回调在网关 Netty event loop 执行。
     * 平台在切换到服务端线程后必须验证该会话仍是 registry 当前身份。
     */
    public void setChunkApplyAckSessionSink(BiConsumer<GatewayPlayerSession, ChunkApplyAck> sink) {
        chunkApplyAckSessionSink = sink;
    }

    void onChunkApplyAck(GatewayPlayerSession session, ChunkApplyAck ack) {
        BiConsumer<GatewayPlayerSession, ChunkApplyAck> sessionSink = chunkApplyAckSessionSink;
        if (sessionSink != null) {
            sessionSink.accept(session, ack);
            return;
        }
        BiConsumer<java.util.UUID, ChunkApplyAck> sink = chunkApplyAckSink;
        if (sink != null) {
            sink.accept(session.playerId(), ack);
        }
    }

    /**
     * ZSTD 阈值/等级（globalCompressionAccepted 时安装）。平台必须与客户端侧
     * 配置同源（客户端 = HassiumConfigService.getGlobalCompressionThreshold/Level）。
     */
    public void setZstd(int threshold, int level) {
        zstdThreshold = threshold;
        zstdLevel = level;
    }

    public int zstdThreshold() {
        return zstdThreshold;
    }

    public int zstdLevel() {
        return zstdLevel;
    }

    /** D-M2: 配置握手鉴权 token（平台接线传入 master.authToken；空 = 不鉴权）。 */
    public void setAuthToken(String authToken) {
        this.authToken = authToken != null ? authToken : "";
    }

    public String authToken() {
        return authToken;
    }

    public boolean isAuthEnabled() {
        return !authToken.isEmpty();
    }

    /** 玩家会话注册表（UUID-keyed；平台挂 per-player 清理钩子）。 */
    public GatewayPlayerRegistry registry() {
        return registry;
    }

    // ==================== 计数（可验证） ====================

    public long s2cFramesTotal() {
        return s2cFramesTotal.get();
    }

    public long c2sFramesTotal() {
        return c2sFramesTotal.get();
    }

    public long loginFramesTotal() {
        return loginFramesTotal.get();
    }

    public long configFramesTotal() {
        return configFramesTotal.get();
    }

    public int connectionCount() {
        return connections.size();
    }

    void onS2CFrame() {
        s2cFramesTotal.incrementAndGet();
    }

    void onC2SFrame() {
        c2sFramesTotal.incrementAndGet();
    }

    void onLoginFrame() {
        loginFramesTotal.incrementAndGet();
    }

    void onConfigFrame() {
        configFramesTotal.incrementAndGet();
    }

    void onConnectionClosed(GatewayChannel gc) {
        Channel ch = gc.channel();
        if (ch != null) {
            connections.remove(ch);
        }
    }
}
