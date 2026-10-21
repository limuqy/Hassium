package io.github.limuqy.mc.hassium.protocol;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PullRequestTrackerTest {
    @Test void consumesOnlyTerminalColumnsAndRetainsSplitResponseMode() {
        var tracker = new PullRequestTracker(8);
        tracker.register(7, Map.of(1L, 11L, 2L, 22L), true, true);
        // A queued/empty response has no terminal columns to consume.
        for (long column : List.<Long>of()) tracker.take(7, column);
        assertNull(tracker.take(7, 9));
        assertEquals(new PullRequestTracker.Mode(true, true, 11L), tracker.take(7, 1));
        assertNull(tracker.take(7, 1));
        assertEquals(new PullRequestTracker.Mode(true, true, 22L), tracker.take(7, 2));
        assertNull(tracker.take(7, 2));
    }

    @Test void boundsOldRequestsWithoutClearingRecentOnesAndResets() {
        var tracker = new PullRequestTracker(2);
        tracker.register(1, Map.of(1L, 11L), true, false);
        tracker.register(2, Map.of(2L, 0L), false, true);
        tracker.register(3, Map.of(3L, 0L), false, false);
        assertNull(tracker.take(1, 1));
        assertEquals(new PullRequestTracker.Mode(false, true, 0L), tracker.take(2, 2));
        tracker.clear();
        assertNull(tracker.take(3, 3));
    }

    @Test void sameHashFullRepairsNbtButUnknownOrDifferentTerrainDoesNotForceReplacement() {
        assertTrue(new PullRequestTracker.Mode(true, false, 42L).requiresNbtRepair(42L));
        assertFalse(new PullRequestTracker.Mode(true, false, 0L).requiresNbtRepair(42L));
        assertFalse(new PullRequestTracker.Mode(true, false, 42L).requiresNbtRepair(43L));
        assertFalse(new PullRequestTracker.Mode(false, false, 0L).requiresNbtRepair(0L));
        assertTrue(new PullRequestTracker.Mode(false, true, 0L).requiresNbtRepair(42L));
    }
}
