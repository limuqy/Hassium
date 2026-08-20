package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.network.core.outbound.ChunkApplyAck;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientChunkApplyAckAggregatorTest {

    @Test
    void batchOf64_flushesImmediatelyInFifoOrder() {
        List<long[]> sent = new ArrayList<>();
        ClientChunkApplyAckAggregator aggregator = new ClientChunkApplyAckAggregator(ack -> {
            sent.add(ack.deliveryIds());
            return true;
        });

        for (long deliveryId = 1L; deliveryId <= ChunkApplyAck.MAX_DELIVERY_IDS; deliveryId++) {
            aggregator.recordApplied(deliveryId);
        }

        assertEquals(1, sent.size());
        long[] expected = new long[ChunkApplyAck.MAX_DELIVERY_IDS];
        for (int index = 0; index < expected.length; index++) {
            expected[index] = index + 1L;
        }
        assertArrayEquals(expected, sent.get(0));
        assertEquals(0, aggregator.size());
    }

    @Test
    void tickTailFlushesPartialBatch() {
        List<long[]> sent = new ArrayList<>();
        ClientChunkApplyAckAggregator aggregator = new ClientChunkApplyAckAggregator(ack -> {
            sent.add(ack.deliveryIds());
            return true;
        });
        aggregator.recordApplied(7L);
        aggregator.recordApplied(8L);

        aggregator.flush();

        assertEquals(1, sent.size());
        assertArrayEquals(new long[]{7L, 8L}, sent.get(0));
        assertEquals(0, aggregator.size());
    }

    @Test
    void failedSendRetainsBatchForNextTick() {
        List<long[]> attempted = new ArrayList<>();
        AtomicBoolean canSend = new AtomicBoolean(false);
        ClientChunkApplyAckAggregator aggregator = new ClientChunkApplyAckAggregator(ack -> {
            attempted.add(ack.deliveryIds());
            return canSend.get();
        });
        aggregator.recordApplied(21L);

        aggregator.flush();
        assertEquals(1, aggregator.size());
        canSend.set(true);
        aggregator.flush();

        assertEquals(2, attempted.size());
        assertArrayEquals(new long[]{21L}, attempted.get(0));
        assertArrayEquals(new long[]{21L}, attempted.get(1));
        assertEquals(0, aggregator.size());
    }

    @Test
    void failedFullBatchRetainsLaterDeliveriesForOrderedRetry() {
        List<long[]> attempted = new ArrayList<>();
        AtomicBoolean canSend = new AtomicBoolean(false);
        ClientChunkApplyAckAggregator aggregator = new ClientChunkApplyAckAggregator(ack -> {
            attempted.add(ack.deliveryIds());
            return canSend.get();
        });

        for (long deliveryId = 1L; deliveryId <= ChunkApplyAck.MAX_DELIVERY_IDS + 2L; deliveryId++) {
            aggregator.recordApplied(deliveryId);
        }
        aggregator.recordApplied(ChunkApplyAck.MAX_DELIVERY_IDS + 1L);
        assertEquals(ChunkApplyAck.MAX_DELIVERY_IDS + 2, aggregator.size());

        long[] firstBatch = new long[ChunkApplyAck.MAX_DELIVERY_IDS];
        for (int index = 0; index < firstBatch.length; index++) {
            firstBatch[index] = index + 1L;
        }
        assertArrayEquals(firstBatch, attempted.get(0));

        canSend.set(true);
        aggregator.flush();
        aggregator.flush();

        assertArrayEquals(firstBatch, attempted.get(attempted.size() - 2));
        assertArrayEquals(new long[]{65L, 66L}, attempted.get(attempted.size() - 1));
        assertEquals(0, aggregator.size());
    }

    @Test
    void duplicateAndZeroDeliveryIdsAreNotQueued() {
        List<long[]> sent = new ArrayList<>();
        ClientChunkApplyAckAggregator aggregator = new ClientChunkApplyAckAggregator(ack -> {
            sent.add(ack.deliveryIds());
            return true;
        });
        aggregator.recordApplied(0L);
        aggregator.recordApplied(55L);
        aggregator.recordApplied(55L);

        aggregator.flush();

        assertEquals(1, sent.size());
        assertArrayEquals(new long[]{55L}, sent.get(0));
    }

    @Test
    void disconnectDropsUnsentAcks() {
        List<long[]> sent = new ArrayList<>();
        ClientChunkApplyAckAggregator aggregator = new ClientChunkApplyAckAggregator(ack -> {
            sent.add(ack.deliveryIds());
            return true;
        });
        aggregator.recordApplied(34L);

        aggregator.clear();
        aggregator.flush();

        assertEquals(0, aggregator.size());
        assertTrue(sent.isEmpty());
    }

    @Test
    void finalApplyCallbackRejectsZeroAndFailedButAcksRenderOnlySuccess() {
        assertFalse(ClientChunkHandler.shouldRecordAuthoritativeApply(0L, false, true));
        assertFalse(ClientChunkHandler.shouldRecordAuthoritativeApply(9L, false, false));
        assertFalse(ClientChunkHandler.shouldRecordAuthoritativeApply(9L, true, false));
        assertTrue(ClientChunkHandler.shouldRecordAuthoritativeApply(9L, true, true));
        assertTrue(ClientChunkHandler.shouldRecordAuthoritativeApply(9L, false, true));
    }
}
