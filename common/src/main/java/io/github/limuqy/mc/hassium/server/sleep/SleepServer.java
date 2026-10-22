package io.github.limuqy.mc.hassium.server.sleep;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.*;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.platform.Services;
import io.github.limuqy.mc.hassium.protocol.handshake.LoginCaps;
import io.github.limuqy.mc.hassium.protocol.sleep.SleepControl;
import io.github.limuqy.mc.hassium.protocol.sleep.SleepControl.*;
import io.github.limuqy.mc.hassium.server.ServerHandshakeActivation;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.core.BlockPos;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** World simulation is untouched. All interest/snapshot decisions are serialized on the real server. */
public final class SleepServer {
    private enum Phase { PREPARED, SLEEPING }
    private static final class Session {
        final ServerPlayer player; final Connection connection; final long generation;
        final net.minecraft.server.level.ServerLevel level;
        final Map<Integer,UUID> delivered = new HashMap<>();
        volatile Phase phase; long receivedSequence; long sentSequence; long started=System.nanoTime();
        Session(ServerPlayer p, long generation, Phase phase) {
            player=p; connection=PlayerCompat.getConnection(p); level=PlayerCompat.getServerLevel(p); this.generation=generation; this.phase=phase;
        }
    }
    private static final Map<Connection,Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<Connection,String> UNSAFE = new ConcurrentHashMap<>();
    private static final AtomicLong GENERATIONS = new AtomicLong(System.currentTimeMillis());
    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(()->false);
    private SleepServer() {}
    public static boolean bypass() { return BYPASS.get(); }
    public static void receive(ServerPlayer player, byte[] bytes) {
        if (player == null || !SleepSupportCompat.supported() || !ServerHandshakeActivation.hasCaps(player.getUUID(),LoginCaps.SLEEP)) return;
        Message m;
        try { m=SleepControl.decode(bytes); } catch (RuntimeException e) { player.connection.disconnect(Component.literal("Invalid Hassium sleep control")); return; }
        Connection connection=PlayerCompat.getConnection(player);
        if(m.op()==Op.COLUMN_APPLIED || m.op()==Op.COLUMN_REQUEST) { SleepTerrainServer.applied(player,m); return; }
        Session session=SESSIONS.get(connection);
        if (m.op()==Op.ENTER) {
            if (session!=null) return;
            String issue=Services.PLATFORM.sleepCompatibilityIssue();
            if (!HassiumConfigService.getInstance().getConfig().master().sleepEnabled() || issue!=null || UNSAFE.containsKey(connection)
                    || !player.isAlive() || player.isPassenger() || player.containerMenu!=player.inventoryMenu
                    || !io.github.limuqy.mc.hassium.utils.DimensionKey.isCacheableDimension(LevelCompat.getDimensionId(PlayerCompat.getServerLevel(player)))
                    || m.generation()!=0
                    || !Double.isFinite(SleepEntityCompat.interactionRadius(player)) || SleepEntityCompat.interactionRadius(player)>1024) {
                String reason=issue!=null ? issue : "Sleep unavailable: close containers and check compatibility";
                send(player,new Message(Op.ERROR,0,m.sequence(),reason)); return;
            }
            session=new Session(player,GENERATIONS.incrementAndGet(),Phase.PREPARED);
            session.receivedSequence=m.sequence(); SESSIONS.put(connection,session);
            send(session,Op.PREPARED,""); return;
        }
        if (session==null || session.player!=player || session.generation!=m.generation() || m.sequence()<=session.receivedSequence) return;
        session.receivedSequence=m.sequence();
        if (m.op()==Op.CONFIRM && session.phase==Phase.PREPARED) {
            // PREPARED still receives the normal world; recheck entry before restricting delivery.
            if (!player.isAlive() || player.isPassenger() || player.containerMenu!=player.inventoryMenu
                    || PlayerCompat.getServerLevel(player)!=session.level
                    || !HassiumConfigService.getInstance().getConfig().master().sleepEnabled()) {
                send(session,Op.ERROR,"Sleep entry rejected: player state changed before confirmation");
                SESSIONS.remove(connection,session);
                return;
            }
            for (Entity e : SleepEntityCompat.observed(player)) {
                if (relevant(player,e)) session.delivered.put(e.getId(),e.getUUID());
                else emit(connection,SleepPacketCompat.remove(e.getId()));
            }
            session.phase=Phase.SLEEPING;
            SleepTerrainServer.enter(player,session.generation);
            Constants.LOG.info("[SLEEP] entered player={} generation={}",player.getUUID(),session.generation);
            send(session,Op.SLEEPING,SleepTerrainServer.description(player));
        } else if (m.op()==Op.RESUME && session.phase==Phase.SLEEPING) {
            resume(session);
        }
    }
    private static void resume(Session s) {
        // One server-main-thread transaction: missing entities first, relationships second,
        // then normal updates. Existing nearby entities and the player's use/mining state stay live.
        List<Entity> observed=SleepEntityCompat.observed(s.player);
        Set<Integer> live=new HashSet<>();
        for(Entity entity:observed) live.add(entity.getId());
        for(int id:List.copyOf(s.delivered.keySet())) if(!live.contains(id)) emit(s.connection,SleepPacketCompat.remove(id));
        int restored=0;
        for(Entity entity:observed) {
            UUID delivered=s.delivered.get(entity.getId());
            if(!entity.getUUID().equals(delivered)) {
                if(delivered!=null) emit(s.connection,SleepPacketCompat.remove(entity.getId()));
                emitSeed(s,entity); restored++;
            }
        }
        for(Entity entity:observed) for(Packet<?> packet:SleepEntityCompat.relationships(entity)) emit(s.connection,packet);
        SESSIONS.remove(s.connection,s);
        SleepTerrainServer.wake(s.player);
        send(s,Op.ACTIVE,"");
        Constants.LOG.info("[SLEEP] resumed player={} generation={} restoredEntities={} usingItem={}",s.player.getUUID(),s.generation,restored,s.player.isUsingItem());
    }
    public static void tick(MinecraftServer server) {
        for (Session s : List.copyOf(SESSIONS.values())) {
            if (PlayerCompat.getMinecraftServer(s.player)!=server) continue;
            if (!s.connection.isConnected() || s.player.isRemoved()) { SESSIONS.remove(s.connection); continue; }
            if (PlayerCompat.getServerLevel(s.player)!=s.level
                    || !HassiumConfigService.getInstance().getConfig().master().sleepEnabled()
                    || !HassiumConfigService.getInstance().isMasterEnabled()) {
                incompatible(s,"Sleep ended: world or server configuration changed"); continue;
            }
            if (s.phase==Phase.PREPARED && System.nanoTime()-s.started>120_000_000_000L) {
                s.player.connection.disconnect(Component.literal("Hassium sleep handshake timed out")); continue;
            }
            if (s.phase==Phase.SLEEPING && s.player.isDeadOrDying()) { resume(s); continue; }
            if (s.phase==Phase.SLEEPING) {
                for (Entity entity : SleepEntityCompat.observed(s.player)) {
                    boolean keep=relevant(s.player,entity);
                    UUID delivered=s.delivered.get(entity.getId());
                    if (keep && !entity.getUUID().equals(delivered)) {
                        if (delivered!=null) emit(s.connection,SleepPacketCompat.remove(entity.getId()));
                        emitSeed(s,entity);
                    } else if (!keep && delivered!=null) {
                        s.delivered.remove(entity.getId()); emit(s.connection,SleepPacketCompat.remove(entity.getId()));
                    }
                }
            }
        }
        SleepTerrainServer.tick(server);
    }
    public static boolean relevant(ServerPlayer player, Entity entity) {
        if (entity==player || entity.getRootVehicle()==player.getRootVehicle()) return true;
        if (entity instanceof Projectile projectile && projectile.getOwner()==player) return true;
        var area=player.getBoundingBox().inflate(SleepEntityCompat.interactionRadius(player));
        if(entity.getBoundingBox().intersects(area)) return true;
        Entity root=entity.getRootVehicle();
        if(root.getBoundingBox().intersects(area)) return true;
        for(Entity passenger:root.getIndirectPassengers()) if(passenger.getBoundingBox().intersects(area)) return true;
        return false;
    }
    public static boolean allowsChunk(ServerPlayer player, int x, int z) { return SleepTerrainServer.allows(player,x,z); }
    /** Called after pressure counters and before Connection enqueues encoding. */
    public static boolean suppress(Connection connection, Packet<?> packet, boolean callback) {
        if (bypass()) return false;
        Session s=SESSIONS.get(connection);
        if (PacketPayloadCompat.isCustomPayloadPacket(packet)) {
            var id=PacketPayloadCompat.getPayloadId(packet);
            if (id!=null && !Set.of("hassium","minecraft","forge","neoforge").contains(id.namespace())) {
                UNSAFE.putIfAbsent(connection,id.fullId());
                if (s!=null && s.phase!=Phase.PREPARED) {
                    PlayerCompat.getMinecraftServer(s.player).execute(()->incompatible(s,"Unsupported world payload: "+id.fullId()));
                    return true;
                }
            }
        }
        if (s==null || callback || s.phase==Phase.PREPARED)
            return SleepTerrainServer.suppress(connection,packet);
        if (!PlayerCompat.getMinecraftServer(s.player).isSameThread()) {
            // A source outside the serialized world path needs an adapter, not reordered motion.
            if(SleepPacketCompat.worldUpdate(packet)) {
                PlayerCompat.getMinecraftServer(s.player).execute(()->incompatible(s,"Unsupported asynchronous world update"));
                return true;
            }
            return false;
        }
        Packet<?> result=filter(s,packet);
        if (result==packet) return false;
        if (result!=null) emit(connection,result);
        return true;
    }
    private static void incompatible(Session s,String reason) {
        if(SESSIONS.remove(s.connection,s)) {
            s.player.connection.disconnect(Component.literal(reason+"; reconnect manually"));
        }
    }
    public static boolean filtering(ServerPlayer player) {
        Session s=SESSIONS.get(PlayerCompat.getConnection(player)); return s!=null && s.phase==Phase.SLEEPING;
    }
    private static Packet<?> filter(Session s, Packet<?> packet) {
        if(PacketPayloadCompat.isCustomPayloadPacket(packet)) {
            var payload=PacketPayloadCompat.getPayloadId(packet);
            if(payload!=null && !Set.of("hassium","minecraft","forge","neoforge").contains(payload.namespace())) {
                UNSAFE.putIfAbsent(s.connection,payload.fullId()); incompatible(s,"Unsupported bundled payload: "+payload.fullId()); return null;
            }
        }
        var children=SleepPacketCompat.children(packet);
        if (children!=null) {
            List<Packet<?>> kept=new ArrayList<>();
            for (Packet<?> child:children) {
                Packet<?> p=filter(s,child);
                if(p!=null) {
                    var seedChildren=SleepPacketCompat.children(p);
                    if(seedChildren!=null) for(Packet<?> flat:seedChildren) kept.add(flat); else kept.add(p);
                }
            }
            return kept.isEmpty()?null:SleepPacketCompat.bundle(kept);
        }
        int[] removed=SleepPacketCompat.removed(packet);
        if(SleepTerrainServer.suppress(s.connection,packet)) return null;
        if (removed!=null) { for(int id:removed) s.delivered.remove(id); return packet; }
        Integer id=SleepPacketCompat.entityId(packet,PlayerCompat.getServerLevel(s.player));
        if (id!=null) {
            if(id==s.player.getId()) return packet;
            Entity entity=PlayerCompat.getServerLevel(s.player).getEntity(id);
            if(entity==null || !relevant(s.player,entity)) return null;
            if(!entity.getUUID().equals(s.delivered.get(id))) {
                List<Packet<?>> list=new ArrayList<>();
                if(s.delivered.containsKey(id)) list.add(SleepPacketCompat.remove(id));
                list.addAll(SleepEntityCompat.seed(s.player,entity));
                if(list.isEmpty()) return null;
                s.delivered.put(id,entity.getUUID());
                if(!SleepPacketCompat.spawn(packet)) list.add(packet);
                return SleepPacketCompat.bundle(list);
            }
            return packet;
        }
        BlockPos pos=SleepPacketCompat.position(packet);
        if(pos!=null && !allowsChunk(s.player,pos.getX()>>4,pos.getZ()>>4)) return null;
        if(!SleepPacketCompat.sectionAllowed(packet,p->allowsChunk(s.player,p.getX()>>4,p.getZ()>>4))) return null;
        return packet;
    }
    private static void emitSeed(Session s, Entity entity) {
        List<Packet<?>> seed=SleepEntityCompat.seed(s.player,entity);
        if (!seed.isEmpty()) { s.delivered.put(entity.getId(),entity.getUUID()); emit(s.connection,SleepPacketCompat.bundle(seed)); }
    }
    static void emit(Connection c, Packet<?> p) { boolean previous=BYPASS.get(); BYPASS.set(true); try { c.send(p); } finally { BYPASS.set(previous); } }
    private static void send(ServerPlayer p, Message m) { Services.NETWORK_MANAGER.sendSleepControl(p,SleepControl.encode(m)); }
    private static void send(Session s, Op op, String detail) { send(s.player,new Message(op,s.generation,++s.sentSequence,detail)); }
    public static void disconnected(Connection c) { SleepTerrainServer.disconnected(c); SESSIONS.remove(c); UNSAFE.remove(c); }
    public static void clear(MinecraftServer server) { SleepTerrainServer.clear(); SESSIONS.entrySet().removeIf(e->PlayerCompat.getMinecraftServer(e.getValue().player)==server); UNSAFE.clear(); }
}
