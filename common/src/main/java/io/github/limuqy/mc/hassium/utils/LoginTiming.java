package io.github.limuqy.mc.hassium.utils;

import io.github.limuqy.mc.hassium.utils.DebugLogger;

/**
 * T0b 诊断埋点：登入→看到世界 总耗时（ClientLifecycleHelper.onLogin → 加载屏消退）。
 * 默认关闭：LogType.NETWORK 开启时记录并打印；onLoadingScreenDismissed 幂等（只打一次）。
 */
public final class LoginTiming {

    private static volatile long loginStartMs;
    private static final java.util.concurrent.atomic.AtomicBoolean DISMISSED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private LoginTiming() {
    }

    /** handleLogin 时刻（ClientLifecycleHelper.onLogin 首行）。 */
    public static void markLogin() {
        if (!DebugLogger.isEnabled(DebugLogger.LogType.NETWORK)) {
            return;
        }
        loginStartMs = System.currentTimeMillis();
    }

    /** 加载屏消退（MixinMinecraft.setScreen 拦截 ReceivingLevelScreen 消失）；打印总耗时并冲刷 chunk 流统计。 */
    public static void onLoadingScreenDismissed() {
        if (!DebugLogger.isEnabled(DebugLogger.LogType.NETWORK)) {
            return;
        }
        if (!DISMISSED.compareAndSet(false, true)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (loginStartMs > 0L) {
            DebugLogger.info(DebugLogger.LogType.NETWORK,
                    "[LOGIN-DIAG] handleLogin -> see world = {}ms", now - loginStartMs);
        } else {
            DebugLogger.info(DebugLogger.LogType.NETWORK,
                    "[LOGIN-DIAG] loading screen dismissed, login start not recorded (wall={})", now);
        }
        ChunkFlowTiming.flush();
    }
}
