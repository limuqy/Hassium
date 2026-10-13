package io.github.limuqy.mc.hassium.protocol;

import io.github.limuqy.mc.hassium.compat.PacketId;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AggregationLimitsTest {
    @Test
    void smallPacketBurstIsSplitIntoFramesTheDecoderAccepts() {
        NamespaceIndexManager index = new NamespaceIndexManager();
        List<AggregatedSubPacket> batch = new ArrayList<>();
        int decoded = 0;
        long bytes = 0;
        for (int i = 0; i < 8193; i++) {
            if (HassiumAggregationManager.shouldSplitBatch(batch.size(), bytes, 8, 256 * 1024)) {
                decoded += roundTrip(batch, index);
                batch.clear();
                bytes = 0;
            }
            batch.add(new AggregatedSubPacket(PacketId.parse("audit:small"), new byte[8]));
            bytes += 8;
        }
        decoded += roundTrip(batch, index);
        assertEquals(8193, decoded);
    }

    private static int roundTrip(List<AggregatedSubPacket> batch, NamespaceIndexManager index) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            new HassiumAggregationPacket(batch, index).encode(buf);
            return HassiumAggregationPacket.decode(buf, index).getSubPackets().size();
        } finally {
            buf.release();
        }
    }
}
