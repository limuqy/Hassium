package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.network.LightDeltaS2CPacket.Entry;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LightDeltaS2C append-only 兼容格式验证：
 * 新版在旧字段后追加每 entry 一对 empty 掩码；旧接收端只读旧字段自然忽略尾块，
 * 新接收端对旧格式（无尾块）按空掩码降级。
 */
class LightDeltaS2CPacketTest {

    private static BitSet bits(int... indexes) {
        BitSet bits = new BitSet();
        for (int i : indexes) {
            bits.set(i);
        }
        return bits;
    }

    @Test
    void roundTripWithEmptyMasks() {
        LightDeltaS2CPacket packet = new LightDeltaS2CPacket(List.of(
                new Entry(3, -7, bits(0, 5), bits(2), bits(9), bits(1, 4)),
                new Entry(-11, 42, bits(), bits(6), bits(3), bits())));

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        packet.encode(buf);
        LightDeltaS2CPacket decoded = LightDeltaS2CPacket.decode(buf);

        assertEquals(2, decoded.entries().size());
        assertEquals(3, decoded.entries().get(0).chunkX());
        assertEquals(-7, decoded.entries().get(0).chunkZ());
        assertEquals(bits(0, 5), decoded.entries().get(0).skyYMask());
        assertEquals(bits(2), decoded.entries().get(0).blockYMask());
        assertEquals(bits(9), decoded.entries().get(0).emptySkyYMask());
        assertEquals(bits(1, 4), decoded.entries().get(0).emptyBlockYMask());
        assertTrue(decoded.entries().get(1).skyYMask().isEmpty());
        assertEquals(bits(6), decoded.entries().get(1).blockYMask());
        assertEquals(bits(3), decoded.entries().get(1).emptySkyYMask());
        assertTrue(decoded.entries().get(1).emptyBlockYMask().isEmpty());
    }

    @Test
    void emptyPacketRoundTrip() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        new LightDeltaS2CPacket(List.of()).encode(buf);
        LightDeltaS2CPacket decoded = LightDeltaS2CPacket.decode(buf);
        assertTrue(decoded.entries().isEmpty());
    }

    @Test
    void legacyWireWithoutEmptyTailDecodesToEmptyMasks() {
        // 旧格式：count + N × (chunkX, chunkZ, skyYMask, blockYMask)，无尾块。
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(1);
        buf.writeVarInt(5);
        buf.writeVarInt(-6);
        buf.writeBitSet(bits(0, 2));
        buf.writeBitSet(bits(1));

        LightDeltaS2CPacket decoded = LightDeltaS2CPacket.decode(buf);

        assertEquals(1, decoded.entries().size());
        assertEquals(bits(0, 2), decoded.entries().get(0).skyYMask());
        assertEquals(bits(1), decoded.entries().get(0).blockYMask());
        assertTrue(decoded.entries().get(0).emptySkyYMask().isEmpty(), "旧格式 empty sky 应为空");
        assertTrue(decoded.entries().get(0).emptyBlockYMask().isEmpty(), "旧格式 empty block 应为空");
        assertFalse(buf.isReadable(), "旧格式应完整消费");
    }

    @Test
    void legacyMultiEntryWithoutEmptyTail() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(2);
        for (int i = 0; i < 2; i++) {
            buf.writeVarInt(i);
            buf.writeVarInt(-i);
            buf.writeBitSet(bits(i));
            buf.writeBitSet(bits(i + 1));
        }

        LightDeltaS2CPacket decoded = LightDeltaS2CPacket.decode(buf);

        assertEquals(2, decoded.entries().size());
        for (Entry entry : decoded.entries()) {
            assertTrue(entry.emptySkyYMask().isEmpty());
            assertTrue(entry.emptyBlockYMask().isEmpty());
        }
        assertFalse(buf.isReadable());
    }
}
