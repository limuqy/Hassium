package io.github.limuqy.mc.hassium.server;

import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntToLongFunction;

/** Bounded section hashes. Dirty columns are never observable as authoritative cache hits. */
final class SectionHashCache {
    private record State(long[] hashes, BitSet dirty, int sectionCount) {}
    private final Map<Long, State> entries;

    SectionHashCache(int capacity) {
        entries = new LinkedHashMap<>(1024, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Long, State> eldest) {
                return size() > capacity;
            }
        };
    }

    synchronized long[] get(long key) {
        State state = entries.get(key);
        return state == null || !state.dirty().isEmpty() ? null : state.hashes().clone();
    }

    synchronized void put(long key, long[] hashes) {
        entries.put(key, new State(hashes.clone(), new BitSet(), -1));
    }

    synchronized boolean invalidate(long key) { return entries.remove(key) != null; }

    synchronized boolean invalidateSection(long key, int sectionIndex) {
        State state = entries.get(key);
        if (state == null) return false;
        // Unknown/out-of-range coordinates must fail closed to a complete cache miss.
        if (sectionIndex < 0 || sectionIndex >= 4096
                || state.sectionCount() >= 0 && sectionIndex >= state.sectionCount()) {
            entries.remove(key);
        } else {
            BitSet dirty = (BitSet) state.dirty().clone();
            dirty.set(sectionIndex);
            entries.put(key, new State(state.hashes(), dirty, state.sectionCount()));
        }
        return true;
    }

    /** Owner-thread only: hash just dirty sections; computation deliberately holds no map lock. */
    long[] resolve(long key, int sectionCount, IntToLongFunction hashSection) {
        State captured;
        synchronized (this) {
            captured = entries.get(key);
            if (captured == null || captured.sectionCount() >= 0 && captured.sectionCount() != sectionCount
                    || captured != null && (captured.hashes().length > sectionCount
                    || captured.dirty().length() > sectionCount)) {
                BitSet all = new BitSet(sectionCount);
                all.set(0, sectionCount);
                captured = new State(new long[0], all, sectionCount);
                entries.put(key, captured);
            }
            if (captured.dirty().isEmpty()) return captured.hashes().clone();
        }
        long[] hashes = Arrays.copyOf(captured.hashes(), sectionCount);
        for (int i = captured.dirty().nextSetBit(0); i >= 0 && i < sectionCount;
                i = captured.dirty().nextSetBit(i + 1)) {
            hashes[i] = hashSection.applyAsLong(i);
        }
        int length = hashes.length;
        while (length > 0 && hashes[length - 1] == 0L) length--;
        hashes = Arrays.copyOf(hashes, length);
        synchronized (this) {
            // An invalidation/clear during capture must never resurrect or publish an old hash.
            if (entries.get(key) != captured) {
                throw new IllegalStateException("Column changed during owner-thread hash capture");
            }
            entries.put(key, new State(hashes, new BitSet(), sectionCount));
        }
        return hashes.clone();
    }

    synchronized int size() { return entries.size(); }
    synchronized void clear() { entries.clear(); }
}
