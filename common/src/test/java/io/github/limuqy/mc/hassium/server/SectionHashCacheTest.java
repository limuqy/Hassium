package io.github.limuqy.mc.hassium.server;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SectionHashCacheTest {
    @Test void dirtySectionsAreHiddenAndOnlyChangedSectionsAreRecomputed() {
        var cache = new SectionHashCache(8);
        long[] world = {11, 22, 0, 44, 0};
        var reads = new AtomicInteger();
        assertArrayEquals(new long[]{11, 22, 0, 44}, cache.resolve(1, 5, i -> {
            reads.incrementAndGet(); return world[i];
        }));
        assertEquals(5, reads.get());
        cache.invalidateSection(1, 1);
        cache.invalidateSection(1, 1);
        world[1] = 23;
        assertNull(cache.get(1), "A partially dirty column cannot advertise its old authority hash");
        reads.set(0);
        assertArrayEquals(new long[]{11, 23, 0, 44}, cache.resolve(1, 5, i -> {
            reads.incrementAndGet(); return world[i];
        }));
        assertEquals(1, reads.get(), "Repeated writes to one section coalesce");
        cache.resolve(1, 5, i -> { fail("An unchanged column must not scan sections"); return 0; });
    }

    @Test void trailingAirCanBecomeNonemptyThenReturnToCanonicalEmpty() {
        var cache = new SectionHashCache(8);
        cache.put(1, new long[0]);
        cache.invalidateSection(1, 23);
        var refreshed = cache.resolve(1, 24, i -> i == 23 ? 99 : 0);
        assertEquals(24, refreshed.length);
        assertEquals(99, refreshed[23]);
        cache.invalidateSection(1, 23);
        assertArrayEquals(new long[0], cache.resolve(1, 24, i -> 0));
    }

    @Test void incrementalResultsMatchFullRecalculationAcrossSectionBoundaries() {
        var cache = new SectionHashCache(8);
        var random = new Random(23010);
        long[] world = new long[24];
        cache.resolve(1, 24, i -> world[i]);
        for (int tick = 0; tick < 500; tick++) {
            for (int change = 0; change < 1 + random.nextInt(8); change++) {
                int section = random.nextInt(world.length);
                world[section] = random.nextBoolean() ? random.nextLong() : 0;
                cache.invalidateSection(1, section);
            }
            int length = world.length;
            while (length > 0 && world[length - 1] == 0) length--;
            assertArrayEquals(Arrays.copyOf(world, length), cache.resolve(1, 24, i -> world[i]));
        }
    }

    @Test void failedCaptureRemainsDirtyAndCanBeRetried() {
        var cache = new SectionHashCache(8);
        cache.put(1, new long[]{11, 22});
        cache.invalidateSection(1, 0);
        assertThrows(IllegalArgumentException.class,
                () -> cache.resolve(1, 2, i -> { throw new IllegalArgumentException("fixture"); }));
        assertNull(cache.get(1));
        assertArrayEquals(new long[]{33, 22}, cache.resolve(1, 2, i -> 33));
    }

    @Test void invalidationOrSessionClearDuringCaptureRejectsStalePublication() {
        var cache = new SectionHashCache(8);
        assertThrows(IllegalStateException.class, () -> cache.resolve(1, 2, i -> {
            if (i == 0) cache.invalidateSection(1, 0);
            return 10;
        }));
        assertNull(cache.get(1));
        assertThrows(IllegalStateException.class, () -> cache.resolve(1, 2, i -> {
            cache.clear(); return 11;
        }));
        assertEquals(0, cache.size(), "Shutdown must not be undone by an older capture");
    }

    @Test void unknownCoordinatesAndDimensionHeightChangesFailClosed() {
        var cache = new SectionHashCache(8);
        cache.resolve(1, 2, i -> 10);
        cache.invalidateSection(1, -1);
        assertNull(cache.get(1));
        assertArrayEquals(new long[]{20, 20}, cache.resolve(1, 2, i -> 20));
        assertArrayEquals(new long[]{30, 30, 30}, cache.resolve(1, 3, i -> 30));
        cache.invalidate(1);
        assertNull(cache.get(1));
    }

    @Test void snapshotsAreDefensiveAndLruCapacityIsBoundedAcrossDimensions() {
        var cache = new SectionHashCache(2);
        long[] supplied = {11};
        cache.put(1, supplied); supplied[0] = 99;
        cache.put(2, new long[]{22});
        long[] received = cache.get(1); received[0] = 98;
        cache.put(3, new long[]{33});
        assertEquals(2, cache.size());
        assertNull(cache.get(2));
        assertArrayEquals(new long[]{11}, cache.get(1));
        cache.invalidateSection(3, 0);
        assertArrayEquals(new long[]{11}, cache.get(1), "Other dimension/coordinate keys remain current");
    }
}
