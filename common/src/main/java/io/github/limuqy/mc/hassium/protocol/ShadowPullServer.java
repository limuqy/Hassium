package io.github.limuqy.mc.hassium.protocol;

import io.github.limuqy.mc.hassium.compat.LevelCompat;
import io.github.limuqy.mc.hassium.compat.PlayerCompat;
import io.github.limuqy.mc.hassium.server.ServerChunkPushManager;
import io.github.limuqy.mc.hassium.server.ServerNetworkGate;
import net.minecraft.server.level.ServerPlayer;
import java.util.UUID;

/** All loaders share this admission boundary; ledger lifetime is one real connection. */
public final class ShadowPullServer {
    private ShadowPullServer() {}
    // Wire v1 clients use epoch zero. The connection identity, not a client-supplied epoch, owns a session.
    public static final long WIRE_EPOCH = 0L;
    private static final ShadowPullSessions SESSIONS = new ShadowPullSessions();

    public static boolean isAuthorized(ServerPlayer player) {
        if (!ServerNetworkGate.shouldTakeOverChunks(player) || player.connection == null
                || !player.isAlive() || player.isRemoved() || player.hasDisconnected()) return false;
        var connection = PlayerCompat.getConnection(player);
        return connection != null && connection.isConnected();
    }

    public static boolean isCurrentSession(ServerPlayer player, Object connection) {
        if (player == null || player.connection != connection) return false;
        return SESSIONS.matches(player.getUUID(), connection) && isAuthorized(player);
    }

    public static void removePlayer(UUID playerId) { SESSIONS.remove(playerId); }
    public static void clear() { SESSIONS.clear(); }

    /** Called on the owning real server thread; invalid requests never create a session or touch chunks. */
    public static ShadowPullResponseS2CPacket handleRequest(ServerPlayer player,
                                                            ShadowPullRequestC2SPacket request) {
        if (!isAuthorized(player)) {
            // No world, position, chunk source or session access on the rejected path.
            return new ShadowPullHandler(new ShadowPullRequestLedger()).handle(
                    player == null ? null : player.getUUID(), request, null, WIRE_EPOCH,
                    0, 0, 0, false, false, (req, entry) -> null);
        }
        boolean newConnection = !SESSIONS.matches(player.getUUID(), player.connection);
        if (newConnection) ServerChunkPushManager.getInstance().removePlayer(player.getUUID());
        ShadowPullHandler handler = SESSIONS.handler(player.getUUID(), player.connection);
        String dimension = LevelCompat.getDimensionId(player.level());
        return handler.handle(player.getUUID(), request, dimension, WIRE_EPOCH,
                player.chunkPosition().x, player.chunkPosition().z,
                PlayerCompat.getViewDistance(player) + ShadowPullRadii.AUTHORITY_MARGIN,
                true, true,
                (req, entry) -> ServerChunkPushManager.getInstance().resolveShadowPull(player, req, entry, dimension));
    }
}
