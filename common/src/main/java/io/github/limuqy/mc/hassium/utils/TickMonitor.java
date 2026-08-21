package io.github.limuqy.mc.hassium.utils;

import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DebugLogger.LogType;
import net.minecraft.server.MinecraftServer;

/**
 * 每 tick 耗时（mspt）采样器。
 * <p>
 * 服务端：每 tick 读 {@link MinecraftServer#getTickTime()}（原版 MSPT 同源，ns），
 * 覆盖进服/加载区块期间 worldgen、序列化、推送对 Server thread 的占用。
 * 客户端：{@code Minecraft.tick()} HEAD/TAIL 实测（含 apply 预算、光照、调度回调），
 * 即客户端主线程每 tick 负载。
 * <p>
 * 每 20 tick（约 1s）输出一行，挂 debug.dispatcherLogging（主线程调度日志）开关，
 * 默认关；开启后从冒烟/玩家日志直接拉 mspt 曲线：
 * <pre>
 * [MSPT] server avg=12.3ms max=18.5ms last=10.2ms ticks=20
 * [MSPT] hassium drainPending=1.2ms drainQueue=0.4ms flush=0.1ms
 * [MSPT] client avg=8.1ms max=21.0ms last=7.9ms ticks=20
 * </pre>
 * vanilla {@code [MSPT] server} 读的是 {@code tickTimes}，不含 {@code hassium$onServerTick}
 * 自身；Hassium TAIL 分段（flush / drainPending / drainQueue）单独一行。
 */
public final class TickMonitor {

    private TickMonitor() {
    }

    private static final int WINDOW_TICKS = 20;

    // ---- 服务端（Server thread，单线程访问） ----
    private static long serverSumNs;
    private static long serverMaxNs;
    private static long serverLastNs;
    private static int serverTicks;

    // ---- Hassium TAIL 分段（Server thread；与 vanilla tickTimes 窗口独立） ----
    private static long hassiumDrainPendingNs;
    private static long hassiumDrainQueueNs;
    private static long hassiumFlushNs;
    private static int hassiumTicks;

    // ---- 客户端（Render thread，单线程访问） ----
    private static long clientStartNs;
    private static long clientSumNs;
    private static long clientMaxNs;
    private static long clientLastNs;
    private static int clientTicks;

    /** 服务端每 tick 调用：记录最近一次 tick 耗时（ns）。tickCount 由 mixin @Shadow 传入。 */
    public static void sampleServerTick(MinecraftServer server, int tickCount) {
        try {
            if (server == null) {
                return;
            }
            long ns;
#if MC_VER < MC_1_20_3
            // 1.20.1~1.20.2：public long[] tickTimes（无公开 getter）
            ns = server.tickTimes[tickCount % 100];
#else
            // 1.20.3+：tickTimes 转私有（mojmap 无公开字段），公开 getTickTimesNanos()
            ns = server.getTickTimesNanos()[tickCount % 100];
#endif
            if (ns <= 0) {
                return;
            }
            serverSumNs += ns;
            serverMaxNs = Math.max(serverMaxNs, ns);
            serverLastNs = ns;
            if (++serverTicks >= WINDOW_TICKS) {
                flushServer();
            }
        } catch (Throwable t) {
            // 采样失败不得影响 tick
        }
    }

    /** {@code MainThreadDispatcher.flushServer} 本 tick 耗时（ns）。 */
    public static void addHassiumFlushNs(long ns) {
        if (ns > 0L) {
            hassiumFlushNs += ns;
        }
    }

    /** {@code drainPendingSends} / {@code drainPlayerQueueTick} 本 tick 耗时（ns）。 */
    public static void addHassiumDrainNs(long drainPendingNs, long drainQueueNs) {
        if (drainPendingNs > 0L) {
            hassiumDrainPendingNs += drainPendingNs;
        }
        if (drainQueueNs > 0L) {
            hassiumDrainQueueNs += drainQueueNs;
        }
    }

    /** 结算本 tick 的 Hassium TAIL 分段；每 {@link #WINDOW_TICKS} tick 打一行。 */
    public static void finishHassiumTick() {
        try {
            if (++hassiumTicks >= WINDOW_TICKS) {
                flushHassium();
            }
        } catch (Throwable t) {
            // 采样失败不得影响 tick
        }
    }

    /** 客户端 {@code Minecraft.tick()} HEAD：记录本 tick 起始时刻。 */
    public static void beginClientTick() {
        clientStartNs = System.nanoTime();
    }

    /** 客户端 {@code Minecraft.tick()} TAIL：结算本 tick 耗时。 */
    public static void endClientTick() {
        long ns = System.nanoTime() - clientStartNs;
        if (ns <= 0) {
            return;
        }
        clientSumNs += ns;
        clientMaxNs = Math.max(clientMaxNs, ns);
        clientLastNs = ns;
        if (++clientTicks >= WINDOW_TICKS) {
            flushClient();
        }
    }

    private static void flushServer() {
        DebugLogger.info(LogType.DISPATCHER,
                "[MSPT] server avg={}ms max={}ms last={}ms ticks={}",
                fmt(serverSumNs / 1_000_000.0 / serverTicks),
                fmt(serverMaxNs / 1_000_000.0),
                fmt(serverLastNs / 1_000_000.0),
                serverTicks);
        serverSumNs = 0;
        serverMaxNs = 0;
        serverTicks = 0;
    }

    private static void flushHassium() {
        DebugLogger.info(LogType.DISPATCHER, "{}",
                formatHassiumMspt(hassiumDrainPendingNs, hassiumDrainQueueNs, hassiumFlushNs, hassiumTicks));
        hassiumDrainPendingNs = 0;
        hassiumDrainQueueNs = 0;
        hassiumFlushNs = 0;
        hassiumTicks = 0;
    }

    /** 包可见：Hassium TAIL 分段日志正文（挂 debug.dispatcherLogging）。 */
    static String formatHassiumMspt(long drainPendingNs, long drainQueueNs, long flushNs, int ticks) {
        int n = ticks <= 0 ? 1 : ticks;
        return "[MSPT] hassium drainPending=" + fmt(drainPendingNs / 1_000_000.0 / n)
                + "ms drainQueue=" + fmt(drainQueueNs / 1_000_000.0 / n)
                + "ms flush=" + fmt(flushNs / 1_000_000.0 / n) + "ms";
    }

    private static void flushClient() {
        DebugLogger.info(LogType.DISPATCHER,
                "[MSPT] client avg={}ms max={}ms last={}ms ticks={}",
                fmt(clientSumNs / 1_000_000.0 / clientTicks),
                fmt(clientMaxNs / 1_000_000.0),
                fmt(clientLastNs / 1_000_000.0),
                clientTicks);
        clientSumNs = 0;
        clientMaxNs = 0;
        clientTicks = 0;
    }

    private static String fmt(double ms) {
        return String.format(java.util.Locale.ROOT, "%.1f", ms);
    }
}
