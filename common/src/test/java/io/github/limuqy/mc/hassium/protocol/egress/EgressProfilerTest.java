package io.github.limuqy.mc.hassium.protocol.egress;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EgressProfilerTest {
    @Test void cumulativeBucketCopiesIsolateSteadyAndBurstWithoutResetting() {
        var distribution = new EgressProfiler.Distribution();
        for (int n = 0; n < 20; n++) distribution.record(1_000, -1);
        long[] warmup = distribution.counts();
        for (int n = 0; n < 80; n++) distribution.record(50_000_000, -1);
        long[] after = distribution.counts();
        assertEquals(20, Arrays.stream(warmup).sum());
        assertEquals(80, Arrays.stream(after).sum() - Arrays.stream(warmup).sum());
        Arrays.fill(after, 0);
        assertEquals(100, distribution.snapshot().count());
    }
    @Test void percentilesBoundTheObservedRanksAndUnsupportedAllocationIsNotZero() {
        var distribution = new EgressProfiler.Distribution();
        for (int n = 1; n <= 100; n++) distribution.record(n * 1000L, -1);
        var result = distribution.snapshot();
        assertEquals(100, result.count()); assertEquals(5_050_000, result.totalNs());
        assertTrue(result.p50UpperNs() >= 50_000 && result.p50UpperNs() < 56_250);
        assertTrue(result.p95UpperNs() >= 95_000 && result.p95UpperNs() < 106_875);
        assertEquals(100_000, result.maxNs()); assertEquals(0, result.allocationSamples());
        distribution.record(Long.MAX_VALUE, 123); assertEquals(Long.MAX_VALUE, distribution.snapshot().maxNs());
        assertEquals(123, distribution.snapshot().allocatedBytes()); assertEquals(1, distribution.snapshot().allocationSamples());
        distribution.reset(); assertEquals(0, distribution.snapshot().count());
    }
    @Test void publicPacketBoundaryIsDefensiveAndLogicalObserverMatchesEveryPacket() {
        byte[] input = {1,2,3}; var packet = new EgressFrameCodec.PacketBytes("fixture:state", input);
        input[0] = 9; byte[] exposed = packet.bytes(); exposed[1] = 9;
        assertArrayEquals(new byte[]{1,2,3}, packet.bytes());
        try (var encoder = new EgressFrameCodec(3); var decoder = new EgressFrameCodec(3)) {
            List<byte[]> observed = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                var frame = encoder.encode(List.of(packet), 1, null, observed::add);
                assertArrayEquals(packet.bytes(), decoder.decode(frame.bytes(), null).get(0));
                assertArrayEquals(packet.bytes(), EgressFrameCodec.splitLogical(observed.get(i)).get(0));
            }
            assertArrayEquals(observed.get(0), observed.get(4));
        }
    }
}
