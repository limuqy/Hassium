package io.github.limuqy.mc.hassium.network;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.20.1 paced pending：admission 拒绝时不得从 pendingSends 丢 key；预算受
 * pending+inFlight 与 maxChunksPerTick 约束。
 */
class ServerChunkPushManagerPacedPendingTest {

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
    void hashSendsPerTick_independentOfAdmissionFull() {
        assertEquals(0, ServerChunkPushManager.pacedSendBudget(
                ChunkAdmissionController.MAX_PENDING_PER_PLAYER, 0, 8),
                "full push must wait on admission");
        assertTrue(ServerChunkPushManager.HASH_SENDS_PER_TICK > 8,
                "R2 hash path must not drip at maxChunksPerTick");
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
}
