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
            assertEquals(0, metrics.getFullChunkRequestCount());
            assertEquals(0, metrics.getFullChunkRequestBytes());
            assertEquals(1, metrics.getServerPushAppliedCount());
            assertEquals(1, metrics.getClientLandedChunkCount());

            NativeChunkMetrics.recordAppliedFullChunk("minecraft:overworld", 4, -7, 0);
            assertEquals(0, metrics.getFullChunkRequestCount(),
                    "没有 payload 的未完成记录不得伪造客户端拉取指标");
            assertEquals(1, metrics.getServerPushAppliedCount(),
                    "没有 payload 的未完成记录不得伪造直推落地指标");
        } finally {
            NetworkStats.reset();
            NetworkStats.setEnabled(false);
        }
    }
}
