package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DebugLogger.LogType;
import net.minecraft.server.MinecraftServer;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 主控负载上报（REQ §B12）：周期采样 CPU 负载 / TPS / 堆内存 / 玩家数。
 * <p>
 * 当前输出为日志（每 {@link #REPORT_INTERVAL_TICKS} tick 一次，挂 MixinMinecraftServer
 * onServerTick）。网关侧接收口：TODO(T8) 在自有通道上以新包把 {@link #getLastReport()}
 * 推送给客户端网关（迁移策略输入）。
 */
public final class ServerLoadReporter {

    /** 上报周期（tick）：100 = 5 秒 */
    public static final int REPORT_INTERVAL_TICKS = 100;

    // review-fix: T13-FixT3Chunk-1：初值 0（Long.MIN_VALUE 使 tick - last 减法溢出恒为负，首报永不触发）
    private static final AtomicLong LAST_REPORT_TICK = new AtomicLong(0);
    private static volatile ServerLoadReport lastReport;

    private ServerLoadReporter() {}

    /** 负载快照（纯数据，可序列化传输） */
    public record ServerLoadReport(
            long timestampMs,
            int tickCount,
            double systemLoadAverage,
            double tps,
            long heapUsedMb,
            long heapMaxMb,
            int playerCount
    ) {
        @Override
        public String toString() {
            return String.format("tps=%.1f cpuLoad=%.2f mem=%d/%dMB players=%d",
                    tps, systemLoadAverage, heapUsedMb, heapMaxMb, playerCount);
        }
    }

    /** 主线程每 tick 调用；达到周期阈值时采样并输出（只输一次/周期） */
    public static void onServerTick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        long tick = server.getTickCount();
        long last = LAST_REPORT_TICK.get();
        if (tick - last < REPORT_INTERVAL_TICKS) {
            return;
        }
        if (!LAST_REPORT_TICK.compareAndSet(last, tick)) {
            return;
        }
        ServerLoadReport report = sample(server);
        lastReport = report;
        DebugLogger.info(LogType.NETWORK, "[LOAD] {}", report);
    }

    /** 采样（可在任意线程调用；server 为空 → null） */
    public static ServerLoadReport sample(MinecraftServer server) {
        if (server == null) {
            return null;
        }
        double systemLoad = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
#if MC_VER < MC_1_20_3
        float avgTickMs = server.getAverageTickTime();
#else
        float avgTickMs = server.getAverageTickTimeNanos() / 1_000_000.0f;
#endif
        double tps = avgTickMs > 0.0f ? Math.min(20.0, 1000.0 / avgTickMs) : 20.0;
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L);
        long maxMb = rt.maxMemory() / (1024L * 1024L);
        return new ServerLoadReport(System.currentTimeMillis(), server.getTickCount(),
                systemLoad, tps, usedMb, maxMb, server.getPlayerList().getPlayerCount());
    }

    /** 最近一次快照（未采样时 null）；T8 网关推送口 TODO */
    public static ServerLoadReport getLastReport() {
        return lastReport;
    }
}
