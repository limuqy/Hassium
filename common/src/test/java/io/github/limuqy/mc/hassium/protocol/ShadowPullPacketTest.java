package io.github.limuqy.mc.hassium.protocol;

import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowPullPacketTest {
    @Test
    @DisplayName("shadowPullV1 round-trips bounded mixed results")
    void roundTripsMixedResults() {
        int[] plane0 = new int[io.github.limuqy.mc.hassium.protocol.sectiondelta.SectionPlaneSyndrome.PLANE_COUNT];
        plane0[0] = 1;
        plane0[1] = 2;
        plane0[2] = 3;
        int[] plane1 = new int[io.github.limuqy.mc.hassium.protocol.sectiondelta.SectionPlaneSyndrome.PLANE_COUNT];
        plane1[0] = 4;
        plane1[1] = 5;
        plane1[2] = 6;
        ShadowPullRequestC2SPacket request = new ShadowPullRequestC2SPacket(
                "minecraft:overworld", 3L, 7L,
                List.of(new ShadowPullRequestC2SPacket.Entry(1, -2, 11L, List.of(12L, 13L),
                        new int[][] {plane0, plane1}, 4)));
        FriendlyByteBuf requestBuf = new FriendlyByteBuf(Unpooled.buffer());
        request.encode(requestBuf);
        ShadowPullRequestC2SPacket decodedRequest = ShadowPullRequestC2SPacket.decode(requestBuf);
        assertEquals(request.dimension(), decodedRequest.dimension());
        assertEquals(request.epoch(), decodedRequest.epoch());
        assertEquals(request.requestId(), decodedRequest.requestId());
        assertEquals(1, decodedRequest.entries().size());
        ShadowPullRequestC2SPacket.Entry decodedEntry = decodedRequest.entries().get(0);
        assertEquals(1, decodedEntry.chunkX());
        assertEquals(-2, decodedEntry.chunkZ());
        assertEquals(11L, decodedEntry.chunkHash());
        assertEquals(List.of(12L, 13L), decodedEntry.sectionHashes());
        assertEquals(4, decodedEntry.lightGeneration());
        assertArrayEquals(plane0, decodedEntry.planes()[0]);
        assertArrayEquals(plane1, decodedEntry.planes()[1]);

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
    @DisplayName("shadowPullV1 batches are bounded by encoded size, not just entry count")
    void batchesAreBoundedByEncodedSize() {
        int planeCount = io.github.limuqy.mc.hassium.protocol.sectiondelta.SectionPlaneSyndrome.PLANE_COUNT;
        // 每柱 64 段非零 hash，每段带 planeCount 个 int —— 单柱约 64 * (8 + 4*48) ≈ 12.8 KB。
        // 这正是「MAX_ENTRIES=384 条上限界不住载荷」的成因。
        List<Long> sectionHashes = java.util.stream.LongStream.rangeClosed(1, 64).boxed().toList();
        int[][] planes = new int[64][];
        for (int i = 0; i < 64; i++) {
            planes[i] = new int[planeCount];
            planes[i][0] = i + 1;
        }
        List<ShadowPullRequestC2SPacket.Entry> heavy = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            heavy.add(new ShadowPullRequestC2SPacket.Entry(i, 0, 1L, sectionHashes, planes, 0));
        }
        int budget = ShadowPullRequestC2SPacket.MAX_PAYLOAD_BYTES;
        List<List<ShadowPullRequestC2SPacket.Entry>> batches =
                ShadowPullRequestC2SPacket.batchesByEncodedSize("minecraft:overworld", heavy, budget);
        // 不丢条目，且每条都真的落进预算内（不是按条数猜的）
        assertEquals(40, batches.stream().mapToInt(List::size).sum());
        assertTrue(batches.size() > 1, "heavy batch should have been split");
        for (List<ShadowPullRequestC2SPacket.Entry> batch : batches) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            new ShadowPullRequestC2SPacket("minecraft:overworld", 0L, 1L, batch).encode(buf);
            assertTrue(buf.readableBytes() <= budget, "batch exceeded budget: " + buf.readableBytes());
        }
        // 对照组：同样 40 条但无分段载荷 —— 一条就够，证明切分由字节驱动而非条数
        List<ShadowPullRequestC2SPacket.Entry> light = java.util.stream.IntStream.range(0, 40)
                .mapToObj(i -> new ShadowPullRequestC2SPacket.Entry(i, 0, 0L, List.of(), 0))
                .toList();
        assertEquals(1, ShadowPullRequestC2SPacket
                .batchesByEncodedSize("minecraft:overworld", light, budget).size());
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