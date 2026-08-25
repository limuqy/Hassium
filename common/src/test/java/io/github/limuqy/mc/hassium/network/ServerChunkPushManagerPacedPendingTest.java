package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.server.RuntimeServerContext;
import java.util.Arrays;
import java.util.List;
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
        assertFalse(ServerChunkPushManager.shouldPairHashWithDirectPush(),
                "直推已有剥光全量，不得再发一份 ChunkHashS2C");
    }

    @Test
    void pendingConfirm_hitMissAndTimeoutConverge() {
        assertFalse(ServerChunkPushManager.shouldPushFullOnConfirmResult(
                ChunkDataRequestC2SPacket.RESULT_HIT, true));
        assertFalse(ServerChunkPushManager.shouldPushFullOnConfirmResult(
                ChunkDataRequestC2SPacket.RESULT_MISS, false));
        assertTrue(ServerChunkPushManager.shouldPushFullOnConfirmResult(
                ChunkDataRequestC2SPacket.RESULT_MISS, true));

        assertTrue(ServerChunkPushManager.shouldBypassTickCapOnConfirmMiss(true, true),
                "已封批且有快照的 miss 必须绕开 4/t");
        assertFalse(ServerChunkPushManager.shouldBypassTickCapOnConfirmMiss(true, false),
                "尚无快照 = 还没付过封批成本");
        assertFalse(ServerChunkPushManager.shouldBypassTickCapOnConfirmMiss(false, true),
                "非 pending 的 miss 不是二次出口");

        long sentAt = 1_000L;
        long timeout = ServerChunkPushManager.PENDING_CONFIRM_TIMEOUT_MS;
        assertEquals(10_000L, timeout);
        assertFalse(ServerChunkPushManager.isPendingConfirmExpired(sentAt, sentAt + timeout));
        assertTrue(ServerChunkPushManager.isPendingConfirmExpired(sentAt, sentAt + timeout + 1L));
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
    void sameHash_inFlightOrConfirmed_isNotResent() {
        assertTrue(ServerChunkPushManager.shouldSkipRedundantHash(42L, true, null, 42L));
        assertTrue(ServerChunkPushManager.shouldSkipRedundantHash(null, false, 42L, 42L));
        assertFalse(ServerChunkPushManager.shouldSkipRedundantHash(42L, true, null, 99L),
                "内容变了必须重发");
        assertFalse(ServerChunkPushManager.shouldSkipRedundantHash(42L, false, null, 42L),
                "pending 已超时不得再当在途跳过");
        assertFalse(ServerChunkPushManager.shouldSkipRedundantHash(null, false, null, 42L));
        assertFalse(ServerChunkPushManager.shouldSkipRedundantHash(42L, true, 42L, 0L));
    }

    @Test
    void forceFull_neverFoldsBackToHash() {
        assertFalse(ServerChunkPushManager.shouldSendHashInsteadOfFull(
                ServerChunkPushManager.PushKind.FORCE_FULL, false));
        assertFalse(ServerChunkPushManager.shouldSendHashInsteadOfFull(
                ServerChunkPushManager.PushKind.FULL_HALO, false));
        assertTrue(ServerChunkPushManager.shouldSendHashInsteadOfFull(
                ServerChunkPushManager.PushKind.FULL_VISIBLE, false),
                "bloom hit 的 FULL_VISIBLE 仍可只发 hash");
        assertFalse(ServerChunkPushManager.shouldSendHashInsteadOfFull(
                ServerChunkPushManager.PushKind.FULL_VISIBLE, true));
    }

    @Test
    void forceFull_upgradesQueuedVisiblePush() {
        assertTrue(ServerChunkPushManager.shouldReplaceQueuedPush(
                ServerChunkPushManager.PushKind.FULL_VISIBLE,
                ServerChunkPushManager.PushKind.FORCE_FULL));
        assertFalse(ServerChunkPushManager.shouldReplaceQueuedPush(
                ServerChunkPushManager.PushKind.FORCE_FULL,
                ServerChunkPushManager.PushKind.FULL_VISIBLE));
        assertFalse(ServerChunkPushManager.shouldReplaceQueuedPush(
                ServerChunkPushManager.PushKind.FULL_VISIBLE,
                ServerChunkPushManager.PushKind.FULL_VISIBLE));
    }

    @Test
    void hitDoesNotRequestFullChunks_mustStillReachConfirmHandler() {
        ChunkDataRequestC2SPacket hit = new ChunkDataRequestC2SPacket(
                "minecraft:overworld", List.of(), ChunkDataRequestC2SPacket.RESULT_HIT);
        assertFalse(hit.requestsFullChunks(), "HIT 空柱回执不得当成拉取全量");
        ChunkDataRequestC2SPacket miss = new ChunkDataRequestC2SPacket(
                "minecraft:overworld", List.of(new net.minecraft.world.level.ChunkPos(0, 0)),
                ChunkDataRequestC2SPacket.RESULT_MISS);
        assertTrue(miss.requestsFullChunks());
        assertFalse(ServerChunkPushManager.shouldPushFullOnConfirmResult(
                hit.result(), true), "服务端收到 HIT 即使曾 pending 也不得 FORCE_FULL");
    }

    @Test
    void blockEntityReplies_areNeverBatchTasks() {
        assertFalse(Arrays.stream(ServerChunkPushManager.PushKind.values())
                .anyMatch(kind -> kind.name().contains("BLOCK_ENTITY")),
                "block entity 补发必须保留独立直发路径，不能进入 full chunk 批队列");
    }
}
