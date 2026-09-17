package io.github.limuqy.mc.hassium.network.seedgen;

import io.github.limuqy.mc.hassium.shadow.track.ShadowTrackingSession;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 注入表回收判定（L0：无 MC 实例）。
 * <p>
 * 覆盖「真服 Forget 登记离开 → 宽限 → 非在途 → 客户端未重新持有」四条守卫的完整真值表。
 * 运行时触发需移动型场景（玩家出圈使柱离开交付窗），本测只锁判定语义。
 */
class ShadowTrackingReclaimTest {

    private static final long GRACE = ShadowTrackingSession.RECLAIM_GRACE_MS;

    @Test
    @DisplayName("宽限边界：宽限内不得回收，到点即可回收")
    void graceBoundary() {
        long now = 1_000_000L;
        assertFalse(ShadowTrackingSession.reclaimEligible(now, now + GRACE - 1, false, false),
                "宽限内不得回收");
        assertTrue(ShadowTrackingSession.reclaimEligible(now, now + GRACE, false, false),
                "到宽限即可回收");
        assertTrue(ShadowTrackingSession.reclaimEligible(now, now + GRACE + 5_000L, false, false));
    }

    @Test
    @DisplayName("守卫：在途 / 客户端已重新持有 / 无离开标记，一律不得回收")
    void guards() {
        long now = 1_000_000L;
        long since = now - GRACE - 1L;
        assertFalse(ShadowTrackingSession.reclaimEligible(since, now, true, false), "在途不得回收");
        assertFalse(ShadowTrackingSession.reclaimEligible(since, now, false, true), "客户端已重新持有不得回收");
        assertFalse(ShadowTrackingSession.reclaimEligible(since, now, true, true), "两者同时成立同样不得回收");
        assertFalse(ShadowTrackingSession.reclaimEligible(0L, now, false, false), "无离开标记不得回收");
    }

    @Test
    @DisplayName("判定只依赖离开时刻：同一标记在后续扫描中恒为可回收（幂等）")
    void deterministicPerLeaveMark() {
        long since = 500_000L;
        assertFalse(ShadowTrackingSession.reclaimEligible(since, since + 1_000L, false, false));
        assertTrue(ShadowTrackingSession.reclaimEligible(since, since + 7_000L, false, false));
        assertTrue(ShadowTrackingSession.reclaimEligible(since, since + 60_000L, false, false));
    }
}
