package io.github.limuqy.mc.hassium.utils;

import io.github.limuqy.mc.hassium.utils.DebugLogger;
import net.minecraft.world.level.ChunkPos;

/**
 * T0b 诊断埋点：首波 chunk 端到端耗时（receive→consume→light→ready→apply）与
 * drainReady apply 速率。默认关闭：仅 {@code DebugLogger LogType.NETWORK} 开启时
 * 记录/打印（isEnabled 走缓存位短路，热路径零开销）。
 * <p>
 * 线程模型：recordReceive=Netty 线程（ClientMetadataHandler.onChunkDataReceived）；
 * recordConsume/recordReady=影子后台线程（ShadowLightCompute.consumeLoop/pushReady）；
 * recordApply=客户端主线程（ShadowLightCompute.drainReady 帧尾）。数组元素写入为
 * 诊断数据，容忍良性竞态（读到中间值只会使该段统计略偏，不影响结论）。
 * <p>
 * 汇总：每 256 块 apply 打印一次各段均值 + 全程 recv→apply p50/p95（滚动）；
 * {@link #flush()}（加载屏消退时）打印累计终值。容量封顶 16384 防泄漏
 * （非影子路径的 receive 记录无 apply 消费）。
 */
public final class ChunkFlowTiming {

    private static final int I_RECV = 0, I_CONSUME = 1, I_READY = 2, I_APPLY = 3;
    private static final int MAX_TRACKED = 16384;

    /** 在途 chunk 时间戳（key=ChunkPos.asLong；apply 后移除）。 */
    private static final java.util.concurrent.ConcurrentHashMap<Long, long[]> FLOW =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 分段累计（ns）：0=recv→consume 1=consume→ready 2=ready→apply 3=recv→apply。 */
    private static final long[] SUM_NS = new long[4];
    private static final long[] CNT = new long[4];
    /** 全程 recv→apply 总延迟样本（p50/p95）。 */
    private static final java.util.List<Long> TOTALS = new java.util.ArrayList<>();
    private static final Object AGG_LOCK = new Object();

    // ---- apply 速率（每帧计数 + 每秒汇总；仅在 1s 窗口内有 apply 时打印）----
    private static final Object RATE_LOCK = new Object();
    private static long rateWindowStartNs;
    private static int rateFrames;
    private static int rateApplied;
    private static int rateMaxPerFrame;
    private static int frameApplied;

    private ChunkFlowTiming() {
    }

    /** 主开关（零开销短路：DebugLogger 缓存位）。 */
    public static boolean enabled() {
        return DebugLogger.isEnabled(DebugLogger.LogType.NETWORK);
    }

    /** 客户端收到 chunk 数据时刻（Netty 线程；ClientMetadataHandler.onChunkDataReceived）。 */
    public static void recordReceive(int x, int z) {
        if (!enabled()) {
            return;
        }
        if (FLOW.size() >= MAX_TRACKED) {
            FLOW.clear(); // 非影子路径泄漏兜底（诊断数据，容忍清空）
        }
        FLOW.put(ChunkPos.asLong(x, z), new long[]{System.nanoTime(), 0L, 0L, 0L});
    }

    /** consumeLoop 取到该 chunk 时刻（影子后台线程；批量装配点，消费前每轮覆写）。 */
    public static void recordConsume(long key) {
        if (!enabled()) {
            return;
        }
        long[] t = FLOW.get(key);
        if (t != null) {
            t[I_CONSUME] = System.nanoTime();
        }
    }

    /** pushReady 入回传队列时刻（影子后台线程）。 */
    public static void recordReady(long key) {
        if (!enabled()) {
            return;
        }
        long[] t = FLOW.get(key);
        if (t != null) {
            t[I_READY] = System.nanoTime();
        }
    }

