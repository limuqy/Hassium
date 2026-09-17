package io.github.limuqy.mc.hassium.protocol;

import java.util.LinkedHashMap;
import java.util.UUID;

/**
 * Bounded idempotency ledger for shadowPullV1 requests.
 * The ledger is transport-neutral and must be scoped to one player session.
 */
public final class ShadowPullRequestLedger {
    public enum Decision {
        ACCEPT,
        DUPLICATE,
        STALE
    }

    private record Key(UUID playerId, long epoch, long requestId) {}

    private final int capacity;
    private final LinkedHashMap<Key, Long> accepted = new LinkedHashMap<>();
    private long currentEpoch;

    public ShadowPullRequestLedger() {
        this(256);
    }

    public ShadowPullRequestLedger(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public synchronized void advanceEpoch(long epoch) {
        if (epoch < currentEpoch) {
            return;
        }
        currentEpoch = epoch;
        accepted.entrySet().removeIf(entry -> entry.getKey().epoch() != epoch);
    }

    public synchronized Decision accept(UUID playerId, long epoch, long requestId, long requestFingerprint) {
        if (playerId == null || epoch < currentEpoch) {
            return Decision.STALE;
        }
        if (epoch > currentEpoch) {
            advanceEpoch(epoch);
        }
        Key key = new Key(playerId, epoch, requestId);
        Long previous = accepted.get(key);
        if (previous != null) {
            return previous == requestFingerprint ? Decision.DUPLICATE : Decision.STALE;
        }
        if (accepted.size() >= capacity) {
            accepted.remove(accepted.keySet().iterator().next());
        }
        accepted.put(key, requestFingerprint);
        return Decision.ACCEPT;
    }

    public synchronized int size() {
        return accepted.size();
    }
}
