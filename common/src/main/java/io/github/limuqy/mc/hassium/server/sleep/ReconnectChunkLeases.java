package io.github.limuqy.mc.hassium.server.sleep;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.PlayerCompat;
import io.github.limuqy.mc.hassium.compat.SleepLeaseCompat;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** Only explicit wake requests create leases. Neither logout nor low health creates one. */
public final class ReconnectChunkLeases {
    public record Lease(UUID playerId, UUID id, long generation, float threshold, ServerLevel level,
                        Connection previous, List<SleepLeaseCompat.HeldChunk> chunks, long deadlineNanos) {}
    private static final Map<UUID, Lease> LEASES = new HashMap<>();
    private ReconnectChunkLeases() {}
    public static Lease get(UUID player) { return LEASES.get(player); }
    public static Lease acquire(ServerPlayer player, long generation, float threshold, int seconds) {
        Lease old = LEASES.get(player.getUUID());
        if (old != null) {
            if (old.generation() != generation) throw new IllegalStateException("Another reconnect hold is still active");
            return old;
        }
        var chunks = SleepLeaseCompat.capture(player);
        if (LEASES.values().stream().mapToInt(l -> l.chunks().size()).sum()+chunks.size() > 32768)
            throw new IllegalStateException("Reconnect chunk hold capacity reached");
        UUID id = UUID.randomUUID();
        ServerLevel level = PlayerCompat.getServerLevel(player);
        SleepLeaseCompat.install(level,id,chunks);
        Lease lease = new Lease(player.getUUID(),id,generation,threshold,level,PlayerCompat.getConnection(player),chunks,
                System.nanoTime()+Math.max(10,Math.min(3600,seconds))*1_000_000_000L);
        LEASES.put(player.getUUID(),lease);
        SleepRuntimeProbe.acquired(lease);
        Constants.LOG.info("[SLEEP_LEASE] acquired player={} generation={} chunks={} seconds={}",player.getUUID(),generation,chunks.size(),seconds);
        return lease;
    }
    public static void tick(MinecraftServer server) {
        long now = System.nanoTime();
        for (Lease lease : List.copyOf(LEASES.values())) {
            if (lease.level().getServer() != server) continue;
            lease.level().resetEmptyTime();
            SleepRuntimeProbe.tick(lease);
            if (now >= lease.deadlineNanos()) { release(lease,"expired"); continue; }
            ServerPlayer player = server.getPlayerList().getPlayer(lease.playerId());
            if (player != null && PlayerCompat.getConnection(player) != lease.previous()
                    && PlayerCompat.getServerLevel(player) == lease.level() && player.connection.isAcceptingMessages()
                    && SleepServer.recoveryAnnounced(player)
                    && server.getTickCount()%20 == 0 && SleepLeaseCompat.nativeCoverage(lease.level(),lease.chunks())) {
                release(lease,"native-ticket-handoff");
            }
        }
    }
    public static void release(Lease lease, String reason) {
        if (!LEASES.remove(lease.playerId(),lease)) return;
        SleepRuntimeProbe.released(lease,reason);
        SleepLeaseCompat.release(lease.level(),lease.id(),lease.chunks());
        Constants.LOG.info("[SLEEP_LEASE] released player={} generation={} reason={}",lease.playerId(),lease.generation(),reason);
    }
    public static void clear(MinecraftServer server) {
        for (Lease lease : List.copyOf(LEASES.values())) if (lease.level().getServer() == server) release(lease,"server-stop");
    }
    public static int activeCount() { return LEASES.size(); }
}
