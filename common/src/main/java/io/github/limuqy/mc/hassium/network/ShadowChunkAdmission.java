package io.github.limuqy.mc.hassium.network;

import java.util.HashMap;
import java.util.Map;

/** Single-admission guard shared by pull and legacy fallback during migration. */
public final class ShadowChunkAdmission {
    public enum Path {
        PULL,
        LEGACY_FALLBACK
    }

    public enum Decision {
        ADMITTED,
        DUPLICATE,
        STALE
    }

    public record Key(String dimension, int chunkX, int chunkZ, long epoch) {}

    private final Map<Key, Path> active = new HashMap<>();
    private long epoch;

    public synchronized void advanceEpoch(long epoch) {
        if (epoch > this.epoch) {
            this.epoch = epoch;
            active.keySet().removeIf(key -> key.epoch() != epoch);
        }
    }

    public synchronized Decision begin(Key key, Path path) {
        if (key == null || path == null || key.epoch() != epoch) {
            return Decision.STALE;
        }
        if (active.putIfAbsent(key, path) != null) {
            return Decision.DUPLICATE;
        }
        return Decision.ADMITTED;
    }

    public synchronized boolean finish(Key key) {
        return active.remove(key) != null;
    }

    public synchronized boolean isActive(Key key) {
        return active.containsKey(key);
    }

    public synchronized int activeCount() {
        return active.size();
    }
}
