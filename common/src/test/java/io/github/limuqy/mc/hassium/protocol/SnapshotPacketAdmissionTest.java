package io.github.limuqy.mc.hassium.protocol;

import io.github.limuqy.mc.hassium.concurrent.KeyedPriorityQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotPacketAdmissionTest {
    private record Packet(Object content) {}
    private static ColumnSnapshotFence<String> fence() {
        return new ColumnSnapshotFence<>(8, 2, 2 * ColumnSnapshotFence.RESERVATION_BYTES);
    }
    @Test void officialAndCachePacketsCaptureIdentityAtBuildAndReachTheRealReadyQueue() {
        var fence = fence(); Object chunk = new Object();
        for (String origin : new String[]{"official-B", "memory-cache"}) {
            var built = SnapshotPacketAdmission.buildCurrent(chunk, chunk, true, false,
                    () -> fence.ready(1, chunk), () -> new Packet(chunk));
            assertNotNull(built, origin); assertTrue(built.token() > 0);
            var ready = new KeyedPriorityQueue<SnapshotPacketAdmission.Built<Packet>>();
            ready.offer(built, new KeyedPriorityQueue.Key(1, 1, "overworld"), 0,
                    KeyedPriorityQueue.OfferPolicy.REPLACE);
            var delivered = ready.poll().item();
            assertTrue(fence.acceptsReady(1, delivered.token()));
            assertSame(chunk, delivered.packet().content());
        }
        assertFalse(fence.acceptsReady(1, 0));
    }
    @Test void oldOfficialPacketCannotBorrowNewFenceIdentityWhenOfferedAfterNewInjection() {
        var fence = fence(); Object old = new Object(), current = new Object();
        var oldBuilt = SnapshotPacketAdmission.buildCurrent(old, old, true, false,
                () -> fence.ready(1, old), () -> new Packet(old));
        assertNotNull(oldBuilt);
        assertTrue(fence.begin(1, 20)); fence.injected(1, fence.incoming(1), current);
        var ready = new KeyedPriorityQueue<SnapshotPacketAdmission.Built<Packet>>();
        ready.offer(oldBuilt, new KeyedPriorityQueue.Key(1, 1, "overworld"), 0,
                KeyedPriorityQueue.OfferPolicy.REPLACE);
        assertFalse(fence.acceptsReady(1, ready.poll().item().token()));
        AtomicBoolean constructed = new AtomicBoolean();
        assertNull(SnapshotPacketAdmission.buildCurrent(old, current, true, true,
                () -> fence.ready(1, current), () -> { constructed.set(true); return new Packet(old); }));
        assertFalse(constructed.get(), "An old holder packet must not be relabeled with the new chunk's token");
        assertTrue(fence.acceptsReady(1, fence.ready(1, current)), "The authoritative A delivery still completes");
    }
    @Test void ownerIdentityAndVersionChangeDuringConstructionRejectThePacket() {
        var fence = fence(); Object chunk = new Object(); AtomicBoolean built = new AtomicBoolean();
        assertNull(SnapshotPacketAdmission.buildCurrent(chunk, chunk, false, false,
                () -> fence.ready(1, chunk), () -> { built.set(true); return new Packet(chunk); }));
        assertFalse(built.get());
        assertNull(SnapshotPacketAdmission.buildCurrent(chunk, new Object(), true, false,
                () -> fence.ready(1, chunk), () -> new Packet(chunk)));
        assertNull(SnapshotPacketAdmission.buildCurrent(chunk, chunk, true, false,
                () -> fence.ready(1, chunk), () -> {
                    fence.begin(1, 10); return new Packet(chunk);
                }));
        fence.clear();
        assertEquals(0, fence.activeCount()); assertEquals(0, fence.reservedBytes());
    }
}
