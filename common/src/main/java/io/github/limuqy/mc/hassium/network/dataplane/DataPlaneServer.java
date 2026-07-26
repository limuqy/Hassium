package io.github.limuqy.mc.hassium.network.dataplane;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.limuqy.mc.hassium.utils.DebugLogger;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Data Plane 服务端。
 * 管理多端口 accept、Bind 校验、per-player PlayerChannelBundle。
 */
public class DataPlaneServer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/DataPlaneServer");

    private static final Map<Integer, Channel> SERVER_CHANNELS = new LinkedHashMap<>();
    private static final ConcurrentHashMap<Channel, UUID> CHANNEL_TO_PLAYER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, PlayerChannelBundle> PLAYER_BUNDLES = new ConcurrentHashMap<>();
    private static volatile NioEventLoopGroup bossGroup;
    private static volatile NioEventLoopGroup workerGroup;
    private static volatile boolean bound = false;

    /**
     * 本次 bind 会话 token（16 字节 SecureRandom）。
     * Bind 校验 + HKDF 派生均用此值；握手 S2C 下发给客户端。
     * {@code null} 表示未 bind / 已 shutdown。
     */
    private static volatile byte[] sessionToken = null;

    /**
     * 运行时可覆盖的 bulk 路由模式（share ↔ exclusive）。
     * <p>
     * 为 null 时回退到 {@link DataPlanePoCConfig#BULK_ROUTE_MODE}（编译期常量默认 share），
     * 非空时由 {@link #setRuntimeMode(String)} 在线设置 —— 用于冒烟在不改 {@code static final}
     * 常量的前提下切换 exclusive 验证降级路径。冒烟结束必须 {@link #clearRuntimeMode()} 复位。
     */
    private static volatile String runtimeMode = null;

    /** 设置运行时 bulk 路由模式覆盖；传入 "share" / "exclusive"。传 null 等同 clearRuntimeMode。 */
    public static void setRuntimeMode(String mode) {
        runtimeMode = mode;
    }

    /** 清除运行时模式覆盖，回退到 {@link DataPlanePoCConfig#BULK_ROUTE_MODE}。 */
    public static void clearRuntimeMode() {
        runtimeMode = null;
    }

    /** 读取当前生效的 bulk 路由模式（运行时覆盖优先于常量默认）。 */
    public static String getRuntimeMode() {
        return runtimeMode != null ? runtimeMode : DataPlanePoCConfig.BULK_ROUTE_MODE;
    }

    /**
     * 主动关闭指定玩家的某条 Data 通道（按 1-based 端点序号 portIdx 匹配）。
     * <p>
     * 通道关闭后由 {@link BindHandshakeHandler#channelInactive} 自动从 bundle 移除，
     * 故 bundle.dataChannels.size() 之后会自动减 1，无需调用方手动 removeChannel。
     *
     * @return true = 找到并已触发关闭；false = 该玩家无 bundle 或无匹配 portIdx 的通道。
     */
    public static boolean killDataChannelByPortIdx(UUID playerId, int portIdx) {
        PlayerChannelBundle bundle = getBundle(playerId);
        if (bundle == null) return false;
        for (PlayerChannel pc : bundle.getDataChannels()) {
            if (pc != null && pc.portIdx == portIdx) {
                pc.close();
                return true;
            }
        }
        return false;
    }


    /** 绑定所有 PoC 数据端口 */
    public static synchronized void bind() {
        if (bound) return;
        if (!DataPlanePoCConfig.isEnabled()) {
            LOGGER.info("DataPlaneServer: disabled by config");
            return;
        }
        if (sessionToken == null) {
            byte[] tok = new byte[16];
            new SecureRandom().nextBytes(tok);
            sessionToken = tok;
            LOGGER.info("DataPlaneServer: generated session token");
        }
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(4);
        if (DataPlanePoCConfig.isDataplaneLogEnabled()) {
            DebugLogger.info(DebugLogger.LogType.DATAPLANE,
                    "DataPlaneServer: binding {} data port(s) {} mode={} primaryWeight={} degradeAfterDrops={}",
                    DataPlanePoCConfig.ENDPOINTS.length, DataPlanePoCConfig.endpointsSummary(),
                    DataPlanePoCConfig.BULK_ROUTE_MODE, DataPlanePoCConfig.PRIMARY_WEIGHT, DataPlanePoCConfig.DEGRADE_AFTER_DROPS);
        } else {
            LOGGER.info("DataPlaneServer: binding {} data port(s)...", DataPlanePoCConfig.ENDPOINTS.length);
        }
        for (int idx = 0; idx < DataPlanePoCConfig.ENDPOINTS.length; idx++) {
            DataPlanePoCConfig.Endpoint ep = DataPlanePoCConfig.ENDPOINTS[idx];
            int portIdx = idx + 1; // 1-based，客户端用同样序号派生密钥
            ServerBootstrap b = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new DataPlaneChannelInitializer(portIdx))
                .childOption(ChannelOption.TCP_NODELAY, true);
            try {
                Channel ch = b.bind(ep.bindHost, ep.bindPort).sync().channel();
                SERVER_CHANNELS.put(ep.bindPort, ch);
                LOGGER.info("DataPlaneServer: bound to {}:{} (weight={}, portIdx={})", ep.bindHost, ep.bindPort, ep.weight, portIdx);
            } catch (Exception e) {
                LOGGER.error("DataPlaneServer: failed to bind {}:{}", ep.bindHost, ep.bindPort, e);
            }
        }
        bound = !SERVER_CHANNELS.isEmpty();
        LOGGER.info("DataPlaneServer: {} port(s) active", SERVER_CHANNELS.size());
    }

    /** 关闭所有 Data 端口 */
    public static synchronized void shutdown() {
        if (!bound) return;
        LOGGER.info("DataPlaneServer: shutting down...");
        // 关闭所有玩家 bundle
        for (PlayerChannelBundle bundle : PLAYER_BUNDLES.values()) bundle.closeAll();
        PLAYER_BUNDLES.clear();
        CHANNEL_TO_PLAYER.clear();
        // 关闭所有 server socket
        for (Channel ch : SERVER_CHANNELS.values()) {
            if (ch.isOpen()) ch.close();
        }
        SERVER_CHANNELS.clear();
        // 关闭 event loop
        if (workerGroup != null) workerGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        if (bossGroup != null) bossGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        bound = false;
        LOGGER.info("DataPlaneServer: shutdown complete");
    }

    public static PlayerChannelBundle getOrCreateBundle(UUID playerId) {
        return PLAYER_BUNDLES.computeIfAbsent(playerId, k -> new PlayerChannelBundle());
    }

    public static PlayerChannelBundle getBundle(UUID playerId) {
        return PLAYER_BUNDLES.get(playerId);
    }

    public static void removeBundle(UUID playerId) {
        PlayerChannelBundle b = PLAYER_BUNDLES.remove(playerId);
        if (b != null) b.closeAll();
    }

    /** 关闭指定玩家所有 Data 通道并清理 bundle（主连接断开时调用） */
    public static void onPrimaryDisconnect(UUID playerId) {
        removeBundle(playerId);
    }

    public static boolean isBound() { return bound; }

    /** 当前会话 token 副本；未 bind 时返回 null。握手 S2C 下发用。 */
    public static byte[] getSessionToken() {
        byte[] t = sessionToken;
        if (t == null) return null;
        return Arrays.copyOf(t, t.length);
    }

    /**
     * 单测/冒烟注入固定 token；须在 {@link #bind()} 之前调用。
     * 传入 null 清除注入，下次 bind 重新 SecureRandom。
     */
    public static void setSessionTokenForTest(byte[] token) {
        if (token == null) {
            sessionToken = null;
            return;
        }
        if (token.length != 16) {
            throw new IllegalArgumentException("session token must be 16 bytes");
        }
        sessionToken = Arrays.copyOf(token, 16);
    }

    /**
     * 尝试把 bulk payload 路由到玩家的 Data 通道（用其 per-channel 派生密钥加密写入）。
     *
     * @param playerId 玩家 UUID（生产=BindRequest 携带的 uuid；测试可用 {@link DataPlanePoCConfig#pseudoPlayerId()}）
     * @param frameType {@link DataPlaneFrame} 帧类型（BULK_COMPRESSED_CHUNK / BULK_SECTION_DELTA）
     * @param payload 帧业务 payload（未经 DataPlaneCodec 加密）
     * @return true = 已写入 Data 通道或已丢弃（caller 不应再走 Primary）；false = 走 Primary
     */
    public static boolean tryRouteBulk(UUID playerId, int frameType, byte[] payload) {
        PlayerChannelBundle bundle = getBundle(playerId);
        if (bundle == null) {
            if (DataPlanePoCConfig.isDataplaneLogEnabled()) {
                DebugLogger.info(DebugLogger.LogType.DATAPLANE,
                        "DataPlaneServer: tryRouteBulk no bundle playerId={} frameType={} payloadSize={} -> Primary", playerId, frameType, payload.length);
            }
            return false; // 未绑定 Data 通道, 走 Primary
        }
        String mode = getRuntimeMode();
        PlayerChannel target = BulkRouter.selectChannel(
                bundle, mode,
                DataPlanePoCConfig.PRIMARY_WEIGHT, DataPlanePoCConfig.DEGRADE_AFTER_DROPS);
        if (target == null) {
            if (DataPlanePoCConfig.isDataplaneLogEnabled()) {
                DebugLogger.info(DebugLogger.LogType.DATAPLANE,
                        "DataPlaneServer: tryRouteBulk no Data target (degraded={} mode={}) -> Primary, payloadSize={} frameType={}",
                        bundle.degraded, mode, payload.length, frameType);
            }
            return false; // 路由到 Primary 或 degraded
        }
        if (target.aesKey == null) {
            LOGGER.warn("DataPlaneServer: target channel has no derived key, falling back to Primary");
            return false;
        }
        try {
            byte[] frame = DataPlaneCodec.encrypt(target.aesKey, frameType, payload);
            if (DataPlanePoCConfig.isDataplaneLogEnabled()) {
                DebugLogger.info(DebugLogger.LogType.DATAPLANE,
                        "DataPlaneServer: routed portIdx={} frameType={} payloadSize={} -> encFrameSize={} weight={} addr={}",
                        target.portIdx, frameType, payload.length, frame.length, target.weight, target.channel.remoteAddress());
            }
            target.channel.writeAndFlush(io.netty.buffer.Unpooled.wrappedBuffer(frame))
                    .addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
            // 记录 Data 路由分流（口径 = payload 等价字节，与 Primary 侧对齐）+ §14 第 4 步 send-side per-portIdx
            io.github.limuqy.mc.hassium.metrics.NetworkStats.recordBulkSentData(payload.length);
            io.github.limuqy.mc.hassium.metrics.NetworkStats.recordBulkSentDataByPort(target.portIdx, payload.length);
            return true;
        } catch (Exception e) {
            LOGGER.warn("DataPlaneServer: encrypt/write failed, falling back to Primary", e);
            return false;
        }
    }

    /**
     * 派生 per-channel 写密钥: HKDF(sessionToken, salt=sessionToken, info=FRAME_KEY_INFO_TAG||portIdx||reqChannelId, 16)。
     * sessionToken 未就绪时回退 {@link DataPlanePoCConfig#BIND_TOKEN}（单测/旧路径）。
     */
    static byte[] deriveChannelKey(int portIdx, int reqChannelId) {
        return deriveChannelKey(effectiveToken(), portIdx, reqChannelId);
    }

    /** 用指定 token 派生密钥（客户端用握手下发 token）。 */
    public static byte[] deriveChannelKey(byte[] token, int portIdx, int reqChannelId) {
        if (token == null || token.length != 16) {
            throw new IllegalArgumentException("token must be 16 bytes");
        }
        byte[] info = new byte[4 + 4 + 4];
        writeTagInt(info, 0, DataPlanePoCConfig.FRAME_KEY_INFO_TAG);
        writeTagInt(info, 4, portIdx);
        writeTagInt(info, 8, reqChannelId);
        byte[] key = Hkdf.extractAndExpand(token, token, info, 16);
        if (DataPlanePoCConfig.isDataplaneLogEnabled()) {
            String keyHex = String.format("%02X%02X%02X%02X…(%d)", key[0] & 0xFF, key[1] & 0xFF, key[2] & 0xFF, key[3] & 0xFF, key.length);
            DebugLogger.info(DebugLogger.LogType.DATAPLANE,
                    "DataPlaneServer: deriveChannelKey portIdx={} reqChannelId={} keyPrefix={} ", portIdx, reqChannelId, keyHex);
        }
        return key;
    }

    private static byte[] effectiveToken() {
        byte[] t = sessionToken;
        return t != null ? t : DataPlanePoCConfig.BIND_TOKEN;
    }

    private static void writeTagInt(byte[] buf, int off, int v) {
        buf[off]     = (byte) (v >>> 24);
        buf[off + 1] = (byte) (v >>> 16);
        buf[off + 2] = (byte) (v >>> 8);
        buf[off + 3] = (byte) v;
    }

    /** ChannelInitializer: 读超时 → Bind 校验 → 帧编解码 */
    static class DataPlaneChannelInitializer extends ChannelInitializer<SocketChannel> {
        private final int portIdx; // 1-based 端点序号，用作派生密钥的子组分
        DataPlaneChannelInitializer(int portIdx) { this.portIdx = portIdx; }

        @Override
        protected void initChannel(SocketChannel ch) {
            ch.pipeline()
                .addLast("timeout", new ReadTimeoutHandler(DataPlanePoCConfig.READ_TIMEOUT_SECS, TimeUnit.SECONDS))
                .addLast("frameSplitter", new VarIntLengthFrameSplitter())
                .addLast("bindHandler", new BindHandshakeHandler(portIdx));
        }
    }

    /** Bind 握手 Handler：接受 BindRequest → 验证 token → BindAck → 加入 PlayerChannelBundle */
    @ChannelHandler.Sharable
    static class BindHandshakeHandler extends ChannelInboundHandlerAdapter {
        private final int portIdx;
        private boolean bound = false;
        private UUID playerId;
        /** per-channel keepalive 调度句柄；bind 成功后启动，channelInactive 时取消以免泄露。 */
        private java.util.concurrent.ScheduledFuture<?> keepaliveTask;

        BindHandshakeHandler(int portIdx) { this.portIdx = portIdx; }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            LOGGER.debug("DataPlaneServer: new connection from {}", ctx.channel().remoteAddress());
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!(msg instanceof ByteBuf buf)) return;
            try {
                int readable = buf.readableBytes();
                if (readable < 1) return;
                byte[] frame = new byte[readable];
                buf.readBytes(frame);

                if (bound) {
                    // Bind 后只接受 KeepAliveAck（PoC 忽略业务 C→S）
                    int type = DataPlaneFrame.decodeType(frame);
                    if (type == DataPlaneFrame.TYPE_KEEPALIVE_ACK) {
                        return; // PoC 不处理
                    }
                    LOGGER.debug("DataPlaneServer: unexpected post-bind frame type {}", type);
                    return;
                }

                int type = DataPlaneFrame.decodeType(frame);
                if (type != DataPlaneFrame.TYPE_BIND_REQUEST) {
                    LOGGER.warn("DataPlaneServer: unexpected frame type {} before bind", type);
                    ctx.close();
                    return;
                }
                byte[] payload = DataPlaneFrame.decodePayload(frame);
                handleBindRequest(ctx, payload);
            } catch (Exception e) {
                LOGGER.error("DataPlaneServer: error reading frame", e);
                ctx.close();
            } finally {
                buf.release();
            }
        }

        private void handleBindRequest(ChannelHandlerContext ctx, byte[] payload) {
            BindRequestCodec.Parsed parsed;
            try {
                parsed = BindRequestCodec.decode(payload);
            } catch (IllegalArgumentException e) {
                LOGGER.warn("DataPlaneServer: bind request rejected: {}", e.getMessage());
                sendBindAck(ctx, false, e.getMessage());
                return;
            }
            if (!Arrays.equals(parsed.token(), effectiveToken())) {
                LOGGER.warn("DataPlaneServer: bind token mismatch");
                sendBindAck(ctx, false, "Token mismatch");
                return;
            }
            if (parsed.protocol() != BindRequestCodec.PROTOCOL_VERSION) {
                LOGGER.warn("DataPlaneServer: unsupported bind protocol {}", parsed.protocol());
                sendBindAck(ctx, false, "Unsupported bind protocol");
                return;
            }
            int reqChannelId = parsed.channelId();
            // 真实玩家 UUID 绑定：bundle key 用 BindRequest 携带的 uuid。
            // 多个玩家分别落独立 bundle；A 的 bulk 绝不进 B 的 channel。
            this.playerId = parsed.playerId();
            PlayerChannelBundle bundle = getOrCreateBundle(this.playerId);
            byte[] aesKey = deriveChannelKey(portIdx, reqChannelId);
            PlayerChannel pc = new PlayerChannel(ctx.channel(), endpointWeightForPortIdx(portIdx), aesKey, portIdx);
            bundle.addChannel(pc);
            CHANNEL_TO_PLAYER.put(ctx.channel(), this.playerId);
            bound = true;

            sendBindAck(ctx, true, "");
            LOGGER.info("DataPlaneServer: bind successful from {} playerId={} portIdx={} reqChannelId={} weight={} dataChannels={}",
                ctx.channel().remoteAddress(), this.playerId, portIdx, reqChannelId, endpointWeightForPortIdx(portIdx), bundle.getDataChannels().size());
            startKeepalive(ctx, aesKey);
        }

        /**
         * 在该 Data 通道 eventLoop 上周期性发加密 KEEPALIVE（S→C）刷新客户端读超时。
         * 客户端收到后回明文 KEEPALIVE_ACK 刷新本侧读超时。心跳挂 per-channel，channelInactive 取消。
         */
        private void startKeepalive(ChannelHandlerContext ctx, byte[] aesKey) {
            if (!DataPlanePoCConfig.KEEPALIVE_ENABLED || aesKey == null) return;
            final Channel ch = ctx.channel();
            final int interval = DataPlanePoCConfig.KEEPALIVE_INTERVAL_SECS;
            keepaliveTask = ch.eventLoop().scheduleAtFixedRate(() -> {
                if (!ch.isOpen()) return;
                try {
                    byte[] frame = DataPlaneCodec.encrypt(aesKey, DataPlaneFrame.TYPE_KEEPALIVE, new byte[0]);
                    ch.writeAndFlush(Unpooled.wrappedBuffer(frame)).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
                } catch (Throwable t) {
                    LOGGER.warn("DataPlaneServer: keepalive encrypt/send failed portIdx={}", portIdx, t);
                }
            }, interval, interval, TimeUnit.SECONDS);
            if (DataPlanePoCConfig.isDataplaneLogEnabled()) {
                DebugLogger.info(DebugLogger.LogType.DATAPLANE,
                        "DataPlaneServer: keepalive started portIdx={} interval={}s", portIdx, interval);
            }
        }

        private int endpointWeightForPortIdx(int portIdx) {
            DataPlanePoCConfig.Endpoint[] eps = DataPlanePoCConfig.ENDPOINTS;
            int idx = portIdx - 1; // portIdx 1-based
            if (idx >= 0 && idx < eps.length) return eps[idx].weight;
            return 50;
        }

        private void sendBindAck(ChannelHandlerContext ctx, boolean ok, String reason) {
            byte[] reasonBytes = reason.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] ackPayload = new byte[1 + reasonBytes.length];
            ackPayload[0] = (byte) (ok ? 1 : 0);
            if (reasonBytes.length > 0) System.arraycopy(reasonBytes, 0, ackPayload, 1, reasonBytes.length);
            byte[] frame = DataPlaneFrame.encode(DataPlaneFrame.TYPE_BIND_ACK, ackPayload);
            ctx.writeAndFlush(Unpooled.wrappedBuffer(frame));
            if (!ok) ctx.close();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (keepaliveTask != null) {
                keepaliveTask.cancel(false);
                keepaliveTask = null;
            }
            if (playerId != null) {
                PlayerChannelBundle bundle = getBundle(playerId);
                if (bundle != null) {
                    PlayerChannel toRemove = null;
                    for (PlayerChannel pc : bundle.getDataChannels()) {
                        if (pc.channel == ctx.channel()) { toRemove = pc; break; }
                    }
                    if (toRemove != null) bundle.removeChannel(toRemove);
                    CHANNEL_TO_PLAYER.remove(ctx.channel());
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            LOGGER.error("DataPlaneServer: exception", cause);
            ctx.close();
        }

        private static int readVarInt(java.nio.ByteBuffer buf) {
            int value = 0, shift = 0;
            byte b;
            do {
                if (!buf.hasRemaining()) break;
                b = buf.get();
                value |= (b & 0x7F) << shift;
                shift += 7;
                if (shift >= 35) break;
            } while ((b & 0x80) != 0);
            return value;
        }
    }
}
