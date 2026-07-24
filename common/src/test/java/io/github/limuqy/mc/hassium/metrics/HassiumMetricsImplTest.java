package io.github.limuqy.mc.hassium.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HassiumMetricsImplTest {

    @Test
    void effectiveCacheHitRateUsesEligibleBytesAsDenominator() {
        HassiumMetricsImpl metrics = new HassiumMetricsImpl();

        metrics.recordCacheLoadEligible(1_000);
        metrics.recordCacheFullHit(300);
        metrics.recordCacheDeltaSaved(200);

        assertEquals(500, metrics.getEffectiveCacheHitBytes());
        assertEquals(0.5, metrics.getEffectiveCacheHitRate());
    }

    @Test
    void effectiveCacheHitRateReturnsZeroWithoutEligibleBytes() {
        HassiumMetricsImpl metrics = new HassiumMetricsImpl();

        metrics.recordCacheFullHit(100);
        metrics.recordCacheDeltaSaved(100);

        assertEquals(0.0, metrics.getEffectiveCacheHitRate());
    }

    @Test
    void fullChunkRequestsSeparateNewAndStaleSources() {
        HassiumMetricsImpl metrics = new HassiumMetricsImpl();

        metrics.recordFullChunkRequests(2, 32_768, false);
        metrics.recordFullChunkRequests(3, 49_152, true);

        assertEquals(5, metrics.getFullChunkRequestCount());
        assertEquals(81_920, metrics.getFullChunkRequestBytes());
        assertEquals(2, metrics.getNewFullChunkRequestCount());
        assertEquals(32_768, metrics.getNewFullChunkRequestBytes());
        assertEquals(3, metrics.getStaleFullChunkRequestCount());
        assertEquals(49_152, metrics.getStaleFullChunkRequestBytes());
    }

    @Test
    void clientDisplayRecordersIgnoreNonPositiveValues() {
        HassiumMetricsImpl metrics = new HassiumMetricsImpl();

        metrics.recordCacheLoadEligible(0);
        metrics.recordCacheFullHit(-1);
        metrics.recordCacheDeltaSaved(0);
        metrics.recordFullChunkRequests(0, 16_384, false);
        metrics.recordFullChunkRequests(1, 0, true);

        assertEquals(0, metrics.getCacheLoadEligibleBytes());
        assertEquals(0, metrics.getCacheHitFullChunkBytes());
        assertEquals(0, metrics.getCacheDeltaSavedBytes());
        assertEquals(0, metrics.getFullChunkRequestCount());
    }

    @Test
    void resetClearsClientDisplayMetrics() {
        HassiumMetricsImpl metrics = new HassiumMetricsImpl();

        metrics.recordCacheLoadEligible(16_384);
        metrics.recordCacheFullHit(16_384);
        metrics.recordCacheDeltaSaved(8_192);
        metrics.recordFullChunkRequests(1, 16_384, false);
        metrics.recordFullChunkRequests(1, 16_384, true);
        metrics.addSectionDeltaRequestsSent(1);
        metrics.recordSectionDeltaReceived(1, 16_384);

        metrics.reset();

        assertEquals(0, metrics.getCacheLoadEligibleBytes());
        assertEquals(0, metrics.getCacheHitFullChunkBytes());
        assertEquals(0, metrics.getCacheDeltaSavedBytes());
        assertEquals(0, metrics.getFullChunkRequestCount());
        assertEquals(0, metrics.getNewFullChunkRequestCount());
        assertEquals(0, metrics.getStaleFullChunkRequestCount());
        assertEquals(0, metrics.getSectionDeltaRequestsSent());
        assertEquals(0, metrics.getSectionDeltaChunksReceived());
    }
}
