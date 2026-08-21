package io.github.limuqy.mc.hassium.utils;

import io.github.limuqy.mc.hassium.Constants;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 冒烟专用卡顿诊断。只打日志、不改行为。
 * {@code -Dhassium.smokeTest=true} 或 {@code -Dhassium.serverSmokeTest=true} 时启用；
 * 1Hz 心跳 + 关键边沿立即打一条。
 */
public final class StallDiag {

    private static final AtomicLong lastClientHzMs = new AtomicLong();
    private static final AtomicLong lastServerHzMs = new AtomicLong();
    private static final AtomicInteger enqueueRejects = new AtomicInteger();
    private static volatile Boolean lastJoinBoost;

    private StallDiag() {
    }

    public static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty("hassium.smokeTest", "false"))
                || Boolean.parseBoolean(System.getProperty("hassium.serverSmokeTest", "false"))
                || Boolean.parseBoolean(System.getProperty("hassium.stallDiag", "false"));
    }

    public static void event(String message, Object... args) {
        if (!enabled()) {
            return;
        }
        Constants.LOG.info("[STALL-DIAG] " + message, args);
    }

    public static void clientHz(String message, Object... args) {
        hz(lastClientHzMs, message, args);
    }

    public static void serverHz(String message, Object... args) {
        hz(lastServerHzMs, message, args);
    }

    /** 服务端入队被拒（满队列 / offer 失败）。计入下一秒心跳。 */
    public static void noteEnqueueReject() {
        if (enabled()) {
            enqueueRejects.incrementAndGet();
        }
    }

    public static int takeEnqueueRejects() {
        return enqueueRejects.getAndSet(0);
    }

    /** JoinBoost true→false / false→true 边沿。 */
    public static void noteJoinBoost(boolean active) {
        if (!enabled()) {
            return;
        }
        Boolean prev = lastJoinBoost;
        if (prev != null && prev == active) {
            return;
        }
        lastJoinBoost = active;
        event("joinBoost {}", active ? "ON" : "OFF");
    }

    private static void hz(AtomicLong lastMs, String message, Object... args) {
        if (!enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        long prev = lastMs.get();
        if (now - prev < 1000L || !lastMs.compareAndSet(prev, now)) {
            return;
        }
        Constants.LOG.info("[STALL-DIAG] " + message, args);
    }
}
