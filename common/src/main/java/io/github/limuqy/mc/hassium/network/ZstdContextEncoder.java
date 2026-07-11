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
 * 保持与原版相同的线协议格式：VarInt(uncompressedLength) + compressedData
 * <p>
 * 注意：黑名单检查已在 MixinConnection 中完成，此处不再重复检查
 */
public class ZstdContextEncoder extends MessageToByteEncoder<ByteBuf> {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/ZstdContextEncoder");

    private int threshold;
    private final ZstdCompressCtx compressCtx;
    private final int compressionLevel;
    private final boolean magicless;
    private volatile boolean closed = false;

    /**
     * @param threshold  压缩阈值（低于此值不压缩）
     * @param level      ZSTD 压缩级别（1-22）
     * @param magicless  是否启用 magicless 模式（去掉 4 字节魔数头）
     */
    public ZstdContextEncoder(int threshold, int level, boolean magicless) {
        this.threshold = threshold;
        this.compressionLevel = level;
        this.magicless = magicless;

        // 创建 per-connection 压缩上下文
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
            // 上下文已关闭，直接透传
            out.writeBytes(in);
            return;
        }

        int readableBytes = in.readableBytes();
        FriendlyByteBuf friendlyBuf = new FriendlyByteBuf(out);

        if (readableBytes < this.threshold) {
            // 低于阈值：不压缩，写 VarInt(0) + 原始数据
            friendlyBuf.writeVarInt(0);
            friendlyBuf.writeBytes(in);
        } else {
            // 高于阈值：使用上下文压缩
            byte[] input = new byte[readableBytes];
            in.readBytes(input);

            // 写入原始长度
            friendlyBuf.writeVarInt(input.length);

            // 使用上下文压缩（复用历史窗口状态，提升压缩率）
            byte[] compressed = compressCtx.compress(input);
            friendlyBuf.writeBytes(compressed);

            if (LOGGER.isDebugEnabled()) {
                double ratio = (1 - (double) compressed.length / input.length) * 100;
                LOGGER.debug("Compressed packet: {} -> {} bytes ({}% reduction)",
                        input.length, compressed.length, String.format("%.1f", ratio));
            }
        }
    }

    /**
     * 更新压缩阈值
     */
    public void setThreshold(int threshold) {
        this.threshold = threshold;
    }

    /**
     * 获取压缩阈值
     */
    public int getThreshold() {
        return threshold;
    }

    /**
     * 获取压缩级别
     */
    public int getCompressionLevel() {
        return compressionLevel;
    }

    /**
     * 检查是否启用 magicless 模式
     */
    public boolean isMagicless() {
        return magicless;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        LOGGER.error("ZSTD encoder error", cause);
        super.exceptionCaught(ctx, cause);
    }

    /**
     * 关闭压缩上下文，释放资源
     */
    public synchronized void close() {
        if (!closed) {
            closed = true;
            compressCtx.close();
            LOGGER.debug("Closed ZSTD context encoder");
        }
    }

//    @Override
//    protected void finalize() throws Throwable {
//        close();
//        super.finalize();
//    }
}
