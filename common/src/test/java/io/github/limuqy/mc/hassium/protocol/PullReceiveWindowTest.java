package io.github.limuqy.mc.hassium.protocol;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PullReceiveWindowTest {
    @Test void slowReceiverBoundsRequestsAndEventuallyDrainsAllNeighbours() {
        var window = new PullReceiveWindow<Integer>(2, 9);
        for (int i = 0; i < 9; i++) assertTrue(window.offer(i, i));
        assertFalse(window.offer(10, 10));
        int received = 0;
        while (received < 9) {
            var batch = window.drain();
            assertTrue(batch.size() <= 2);
            assertTrue(window.drain().isEmpty());
            for (var item : batch) {
                assertTrue(window.bind(item.key(), item.token(), item.token()));
                // Release on ingestion, not LIGHT/display, so missing neighbours can still arrive.
                window.complete(item.key(), item.token()); received++;
            }
        }
        assertTrue(window.active().isEmpty());
    }
    @Test void staleResponseAndResetCannotReleaseReplacement() {
        var window = new PullReceiveWindow<Integer>(1, 2);
        window.offer(1, 1);
        var old = window.drain().get(0);
        window.bind(1, old.token(), 11);
        window.clear(); window.offer(1, 2);
        var next = window.drain().get(0);
        assertFalse(window.matchesAdmission(1, old.token()));
        assertTrue(window.matchesAdmission(1, next.token()));
        assertFalse(window.bind(1, old.token(), 11));
        window.bind(1, next.token(), 12);
        window.complete(1, 11);
        assertTrue(window.matches(1, 12));
        window.cancel(1);
        assertFalse(window.matches(1, 12));
    }
    @Test void missingResponseExpiresButSlowIngestionKeepsItsCredit() {
        var window = new PullReceiveWindow<Integer>(2, 2);
        window.offer(1, 1); window.offer(2, 2);
        for (var item : window.drain()) window.bind(item.key(), item.token(), item.key(), 100);
        window.received(2, 2);
        var expired = window.expire(201, 100);
        assertEquals(1, expired.size());
        assertEquals(1, expired.get(0).key());
        assertTrue(window.matches(2, 2));
    }
}
