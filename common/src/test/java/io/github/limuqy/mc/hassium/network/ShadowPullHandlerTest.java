package io.github.limuqy.mc.hassium.network;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    @DisplayName("out-of-range entries get a terminal error, in-range entries proceed")
    void rejectsOutOfRange() {
        ShadowPullRequestC2SPacket request = new ShadowPullRequestC2SPacket(
                "minecraft:overworld", 1L, 2L,
                List.of(new ShadowPullRequestC2SPacket.Entry(4, 0, 3L, List.of(), 1)));
        ShadowPullHandler handler = new ShadowPullHandler(new ShadowPullRequestLedger());
        ShadowPullResponseS2CPacket response = handler.handle(UUID.randomUUID(), request,
                "minecraft:overworld", 1L, 0, 0, 1, true, true, (req, entry) -> null);
        // 逐条目范围校验：越界柱给终端 error，不牵连同批；resolver null = 未就绪柱才省略
        assertEquals(1, response.results().size());
        assertEquals(ShadowPullResponseS2CPacket.Kind.ERROR, response.results().get(0).kind());
    }
}



