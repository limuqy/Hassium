package io.github.limuqy.mc.hassium.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HassiumMetricsImplTest {

    @Test
    void effectiveCacheHitRateUsesAppliedChunkDenominator() {
        HassiumMetricsImpl metrics = new HassiumMetricsImpl();

        // 2 客户端缓存 + 1 本地重算（SeedGen 本地生成）+ 1 分片增量 = 4 个应用区块来源
        metrics.recordCacheFullHit(16_384);
        metrics.recordCacheFullHit(16_384);
        metrics.recordLocallyGeneratedChunk(16_384);
        metrics.recordCacheDeltaSaved(16_384);

        // (客户端缓存 2 + 本地重算 1 - 分片 1) / 应用区块 4
        assertEquals(2, metrics.getEffectiveCacheHitCount());
        assertEquals(32_768, metrics.getEffectiveCacheHitBytes());
        assertEquals(0.5, metrics.getEffectiveCacheHitRate(), 1e-9);
        assertEquals(4, metrics.getClientAppliedChunkCount());
    }

    @Test
    void effectiveCacheHitRateSubtractsSectionDeltaFromHitNumerator() {
        HassiumMetricsImpl metrics = new HassiumMetricsImpl();

        // 1 客户端缓存 + 1 分片增量：分片计入应用区块，并按公式从命中分子中扣除
        metrics.recordCacheFullHit(16_384);
        metrics.recordCacheDeltaSaved(16_384);

        assertEquals(0, metrics.getEffectiveCacheHitCount());
        assertEquals(0, metrics.getEffectiveCacheHitBytes());
        assertEquals(0.0, metrics.getEffectiveCacheHitRate(), 1e-9);
        assertEquals(2, metrics.getClientAppliedChunkCount());
    }

    @Test
    void effectiveCacheHitRateReturnsZeroWithoutAnyChunkSources() {
        HassiumMetricsImpl metrics = new HassiumMetricsImpl();

        assertEquals(0, metrics.getEffectiveCacheHitCount());
        assertEquals(0.0, metrics.getEffectiveCacheHitRate());
        assertEquals(0, metrics.getClientAppliedChunkCount());
    }

    @Test
    void clientLandedChunkCountDeduplicatesPositions() {
        HassiumMetricsImpl metrics = new HassiumMetricsImpl();

        metrics.recordClientChunkApplied(1L);
        metrics.recordClientChunkApplied(1L);
        metrics.recordClientChunkApplied(2L);

        assertEquals(2, metrics.getClientLandedChunkCount());
        assertEquals(0, metrics.getClientAppliedChunkCount()); // 来源计数与落地快照互不影响
    }

    @Test
    void lightCacheHitRateIncludesShadowReuseAndRecompute() {
        HassiumMetricsImpl metrics = new HassiumMetricsImpl();

        metrics.recordLightReuseShadow(16_384);
        metrics.recordLightReuseShadow(16_384);
        metrics.recordLightCacheMiss(16_384);

        assertEquals(2.0 / 3.0, metrics.getLightCacheHitRate(), 1e-9);
    }

    @Test
    void networkReplacedCacheHitsAreTrackedSeparately() {
        HassiumMetricsImpl metrics = new HassiumMetricsImpl();

        metrics.recordCacheFullHit(16_384);
        metrics.recordCacheFullHitNetworkReplaced(16_384);
        metrics.recordCacheFullHit(16_384);

        assertEquals(2, metrics.getCacheHitFullChunkCount());
        assertEquals(1, metrics.getCacheHitNetworkReplacedCount());
        assertEquals(16_384, metrics.getCacheHitNetworkReplacedBytes());
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
        metrics.recordCacheFullHitNetworkReplaced(-1);
        metrics.recordCacheDeltaSaved(0);
        metrics.recordFullChunkRequests(0, 16_384, false);
        metrics.recordFullChunkRequests(1, 0, true);

        assertEquals(0, metrics.getCacheLoadEligibleBytes());
        assertEquals(0, metrics.getCacheHitFullChunkBytes());
        assertEquals(0, metrics.getCacheDeltaSavedBytes());
        assertEquals(0, metrics.getFullChunkRequestCount());
        assertEquals(1, metrics.getCacheHitNetworkReplacedCount()); // count 不受 bytes<=0 影响
        assertEquals(0, metrics.getCacheHitNetworkReplacedBytes());
    }

    @Test
    void resetClearsClientDisplayMetrics() {
        HassiumMetricsImpl metrics = new HassiumMetricsImpl();

        metrics.recordCacheLoadEligible(16_384);
        metrics.recordCacheFullHit(16_384);
        metrics.recordCacheFullHitNetworkReplaced(16_384);
        metrics.recordCacheDeltaSaved(8_192);
        metrics.recordFullChunkRequests(1, 16_384, false);
        metrics.recordFullChunkRequests(1, 16_384, true);
        metrics.addSectionDeltaRequestsSent(1);
        metrics.recordSectionDeltaReceived(1, 16_384);
        metrics.recordLocallyGeneratedChunk(16_384);
        metrics.recordClientChunkApplied(1L);

        metrics.reset();

        assertEquals(0, metrics.getCacheLoadEligibleBytes());
        assertEquals(0, metrics.getCacheHitFullChunkBytes());
        assertEquals(0, metrics.getCacheDeltaSavedBytes());
        assertEquals(0, metrics.getFullChunkRequestCount());
        assertEquals(0, metrics.getNewFullChunkRequestCount());
        assertEquals(0, metrics.getStaleFullChunkRequestCount());
        assertEquals(0, metrics.getSectionDeltaRequestsSent());
        assertEquals(0, metrics.getSectionDeltaChunksReceived());
        assertEquals(0, metrics.getLocallyGeneratedChunkCount());
        assertEquals(0, metrics.getClientAppliedChunkCount());
        assertEquals(0, metrics.getClientLandedChunkCount());
        assertEquals(0, metrics.getCacheHitNetworkReplacedCount());
        assertEquals(0, metrics.getCacheHitNetworkReplacedBytes());
    }
}
