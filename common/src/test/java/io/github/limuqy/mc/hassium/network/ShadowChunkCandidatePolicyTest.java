package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.network.seedgen.ShadowChunkSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ShadowChunkCandidatePolicyTest {
    @Test
    @DisplayName("complete block light cache is the only unchanged hit")
    void requiresAllCacheGenerations() {
        assertEquals(ShadowChunkCandidatePolicy.Result.CACHE_HIT,
                ShadowChunkCandidatePolicy.resolve(true, true, true, true, true, true, true));
        assertEquals(ShadowChunkCandidatePolicy.Result.REMOTE_FULL,
                ShadowChunkCandidatePolicy.resolve(true, true, true, false, false, false, false));
        assertEquals(ShadowChunkCandidatePolicy.Result.REMOTE_FULL,
                ShadowChunkCandidatePolicy.resolve(true, false, true, true, false, false, false));
    }

    @Test
    @DisplayName("SeedGen requires explicit capability and pristine input")
    void gatesSeedgen() {
        assertEquals(ShadowChunkCandidatePolicy.Result.SEEDGEN,
                ShadowChunkCandidatePolicy.resolve(false, false, false, false, true, true, true));
        assertEquals(ShadowChunkCandidatePolicy.Result.REMOTE_FULL,
                ShadowChunkCandidatePolicy.resolve(false, false, false, false, true, true, false));
        assertEquals(ShadowChunkSource.SEEDGEN,
                ShadowChunkCandidatePolicy.source(ShadowChunkCandidatePolicy.Result.SEEDGEN));
        assertNull(ShadowChunkCandidatePolicy.source(ShadowChunkCandidatePolicy.Result.ERROR));
    }
}
