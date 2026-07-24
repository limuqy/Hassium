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
}