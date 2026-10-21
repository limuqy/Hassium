package io.github.limuqy.mc.hassium.compat;

import io.github.limuqy.mc.hassium.mixin.server.SleepChunkMapAccess;
import io.github.limuqy.mc.hassium.mixin.server.SleepDistanceManagerAccess;
import io.github.limuqy.mc.hassium.mixin.server.SleepTickingTrackerAccess;
import io.github.limuqy.mc.hassium.platform.Services;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import java.util.*;

/** Exact loading/ticking graph inputs, independent of connection cleanup. Server thread only. */
public final class SleepLeaseCompat {
    public record HeldChunk(ChunkPos pos, int loadLevel, int tickLevel, boolean randomTickActive) {}
#if MC_VER < MC_1_21_2
    public static final net.minecraft.server.level.TicketType<UUID> LOAD = net.minecraft.server.level.TicketType.create("hassium_sleep_load", UUID::compareTo);
    public static final net.minecraft.server.level.TicketType<UUID> TICK = net.minecraft.server.level.TicketType.create("hassium_sleep_tick", UUID::compareTo);
    public static final net.minecraft.server.level.TicketType<UUID> FORCE = net.minecraft.server.level.TicketType.create("hassium_sleep_force", UUID::compareTo);
#endif
    private SleepLeaseCompat() {}
    public static boolean supported() {
#if MC_VER < MC_1_21_2
        String platform = Services.PLATFORM.getPlatformName();
#if MC_VER < MC_1_21_1
        return platform.equals("Forge");
#else
        return platform.equals("NeoForge");
#endif
#else
        return false;
#endif
    }
    public static List<HeldChunk> capture(ServerPlayer player) {
#if MC_VER < MC_1_21_2
        ServerLevel level = PlayerCompat.getServerLevel(player);
        var map = (SleepChunkMapAccess) level.getChunkSource().chunkMap;
        var ticking = ((SleepDistanceManagerAccess)map.hassium$sleepDistanceManager()).hassium$sleepTickingTracker();
        int radius = Math.max(PlayerCompat.getViewDistance(player) + 2,
                PlayerCompat.getMinecraftServer(player).getPlayerList().getSimulationDistance() + 2);
        List<HeldChunk> result = new ArrayList<>();
        for (var holder : map.hassium$sleepChunks()) {
            ChunkPos pos = holder.getPos();
            if (Math.max(Math.abs(pos.x-player.chunkPosition().x), Math.abs(pos.z-player.chunkPosition().z)) <= radius
                    && holder.getTicketLevel() <= 33 && level.getChunkSource().getChunkNow(pos.x,pos.z) != null) {
                result.add(new HeldChunk(pos, holder.getTicketLevel(), ticking.getLevel(pos),
                        level.isNaturalSpawningAllowed(pos) && map.hassium$sleepActiveChunk(pos)));
            }
        }
        if (result.isEmpty() || result.size() > 8192) throw new IllegalStateException("Invalid or excessive reconnect hold area");
        return List.copyOf(result);
#else
        throw new UnsupportedOperationException("Sleep leases require a validated anchor");
#endif
    }
    public static void install(ServerLevel level, UUID id, List<HeldChunk> chunks) {
#if MC_VER < MC_1_21_2
        var dm = ((SleepChunkMapAccess)level.getChunkSource().chunkMap).hassium$sleepDistanceManager();
        var ticking = ((SleepDistanceManagerAccess)dm).hassium$sleepTickingTracker();
        try {
            for (HeldChunk c : chunks) {
                dm.addTicket(LOAD, c.pos(), c.loadLevel(), id);
                if (c.tickLevel() <= 32) {
                    ticking.addTicket(TICK, c.pos(), c.tickLevel(), id);
                    if(c.randomTickActive()) Services.PLATFORM.setSleepForceTick(level, c.pos(), id, true);
                }
            }
            dm.runAllUpdates(level.getChunkSource().chunkMap);
            for (HeldChunk c : chunks) {
                if (level.getChunkSource().getChunkNow(c.pos().x, c.pos().z) == null || ticking.getLevel(c.pos()) > c.tickLevel())
                    throw new IllegalStateException("Reconnect hold did not become ready");
            }
        } catch (RuntimeException error) { release(level, id, chunks); throw error; }
#else
        throw new UnsupportedOperationException("Sleep leases require a validated anchor");
#endif
    }
    public static void release(ServerLevel level, UUID id, List<HeldChunk> chunks) {
#if MC_VER < MC_1_21_2
        var dm = ((SleepChunkMapAccess)level.getChunkSource().chunkMap).hassium$sleepDistanceManager();
        var ticking = ((SleepDistanceManagerAccess)dm).hassium$sleepTickingTracker();
        for (HeldChunk c : chunks) {
            dm.removeTicket(LOAD, c.pos(), c.loadLevel(), id);
            if (c.tickLevel() <= 32) {
                ticking.removeTicket(TICK, c.pos(), c.tickLevel(), id);
                if(c.randomTickActive()) Services.PLATFORM.setSleepForceTick(level, c.pos(), id, false);
            }
        }
#endif
    }
    /** Check actual source tickets excluding ALL sleep leases; no circular readiness proof. */
    public static boolean nativeCoverage(ServerLevel level, List<HeldChunk> chunks) {
#if MC_VER < MC_1_21_2
        var dm = (SleepDistanceManagerAccess)((SleepChunkMapAccess)level.getChunkSource().chunkMap).hassium$sleepDistanceManager();
        // Loading PLAYER tickets are explicit per chunk. Simulation PLAYER tickets are graph sources.
        var loadSources = dm.hassium$sleepTickets();
        var tickSources = ((SleepTickingTrackerAccess)dm.hassium$sleepTickingTracker()).hassium$sleepTickTickets();
        for (HeldChunk c : chunks) {
            if (!covered(loadSources, c.pos(), c.loadLevel()) || (c.tickLevel() <= 32 && !covered(tickSources,c.pos(),c.tickLevel()))) return false;
        }
        return true;
#else
        return false;
#endif
    }
#if MC_VER < MC_1_21_2
    private static boolean covered(it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<net.minecraft.util.SortedArraySet<net.minecraft.server.level.Ticket<?>>> sources, ChunkPos target, int maximum) {
        var direct=sources.get(target.toLong());
        if(direct!=null) for(var ticket:direct) {
            var type=ticket.getType();
            if(type!=LOAD && type!=TICK && type!=FORCE && ticket.getTicketLevel()<=maximum) return true;
        }
        for (var entry : sources.long2ObjectEntrySet()) {
            if(entry.getLongKey()==target.toLong()) continue;
            ChunkPos source = new ChunkPos(entry.getLongKey());
            int distance = Math.max(Math.abs(source.x-target.x),Math.abs(source.z-target.z));
            for (var ticket : entry.getValue()) {
                var type = ticket.getType();
                if (type != LOAD && type != TICK && type != FORCE && ticket.getTicketLevel()+distance <= maximum) return true;
            }
        }
        return false;
    }
#endif
}
