package io.github.limuqy.mc.hassium.network.dataplane;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 {@link VarIntLengthFrameSplitter} 把 TCP 字节流正确切成整帧，
 * 消除粘包/半包。
 */
class VarIntLengthFrameSplitterTest {

    private EmbeddedChannel newChannel() {
        return new EmbeddedChannel(new VarIntLengthFrameSplitter());
    }

    /** 把一整帧的 ByteBuf 内容拷成 byte[]（不读取长度头，整段）。 */
    private static byte[] frameBytes(ByteBuf buf) {
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        return out;
    }

    @Test @DisplayName("单帧整包到达：切出一帧，内容与原始编码完全一致")
    void singleWholeFrame() {
        byte[] payload = new byte[]{1, 2, 3, 4, 5};
        byte[] frame = DataPlaneFrame.encode(DataPlaneFrame.TYPE_KEEPALIVE, payload);
        EmbeddedChannel ch = newChannel();
        ch.writeInbound(Unpooled.wrappedBuffer(frame));
        ByteBuf out = ch.readInbound();
        assertNotNull(out);
        assertArrayEquals(frame, frameBytes(out));
        out.release();
        assertNull(ch.readInbound());
        ch.finishAndReleaseAll();
    }

    @Test @DisplayName("两个帧粘在一个 ByteBuf：依次切出两帧，内容完整")
    void twoFramesGlued() {
        byte[] f1 = DataPlaneFrame.encode(DataPlaneFrame.TYPE_BULK_COMPRESSED_CHUNK, new byte[]{10, 20});
        byte[] f2 = DataPlaneFrame.encode(DataPlaneFrame.TYPE_KEEPALIVE, new byte[]{30, 40, 50});
        byte[] glued = new byte[f1.length + f2.length];
        System.arraycopy(f1, 0, glued, 0, f1.length);
        System.arraycopy(f2, 0, glued, f1.length, f2.length);

        EmbeddedChannel ch = newChannel();
        ch.writeInbound(Unpooled.wrappedBuffer(glued));
        ByteBuf out1 = ch.readInbound();
        ByteBuf out2 = ch.readInbound();
        assertNotNull(out1);
        assertNotNull(out2);
        assertArrayEquals(f1, frameBytes(out1));
        assertArrayEquals(f2, frameBytes(out2));
        out1.release();
        out2.release();
        ch.finishAndReleaseAll();
    }

    @Test @DisplayName("一个帧被拆成两段：第一段到达无输出，凑齐后切出一帧")
    void frameSplitAcrossReads() {
        byte[] payload = new byte[]{11, 22, 33};
        byte[] frame = DataPlaneFrame.encode(DataPlaneFrame.TYPE_BULK_SECTION_DELTA, payload);
        int splitAt = 2; // 拆在长度头中间
        byte[] part1 = new byte[splitAt];
        byte[] part2 = new byte[frame.length - splitAt];
        System.arraycopy(frame, 0, part1, 0, splitAt);
        System.arraycopy(frame, splitAt, part2, 0, part2.length);

        EmbeddedChannel ch = newChannel();
        ch.writeInbound(Unpooled.wrappedBuffer(part1));
        assertNull(ch.readInbound(), "部分帧到达不应输出");
        ch.writeInbound(Unpooled.wrappedBuffer(part2));
        ByteBuf out = ch.readInbound();
        assertNotNull(out);
        assertArrayEquals(frame, frameBytes(out));
        out.release();
        ch.finishAndReleaseAll();
    }

    @Test @DisplayName("粘包 + 半包混合：整两帧 + 半帧一段，再补齐，切出三帧")
    void mixedGlueAndHalf() {
        byte[] f1 = DataPlaneFrame.encode(DataPlaneFrame.TYPE_KEEPALIVE, new byte[]{1});
        byte[] f2 = DataPlaneFrame.encode(DataPlaneFrame.TYPE_BULK_COMPRESSED_CHUNK, new byte[]{2, 3});
        byte[] f3 = DataPlaneFrame.encode(DataPlaneFrame.TYPE_CLOSE, new byte[]{4, 5, 6, 7});

        EmbeddedChannel ch = newChannel();
        // batch1 = f1 + f2 完整 + f3 的前一半
        int f3Split = 2;
        byte[] f3a = new byte[f3Split];
        byte[] f3b = new byte[f3.length - f3Split];
        System.arraycopy(f3, 0, f3a, 0, f3Split);
        System.arraycopy(f3, f3Split, f3b, 0, f3b.length);

        byte[] batch1 = new byte[f1.length + f2.length + f3a.length];
        int off = 0;
        System.arraycopy(f1, 0, batch1, off, f1.length); off += f1.length;
        System.arraycopy(f2, 0, batch1, off, f2.length); off += f2.length;
        System.arraycopy(f3a, 0, batch1, off, f3a.length);

        ch.writeInbound(Unpooled.wrappedBuffer(batch1));
        ByteBuf out1 = ch.readInbound();
        ByteBuf out2 = ch.readInbound();
        assertNotNull(out1);
        assertNotNull(out2);
        assertArrayEquals(f1, frameBytes(out1));
        assertArrayEquals(f2, frameBytes(out2));
        out1.release();
        out2.release();
        assertNull(ch.readInbound(), "f3 半帧到达不应输出");

        ch.writeInbound(Unpooled.wrappedBuffer(f3b));
        ByteBuf out3 = ch.readInbound();
        assertNotNull(out3);
        assertArrayEquals(f3, frameBytes(out3));
        out3.release();
        ch.finishAndReleaseAll();
    }

    @Test @DisplayName("异常大帧长度（超过上限）触发 DecoderException 并关闭通道")
    void oversizeFrameThrows() {
        // 构造一个声称 5 MiB 的长度头（body 不补齐），应立即触发 MAX_FRAME_LEN 上限保护
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        DataPlaneFrame.writeVarInt(bos, 5 * 1024 * 1024); // 5 MiB > MAX_FRAME_LEN(4 MiB)
        byte[] header = bos.toByteArray();

        // 在 splitter 之后挂一个 tail handler 捕获 exceptionCaught
        final java.util.concurrent.atomic.AtomicReference<Throwable> caught = new java.util.concurrent.atomic.AtomicReference<>();
        io.netty.channel.ChannelInboundHandlerAdapter catcher = new io.netty.channel.ChannelInboundHandlerAdapter() {
            @Override
            public void exceptionCaught(io.netty.channel.ChannelHandlerContext ctx, Throwable cause) {
                caught.set(cause);
            }
        };
        EmbeddedChannel ch = new EmbeddedChannel(new VarIntLengthFrameSplitter(), catcher);
        ch.writeInbound(Unpooled.wrappedBuffer(header));

        Throwable recorded = caught.get();
        assertNotNull(recorded, "超长帧应抛 DecoderException");
        assertTrue(recorded instanceof io.netty.handler.codec.DecoderException
                        || (recorded.getMessage() != null && recorded.getMessage().contains("frame length")),
                "应为 DecoderException，实际: " + recorded);
        ch.finishAndReleaseAll();
    }
}
