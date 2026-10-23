package io.github.limuqy.mc.hassium.server;

import io.github.limuqy.mc.hassium.compat.PacketCodecCompat;
import io.github.limuqy.mc.hassium.compat.PlayerCompat;
import io.github.limuqy.mc.hassium.compat.PullUpdatePacketCompat;
import io.github.limuqy.mc.hassium.protocol.ColumnSnapshotFence;
import io.github.limuqy.mc.hassium.protocol.sleep.SleepUpdateBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;

/** Owner-thread capture-to-send tail. Original updates remain live; duplicates after FULL restore order. */
public final class PullSnapshotTailServer {
    private static final Map<Connection, Map<Long, Token>> connections = new ConcurrentHashMap<>();
    public static final class Token {
        final Connection connection;
        final ServerPlayer player;
        final Object level;
        final long column, request;
        final SleepUpdateBuffer<PacketCodecCompat.CapturedPacket> tail =
                new SleepUpdateBuffer<>(ColumnSnapshotFence.MAX_PACKETS, ColumnSnapshotFence.MAX_BYTES);
        Token(ServerPlayer player, int x, int z, long request) {
            this.player = player; connection = PlayerCompat.getConnection(player);
            level = PlayerCompat.getServerLevel(player); column = net.minecraft.world.level.ChunkPos.asLong(x, z);
            this.request = request;
        }
    }
    private PullSnapshotTailServer() {}
    public static Token begin(ServerPlayer player, int x, int z, long request) {
        Token token = new Token(player, x, z, request);
        if (token.connection == null) return null;
        boolean[] admitted = {false};
        connections.compute(token.connection, (connection, columns) -> {
            if (columns == null) columns = new ConcurrentHashMap<>();
            Token old = columns.get(token.column);
            if (old == null || old.request < request) { columns.put(token.column, token); admitted[0] = true; }
            return columns;
        });
        return admitted[0] ? token : null;
    }
    public static boolean current(Token token) {
        if (token == null) return false;
        var columns = connections.get(token.connection);
        return columns != null && columns.get(token.column) == token
                && token.level == PlayerCompat.getServerLevel(token.player);
    }
    /** Called only for native packets actually allowed through the existing sleep filter. */
    public static void observe(Connection connection, Packet<?> packet) {
        if (io.github.limuqy.mc.hassium.compat.PullOrderingProbeCompat.legacyServer()) return;
        var columns = connections.get(connection);
        if (columns == null || columns.isEmpty()) return;
        var children = PullUpdatePacketCompat.children(packet);
        if (children != null) { for (var child : children) observe(connection, child); return; }
        var pos = PullUpdatePacketCompat.column(packet);
        if (pos == null) return;
        Token token = columns.get(pos.toLong());
        if (token == null) return;
        synchronized (token) {
            if (token.tail.overflowed()) return;
            var owner = PlayerCompat.getMinecraftServer(token.player);
            if (owner == null || !owner.isSameThread() || token.level != PlayerCompat.getServerLevel(token.player)) {
                token.tail.overflow(); return;
            }
            try {
                var captured = PacketCodecCompat.captureClientbound(packet,
                        PlayerCompat.getServerLevel(token.player).registryAccess());
                token.tail.add(captured, captured.body().length);
            } catch (Throwable failure) { token.tail.overflow(); }
        }
    }
    public static boolean overflowed(Token token) {
        synchronized (token) { return token.tail.overflowed(); }
    }
    public static List<PacketCodecCompat.CapturedPacket> finish(Token token) {
        if (!current(token)) return null;
        synchronized (token) {
            if (token.tail.overflowed()) return null;
            List<PacketCodecCompat.CapturedPacket> tail = token.tail.values(); discard(token); return tail;
        }
    }
    public static void discard(Token token) {
        if (token == null) return;
        connections.computeIfPresent(token.connection, (connection, columns) -> {
            columns.remove(token.column, token);
            return columns.isEmpty() ? null : columns;
        });
    }
    public static void removePlayer(java.util.UUID player) {
        connections.values().forEach(columns -> columns.values().removeIf(token -> token.player.getUUID().equals(player)));
        connections.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }
    public static void disconnected(Connection connection) { connections.remove(connection); }
    public static void clear() { connections.clear(); }
    public static int activeCount() { return connections.values().stream().mapToInt(Map::size).sum(); }
}
