package io.github.limuqy.mc.hassium.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** Transport-neutral server handler for shadowPullV1. */
public final class ShadowPullHandler {
    @FunctionalInterface
    public interface Resolver {
        /**
         * @param request 原始请求（待推送队列取 requestId/epoch 用）
         * @return 单柱终态；{@code null} = 该柱未就绪、已登记服务端待推送队列，
         *         本响应省略该柱，就绪后由服务端主动推送（不复述错误）。
         */
        ShadowPullResponseS2CPacket.Result resolve(ShadowPullRequestC2SPacket request,
                                                   ShadowPullRequestC2SPacket.Entry entry);
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
            if (!ShadowPullRequestValidator.inRange(entry, centerX, centerZ, maxDistance)) {
                // 逐条目范围校验：只拒绝越界柱，不牵连同批在范围内的柱
                result = ShadowPullResponseS2CPacket.Result.error(entry.chunkX(), entry.chunkZ(), "range");
            } else {
                try {
                    result = resolver.resolve(request, entry);
                } catch (Throwable t) {
                    result = ShadowPullResponseS2CPacket.Result.error(entry.chunkX(), entry.chunkZ(), "resolver_error");
                }
            }
            // null = 未就绪柱已入待推送队列：本响应省略，不产生错误终态（客户端无需重试）
            if (result != null) {
                results.add(result);
            }
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
