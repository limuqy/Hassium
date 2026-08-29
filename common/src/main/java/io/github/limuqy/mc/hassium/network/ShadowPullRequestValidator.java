package io.github.limuqy.mc.hassium.network;

/** Pure validation boundary for shadowPullV1 before touching a server ChunkSource. */
public final class ShadowPullRequestValidator {
    private ShadowPullRequestValidator() {}

    public enum Rejection {
        NONE,
        CAPABILITY,
        PERMISSION,
        DIMENSION,
        EPOCH,
        RANGE,
        EMPTY
    }

    public static Rejection validate(ShadowPullRequestC2SPacket request,
                                     String expectedDimension,
                                     long expectedEpoch,
                                     int centerX,
                                     int centerZ,
                                     int maxDistance,
                                     boolean capabilityEnabled,
                                     boolean authorized) {
        if (request == null || request.entries().isEmpty()) {
            return Rejection.EMPTY;
        }
        if (!capabilityEnabled) {
            return Rejection.CAPABILITY;
        }
        if (!authorized) {
            return Rejection.PERMISSION;
        }
        if (expectedDimension == null || !expectedDimension.equals(request.dimension())) {
            return Rejection.DIMENSION;
        }
        if (request.epoch() != expectedEpoch) {
            return Rejection.EPOCH;
        }
        if (maxDistance < 0) {
            return Rejection.RANGE;
        }
        for (ShadowPullRequestC2SPacket.Entry entry : request.entries()) {
            long dx = (long) entry.chunkX() - centerX;
            long dz = (long) entry.chunkZ() - centerZ;
            if (Math.max(Math.abs(dx), Math.abs(dz)) > maxDistance) {
                return Rejection.RANGE;
            }
        }
        return Rejection.NONE;
    }
}
