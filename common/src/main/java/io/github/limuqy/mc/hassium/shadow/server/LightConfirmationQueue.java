package io.github.limuqy.mc.hassium.shadow.server;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.LongPredicate;
import java.util.function.Supplier;

/** Bounded, instance-owned dirty tickets. Only the shadow owner calls drain. */
public final class LightConfirmationQueue {
    private record Ticket(long revision, long sinceNs) {}
    public record Snapshot(int pending, int highWater, boolean overflow, long marked,
                           long coalesced, long examined, long maxWaitNs, long oldestNs,
                           boolean closed) {}

    private final int capacity;
    private final LinkedHashMap<Long, Ticket> pending = new LinkedHashMap<>();
    private long revision, marked, coalesced, examined, maxWaitNs, overflowRevision;
    private long overflowSinceNs;
    private int highWater;
    private boolean closed, overflow;
    // Only drain touches the cursor. It is weakly consistent over the owner's live key set.
    private Iterator<Long> overflowCursor;
    private long cursorRevision;
    // Carries across owner passes: even a one-column time budget must serve both lanes.
    private boolean preferOverflow;

    public LightConfirmationQueue(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity");
        this.capacity = capacity;
    }

    public synchronized void mark(long key) {
        if (closed) return;
        marked++;
        Ticket old = pending.get(key);
        if (old != null) {
            coalesced++;
            pending.put(key, new Ticket(++revision, old.sinceNs));
        } else if (pending.size() < capacity) {
            pending.put(key, new Ticket(++revision, System.nanoTime()));
            highWater = Math.max(highWater, pending.size());
        } else {
            // Do not enqueue without bound, and do not lose a dirty resident column.
            if (!overflow) overflowSinceNs = System.nanoTime();
            overflow = true;
            overflowRevision++;
        }
    }

    /** No world access while holding the queue monitor; failed/changed tickets stay pending. */
    public void drain(int budget, BooleanSupplier current, LongPredicate confirm,
                      Supplier<Iterator<Long>> residentKeys, BooleanSupplier hasTime) {
        if (!current.getAsBoolean()) { close(); return; }
        int normalRemaining;
        synchronized (this) {
            if (closed) return;
            normalRemaining = Math.min(pending.size(), budget);
        }
        for (int i = 0; i < budget && hasTime.getAsBoolean(); i++) {
            Long key = null;
            Ticket ticket = null;
            synchronized (this) {
                if (closed) return;
                if (overflow && (preferOverflow || normalRemaining == 0 || pending.isEmpty())) {
                    key = nextOverflowKey(residentKeys);
                    if (key != null) preferOverflow = false;
                }
                if (key == null && normalRemaining > 0 && !pending.isEmpty()) {
                    var entry = pending.entrySet().iterator().next();
                    key = entry.getKey(); ticket = entry.getValue();
                    normalRemaining--;
                    preferOverflow = true;
                }
                if (key == null) return;
            }
            if (!current.getAsBoolean()) { close(); return; }
            boolean consumed = false;
            try { consumed = confirm.test(key); }
            finally {
                synchronized (this) {
                    examined++;
                    if (ticket != null) {
                        maxWaitNs = Math.max(maxWaitNs, System.nanoTime() - ticket.sinceNs);
                        // Remove only the exact revision we examined. New invalidations win.
                        Ticket latest = pending.remove(key);
                        if (!closed && latest != null && (!consumed || latest != ticket)) {
                            pending.put(key, latest);
                        }
                    }
                }
                if (ticket == null && !consumed) mark(key);
            }
        }
    }

    /** Called under the queue monitor; only obtains keys, never reads a world. */
    private Long nextOverflowKey(Supplier<Iterator<Long>> residentKeys) {
        if (overflowCursor == null) {
            overflowCursor = residentKeys.get();
            cursorRevision = overflowRevision;
        }
        if (overflowCursor.hasNext()) return overflowCursor.next();
        overflowCursor = null;
        if (cursorRevision == overflowRevision) {
            maxWaitNs = Math.max(maxWaitNs, System.nanoTime() - overflowSinceNs);
            overflow = false;
        }
        return null;
    }

    public synchronized Snapshot snapshot() {
        long oldest = 0, now = System.nanoTime();
        for (Ticket ticket : pending.values()) oldest = Math.max(oldest, now - ticket.sinceNs);
        if (overflow) oldest = Math.max(oldest, now - overflowSinceNs);
        return new Snapshot(pending.size(), highWater, overflow, marked, coalesced,
                examined, maxWaitNs, oldest, closed);
    }

    public synchronized boolean hasPending() { return !closed && (!pending.isEmpty() || overflow); }

    public synchronized void close() {
        closed = true;
        pending.clear();
        overflow = false;
        // The owner drops its cursor on exit. Do not touch it from a closing thread.
    }
}
