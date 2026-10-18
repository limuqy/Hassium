package io.github.limuqy.mc.hassium.protocol;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Idempotency belongs to a connection lifetime, even when the same UUID reconnects with request id zero. */
public final class ShadowPullSessions {
    private record Session(Object connection, ShadowPullHandler handler) {}
    private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();

    public ShadowPullHandler handler(UUID playerId, Object connection) {
        java.util.Objects.requireNonNull(playerId);
        java.util.Objects.requireNonNull(connection);
        return sessions.compute(playerId, (id, old) -> old != null && old.connection() == connection
                ? old : new Session(connection, new ShadowPullHandler(new ShadowPullRequestLedger()))).handler();
    }

    public boolean matches(UUID playerId, Object connection) {
        Session session = sessions.get(playerId);
        return session != null && session.connection() == connection;
    }

    public void remove(UUID playerId) { sessions.remove(playerId); }
    public void clear() { sessions.clear(); }
}
