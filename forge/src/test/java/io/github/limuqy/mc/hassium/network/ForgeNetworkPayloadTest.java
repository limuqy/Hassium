package io.github.limuqy.mc.hassium.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class ForgeNetworkPayloadTest {

    @Test
    void aggregationPayloadUsesASeparateWireWrapper() {
        byte[] data = new byte[]{0, 1, 2, 127, -1};
        ForgeNetworkManager.AggregationWrapper original = new ForgeNetworkManager.AggregationWrapper(data);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            original.encode(buf);
            ForgeNetworkManager.AggregationWrapper decoded = ForgeNetworkManager.AggregationWrapper.decode(buf);

            assertArrayEquals(data, decoded.data());
        } finally {
            buf.release();
        }
    }
}
