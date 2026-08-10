package io.github.limuqy.mc.hassium.network;

import com.github.luben.zstd.ZstdDecompressCtx;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import io.github.limuqy.mc.hassium.network.core.outbound.ControlFrameCodec;
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
 * <p>
 * frameAware 模式（网关管道专用，T9 修复）：uncompressedLength=0 的明文单元可能
 * 与后续单元在同一 TCP 段到达（Netty 合并相邻 writeAndFlush / Nagle 粘包），原实现
 * 把剩余字节全部透传会把下一个单元的 VarInt(0) 头带进下游 FrameDecoder 导致
 * {@code invalid control frame length: 0}。frameAware=true 时按 ControlFrameCodec
 * 帧边界只消费恰好一个明文帧，剩余保留待下次 decode（半包时整体回退等待）。
 */
public class ZstdContextDecoder extends ByteToMessageDecoder {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/ZstdContextDecoder");

    private static final int MAXIMUM_COMPRESSED_LENGTH = 2 * 1024 * 1024;
    private static final int MAXIMUM_UNCOMPRESSED_LENGTH = 8 * 1024 * 1024;

    private int threshold;
    private boolean validateDecompressed;
    private final ZstdDecompressCtx decompressCtx;
    private final boolean frameAware;
    private volatile boolean closed = false;

    /**
     * @param threshold            压缩阈值
     * @param validateDecompressed 是否验证解压大小
     * @param magicless            是否启用 magicless 模式
     * @param frameAware           明文单元按 ControlFrameCodec 帧边界消费（网关管道）
     */
    public ZstdContextDecoder(int threshold, boolean validateDecompressed, boolean magicless, boolean frameAware) {
        this.threshold = threshold;
        this.validateDecompressed = validateDecompressed;
        this.frameAware = frameAware;

        this.decompressCtx = new ZstdDecompressCtx();
        if (magicless) {
            this.decompressCtx.setMagicless(true);
            LOGGER.debug("Enabled magicless ZSTD decoder mode");
        }

        LOGGER.debug("Created ZSTD context decoder (threshold={}, magicless={}, frameAware={})",
                threshold, magicless, frameAware);
    }

    public ZstdContextDecoder(int threshold, boolean validateDecompressed, boolean magicless) {
        this(threshold, validateDecompressed, magicless, false);
    }

    public ZstdContextDecoder(int threshold, boolean validateDecompressed) {
        this(threshold, validateDecompressed, false, false);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (closed) {
            int inStart = in.readerIndex();
            out.add(in.readBytes(in.readableBytes()));
            NetworkStats.recordWireBytesReceived(in.readerIndex() - inStart);
            return;
        }

        if (in.readableBytes() == 0) {
            return;
        }

        int inStart = in.readerIndex();
        FriendlyByteBuf friendlyBuf = new FriendlyByteBuf(in);
        int uncompressedLength = friendlyBuf.readVarInt();

        if (uncompressedLength == 0) {
            // 与原版一致：剩余全部为未压缩包体
            if (frameAware) {
                // 明文单元 = 恰好一个 ControlFrameCodec 帧（帧协议自带帧长）。只消费一帧：
                // TCP 段可能合并多个单元，把后续单元的 VarInt(0) 头透传给 FrameDecoder 会
                // 误判为帧长 0；半包（帧不完整）整体回退等待更多数据。
                int frameStart = in.readerIndex();
                ControlFrameCodec.Frame f = ControlFrameCodec.tryDecodeFrame(in);
                if (f == null) {
                    in.readerIndex(inStart);
                    return;
                }
                f.payload().release();
                // 独立拷贝：decode 返回后累积缓冲会被 compact，slice 会随底层数据移动而错乱。
                // readBytes 从当前 ridx 读——先回退到帧起点再拷贝恰好一帧，剩余保留。
                int frameLen = in.readerIndex() - frameStart;
                in.readerIndex(frameStart);
                out.add(in.readBytes(frameLen));
            } else {
                out.add(friendlyBuf.readBytes(friendlyBuf.readableBytes()));
            }
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

            byte[] result;
            try {
                result = decompressCtx.decompress(compressed, uncompressedLength);
            } catch (Throwable t) {
                // 半包守卫：TCP 段边界不保证压缩单元完整（粘包段可能截断尾部）。
                // 压缩体不完整时 ZSTD 解压失败——整体回退等待更多数据（下次 channelRead 重试）；
                // 真损坏数据（TCP 校验兜底，概率可忽略）会一直等待，由连接读超时/对端关闭收敛。
                LOGGER.debug("Hassium: zstd decompress failed ({}), waiting for more data", t.toString());
                in.readerIndex(inStart);
                return;
            }

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Decompressed packet: {} -> {} bytes", compressedLength, result.length);
            }
            out.add(Unpooled.wrappedBuffer(result));
        }
        NetworkStats.recordWireBytesReceived(in.readerIndex() - inStart);
    }

    public void setThreshold(int threshold, boolean validateDecompressed) {
        this.threshold = threshold;
        this.validateDecompressed = validateDecompressed;
    }

    public int getThreshold() {
        return threshold;
    }

    @Override
    @SuppressWarnings("deprecation") // ChannelHandlerAdapter.exceptionCaught deprecated in Netty 4.1.97+
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        LOGGER.error("ZSTD decoder error", cause);
        ctx.fireExceptionCaught(cause);
    }

    @Override
    protected void handlerRemoved0(ChannelHandlerContext ctx) throws Exception {
        close(); // 释放 native 解压上下文，防泄漏（review-fix: T13-M3）
    }

    public synchronized void close() {
        if (!closed) {
            closed = true;
            decompressCtx.close();
            LOGGER.debug("Closed ZSTD context decoder");
        }
    }
}
