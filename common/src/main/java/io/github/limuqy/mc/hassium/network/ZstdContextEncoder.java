package io.github.limuqy.mc.hassium.network;

import com.github.luben.zstd.ZstdCompressCtx;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import net.minecraft.network.FriendlyByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于上下文的 ZSTD 编码器
 * <p>
 * 借鉴 NEB 的优化思路：
 * 1. Per-connection 压缩上下文复用（利用历史窗口状态提升压缩率）
 * 2. Magicless ZSTD（减少 4 字节开销）
 * 3. 可配置的压缩级别
 * <p>
 * 线协议与原版 {@code CompressionEncoder} 一致：
 * {@code VarInt(uncompressedLength)} + data（未压缩时 length=0，其后为原始包体；
 * 压缩时 length 为解压后大小，其后为压缩字节，无额外长度前缀）。
 */
public class ZstdContextEncoder extends MessageToByteEncoder<ByteBuf> {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/ZstdContextEncoder");

    private int threshold;
    private final ZstdCompressCtx compressCtx;
    private final int compressionLevel;
    private final boolean magicless;
    private volatile boolean closed = false;

    /**
     * @param threshold 压缩阈值（低于此值不压缩）
     * @param level     ZSTD 压缩级别（1-22）
     * @param magicless 是否启用 magicless 模式（去掉 4 字节魔数头）
     */
    public ZstdContextEncoder(int threshold, int level, boolean magicless) {
        this.threshold = threshold;
        this.compressionLevel = level;
        this.magicless = magicless;

        this.compressCtx = new ZstdCompressCtx();
        this.compressCtx.setLevel(level);
        if (magicless) {
            this.compressCtx.setMagicless(true);
            LOGGER.debug("Enabled magicless ZSTD mode (saves 4 bytes per packet)");
        }

        LOGGER.debug("Created ZSTD context encoder (level={}, threshold={}, magicless={})",
                level, threshold, magicless);
    }

    /**
     * 创建默认编码器（带魔数头）
     */
    public ZstdContextEncoder(int threshold, int level) {
        this(threshold, level, false);
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf in, ByteBuf out) throws Exception {
        if (closed) {
            out.writeBytes(in);
            return;
        }

        int readableBytes = in.readableBytes();
        FriendlyByteBuf friendlyBuf = new FriendlyByteBuf(out);

        if (readableBytes < this.threshold) {
            // 与原版一致：VarInt(0) + 原始数据（无额外长度前缀）
            friendlyBuf.writeVarInt(0);
            friendlyBuf.writeBytes(in);
        } else {
            byte[] input = new byte[readableBytes];
            in.readBytes(input);

            byte[] compressed = compressCtx.compress(input);

            // 与原版一致：VarInt(uncompressedLength) + 压缩数据
            friendlyBuf.writeVarInt(input.length);
            friendlyBuf.writeBytes(compressed);

            if (LOGGER.isDebugEnabled()) {
                double ratio = (1 - (double) compressed.length / input.length) * 100;
                LOGGER.debug("Compressed packet: {} -> {} bytes ({}% reduction)",
                        input.length, compressed.length, String.format("%.1f", ratio));
            }
        }
    }

    public void setThreshold(int threshold) {
        this.threshold = threshold;
    }

    public int getThreshold() {
        return threshold;
    }

    public int getCompressionLevel() {
        return compressionLevel;
    }

    public boolean isMagicless() {
        return magicless;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        LOGGER.error("ZSTD encoder error", cause);
        super.exceptionCaught(ctx, cause);
    }

    public synchronized void close() {
        if (!closed) {
            closed = true;
            compressCtx.close();
            LOGGER.debug("Closed ZSTD context encoder");
        }
    }
}
