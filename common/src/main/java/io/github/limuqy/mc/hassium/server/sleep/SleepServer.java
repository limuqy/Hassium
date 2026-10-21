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
    private enum Phase { PREPARED, SLEEPING, WAKING, RECOVERING, BLOCKED }
    private static final class Session {
        final ServerPlayer player; final Connection connection; final long generation; final float threshold;
        final net.minecraft.server.level.ServerLevel level;
        final boolean inPlace;
        final Map<Integer,UUID> delivered = new HashMap<>();
        volatile long[] relatedChunks=new long[0];
        volatile Phase phase; boolean recoveryAnnounced; long receivedSequence; long sentSequence; long wakeDeadline; long started=System.nanoTime();
        Session(ServerPlayer p, long generation, float threshold, Phase phase) {
            player=p; connection=PlayerCompat.getConnection(p); level=PlayerCompat.getServerLevel(p); this.generation=generation; this.threshold=threshold; this.phase=phase;
            inPlace=ServerHandshakeActivation.hasCaps(p.getUUID(),LoginCaps.SLEEP_IN_PLACE);
        }
    }
    private static final Map<Connection,Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<Connection,String> UNSAFE = new ConcurrentHashMap<>();
    private static final Set<Connection> PLAY_READY=ConcurrentHashMap.newKeySet(), RECOVERY_DONE=ConcurrentHashMap.newKeySet();
    private static final AtomicLong GENERATIONS = new AtomicLong(System.currentTimeMillis());
    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(()->false);
    private SleepServer() {}
    public static boolean bypass() { return BYPASS.get(); }
    public static void receive(ServerPlayer player, byte[] bytes) {
        if (player == null || !SleepLeaseCompat.supported() || !ServerHandshakeActivation.hasCaps(player.getUUID(),LoginCaps.SLEEP)) return;
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
                    || m.generation()!=0 || !SleepControl.validThreshold(m.threshold(),player.getMaxHealth())
                    || !Double.isFinite(SleepEntityCompat.interactionRadius(player)) || SleepEntityCompat.interactionRadius(player)>1024
                    || SleepControl.lowHealth(player.getHealth(),m.threshold())) {
                String reason=issue!=null ? issue : SleepControl.lowHealth(player.getHealth(),m.threshold())
                        ? "Health "+player.getHealth()+" HP is at or below sleep threshold "+m.threshold()+" HP"
                        : "Sleep unavailable: close containers, check threshold and compatibility";
                send(player,new Message(Op.ERROR,0,m.sequence(),0,0,reason)); return;
            }
            session=new Session(player,GENERATIONS.incrementAndGet(),m.threshold(),Phase.PREPARED);
            session.receivedSequence=m.sequence(); SESSIONS.put(connection,session);
            send(session,Op.PREPARED,0,""); return;
        }
        if (session==null || session.player!=player || session.generation!=m.generation() || m.sequence()<=session.receivedSequence) return;
        session.receivedSequence=m.sequence();
        if (checkHealth(player)) return;
        if (m.op()==Op.CONFIRM && session.phase==Phase.PREPARED) {
            // PREPARED still has a complete normal world. A failed confirmation is a rejection,
            // not a protected sleep disconnect; protection starts only after this boundary.
            if (!player.isAlive() || player.isPassenger() || player.containerMenu!=player.inventoryMenu
                    || PlayerCompat.getServerLevel(player)!=session.level
                    || !SleepControl.validThreshold(session.threshold,player.getMaxHealth())
                    || SleepControl.lowHealth(player.getHealth(),session.threshold)) {
                send(session,Op.ERROR,0,"Sleep entry rejected: player state changed before confirmation");
                SESSIONS.remove(connection,session);
                return;
            }
            for (Entity e : SleepEntityCompat.observed(player)) {
                if (relevant(player,e)) session.delivered.put(e.getId(),e.getUUID());
                else emit(connection,SleepPacketCompat.remove(e.getId()));
            }
            session.phase=Phase.SLEEPING;
            SleepTerrainServer.enter(player,session.generation);
            Constants.LOG.info("[SLEEP] entered player={} generation={} healthThreshold={}",player.getUUID(),session.generation,session.threshold);
            send(session,Op.SLEEPING,0,SleepTerrainServer.description(player));
        } else if (m.op()==Op.RESUME && session.phase==Phase.SLEEPING && session.inPlace) {
            resume(session);
        } else if (m.op()==Op.WAKE && (session.phase==Phase.SLEEPING || session.phase==Phase.WAKING || session.phase==Phase.BLOCKED)) {
            player.stopUsingItem();
            try {
                if (session.wakeDeadline>0 && System.nanoTime()>=session.wakeDeadline)
                    throw new IllegalStateException("Reconnect hold expired; reconnect manually");
                int seconds=HassiumConfigService.getInstance().getConfig().master().sleepWakeHoldSeconds();
                var lease=ReconnectChunkLeases.acquire(player,session.generation,session.threshold,seconds);
                session.wakeDeadline=lease.deadlineNanos();
                if (checkHealth(player)) return;
                session.phase=Phase.WAKING;
                int left=(int)Math.max(0,(lease.deadlineNanos()-System.nanoTime())/1_000_000_000L);
                send(session,Op.WAKE_READY,left,"");
            } catch (RuntimeException failure) {
                session.phase=Phase.SLEEPING;
                Constants.LOG.warn("[SLEEP] reconnect hold failed for {}",player.getUUID(),failure);
                String reason=failure.getMessage()==null ? failure.getClass().getSimpleName() : failure.getMessage();
                send(session,Op.ERROR,0,"Reconnect hold failed: "+reason.substring(0,Math.min(180,reason.length()))+"; connection retained");
            }
        } else if (m.op()==Op.FINISH && session.phase==Phase.RECOVERING) {
            if (!checkHealth(player)) { send(session,Op.ACTIVE,0,""); RECOVERY_DONE.add(connection); SESSIONS.remove(connection); }
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
        send(s,Op.ACTIVE,0,"");
        Constants.LOG.info("[SLEEP] resumed inPlace player={} generation={} restoredEntities={} usingItem={} leases={}",s.player.getUUID(),s.generation,restored,s.player.isUsingItem(),ReconnectChunkLeases.activeCount());
    }
    public static void onPlayReady(ServerPlayer player) {
        PLAY_READY.add(PlayerCompat.getConnection(player));
        if(!ServerHandshakeActivation.hasCaps(player.getUUID(),LoginCaps.SLEEP_WAKE_ATTEMPT)) return;
        var lease=ReconnectChunkLeases.get(player.getUUID());
        if (lease==null || lease.previous()==PlayerCompat.getConnection(player) || System.nanoTime()>=lease.deadlineNanos()) {
            player.connection.disconnect(Component.literal("Hassium reconnect hold expired; reconnect manually")); return;
        }
        Session s=SESSIONS.computeIfAbsent(PlayerCompat.getConnection(player),c->new Session(player,lease.generation(),lease.threshold(),Phase.RECOVERING));
        s.recoveryAnnounced=true;
        SleepProbeCompat.hurtOnReady(player);
        if (!checkHealth(player)) send(s,Op.RECOVERING,(int)Math.max(0,(lease.deadlineNanos()-System.nanoTime())/1_000_000_000L),"");
    }
    public static boolean checkHealth(ServerPlayer player) {
        Connection c=PlayerCompat.getConnection(player);
        if(c==null) return false;
        if(!c.isConnected()) return true;
        Session s=SESSIONS.get(c);
        if(s==null) {
            var lease=ReconnectChunkLeases.get(player.getUUID());
            if(lease!=null && lease.previous()!=c && !RECOVERY_DONE.contains(c)
                    && ServerHandshakeActivation.hasCaps(player.getUUID(),LoginCaps.SLEEP_WAKE_ATTEMPT)) {
                s=new Session(player,lease.generation(),lease.threshold(),Phase.RECOVERING); SESSIONS.put(c,s);
            }
        }
        if (s==null) return false;
        if (s.phase==Phase.PREPARED) return false;
        if (s.threshold>0 && (SleepControl.lowHealth(player.getHealth(),s.threshold) || player.isDeadOrDying())) {
            SleepRuntimeProbe.health(player,s.threshold);
            player.stopUsingItem();
            SESSIONS.remove(s.connection);
            send(s,Op.SAFETY,0,"health="+player.getHealth()+", threshold="+s.threshold);
            // A lease already created by an explicit wake retains its original failure deadline.
            // Low health itself never creates a lease. A later manual login does not inherit protection.
            Component reason=Component.translatable("hassium.sleep.low_health",player.getHealth(),s.threshold);
            Constants.LOG.info("[SLEEP_SAFETY] disconnect player={} health={} threshold={}",player.getUUID(),player.getHealth(),s.threshold);
            // Direct close + normal onDisconnect cleanup, without waiting for a blocked client write.
            s.connection.disconnect(reason); s.connection.handleDisconnection(); return true;
        }
        return false;
    }
    public static void tick(MinecraftServer server) {
        for(ServerPlayer p:List.copyOf(server.getPlayerList().getPlayers())) checkHealth(p);
        for (Session s : List.copyOf(SESSIONS.values())) {
            if (PlayerCompat.getMinecraftServer(s.player)!=server) continue;
            if (!s.connection.isConnected() || s.player.isRemoved()) { SESSIONS.remove(s.connection); continue; }
            if (checkHealth(s.player)) continue;
            if (s.player.isDeadOrDying() || PlayerCompat.getServerLevel(s.player)!=s.level
                    || !HassiumConfigService.getInstance().getConfig().master().sleepEnabled()
                    || !HassiumConfigService.getInstance().isMasterEnabled()) {
                incompatible(s,"Sleep ended: world, life state or server configuration changed"); continue;
            }
            if ((s.phase==Phase.PREPARED || s.phase==Phase.RECOVERING) && System.nanoTime()-s.started>120_000_000_000L) {
                s.player.connection.disconnect(Component.literal("Hassium sleep handshake timed out")); continue;
            }
            if (s.phase==Phase.SLEEPING) {
                List<Long> positions=new ArrayList<>();
                for (Entity entity : SleepEntityCompat.observed(s.player)) {
                    if(entity instanceof Projectile projectile && projectile.getOwner()==s.player) positions.add(entity.chunkPosition().toLong());
                    boolean keep=relevant(s.player,entity);
                    UUID delivered=s.delivered.get(entity.getId());
                    if (keep && !entity.getUUID().equals(delivered)) {
                        if (delivered!=null) emit(s.connection,SleepPacketCompat.remove(entity.getId()));
                        emitSeed(s,entity);
                    } else if (!keep && delivered!=null) {
                        s.delivered.remove(entity.getId()); emit(s.connection,SleepPacketCompat.remove(entity.getId()));
                    }
                }
                s.relatedChunks=positions.stream().mapToLong(Long::longValue).toArray();
            }
        }
        ReconnectChunkLeases.tick(server);
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
    public static boolean allowsChunk(ServerPlayer player, int x, int z) {
        if(!SleepTerrainServer.allows(player,x,z)) return false;
        Connection c=PlayerCompat.getConnection(player);
        Session s=c==null?null:SESSIONS.get(c);
        if (s==null || s.inPlace || (s.phase!=Phase.SLEEPING && s.phase!=Phase.WAKING && s.phase!=Phase.BLOCKED)) return true;
        double radius=SleepEntityCompat.interactionRadius(player)+16; // nearby interaction chunks + native light neighbours
        if(Math.abs(x*16+8-player.getX())<=radius+8 && Math.abs(z*16+8-player.getZ())<=radius+8) return true;
        for(long packed:s.relatedChunks) {
            var pos=new net.minecraft.world.level.ChunkPos(packed);
            if(Math.abs(x-pos.x)<=1 && Math.abs(z-pos.z)<=1) return true;
        }
        return false;
    }
    /** Called after pressure counters and before Connection enqueues encoding. */
    public static boolean suppress(Connection connection, Packet<?> packet, boolean callback) {
        if (bypass()) return false;
        Session s=SESSIONS.get(connection);
        if (PacketPayloadCompat.isCustomPayloadPacket(packet)) {
            var id=PacketPayloadCompat.getPayloadId(packet);
            if (id!=null && !Set.of("hassium","minecraft","forge","neoforge").contains(id.namespace())) {
                UNSAFE.putIfAbsent(connection,id.fullId());
                if (s!=null && s.phase!=Phase.PREPARED && s.phase!=Phase.RECOVERING) {
                    PlayerCompat.getMinecraftServer(s.player).execute(()->incompatible(s,"Unsupported world payload: "+id.fullId()));
                    return true;
                }
            }
        }
        if (s==null || callback || s.phase==Phase.PREPARED || s.phase==Phase.RECOVERING)
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
            s.phase=Phase.BLOCKED;
            s.player.connection.disconnect(Component.literal(reason+"; reconnect manually"));
        }
    }
    public static boolean blocksInteraction(ServerPlayer player) {
        Session s=SESSIONS.get(PlayerCompat.getConnection(player));
        return s!=null && (s.phase==Phase.WAKING || s.phase==Phase.RECOVERING || s.phase==Phase.BLOCKED);
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
    private static void send(Session s, Op op, int hold, String detail) { send(s.player,new Message(op,s.generation,++s.sentSequence,s.threshold,hold,detail,(float)Math.max(8,Math.min(1024,SleepEntityCompat.interactionRadius(s.player))))); }
    public static void disconnected(Connection c) { SleepTerrainServer.disconnected(c); SESSIONS.remove(c); UNSAFE.remove(c); PLAY_READY.remove(c); RECOVERY_DONE.remove(c); }
    public static boolean recoveryAnnounced(ServerPlayer player) {
        Connection c=PlayerCompat.getConnection(player); Session s=c==null?null:SESSIONS.get(c);
        return c!=null && PLAY_READY.contains(c);
    }
    public static void clear(MinecraftServer server) { SleepTerrainServer.clear(); SESSIONS.entrySet().removeIf(e->PlayerCompat.getMinecraftServer(e.getValue().player)==server); UNSAFE.clear(); PLAY_READY.clear(); RECOVERY_DONE.clear(); ReconnectChunkLeases.clear(server); }
}
