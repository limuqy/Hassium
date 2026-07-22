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

import java.util.List;

/**
 * 基于上下文的 ZSTD 解码器
 * <p>
 * 借鉴 NEB 的优化思路：
 * 1. Per-connection 解压上下文复用
 * 2. Magicless ZSTD 支持
 * <p>
 * 线协议与原版 {@code CompressionDecoder} 一致：
 * {@code VarInt(uncompressedLength)} + data。
 */
public class ZstdContextDecoder extends ByteToMessageDecoder {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/ZstdContextDecoder");

    private static final int MAXIMUM_COMPRESSED_LENGTH = 2 * 1024 * 1024;
    private static final int MAXIMUM_UNCOMPRESSED_LENGTH = 8 * 1024 * 1024;

    private int threshold;
    private boolean validateDecompressed;
    private final ZstdDecompressCtx decompressCtx;
    private volatile boolean closed = false;

    /**
     * @param threshold            压缩阈值
     * @param validateDecompressed 是否验证解压大小
     * @param magicless            是否启用 magicless 模式
     */
    public ZstdContextDecoder(int threshold, boolean validateDecompressed, boolean magicless) {
        this.threshold = threshold;
        this.validateDecompressed = validateDecompressed;

        this.decompressCtx = new ZstdDecompressCtx();
        if (magicless) {
            this.decompressCtx.setMagicless(true);
            LOGGER.debug("Enabled magicless ZSTD decoder mode");
        }

        LOGGER.debug("Created ZSTD context decoder (threshold={}, magicless={})",
                threshold, magicless);
    }

    public ZstdContextDecoder(int threshold, boolean validateDecompressed) {
        this(threshold, validateDecompressed, false);
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

        FriendlyByteBuf friendlyBuf = new FriendlyByteBuf(in);
        int uncompressedLength = friendlyBuf.readVarInt();

        if (uncompressedLength == 0) {
            // 与原版一致：剩余全部为未压缩包体
            out.add(friendlyBuf.readBytes(friendlyBuf.readableBytes()));
        } else {
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

            byte[] result = decompressCtx.decompress(compressed, uncompressedLength);

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Decompressed packet: {} -> {} bytes", compressedLength, result.length);
            }
            out.add(Unpooled.wrappedBuffer(result));
        }
    }

    public void setThreshold(int threshold, boolean validateDecompressed) {
        this.threshold = threshold;
        this.validateDecompressed = validateDecompressed;
    }

    public int getThreshold() {
        return threshold;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        LOGGER.error("ZSTD decoder error", cause);
        super.exceptionCaught(ctx, cause);
    }

    public synchronized void close() {
        if (!closed) {
            closed = true;
            decompressCtx.close();
            LOGGER.debug("Closed ZSTD context decoder");
        }
    }
}
