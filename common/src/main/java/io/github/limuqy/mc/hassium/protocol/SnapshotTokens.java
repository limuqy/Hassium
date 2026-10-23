package io.github.limuqy.mc.hassium.protocol;

import java.util.concurrent.atomic.AtomicLong;

/** Opaque identities shared by normal Pull and sleep; never reset or derived from wire request ids. */
public final class SnapshotTokens {
    /** A normal tail completion can retain LIGHT authority without retaining content/ready authority. */
    public static boolean permitsLight(long current,long completed,long token) {
        return token>0 && (token==current || token==completed);
    }
    private static final AtomicLong sequence = new AtomicLong();
    private SnapshotTokens() {}
    public static long next() {
        long token = sequence.incrementAndGet();
        if (token <= 0) throw new IllegalStateException("Snapshot token space exhausted");
        return token;
    }
}
