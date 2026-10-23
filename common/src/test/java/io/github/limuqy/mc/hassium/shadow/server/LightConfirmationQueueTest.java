package io.github.limuqy.mc.hassium.shadow.server;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LightConfirmationQueueTest {
    private static void drain(LightConfirmationQueue queue, int budget,
                              java.util.function.LongPredicate confirm) {
        queue.drain(budget, () -> true, confirm, List.<Long>of()::iterator, () -> true);
    }

    @Test
    void stableColumnsAreNeverRevisitedAndDirtyUpdatesCoalesce() {
        var queue = new LightConfirmationQueue(8);
        for (int i = 0; i < 100; i++) queue.mark(7L);
        var seen = new ArrayList<Long>();
        drain(queue, 32, key -> { seen.add(key); return true; });
        drain(queue, 32, key -> { fail("stable column rescanned"); return true; });
        assertEquals(List.of(7L), seen);
        assertEquals(99, queue.snapshot().coalesced());
        assertFalse(queue.hasPending());
        queue.mark(7L); // A later content/layer/LIGHT transition must be observed again.
        drain(queue, 32, key -> { seen.add(key); return true; });
        assertEquals(List.of(7L, 7L), seen);
    }

    @Test
    void concurrentInvalidationDuringConfirmationCannotBeLost() throws Exception {
        var queue = new LightConfirmationQueue(8);
        queue.mark(3L);
        var entered = new CountDownLatch(1);
        var changed = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var work = executor.submit(() -> drain(queue, 1, key -> {
                entered.countDown();
                try { assertTrue(changed.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException error) { throw new AssertionError(error); }
                return true;
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            queue.mark(3L);
            changed.countDown();
            work.get(5, TimeUnit.SECONDS);
            assertEquals(1, queue.snapshot().pending());
            drain(queue, 1, key -> true);
            assertFalse(queue.hasPending());
        } finally { executor.shutdownNow(); }
    }

    @Test
    void unconvergedDimensionDoesNotStarveAnotherDimensionAndRetryIsBounded() {
        var queue = new LightConfirmationQueue(8);
        long overworld = 4L, nether = (1L << 52) | 4L;
        queue.mark(overworld); queue.mark(nether);
        var seen = new ArrayList<Long>();
        drain(queue, 32, key -> { seen.add(key); return key == nether; });
        assertEquals(List.of(overworld, nether), seen);
        assertEquals(1, queue.snapshot().pending());
        drain(queue, 32, key -> true);
        assertFalse(queue.hasPending());
    }

    @Test
    void absentOrFailingOwnerRetainsWorkWithoutDispatchAndCanRetry() {
        var queue = new LightConfirmationQueue(8);
        queue.mark(1L);
        // No owner drain has run: there is no dispatch that can reject/lose this wakeup.
        assertEquals(1, queue.snapshot().pending());
        assertThrows(IllegalStateException.class,
                () -> drain(queue, 1, key -> { throw new IllegalStateException("probe"); }));
        assertEquals(1, queue.snapshot().pending());
        drain(queue, 1, key -> true);
        assertFalse(queue.hasPending());
    }

    @Test
    void disconnectOldSessionAndDimensionChangeDoNotPublishIntoNewSession() {
        var old = new LightConfirmationQueue(8);
        old.mark(1L);
        old.drain(1, () -> false, key -> { fail("old owner"); return true; },
                List.<Long>of()::iterator, () -> true);
        old.mark(1L);
        drain(old, 1, key -> { fail("closed owner"); return true; });
        assertTrue(old.snapshot().closed());
        assertEquals(0, old.snapshot().pending());
        var current = new LightConfirmationQueue(8);
        current.mark(1L);
        drain(current, 1, key -> true);
        assertEquals(1, current.snapshot().examined());
    }

    @Test
    void fixedCapacityOverflowRecoversEveryResidentWithBoundedBatches() {
        var queue = new LightConfirmationQueue(2);
        var residents = List.of(1L, 2L, 3L, 4L, 5L);
        residents.forEach(queue::mark);
        var seen = new java.util.HashSet<Long>();
        for (int batch = 0; batch < 20 && queue.hasPending(); batch++) {
            long before = queue.snapshot().examined();
            queue.drain(2, () -> true, key -> { seen.add(key); return true; },
                    residents::iterator, () -> true);
            assertTrue(queue.snapshot().examined() - before <= 2);
            assertTrue(queue.snapshot().pending() <= 2);
        }
        assertEquals(new java.util.HashSet<>(residents), seen);
        assertEquals(2, queue.snapshot().highWater());
        assertFalse(queue.hasPending());
    }

    @Test
    void oneColumnSoftBudgetAlternatesNormalAndOverflowAcrossOwnerPasses() {
        var queue = new LightConfirmationQueue(1);
        queue.mark(1); queue.mark(2);
        var seen = new ArrayList<Long>();
        int reachedAt = -1;
        for (int pass = 0; pass < 4; pass++) {
            var checked = new AtomicInteger();
            queue.drain(32, () -> true, key -> {
                checked.incrementAndGet();
                seen.add(key);
                return key != 1; // Retained regular ticket always spends the whole soft budget.
            }, () -> List.of(1L, 2L).iterator(), () -> checked.get() == 0);
            assertEquals(1, checked.get());
            if (seen.contains(2L)) { reachedAt = pass + 1; break; }
        }
        assertEquals(4, reachedAt, "overflow must progress despite a permanently retained regular ticket");
        assertEquals(List.of(1L, 1L, 1L, 2L), seen);
        assertEquals(1, queue.snapshot().pending());
    }

    @Test
    void oneColumnCountBudgetAlsoServesBothLanesWithoutIncreasingTheBudget() {
        var queue = new LightConfirmationQueue(1);
        queue.mark(1); queue.mark(2);
        var seen = new ArrayList<Long>();
        for (int pass = 0; pass < 4; pass++) {
            long before = queue.snapshot().examined();
            queue.drain(1, () -> true, key -> { seen.add(key); return key != 1; },
                    () -> List.of(1L, 2L).iterator(), () -> true);
            assertTrue(queue.snapshot().examined() - before <= 1);
        }
        assertEquals(List.of(1L, 1L, 1L, 2L), seen);
    }

    @Test
    void sustainedDirtyArrivalAcrossDimensionsCannotStarveOlderOverflowOrNewSweep() {
        var queue = new LightConfirmationQueue(2);
        long overworld = 1, nether = (1L << 52) | 1, end = (2L << 52) | 1;
        var residents = new ArrayList<Long>(List.of(overworld, nether, end));
        residents.forEach(queue::mark);
        var seenAt = new java.util.HashMap<Long, Integer>();
        long late = 0;
        for (int pass = 0; pass < 40; pass++) {
            long arriving = ((long) (pass % 3) << 52) | (100 + pass);
            residents.add(arriving);
            queue.mark(arriving); // Keep both regular slots occupied and overflow revision changing.
            if (pass == 2) late = arriving;
            var checked = new AtomicInteger();
            int ownerPass = pass;
            queue.drain(32, () -> true, key -> {
                checked.incrementAndGet();
                seenAt.putIfAbsent(key, ownerPass);
                return key != overworld && key != nether;
            }, () -> List.copyOf(residents).iterator(), () -> checked.get() == 0);
            assertTrue(checked.get() <= 1);
            assertTrue(queue.snapshot().pending() <= 2);
        }
        assertTrue(seenAt.get(end) < 8, "older dimension must progress in the first sweep");
        assertNotNull(seenAt.get(late), "a change arriving after the first cursor must reach a later sweep");
        assertTrue(seenAt.get(late) < 32);
        // Once sources settle, retained regular columns and every overflow change can finish.
        for (int pass = 0; pass < 100 && queue.hasPending(); pass++) {
            queue.drain(32, () -> true, key -> true, () -> List.copyOf(residents).iterator(), () -> true);
        }
        assertFalse(queue.hasPending());
    }

    @Test
    void changedOverflowDuringSweepAndProbeFailureMustRetry() {
        var queue = new LightConfirmationQueue(1);
        var residents = List.of(1L, 2L);
        queue.mark(1); queue.mark(2);
        var changed = new AtomicBoolean();
        for (int batch = 0; batch < 20 && queue.hasPending(); batch++) {
            queue.drain(2, () -> true, key -> {
                if (key == 2 && changed.compareAndSet(false, true)) {
                    queue.mark(1); queue.mark(2); // Fill and invalidate while overflow is in progress.
                    return false;
                }
                return true;
            }, residents::iterator, () -> true);
        }
        assertTrue(changed.get());
        assertFalse(queue.hasPending());
    }

    @Test
    void expiredTimeBudgetDoesNoWorkAndCloseDuringProbeCannotResurrectTickets() {
        var queue = new LightConfirmationQueue(8);
        queue.mark(1);
        queue.drain(32, () -> true, key -> { fail(); return true; },
                List.<Long>of()::iterator, () -> false);
        assertEquals(1, queue.snapshot().pending());
        drain(queue, 1, key -> { queue.close(); queue.mark(key); return false; });
        assertFalse(queue.hasPending());
        assertEquals(0, queue.snapshot().pending());
    }
}
