package io.github.limuqy.mc.hassium.protocol;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Invoked inside the owner's existing chunk lock, at construction rather than on an old packet. */
public final class SnapshotPacketAdmission {
    public record Built<T>(T packet, long token) {}
    private SnapshotPacketAdmission() {}
    public static <T> Built<T> buildCurrent(Object source, Object current, boolean owner, boolean fenced,
                                           LongSupplier version, Supplier<T> build) {
        if (!owner || fenced || source == null || source != current) return null;
        long captured = version.getAsLong();
        if (captured <= 0) return null;
        T packet = build.get();
        // Receive-side invalidation can run concurrently even while the owner holds the chunk lock.
        return captured == version.getAsLong() ? new Built<>(packet, captured) : null;
    }
}
