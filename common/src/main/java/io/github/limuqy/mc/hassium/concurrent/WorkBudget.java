package io.github.limuqy.mc.hassium.concurrent;

import java.util.HashMap;
import java.util.Map;

/** Nonblocking reservations held through the final handoff, including completed-worker queues. */
public final class WorkBudget<K> {
    private final int jobsLimit, ownerJobsLimit;
    private final long bytesLimit, ownerBytesLimit;
    private final Map<K, Usage> owners = new HashMap<>();
    private int jobs;
    private long bytes, generation;
    private static final class Usage { int jobs; long bytes; }

    public WorkBudget(int jobsLimit, int ownerJobsLimit, long bytesLimit, long ownerBytesLimit) {
        this.jobsLimit = jobsLimit; this.ownerJobsLimit = ownerJobsLimit;
        this.bytesLimit = bytesLimit; this.ownerBytesLimit = ownerBytesLimit;
    }

    public synchronized Lease tryAcquire(K owner, long weight) {
        if (weight < 0 || jobs >= jobsLimit || weight > bytesLimit - bytes) return null;
        Usage usage = owners.get(owner);
        if (usage != null && (usage.jobs >= ownerJobsLimit || weight > ownerBytesLimit - usage.bytes)) return null;
        if (weight > ownerBytesLimit) return null;
        if (usage == null) { usage = new Usage(); owners.put(owner, usage); }
        usage.jobs++; usage.bytes += weight; jobs++; bytes += weight;
        return new Lease(owner, weight, generation);
    }

    public synchronized int jobs() { return jobs; }
    public synchronized long bytes() { return bytes; }
    public synchronized void reset() { generation++; owners.clear(); jobs = 0; bytes = 0; }

    public final class Lease implements AutoCloseable {
        private final K owner;
        private final long weight, epoch;
        private boolean closed;
        private Lease(K owner, long weight, long epoch) { this.owner = owner; this.weight = weight; this.epoch = epoch; }
        public boolean active() { synchronized (WorkBudget.this) { return !closed && epoch == generation; } }
        @Override public void close() {
            synchronized (WorkBudget.this) {
                if (closed) return;
                closed = true;
                if (epoch != generation) return;
                Usage usage = owners.get(owner);
                usage.jobs--; usage.bytes -= weight; jobs--; bytes -= weight;
                if (usage.jobs == 0) owners.remove(owner);
            }
        }
    }
}
