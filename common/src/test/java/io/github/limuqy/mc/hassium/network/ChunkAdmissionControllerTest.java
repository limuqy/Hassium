package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkAdmissionControllerTest {

    @Test
    void admission_hardCapLimitsEachTick() {
        ChunkAdmissionController controller = new ChunkAdmissionController();
        for (int x = 0; x < 8; x++) {
            controller.offer(key(x));
        }

        controller.beginTick(3);
        int admitted = 0;
        for (int x = 0; x < 8; x++) {
            if (controller.admit(key(x), 1L) != null) {
                admitted++;
            }
        }

        assertEquals(3, admitted);
        assertEquals(3, controller.inFlightCount());
    }

    @Test
    void admission_firstBatchBlocksUntilKnownAck() {
        ChunkAdmissionController controller = new ChunkAdmissionController();
        controller.offer(key(1));
        controller.beginTick(5);
        ChunkAdmissionController.Reservation first = controller.admit(key(1), 1L);
        assertNotNull(first);

        controller.offer(key(2));
        controller.beginTick(5);
        assertFalse(controller.canAdmit());

        assertTrue(controller.acknowledge(first.deliveryId(), 21_000_000L));
        controller.beginTick(5);
        assertNotNull(controller.admit(key(2), 21_000_001L));
    }

    @Test
    void admission_afterFirstAckAllowsTenUnacknowledgedBatches() {
        ChunkAdmissionController controller = new ChunkAdmissionController();
        controller.offer(key(0));
        controller.beginTick(1);
        ChunkAdmissionController.Reservation first = controller.admit(key(0), 1L);
        assertNotNull(first);
        assertTrue(controller.acknowledge(first.deliveryId(), 2L));

        for (int x = 1; x <= ChunkAdmissionController.POST_ACK_MAX_UNACKNOWLEDGED_BATCHES; x++) {
            controller.offer(key(x));
            controller.beginTick(1);
            assertNotNull(controller.admit(key(x), x + 2L));
        }
        assertEquals(ChunkAdmissionController.POST_ACK_MAX_UNACKNOWLEDGED_BATCHES,
                controller.unacknowledgedBatches());

        controller.offer(key(11));
        controller.beginTick(1);
        assertFalse(controller.canAdmit());
    }

    @Test
    void admission_feedbackDecreasesSlowlyAndIncreasesOneStep() {
        ChunkAdmissionController slow = new ChunkAdmissionController();
        slow.offer(key(1));
        slow.beginTick(9);
        ChunkAdmissionController.Reservation slowReservation = slow.admit(key(1), 1L);
        assertNotNull(slowReservation);
        assertTrue(slow.acknowledge(slowReservation.deliveryId(), 201_000_001L));
        assertEquals(9.0, slow.desiredPerTick(),
                "slow batch must not collapse below PlayerChunkSender-style INITIAL=9");

        ChunkAdmissionController fast = new ChunkAdmissionController();
        fast.offer(key(2));
        fast.beginTick(10);
        ChunkAdmissionController.Reservation fastReservation = fast.admit(key(2), 1L);
        assertNotNull(fastReservation);
        assertTrue(fast.acknowledge(fastReservation.deliveryId(), 2L));
        assertEquals(10.0, fast.desiredPerTick());
    }

    @Test
    void admission_deduplicatesPendingAndInFlightAndIgnoresUnknownAcks() {
        ChunkAdmissionController controller = new ChunkAdmissionController();
        assertTrue(controller.offer(key(1)));
        assertFalse(controller.offer(key(1)));
        assertEquals(1, controller.pendingCount());
        controller.beginTick(2);
        ChunkAdmissionController.Reservation reservation = controller.admit(key(1), 1L);
        assertNotNull(reservation);
        assertFalse(controller.offer(key(1)));
        assertFalse(controller.acknowledge(99L, 2L));
        assertTrue(controller.acknowledge(reservation.deliveryId(), 2L));
        assertFalse(controller.acknowledge(reservation.deliveryId(), 3L));
    }

    @Test
    void admission_resyncSkipsPendingAndInFlightKey() {
        ChunkAdmissionController controller = new ChunkAdmissionController();
        ChunkAdmissionController.ChunkDeliveryKey deliveryKey = key(7);

        controller.offer(deliveryKey);
        assertTrue(controller.contains(deliveryKey), "resync 应跳过已 pending 的 full key");

        controller.beginTick(1);
        ChunkAdmissionController.Reservation reservation = controller.admit(deliveryKey, 1L);
        assertNotNull(reservation);
        assertTrue(controller.contains(deliveryKey), "resync 应跳过等待 ACK 的 full key");

        assertTrue(controller.acknowledge(reservation.deliveryId(), 2L));
        assertFalse(controller.contains(deliveryKey));
    }

    @Test
    void admission_nonWritableTransportDoesNotConsumeFullCredit() {
        ChunkAdmissionController controller = new ChunkAdmissionController();
        ChunkAdmissionController.ChunkDeliveryKey deliveryKey = key(8);
        controller.offer(deliveryKey);

        assertFalse(controller.beginTick(1, false));
        assertEquals(1, controller.pendingCount());
        assertEquals(0, controller.inFlightCount());
        assertNull(controller.admit(deliveryKey, 1L));

        assertTrue(controller.beginTick(1, true));
        ChunkAdmissionController.Reservation reservation = controller.admit(deliveryKey, 2L);
        assertNotNull(reservation);
        assertEquals(1L, reservation.deliveryId(), "暂停期间不得分配 deliveryId");
    }

    @Test
    void admission_timeoutRequeuesAndDisconnectClearsState() {
        ChunkAdmissionController controller = new ChunkAdmissionController();
        controller.offer(key(1));
        controller.beginTick(1);
        ChunkAdmissionController.Reservation reservation = controller.admit(key(1), 1L);
        assertNotNull(reservation);

        assertEquals(1, controller.expire(101L, 100L).size());
        assertEquals(0, controller.pendingCount());
        assertEquals(0, controller.inFlightCount());
        assertTrue(controller.offer(key(1)), "timeout caller re-admits only if tracking still requires the key");
        assertTrue(controller.release(key(1)));

        controller.offer(key(2));
        controller.beginTick(1);
        assertNotNull(controller.admit(key(2), 102L));
        assertTrue(controller.release(key(2)));
        assertEquals(0, controller.inFlightCount());
        controller.clear();
        assertEquals(0, controller.pendingCount());
        assertEquals(0, controller.unacknowledgedBatches());
    }

    @Test
    void admission_reservationStartsAtTransportHandoffAndRollsBackExactly() {
        ChunkAdmissionController controller = new ChunkAdmissionController();
        ChunkAdmissionController.ChunkDeliveryKey deliveryKey = key(12);
        controller.offer(deliveryKey);
        controller.beginTick(1);

        ChunkAdmissionController.Reservation reservation = controller.admit(deliveryKey);
        assertNotNull(reservation);
        assertFalse(controller.acknowledge(reservation.deliveryId(), 10L));
        assertTrue(controller.markSent(reservation.deliveryId(), 5L));
        assertTrue(controller.release(deliveryKey, reservation.deliveryId()));
        assertEquals(0, controller.inFlightCount());
        assertEquals(0, controller.unacknowledgedBatches());
        assertFalse(controller.release(deliveryKey, reservation.deliveryId()));
    }

    @Test
    void admission_pendingBoundRejectsOnlyNewKeys() {
        ChunkAdmissionController controller = new ChunkAdmissionController();
        for (int x = 0; x < ChunkAdmissionController.MAX_PENDING_PER_PLAYER; x++) {
            assertTrue(controller.offer(key(x)));
        }
        assertFalse(controller.offer(key(ChunkAdmissionController.MAX_PENDING_PER_PLAYER)));
        assertEquals(ChunkAdmissionController.MAX_PENDING_PER_PLAYER, controller.pendingCount());
    }

    @Test
    void admission_quotaExhaustedLeavesPendingAndBlocksReoffer() {
        ChunkAdmissionController controller = new ChunkAdmissionController();
        controller.offer(key(1));
        controller.offer(key(2));
        controller.beginTick(1);

        assertNotNull(controller.admit(key(1), 1L));
        assertFalse(controller.canAdmit());
        assertNull(controller.admit(key(2), 2L), "quota 用尽时 admit 必须失败");
        assertTrue(controller.isPending(key(2)), "失败不得摘掉 pending，否则队列与 occupancy 分裂");
        assertFalse(controller.offer(key(2)), "pending 仍占位时 contains/offer 会拒绝同坐标重入队");
        assertEquals(1, controller.pendingCount());
        assertEquals(1, controller.inFlightCount());
    }

    @Test
    void admission_admitMissingPendingDoesNotBlockLaterOffer() {
        ChunkAdmissionController controller = new ChunkAdmissionController();
        controller.beginTick(1);
        assertNull(controller.admit(key(3), 1L));
        assertFalse(controller.isPending(key(3)));
        assertTrue(controller.offer(key(3)), "从未 pending 的 admit 失败不得污染 occupancy");
    }

    @Test
    void drainProtocol_admitFailureMustRequeueWhilePending() {
        ChunkAdmissionController controller = new ChunkAdmissionController();
        KeyedPriorityQueue<String> queue = new KeyedPriorityQueue<>();
        KeyedPriorityQueue.Key queueKey = new KeyedPriorityQueue.Key(1L, 0, "minecraft:overworld");

        controller.offer(key(1));
        controller.offer(key(2));
        queue.offer("chunk-2", queueKey, 1.0, KeyedPriorityQueue.OfferPolicy.REPLACE);

        controller.beginTick(1);
        assertNotNull(controller.admit(key(1), 1L));

        KeyedPriorityQueue.Entry<String> entry = queue.poll();
        assertNotNull(entry);
        queue.release(entry);
        assertNull(controller.admit(key(2)));
        assertTrue(controller.isPending(key(2)));

        queue.offer(entry.item(), queueKey, entry.priority(), KeyedPriorityQueue.OfferPolicy.REPLACE);
        assertEquals(1, queue.size());
        assertEquals("chunk-2", queue.poll().item());
        assertFalse(controller.offer(key(2)));
    }

    private static ChunkAdmissionController.ChunkDeliveryKey key(int x) {
        return new ChunkAdmissionController.ChunkDeliveryKey("minecraft:overworld", x, 0);
    }
}
