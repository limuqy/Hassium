package io.github.limuqy.mc.hassium.network;

import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ShadowPullPacketTest {
    @Test
    @DisplayName("shadowPullV1 round-trips bounded mixed results")
    void roundTripsMixedResults() {
        ShadowPullRequestC2SPacket request = new ShadowPullRequestC2SPacket(
                "minecraft:overworld", 3L, 7L,
                List.of(new ShadowPullRequestC2SPacket.Entry(1, -2, 11L, List.of(12L, 13L), 4)));
        FriendlyByteBuf requestBuf = new FriendlyByteBuf(Unpooled.buffer());
        request.encode(requestBuf);
        ShadowPullRequestC2SPacket decodedRequest = ShadowPullRequestC2SPacket.decode(requestBuf);
        assertEquals(request, decodedRequest);

        ShadowPullResponseS2CPacket response = new ShadowPullResponseS2CPacket(
                "minecraft:overworld", 3L, 7L,
                List.of(
                        ShadowPullResponseS2CPacket.Result.unchanged(1, -2, 11L, List.of(12L)),
                        ShadowPullResponseS2CPacket.Result.payload(2, -2,
                                ShadowPullResponseS2CPacket.Kind.DELTA, 14L, List.of(15L),
                                new byte[]{1, 2}),
                        ShadowPullResponseS2CPacket.Result.error(3, -2, "out-of-range")));
        FriendlyByteBuf responseBuf = new FriendlyByteBuf(Unpooled.buffer());
        response.encode(responseBuf);
        ShadowPullResponseS2CPacket decodedResponse = ShadowPullResponseS2CPacket.decode(responseBuf);
        assertEquals(response.dimension(), decodedResponse.dimension());
        assertEquals(response.epoch(), decodedResponse.epoch());
        assertEquals(response.requestId(), decodedResponse.requestId());
        assertEquals(response.results().size(), decodedResponse.results().size());
        for (int i = 0; i < response.results().size(); i++) {
            ShadowPullResponseS2CPacket.Result expected = response.results().get(i);
            ShadowPullResponseS2CPacket.Result actual = decodedResponse.results().get(i);
            assertEquals(expected.chunkX(), actual.chunkX());
            assertEquals(expected.chunkZ(), actual.chunkZ());
            assertEquals(expected.kind(), actual.kind());
            assertEquals(expected.chunkHash(), actual.chunkHash());
            assertEquals(expected.sectionHashes(), actual.sectionHashes());
            assertArrayEquals(expected.payload(), actual.payload());
            assertEquals(expected.error(), actual.error());
        }
    }

    @Test
    @DisplayName("shadowPullV1 rejects oversized batches and invalid result kinds")
    void rejectsOversizedInput() {
        assertThrows(IllegalArgumentException.class,
                () -> new ShadowPullRequestC2SPacket("minecraft:overworld", 0, 1,
                        java.util.stream.IntStream.range(0, 385)
                                .mapToObj(i -> new ShadowPullRequestC2SPacket.Entry(i, 0, 0L, List.of(), 0))
                                .toList()));
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeUtf("minecraft:overworld", 128);
        buf.writeVarLong(0L);
        buf.writeVarLong(1L);
        buf.writeVarInt(1);
        buf.writeVarInt(0);
        buf.writeVarInt(0);
        buf.writeVarInt(99);
        assertThrows(IllegalArgumentException.class, () -> ShadowPullResponseS2CPacket.decode(buf));
    }
}
