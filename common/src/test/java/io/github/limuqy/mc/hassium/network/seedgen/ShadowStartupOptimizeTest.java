package io.github.limuqy.mc.hassium.network.seedgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 影子端早期启动 / 保活复用闸门（不启 MinecraftServer）。
 */
class ShadowStartupOptimizeTest {


    @Test
    void reuseParkedInstanceSameServerId() {
        assertTrue(ShadowServerRegistry.shouldReuseParkedInstance(
                "server_127.0.0.1_25565", "server_127.0.0.1_25565", true));
        assertFalse(ShadowServerRegistry.shouldReuseParkedInstance(
                "server_a", "server_b", true));
        assertFalse(ShadowServerRegistry.shouldReuseParkedInstance(
                "server_a", "server_a", false));
        // 身份未齐：保守复用
        assertTrue(ShadowServerRegistry.shouldReuseParkedInstance(null, "server_a", true));
        assertTrue(ShadowServerRegistry.shouldReuseParkedInstance("server_a", null, true));
    }

    @Test
    void unparkBlockedUntilLoginAndEncodingResume() {
        assertFalse(ShadowServerRegistry.shouldUnpark(true, true, true));
        assertFalse(ShadowServerRegistry.shouldUnpark(true, false, false));
        assertTrue(ShadowServerRegistry.shouldUnpark(true, false, true));
        assertFalse(ShadowServerRegistry.shouldUnpark(false, false, true));
        assertFalse(ShadowServerRegistry.shouldUnpark(false, true, true));
    }
}
