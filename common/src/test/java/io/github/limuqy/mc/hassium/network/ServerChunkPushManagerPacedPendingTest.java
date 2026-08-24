package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.server.RuntimeServerContext;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 批封装、pending-confirm 收敛及 BE 直发边界的确定性契约。 */
class ServerChunkPushManagerPacedPendingTest {

    @Test
    void shouldPaceChunkSends_dependsOnDedicatedNotCompression() {
        boolean prev = RuntimeServerContext.isDedicatedServerContext();
        try {
            RuntimeServerContext.setDedicatedServer(false);
            assertFalse(ServerChunkPushManager.shouldPaceChunkSends());
            RuntimeServerContext.setDedicatedServer(true);
            assertTrue(ServerChunkPushManager.shouldPaceChunkSends());
        } finally {
            RuntimeServerContext.setDedicatedServer(prev);
        }
    }

    @Test
    void sealedBatches_areBoundedPerPlayerAndFreedWhenConsumed() {
        ServerChunkPushManager.PlayerPushQueue queue = new ServerChunkPushManager.PlayerPushQueue();
        for (int i = 0; i < ServerChunkPushManager.MAX_QUEUED_BATCHES_PER_PLAYER; i++) {
            assertTrue(queue.tryReserveSealedBatch());
        }
        assertEquals(ServerChunkPushManager.MAX_QUEUED_BATCHES_PER_PLAYER, queue.queuedBatchCount());
        assertFalse(queue.tryReserveSealedBatch(), "第 11 个已封装批次不得入通道");

        queue.dequeueSealedBatch();
        assertEquals(ServerChunkPushManager.MAX_QUEUED_BATCHES_PER_PLAYER - 1, queue.queuedBatchCount());
        assertTrue(queue.tryReserveSealedBatch(), "消费者取走一个批次后应释放一个名额");
    }

    @Test
    void abandonedSeal_releasesItsBatchSlot() {
        ServerChunkPushManager.PlayerPushQueue queue = new ServerChunkPushManager.PlayerPushQueue();
        assertTrue(queue.tryReserveSealedBatch());
        queue.releaseSealedBatchReservation();
        assertEquals(0, queue.queuedBatchCount());
    }

    @Test
    void eachTickUsesConfiguredChunkLimitForOneSealedBatch() {
        assertEquals(7, ServerChunkPushManager.normalizeMaxChunksPerTick(7));
        assertEquals(4, ServerChunkPushManager.normalizeMaxChunksPerTick(0));
        assertEquals(4, ServerChunkPushManager.normalizeMaxChunksPerTick(-1));
    }

    @Test
    void bloomMissWithoutSessionRecord_skipsHashBeforeStrippedFull() {
        assertTrue(ServerChunkPushManager.shouldDirectPushWithoutHash(true, null, true));
        assertFalse(ServerChunkPushManager.shouldDirectPushWithoutHash(true, 42L, true));
        assertFalse(ServerChunkPushManager.shouldDirectPushWithoutHash(false, null, true));
        assertFalse(ServerChunkPushManager.shouldDirectPushWithoutHash(true, null, false));
        assertTrue(ServerChunkPushManager.shouldPairHashWithDirectPush());
    }

    @Test
    void pendingConfirm_hitMissAndTimeoutConverge() {
        assertFalse(ServerChunkPushManager.shouldPushFullOnConfirmResult(
                ChunkDataRequestC2SPacket.RESULT_HIT, true));
        assertFalse(ServerChunkPushManager.shouldPushFullOnConfirmResult(
                ChunkDataRequestC2SPacket.RESULT_MISS, false));
        assertTrue(ServerChunkPushManager.shouldPushFullOnConfirmResult(
                ChunkDataRequestC2SPacket.RESULT_MISS, true));

        long sentAt = 1_000L;
        assertFalse(ServerChunkPushManager.isPendingConfirmExpired(sentAt, sentAt + 5_000L));
        assertTrue(ServerChunkPushManager.isPendingConfirmExpired(sentAt, sentAt + 5_001L));
    }

    @Test
    void emptyHit_clearsOnlyPendingConfirmsInItsDimension() {
        assertTrue(ServerChunkPushManager.shouldClearPendingConfirmOnEmptyHit(
                ChunkDataRequestC2SPacket.RESULT_HIT, "minecraft:overworld", "minecraft:overworld"));
        assertFalse(ServerChunkPushManager.shouldClearPendingConfirmOnEmptyHit(
                ChunkDataRequestC2SPacket.RESULT_HIT, "minecraft:overworld", "minecraft:the_nether"));
        assertFalse(ServerChunkPushManager.shouldClearPendingConfirmOnEmptyHit(
                ChunkDataRequestC2SPacket.RESULT_MISS, "minecraft:overworld", "minecraft:overworld"));
    }

    @Test
    void blockEntityReplies_areNeverBatchTasks() {
        assertFalse(Arrays.stream(ServerChunkPushManager.PushKind.values())
                .anyMatch(kind -> kind.name().contains("BLOCK_ENTITY")),
                "block entity 补发必须保留独立直发路径，不能进入 full chunk 批队列");
    }
}
