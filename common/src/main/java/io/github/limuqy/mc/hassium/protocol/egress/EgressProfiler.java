package io.github.limuqy.mc.hassium.protocol.egress;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/** Opt-in, bounded stage measurements. Allocation counts cover Java heap on the current thread only. */
public final class EgressProfiler {
    public static final boolean ENABLED = Boolean.getBoolean("hassium.egressProfile")
            || "egressload".equals(System.getProperty("hassium.smokeScenario"))
            || "egressload".equals(System.getProperty("hassium.serverSmokeScenario"));
    private static final class Allocations { static final com.sun.management.ThreadMXBean BEAN = allocationBean(); }
    public enum Stage {
        BATCH_WAIT, FIFO_WAIT, PREPARE, WORKER_QUEUE, CODEC, SAMPLE, EVENT_LOOP_RETURN, WRITE, OUTBOUND_TOTAL,
        INBOUND_WORKER_QUEUE, DECODE, INBOUND_EVENT_LOOP_RETURN, NATIVE_DELIVERY;
        private final Distribution values = new Distribution();
        void record(long nanos, long allocated) { if (ENABLED) values.record(nanos, allocated); }
    }
    public record Summary(long count, long totalNs, long p50UpperNs, long p95UpperNs, long p99UpperNs,
                          long maxNs, long allocatedBytes, long allocationSamples) {}
    static final class Distribution {
        private final AtomicLongArray buckets = new AtomicLongArray(488);
        private final LongAdder total = new LongAdder(), allocations = new LongAdder(), allocationSamples = new LongAdder();
        private final LongAccumulator max = new LongAccumulator(Long::max, 0);
        void record(long nanos, long allocated) {
            nanos = Math.max(0, nanos);
            int exponent = nanos < 8 ? 0 : 63 - Long.numberOfLeadingZeros(nanos);
            int index = nanos < 8 ? (int)nanos : 8 + (exponent - 3) * 8 + (int)(nanos >>> (exponent - 3)) - 8;
            buckets.incrementAndGet(index); total.add(nanos); max.accumulate(nanos);
            if (allocated >= 0) { allocations.add(allocated); allocationSamples.increment(); }
        }
        Summary snapshot() {
            long[] counts = new long[buckets.length()]; long count = 0;
            for (int i = 0; i < counts.length; i++) { counts[i] = buckets.get(i); count += counts[i]; }
            return new Summary(count, total.sum(), percentile(counts, count, 50), percentile(counts, count, 95),
                    percentile(counts, count, 99), max.get(), allocations.sum(), allocationSamples.sum());
        }
        private static long percentile(long[] counts, long count, int percentile) {
            if (count == 0) return 0;
            long rank = count / 100 * percentile + ((count % 100 * percentile + 99) / 100), seen = 0;
            for (int i = 0; i < counts.length; i++) if ((seen += counts[i]) >= rank) {
                if (i < 8) return i;
                int exponent = 3 + (i - 8) / 8, sub = (i - 8) % 8;
                if (exponent == 62 && sub == 7) return Long.MAX_VALUE;
                return ((9L + sub) << (exponent - 3)) - 1;
            }
            return 0;
        }
        void reset() { for (int i = 0; i < buckets.length(); i++) buckets.set(i, 0); total.reset(); allocations.reset(); allocationSamples.reset(); max.reset(); }
    }
    private static com.sun.management.ThreadMXBean allocationBean() {
        if (!(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean)
                || !bean.isThreadAllocatedMemorySupported()) return null;
        try { if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true); }
        catch (UnsupportedOperationException | SecurityException ignored) { return null; }
        return bean;
    }
    static long allocated() { return ENABLED ? currentThreadAllocatedBytes() : -1; }
    static long currentThreadAllocatedBytes() {
        var bean = Allocations.BEAN;
        return bean == null ? -1 : bean.getThreadAllocatedBytes(Thread.currentThread().getId());
    }
    static long allocationDelta(long before) { return before < 0 ? -1 : Math.max(0, allocated() - before); }
    static void finish(Stage stage, long started, long allocated) {
        if (ENABLED) stage.record(System.nanoTime() - started, allocationDelta(allocated));
    }
    static void elapsed(Stage stage, long started) { if (ENABLED) stage.record(System.nanoTime() - started, -1); }
    public static Map<String, Summary> snapshot() {
        Map<String, Summary> result = new LinkedHashMap<>();
        if (ENABLED) for (Stage stage : Stage.values()) result.put(stage.name(), stage.values.snapshot());
        return result;
    }
    static void reset() { for (Stage stage : Stage.values()) stage.values.reset(); }
    private EgressProfiler() {}
}
