package io.github.limuqy.mc.hassium.network.dataplane;

import io.github.limuqy.mc.hassium.network.SectionDeltaS2CPacket;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 8 — section delta 经 UDP 数据面端到端解调并走现有 metadata handler 的回归。
 *
 * <p>两个契约：
 * <ul>
 *   <li>{@code authenticatedUdpSectionDeltaDecodesAndUsesExistingMetadataHandler}：将一个真的
 *       SectionDelta 包 encode → 注入 bundle 的 receiveForTest seam → 校验注入的
 *       SectionDeltaDispatcher 收到 equals 原始包的解码包（命中 dispatchReceived = type 4 分支）。</li>
 *   <li>{@code sectionDeltaUsesUdpWhenHealthyAndPrimaryWhenNoSessionExists}：服务端在没有绑定 session
 *       时 tryRouteBulk(TYPE_BULK_SECTION_DELTA) 返回 false，意味着 caller fall back 到 Primary
 *       路径（正常情况；服务端只要 advertise 没收到 BindRequest 时不消耗数据帧）。</li>
 * </ul>
 */
class UdpSectionDeltaDispatchTest {

    private DataPlaneClientBundle bundle;

    @BeforeEach
    void setUp() {
        DataPlaneClientBundle.resetDataBulkCounters();
        bundle = new DataPlaneClientBundle();
    }

    @AfterEach
    void tearDown() {
        try { bundle.shutdown(); } catch (Throwable ignored) {}
        DataPlaneClientBundle.resetDataBulkCounters();
    }

    @Test
    @DisplayName("BULK_SECTION_DELTA → 解码并在注入 dispatcher 投递原始 packet")
    void authenticatedUdpSectionDeltaDecodesAndUsesExistingMetadataHandler() {
        AtomicReference<SectionDeltaS2CPacket> handled = new AtomicReference<>();
        bundle.setSectionDeltaDispatcherForTest(handled::set);

        SectionDeltaS2CPacket original = fixtureDeltaPacket();
        FriendlyByteBuf out = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        original.encode(out);
        byte[] bytes = new byte[out.readableBytes()];
        out.getBytes(out.readerIndex(), bytes);

        bundle.receiveForTest(DataPlaneFrame.TYPE_BULK_SECTION_DELTA, bytes);

        SectionDeltaS2CPacket got = handled.get();
        assertNotNull(got, "section delta dispatcher 被调用");
        assertEquals(original.dimension(), got.dimension(), "维度一致");
        assertEquals(original.entries().size(), got.entries().size(), "DeltaEntry 数量一致");
        assertEquals(original.entries().get(0).expectedChunkHash(),
                got.entries().get(0).expectedChunkHash(), "expectedChunkHash 一致");
        assertEquals(original.entries().get(0).changedSections().get(0).kind(),
                got.entries().get(0).changedSections().get(0).kind(), "section kind 一致");
        for (int i = 0; i < original.entries().get(0).heightmaps().size(); i++) {
            assertEquals(original.entries().get(0).heightmaps().get(i).typeId(),
                    got.entries().get(0).heightmaps().get(i).typeId(), "heightmap typeId 一致");
            assertArrayEquals(original.entries().get(0).heightmaps().get(i).data(),
                    got.entries().get(0).heightmaps().get(i).data(), "heightmap rawData 一致");
        }
        assertEquals(1, DataPlaneClientBundle.getBulkFramesData(), "帧计数 +1");
        assertEquals(bytes.length, DataPlaneClientBundle.getBulkBytesData(), "字节累计 = payload 长度");
    }

    @Test
    @DisplayName("没有 session 的玩家 → tryRouteBulk(SECTION_DELTA) 返回 false，caller 走 Primary")
    void sectionDeltaUsesPrimaryWhenNoSessionExists() {
        UUID player = UUID.randomUUID();
        // 没有任何已绑定 UDP session 的玩家：data plane 应当让 caller 回退 Primary。
        boolean routed = io.github.limuqy.mc.hassium.network.dataplane.DataPlaneServer.tryRouteBulk(
                player,
                DataPlaneFrame.TYPE_BULK_SECTION_DELTA,
                new byte[] {0x01, 0x02});
        assertFalse(routed, "无 session 应当回退到 Primary");
    }

    private static SectionDeltaS2CPacket fixtureDeltaPacket() {
        List<SectionDeltaS2CPacket.DeltaEntry> entries = new ArrayList<>();
        List<SectionDeltaS2CPacket.SectionData> sections = new ArrayList<>();
        sections.add(new SectionDeltaS2CPacket.SectionData(
                0, SectionDeltaS2CPacket.KIND_FULL, new byte[] {0x0A, 0x0B, 0x0C}));
        List<SectionDeltaS2CPacket.BlockEntityData> blockEntities = new ArrayList<>();
        List<SectionDeltaS2CPacket.HeightmapData> heightmaps = new ArrayList<>();
        heightmaps.add(new SectionDeltaS2CPacket.HeightmapData(0, new long[] {0x1122334455667788L, 0x99AABBCCDDEEFF00L}));
        heightmaps.add(new SectionDeltaS2CPacket.HeightmapData(3, new long[] {0x0F0F0F0F0F0F0F0FL}));
        entries.add(new SectionDeltaS2CPacket.DeltaEntry(
                32, -16,
                sections,
                heightmaps,
                blockEntities,
                0x5A5A5A5A5A5A5A5AL
        ));
        List<SectionDeltaS2CPacket.SkippedChunk> skipped = new ArrayList<>();
        return new SectionDeltaS2CPacket("minecraft:overworld", entries, skipped);
    }
}
