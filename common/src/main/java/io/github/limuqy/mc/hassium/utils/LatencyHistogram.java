package io.github.limuqy.mc.hassium.utils;

/** Fixed-memory histogram. Quantiles are upper bounds, within 6.25% above 16 ns. */
public final class LatencyHistogram {
    private final long[] buckets = new long[16 + 60 * 16];
    private long count, max;
    private double total;
    public synchronized void record(long nanos) {
        if (nanos < 0) return;
        int exponent = 63 - Long.numberOfLeadingZeros(nanos);
        int bucket = nanos < 16 ? (int) nanos
                : 16 + (exponent - 4) * 16 + (int) (nanos >>> (exponent - 4)) - 16;
        buckets[bucket]++;
        count++;
        total += nanos;
        max = Math.max(max, nanos);
    }
    public synchronized Snapshot snapshot() {
        return new Snapshot(count, count == 0 ? 0 : total / count, percentile(.5), percentile(.95), percentile(.99), max);
    }
    private long percentile(double fraction) {
        long target = (long) Math.ceil(count * fraction), cumulative = 0;
        if (target == 0) return 0;
        for (int i = 0; i < buckets.length; i++) {
            cumulative += buckets[i];
            if (cumulative < target) continue;
            if (i < 16) return i;
            int shift = (i - 16) / 16;
            long units = 17L + (i - 16) % 16;
            long upper = units > (Long.MAX_VALUE >>> shift) ? Long.MAX_VALUE : (units << shift) - 1;
            return Math.min(max, upper);
        }
        return max;
    }
    public record Snapshot(long count, double meanNs, long p50Ns, long p95Ns, long p99Ns, long maxNs) {}
}
