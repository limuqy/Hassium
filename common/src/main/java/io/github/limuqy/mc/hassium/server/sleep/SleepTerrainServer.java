package io.github.limuqy.mc.hassium.server.sleep;

import io.github.limuqy.mc.hassium.compat.*;
import io.github.limuqy.mc.hassium.platform.Services;
import io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps;
import io.github.limuqy.mc.hassium.protocol.sleep.*;
import io.github.limuqy.mc.hassium.server.ServerHandshakeActivation;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Main-thread snapshot fence. No updates are retained during the AFK period. */
public final class SleepTerrainServer {
    private static final int MAX_PACKETS_PER_COLUMN=512, MAX_PACKETS=8192;
    private static final int MAX_BYTES=8*1024*1024;
    private static final class Pending {
        final long request;
        final SleepUpdateBuffer<PacketCodecCompat.CapturedPacket> updates=new SleepUpdateBuffer<>(MAX_PACKETS_PER_COLUMN,256*1024);
        Pending(long request) { this.request=request; }
    }
    private static final class Window {
        final ServerPlayer player;
        final Object level;
        final long generation;
        final SleepChunkWindow area;
        final int radius;
        final Set<Long> ready=new HashSet<>();
        final Set<Long> requested=new HashSet<>();
        final Map<Long,Pending> pending=new HashMap<>();
        volatile boolean sleeping=true;
        int packets,bytes;
        long sequence,received;
        Window(ServerPlayer p,long generation) {
            player=p; level=PlayerCompat.getServerLevel(p); this.generation=generation;
            area=new SleepChunkWindow(p.chunkPosition().x,p.chunkPosition().z);
            radius=PlayerCompat.getViewDistance(p)+2;
        }
        boolean dirty(int x,int z) {
            return (requested.contains(ChunkPos.asLong(x,z)) || !area.light(x,z)
                    && ChunkShapeCompat.containsDilated(area.x(),area.z(),radius-2,1,x,z))
                    && !ready.contains(ChunkPos.asLong(x,z));
        }
    }
    private static final Map<Connection,Window> windows=new ConcurrentHashMap<>();
    private SleepTerrainServer() {}
    private static Window current(ServerPlayer player) {
        Connection connection=PlayerCompat.getConnection(player);
        Window w=connection==null?null:windows.get(connection);
        if(w!=null && w.level!=PlayerCompat.getServerLevel(player)) { windows.remove(connection,w); return null; }
        return w;
    }
    public static void enter(ServerPlayer player,long generation) {
        if(ServerHandshakeActivation.hasCaps(player.getUUID(),LoginCaps.SLEEP)
                && io.github.limuqy.mc.hassium.utils.DimensionKey.isCacheableDimension(LevelCompat.getDimensionId(PlayerCompat.getServerLevel(player))))
            windows.put(PlayerCompat.getConnection(player),new Window(player,generation));
    }
    public static void wake(ServerPlayer player) { Window w=windows.get(PlayerCompat.getConnection(player)); if(w!=null) w.sleeping=false; }
    public static String description(ServerPlayer player) {
        Window w=windows.get(PlayerCompat.getConnection(player));
        return w==null?"":"window,"+w.area.x()+","+w.area.z();
    }
    public static boolean allows(ServerPlayer player,int x,int z) {
        Connection connection=PlayerCompat.getConnection(player);
        Window w=connection==null?null:windows.get(connection); return w==null || !w.sleeping || w.area.light(x,z);
    }
    /** Native dynamic light for the nine live columns; the client no longer ticks halo blocks. */
    public static boolean needsNativeLight(ServerPlayer player,int x,int z) {
        Window w=current(player);
        return w!=null && w.sleeping && w.area.visible(x,z);
    }
    /** Called in the same server tick that captured the current snapshot, before any later mutation. */
    public static void snapshot(ServerPlayer player,int x,int z,long request) {
        Window w=current(player);
        if(w==null || w.sleeping || !w.dirty(x,z)) return;
        Pending existing=w.pending.get(ChunkPos.asLong(x,z));
        if(existing!=null && existing.request>=request) return;
        Pending old=w.pending.put(ChunkPos.asLong(x,z),new Pending(request));
        if(old!=null) { w.packets-=old.updates.size(); w.bytes-=old.updates.bytes(); }
        SleepProbeCompat.afterTerrainSnapshot(player,x,z);
    }
    public static boolean responseAllowed(ServerPlayer player,int x,int z,long request) {
        Window w=current(player);
        if(w==null) return true;
        if(w.sleeping) return w.area.light(x,z);
        if(!w.dirty(x,z)) return true;
        Pending p=w.pending.get(ChunkPos.asLong(x,z)); return p!=null && p.request==request;
    }
    public static boolean suppress(Connection connection,Packet<?> packet) {
        Window w=windows.get(connection);
        if(w==null) return false;
        if(w.level!=PlayerCompat.getServerLevel(w.player)) { windows.remove(connection,w); return false; }
        var children=SleepPacketCompat.children(packet);
        if(children!=null) {
            List<Packet<?>> kept=new ArrayList<>(); boolean changed=false;
            for(Packet<?> child:children) { if(suppress(connection,child)) changed=true; else kept.add(child); }
            if(changed && !kept.isEmpty()) SleepServer.emit(connection,SleepPacketCompat.bundle(kept));
            return changed;
        }
        var pos=SleepPacketCompat.column(packet);
        if(pos==null) return false;
        if(w.sleeping) return !w.area.light(pos.x,pos.z);
        if(!w.dirty(pos.x,pos.z)) return false;
        Pending p=w.pending.get(pos.toLong());
        if(p!=null && !p.updates.overflowed()) {
            w.packets-=p.updates.size(); w.bytes-=p.updates.bytes();
            try {
                var captured=PacketCodecCompat.captureClientbound(packet,PlayerCompat.getServerLevel(w.player).registryAccess());
                if(w.packets+p.updates.size()>=MAX_PACKETS || captured.body().length>MAX_BYTES-w.bytes-p.updates.bytes()) p.updates.overflow();
                else p.updates.add(captured,captured.body().length);
            } catch(RuntimeException oversized) { p.updates.overflow(); }
            w.packets+=p.updates.size(); w.bytes+=p.updates.bytes();
        }
        return true;
    }
    public static void applied(ServerPlayer player,SleepControl.Message m) {
        Connection connection=PlayerCompat.getConnection(player); Window w=current(player);
        if(w==null || w.player!=player || m.generation()!=w.generation || w.sleeping || m.sequence()<=w.received) return;
        w.received=m.sequence();
        String[] parts=m.detail().split(","); if(parts.length!=3) return;
        try {
            int x=Integer.parseInt(parts[0]),z=Integer.parseInt(parts[1]); long request=Long.parseLong(parts[2]);
            long key=ChunkPos.asLong(x,z);
            if(m.op()==SleepControl.Op.COLUMN_REQUEST) {
                var center=player.chunkPosition(); int limit=PlayerCompat.getViewDistance(player)+2;
                if(Math.abs((long)x-center.x)>limit || Math.abs((long)z-center.z)>limit) return;
                if(w.requested.size()>=8192 && !w.requested.contains(key)) return;
                w.requested.add(key); w.ready.remove(key); return;
            }
            Pending p=w.pending.get(key);
            if(p==null || p.request!=request) return;
            if(io.github.limuqy.mc.hassium.server.ServerSmokeTest.isEnabled() && x==3 && z==0)
                io.github.limuqy.mc.hassium.Constants.LOG.info("HassiumSmokeTest:SLEEP snapshot tail packets={} bytes={} retry={}",p.updates.size(),p.updates.bytes(),p.updates.overflowed());
            w.pending.remove(key); w.packets-=p.updates.size(); w.bytes-=p.updates.bytes();
            if(!p.updates.overflowed()) {
                w.requested.remove(key);
                if(Math.abs((long)x-w.area.x())<=w.radius && Math.abs((long)z-w.area.z())<=w.radius) w.ready.add(key);
                for(var packet:p.updates.values()) SleepServer.emit(connection,packet.restore(PlayerCompat.getServerLevel(player).registryAccess()));
            }
            // On overflow request another current snapshot; never replay a truncated history.
            Services.NETWORK_MANAGER.sendSleepControl(player,SleepControl.encode(new SleepControl.Message(
                    p.updates.overflowed()?SleepControl.Op.COLUMN_RETRY:SleepControl.Op.COLUMN_READY,w.generation,++w.sequence,m.detail())));
        } catch(NumberFormatException ignored) { }
    }
    public static void disconnected(Connection connection) { windows.remove(connection); }
    public static void tick(net.minecraft.server.MinecraftServer server) {
        for(Window w:windows.values()) {
            if(w.sleeping || PlayerCompat.getMinecraftServer(w.player)!=server) continue;
            var center=w.player.chunkPosition(); int limit=PlayerCompat.getViewDistance(w.player)+2;
            w.requested.removeIf(key->{
                var pos=new ChunkPos(key);
                if(Math.abs((long)pos.x-center.x)<=limit && Math.abs((long)pos.z-center.z)<=limit) return false;
                Pending p=w.pending.remove(key);
                if(p!=null) { w.packets-=p.updates.size(); w.bytes-=p.updates.bytes(); }
                return true;
            });
        }
    }
    public static void clear() { windows.clear(); }
}
