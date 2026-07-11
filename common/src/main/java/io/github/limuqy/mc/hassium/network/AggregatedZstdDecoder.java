package io.github.limuqy.mc.hassium.network;

import com.github.luben.zstd.ZstdDecompressCtx;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 支持包解聚合的 ZSTD 解码器
 * <p>
 * 借鉴 NEB 的优化思路：
 * 1. 包解聚合：从聚合包中解析出单个包
 * 2. Per-connection 解压上下文复用
 * 3. Magicless ZSTD
 * <p>
 * 线协议格式：
 * [isAggregated:byte] [uncompressedLength:VarInt] [compressedData]
 * <p>
 * TODO: 未来版本将在聚合包内部使用紧凑包头（参考 NEB 的 CustomPacketPrefixHelper），
 * 在子包解码时用 VarInt 索引还原 ResourceLocation 字符串。
 * 这需要将聚合拦截点从 Pipeline 层移到 Connection.send() 层，以获取包类型信息。
 */
public class AggregatedZstdDecoder extends ByteToMessageDecoder {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/AggregatedZstdDecoder");

    /**
     * 聚合标记
     */
    private static final byte AGGREGATED_FLAG = 1;
    private static final byte NOT_AGGREGATED_FLAG = 0;

    /**
     * 最大压缩数据长度（2MB）
     */
    private static final int MAXIMUM_COMPRESSED_LENGTH = 2 * 1024 * 1024;

    /**
     * 最大解压数据长度（8MB）
     */
    private static final int MAXIMUM_UNCOMPRESSED_LENGTH = 8 * 1024 * 1024;

    private final int threshold;
    private final boolean validateDecompressed;
    private final ZstdDecompressCtx decompressCtx;
    private volatile boolean closed = false;

    /**
     * @param threshold           压缩阈值
     * @param validateDecompressed 是否验证解压大小
     * @param magicless           是否启用 magicless 模式
     */
    public AggregatedZstdDecoder(int threshold, boolean validateDecompressed, boolean magicless) {
        this.threshold = threshold;
        this.validateDecompressed = validateDecompressed;

        // 创建 per-connection 解压上下文
        this.decompressCtx = new ZstdDecompressCtx();
        if (magicless) {
            this.decompressCtx.setMagicless(true);
        }

        LOGGER.debug("Created aggregated ZSTD decoder (threshold={}, magicless={})",
                threshold, magicless);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (closed) {
            out.add(in.readBytes(in.readableBytes()));
            return;
        }

        if (in.readableBytes() == 0) {
            return;
        }

        // 标记当前位置，以便回滚
        in.markReaderIndex();

        // 读取聚合标记
        byte flag = in.readByte();

        // 读取解压后长度
        int uncompressedLength = readVarInt(in);

        if (uncompressedLength == 0) {
            // 未压缩数据
            if (flag == AGGREGATED_FLAG) {
                // 聚合的未压缩数据
                List<byte[]> packets = PacketAggregator.disaggregate(in);
                for (byte[] packet : packets) {
                    out.add(Unpooled.wrappedBuffer(packet));
                }
            } else {
                // 单个未压缩包
                out.add(in.readBytes(in.readableBytes()));
            }
            return;
        }

        // 验证解压大小
        if (validateDecompressed) {
            if (uncompressedLength > MAXIMUM_UNCOMPRESSED_LENGTH) {
                throw new DecoderException("Badly compressed packet - size " +
                        uncompressedLength + " exceeds maximum " + MAXIMUM_UNCOMPRESSED_LENGTH);
            }
        }

        // 读取压缩数据
        int compressedLength = in.readableBytes();
        if (compressedLength > MAXIMUM_COMPRESSED_LENGTH) {
            throw new DecoderException("Badly compressed packet - compressed size " +
                    compressedLength + " exceeds maximum " + MAXIMUM_COMPRESSED_LENGTH);
        }

        byte[] compressed = new byte[compressedLength];
        in.readBytes(compressed);

        // 解压
        byte[] decompressed = decompressCtx.decompress(compressed, uncompressedLength);

        if (flag == AGGREGATED_FLAG) {
            // 解聚合
            ByteBuf decompressedBuf = Unpooled.wrappedBuffer(decompressed);
            try {
                List<byte[]> packets = PacketAggregator.disaggregate(decompressedBuf);
                for (byte[] packet : packets) {
                    out.add(Unpooled.wrappedBuffer(packet));
                }

                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Decompressed and disaggregated {} packets", packets.size());
                }
            } finally {
                decompressedBuf.release();
            }
        } else {
            // 单个包
            out.add(Unpooled.wrappedBuffer(decompressed));

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Decompressed single packet: {} -> {} bytes",
                        compressedLength, decompressed.length);
            }
        }
    }

    /**
     * 读取 VarInt
     */
    private static int readVarInt(ByteBuf buf) {
        int value = 0;
        int shift = 0;
        byte b;
        do {
            b = buf.readByte();
            value |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return value;
    }

    /**
     * 更新压缩阈值
     */
    public void setThreshold(int threshold, boolean validateDecompressed) {
        // 这里只是记录，实际不会改变已创建的上下文
    }

    /**
     * 获取压缩阈值
     */
    public int getThreshold() {
        return threshold;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        LOGGER.error("Aggregated ZSTD decoder error", cause);
        super.exceptionCaught(ctx, cause);
    }

    /**
     * 关闭解压上下文
     */
    public synchronized void close() {
        if (!closed) {
            closed = true;
            decompressCtx.close();
            LOGGER.debug("Closed aggregated ZSTD decoder");
        }
    }
}
