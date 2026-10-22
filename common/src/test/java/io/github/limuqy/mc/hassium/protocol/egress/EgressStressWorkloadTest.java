package io.github.limuqy.mc.hassium.protocol.egress;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EgressStressWorkloadTest {
    @Test void bothPlacementsHaveEqualMeasuredVolumeAndExactBoundaries() {
        var workload = new EgressStressWorkload(1024, 900, 1200, 100);
        int before = 0, after = 0;
        for (int tick = 0; tick < workload.totalTicks(); tick++) {
            before += workload.beforeFlushCount(tick);
            after += workload.afterFlushCount(tick);
        }
        assertEquals(2_688_000, before + after);
        assertEquals(workload.expected(), before + after);
        assertEquals(before - 900 * 256, after);
        assertEquals(0, workload.beforeFlushCount(2100));
        assertEquals(2048, workload.afterFlushCount(2100));
        assertEquals(0, workload.afterFlushCount(2101));
        assertEquals(3, workload.phase(3300));
        assertEquals(4, workload.phase(3400));
    }
    @Test void rejectsParametersThatWouldMiscountOrOverrunBurstBudget() {
        assertThrows(IllegalArgumentException.class, () -> new EgressStressWorkload(1023, 900, 1200, 100));
        assertThrows(IllegalArgumentException.class, () -> new EgressStressWorkload(2048, 900, 1200, 100));
        assertThrows(IllegalArgumentException.class, () -> new EgressStressWorkload(1024, 900, 1201, 100));
    }
    @Test void payloadHasBoundedCorpusAndMixedCompressibility() {
        String first = EgressStressWorkload.text(0), second = EgressStressWorkload.text(2);
        assertEquals(3072, first.length());
        assertEquals(first.substring(0, 1024), second.substring(0, 1024));
        assertNotEquals(first.substring(1024), second.substring(1024));
        assertTrue(first.chars().allMatch(c -> c >= 33 && c < 127));
        assertSame(first, EgressStressWorkload.text(2048));
    }
}
