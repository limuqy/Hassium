package io.github.limuqy.mc.hassium.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 遮挡图侵蚀自愈的判定逻辑钉死：只在「差额达阈值 + 编译已启动但停摆 + 非冷却 + 未超上限」
 * 时触发；在途波动（编译仍在推进）绝不触发。
 */
class OcclusionGraphHealerTest {

    private static final long NOW = 1_000_000L;

    @Test
    void gapBelowThresholdNeverHeals() {
        assertFalse(OcclusionGraphHealer.shouldHeal(
                OcclusionGraphHealer.GAP_THRESHOLD - 1, NOW, NOW - 1, 0, 0));
    }

    @Test
    void healsWhenStalledBeyondThreshold() {
        assertTrue(OcclusionGraphHealer.shouldHeal(
                OcclusionGraphHealer.GAP_THRESHOLD, NOW,
                NOW - OcclusionGraphHealer.STALL_MS - 1, 0, 0));
    }

    @Test
    void doesNotHealWhileCompileProgressing() {
        assertFalse(OcclusionGraphHealer.shouldHeal(500, NOW, NOW - 100, 0, 0));
    }

    @Test
    void doesNotHealBeforeFirstCompile() {
        assertFalse(OcclusionGraphHealer.shouldHeal(500, NOW, 0, 0, 0));
    }

    @Test
    void respectsCooldown() {
        long withinCooldown = NOW - OcclusionGraphHealer.HEAL_COOLDOWN_MS + 100;
        long pastCooldown = NOW - OcclusionGraphHealer.HEAL_COOLDOWN_MS - 1;
        assertFalse(OcclusionGraphHealer.shouldHeal(500, NOW,
                NOW - OcclusionGraphHealer.STALL_MS - 1, withinCooldown, 1));
        assertTrue(OcclusionGraphHealer.shouldHeal(500, NOW,
                NOW - OcclusionGraphHealer.STALL_MS - 1, pastCooldown, 1));
    }

    @Test
    void capsHealsPerSession() {
        assertFalse(OcclusionGraphHealer.shouldHeal(500, NOW,
                NOW - OcclusionGraphHealer.STALL_MS - 1, 0,
                OcclusionGraphHealer.MAX_HEALS_PER_SESSION));
        assertTrue(OcclusionGraphHealer.shouldHeal(500, NOW,
                NOW - OcclusionGraphHealer.STALL_MS - 1, 0,
                OcclusionGraphHealer.MAX_HEALS_PER_SESSION - 1));
    }
}
