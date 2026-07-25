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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Data Plane 服务端。
 * 管理多端口 accept、Bind 校验、per-player PlayerChannelBundle。
 */
public class DataPlaneServer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/DataPlaneServer");

    private static final Map<Integer, Channel> SERVER_CHANNELS = new LinkedHashMap<>();
    private static final ConcurrentHashMap<Channel, UUID> CHANNEL_TO_PLAYER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, PlayerChannelBundle> PLAYER_BUNDLES = new ConcurrentHashMap<>();
    private static final AtomicLong CHANNEL_ID_GEN = new AtomicLong(0);
    private static volatile NioEventLoopGroup bossGroup;
    private static volatile NioEventLoopGroup workerGroup;
    private static volatile boolean bound = false;

    /** 绑定所有 PoC 数据端口 */
    public static synchronized void bind() {
        if (bound) return;
        if (!DataPlanePoCConfig.ENABLED) {
            LOGGER.info("DataPlaneServer: disabled by config");
            return;
        }
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(4);
        LOGGER.info("DataPlaneServer: binding {} data port(s)...", DataPlanePoCConfig.ENDPOINTS.length);
        for (DataPlanePoCConfig.Endpoint ep : DataPlanePoCConfig.ENDPOINTS) {
            long channelId = CHANNEL_ID_GEN.incrementAndGet();
            ServerBootstrap b = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new DataPlaneChannelInitializer(channelId))
                .childOption(ChannelOption.TCP_NODELAY, true);
            try {
                Channel ch = b.bind(ep.bindHost, ep.bindPort).sync().channel();
                SERVER_CHANNELS.put(ep.bindPort, ch);
                LOGGER.info("DataPlaneServer: bound to {}:{} (weight={})", ep.bindHost, ep.bindPort, ep.weight);
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

    /**
     * 尝试把 bulk payload 路由到玩家的 Data 通道（加密写入）。
     *
     * @param playerId 玩家 UUID（PoC 客户端层用真实 UUID 服务端拦截；bundle 由 Bind 时建立）
     * @param frameType {@link DataPlaneFrame} 帧类型（BULK_COMPRESSED_CHUNK / BULK_SECTION_DELTA）
     * @param payload 帧业务 payload（未经 DataPlaneCodec 加密）
     * @param channelKeyMaterial 用于派生每帧写密钥的信息（PoC: BIND_TOKEN + frameType + 通道序号）
     * @return true = 已写入 Data 通道或已丢弃（caller 不应再走 Primary）；false = 走 Primary
     */
    public static boolean tryRouteBulk(UUID playerId, int frameType, byte[] payload, byte[] channelKeyMaterial) {
        PlayerChannelBundle bundle = getBundle(playerId);
        if (bundle == null) return false; // 未绑定 Data 通道, 走 Primary
        PlayerChannel target = BulkRouter.selectChannel(
                bundle, DataPlanePoCConfig.BULK_ROUTE_MODE,
                DataPlanePoCConfig.PRIMARY_WEIGHT, DataPlanePoCConfig.DEGRADE_AFTER_DROPS);
        if (target == null) return false; // 路由到 Primary 或 degraded
        try {
            byte[] key = deriveFrameKey(channelKeyMaterial);
            byte[] frame = DataPlaneCodec.encrypt(key, frameType, payload);
            target.channel.writeAndFlush(io.netty.buffer.Unpooled.wrappedBuffer(frame));
            return true;
        } catch (Exception e) {
            LOGGER.warn("DataPlaneServer: encrypt/write failed, falling back to Primary", e);
            return false;
        }
    }

    /** 每帧派生密钥: HKDF(BIND_TOKEN, info=frameType||channelKeyMaterial, len=16) */
    private static byte[] deriveFrameKey(byte[] channelKeyMaterial) {
        byte[] info = new byte[4 + channelKeyMaterial.length];
        info[0] = (byte) DataPlanePoCConfig.FRAME_KEY_INFO_TAG;
        System.arraycopy(channelKeyMaterial, 0, info, 4, channelKeyMaterial.length);
        return Hkdf.extractAndExpand(DataPlanePoCConfig.BIND_TOKEN, DataPlanePoCConfig.BIND_TOKEN, info, 16);
    }

    /** ChannelInitializer: 读超时 → Bind 校验 → 帧编解码 */
    static class DataPlaneChannelInitializer extends ChannelInitializer<SocketChannel> {
        private final long channelId;
        DataPlaneChannelInitializer(long channelId) { this.channelId = channelId; }

        @Override
        protected void initChannel(SocketChannel ch) {
            ch.pipeline()
                .addLast("timeout", new ReadTimeoutHandler(5, TimeUnit.SECONDS))
                .addLast("bindHandler", new BindHandshakeHandler(channelId));
        }
    }

    /** Bind 握手 Handler：接受 BindRequest → 验证 token → BindAck → 加入 PlayerChannelBundle */
    @ChannelHandler.Sharable
    static class BindHandshakeHandler extends ChannelInboundHandlerAdapter {
        private final long channelId;
        private boolean bound = false;
        private UUID playerId;

        BindHandshakeHandler(long channelId) { this.channelId = channelId; }

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
            if (payload.length < 16) {
                LOGGER.warn("DataPlaneServer: bind request too short");
                sendBindAck(ctx, false, "Bad request length");
                return;
            }
            byte[] token = new byte[16];
            System.arraycopy(payload, 0, token, 0, 16);
            if (!Arrays.equals(token, DataPlanePoCConfig.BIND_TOKEN)) {
                LOGGER.warn("DataPlaneServer: bind token mismatch");
                sendBindAck(ctx, false, "Token mismatch");
                return;
            }
            // 解析 channelId + protocol（PoC 仅读取，不做强校验）
            java.nio.ByteBuffer rest = java.nio.ByteBuffer.wrap(payload, 16, payload.length - 16);
            int reqChannelId = readVarInt(rest);
            int reqProtocol = readVarInt(rest);

            // PoC: 玩家身份来自客户端的 UUID，但 PoC 不扩展握手故无法绑 UUID；
            // 使用 channelId 作为伪 player 标识，保证同 player 多通道归到同一 bundle。
            // 真正绑定 UUID 留到握手扩展阶段。此处用 channelId 的低64位作为 playerId 桶键。
            long pseudoId = reqChannelId & 0xFFFFFFFFL;
            playerId = new UUID(0L, pseudoId);
            PlayerChannelBundle bundle = getOrCreateBundle(playerId);
            PlayerChannel pc = new PlayerChannel(ctx.channel(), endpointWeightFor(reqChannelId));
            bundle.addChannel(pc);
            CHANNEL_TO_PLAYER.put(ctx.channel(), playerId);
            bound = true;

            sendBindAck(ctx, true, "");
            LOGGER.info("DataPlaneServer: bind successful from {} playerId={} channelId={}",
                ctx.channel().remoteAddress(), playerId, reqChannelId);
        }

        private int endpointWeightFor(int channelId) {
            int idx = (int) (channelId - 1);
            DataPlanePoCConfig.Endpoint[] eps = DataPlanePoCConfig.ENDPOINTS;
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
                if (!buf.hasRemaining()) return value;
                b = buf.get();
                value |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);
            return value;
        }
    }
}
