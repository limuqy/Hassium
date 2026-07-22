package io.github.limuqy.mc.hassium.network;

import com.github.luben.zstd.Zstd;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * ZSTD 包解压缩解码器
 * <p>
 * 替换原版的 CompressionDecoder（Zlib），使用 ZSTD 算法解压网络包。
 * 保持与原版相同的线协议格式：VarInt(uncompressedLength) + compressedData
 */
public class ZstdPacketDecoder extends ByteToMessageDecoder {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/ZstdDecoder");

    /**
     * 最大压缩数据长度（2MB）
     */
    private static final int MAXIMUM_COMPRESSED_LENGTH = 2 * 1024 * 1024;

    /**
     * 最大解压数据长度（8MB）
     */
    private static final int MAXIMUM_UNCOMPRESSED_LENGTH = 8 * 1024 * 1024;

    private int threshold;
    private boolean validateDecompressed;

    public ZstdPacketDecoder(int threshold, boolean validateDecompressed) {
        this.threshold = threshold;
        this.validateDecompressed = validateDecompressed;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.readableBytes() == 0) {
            return;
        }

        in.markReaderIndex();
        FriendlyByteBuf friendlyBuf = new FriendlyByteBuf(in);
        int uncompressedLength = friendlyBuf.readVarInt();

        if (uncompressedLength == 0) {
            // 与原版一致：剩余全部为未压缩包体
            out.add(friendlyBuf.readBytes(friendlyBuf.readableBytes()));
        } else {
            // 验证解压大小
            if (this.validateDecompressed) {
                if (uncompressedLength < this.threshold) {
                    throw new DecoderException("Badly compressed packet - size " +
                            uncompressedLength + " below threshold " + this.threshold);
                }
                if (uncompressedLength > MAXIMUM_UNCOMPRESSED_LENGTH) {
                    throw new DecoderException("Badly compressed packet - size " +
                            uncompressedLength + " exceeds maximum " + MAXIMUM_UNCOMPRESSED_LENGTH);
                }
            }

            int compressedLength = friendlyBuf.readableBytes();
            if (compressedLength > MAXIMUM_COMPRESSED_LENGTH) {
                throw new DecoderException("Badly compressed packet - compressed size " +
                        compressedLength + " exceeds maximum " + MAXIMUM_COMPRESSED_LENGTH);
            }

            byte[] compressed = new byte[compressedLength];
            friendlyBuf.readBytes(compressed);

            // ZSTD 解压
            byte[] result = Zstd.decompress(compressed, uncompressedLength);
            if (result == null || result.length == 0) {
                throw new DecoderException("ZSTD decompression failed: empty output");
            }

            LOGGER.debug("Decompressed packet: {} -> {} bytes", compressedLength, result.length);
            out.add(Unpooled.wrappedBuffer(result));
        }
    }

    /**
     * 更新压缩阈值
     */
    public void setThreshold(int threshold, boolean validateDecompressed) {
        this.threshold = threshold;
        this.validateDecompressed = validateDecompressed;
    }

    /**
     * 获取压缩阈值
     */
    public int getThreshold() {
        return threshold;
    }
}
