package io.github.limuqy.mc.hassium.server.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EntityFramePressureTest {

    @Test
    @DisplayName("初始 1 挡，maxPressure 构造时 clamp 到 [1,16]")
    void constructorClampsMaxPressure() {
        assertEquals(1, new EntityFramePressure(8).pressure());
        assertEquals(1, new EntityFramePressure(0).pressure());
        assertEquals(1, new EntityFramePressure(-3).pressure());
        EntityFramePressure wide = new EntityFramePressure(100);
        for (int i = 0; i < 6; i++) {
            wide.sample(30, 10);
        }
        assertEquals(16, wide.pressure(), "上限硬顶 16，不会翻到 32");
    }

    @Test
    @DisplayName("budget <= 0 回到 1 挡并清空 EWMA")
    void zeroBudgetResetsToBasePressure() {
        EntityFramePressure p = new EntityFramePressure(8);
        p.sample(30, 10);
        assertEquals(2, p.pressure());
        p.sample(30, 0);
        assertEquals(1, p.pressure());
        // EWMA 已清空：下一帧按首帧直接取 ratio = 0.4（低载）而非 0.75*3 + 0.25*0.4 = 2.35（超载）
        p.sample(4, 10);
        assertEquals(1, p.pressure());
    }

    @Test
    @DisplayName("超载快攻：EWMA > 1.25 时压力翻倍直到上限")
    void overloadDoublesPressureToCap() {
        EntityFramePressure p = new EntityFramePressure(8);
        p.sample(30, 10);
        assertEquals(2, p.pressure());
        p.sample(30, 10);
        assertEquals(4, p.pressure());
        p.sample(30, 10);
        assertEquals(8, p.pressure());
        p.sample(30, 10);
        assertEquals(8, p.pressure(), "到上限后不再翻倍");

        EntityFramePressure low = new EntityFramePressure(3);
        low.sample(30, 10);
        assertEquals(2, low.pressure());
        low.sample(30, 10);
        assertEquals(3, low.pressure(), "min(maxPressure, pressure*2)");
    }

    @Test
    @DisplayName("1.25 是升挡边界：取等号不升，超过才翻倍")
    void overloadThresholdIsExclusive() {
        EntityFramePressure at = new EntityFramePressure(8);
        at.sample(50, 40);
        assertEquals(1, at.pressure(), "ratio 恰为 1.25 不算超载");
        EntityFramePressure above = new EntityFramePressure(8);
        above.sample(52, 40);
        assertEquals(2, above.pressure(), "ratio 1.3 > 1.25 翻倍");
    }

    @Test
    @DisplayName("低载满 20 次降 1 挡；EWMA 衰减穿过回差区间的帧不计入低载")
    void underloadStepsDownEvery20Samples() {
        EntityFramePressure p = new EntityFramePressure(8);
        p.sample(13, 10);
        assertEquals(2, p.pressure(), "ratio 1.3 首帧即超载");
        // 静默帧把 EWMA 按 0.75 衰减：1.3 → 0.975 → 0.731 → 0.548 仍在回差区间，第 4 次才跌破 0.5
        for (int i = 0; i < 3; i++) {
            p.sample(0, 10);
        }
        assertEquals(2, p.pressure(), "回差区间不计入低载计数");
        for (int i = 0; i < 19; i++) {
            p.sample(0, 10);
        }
        assertEquals(2, p.pressure(), "第 19 次低载：还差 1 次");
        p.sample(0, 10);
        assertEquals(1, p.pressure(), "第 20 次低载触发降挡");
    }

    @Test
    @DisplayName("0.5~1.25 回差区间内压力保持不动")
    void deadbandKeepsPressureStable() {
        EntityFramePressure p = new EntityFramePressure(8);
        p.sample(13, 10);
        assertEquals(2, p.pressure());
        // ratio 1.0 让 EWMA 从 1.3 收敛到 1.0，全程落在回差区间：既不超载也不低载
        for (int i = 0; i < 100; i++) {
            p.sample(10, 10);
        }
        assertEquals(2, p.pressure());
    }

    @Test
    @DisplayName("reset 回到初始态：压力 1 且 EWMA 未初始化")
    void resetReturnsToInitialState() {
        EntityFramePressure p = new EntityFramePressure(8);
        p.sample(30, 10);
        p.sample(30, 10);
        assertEquals(4, p.pressure());
        p.reset();
        assertEquals(1, p.pressure());
        p.sample(4, 10);
        assertEquals(1, p.pressure(), "reset 后首帧直接取 ratio 0.4，而非旧 EWMA 的 2.35");
    }
}
