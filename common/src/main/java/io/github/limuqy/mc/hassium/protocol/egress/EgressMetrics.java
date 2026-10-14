package io.github.limuqy.mc.hassium.protocol.egress;

import java.util.concurrent.atomic.LongAdder;

/** Server-only counters. Input bytes and actual socket payload bytes are separate measurements. */
public final class EgressMetrics {
    public final LongAdder originalBytes = new LongAdder();
    public final LongAdder aggregateBytes = new LongAdder();
    public final LongAdder bypassBytes = new LongAdder();
    public final LongAdder wireBytes = new LongAdder();
    public final LongAdder packets = new LongAdder();
    public final LongAdder frames = new LongAdder();
    public final LongAdder exactReferences = new LongAdder();
    public final LongAdder templateReferences = new LongAdder();
    public final LongAdder definitionBytes = new LongAdder();
    public final LongAdder compressionNanos = new LongAdder();
    public final LongAdder compressionCpuNanos = new LongAdder();
    public final LongAdder queueNanos = new LongAdder();
    public final LongAdder errors = new LongAdder();
    public Snapshot snapshot() {
        return new Snapshot(originalBytes.sum(), aggregateBytes.sum(), bypassBytes.sum(), wireBytes.sum(),
                packets.sum(), frames.sum(), exactReferences.sum(), templateReferences.sum(), definitionBytes.sum(),
                compressionNanos.sum(), compressionCpuNanos.sum(), queueNanos.sum(), errors.sum());
    }
    public void reset() {
        for (LongAdder counter : new LongAdder[]{originalBytes, aggregateBytes, bypassBytes, wireBytes, packets, frames,
                exactReferences, templateReferences, definitionBytes, compressionNanos, compressionCpuNanos, queueNanos, errors}) counter.reset();
    }
    public record Snapshot(long originalBytes, long aggregateBytes, long bypassBytes, long wireBytes,
                           long packets, long frames, long exactReferences, long templateReferences,
                           long definitionBytes, long compressionNanos, long compressionCpuNanos, long queueNanos, long errors) {}
}
