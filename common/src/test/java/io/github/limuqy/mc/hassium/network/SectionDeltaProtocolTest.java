package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.network.sectiondelta.SectionPlaneSyndrome;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SectionDeltaProtocolTest {

    @Test
    @DisplayName("ShadowPull：非 0 section hash 后跟 48×u32 平面")
    void shadowPullRequestRoundTripsPlanes() {
        int[] planes = new int[SectionPlaneSyndrome.PLANE_COUNT];
        for (int i = 0; i < planes.length; i++) {
            planes[i] = 0x1000 + i;
        }
        long[] hashes = {0L, 0xABCDEFL, 0L};
        int[][] planeTable = {null, planes, null};
        ShadowPullRequestC2SPacket original = new ShadowPullRequestC2SPacket(
                "minecraft:overworld", 0L, 1L,
                List.of(new ShadowPullRequestC2SPacket.Entry(3, -4, 0x1234L,
                        List.of(hashes[0], hashes[1], hashes[2]), planeTable, 0)));

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            original.encode(buffer);
            ShadowPullRequestC2SPacket decoded = ShadowPullRequestC2SPacket.decode(buffer);
            assertEquals(original.dimension(), decoded.dimension());
            ShadowPullRequestC2SPacket.Entry entry = decoded.entries().get(0);
            assertEquals(3, entry.chunkX());
            assertEquals(-4, entry.chunkZ());
            assertEquals(List.of(0L, 0xABCDEFL, 0L), entry.sectionHashes());
            assertArrayEquals(planes, entry.planes()[1]);
            assertEquals(null, entry.planes()[0]);
        } finally {
            buffer.release();
        }
    }

    @Test
    @DisplayName("SectionDeltaS2C：FULL + BLOCKS + expectedChunkHash roundtrip")
    void sectionDeltaRoundTripsFullBlocksAndHash() {
        byte[] fullBytes = {0x0A, 0x0B, 0x0C};
        int packed = SectionPlaneSyndrome.packLocalPos(1, 2, 3);
        byte[] blocksPayload = SectionPlaneSyndrome.encodeBlockList(new int[] {packed}, new int[] {7});
        List<SectionDeltaS2CPacket.SectionData> sections = List.of(
                new SectionDeltaS2CPacket.SectionData(0, SectionDeltaS2CPacket.KIND_FULL, fullBytes),
                new SectionDeltaS2CPacket.SectionData(2, SectionDeltaS2CPacket.KIND_BLOCKS, blocksPayload));
        List<SectionDeltaS2CPacket.HeightmapData> heightmaps = List.of(
                new SectionDeltaS2CPacket.HeightmapData(0, new long[] {0x1122334455667788L}));
        long expectedHash = 0xCAFEBABEL;
        SectionDeltaS2CPacket original = new SectionDeltaS2CPacket(
                "minecraft:overworld",
                List.of(new SectionDeltaS2CPacket.DeltaEntry(
                        8, -2, sections, heightmaps, List.of(), expectedHash)),
                List.of(new SectionDeltaS2CPacket.SkippedChunk(1, 1)));

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            original.encode(buffer);
            SectionDeltaS2CPacket decoded = SectionDeltaS2CPacket.decode(buffer);
            assertEquals(original.dimension(), decoded.dimension());
            SectionDeltaS2CPacket.DeltaEntry entry = decoded.entries().get(0);
            assertEquals(expectedHash, entry.expectedChunkHash());
            assertEquals(SectionDeltaS2CPacket.KIND_FULL, entry.changedSections().get(0).kind());
            assertArrayEquals(fullBytes, entry.changedSections().get(0).blockData());
            SectionDeltaS2CPacket.SectionData blocks = entry.changedSections().get(1);
            assertEquals(SectionDeltaS2CPacket.KIND_BLOCKS, blocks.kind());
            assertArrayEquals(blocksPayload, blocks.blockData());
            assertEquals(4096 + 1, SectionDeltaS2CPacket.changedCells(decoded.entries().get(0).changedSections()));
            assertEquals(1, decoded.skipped().size());
            assertEquals(1, decoded.skipped().get(0).chunkX());
        } finally {
            buffer.release();
        }
    }
}
