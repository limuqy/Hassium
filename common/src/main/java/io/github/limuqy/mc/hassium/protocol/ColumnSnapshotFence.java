package io.github.limuqy.mc.hassium.protocol;

import io.github.limuqy.mc.hassium.concurrent.WorkBudget;
import io.github.limuqy.mc.hassium.protocol.sleep.SleepUpdateBuffer;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Versioned, bounded ownership of a snapshot and its native update tail. No world access. */
public final class ColumnSnapshotFence<T> {
    public static final int MAX_PACKETS = 512, MAX_BYTES = 256 * 1024;
    public static final long RESERVATION_BYTES = 2L * (MAX_BYTES + MAX_PACKETS * 128L);
    private final int capacity;
    private final Map<Long, State<T>> states = new LinkedHashMap<>(1024, .75f, true);
    private final WorkBudget<Boolean> budget;
    private static final class State<T> {
        long request, version, completedVersion;
        Object chunk;
        boolean active, draining;
        SleepUpdateBuffer<T> tail;
        WorkBudget<Boolean>.Lease lease;
    }
    public ColumnSnapshotFence(int capacity, int activeLimit, long bytesLimit) {
        this.capacity = capacity;
        budget = new WorkBudget<>(activeLimit, activeLimit, bytesLimit, bytesLimit);
    }
    private State<T> state(long key) {
        State<T> state = states.get(key);
        if (state != null) return state;
        if (states.size() >= capacity) {
            Iterator<State<T>> iterator = states.values().iterator();
            while (iterator.hasNext()) {
                if (!iterator.next().active) { iterator.remove(); break; }
            }
            if (states.size() >= capacity) return null;
        }
        state = new State<>(); state.version = SnapshotTokens.next(); states.put(key, state); return state;
    }
    public synchronized long incoming(long key) {
        State<T> state = state(key); return state == null ? -1 : state.version;
    }
    public synchronized boolean pending(long key, long version) {
        State<T> state = states.get(key);
        return state != null && state.version == version && !state.draining;
    }
    /** Only already committed content may use this credential to finish its remaining LIGHT. */
    public synchronized boolean lightCurrent(long key,long version) {
        State<T> state=states.get(key);
        return state!=null && SnapshotTokens.permitsLight(state.version,state.completedVersion,version);
    }
    public synchronized boolean begin(long key, long request) {
        State<T> state = state(key);
        if (state == null || request <= state.request) return false;
        // Replacement releases its old reservation before taking the new fixed-size one.
        close(state);
        state.request = request; state.version = SnapshotTokens.next(); state.chunk = null;
        state.lease = budget.tryAcquire(Boolean.TRUE, RESERVATION_BYTES);
        state.active = state.lease != null; state.draining = false;
        state.tail = state.active ? new SleepUpdateBuffer<>(MAX_PACKETS, MAX_BYTES) : null;
        return state.active;
    }
    public synchronized boolean active(long key) {
        State<T> state = states.get(key); return state != null && state.active;
    }
    /** Observe existing state only: incoming()/ready() would create a token and perturb diagnosis. */
    public synchronized String diagnostics(long key) {
        State<T> state = states.get(key);
        return state == null ? "fence=none" : "fenceRequest=" + state.request + " version=" + state.version
                + " completed=" + state.completedVersion + " active=" + state.active
                + " bound=" + (state.chunk != null) + " draining=" + state.draining;
    }
    /** FULL ingress may not reuse a same-hash baseline while its authoritative snapshot is unbound. */
    public synchronized boolean requiresFullReplacement(long key) { return active(key); }
    public synchronized boolean request(long key, long request) {
        State<T> state = states.get(key); return state != null && state.active && state.request == request;
    }
    public synchronized void injected(long key, long version, Object chunk) {
        State<T> state = states.get(key);
        if (state != null && state.active && state.version == version && !state.draining) state.chunk = chunk;
    }
    public synchronized void injectedRequest(long key, long request, Object chunk) {
        State<T> state = states.get(key);
        if (state != null && state.active && state.request == request && !state.draining) state.chunk = chunk;
    }
    public synchronized long ready(long key, Object chunk) {
        State<T> state = state(key);
        if (state == null || state.draining || state.active && (state.chunk == null || state.chunk != chunk)) return -1;
        return state.version;
    }
    public synchronized boolean acceptsReady(long key, long version) {
        State<T> state = states.get(key);
        return state != null && state.version == version && !state.draining
                && (!state.active || state.chunk != null);
    }
    public synchronized boolean append(long key, T value, int bytes) {
        State<T> state = states.get(key);
        if (state == null || !state.active) return false;
        state.tail.add(value, bytes); return true;
    }
    public synchronized void overflow(long key) {
        State<T> state = states.get(key); if (state != null && state.active) state.tail.overflow();
    }
    public synchronized boolean overflowed(long key) {
        State<T> state = states.get(key); return state != null && state.active && state.tail.overflowed();
    }
    /** A render/halo completion starts draining; the caller must then cross the shadow owner boundary. */
    public synchronized long startDrain(long key) {
        State<T> state = states.get(key);
        if (state == null || !state.active || state.draining || state.chunk == null) return -1;
        state.draining = true; return state.version;
    }
    public synchronized List<T> takeTail(long key, long version) {
        State<T> state = states.get(key);
        if (state == null || state.version != version || !state.draining || state.tail.overflowed()) return null;
        List<T> tail = state.tail.values(); state.tail = new SleepUpdateBuffer<>(MAX_PACKETS, MAX_BYTES); return tail;
    }
    /** False means more native updates arrived while the shadow owner consumed the preceding batch. */
    public synchronized boolean finishDrain(long key, long version) {
        State<T> state = states.get(key);
        if (state == null || state.version != version || !state.draining || state.tail.overflowed()
                || state.tail.size() != 0) return false;
        long completed=state.version;
        close(state); state.completedVersion=completed;
        state.version = SnapshotTokens.next(); state.chunk = null; return true;
    }
    public synchronized void cancel(long key) {
        State<T> state = states.get(key);
        if (state != null) { close(state); state.version = SnapshotTokens.next(); state.chunk = null; }
    }
    private void close(State<T> state) {
        if (state.lease != null) state.lease.close();
        state.lease = null; state.tail = null; state.active = false; state.draining = false;
        state.completedVersion=0;
    }
    public synchronized void clear() {
        states.values().forEach(this::close); states.clear(); budget.reset();
    }
    public synchronized int activeCount() { return budget.jobs(); }
    public synchronized long reservedBytes() { return budget.bytes(); }
    /** On-demand primitive-only diagnostics, bounded by the active work budget. */
    public record Active(long key,long request,long version,boolean bound,boolean draining,
                         int packets,int bytes,boolean overflow) {}
    public synchronized List<Active> activeStates() {
        var result=new java.util.ArrayList<Active>();
        states.forEach((key,state)->{
            if(state.active) result.add(new Active(key,state.request,state.version,state.chunk!=null,
                    state.draining,state.tail.size(),state.tail.bytes(),state.tail.overflowed()));
        });
        return List.copyOf(result);
    }
}
