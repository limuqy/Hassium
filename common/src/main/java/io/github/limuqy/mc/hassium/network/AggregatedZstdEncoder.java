package io.github.limuqy.mc.hassium.network;

import com.github.luben.zstd.ZstdCompressCtx;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import net.minecraft.network.FriendlyByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 支持包聚合的 ZSTD 编码器
 * <p>
 * 借鉴 NEB 的优化思路：
 * 1. 包聚合：将多个小包合并后再压缩
 * 2. Per-connection 压缩上下文复用
 * 3. Magicless ZSTD
 * <p>
 * 线协议格式：
 * [isAggregated:byte] [uncompressedLength:VarInt] [compressedData]
 * <p>
 * 如果 isAggregated = 1，compressedData 解压后是：
 * [packetCount:VarInt] [packet1Length:VarInt] [packet1Data] ...
 * <p>
 * 注意：黑名单检查已在 MixinConnection 中完成，此处不再重复检查
 */
public class AggregatedZstdEncoder extends MessageToByteEncoder<ByteBuf> {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/AggregatedZstdEncoder");

    /**
     * 聚合标记
     */
    private static final byte AGGREGATED_FLAG = 1;
    private static final byte NOT_AGGREGATED_FLAG = 0;

    private final int threshold;
    private final ZstdCompressCtx compressCtx;
    private final PacketAggregator aggregator;
    private final boolean magicless;
    private volatile boolean closed = false;

    /**
     * @param threshold       压缩阈值
     * @param level           ZSTD 压缩级别
     * @param magicless       是否启用 magicless 模式
     * @param aggregator      包聚合器
     */
    public AggregatedZstdEncoder(int threshold, int level,
                                  boolean magicless, PacketAggregator aggregator) {
        this.threshold = threshold;
        this.magicless = magicless;
        this.aggregator = aggregator;

        // 创建 per-connection 压缩上下文
        this.compressCtx = new ZstdCompressCtx();
        this.compressCtx.setLevel(level);
        if (magicless) {
            this.compressCtx.setMagicless(true);
        }

        LOGGER.debug("Created aggregated ZSTD encoder (level={}, threshold={}, magicless={})",
                level, threshold, magicless);
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf in, ByteBuf out) throws Exception {
        if (closed) {
            out.writeBytes(in);
            return;
        }

        int readableBytes = in.readableBytes();

        // 小包：添加到聚合缓冲区
        if (readableBytes < threshold) {
            byte[] packetData = new byte[readableBytes];
            in.readBytes(packetData);

            if (aggregator.addPacket(packetData)) {
                // 需要刷新
                flushAggregated(ctx, out);
            }
            return;
        }

        // 大包：先刷新聚合缓冲区，然后单独压缩
        flushAggregated(ctx, out);

        // 压缩大包
        byte[] input = new byte[readableBytes];
        in.readBytes(input);

        byte[] compressed = compressCtx.compress(input);

        FriendlyByteBuf friendlyBuf = new FriendlyByteBuf(out);
        friendlyBuf.writeByte(NOT_AGGREGATED_FLAG);
        friendlyBuf.writeVarInt(input.length);
        friendlyBuf.writeVarInt(compressed.length);
        friendlyBuf.writeBytes(compressed);

        if (LOGGER.isDebugEnabled()) {
            double ratio = (1 - (double) compressed.length / input.length) * 100;
            LOGGER.debug("Compressed large packet: {} -> {} bytes ({}% reduction)",
                    input.length, compressed.length, String.format("%.1f", ratio));
        }
    }

    /**
     * 刷新聚合缓冲区
     */
    private void flushAggregated(ChannelHandlerContext ctx, ByteBuf out) {
        ByteBuf aggregated = aggregator.flush();
        if (aggregated == null) {
            return;
        }

        try {
            int aggregatedSize = aggregated.readableBytes();

            // 压缩聚合数据
            byte[] input = new byte[aggregatedSize];
            aggregated.readBytes(input);

            byte[] compressed = compressCtx.compress(input);

            // 写入输出
            FriendlyByteBuf friendlyBuf = new FriendlyByteBuf(out);
            friendlyBuf.writeByte(AGGREGATED_FLAG);
            friendlyBuf.writeVarInt(input.length);
            friendlyBuf.writeVarInt(compressed.length);
            friendlyBuf.writeBytes(compressed);

            if (LOGGER.isDebugEnabled()) {
                double ratio = (1 - (double) compressed.length / input.length) * 100;
                LOGGER.debug("Flushed aggregated packets: {} -> {} bytes ({}% reduction)",
                        input.length, compressed.length, String.format("%.1f", ratio));
            }
        } finally {
            aggregated.release();
        }
    }

    /**
     * 手动刷新（连接关闭时调用）
     */
    public void flushOnClose(ChannelHandlerContext ctx) {
        if (!closed) {
            closed = true;
            // 尝试刷新剩余的包
            ByteBuf out = ctx.alloc().buffer();
            try {
                flushAggregated(ctx, out);
                if (out.readableBytes() > 0) {
                    ctx.writeAndFlush(out);
                } else {
                    out.release();
                }
            } catch (Exception e) {
                out.release();
                LOGGER.error("Error flushing on close", e);
            }
            compressCtx.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        LOGGER.error("Aggregated ZSTD encoder error", cause);
        super.exceptionCaught(ctx, cause);
    }

    /**
     * 获取聚合器
     */
    public PacketAggregator getAggregator() {
        return aggregator;
    }

    /**
     * 获取压缩阈值
     */
    public int getThreshold() {
        return threshold;
    }
}
