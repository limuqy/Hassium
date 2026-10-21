package io.github.limuqy.mc.hassium.client.sleep;

import io.github.limuqy.mc.hassium.compat.LevelCompat;
import io.github.limuqy.mc.hassium.platform.Services;
import io.github.limuqy.mc.hassium.protocol.ShadowPullClient;
import io.github.limuqy.mc.hassium.protocol.handshake.*;
import io.github.limuqy.mc.hassium.protocol.sleep.*;
import io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute;
import io.github.limuqy.mc.hassium.shadow.track.ShadowTrackingSession;
import io.github.limuqy.mc.hassium.utils.DimensionKey;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps nearby native chunks intact. Only paused columns lose their authority credentials. */
public final class SleepTerrainClient {
    private static final class Column {
        volatile long request, injected, version, floor;
        volatile Object chunk;
        volatile boolean done, ack, publish;
        volatile long retryAt;
        long testApplyAt;
        Column(long floor) { this.floor=floor; version=versions.incrementAndGet(); }
    }
    private static final java.util.concurrent.atomic.AtomicLong versions=new java.util.concurrent.atomic.AtomicLong();
    private static final ConcurrentHashMap<Long,Column> columns=new ConcurrentHashMap<>();
    private static volatile SleepChunkWindow window;
    private static volatile boolean sleeping;
    private static volatile long generation, minimumRequest;
    private static volatile String dimension;
    private static final java.util.concurrent.atomic.AtomicLong sequence=new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.Set<Long> testDrawn=new java.util.HashSet<>();
    private static int testCulled;
    private SleepTerrainClient() {}
    public static boolean supported() { return LoginCaps.has(ClientLoginNegotiation.current(),LoginCaps.SLEEP_WINDOW); }
    public static boolean restricted() { return sleeping && window!=null; }
    public static boolean moved(int x,int z) { var w=window; return sleeping && w!=null && (w.x()!=x || w.z()!=z); }
    public static boolean canEnter(int x,int z) {
        if(dimension==null) return true;
        for(int cx=x-2;cx<=x+2;cx++) for(int cz=z-2;cz<=z+2;cz++) {
            Column c=columns.get(DimensionKey.key(dimension,cx,cz));
            if(c!=null && !c.done) return false;
        }
        return true;
    }
    public static boolean allows(int x,int z) { var w=window; return !sleeping || w==null || w.light(x,z); }
    public static boolean visible(int x,int z) {
        var w=window;
        if(sleeping && w!=null) return w.visible(x,z);
        var c=dimension==null?null:columns.get(DimensionKey.key(dimension,x,z));
        return c==null || c.done;
    }
    public static boolean renderColumn(int x,int z) {
        boolean keep=visible(x,z);
        if(restricted() && io.github.limuqy.mc.hassium.client.ClientSmokeTest.isEnabled()) {
            if(keep) testDrawn.add(ChunkPos.asLong(x,z)); else testCulled++;
        }
        return keep;
    }
    public static void assertRenderedWindow() {
        if(testDrawn.isEmpty() || testDrawn.size()>9 || testCulled==0)
            throw new IllegalStateException("Renderer window draw="+testDrawn.size()+" culled="+testCulled);
        io.github.limuqy.mc.hassium.Constants.LOG.info("HassiumSmokeTest:SLEEP renderer drawnColumns={} culledSections={}",testDrawn.size(),testCulled);
    }
    public static void enter(long id,int x,int z) {
        if(!supported()) return;
        var mc=Minecraft.getInstance();
        dimension=LevelCompat.getDimensionId(mc.level);
        if(!DimensionKey.isCacheableDimension(dimension)) return;
        generation=id; sequence.set(0);
        testDrawn.clear(); testCulled=0;
        window=new SleepChunkWindow(x,z); sleeping=false;
        minimumRequest=ShadowPullClient.nextRequestFloor();
        // Include the original compute window, including non-rendered light neighbours.
        int radius=ShadowTrackingSession.getInstance().computeDomainBoxRadius();
        for(int cx=x-radius;cx<=x+radius;cx++) for(int cz=z-radius;cz<=z+radius;cz++) {
            if(window.light(cx,cz) || !io.github.limuqy.mc.hassium.compat.ChunkShapeCompat.containsDilated(
                    x,z,ShadowTrackingSession.serverViewDistance(),1,cx,cz)) continue;
            long key=DimensionKey.key(dimension,cx,cz);
            columns.put(key,new Column(minimumRequest));
            invalidate(key);
        }
        sleeping=true;
        mc.levelRenderer.needsUpdate();
    }
    private static void invalidate(long key) {
        String dim=DimensionKey.dimensionOf(key);
        var pos=new ChunkPos(DimensionKey.chunkXOf(key),DimensionKey.chunkZOf(key));
        ShadowLightCompute.invalidateAuthorityEntry(dim,pos);
        ShadowLightCompute.invalidateClientApplyEpoch(dim,pos);
        ShadowTrackingSession.getInstance().clearPullInFlight(dim,pos);
        ShadowPullClient.clearCompareRequested(dim,pos);
    }
    public static void wake() { sleeping=false; Minecraft.getInstance().levelRenderer.needsUpdate(); }
    /** Called by the existing tracking selector, under its normal request budget. */
    public static boolean needsRefresh(String dim,ChunkPos pos) {
        Column c=columns.get(DimensionKey.key(dim,pos.x,pos.z));
        return c!=null && !c.done;
    }
    public static boolean requestRefresh(String dim,ChunkPos pos) {
        Column c=columns.get(DimensionKey.key(dim,pos.x,pos.z));
        if(c==null || c.done || sleeping || System.nanoTime()<c.retryAt) return false;
        c.retryAt=System.nanoTime()+30_000_000_000L;
        invalidate(DimensionKey.key(dim,pos.x,pos.z));
        send(SleepControl.Op.COLUMN_REQUEST,DimensionKey.key(dim,pos.x,pos.z),0);
        if(LoginCaps.has(ClientLoginNegotiation.current(),LoginCaps.PULL_NBT))
            ShadowPullClient.requestFull(dim,java.util.List.of(pos));
        else ShadowPullClient.requestAuthoritativeFull(dim,java.util.List.of(pos));
        return true;
    }
    public static boolean acceptResponse(String dim,int x,int z,long request) {
        if(!allows(x,z)) return false;
        Column c=columns.get(DimensionKey.key(dim,x,z));
        if(c==null) return true;
        if(c.done) return request>c.request;
        if(sleeping || request<c.floor || request<=c.request) return false;
        c.request=request; c.ack=false; c.publish=false; c.version=versions.incrementAndGet(); return true;
    }
    public static long incomingToken(long key) {
        Column c=columns.get(key); return c==null?0:c.request;
    }
    public static boolean acceptPending(long key,long token) {
        Column c=columns.get(key);
        return c==null || token>0 && token==c.request && allows(DimensionKey.chunkXOf(key),DimensionKey.chunkZOf(key));
    }
    public static boolean forceReplace(long key) { Column c=columns.get(key); return c!=null && !c.done; }
    public static void injected(long key,long token,Object chunk) {
        Column c=columns.get(key);
        if(c!=null && token==c.request) { c.injected=token; c.chunk=chunk; }
    }
    /** A LIGHT completion belonging to the previous injected object cannot publish its snapshot. */
    public static long snapshotToken(long key,Object chunk) {
        Column c=columns.get(key);
        if(c==null) return 0;
        if(!allows(DimensionKey.chunkXOf(key),DimensionKey.chunkZOf(key))) return -1;
        if(c.done) return c.publish?c.version:-1;
        return !c.ack && c.request>0 && c.injected==c.request && c.chunk==chunk?c.version:-1;
    }
    public static boolean acceptReady(long key,long token) {
        Column c=columns.get(key);
        return c==null || token>0 && token==c.version && (!c.ack || c.publish)
                && c.injected==c.request && allows(DimensionKey.chunkXOf(key),DimensionKey.chunkZOf(key));
    }
    public static void applied(long key,long token) {
        Column c=columns.get(key);
        if(c==null || c.done || c.ack || token<=0 || token!=c.version) return;
        c.ack=true; c.publish=false; c.version=versions.incrementAndGet();
        send(SleepControl.Op.COLUMN_APPLIED,key,c.request);
    }
    public static void haloApplied(long key,long request) {
        Column c=columns.get(key); if(c!=null && c.request==request) applied(key,c.version);
    }
    /** Dev fixture: exercise updates that arrive after the snapshot, before native application. */
    public static boolean deferTestApply(long key) {
        Column c=columns.get(key);
        if(c==null || c.done || (DimensionKey.chunkXOf(key)!=3 && DimensionKey.chunkXOf(key)!=4) || DimensionKey.chunkZOf(key)!=0
                || !io.github.limuqy.mc.hassium.client.ClientSmokeTest.isEnabled()
                || !"sleepresume".equals(System.getProperty("hassium.smokeScenario"))) return false;
        if(c.testApplyAt==0) c.testApplyAt=System.nanoTime()+2_000_000_000L;
        return System.nanoTime()<c.testApplyAt;
    }
    public static void receive(SleepControl.Message m) {
        if(m.generation()!=generation || dimension==null) return;
        String[] parts=m.detail().split(",");
        if(parts.length!=3) return;
        try {
            int x=Integer.parseInt(parts[0]),z=Integer.parseInt(parts[1]); long request=Long.parseLong(parts[2]);
            long key=DimensionKey.key(dimension,x,z); Column c=columns.get(key);
            if(c==null || request!=c.request) return;
            if(m.op()==SleepControl.Op.COLUMN_READY) {
                c.done=true; c.chunk=null;
                // Native tail handlers have already queued their shadow mutations. Publish only
                // after that queue boundary, with a fresh revision; old packed snapshots stay invalid.
                long revision=c.version;
                var shadow=io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry.getInstance().get();
                if(shadow!=null) shadow.execute(()->{ if(c.done && c.version==revision) c.publish=true; });
                Minecraft.getInstance().levelRenderer.needsUpdate();
            } else restart(key,c);
        } catch(NumberFormatException ignored) { }
    }
    private static void restart(long key,Column c) {
        c.done=false; c.publish=false; c.version=versions.incrementAndGet(); c.floor=ShadowPullClient.nextRequestFloor();
        c.request=0; c.injected=0; c.chunk=null; c.ack=false; c.retryAt=0; invalidate(key);
    }
    public static void unloaded(String dim,ChunkPos pos) {
        long key=DimensionKey.key(dim,pos.x,pos.z); Column c=columns.get(key);
        if(c!=null) restart(key,c);
    }
    private static synchronized void send(SleepControl.Op op,long key,long request) {
        Services.NETWORK_MANAGER.sendSleepControlToServer(SleepControl.encode(new SleepControl.Message(op,generation,sequence.incrementAndGet(),0,0,
                DimensionKey.chunkXOf(key)+","+DimensionKey.chunkZOf(key)+","+request)));
    }
    public static void reset() { sleeping=false; window=null; dimension=null; columns.clear(); generation=0; sequence.set(0); }
}
