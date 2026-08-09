package io.github.limuqy.mc.hassium.network.core.migration;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 空闲窗口判定（骨架）：位置变化率 + 区块 hash 活动稳定性。
 */
class IdleWindowDetectorTest {

    @Test
    void idleAfterStationaryWindow() {
        AtomicLong clock = new AtomicLong(0);
        IdleWindowDetector d = new IdleWindowDetector(0.5, 10_000, clock::get);
        assertFalse(d.isIdle(), "未采样前不判定空闲");

        d.sample(0, 0);            // t=0
        clock.set(1_000);
        d.sample(0, 0);            // 静止（速率 0 < 0.5 b/s）
        clock.set(9_999);
        assertFalse(d.isIdle(), "静止不足窗口时长");
        clock.set(10_001);
        assertTrue(d.isIdle(), "静止 ≥ 10s → 空闲");
    }

    @Test
    void movementResetsIdle() {
        AtomicLong clock = new AtomicLong(0);
        IdleWindowDetector d = new IdleWindowDetector(0.5, 10_000, clock::get);
        d.sample(0, 0);            // t=0
        clock.set(1_000);
        d.sample(10, 0);           // 速率 10 b/s → 移动（lastMove=1000）
        clock.set(10_999);
        assertFalse(d.isIdle(), "移动后窗口内不空闲");
        clock.set(11_001);
        assertTrue(d.isIdle(), "移动后静止满窗口 → 空闲");
    }

    @Test
    void chunkHashActivityResetsIdle() {
        AtomicLong clock = new AtomicLong(0);
        IdleWindowDetector d = new IdleWindowDetector(0.5, 10_000, clock::get);
        d.sample(0, 0);
        clock.set(11_001);
        d.sample(0, 0);
        assertTrue(d.isIdle(), "静止且无 hash 活动 → 空闲");

        clock.set(12_000);
        d.noteChunkHashActivity(); // 区块 hash 仍在交换（增量未收敛）
        assertFalse(d.isIdle());
        clock.set(21_999);
        assertFalse(d.isIdle(), "hash 活动后不足窗口");
        clock.set(22_001);
        assertTrue(d.isIdle(), "hash 活动停止满窗口 → 空闲");
    }
}
