package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.server.RuntimeServerContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.20.1 paced pending：admission 拒绝时不得从 pendingSends 丢 key；入队预算与
 * drain 定额相同（源头限速，不再领先入队）。
 */
class ServerChunkPushManagerPacedPendingTest {

    @Test
    void shouldPaceChunkSends_dependsOnDedicatedNotCompression() {
        boolean prev = RuntimeServerContext.isDedicatedServerContext();
        try {
            RuntimeServerContext.setDedicatedServer(false);
            assertFalse(ServerChunkPushManager.shouldPaceChunkSends());
            RuntimeServerContext.setDedicatedServer(true);
            assertTrue(ServerChunkPushManager.shouldPaceChunkSends(),
                    "source pacing must not wait on compression/handshake");
        } finally {
            RuntimeServerContext.setDedicatedServer(prev);
        }
    }

    @Test
    void commitPacedPendingKey_offerFailureKeepsKey() {
        Set<Long> pending = ConcurrentHashMap.newKeySet();
        long key = ChunkPos.asLong(3, 7);
        pending.add(key);

        assertFalse(ServerChunkPushManager.commitPacedPendingKey(pending, key, false));
        assertTrue(pending.contains(key), "failed offer must not drop paced pending key");
    }

    @Test
    void commitPacedPendingKey_offerSuccessRemovesKey() {
        Set<Long> pending = ConcurrentHashMap.newKeySet();
        long key = ChunkPos.asLong(1, 2);
        pending.add(key);

        assertTrue(ServerChunkPushManager.commitPacedPendingKey(pending, key, true));
        assertFalse(pending.contains(key));
    }

    @Test
    void pacedSendBudget_zeroWhenAdmissionFull() {
        assertEquals(0, ServerChunkPushManager.pacedSendBudget(
                ChunkAdmissionController.MAX_PENDING_PER_PLAYER, 0, 8));
        assertEquals(0, ServerChunkPushManager.pacedSendBudget(
                200, ChunkAdmissionController.MAX_PENDING_PER_PLAYER - 200, 8));
    }

    @Test
    void pacedSendBudget_capsByTickAndAdmissionRoom() {
        assertEquals(4, ServerChunkPushManager.pacedSendBudget(380, 0, 8));
        assertEquals(8, ServerChunkPushManager.pacedSendBudget(0, 0, 8));
        assertEquals(0, ServerChunkPushManager.pacedSendBudget(0, 0, 0));
    }

    @Test
    void pacedEnqueueBudget_matchesSendQuota() {
        int send = ServerChunkPushManager.pacedSendBudget(0, 0, 4);
        int enqueue = ServerChunkPushManager.pacedEnqueueBudget(0, 0, 4);
        assertEquals(send, enqueue, "source-paced enqueue must not lead drain");
        assertEquals(4, enqueue);
        assertEquals(8, ServerChunkPushManager.pacedEnqueueBudget(0, 0, 8));
    }

    @Test
    void pacedEnqueueBudget_zeroWhenAdmissionFull() {
        assertEquals(0, ServerChunkPushManager.pacedEnqueueBudget(
                ChunkAdmissionController.MAX_PENDING_PER_PLAYER, 0, 8));
        assertEquals(0, ServerChunkPushManager.pacedEnqueueBudget(
                200, ChunkAdmissionController.MAX_PENDING_PER_PLAYER - 200, 8));
    }

    @Test
    void pacedEnqueueBudget_cappedByAdmissionRoom() {
        assertEquals(4, ServerChunkPushManager.pacedEnqueueBudget(380, 0, 8));
        assertEquals(4, ServerChunkPushManager.pacedEnqueueBudget(
                ChunkAdmissionController.MAX_PENDING_PER_PLAYER - 10, 0, 4),
                "source-paced budget is min(maxChunksPerTick, admission room)");
    }

    @Test
    void hashSendsPerTick_independentOfAdmissionFull() {
        assertEquals(0, ServerChunkPushManager.pacedEnqueueBudget(
                ChunkAdmissionController.MAX_PENDING_PER_PLAYER, 0, 8),
                "full push must wait on admission");
        assertTrue(ServerChunkPushManager.HASH_SENDS_PER_TICK > 8,
                "R2 hash path must not drip at maxChunksPerTick");
        assertTrue(ServerChunkPushManager.shouldProbeWorldForPacedPending(
                false, 0, ServerChunkPushManager.HASH_SENDS_PER_TICK),
                "hash path may still probe when enqueue budget is 0");
        assertFalse(ServerChunkPushManager.shouldProbeWorldForPacedPending(true, 0, 32),
                "ROUND1 must not getChunkNow after enqueue budget is filled");
    }

    @Test
    void round1_directPushSkipsMainThreadHash() {
        assertTrue(ServerChunkPushManager.shouldDirectPushWithoutHash(true, null));
        assertTrue(ServerChunkPushManager.shouldDirectPushWithoutHash(true, null, true));
        assertFalse(ServerChunkPushManager.shouldDirectPushWithoutHash(true, 42L),
                "session reuse comparison goes through submitMetadataTaskFromChunk / pushPool");
        assertFalse(ServerChunkPushManager.shouldDirectPushWithoutHash(false, null),
                "bloom hit stays on the hash / pushPool path");
        assertFalse(ServerChunkPushManager.shouldDirectPushWithoutHash(true, null, false),
                "bloom unready (R2 before client report) must stay on the hash path");
    }

    @Test
    void sortPackedKeysByDistance_doesNotNeedWorld_nearFirst() {
        List<Long> keys = new ArrayList<>();
        keys.add(ChunkPos.asLong(10, 0));
        keys.add(ChunkPos.asLong(0, 0));
        keys.add(ChunkPos.asLong(0, 8));
        ServerChunkPushManager.sortPackedKeysByDistance(keys, 0, 0);
        assertEquals(ChunkPos.asLong(0, 0), keys.get(0));
        assertEquals(ChunkPos.asLong(0, 8), keys.get(1));
        assertEquals(ChunkPos.asLong(10, 0), keys.get(2));
    }

    @Test
    void admissionOffer_rejectsWhenPendingFull_withoutWorld() {
        ChunkAdmissionController controller = new ChunkAdmissionController();
        for (int i = 0; i < ChunkAdmissionController.MAX_PENDING_PER_PLAYER; i++) {
            assertTrue(controller.offer(new ChunkAdmissionController.ChunkDeliveryKey("dim", i, 0)));
        }
        assertFalse(controller.offer(new ChunkAdmissionController.ChunkDeliveryKey("dim", 999, 0)));
        assertEquals(ChunkAdmissionController.MAX_PENDING_PER_PLAYER, controller.pendingCount());
    }

    @Test
    void unacceptedFullRequest_mustBacklogUnlessAlreadyAdmitted() {
        assertTrue(ServerChunkPushManager.shouldBacklogUnacceptedFullRequest(false, false),
                "offer failed and not in admission → keep on server until later tick");
        assertFalse(ServerChunkPushManager.shouldBacklogUnacceptedFullRequest(true, false));
        assertFalse(ServerChunkPushManager.shouldBacklogUnacceptedFullRequest(false, true),
                "already pending/in-flight is not a drop");
    }
}
