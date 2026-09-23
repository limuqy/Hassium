package io.github.limuqy.mc.hassium.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityLerpPacingTest {

    @BeforeEach
    void resetCadence() {
        EntityLerpPacing.clear();
    }

    @Test
    @DisplayName("间隔 1..上限直接作窗口：窗口=间隔时每段匀速首尾相接")
    void planStepsUsesGapAsWindow() {
        assertEquals(1, EntityLerpPacing.planSteps(1, 3));
        assertEquals(3, EntityLerpPacing.planSteps(3, 3));
        assertEquals(10, EntityLerpPacing.planSteps(10, 3));
        assertEquals(EntityLerpPacing.MAX_CATCH_UP_GAP,
                EntityLerpPacing.planSteps(EntityLerpPacing.MAX_CATCH_UP_GAP, 3));
    }

    @Test
    @DisplayName("超上限（首包 / 静止 60 tick 兜底重发后）回落原版 3，防久静止慢滑")
    void planStepsFallsBackBeyondCap() {
        assertEquals(EntityLerpPacing.VANILLA_STEPS, EntityLerpPacing.planSteps(21, 10));
        assertEquals(EntityLerpPacing.VANILLA_STEPS, EntityLerpPacing.planSteps(60, 10));
        assertTrue(EntityLerpPacing.MAX_CATCH_UP_GAP < 60, "上限须小于原版静止重发周期");
    }

    @Test
    @DisplayName("同 tick 第二包沿用上次窗口；无历史时用 3，步数恒 ≥1")
    void planStepsKeepsWindowOnZeroGap() {
        assertEquals(7, EntityLerpPacing.planSteps(0, 7));
        assertEquals(7, EntityLerpPacing.planSteps(-3, 7));
        assertEquals(EntityLerpPacing.VANILLA_STEPS, EntityLerpPacing.planSteps(0, 0));
        assertEquals(EntityLerpPacing.VANILLA_STEPS, EntityLerpPacing.planSteps(-1, -1));
    }

    @Test
    @DisplayName("首包用原版 3，第二包起窗口跟随实际间隔")
    void observeLearnsSteadyCadence() {
        assertEquals(EntityLerpPacing.VANILLA_STEPS, EntityLerpPacing.observeAndPlan(1, 1_000));
        assertEquals(10, EntityLerpPacing.observeAndPlan(1, 1_010));
        assertEquals(10, EntityLerpPacing.observeAndPlan(1, 1_020));
        assertEquals(4, EntityLerpPacing.observeAndPlan(1, 1_024));
    }

    @Test
    @DisplayName("同 tick 双包幂等：第二包不污染节奏样本")
    void observeIdempotentWithinTick() {
        assertEquals(EntityLerpPacing.VANILLA_STEPS, EntityLerpPacing.observeAndPlan(7, 500));
        assertEquals(10, EntityLerpPacing.observeAndPlan(7, 510));
        assertEquals(10, EntityLerpPacing.observeAndPlan(7, 510));
        assertEquals(10, EntityLerpPacing.observeAndPlan(7, 520));
    }

    @Test
    @DisplayName("间隔由大缩小（降帧换档 / impulse 提前包）时窗口立即跟随")
    void observeFollowsCadenceShrink() {
        assertEquals(EntityLerpPacing.VANILLA_STEPS, EntityLerpPacing.observeAndPlan(2, 0));
        assertEquals(EntityLerpPacing.VANILLA_STEPS, EntityLerpPacing.observeAndPlan(2, 30));
        assertEquals(3, EntityLerpPacing.observeAndPlan(2, 33));
        assertEquals(2, EntityLerpPacing.observeAndPlan(2, 35));
    }

    @Test
    @DisplayName("实体 id 互不串扰")
    void observeIsolatesEntities() {
        assertEquals(EntityLerpPacing.VANILLA_STEPS, EntityLerpPacing.observeAndPlan(11, 100));
        assertEquals(EntityLerpPacing.VANILLA_STEPS, EntityLerpPacing.observeAndPlan(22, 100));
        assertEquals(8, EntityLerpPacing.observeAndPlan(11, 108));
        assertEquals(6, EntityLerpPacing.observeAndPlan(22, 106));
    }

    @Test
    @DisplayName("clear 后回到首包语义（重登换会话）")
    void clearResetsSession() {
        EntityLerpPacing.observeAndPlan(4, 100);
        assertEquals(10, EntityLerpPacing.observeAndPlan(4, 110));
        EntityLerpPacing.clear();
        assertEquals(EntityLerpPacing.VANILLA_STEPS, EntityLerpPacing.observeAndPlan(4, 120));
    }
}
