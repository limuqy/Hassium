package io.github.limuqy.mc.hassium.network.dataplane;

import io.github.limuqy.mc.hassium.network.ClientChunkHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.slf4j.Logger;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 客户端 DataPlane 通道组。管理到服务端 Data 端口的连接、Bind 握手、demux bulk 帧。
 * <p>
 * 每条连接以端点 1-based 序号 (portIdx) + 固定 reqChannelId=1 派生 per-channel 读密钥，
 * 与服务端 {@link DataPlaneServer#deriveChannelKey} 一致。
 */
public class DataPlaneClientBundle {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/DataPlaneClient");

    /** BindRequest 里固定写入的 channelId（与 pseudoPlayerId 对应 PoC 单玩家演示） */
    private static final int REQ_CHANNEL_ID = 1;
    private static final int REQ_PROTOCOL = 1;

    private final List<Channel> channels = new ArrayList<>();
    private final NioEventLoopGroup workerGroup = new NioEventLoopGroup(2);
    private volatile boolean bound = false;

    /**
     * 客户端经 Data 通道收到的 bulk 帧计数（PoC 临时指标，不入 {@code NetworkStats}，
     * 设计稿 §11 把分通道指标列为 post-PoC）。仅 {@link DataPlaneClientHandler#handleBulkChunk} 累加 ——
     * bulk 经 Primary 到达绝不会进该路径，故「Data 通道来的 bulk」与「total − Data」可分离。
     * <p>
     * 冒烟断言用 delta（结束快照 − 起始快照），跨轮前显式 {@link #resetDataBulkCounters()}。
     * <p>
     * 1.2.0 起在聚合 {@code bulkFramesData/bulkBytesData} 之外，再加 per-portIdx（1-based 端点序号）
     * 帧计数 Map，便于冒烟区分各 Data 通道的实际到达率（PoC share WRR 下两通道观测独立）。
     */
    public static volatile long bulkFramesData = 0;
    public static volatile long bulkBytesData = 0;
    /** per-portIdx → 帧数；key=portIdx(1-based)，value=累计到达帧数。 */
    private static final java.util.concurrent.ConcurrentHashMap<Integer, java.util.concurrent.atomic.AtomicLong>
            perPortFrames = new java.util.concurrent.ConcurrentHashMap<>();
    /** per-portIdx → 累计 payload 字节数。 */
    private static final java.util.concurrent.ConcurrentHashMap<Integer, java.util.concurrent.atomic.AtomicLong>
            perPortBytes = new java.util.concurrent.ConcurrentHashMap<>();

    /** 当前 Data 通道 bulk 帧累计数。 */
    public static long getBulkFramesData() { return bulkFramesData; }

    /** 当前 Data 通道 bulk 累计字节。 */
    public static long getBulkBytesData() { return bulkBytesData; }

    /** 取 per-portIdx 累计帧数；不存在则为 0。 */
    public static long getBulkFramesByPort(int portIdx) {
        java.util.concurrent.atomic.AtomicLong v = perPortFrames.get(portIdx);
        return v == null ? 0L : v.get();
    }

    /** 取 per-portIdx 累计字节数；不存在则为 0。 */
    public static long getBulkBytesByPort(int portIdx) {
        java.util.concurrent.atomic.AtomicLong v = perPortBytes.get(portIdx);
        return v == null ? 0L : v.get();
    }

    /** per-portIdx 视图快照（按 key 升序），冒烟 {@code DATAPLANE_CLIENT_STATS} 输出用。 */
    public static java.util.SortedMap<Integer, long[]> snapshotPerPort() {
        java.util.TreeMap<Integer, long[]> snap = new java.util.TreeMap<>();
        for (Integer k : perPortFrames.keySet()) snap.put(k, new long[]{getBulkFramesByPort(k), getBulkBytesByPort(k)});
        return snap;
    }

    /** 记录一条 bulk 帧（聚合 + per-portIdx）到达；handleBulkChunk 调用。 */
    private static void onBulkArrived(int portIdx, long payloadLen) {
        bulkFramesData++;
        if (payloadLen > 0) bulkBytesData += payloadLen;
        perPortFrames.computeIfAbsent(portIdx, k -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
        if (payloadLen > 0) perPortBytes.computeIfAbsent(portIdx, k -> new java.util.concurrent.atomic.AtomicLong()).addAndGet(payloadLen);
    }

    /** 复位 Data 帧/字节计数（冒烟跨轮/跨阶段边界调用，避免污染）。 */
    public static void resetDataBulkCounters() {
        bulkFramesData = 0;
        bulkBytesData = 0;
        perPortFrames.clear();
        perPortBytes.clear();
    }


    /** 连接到所有 PoC 端点并发送 BindRequest */
    public void connectAndBind() {
        if (!DataPlanePoCConfig.isEnabled() || !DataPlanePoCConfig.CLIENT_ENABLE_DATA_PLANE) return;
        if (DataPlanePoCConfig.isDataplaneLogEnabled()) {
            DebugLogger.info(DebugLogger.LogType.DATAPLANE,
                    "DataPlaneClient: connecting endpoints={} reqChannelId={} protocol={}",
                    DataPlanePoCConfig.endpointsSummary(), REQ_CHANNEL_ID, REQ_PROTOCOL);
        } else {
            LOGGER.info("DataPlaneClient: connecting to {} endpoint(s)...", DataPlanePoCConfig.ENDPOINTS.length);
        }
        for (int idx = 0; idx < DataPlanePoCConfig.ENDPOINTS.length; idx++) {
            DataPlanePoCConfig.Endpoint ep = DataPlanePoCConfig.ENDPOINTS[idx];
            int portIdx = idx + 1;
            // 与服务端同源派生读密钥（服务端用同一 portIdx + reqChannelId 加密）
            byte[] aesKey = DataPlaneServer.deriveChannelKey(portIdx, REQ_CHANNEL_ID);
            try {
                if (DataPlanePoCConfig.isDataplaneLogEnabled()) {
                    String keyHex = String.format("%02X%02X%02X%02X…(%d)", aesKey[0] & 0xFF, aesKey[1] & 0xFF, aesKey[2] & 0xFF, aesKey[3] & 0xFF, aesKey.length);
                    DebugLogger.info(DebugLogger.LogType.DATAPLANE,
                            "DataPlaneClient: connecting #{} {}:{} reqChannelId={} keyPrefix={}", portIdx, ep.address, ep.port, REQ_CHANNEL_ID, keyHex);
                }
                ChannelFuture f = new Bootstrap()
                        .group(workerGroup)
                        .channel(NioSocketChannel.class)
                        .handler(new ChannelInitializer<SocketChannel>() {
                            @Override protected void initChannel(SocketChannel ch) {
                                ch.pipeline()
                                        .addLast("timeout", new ReadTimeoutHandler(DataPlanePoCConfig.READ_TIMEOUT_SECS, TimeUnit.SECONDS))
                                        .addLast("frameSplitter", new VarIntLengthFrameSplitter())
                                        .addLast("dataHandler", new DataPlaneClientHandler(portIdx, aesKey));
                            }
                        })
                        .option(ChannelOption.TCP_NODELAY, true)
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
                        .connect(ep.address, ep.port)
                        .sync();
                sendBindRequest(f.channel());
                channels.add(f.channel());
                LOGGER.info("DataPlaneClient: connected to {}:{} (portIdx={})", ep.address, ep.port, portIdx);
            } catch (Exception e) {
                LOGGER.error("DataPlaneClient: failed to connect to {}:{}", ep.address, ep.port, e);
            }
        }
        bound = !channels.isEmpty();
        if (bound) {
            LOGGER.info("DataPlaneClient: {} channel(s) bound", channels.size());
        }
    }

    private void sendBindRequest(Channel channel) {
        // BindRequest: token[16] + channelId(VarInt) + protocol(VarInt)
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            out.write(DataPlanePoCConfig.BIND_TOKEN);
            DataPlaneFrame.writeVarInt(out, REQ_CHANNEL_ID);
            DataPlaneFrame.writeVarInt(out, REQ_PROTOCOL);
        } catch (java.io.IOException e) {
            LOGGER.error("DataPlaneClient: encode BindRequest failed", e);
            return;
        }
        byte[] payload = out.toByteArray();
        byte[] frame = DataPlaneFrame.encode(DataPlaneFrame.TYPE_BIND_REQUEST, payload);
        channel.writeAndFlush(Unpooled.wrappedBuffer(frame));
        if (DataPlanePoCConfig.isDataplaneLogEnabled()) {
            DebugLogger.info(DebugLogger.LogType.DATAPLANE,
                    "DataPlaneClient: sent BindRequest frameLen={} tokenBytes={} reqChannelId={} protocol={}",
                    frame.length, DataPlanePoCConfig.BIND_TOKEN.length, REQ_CHANNEL_ID, REQ_PROTOCOL);
        }
    }

    /** 关闭所有 Data 通道 */
    public void shutdown() {
        if (!bound) return;
        LOGGER.info("DataPlaneClient: shutting down...");
        for (Channel ch : channels) {
            if (ch.isOpen()) ch.close();
        }
        channels.clear();
        workerGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        bound = false;
    }

    public boolean isBound() { return bound; }

    /** 客户端 Handler: 解码 BindAck + demux 并解密 BulkCompressedChunk */
    static class DataPlaneClientHandler extends ChannelInboundHandlerAdapter {
        private final int portIdx;
        private final byte[] aesKey;
        private volatile boolean bindOk = false;

        DataPlaneClientHandler(int portIdx, byte[] aesKey) {
            this.portIdx = portIdx;
            this.aesKey = aesKey;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!(msg instanceof ByteBuf buf)) return;
            try {
                int readable = buf.readableBytes();
                if (readable < 1) return;
                byte[] frame = new byte[readable];
                buf.readBytes(frame);

                if (!bindOk) {
                    // 未 Bind 完成前收到的应是 BindAck（明文帧）
                    int ackType = DataPlaneFrame.decodeType(frame);
                    byte[] ackPayload = DataPlaneFrame.decodePayload(frame);
                    if (DataPlanePoCConfig.isDataplaneLogEnabled()) {
                        DebugLogger.info(DebugLogger.LogType.DATAPLANE,
                                "DataPlaneClient: pre-bind frame portIdx={} ackType={} payloadLen={}", portIdx, ackType, ackPayload.length);
                    }
                    handleBindAck(ctx, ackType, ackPayload);
                    return;
                }

                if (DataPlanePoCConfig.isDataplaneLogEnabled()) {
                    DebugLogger.info(DebugLogger.LogType.DATAPLANE,
                            "DataPlaneClient: enc frame in portIdx={} frameLen={}", portIdx, frame.length);
                }
                DataPlaneCodec.FrameDecryptResult dec;
                try {
                    dec = DataPlaneCodec.decrypt(aesKey, frame);
                } catch (Exception e) {
                    LOGGER.warn("DataPlaneClient: decrypt failed (key mismatch?) portIdx={} frameLen={} dropping frame", portIdx, frame.length, e);
                    return;
                }
                if (DataPlanePoCConfig.isDataplaneLogEnabled()) {
                    DebugLogger.info(DebugLogger.LogType.DATAPLANE,
                            "DataPlaneClient: dec ok portIdx={} type={} payloadLen={}", portIdx, dec.type, dec.payload.length);
                }
                switch (dec.type) {
                    case DataPlaneFrame.TYPE_BULK_COMPRESSED_CHUNK -> {
                        // 与 Primary 管线 ZstdContextDecoder 的 recordWireBytesReceived 对齐：Data 通道
                        io.github.limuqy.mc.hassium.metrics.NetworkStats.recordWireBytesReceived(frame.length);
                        handleBulkChunk(dec.payload);
                    }
                    case DataPlaneFrame.TYPE_KEEPALIVE -> sendKeepaliveAck(ctx);
                    case DataPlaneFrame.TYPE_CLOSE -> ctx.close();
                    default -> LOGGER.warn("DataPlaneClient: unknown frame type {} portIdx={}", dec.type, portIdx);
                }
            } catch (Exception e) {
                LOGGER.error("DataPlaneClient: error reading frame portIdx={}", portIdx, e);
            } finally {
                buf.release();
            }
        }

        /**
         * 回明文 KEEPALIVE_ACK（C→S）刷新服务端读超时。
         * <p>
         * 设计稿：ACK 不携敏感数据，明文发送可接受（仅保活语义）；服务端 {@code BindHandshakeHandler}
         * bound 分支按明文 {@code decodeType} 读 type=6 后忽略，方向匹配。
         */
        private void sendKeepaliveAck(ChannelHandlerContext ctx) {
            byte[] frame = DataPlaneFrame.encode(DataPlaneFrame.TYPE_KEEPALIVE_ACK, new byte[0]);
            ctx.writeAndFlush(Unpooled.wrappedBuffer(frame)).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
            if (DataPlanePoCConfig.isDataplaneLogEnabled()) {
                DebugLogger.info(DebugLogger.LogType.DATAPLANE,
                        "DataPlaneClient: sent KeepaliveAck portIdx={} frameLen={}", portIdx, frame.length);
            }
        }

        private void handleBindAck(ChannelHandlerContext ctx, int type, byte[] payload) {            if (type != DataPlaneFrame.TYPE_BIND_ACK) {
                LOGGER.warn("DataPlaneClient: expected BindAck, got type {} portIdx={}", type, portIdx);
                ctx.close();
                return;
            }
            boolean ok = payload.length > 0 && payload[0] == 1;
            LOGGER.info("DataPlaneClient: BindAck portIdx={} result={}", portIdx, ok ? "OK" : "FAIL");
            if (!ok) {
                ctx.close();
            } else {
                bindOk = true;
            }
        }

        private void handleBulkChunk(byte[] plaintextPayload) {
            // plaintextPayload = CompressedChunkData.encode() 输出
            // 交给 ClientChunkHandler 走标准路径: 解压 → 入库 → apply
            // 累加 PoC 临时 Data 帧计数（聚合 + per-portIdx）；bulk 经 Data 到达必经此路径；经 Primary 到达不累加
            onBulkArrived(portIdx, plaintextPayload == null ? 0L : plaintextPayload.length);
            if (DataPlanePoCConfig.isDataplaneLogEnabled()) {
                DebugLogger.info(DebugLogger.LogType.DATAPLANE,
                        "DataPlaneClient: handleBulkChunk portIdx={} payloadLen={}", portIdx, plaintextPayload.length);
            }
            try {
                ClientChunkHandler.handleCompressedChunk(plaintextPayload);
            } catch (Exception e) {
                LOGGER.error("DataPlaneClient: handleCompressedChunk failed portIdx={}", portIdx, e);
            }
        }
    }
}
