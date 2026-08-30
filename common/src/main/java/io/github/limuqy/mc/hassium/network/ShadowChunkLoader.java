package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.network.seedgen.ShadowChunkSource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Client-side desired-set state machine for the shadow chunk loader.
 *
 * <p>The class owns only admission state. It does not decode packets, touch
 * Minecraft objects, or publish candidates to the real client. Callers must
 * complete a {@link LoadTicket} before a candidate becomes {@link State#READY}.</p>
 */
public final class ShadowChunkLoader {
    public static final int DEFAULT_MAX_IN_FLIGHT = 64;
    public static final int MAX_WORKSET = 16_384;

    public enum State {
        DESIRED,
        LOADING,
        READY,
        UNLOAD_PENDING,
        CANCELLED
    }

    public enum Completion {
        READY,
        RETRY,
        STALE,
        DUPLICATE
    }

    public record ChunkKey(String dimension, int chunkX, int chunkZ) {
        public ChunkKey {
            if (dimension == null || dimension.isBlank()) {
                throw new IllegalArgumentException("dimension must not be blank");
            }
        }
    }

    public record LoadTicket(ChunkKey key, long epoch, long generation,
                             ShadowChunkRole role, ShadowChunkSource source) {}

    private static final class Entry {
        private ShadowChunkRole role;
        private State state;
        private long generation;

        private Entry(ShadowChunkRole role) {
            this.role = role;
            this.state = State.DESIRED;
            this.generation = 1L;
        }
    }

    private final int maxInFlight;
    private final Map<ChunkKey, Entry> entries = new LinkedHashMap<>();
    private long epoch;
    private int inFlight;
    private String dimension;
    private int centerX;
    private int centerZ;

    public ShadowChunkLoader() {
        this(DEFAULT_MAX_IN_FLIGHT);
    }

    public ShadowChunkLoader(int maxInFlight) {
        if (maxInFlight <= 0) {
            throw new IllegalArgumentException("maxInFlight must be positive");
        }
        this.maxInFlight = maxInFlight;
    }

    /**
     * Starts a new desired set. A movement, teleport, dimension change,
     * reconnect, or migration must call this method; every previous ticket is
     * thereby stale without interrupting an already-written network frame.
     */
    public synchronized long updateView(String dimension, int centerX, int centerZ, int visibleRadius) {
        if (dimension == null || dimension.isBlank()) {
            throw new IllegalArgumentException("dimension must not be blank");
        }
        if (visibleRadius < 0) {
            throw new IllegalArgumentException("visibleRadius must not be negative");
        }
        long side = (long) visibleRadius * 2L + 3L;
        if (side * side > MAX_WORKSET) {
            throw new IllegalArgumentException("desired workset exceeds limit");
        }
        this.epoch++;
        this.dimension = dimension;
        this.centerX = centerX;
        this.centerZ = centerZ;

        // Keep completed overlap chunks. Rebuilding the whole workset on every
        // chunk boundary starves the newly entered edge while the player moves.
        Map<ChunkKey, Entry> previous = new LinkedHashMap<>(entries);
        entries.clear();
        this.inFlight = 0;
        int haloRadius = visibleRadius + 1;
        for (int x = centerX - haloRadius; x <= centerX + haloRadius; x++) {
            for (int z = centerZ - haloRadius; z <= centerZ + haloRadius; z++) {
                int distance = Math.max(Math.abs(x - centerX), Math.abs(z - centerZ));
                ShadowChunkRole role = distance <= visibleRadius
                        ? ShadowChunkRole.VISIBLE : ShadowChunkRole.HALO;
                ChunkKey key = new ChunkKey(dimension, x, z);
                Entry old = previous.get(key);
                if (old != null && old.state == State.READY) {
                    old.role = role;
                    entries.put(key, old);
                } else {
                    Entry next = new Entry(role);
                    if (old != null) {
                        next.generation = old.generation + 1L;
                    }
                    entries.put(key, next);
                }
            }
        }
        return epoch;
    }

    /**
     * 丢弃断线会话的所有 desired/loading 状态，并推进 epoch 使旧响应不可恢复旧状态。
     */
    public synchronized void reset() {
        epoch++;
        entries.clear();
        inFlight = 0;
        dimension = null;
        centerX = 0;
        centerZ = 0;
    }

    public synchronized long epoch() {
        return epoch;
    }

    public synchronized String dimension() {
        return dimension;
    }

    public synchronized int centerX() {
        return centerX;
    }

    public synchronized int centerZ() {
        return centerZ;
    }

    public synchronized Set<ChunkKey> desired() {
        return Set.copyOf(entries.keySet());
    }

    public synchronized Optional<State> state(ChunkKey key) {
        Entry entry = entries.get(key);
        return entry == null ? Optional.empty() : Optional.of(entry.state);
    }

    public synchronized int inFlight() {
        return inFlight;
    }

    /** Starts one bounded load and chooses cache, SeedGen, or remote full. */
    public synchronized Optional<LoadTicket> beginLoad(ChunkKey key,
                                                        boolean cacheValid,
                                                        boolean seedgenAllowed,
                                                        boolean pristine) {
        if (inFlight >= maxInFlight) {
            return Optional.empty();
        }
        Entry entry = entries.get(key);
        if (entry == null || entry.state != State.DESIRED) {
            return Optional.empty();
        }
        ShadowChunkSource source = chooseSource(cacheValid, seedgenAllowed, pristine);
        entry.state = State.LOADING;
        inFlight++;
        return Optional.of(new LoadTicket(key, epoch, entry.generation, entry.role, source));
    }
    public synchronized Optional<ShadowChunkRole> role(ChunkKey key) {
        Entry entry = entries.get(key);
        return entry == null ? Optional.empty() : Optional.of(entry.role);
    }


    /** Completes only the current epoch/generation; stale results never publish. */
    public synchronized Completion complete(LoadTicket ticket, boolean success) {
        if (ticket == null || ticket.epoch() != epoch) {
            return Completion.STALE;
        }
        Entry entry = entries.get(ticket.key());
        if (entry == null || entry.generation != ticket.generation()
                || entry.state != State.LOADING) {
            return Completion.DUPLICATE;
        }
        inFlight--;
        entry.state = success ? State.READY : State.DESIRED;
        return success ? Completion.READY : Completion.RETRY;
    }

    /** Cancels an in-flight or desired load without touching another key. */
    public synchronized boolean cancel(ChunkKey key) {
        Entry entry = entries.get(key);
        if (entry == null || entry.state == State.CANCELLED
                || entry.state == State.UNLOAD_PENDING) {
            return false;
        }
        if (entry.state == State.LOADING) {
            inFlight--;
        }
        entry.state = State.CANCELLED;
        entry.generation++;
        return true;
    }

    /** Moves a key out of the desired workset; completion is no longer publishable. */
    public synchronized boolean requestUnload(ChunkKey key) {
        Entry entry = entries.get(key);
        if (entry == null || entry.state == State.UNLOAD_PENDING) {
            return false;
        }
        if (entry.state == State.LOADING) {
            inFlight--;
        }
        entry.state = State.UNLOAD_PENDING;
        entry.generation++;
        return true;
    }

    /** Finalizes the unload after the shadow-side chunk/light cancellation runs. */
    public synchronized boolean finishUnload(ChunkKey key) {
        Entry entry = entries.get(key);
        if (entry == null || entry.state != State.UNLOAD_PENDING) {
            return false;
        }
        entries.remove(key);
        return true;
    }

    public static ShadowChunkSource chooseSource(boolean cacheValid,
                                                 boolean seedgenAllowed,
                                                 boolean pristine) {
        if (cacheValid) {
            return ShadowChunkSource.CACHE_SNAPSHOT;
        }
        if (seedgenAllowed && pristine) {
            return ShadowChunkSource.SEEDGEN;
        }
        return ShadowChunkSource.REMOTE_FULL;
    }
}
