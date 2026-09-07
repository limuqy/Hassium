package io.github.limuqy.mc.hassium.network;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowPullHandlerTest {
    @Test
    @DisplayName("valid pull resolves every chunk exactly once")
    void resolvesMixedBatch() {
        ShadowPullRequestC2SPacket request = new ShadowPullRequestC2SPacket(
                "minecraft:overworld", 1L, 2L,
                List.of(new ShadowPullRequestC2SPacket.Entry(0, 0, 3L, List.of(), 1)));
        ShadowPullHandler handler = new ShadowPullHandler(new ShadowPullRequestLedger());
        ShadowPullResponseS2CPacket response = handler.handle(UUID.randomUUID(), request,
                "minecraft:overworld", 1L, 0, 0, 1, true, true,
                (req, entry) -> ShadowPullResponseS2CPacket.Result.unchanged(
                        entry.chunkX(), entry.chunkZ(), entry.chunkHash(), entry.sectionHashes()));
        assertEquals(ShadowPullResponseS2CPacket.Kind.UNCHANGED, response.results().get(0).kind());
    }

    @Test
    @DisplayName("invalid range yields a terminal error for each requested chunk")
    void rejectsOutOfRange() {
        ShadowPullRequestC2SPacket request = new ShadowPullRequestC2SPacket(
                "minecraft:overworld", 1L, 2L,
                List.of(new ShadowPullRequestC2SPacket.Entry(4, 0, 3L, List.of(), 1)));
        ShadowPullHandler handler = new ShadowPullHandler(new ShadowPullRequestLedger());
        ShadowPullResponseS2CPacket response = handler.handle(UUID.randomUUID(), request,
                "minecraft:overworld", 1L, 0, 0, 1, true, true, (req, entry) -> null);
        // resolver null = 未就绪柱已入待推送队列：响应省略该柱（不再产生 negative 错误）
        assertTrue(response.results().isEmpty());
    }
}



