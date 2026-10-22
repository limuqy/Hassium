package io.github.limuqy.mc.hassium.concurrent;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkBudgetTest {
    @Test void slowConsumerHoldsReservationAfterWorkerCompletion() {
        var budget = new WorkBudget<String>(3, 2, 100, 70);
        var a = budget.tryAcquire("a", 40);
        assertNull(budget.tryAcquire("a", 40));
        var b = budget.tryAcquire("b", 40);
        assertNull(budget.tryAcquire("c", 21));
        // Completing compression does not release it: only the send/discard closes the lease.
        assertEquals(80, budget.bytes());
        a.close(); a.close();
        assertEquals(40, budget.bytes());
        assertNotNull(budget.tryAcquire("c", 60));
        b.close();
    }

    @Test void taskLimitAndResetDoNotLetOldCallbacksReleaseNewWork() {
        var budget = new WorkBudget<String>(2, 1, 100, 100);
        var old = budget.tryAcquire("a", 1);
        assertNull(budget.tryAcquire("a", 1));
        assertNotNull(budget.tryAcquire("b", 1));
        assertNull(budget.tryAcquire("c", 1));
        budget.reset();
        var current = budget.tryAcquire("a", 90);
        old.close();
        assertFalse(old.active());
        assertTrue(current.active());
        assertEquals(1, budget.jobs());
        assertEquals(90, budget.bytes());
    }

    @Test void concurrentProducersAndDuplicateClosesStayWithinLimits() throws Exception {
        var budget = new WorkBudget<Integer>(8, 2, 80, 20);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int producer = 0; producer < 8; producer++) {
                int owner = producer % 4;
                tasks.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 1000; i++) {
                        var lease = budget.tryAcquire(owner, 10);
                        if (lease == null) continue;
                        try { assertTrue(budget.jobs() <= 8); assertTrue(budget.bytes() <= 80); }
                        finally { lease.close(); lease.close(); }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var task : tasks) task.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(0, budget.jobs()); assertEquals(0, budget.bytes());
        } finally { pool.shutdownNow(); }
    }
}
