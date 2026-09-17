package io.github.limuqy.mc.hassium.protocol;

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
        // 范围校验改逐条目（ShadowPullHandler 循环内调 {@link #inRange}）：
        // 整批拒绝会把同批全部在范围内柱一起错误化（pull11 实证 R1 landed 掉到 938）。
        return Rejection.NONE;
    }

    /** 单条目切比雪夫范围校验（超出即终端失败，客户端不重试）。 */
    public static boolean inRange(ShadowPullRequestC2SPacket.Entry entry,
                                  int centerX, int centerZ, int maxDistance) {
        if (entry == null || maxDistance < 0) {
            return false;
        }
        long dx = (long) entry.chunkX() - centerX;
        long dz = (long) entry.chunkZ() - centerZ;
        return Math.max(Math.abs(dx), Math.abs(dz)) <= maxDistance;
    }
}
