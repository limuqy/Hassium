package io.github.limuqy.mc.hassium.network;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShadowPullRequestValidatorTest {
    private static ShadowPullRequestC2SPacket request(String dimension, long epoch, int x, int z) {
        return new ShadowPullRequestC2SPacket(dimension, epoch, 1L,
                List.of(new ShadowPullRequestC2SPacket.Entry(x, z, 0L, List.of(), 0)));
    }

    @Test
    @DisplayName("pull validation gates capability, permission, dimension, epoch, and range")
    void validatesServerBoundary() {
        assertEquals(ShadowPullRequestValidator.Rejection.CAPABILITY,
                validate(request("minecraft:overworld", 4, 0, 0), false, true));
        assertEquals(ShadowPullRequestValidator.Rejection.PERMISSION,
                validate(request("minecraft:overworld", 4, 0, 0), true, false));
        assertEquals(ShadowPullRequestValidator.Rejection.DIMENSION,
                validate(request("minecraft:the_nether", 4, 0, 0), true, true));
        assertEquals(ShadowPullRequestValidator.Rejection.EPOCH,
                validate(request("minecraft:overworld", 3, 0, 0), true, true));
        assertEquals(ShadowPullRequestValidator.Rejection.RANGE,
                validate(request("minecraft:overworld", 4, 2, 0), true, true));
        assertEquals(ShadowPullRequestValidator.Rejection.NONE,
                validate(request("minecraft:overworld", 4, 1, -1), true, true));
    }

    private static ShadowPullRequestValidator.Rejection validate(
            ShadowPullRequestC2SPacket request, boolean capability, boolean authorized) {
        return ShadowPullRequestValidator.validate(request, "minecraft:overworld", 4L,
                0, 0, 1, capability, authorized);
    }
}
