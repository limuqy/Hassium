package io.github.limuqy.mc.hassium.utils;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LatencyHistogramTest {
    @Test void emptyAndExtremeSamples() {
        var h = new LatencyHistogram();
        assertEquals(0, h.snapshot().count());
        h.record(-1);
        h.record(0);
        h.record(Long.MAX_VALUE);
        assertEquals(2, h.snapshot().count());
        assertEquals(0, h.snapshot().p50Ns());
        assertEquals(Long.MAX_VALUE, h.snapshot().p99Ns());
    }

    @Test void quantilesBoundKnownDistributionWithoutRetainingSamples() {
        var h = new LatencyHistogram();
        for (int n = 1; n <= 100_000; n++) h.record(n * 1_000L);
        var s = h.snapshot();
        assertEquals(100_000, s.count());
        assertEquals(50_000_500, s.meanNs());
        assertTrue(s.p50Ns() >= 50_000_000 && s.p50Ns() <= 53_125_000);
        assertTrue(s.p95Ns() >= 95_000_000 && s.p95Ns() <= 100_000_000);
        assertEquals(100_000_000, s.maxNs());
    }
}
