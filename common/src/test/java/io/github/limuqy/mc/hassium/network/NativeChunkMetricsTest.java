package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NativeChunkMetricsTest {

    @Test
    void recordsOnlySuccessfullyAppliedNativeChunkPayload() {
        NetworkStats.reset();
        NetworkStats.setEnabled(true);
        try {
            NativeChunkMetrics.recordAppliedFullChunk("minecraft:overworld", 3, -7, 1_024);

            var metrics = NetworkStats.getMetrics();
            assertEquals(1_024, metrics.getVanillaBytesReceived());
            assertEquals(1_024, metrics.getActualBytesReceived());
            assertEquals(1, metrics.getFullChunkRequestCount());
            assertEquals(1_024, metrics.getFullChunkRequestBytes());
            assertEquals(1, metrics.getClientLandedChunkCount());

            NativeChunkMetrics.recordAppliedFullChunk("minecraft:overworld", 4, -7, 0);
            assertEquals(1, metrics.getFullChunkRequestCount(),
                    "没有 payload 的未完成记录不得伪造完整区块指标");
        } finally {
            NetworkStats.reset();
            NetworkStats.setEnabled(false);
        }
    }
}
