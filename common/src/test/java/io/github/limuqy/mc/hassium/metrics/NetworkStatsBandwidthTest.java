package io.github.limuqy.mc.hassium.metrics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NetworkStatsBandwidthTest {

    @BeforeEach
    void setUp() {
        NetworkStats.reset();
        NetworkStats.setEnabled(false);
    }

    @AfterEach
    void tearDown() {
        NetworkStats.reset();
        NetworkStats.setEnabled(false);
    }

    @Test
    void disabledSkipsAllRecording() {
        NetworkStats.recordChunkSent(1000);
        NetworkStats.recordWireBytesSent(200);
        NetworkStats.recordVanillaBytesSent(500);
        assertEquals(0, NetworkStats.getMetrics().getVanillaBytesSent());
        assertEquals(0, NetworkStats.getMetrics().getActualBytesSent());
        assertEquals(0, NetworkStats.getMetrics().getChunksCompressed());
    }

    @Test
    void enabledSeparatesVanillaAndWireActual() {
        NetworkStats.setEnabled(true);
        NetworkStats.recordChunkSent(1000);
        NetworkStats.recordWireBytesSent(200);

        assertEquals(1000, NetworkStats.getMetrics().getVanillaBytesSent());
        assertEquals(200, NetworkStats.getMetrics().getActualBytesSent());
        assertEquals(1, NetworkStats.getMetrics().getChunksCompressed());

        // 80% saving
        double saved = 1.0 - (200.0 / 1000.0);
        assertEquals(0.8, saved, 1e-9);
        assertEquals("5.00:1", MetricsTextFormatter.formatCompressionRatio(1000, 200));
    }

    @Test
    void sectionDeltaDoesNotWriteActual() {
        NetworkStats.setEnabled(true);
        NetworkStats.recordSectionDeltaReceived(2, 32_768L);
        assertEquals(32_768L, NetworkStats.getMetrics().getVanillaBytesReceived());
        assertEquals(0, NetworkStats.getMetrics().getActualBytesReceived());
        assertEquals(2, NetworkStats.getMetrics().getSectionDeltaChunksReceived());
    }

    @Test
    void resetClearsCounters() {
        NetworkStats.setEnabled(true);
        NetworkStats.recordChunkSent(100);
        NetworkStats.recordWireBytesSent(10);
        NetworkStats.reset();
        assertEquals(0, NetworkStats.getMetrics().getVanillaBytesSent());
        assertEquals(0, NetworkStats.getMetrics().getActualBytesSent());
    }

    @Test
    void landedChunkCountDeduplicatesByPosition() {
        NetworkStats.setEnabled(true);
        NetworkStats.recordChunkApplied(1, 2);
        NetworkStats.recordChunkApplied(1, 2);
        NetworkStats.recordChunkApplied(3, 4);
        assertEquals(2, NetworkStats.getMetrics().getClientLandedChunkCount());
    }

    @Test
    void noModReceiveUsesDataPacketsLocalGenCacheAndLight() {
        NetworkStats.setEnabled(true);
        HassiumMetricsImpl m = NetworkStats.getMetrics();

        // 数据包：真发 1000B wire + 分片全量等价 4918B（分片不再另列，直接并入数据包）
        m.recordVanillaBytesReceived(1_000L);
        m.recordSectionDeltaReceived(1, VanillaZlibEstimator.estimate((int) NetworkStats.ESTIMATED_CHUNK_BYTES));
        // 本地重算：2 个 SeedGen 本地生成
        m.recordLocallyGeneratedChunk(NetworkStats.ESTIMATED_CHUNK_BYTES);
        m.recordLocallyGeneratedChunk(NetworkStats.ESTIMATED_CHUNK_BYTES);
        // 客户端缓存：3 个全命中，其中 1 个的完整区块其实已经到线 → 只算 2 个「少推」
        m.recordCacheFullHit(NetworkStats.ESTIMATED_CHUNK_BYTES);
        m.recordCacheFullHitNetworkReplaced(NetworkStats.ESTIMATED_CHUNK_BYTES);
        m.recordCacheFullHit(NetworkStats.ESTIMATED_CHUNK_BYTES);
        m.recordCacheFullHit(NetworkStats.ESTIMATED_CHUNK_BYTES);
        // 光照：1 直连命中 + 4 影子复用 + 1 本地重算
        m.recordLightCacheHit(NetworkStats.ESTIMATED_LIGHT_BYTES);
        for (int i = 0; i < 4; i++) {
            m.recordLightReuseShadow(NetworkStats.ESTIMATED_LIGHT_BYTES);
        }
        m.recordLightCacheMiss(NetworkStats.ESTIMATED_LIGHT_BYTES);
        m.recordActualBytesReceived(1_000L);

        long chunkWire = VanillaZlibEstimator.estimate((int) NetworkStats.ESTIMATED_CHUNK_BYTES);
        long lightWire = VanillaZlibEstimator.estimate((int) NetworkStats.ESTIMATED_LIGHT_BYTES);
        // 数据包 1000 + delta 全量等价，本地 2，缓存少推 2，光照 6
        long expected = 1_000L + chunkWire + chunkWire * 2 + chunkWire * 2 + lightWire * 6;
        assertEquals(expected, m.getNoModReceiveBytes());
        assertEquals(1_000.0 / expected * 100.0, m.getTrafficSavingsPercent(), 1e-9);
    }
}