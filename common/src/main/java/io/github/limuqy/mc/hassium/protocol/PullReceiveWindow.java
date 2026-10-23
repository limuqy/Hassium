package io.github.limuqy.mc.hassium.protocol;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lightweight unsent intents; only admitted columns may retain response payloads. */
final class PullReceiveWindow<T> {
    record Admission<T>(long key, long token, T value) {}
    private record Active<T>(Admission<T> admission, long requestId, long sentAt, boolean received) {}
    private final int capacity, deferredCapacity;
    private final LinkedHashMap<Long, T> deferred = new LinkedHashMap<>();
    private final Map<Long, Active<T>> active = new HashMap<>();
    private long sequence;
    PullReceiveWindow(int capacity, int deferredCapacity) {
        this.capacity = capacity; this.deferredCapacity = deferredCapacity;
    }
    synchronized boolean offer(long key, T value) {
        return offerStatus(key,value)!=Offer.FULL;
    }
    enum Offer { ADDED, EXISTING, FULL }
    synchronized Offer offerStatus(long key,T value) {
        if (active.containsKey(key) || deferred.containsKey(key)) return Offer.EXISTING;
        if (deferred.size() >= deferredCapacity) return Offer.FULL;
        deferred.put(key, value);
        return Offer.ADDED;
    }
    synchronized List<Admission<T>> drain() {
        List<Admission<T>> result = new ArrayList<>();
        var it = deferred.entrySet().iterator();
        while (active.size() < capacity && it.hasNext()) {
            var entry = it.next();
            var admitted = new Admission<T>(entry.getKey(), ++sequence, entry.getValue());
            active.put(entry.getKey(), new Active<>(admitted, -1, System.nanoTime(), false));
            result.add(admitted);
            it.remove();
        }
        return result;
    }
    synchronized boolean bind(long key, long token, long requestId) {
        return bind(key, token, requestId, System.nanoTime());
    }
    synchronized boolean bind(long key, long token, long requestId, long now) {
        Active<T> slot = active.get(key);
        if (slot == null || slot.admission.token() != token) return false;
        active.put(key, new Active<>(slot.admission, requestId, now, false));
        return true;
    }
    synchronized boolean matches(long key, long requestId) {
        Active<T> slot = active.get(key);
        return slot != null && slot.requestId == requestId;
    }
    synchronized boolean matchesAdmission(long key, long token) {
        Active<T> slot = active.get(key);
        return slot != null && slot.admission.token() == token;
    }
    synchronized void complete(long key, long requestId) {
        if (matches(key, requestId)) active.remove(key);
    }
    synchronized void received(long key, long requestId) {
        if (!matches(key, requestId)) return;
        var slot = active.get(key);
        active.put(key, new Active<>(slot.admission, requestId, slot.sentAt, true));
    }
    synchronized List<Admission<T>> expire(long now, long timeout) {
        List<Admission<T>> expired = new ArrayList<>();
        active.values().removeIf(slot -> {
            if (slot.received || now - slot.sentAt < timeout) return false;
            expired.add(slot.admission);
            return true;
        });
        return expired;
    }
    synchronized T cancel(long key) {
        T queued=deferred.remove(key); Active<T> removed=active.remove(key);
        return removed==null?queued:removed.admission.value();
    }
    /** Reject an unsent prepared intent without touching a replacement admission. */
    synchronized boolean discard(long key,long token) {
        if(!matchesAdmission(key,token)) return false;
        active.remove(key); return true;
    }
    synchronized boolean queued(long key) { return deferred.containsKey(key); }
    synchronized String diagnostics(long key) {
        Active<T> slot = active.get(key);
        return "queued=" + deferred.containsKey(key) + (slot == null ? " receive=none"
                : " request=" + slot.requestId + " received=" + slot.received
                    + " receiveAgeMs=" + (System.nanoTime() - slot.sentAt) / 1_000_000L);
    }
    synchronized List<Admission<T>> active() { return active.values().stream().map(Active::admission).toList(); }
    synchronized void clear() { deferred.clear(); active.clear(); }
}
