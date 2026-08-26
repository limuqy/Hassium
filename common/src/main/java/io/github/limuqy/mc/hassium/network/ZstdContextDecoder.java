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
 * <p>
 * M3 冒烟补强（T5Verification）：压缩单元同样会与后续单元同段粘包（1.20.1
 * ClientboundLoginPacket 帧后紧跟 difficulty/abilities 等小帧），原实现把
 * {@code readableBytes()} 全部当作压缩体——容量检查随粘包内容漂移且尾随帧被吞。
 * frameAware=true 时压缩分支按 RFC 8878 帧头/块头解析首帧边界，只消费本帧
 * （{@link #parseFrameCompressedLength}），剩余留待下次 decode，与明文分支同语义。
 */
public class ZstdContextDecoder extends ByteToMessageDecoder {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/ZstdContextDecoder");

    // 压缩帧长度上限 = 解压上限 + ZSTD 冗余（M3 冒烟实测：1.20.1 ClientboundLoginPacket
    // 内嵌 RegistryAccess$Frozen 全量注册表，解压体积贴近 8MiB 上限且数据近乎不可压，
    // ZSTD level3 后 8389820B ≈ 解压体积 + 1.2KB 帧头——旧 2MiB 上限与 8MiB 对齐上限
    // 都拦不住，必须留出 ZSTD 帧头/字面量段开销余量。防炸弹的硬约束是解压上限，
    // 压缩上限仅为内存保护，冗余 1MiB 无安全损失）。
    private static final int MAXIMUM_COMPRESSED_LENGTH = 8 * 1024 * 1024 + 1024 * 1024;
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
        if (!hasCompleteVarInt(in)) {
            // 半包：VarInt 头被 TCP 截断。读一个字节都会 IOB，必须等后续段，
            // 否则 DecoderException → 网关 FAILOVER 把 ACTIVE 打成 IDLE。
            return;
        }
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

            byte[] compressed;
            if (frameAware) {
                // 压缩单元与后续单元可能同段粘包（M3 冒烟实测：1.20.1 ClientboundLoginPacket
                // 帧后紧跟 difficulty/abilities 等小帧——readableBytes() 把尾随帧计入压缩体，
                // 容量检查随粘包内容漂移 8.39MB→9.44MB 且尾随帧被吞）。与明文分支同语义：
                // 按 zstd 帧边界（RFC 8878 帧头/块头解析）只消费本帧，剩余留待下次 decode。
                long frameLen = parseFrameCompressedLength(in, in.readerIndex(), in.readableBytes());
                if (frameLen < 0) {
                    // 半包：帧头/数据块未齐，整体回退等待更多数据
                    in.readerIndex(inStart);
                    return;
                }
                if (frameLen > MAXIMUM_COMPRESSED_LENGTH) {
                    throw new DecoderException("Badly compressed packet - compressed size " +
                            frameLen + " exceeds maximum " + MAXIMUM_COMPRESSED_LENGTH);
                }
                if (in.readableBytes() < frameLen) {
                    in.readerIndex(inStart);
                    return;
                }
                compressed = new byte[(int) frameLen];
                friendlyBuf.readBytes(compressed);
            } else {
                int compressedLength = friendlyBuf.readableBytes();
                if (compressedLength > MAXIMUM_COMPRESSED_LENGTH) {
                    throw new DecoderException("Badly compressed packet - compressed size " +
                            compressedLength + " exceeds maximum " + MAXIMUM_COMPRESSED_LENGTH);
                }
                compressed = new byte[compressedLength];
                friendlyBuf.readBytes(compressed);
            }

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
                LOGGER.debug("Decompressed packet: {} -> {} bytes", compressed.length, result.length);
            }
            out.add(Unpooled.wrappedBuffer(result));
        }

        // 仅成功消费路径记账；半包回退（readerIndex 已还原）不记，避免重复累计。
        NetworkStats.recordWireBytesReceived(in.readerIndex() - inStart);
    }

    /**
     * 压缩单元前缀是 VarInt(uncompressedLength)。TCP 段可能只到续组字节，
     * {@code FriendlyByteBuf.readVarInt} 会 IOB；半包必须等待，不得关连接。
     */
    static boolean hasCompleteVarInt(ByteBuf in) {
        int idx = in.readerIndex();
        int end = in.writerIndex();
        for (int i = 0; i < 5; i++) {
            if (idx + i >= end) {
                return false;
            }
            if ((in.getByte(idx + i) & 0x80) == 0) {
                return true;
            }
        }
        return true;
    }

    /**
     * 解析 zstd 帧（RFC 8878）首帧的压缩体长度（含 magic/帧头/全部数据块/内容校验和）。
     * 压缩单元可能与后续单元同段粘包（M3 冒烟实测），压缩体长度必须按帧边界确定，
     * 不能用 {@code readableBytes()}——后者会把尾随帧计入且导致容量检查随粘包漂移。
     *
     * @param start     帧起点（zstd magic 所在偏移）
     * @param available 从 {@code start} 起可读字节数
     * @return 首帧压缩体字节数；数据不完整（半包）返回 -1
     */
    private static long parseFrameCompressedLength(ByteBuf in, int start, int available) {
        // 全部按相对 start 的偏移推进（available 亦是相对长度，不得与绝对 pos 混用）
        if (available < 4) {
            return -1;
        }
        // RFC 8878 §3.1.1：Magic_Number 4 字节小端 0xFD2FB528（线上 28 B5 2F FD）
        if (Integer.reverseBytes(in.getInt(start)) != 0xFD2FB528) {
            throw new DecoderException("Badly compressed packet - invalid zstd magic");
        }
        if (available < 5) {
            return -1;
        }
        int fhd = in.getUnsignedByte(start + 4);
        boolean singleSegment = (fhd & 0x20) != 0;
        if ((fhd & 0x8) != 0) {
            // §3.1.1.1.1.4 Reserved_bit 必须为 0
            throw new DecoderException("Badly compressed packet - reserved zstd header bit set");
        }
        int pos = 5;
        if (!singleSegment) {
            if (available < pos + 1) {
                return -1;
            }
            pos += 1; // Window_Descriptor
        }
        // §3.1.1.1 字段序：Window_Descriptor → Dictionary_ID → Frame_Content_Size
        int didSize = switch (fhd & 0x3) {
            case 0 -> 0;
            case 1 -> 1;
            case 2 -> 2;
            default -> 4;
        };
        if (available < pos + didSize) {
            return -1;
        }
        pos += didSize;
        // §3.1.1.1.1.1 FCS_Field_Size：flag 0=单段 1B/多段无，1=2B，2=4B，3=8B
        int fcsSize = switch ((fhd >> 6) & 0x3) {
            case 0 -> singleSegment ? 1 : 0;
            case 1 -> 2;
            case 2 -> 4;
            default -> 8;
        };
        if (available < pos + fcsSize) {
            return -1;
        }
        pos += fcsSize;
        // §3.1.1.2 块：Block_Header 3 字节小端——Last_Block=bit0，Block_Type=bits1-2，
        // Block_Size=bits3-23（数据块长度；RLE 块数据只有 1 字节）
        while (true) {
            if (available < pos + 3) {
                return -1;
            }
            int blockHeader = in.getUnsignedByte(start + pos)
                    | (in.getUnsignedByte(start + pos + 1) << 8)
                    | (in.getUnsignedByte(start + pos + 2) << 16);
            boolean lastBlock = (blockHeader & 0x1) != 0;
            int blockType = (blockHeader >> 1) & 0x3;
            int blockSize = (blockHeader >> 3) & 0x1FFFFF;
            if (blockType == 3) {
                throw new DecoderException("Badly compressed packet - reserved zstd block type");
            }
            int dataLen = blockType == 1 ? 1 : blockSize;
            if (available < pos + 3 + dataLen) {
                return -1;
            }
            pos += 3 + dataLen;
            if (lastBlock) {
                break;
            }
        }
        if ((fhd & 0x4) != 0) { // Content_Checksum
            if (available < pos + 4) {
                return -1;
            }
            pos += 4;
        }
        return pos;
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
