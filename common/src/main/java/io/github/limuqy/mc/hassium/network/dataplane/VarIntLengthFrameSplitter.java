package io.github.limuqy.mc.hassium.network.dataplane;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * Data Plane 帧切分器：按 VarInt 长度头把 TCP 字节流切成「整帧」ByteBuf。
 * <p>
 * 线格式：{@code VarInt(frameLen) + frameLen 字节}（= {@link DataPlaneFrame#encode} 输出）。
 * Netty 的 {@code LengthFieldBasedFrameDecoder} 假设定长长度字段，不适配 MC VarInt，
 * 故此处在 decode 中手动读 VarInt 头，剩余不足整帧时回退读指针等待更多数据。
 * <p>
 * 切出的每个 ByteBuf = <b>完整一帧</b>（含 VarInt 长度头与 body），body 范围与
 * {@link DataPlaneCodec#decrypt} / {@link DataPlaneFrame#decodeType} 等下游「整帧当一帧」逻辑一致，
 * 因此下游解析代码无需改动 —— 切分器只保证每次 {@code channelRead} 拿到的是恰好一帧，
 * 消除 TCP 粘包/半包导致的多帧拼成一帧或一帧被拆分的问题。
 * <p>
 * 保护：VarInt 头最多读 5 字节（int32 上限）；单帧上限 {@link #MAX_FRAME_LEN}（4 MiB），
 * 超出视为损坏帧直接断连，防止 CFB8 错误密钥乱码产生异常大读。
 * <ul>
 *   <li>读指针未整帧就绪时 {@code decode} 不消费，等下次数据到达</li>
 *   <li>切出一帧后 {@code decode} 会继续循环切下一帧（{@code ByteToMessage} 默认行为）</li>
 * </ul>
 */
public class VarIntLengthFrameSplitter extends ByteToMessageDecoder {

    /** 单帧上限 4 MiB（含长度头与 body）。PoC bulk 帧远小于此值。 */
    private static final int MAX_FRAME_LEN = 4 * 1024 * 1024;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        while (in.readableBytes() > 0) {
            in.markReaderIndex();
            // ---- 读 VarInt 长度头（带 5 字节上限保护）----
            int frameLen = 0;
            int shift = 0;
            boolean completeHeader = false;
            int headerBytes = 0;
            while (headerBytes < 5 && in.isReadable()) {
                byte b = in.readByte();
                headerBytes++;
                frameLen |= (b & 0x7F) << shift;
                shift += 7;
                if ((b & 0x80) == 0) {
                    completeHeader = true;
                    break;
                }
            }
            if (!completeHeader) {
                // 长度头尚未读全（或超过 5 字节仍未终止）
                in.resetReaderIndex();
                return; // 等更多数据
            }
            if (frameLen <= 0 || frameLen > MAX_FRAME_LEN) {
                throw new io.netty.handler.codec.DecoderException(
                        "Invalid Data Plane frame length " + frameLen
                                + " (max " + MAX_FRAME_LEN + "), closing channel");
            }
            // ---- 检查整帧 body 是否就绪 ----
            if (in.readableBytes() < frameLen) {
                in.resetReaderIndex(); // body 不完整，等更多数据
                return;
            }
            // ---- 切出整帧（含长度头 + body）----
            int totalFrameBytes = headerBytes + frameLen;
            // reset 到帧头起点，整段读出（含长度头），保证下游 decrypt 拿到与 encode 一致的完整字节
            in.resetReaderIndex();
            ByteBuf frame = in.readRetainedSlice(totalFrameBytes);
            out.add(frame);
        }
    }
}
