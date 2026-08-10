package io.github.limuqy.mc.hassium.network.core.migration;

import java.util.function.LongSupplier;

/**
 * 空闲窗口判定（L1 骨架，REQ §C14）：玩家静止 + 区块 hash 稳定 → 适合迁移的时机。
 *
 * <p>骨架实现：
 * <ul>
 *   <li>位置变化率：{@link #sample} 记录每次位置采样；位移超过
 *       {@code moveThresholdBlocksPerSec} × 采样间隔视为"在移动"，刷新 lastMoveMs。</li>
 *   <li>区块 hash 活动：{@link #noteChunkHashActivity}（SectionHashRequest 收/发计数）
 *       刷新 lastHashActivityMs——hash 仍在交换说明增量未收敛，不宜迁移。</li>
 *   <li>{@link #isIdle}：距上次移动 / 上次 hash 活动均 ≥ {@code windowMs} → idle。</li>
 * </ul>
 *
 * <p>纯逻辑无 MC 依赖：位置源由调用方注入（客户端 {@code Minecraft.player} 或测试桩）。
 * 线程：由主线程/调用方串行驱动（骨架）。
 */
public final class IdleWindowDetector {

    /** 判定静止的移动阈值（方块/秒）。 */
    private final double moveThresholdBlocksPerSec;
    /** 连续无移动且无 hash 活动的最小时长（ms）。 */
    private final long windowMs;
    private final LongSupplier clockMs;

    private volatile long lastSampleMs = Long.MIN_VALUE;
    private volatile double lastX;
    private volatile double lastZ;
    private volatile long lastMoveMs = Long.MIN_VALUE;
    private volatile long lastHashActivityMs = Long.MIN_VALUE;

    public IdleWindowDetector(double moveThresholdBlocksPerSec, long windowMs, LongSupplier clockMs) {
        if (!(moveThresholdBlocksPerSec > 0)) {
            throw new IllegalArgumentException("moveThreshold must be positive");
        }
        if (windowMs <= 0) {
            throw new IllegalArgumentException("windowMs must be positive");
        }
        this.moveThresholdBlocksPerSec = moveThresholdBlocksPerSec;
        this.windowMs = windowMs;
        this.clockMs = clockMs;
    }

    /** 位置采样（x/z 平面距离；首个采样仅记录，不判定移动）。 */
    public void sample(double x, double z) {
        long now = clockMs.getAsLong();
        if (lastSampleMs == Long.MIN_VALUE) {
            lastSampleMs = now;
            lastMoveMs = now; // 首采样视为静止起点（避免 MIN 溢出误判）
            lastX = x;
            lastZ = z;
            return;
        }
        double dtSec = (now - lastSampleMs) / 1000.0;
        if (dtSec > 0) {
            double dx = x - lastX;
            double dz = z - lastZ;
            double rate = Math.sqrt(dx * dx + dz * dz) / dtSec;
            if (rate >= moveThresholdBlocksPerSec) {
                lastMoveMs = now;
            }
        }
        lastSampleMs = now;
        lastX = x;
        lastZ = z;
    }

    /** 区块 hash 活动（增量未收敛信号；刷新静止判定计时）。 */
    public void noteChunkHashActivity() {
        lastHashActivityMs = clockMs.getAsLong();
    }

    /** 距上次移动/上次 hash 活动均 ≥ windowMs → 空闲（适合迁移窗口）。 */
    public boolean isIdle() {
        long now = clockMs.getAsLong();
        // 从未采样/从未有 hash 活动：移动侧不成立（不判定空闲），hash 侧视为稳定
        boolean moveStable = lastMoveMs != Long.MIN_VALUE && now - lastMoveMs >= windowMs;
        boolean hashStable = lastHashActivityMs == Long.MIN_VALUE || now - lastHashActivityMs >= windowMs;
        return moveStable && hashStable;
    }

    /** 上次移动时刻（未移动过为 Long.MIN_VALUE；测试/观测用）。 */
    public long lastMoveMs() {
        return lastMoveMs;
    }

    /** 上次区块 hash 活动时刻（无活动为 Long.MIN_VALUE）。 */
    public long lastHashActivityMs() {
        return lastHashActivityMs;
    }

    /** 判定静止的移动阈值（方块/秒；policy 参数观测/重建比较用）。 */
    public double moveThresholdBlocksPerSec() {
        return moveThresholdBlocksPerSec;
    }

    /** 连续无移动且无 hash 活动的最小时长（ms；policy 参数观测/重建比较用）。 */
    public long windowMs() {
        return windowMs;
    }
}
