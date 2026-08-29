package io.github.limuqy.mc.hassium.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** Transport-neutral server handler for shadowPullV1. */
public final class ShadowPullHandler {
    @FunctionalInterface
    public interface Resolver {
        ShadowPullResponseS2CPacket.Result resolve(ShadowPullRequestC2SPacket.Entry entry);
    }

    private final ShadowPullRequestLedger ledger;

    public ShadowPullHandler(ShadowPullRequestLedger ledger) {
        this.ledger = ledger;
    }

    public ShadowPullResponseS2CPacket handle(UUID playerId,
                                              ShadowPullRequestC2SPacket request,
                                              String expectedDimension,
                                              long expectedEpoch,
                                              int centerX,
                                              int centerZ,
                                              int maxDistance,
                                              boolean capabilityEnabled,
                                              boolean authorized,
                                              Resolver resolver) {
        ShadowPullRequestValidator.Rejection rejection = ShadowPullRequestValidator.validate(
                request, expectedDimension, expectedEpoch, centerX, centerZ, maxDistance,
                capabilityEnabled, authorized);
        if (rejection != ShadowPullRequestValidator.Rejection.NONE) {
            List<ShadowPullResponseS2CPacket.Result> errors = new ArrayList<>();
            if (request != null && request.entries() != null) {
                for (ShadowPullRequestC2SPacket.Entry entry : request.entries()) {
                    errors.add(ShadowPullResponseS2CPacket.Result.error(entry.chunkX(), entry.chunkZ(),
                            rejection.name().toLowerCase(java.util.Locale.ROOT)));
                }
            }
            return new ShadowPullResponseS2CPacket(
                    expectedDimension == null ? "" : expectedDimension,
                    request == null ? expectedEpoch : request.epoch(),
                    request == null ? 0L : request.requestId(), errors);
        }
        long fingerprint = fingerprint(request);
        ShadowPullRequestLedger.Decision decision = ledger.accept(
                playerId, request.epoch(), request.requestId(), fingerprint);
        if (decision != ShadowPullRequestLedger.Decision.ACCEPT) {
            List<ShadowPullResponseS2CPacket.Result> errors = new ArrayList<>();
            for (ShadowPullRequestC2SPacket.Entry entry : request.entries()) {
                errors.add(ShadowPullResponseS2CPacket.Result.error(entry.chunkX(), entry.chunkZ(),
                        decision.name().toLowerCase(java.util.Locale.ROOT)));
            }
            return new ShadowPullResponseS2CPacket(request.dimension(), request.epoch(),
                    request.requestId(), errors);
        }
        List<ShadowPullResponseS2CPacket.Result> results = new ArrayList<>(request.entries().size());
        for (ShadowPullRequestC2SPacket.Entry entry : request.entries()) {
            ShadowPullResponseS2CPacket.Result result;
            try {
                result = resolver.resolve(entry);
            } catch (Throwable t) {
                result = ShadowPullResponseS2CPacket.Result.error(entry.chunkX(), entry.chunkZ(), "resolver_error");
            }
            results.add(result == null
                    ? ShadowPullResponseS2CPacket.Result.error(entry.chunkX(), entry.chunkZ(), "negative")
                    : result);
        }
        return new ShadowPullResponseS2CPacket(request.dimension(), request.epoch(), request.requestId(), results);
    }

    private static long fingerprint(ShadowPullRequestC2SPacket request) {
        long hash = 1125899906842597L;
        hash = 31L * hash + request.dimension().hashCode();
        hash = 31L * hash + request.epoch();
        for (ShadowPullRequestC2SPacket.Entry entry : request.entries()) {
            hash = 31L * hash + entry.chunkX();
            hash = 31L * hash + entry.chunkZ();
            hash = 31L * hash + entry.chunkHash();
            hash = 31L * hash + entry.lightGeneration();
            for (long sectionHash : entry.sectionHashes()) {
                hash = 31L * hash + sectionHash;
            }
        }
        return hash;
    }
}
