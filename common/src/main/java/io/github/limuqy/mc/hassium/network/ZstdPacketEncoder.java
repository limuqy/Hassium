package io.github.limuqy.mc.hassium.network;

import com.github.luben.zstd.Zstd;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import net.minecraft.network.FriendlyByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ZSTD 包压缩编码器
 * <p>
 * 替换原版的 CompressionEncoder（Zlib），使用 ZSTD 算法压缩网络包。
 * 保持与原版相同的线协议格式：VarInt(uncompressedLength) + compressedData
 * <p>
 * 注意：黑名单检查已在 MixinConnection 中完成，此处不再重复检查
 */
public class ZstdPacketEncoder extends MessageToByteEncoder<ByteBuf> {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/ZstdEncoder");

    private int threshold;
    private final int compressionLevel;

    public ZstdPacketEncoder(int threshold, int compressionLevel) {
        this.threshold = threshold;
        this.compressionLevel = compressionLevel;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf in, ByteBuf out) throws Exception {
        int readableBytes = in.readableBytes();
        FriendlyByteBuf friendlyBuf = new FriendlyByteBuf(out);

        if (readableBytes < this.threshold) {
            // 低于阈值：不压缩，写 VarInt(0) + 原始数据
            friendlyBuf.writeVarInt(0);
            friendlyBuf.writeBytes(in);
        } else {
            // 高于阈值：ZSTD 压缩
            byte[] input = new byte[readableBytes];
            in.readBytes(input);

            // 写入原始长度
            friendlyBuf.writeVarInt(input.length);

            // ZSTD 压缩
            byte[] compressed = Zstd.compress(input, this.compressionLevel);
            friendlyBuf.writeBytes(compressed);

            LOGGER.debug("Compressed packet: {} -> {} bytes ({}% reduction)",
                    input.length, compressed.length,
                    (1 - (double) compressed.length / input.length) * 100);
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
     * 获取压缩等级
     */
    public int getCompressionLevel() {
        return compressionLevel;
    }
}
