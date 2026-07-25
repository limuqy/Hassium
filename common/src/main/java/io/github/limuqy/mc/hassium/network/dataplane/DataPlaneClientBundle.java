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
     */
    public static volatile long bulkFramesData = 0;
    public static volatile long bulkBytesData = 0;

    /** 当前 Data 通道 bulk 帧累计数。 */
    public static long getBulkFramesData() { return bulkFramesData; }

    /** 当前 Data 通道 bulk 累计字节。 */
    public static long getBulkBytesData() { return bulkBytesData; }

    /** 复位 Data 帧/字节计数（冒烟跨轮/跨阶段边界调用，避免污染）。 */
    public static void resetDataBulkCounters() { bulkFramesData = 0; bulkBytesData = 0; }


    /** 连接到所有 PoC 端点并发送 BindRequest */
    public void connectAndBind() {
        if (!DataPlanePoCConfig.isEnabled() || !DataPlanePoCConfig.CLIENT_ENABLE_DATA_PLANE) return;
        if (DataPlanePoCConfig.DEBUG_DATAPLANE) {
            LOGGER.info("DataPlaneClient: connecting endpoints={} reqChannelId={} protocol={}",
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
                if (DataPlanePoCConfig.DEBUG_DATAPLANE) {
                    String keyHex = String.format("%02X%02X%02X%02X…(%d)", aesKey[0] & 0xFF, aesKey[1] & 0xFF, aesKey[2] & 0xFF, aesKey[3] & 0xFF, aesKey.length);
                    LOGGER.info("DataPlaneClient: connecting #{} {}:{} reqChannelId={} keyPrefix={}", portIdx, ep.address, ep.port, REQ_CHANNEL_ID, keyHex);
                }
                ChannelFuture f = new Bootstrap()
                        .group(workerGroup)
                        .channel(NioSocketChannel.class)
                        .handler(new ChannelInitializer<SocketChannel>() {
                            @Override protected void initChannel(SocketChannel ch) {
                                ch.pipeline()
                                        .addLast("timeout", new ReadTimeoutHandler(5, TimeUnit.SECONDS))
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
        if (DataPlanePoCConfig.DEBUG_DATAPLANE) {
            LOGGER.info("DataPlaneClient: sent BindRequest frameLen={} tokenBytes={} reqChannelId={} protocol={}",
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
                    if (DataPlanePoCConfig.DEBUG_DATAPLANE) {
                        LOGGER.info("DataPlaneClient: pre-bind frame portIdx={} ackType={} payloadLen={}", portIdx, ackType, ackPayload.length);
                    }
                    handleBindAck(ctx, ackType, ackPayload);
                    return;
                }

                // Bind 后业务帧为加密帧：先解密再 demux
                if (DataPlanePoCConfig.DEBUG_DATAPLANE) {
                    LOGGER.info("DataPlaneClient: enc frame in portIdx={} frameLen={}", portIdx, frame.length);
                }
                DataPlaneCodec.FrameDecryptResult dec;
                try {
                    dec = DataPlaneCodec.decrypt(aesKey, frame);
                } catch (Exception e) {
                    LOGGER.warn("DataPlaneClient: decrypt failed (key mismatch?) portIdx={} frameLen={} dropping frame", portIdx, frame.length, e);
                    return;
                }
                if (DataPlanePoCConfig.DEBUG_DATAPLANE) {
                    LOGGER.info("DataPlaneClient: dec ok portIdx={} type={} payloadLen={}", portIdx, dec.type, dec.payload.length);
                }
                switch (dec.type) {
                    case DataPlaneFrame.TYPE_BULK_COMPRESSED_CHUNK -> handleBulkChunk(dec.payload);
                    case DataPlaneFrame.TYPE_KEEPALIVE -> { /* PoC 忽略 */ }
                    case DataPlaneFrame.TYPE_CLOSE -> ctx.close();
                    default -> LOGGER.warn("DataPlaneClient: unknown frame type {} portIdx={}", dec.type, portIdx);
                }
            } catch (Exception e) {
                LOGGER.error("DataPlaneClient: error reading frame portIdx={}", portIdx, e);
            } finally {
                buf.release();
            }
        }

        private void handleBindAck(ChannelHandlerContext ctx, int type, byte[] payload) {
            if (type != DataPlaneFrame.TYPE_BIND_ACK) {
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
            // 累加 PoC 临时 Data 帧计数（bulk 经 Data 到达必经此路径；经 Primary 到达不累加）
            bulkFramesData++;
            if (plaintextPayload != null) bulkBytesData += plaintextPayload.length;
            if (DataPlanePoCConfig.DEBUG_DATAPLANE) {
                LOGGER.info("DataPlaneClient: handleBulkChunk portIdx={} payloadLen={}", portIdx, plaintextPayload.length);
            }
            try {
                ClientChunkHandler.handleCompressedChunk(plaintextPayload);
            } catch (Exception e) {
                LOGGER.error("DataPlaneClient: handleCompressedChunk failed portIdx={}", portIdx, e);
            }
        }
    }
}
