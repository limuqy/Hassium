package io.github.limuqy.mc.hassium.network;

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
    void admission_slowAckReturnPathOpensWindowByProbe() {
        ChunkAdmissionController controller = new ChunkAdmissionController();
        controller.offer(key(1));
        controller.beginTick(4);
        ChunkAdmissionController.Reservation first = controller.admit(key(1), 1L);
        assertNotNull(first);

        // 首个 ACK 未到：探测间隔内窗口保持冻结（原语义）
        controller.offer(key(2));
        controller.beginTick(4);
        assertFalse(controller.canAdmit());

        // 慢回程（fabric 网关）：最老批在途超过探测间隔后放行新批，不等 8s 超时
        long probeAt = 1L + ChunkAdmissionController.FIRST_ACK_PROBE_INTERVAL_NANOS + 1L;
        // hasBatchCredit 用 System.nanoTime() 计时；批的 firstSentAtNanos=1L 是测试锚点，
        // 与真实时钟差值巨大 → elapsed 必然远超间隔 → 本 tick 放行一批。
        controller.beginTick(4);
        assertTrue(controller.canAdmit(),
                "slow ACK return path must open one batch per probe interval instead of freezing");
        assertNotNull(controller.admit(key(2), probeAt));
        assertFalse(controller.canAdmit(), "one probe opens exactly one batch");
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
    void admission_partialBatchAcksDoNotFreezeWindow() {
        ChunkAdmissionController controller = new ChunkAdmissionController();
        controller.offer(key(0));
        controller.beginTick(9);
        ChunkAdmissionController.Reservation first = controller.admit(key(0), 1L);
        assertNotNull(first);
        assertTrue(controller.acknowledge(first.deliveryId(), 2L));

        // 10 ticks × 2 chunks：每批只 ACK 1/2，旧逻辑 unacknowledgedBatches=10 冻死窗口。
        long sentAt = 3L;
        java.util.List<ChunkAdmissionController.Reservation> stuck = new java.util.ArrayList<>();
        for (int tick = 0; tick < ChunkAdmissionController.POST_ACK_MAX_UNACKNOWLEDGED_BATCHES; tick++) {
            int a = 100 + tick * 2;
            int b = a + 1;
            controller.offer(key(a));
            controller.offer(key(b));
            controller.beginTick(9);
            ChunkAdmissionController.Reservation ra = controller.admit(key(a), sentAt++);
            ChunkAdmissionController.Reservation rb = controller.admit(key(b), sentAt++);
            assertNotNull(ra);
            assertNotNull(rb);
            assertTrue(controller.acknowledge(ra.deliveryId(), sentAt++));
            stuck.add(rb);
        }
        assertEquals(ChunkAdmissionController.POST_ACK_MAX_UNACKNOWLEDGED_BATCHES,
                stuck.size());
        assertEquals(ChunkAdmissionController.POST_ACK_MAX_UNACKNOWLEDGED_BATCHES,
                controller.inFlightCount());

        controller.offer(key(999));
        controller.beginTick(9);
        assertNotNull(controller.admit(key(999), sentAt),
                "partial ACKs must free in-flight credit even if tick-batches remain incomplete");
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
        assertTrue(controller.markSent(reservation.deliveryId(), 5L));
        assertTrue(controller.release(deliveryKey, reservation.deliveryId()));
        assertEquals(0, controller.inFlightCount());
        assertEquals(0, controller.unacknowledgedBatches());
        assertFalse(controller.release(deliveryKey, reservation.deliveryId()));
    }

    @Test
    void admission_ackBeforeMarkSentReleasesBatchCredit() {
        ChunkAdmissionController controller = new ChunkAdmissionController();
        controller.offer(key(1));
        controller.beginTick(5);
        ChunkAdmissionController.Reservation first = controller.admit(key(1));
        assertNotNull(first);
        assertEquals(1, controller.unacknowledgedBatches());

        assertTrue(controller.acknowledge(first.deliveryId(), 10L),
                "client apply/ACK can beat pushPool markSent");
        assertEquals(0, controller.inFlightCount());
        assertEquals(0, controller.unacknowledgedBatches());
        assertFalse(controller.markSent(first.deliveryId(), 11L),
                "already-ACKed delivery must not be marked sent");

        controller.offer(key(2));
        controller.beginTick(5);
        assertNotNull(controller.admit(key(2), 12L),
                "early ACK must free the first-batch gate");
    }

    @Test
    void admission_unsentReservationExpiresFromAdmitTime() {
        ChunkAdmissionController controller = new ChunkAdmissionController();
        controller.offer(key(1));
        controller.beginTick(1);
        ChunkAdmissionController.Reservation reservation = controller.admit(key(1), 0L, 1L);
        assertNotNull(reservation);
        assertEquals(1, controller.inFlightCount());

        assertEquals(0, controller.expire(100L, 100L).size(), "unsent must not expire before admit+timeout");
        assertEquals(1, controller.expire(101L, 100L).size());
        assertEquals(0, controller.inFlightCount());
        assertEquals(0, controller.unacknowledgedBatches());
        assertTrue(controller.offer(key(1)), "timeout caller re-admits only if tracking still requires the key");
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
    void drainProtocol_admitFailureKeepsPendingHead() {
        ChunkAdmissionController controller = new ChunkAdmissionController();
        controller.offer(key(1));
        controller.offer(key(2));
        controller.beginTick(1);
        assertNotNull(controller.admit(key(1), 1L));
        assertNull(controller.admit(key(2)), "quota 用尽不得摘掉队头 pending");
        assertTrue(controller.isPending(key(2)));
        assertFalse(controller.offer(key(2)));
        assertEquals(1, controller.pendingCount());
        assertEquals(1, controller.inFlightCount());
    }

    private static ChunkAdmissionController.ChunkDeliveryKey key(int x) {
        return new ChunkAdmissionController.ChunkDeliveryKey("minecraft:overworld", x, 0);
    }

    @Test
    void admission_windowWidensToBandwidthDelayProductAfterMeasuredRtt() {
        ChunkAdmissionController controller = new ChunkAdmissionController();
        // 首批：maxChunksPerTick=5、完整批 1s RTT → desiredPerTick 钉在 floor=5，
        // ewmaRtt=1s → rttTicks=20 → BDP=desired×rttTicks=100 > 基线 10×5=50。
        controller.offer(key(0));
        controller.beginTick(5);
        assertNotNull(controller.admit(key(0), 1L));
        assertTrue(controller.acknowledge(1L, 1_000_000_000L));

        int admitted = fillToWindowCap(controller, 5, 400, 2_000_000_000L);
        assertEquals(100, admitted, "慢回程实测后窗口必须按 BDP 放宽到 100 个在途 chunk");
        assertFalse(controller.canAdmit(), "达到自适应窗口后必须停止放行");
    }

    @Test
    void admission_adaptiveWindowHardCapsAtPendingBound() {
        ChunkAdmissionController controller = new ChunkAdmissionController();
        controller.offer(key(0));
        controller.beginTick(5);
        assertNotNull(controller.admit(key(0), 1L));
        assertTrue(controller.acknowledge(1L, 1L + 60_000_000_000L)); // 60s 极端 RTT

        int admitted = fillToWindowCap(controller, 5, 500, 61_000_000_000L);
        assertEquals(ChunkAdmissionController.MAX_PENDING_PER_PLAYER, admitted,
                "极端 RTT 下窗口必须被 MAX_PENDING_PER_PLAYER 硬顶防雪崩");
    }

    @Test
    void tieredTimeout_waterLevelBoundaries() {
        long base = java.util.concurrent.TimeUnit.SECONDS.toNanos(8L);
        assertEquals(base, ChunkAdmissionController.tieredDeliveryTimeoutNanos(0, 100, base));
        assertEquals(base, ChunkAdmissionController.tieredDeliveryTimeoutNanos(74, 100, base),
                "水位低于窗口 3/4 保持基线档");
        assertEquals(base / 2, ChunkAdmissionController.tieredDeliveryTimeoutNanos(75, 100, base),
                "水位达窗口 3/4 用半档");
        assertEquals(base / 4, ChunkAdmissionController.tieredDeliveryTimeoutNanos(100, 100, base),
                "窗口打满用短档");
        assertEquals(base / 4, ChunkAdmissionController.tieredDeliveryTimeoutNanos(150, 100, base));
    }

    @Test
    void admission_saturatedWindowRecyclesFasterThanBaseline() {
        ChunkAdmissionController controller = new ChunkAdmissionController();
        // 无 RTT 样本 → postAckInFlightChunkCap 走基线 10×10=100；灌满到饱和水位。
        controller.offer(key(0));
        controller.beginTick(10);
        assertNotNull(controller.admit(key(0), 1L));
        assertTrue(controller.acknowledge(1L, 2L));
        fillToWindowCap(controller, 10, 200, 3L);
        assertEquals(100, controller.inFlightCount());

        long base = java.util.concurrent.TimeUnit.SECONDS.toNanos(8L);
        long nowNanos = 4_000_000_000L; // 在途龄 4s：基线档不回收，短档（2s）全回收
        long tiered = ChunkAdmissionController.tieredDeliveryTimeoutNanos(
                controller.inFlightCount(), controller.postAckInFlightChunkCap(), base);
        assertEquals(base / 4, tiered);
        assertTrue(controller.expire(nowNanos, base).isEmpty(),
                "基线 8s 档下 4s 龄投递不得回收");
        assertFalse(controller.expire(nowNanos, tiered).isEmpty(),
                "饱和水位的冻结窗口必须按短档快速回收解冻 admission");
    }

    /** 逐 key offer→beginTick→admit，返回成功入窗的在途数（不 ACK）。 */
    private static int fillToWindowCap(ChunkAdmissionController controller, int maxChunksPerTick,
                                       int maxKeys, long sentBaseNanos) {
        int admitted = 0;
        for (int x = 1; x <= maxKeys; x++) {
            if (!controller.offer(key(x))) {
                continue;
            }
            controller.beginTick(maxChunksPerTick);
            if (controller.admit(key(x), sentBaseNanos + x) != null) {
                admitted++;
            }
        }
        return admitted;
    }
}
