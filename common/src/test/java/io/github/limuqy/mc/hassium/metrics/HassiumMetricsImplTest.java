package io.github.limuqy.mc.hassium.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HassiumMetricsImplTest {

    @Test
    void effectiveCacheHitRateIgnoresSeedGenAndUsesBytes() {
        HassiumMetricsImpl metrics = new HassiumMetricsImpl();

        // 2 全命中 + 1 SeedGen（不算缓存）+ 1 部分命中（未记分片）
        metrics.recordCacheFullHit(16_384);
        metrics.recordCacheFullHit(16_384);
        metrics.recordLocallyGeneratedChunk(16_384);
        metrics.recordCacheDeltaSaved(16_384);

        // 分子 = 全命中 32768 + 部分 16384；分母不含 SeedGen = 49152 → 100%
        assertEquals(2, metrics.getEffectiveCacheHitCount());
        assertEquals(49_152, metrics.getEffectiveCacheHitBytes());
        assertEquals(1.0, metrics.getEffectiveCacheHitRate(), 1e-9);
        assertEquals(4, metrics.getClientAppliedChunkCount());
        assertEquals(49_152, metrics.getClientAppliedChunkBytes());
    }

    @Test
    void effectiveCacheHitRateSubtractsChangedSectionShardOnly() {
        HassiumMetricsImpl metrics = new HassiumMetricsImpl();

        metrics.recordCacheFullHit(16_384);
        metrics.recordCacheDeltaSaved(16_384);
        // 24 section 里改 2 个 → 分片 1365，未变内容仍算部分命中
        metrics.recordCacheShard(16_384 * 2 / 24);

        long expectedHit = 16_384 + 16_384 - (16_384 * 2 / 24);
        assertEquals(1, metrics.getEffectiveCacheHitCount());
        assertEquals(expectedHit, metrics.getEffectiveCacheHitBytes());
        assertEquals((double) expectedHit / 32_768, metrics.getEffectiveCacheHitRate(), 1e-9);
        assertEquals(2, metrics.getClientAppliedChunkCount());
    }

    @Test
    void shardEquivBytesScalesByChangedSections() {
        assertEquals(0L, NetworkStats.shardEquivBytes(0, 24));
        assertEquals(0L, NetworkStats.shardEquivBytes(2, 0));
        assertEquals(16_384L * 2 / 24, NetworkStats.shardEquivBytes(2, 24));
        assertEquals(16_384L, NetworkStats.shardEquivBytes(24, 24));
        assertEquals(16_384L, NetworkStats.shardEquivBytes(30, 24));
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
        metrics.recordCacheShard(0);
        metrics.recordCacheShard(-1);
        metrics.recordFullChunkRequests(0, 16_384, false);
        metrics.recordFullChunkRequests(1, 0, true);

        assertEquals(0, metrics.getCacheLoadEligibleBytes());
        assertEquals(0, metrics.getCacheHitFullChunkBytes());
        assertEquals(0, metrics.getCacheDeltaSavedBytes());
        assertEquals(0, metrics.getCacheShardBytes());
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
        metrics.recordCacheShard(1_365);
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
        assertEquals(0, metrics.getCacheShardBytes());
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