    /** drainReady 主线程 apply 完成时刻；累计分段均值 + 每 256 块汇总 + 速率窗口。 */
    public static void recordApply(long key) {
        if (!enabled()) {
            return;
        }
        long[] t = FLOW.remove(key);
        if (t == null) {
            return;
        }
        long now = System.nanoTime();
        t[I_APPLY] = now;
        noteApplyInFrame(now);
        synchronized (AGG_LOCK) {
            long[] deltas = {-1L, -1L, -1L, now - t[I_RECV]};
            if (t[I_CONSUME] > 0) {
                deltas[0] = t[I_CONSUME] - t[I_RECV];
            }
            if (t[I_READY] > 0 && t[I_CONSUME] > 0) {
                deltas[1] = t[I_READY] - t[I_CONSUME];
            }
            if (t[I_READY] > 0) {
                deltas[2] = now - t[I_READY];
            }
            for (int i = 0; i < 4; i++) {
                if (deltas[i] >= 0) {
                    SUM_NS[i] += deltas[i];
                    CNT[i]++;
                }
            }
            TOTALS.add(deltas[3]);
            long applied = CNT[I_APPLY];
            if ((applied & 0xFF) == 0L) {
                printAggregate("window");
            }
        }
    }

    /** 帧开始（ShadowLightCompute.drainReady 入口；主线程每帧一次）。 */
    public static void noteFrame() {
        if (!enabled()) {
            return;
        }
        synchronized (RATE_LOCK) {
            closeFrame();
            maybeRollWindow();
        }
    }

    private static void noteApplyInFrame(long now) {
        synchronized (RATE_LOCK) {
            frameApplied++;
            maybeRollWindow();
        }
    }

    private static void closeFrame() {
        if (frameApplied > 0) {
            rateFrames++;
            rateApplied += frameApplied;
            if (frameApplied > rateMaxPerFrame) {
                rateMaxPerFrame = frameApplied;
            }
            frameApplied = 0;
        }
    }

    /** 1s 窗口到期且窗口内有 apply → 打印；空窗口静默重开（登录潮后无噪音）。 */
    private static void maybeRollWindow() {
        long now = System.nanoTime();
        if (rateWindowStartNs == 0L) {
            rateWindowStartNs = now;
            return;
        }
        long winMs = (now - rateWindowStartNs) / 1_000_000L;
        if (winMs < 1000L) {
            return;
        }
        if (rateApplied > 0) {
            DebugLogger.info(DebugLogger.LogType.NETWORK,
                    "[APPLY-RATE] window={}ms frames={} applied={} meanPerFrame={} maxPerFrame={}",
                    winMs, rateFrames, rateApplied,
                    String.format("%.1f", rateFrames == 0 ? 0.0 : (double) rateApplied / rateFrames),
                    rateMaxPerFrame);
        }
        rateWindowStartNs = now;
        rateFrames = 0;
        rateApplied = 0;
        rateMaxPerFrame = 0;
    }

    /** 累计终值打印（加载屏消退时调用；含全程 p50/p95）。 */
    public static void flush() {
        if (!enabled()) {
            return;
        }
        synchronized (AGG_LOCK) {
            printAggregate("final");
        }
    }

    private static void printAggregate(String tag) {
        long applied = CNT[I_APPLY];
        if (applied == 0L) {
            return;
        }
        String seg0 = CNT[0] == 0L ? "n/a" : String.format("%.1fms", SUM_NS[0] / 1e6 / CNT[0]);
        String seg1 = CNT[1] == 0L ? "n/a" : String.format("%.1fms", SUM_NS[1] / 1e6 / CNT[1]);
        String seg2 = CNT[2] == 0L ? "n/a" : String.format("%.1fms", SUM_NS[2] / 1e6 / CNT[2]);
        long[] sorted = TOTALS.stream().mapToLong(Long::longValue).sorted().toArray();
        long p50 = sorted[(int) (sorted.length * 0.50)];
        long p95 = sorted[(int) (sorted.length * 0.95)];
        DebugLogger.info(DebugLogger.LogType.NETWORK,
                "[FLOW-DIAG] {} applied={} recv->consume={} consume->ready={} ready->apply={} total={} p50={}ms p95={}ms",
                tag, applied, seg0, seg1, seg2,
                String.format("%.1fms", SUM_NS[3] / 1e6 / CNT[3]),
                p50 / 1e6, p95 / 1e6);
    }
}
