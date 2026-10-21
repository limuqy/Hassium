package io.github.limuqy.mc.hassium.server.sleep;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.SleepLeaseCompat;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import java.util.*;

/** Dev smoke evidence only. Tracks actual chunk identity, unloads, and executed hopper ticker calls. */
public final class SleepRuntimeProbe {
    public static final boolean ENABLED=io.github.limuqy.mc.hassium.server.ServerSmokeTest.isEnabled();
    private static final Map<UUID,Observation> HOLDS=new HashMap<>();
    private static final Map<BlockEntity,Long> TICKS=new IdentityHashMap<>();
    private static long failures;
    private static final java.util.concurrent.atomic.LongAdder TCP_BYTES=new java.util.concurrent.atomic.LongAdder();
    private static String trafficPhase="setup";
    private static long trafficSample;
    private static boolean factoryInitialized;
    private static final class Observation {
        final ReconnectChunkLeases.Lease lease;
        final Map<Long,LevelChunk> chunks=new HashMap<>();
        final Map<BlockEntity,Long> hoppers=new IdentityHashMap<>();
        long ticks,offlineTicks;
        Observation(ReconnectChunkLeases.Lease lease) { this.lease=lease; }
    }
    private SleepRuntimeProbe() {}
    public static void installTcpMeter(io.netty.channel.Channel channel) {
        if(!ENABLED || channel.pipeline().get("prepender")==null || channel.pipeline().get("sleep_tcp_probe")!=null) return;
        channel.pipeline().addBefore("prepender","sleep_tcp_probe",new io.netty.channel.ChannelOutboundHandlerAdapter() {
            @Override public void write(io.netty.channel.ChannelHandlerContext ctx,Object message,io.netty.channel.ChannelPromise promise) {
                int bytes=message instanceof io.netty.buffer.ByteBuf buffer ? buffer.readableBytes():0;
                var observed=promise.isVoid()?ctx.newPromise():promise;
                observed.addListener(f->{if(f.isSuccess()) TCP_BYTES.add(bytes);});
                ctx.write(message,observed);
            }
        });
    }
    public static void traffic(net.minecraft.server.MinecraftServer server) {
        String scenario=System.getProperty("hassium.serverSmokeScenario","");
        if(!ENABLED || !Set.of("sleeptraffic","sleepfactory","sleepwindowtraffic").contains(scenario)) return;
        boolean realFactory=scenario.equals("sleepfactory");
        if(realFactory && !factoryInitialized) { io.github.limuqy.mc.hassium.compat.EgressFixtureCompat.freeze(server,true); factoryInitialized=true; }
        String next=trafficPhase;
        if(!server.getPlayerList().getPlayers().isEmpty()) next=server.getPlayerList().getPlayers().get(0).getTags().stream()
                .filter(t->t.startsWith("sleep_traffic_")).sorted().findFirst().orElse(trafficPhase);
        long now=System.nanoTime();
        if(!next.equals(trafficPhase) || now-trafficSample>=1_000_000_000L) {
            long output=0;
            for(int i=0;!realFactory && i<16;i++) {
                var pos=new net.minecraft.core.BlockPos(26+(i%4)*7,139,12+(i/4)*7);
                if(server.overworld().getBlockEntity(pos) instanceof net.minecraft.world.Container container)
                    for(int slot=0;slot<container.getContainerSize();slot++) output+=container.getItem(slot).getCount();
            }
            Constants.LOG.info("[SLEEP_TRAFFIC] phase={} next={} nanoTime={} tcpBytes={} players={} timestampMs={} outputItems={} worldTicks={} frozen={}",trafficPhase,next,now,TCP_BYTES.sum(),server.getPlayerList().getPlayerCount(),System.currentTimeMillis(),realFactory?"n/a":Long.toString(output),server.overworld().getGameTime(),realFactory && io.github.limuqy.mc.hassium.compat.EgressFixtureCompat.frozen(server));
            trafficSample=now; trafficPhase=next;
        }
    }
    public static void health(net.minecraft.server.level.ServerPlayer player,float threshold) {
        if(ENABLED) Constants.LOG.info("[SLEEP_PROBE] health health={} max={} absorption={} dead={} threshold={} leases={}",
                player.getHealth(),player.getMaxHealth(),player.getAbsorptionAmount(),player.isDeadOrDying(),threshold,ReconnectChunkLeases.activeCount());
    }
    public static void acquired(ReconnectChunkLeases.Lease lease) {
        if(!ENABLED) return;
        Observation o=new Observation(lease);
        for(var c:lease.chunks()) {
            LevelChunk chunk=lease.level().getChunkSource().getChunkNow(c.pos().x,c.pos().z);
            o.chunks.put(c.pos().toLong(),chunk);
            if(chunk!=null) for(BlockEntity entity:chunk.getBlockEntities().values()) if(entity instanceof HopperBlockEntity)
                o.hoppers.put(entity,TICKS.getOrDefault(entity,0L));
        }
        HOLDS.put(lease.id(),o);
    }
    public static void hopperTick(BlockEntity entity) {
        if(ENABLED && entity instanceof HopperBlockEntity) TICKS.merge(entity,1L,Long::sum);
    }
    public static void unloaded(LevelChunk chunk) {
        if(!ENABLED) return;
        for(Observation o:HOLDS.values()) if(o.chunks.get(chunk.getPos().toLong())==chunk) fail("protected chunk unloaded "+chunk.getPos());
    }
    public static void tick(ReconnectChunkLeases.Lease lease) {
        if(!ENABLED) return;
        Observation o=HOLDS.get(lease.id()); if(o==null) return;
        o.ticks++; if(lease.level().players().isEmpty()) o.offlineTicks++;
        for(var entry:o.chunks.entrySet()) {
            var p=new net.minecraft.world.level.ChunkPos(entry.getKey());
            if(lease.level().getChunkSource().getChunkNow(p.x,p.z)!=entry.getValue()) fail("protected chunk identity changed "+p);
        }
    }
    public static void released(ReconnectChunkLeases.Lease lease,String reason) {
        if(!ENABLED) return;
        Observation o=HOLDS.remove(lease.id()); if(o==null) return;
        long minimumHopperTicks=Long.MAX_VALUE;
        for(var entry:o.hoppers.entrySet()) minimumHopperTicks=Math.min(minimumHopperTicks,TICKS.getOrDefault(entry.getKey(),0L)-entry.getValue());
        if(!o.hoppers.isEmpty() && minimumHopperTicks<Math.max(0,o.ticks-2)) fail("hopper ticker gap during hold");
        Constants.LOG.info("[SLEEP_PROBE] hold-release reason={} chunks={} ticks={} offlineTicks={} hoppers={} minimumHopperTicks={} failures={}",
                reason,o.chunks.size(),o.ticks,o.offlineTicks,o.hoppers.size(),o.hoppers.isEmpty()?0:minimumHopperTicks,failures);
        if(HOLDS.isEmpty()) TICKS.clear();
    }
    private static void fail(String reason) { failures++; Constants.LOG.error("[SLEEP_PROBE] FAIL {}",reason); }
}
