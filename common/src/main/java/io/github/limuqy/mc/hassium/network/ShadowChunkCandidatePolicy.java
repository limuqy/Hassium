package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.network.seedgen.ShadowChunkSource;

/** Deterministic candidate precedence for the shadow loader. */
public final class ShadowChunkCandidatePolicy {
    public enum Result {
        CACHE_HIT,
        SEEDGEN,
        REMOTE_FULL,
        ERROR
    }

    private ShadowChunkCandidatePolicy() {}

    public static Result resolve(boolean cachePresent,
                                 boolean cacheBlockHashMatches,
                                 boolean cacheSectionHashesMatch,
                                 boolean cacheLightGenerationMatches,
                                 boolean seedgenAllowed,
                                 boolean pristine,
                                 boolean seedgenSucceeded) {
        if (cachePresent && cacheBlockHashMatches && cacheSectionHashesMatch
                && cacheLightGenerationMatches) {
            return Result.CACHE_HIT;
        }
        if (seedgenAllowed && pristine) {
            return seedgenSucceeded ? Result.SEEDGEN : Result.REMOTE_FULL;
        }
        return Result.REMOTE_FULL;
    }

    public static ShadowChunkSource source(Result result) {
        return switch (result) {
            case CACHE_HIT -> ShadowChunkSource.CACHE_SNAPSHOT;
            case SEEDGEN -> ShadowChunkSource.SEEDGEN;
            case REMOTE_FULL -> ShadowChunkSource.REMOTE_FULL;
            case ERROR -> null;
        };
    }
}
