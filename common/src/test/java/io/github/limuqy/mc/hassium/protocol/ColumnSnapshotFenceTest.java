package io.github.limuqy.mc.hassium.protocol;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ColumnSnapshotFenceTest {
    private static ColumnSnapshotFence<Runnable> fence() {
        return new ColumnSnapshotFence<>(8, 2, 2 * ColumnSnapshotFence.RESERVATION_BYTES);
    }
    @Test void fullAndDeltaSnapshotsCannotOverwriteNewBlockOrBeStateAndAckFollowsOwnerTail() {
        for (String kind : List.of("FULL", "DELTA")) {
            var fence = fence();
            var events = new ArrayList<String>();
            String[] visible = {"old-block", "old-nbt"}, owner = visible.clone();
            // Real pre-fix order: live updates arrive, then a slow old snapshot overwrites them.
            visible[0] = "new-block"; visible[1] = "new-nbt";
            visible[0] = "old-block"; visible[1] = "old-nbt";
            assertEquals("old-nbt", visible[1], kind + " negative control reproduces the lost update");
            assertTrue(fence.begin(1, 10));
            long token = fence.incoming(1); Object chunk = new Object();
            fence.injected(1, token, chunk);
            assertTrue(fence.append(1, () -> {
                visible[0] = "new-block"; owner[0] = "new-block"; events.add("block");
            }, 16));
            assertTrue(fence.append(1, () -> {
                visible[1] = "new-nbt"; owner[1] = "new-nbt"; events.add("nbt-before-response");
            }, 16));
            assertTrue(fence.acceptsReady(1, fence.ready(1, chunk)));
            events.add("snapshot-visible");
            long drain = fence.startDrain(1);
            fence.takeTail(1, drain).forEach(Runnable::run);
            // A later native update arrives while the queued owner boundary is still pending.
            fence.append(1, () -> {
                visible[1] = "latest-nbt"; owner[1] = "latest-nbt"; events.add("nbt-after-response");
            }, 16);
            assertFalse(fence.finishDrain(1, drain), "ACK cannot jump a tail arriving during owner commit");
            assertFalse(fence.acceptsReady(1, token));
            fence.takeTail(1, drain).forEach(Runnable::run);
            assertTrue(fence.finishDrain(1, drain)); events.add("ack");
            assertArrayEquals(new String[]{"new-block", "latest-nbt"}, visible);
            assertArrayEquals(visible, owner);
            assertEquals(List.of("snapshot-visible", "block", "nbt-before-response", "nbt-after-response", "ack"), events);
            assertEquals(0, fence.activeCount()); assertEquals(0, fence.reservedBytes());
            assertFalse(fence.acceptsReady(1, token), "Completed snapshots cannot republish before their newer tail");
        }
    }

    @Test void newerRequestUnloadDimensionAndReconnectRejectOldPendingAndReady() {
        var fence = fence(); Object old = new Object(), current = new Object();
        long preResponse = fence.incoming(1);
        assertTrue(fence.begin(1, 10)); long first = fence.incoming(1);
        fence.injected(1, first, old);
        assertFalse(fence.pending(1, preResponse));
        assertTrue(fence.begin(1, 11)); long newer = fence.incoming(1);
        assertFalse(fence.pending(1, first));
        fence.injected(1, first, old);
        assertEquals(-1, fence.ready(1, old));
        fence.injected(1, newer, current);
        assertTrue(fence.acceptsReady(1, fence.ready(1, current)));
        fence.cancel(1);
        assertFalse(fence.acceptsReady(1, newer)); assertEquals(0, fence.activeCount());
        assertTrue(fence.begin(2, 1)); long otherDimension = fence.incoming(2);
        fence.clear();
        assertFalse(fence.pending(2, otherDimension));
        assertTrue(fence.begin(1, 1));
        assertFalse(fence.pending(1, newer), "Reused request ids do not reuse internal versions after reconnect");
    }

    @Test void saturationOverflowFailureAndCancellationReleaseEveryReservation() {
        var fence = fence();
        assertTrue(fence.begin(1, 1)); assertTrue(fence.begin(2, 1));
        assertFalse(fence.begin(3, 1));
        long token = fence.incoming(1); fence.injected(1, token, new Object());
        fence.append(1, () -> fail("A truncated tail must never replay"), ColumnSnapshotFence.MAX_BYTES);
        fence.append(1, () -> {}, 1);
        assertTrue(fence.overflowed(1));
        long drain = fence.startDrain(1);
        assertNull(fence.takeTail(1, drain)); assertFalse(fence.finishDrain(1, drain));
        fence.cancel(1); fence.cancel(1);
        assertTrue(fence.begin(3, 2));
        fence.clear(); assertEquals(0, fence.activeCount()); assertEquals(0, fence.reservedBytes());
    }

    @Test void capacityEvictionRejectsPreviouslyPackedIdleWork() {
        var fence = new ColumnSnapshotFence<Runnable>(2, 1, ColumnSnapshotFence.RESERVATION_BYTES);
        long old = fence.incoming(1);
        fence.incoming(2); fence.incoming(3);
        assertFalse(fence.pending(1, old)); assertFalse(fence.acceptsReady(1, old));
        assertTrue(fence.begin(3, 1));
        fence.incoming(4); fence.incoming(5);
        assertTrue(fence.request(3, 1), "Idle LRU entries cannot evict a live snapshot");
        fence.clear();
    }

    @Test void completedSleepRevisionCannotReviveAcrossOrdinaryFullAndDeltaWithTheSameWireId() {
        long sleepPending = SnapshotTokens.next(), sleepPublished = SnapshotTokens.next();
        var fence = fence();
        for (long request : new long[]{10, 20}) {
            assertTrue(fence.begin(1, request));
            long ordinary = fence.incoming(1); Object chunk = new Object();
            assertNotEquals(sleepPending, ordinary); assertNotEquals(sleepPublished, ordinary);
            assertFalse(fence.pending(1, sleepPublished));
            assertFalse(fence.acceptsReady(1, sleepPublished));
            assertTrue(fence.requiresFullReplacement(1), "Same-hash FULL must still bind the authoritative object");
            assertEquals(-1, fence.ready(1, null), "An unbound active snapshot cannot publish");
            fence.injectedRequest(1, request, chunk);
            fence.append(1, () -> {}, 1);
            long drain = fence.startDrain(1);
            assertEquals(1, fence.takeTail(1, drain).size());
            assertTrue(fence.finishDrain(1, drain));
            assertFalse(fence.requiresFullReplacement(1));
        }
        assertEquals(0, fence.reservedBytes());
    }

    @Test void engineOffCanDrainHeldNativeUpdatesWithoutShadowOwnerAndInvalidatesOldReady() {
        var fence = fence(); var replayed = new ArrayList<String>();
        assertTrue(fence.begin(1, 1)); long stale = fence.incoming(1);
        fence.append(1, () -> replayed.add("block"), 1);
        fence.append(1, () -> replayed.add("BE"), 1);
        // Direct vanilla fallback binds the current native level, never the old shadow chunk.
        fence.injected(1, stale, new Object());
        long drain = fence.startDrain(1);
        fence.takeTail(1, drain).forEach(Runnable::run);
        assertTrue(fence.finishDrain(1, drain));
        assertEquals(List.of("block", "BE"), replayed);
        assertFalse(fence.acceptsReady(1, stale));
        assertFalse(fence.pending(1, stale));
        assertEquals(0, fence.activeCount()); assertEquals(0, fence.reservedBytes());
        // One fresh FULL, now applied directly, closes rather than starting a retry cycle.
        assertTrue(fence.begin(1, 2)); fence.injected(1, fence.incoming(1), new Object());
        long fresh = fence.startDrain(1); assertTrue(fence.takeTail(1, fresh).isEmpty());
        assertTrue(fence.finishDrain(1, fresh)); assertEquals(0, fence.activeCount());
    }
}
