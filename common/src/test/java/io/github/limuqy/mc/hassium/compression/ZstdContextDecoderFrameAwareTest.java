package io.github.limuqy.mc.hassium.compression;

import com.github.luben.zstd.ZstdCompressCtx;
import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import io.github.limuqy.mc.hassium.network.ZstdContextDecoder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ZstdContextDecoder frameAware 压缩分支回归测试（M3 冒烟发现）：
 * 压缩单元与后续单元同段粘包时，原实现把 {@code readableBytes()} 全部当作压缩体——
 * 容量检查随粘包内容漂移（1.20.1 ClientboundLoginPacket 帧后紧跟小帧，
 * 8.39MB→9.44MB 递增）且尾随帧被吞。修复后按 RFC 8878 帧边界只消费本帧，
 * 尾随帧保留待下次 decode。
 */
public class ZstdContextDecoderFrameAwareTest {

    private static final int THRESHOLD = 256;

    @BeforeEach
    void enableMetrics() {
        NetworkStats.reset();
        NetworkStats.setEnabled(true);
    }

    @AfterEach
    void disableMetrics() {
        NetworkStats.reset();
        NetworkStats.setEnabled(false);
    }

    private static byte[] compress(byte[] input) {
        try (ZstdCompressCtx ctx = new ZstdCompressCtx()) {
            ctx.setLevel(3);
            return ctx.compress(input);
        }
    }

    private static void writeVarInt(ByteBuf buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    private static byte[] readAll(ByteBuf buf) {
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        buf.release();
        return out;
    }

    /** 两帧同段粘包：必须按帧边界消费，两帧都完整解出（旧实现尾帧被吞 → 本测试失败）。 */
    @Test
    public void testCoalescedFramesBothDecoded() {
        Random rnd = new Random(42);
        byte[] payload1 = new byte[300];
        byte[] payload2 = new byte[300];
        rnd.nextBytes(payload1);
        rnd.nextBytes(payload2);

        byte[] comp1 = compress(payload1);
        byte[] comp2 = compress(payload2);

        ByteBuf wire = Unpooled.buffer();
        writeVarInt(wire, payload1.length);
        wire.writeBytes(comp1);
        writeVarInt(wire, payload2.length);
        wire.writeBytes(comp2);
        int totalWire = wire.readableBytes();

        EmbeddedChannel ch = new EmbeddedChannel(new ZstdContextDecoder(THRESHOLD, true, false, true));
        try {
            assertTrue(ch.writeInbound(wire));
            ByteBuf out1 = ch.readInbound();
            assertNotNull(out1, "first frame must be decoded");
            assertArrayEquals(payload1, readAll(out1));

            // 尾随帧保留在累积缓冲，二次 decode 解出（旧实现直接吞掉 → null）
            ByteBuf out2 = ch.readInbound();
            assertNotNull(out2, "second coalesced frame must survive (frame-boundary consumption)");
            assertArrayEquals(payload2, readAll(out2));

            assertEquals(totalWire, NetworkStats.getMetrics().getActualBytesReceived(),
                    "both wire units must contribute to actualBytesReceived");
        } finally {
            ch.finishAndReleaseAll();
        }
    }

    /** 半包守卫：压缩帧被截断时整体回退等待，补齐后解出。 */
    @Test
    public void testPartialFrameWaitsThenDecodes() {
        Random rnd = new Random(7);
        byte[] payload = new byte[400];
        rnd.nextBytes(payload);
        byte[] comp = compress(payload);

        ByteBuf wire = Unpooled.buffer();
        writeVarInt(wire, payload.length);
        int headerAndFull = wire.readableBytes() + comp.length;
        wire.writeBytes(comp, 0, comp.length / 2); // 只给半帧

        EmbeddedChannel ch = new EmbeddedChannel(new ZstdContextDecoder(THRESHOLD, true, false, true));
        try {
            assertFalse(ch.writeInbound(wire), "partial frame must not produce output");
            assertNull(ch.readInbound());
            assertEquals(0, NetworkStats.getMetrics().getActualBytesReceived(),
                    "partial frame must not record wire bytes");

            // 补全剩余字节 → 解出完整帧
            ByteBuf rest = Unpooled.buffer();
            rest.writeBytes(comp, comp.length / 2, comp.length - comp.length / 2);
            assertTrue(ch.writeInbound(rest));
            ByteBuf out = ch.readInbound();
            assertNotNull(out);
            assertArrayEquals(payload, readAll(out));
            assertEquals(headerAndFull, NetworkStats.getMetrics().getActualBytesReceived(),
                    "wire bytes recorded only after full unit consumed");
        } finally {
            ch.finishAndReleaseAll();
        }
    }
}
