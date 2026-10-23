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
        volatile long request, injected, version, floor, lightVersion;
        volatile Object chunk;
        volatile boolean done, ack, publish;
        volatile long retryAt;
        volatile RefreshPermit refresh;
        long testApplyAt;
        Column(long floor) { this.floor=floor; version=io.github.limuqy.mc.hassium.protocol.SnapshotTokens.next(); }
    }
    private static final ConcurrentHashMap<Long,Column> columns=new ConcurrentHashMap<>();
    private static volatile SleepChunkWindow window;
    private static volatile boolean sleeping;
    private static volatile long generation, minimumRequest;
    private static volatile String dimension;
    private static final java.util.concurrent.atomic.AtomicLong sequence=new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.Set<Long> testDrawn=new java.util.HashSet<>();
    private static int testCulled;
    private static final boolean RECOVERY_DIAGNOSTICS=Boolean.getBoolean("hassium.smokeTest")
            && "sleepresume".equals(System.getProperty("hassium.smokeScenario"));
    private static final java.util.concurrent.atomic.AtomicInteger recoveryEvents=new java.util.concurrent.atomic.AtomicInteger();
    private SleepTerrainClient() {}
    public static boolean supported() { return LoginCaps.has(ClientLoginNegotiation.current(),LoginCaps.SLEEP); }
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
        String dim=LevelCompat.getDimensionId(mc.level);
        if(!DimensionKey.isCacheableDimension(dim)) return;
        io.github.limuqy.mc.hassium.compat.PullOrderingProbeCompat.Client.beginSleep(id);
        testDrawn.clear(); testCulled=0;
        // Include the original compute window, including non-rendered light neighbours.
        int radius=ShadowTrackingSession.getInstance().computeDomainBoxRadius();
        installWindow(id,dim,x,z,ShadowPullClient.nextRequestFloor(),radius,
                ShadowTrackingSession.serverViewDistance(),SleepTerrainClient::invalidate);
        mc.levelRenderer.needsUpdate();
    }
    /** Only metadata is published under this monitor; no chunk capture or owner wait occurs here. */
    private static synchronized void installWindow(long id,String dim,int x,int z,long floor,int radius,int vd,
                                                   java.util.function.LongConsumer invalidator) {
        dimension=dim; generation=id; sequence.set(0); recoveryEvents.set(0);
        window=new SleepChunkWindow(x,z); sleeping=true;
        minimumRequest=floor;
        for(int cx=x-radius;cx<=x+radius;cx++) for(int cz=z-radius;cz<=z+radius;cz++) {
            if(window.light(cx,cz) || !io.github.limuqy.mc.hassium.compat.ChunkShapeCompat.containsDilated(
                    x,z,vd,1,cx,cz)) continue;
            long key=DimensionKey.key(dimension,cx,cz);
            columns.put(key,new Column(minimumRequest));
            invalidator.accept(key);
            recoveryEvent(key,"installed",0);
        }
    }
    private static void invalidate(long key) {
        String dim=DimensionKey.dimensionOf(key);
        var pos=new ChunkPos(DimensionKey.chunkXOf(key),DimensionKey.chunkZOf(key));
        ShadowLightCompute.invalidateAuthorityEntry(dim,pos);
        ShadowLightCompute.invalidateClientApplyEpoch(dim,pos);
        ShadowTrackingSession.getInstance().clearPullInFlight(dim,pos);
        ShadowPullClient.clearCompareRequested(dim,pos);
    }
    public static void wake() { wakeWindow(); Minecraft.getInstance().levelRenderer.needsUpdate(); }
    private static synchronized void wakeWindow() {
        sleeping=false;
        if(dimension!=null) recoveryEvent(DimensionKey.key(dimension,-6,0),"wake",0);
    }
    /** Called by the existing tracking selector, under its normal request budget. */
    public static boolean needsRefresh(String dim,ChunkPos pos) {
        Column c=columns.get(DimensionKey.key(dim,pos.x,pos.z));
        return c!=null && !c.done;
    }
    public static boolean requestRefresh(String dim,ChunkPos pos) {
        long key=DimensionKey.key(dim,pos.x,pos.z);
        RefreshAttempt attempt=captureRefresh(key);
        RefreshPermit permit=reserveRefresh(attempt,System.nanoTime(),()->invalidate(key));
        if(permit==null) return false;
        // Hash/capture/disk preparation stays outside the lifecycle monitor. Only the final
        // already-encoded wire send is admitted by the permit, with a second identity check.
        try { ShadowPullClient.requestSleepRefresh(dim,pos,permit); }
        catch(RuntimeException failure) { permit.cancelled(); throw failure; }
        return true;
    }
    private record RefreshAttempt(long key,Column column,long generation,SleepChunkWindow window) {}
    private static RefreshAttempt captureRefresh(long key) {
        return new RefreshAttempt(key,columns.get(key),generation,window);
    }
    private static boolean refreshCurrent(RefreshAttempt attempt) {
        return attempt.column()!=null && columns.get(attempt.key())==attempt.column()
                && generation==attempt.generation() && window==attempt.window()
                && DimensionKey.dimensionOf(attempt.key()).equals(dimension) && !sleeping && !attempt.column().done;
    }
    private static synchronized RefreshPermit reserveRefresh(RefreshAttempt attempt,long now,Runnable invalidator) {
        if(!refreshCurrent(attempt) || attempt.column().refresh!=null || now<attempt.column().retryAt) return null;
        invalidator.run(); // Concurrent maps/version tables only, never chunk capture or scheduling.
        RefreshPermit permit=new RefreshPermit(attempt);
        attempt.column().refresh=permit;
        recoveryEvent(attempt.key(),"reserved",0);
        return permit;
    }
    private record RefreshPermit(RefreshAttempt attempt) implements ShadowPullClient.DispatchGate {
        @Override public boolean send(java.util.function.BooleanSupplier wireSend) {
            return sendRefresh(this,()->SleepTerrainClient.send(SleepControl.Op.COLUMN_REQUEST,attempt.key(),0),
                    wireSend,System.nanoTime());
        }
        @Override public void cancelled() { cancelRefresh(this); }
    }
    private static synchronized boolean sendRefresh(RefreshPermit permit,Runnable controlSend,java.util.function.BooleanSupplier wireSend,long now) {
        RefreshAttempt attempt=permit.attempt(); Column c=attempt.column();
        if(!refreshCurrent(attempt) || c.refresh!=permit) { cancelRefresh(permit); return false; }
        try {
            controlSend.run(); if(!wireSend.getAsBoolean()) return false;
            c.retryAt=now+30_000_000_000L; // An unsent/filtered/stale intent never consumes this cooldown.
            recoveryEvent(attempt.key(),"wire-sent",0);
            return true;
        } finally { if(c.refresh==permit) c.refresh=null; }
    }
    private static synchronized void cancelRefresh(RefreshPermit permit) {
        Column c=permit.attempt().column(); if(c.refresh==permit) c.refresh=null;
        recoveryEvent(permit.attempt().key(),"unsent-cancelled",0);
    }
    public static boolean acceptResponse(String dim,int x,int z,long request) {
        recoveryEvent(DimensionKey.key(dim,x,z),"response",request);
        if(!allows(x,z)) return false;
        Column c=columns.get(DimensionKey.key(dim,x,z));
        if(c==null) return true;
        if(c.done) {
            if(request<=c.request) return false;
            // COLUMN_READY completed this sleep recovery. A subsequent response owns a normal fence.
            columns.remove(DimensionKey.key(dim,x,z),c);
            return true;
        }
        if(sleeping || request<c.floor || request<=c.request) return false;
        c.request=request; c.ack=false; c.publish=false; c.lightVersion=0; c.version=io.github.limuqy.mc.hassium.protocol.SnapshotTokens.next(); return true;
    }
    public static long incomingToken(long key) {
        Column c=columns.get(key); return c==null?io.github.limuqy.mc.hassium.client.PullSnapshotTailClient.incoming(key):c.version;
    }
    public static boolean acceptPending(long key,long token) {
        Column c=columns.get(key);
        return c==null ? io.github.limuqy.mc.hassium.client.PullSnapshotTailClient.pending(key,token)
                : c.request>0 && token==c.version && allows(DimensionKey.chunkXOf(key),DimensionKey.chunkZOf(key));
    }
    public static boolean acceptLight(long key,long token) {
        Column c=columns.get(key);
        return c==null ? io.github.limuqy.mc.hassium.client.PullSnapshotTailClient.lightCurrent(key,token)
                : c.request>0 && io.github.limuqy.mc.hassium.protocol.SnapshotTokens.permitsLight(c.version,c.lightVersion,token)
                && allows(DimensionKey.chunkXOf(key),DimensionKey.chunkZOf(key));
    }
    public static boolean forceReplace(long key) {
        Column c=columns.get(key);
        return c!=null && !c.done || io.github.limuqy.mc.hassium.client.PullSnapshotTailClient.forceReplace(key);
    }
    public static void cancelSnapshot(long key) {
        Column c=columns.get(key);
        if(c!=null) c.lightVersion=0;
        if(c!=null && !c.done) {
            c.version=io.github.limuqy.mc.hassium.protocol.SnapshotTokens.next();
            c.request=0; c.injected=0; c.chunk=null; c.ack=false;
        }
    }
    public static void invalidateReadySnapshots() {
        columns.values().forEach(c -> { c.lightVersion=0; c.version=io.github.limuqy.mc.hassium.protocol.SnapshotTokens.next(); });
    }
    public static boolean completedSleepColumn(long key) { Column c=columns.get(key); return c!=null && c.done && c.publish; }
    /** Read-only fixture diagnostics distinguish an absent record from a pending or published one. */
    public static String snapshotState(long key) {
        Column c=columns.get(key);
        return "sleeping="+sleeping+" generation="+generation+" "+(c==null?"record=absent":
                "request="+c.request+" version="+c.version+" floor="+c.floor+" injected="+c.injected
                +" chunk="+(c.chunk==null?"null":System.identityHashCode(c.chunk))+" ack="+c.ack+" done="+c.done+" publish="+c.publish
                +" refresh="+(c.refresh!=null)+" retryMs="+Math.max(0,(c.retryAt-System.nanoTime())/1_000_000L));
    }
    /** Bounded, explicit smoke-only diagnosis for the recovery column that exposed the race. */
    public static void recoveryEvent(long key,String stage,long request) {
        if(!RECOVERY_DIAGNOSTICS || !io.github.limuqy.mc.hassium.client.ClientSmokeTest.isEnabled()
                || DimensionKey.chunkXOf(key)!=-6 || DimensionKey.chunkZOf(key)!=0
                || recoveryEvents.getAndIncrement()>=128) return;
        io.github.limuqy.mc.hassium.Constants.LOG.info("HassiumSmokeTest:SLEEP recovery stage={} key={} request={} state={} thread={}",
                stage,key,request,snapshotState(key),Thread.currentThread().getName());
    }
    public static void injected(long key,long token,Object chunk) {
        Column c=columns.get(key);
        if(c==null) io.github.limuqy.mc.hassium.client.PullSnapshotTailClient.injected(key,token,chunk);
        else if(token==c.version) io.github.limuqy.mc.hassium.client.PullSnapshotTailClient.injectedRequest(key,c.request,chunk);
        if(c!=null && token==c.version) { c.injected=c.request; c.chunk=chunk; }
        recoveryEvent(key,"injected",token);
    }
    /** Inline UNCHANGED responses carry a wire request id, while pending terrain carries an opaque version. */
    public static void injectedResponse(long key,long request,Object chunk) {
        Column c=columns.get(key);
        if(c!=null && c.request==request) injected(key,c.version,chunk);
        else if(c==null) io.github.limuqy.mc.hassium.client.PullSnapshotTailClient.injectedRequest(key,request,chunk);
    }
    /** A LIGHT completion belonging to the previous injected object cannot publish its snapshot. */
    public static long snapshotToken(long key,Object chunk) {
        Column c=columns.get(key);
        if(c==null) return io.github.limuqy.mc.hassium.client.PullSnapshotTailClient.ready(key,chunk);
        if(!allows(DimensionKey.chunkXOf(key),DimensionKey.chunkZOf(key))) return -1;
        if(c.done) return c.publish?c.version:-1;
        return !c.ack && c.request>0 && c.injected==c.request && c.chunk==chunk?c.version:-1;
    }
    public static boolean acceptReady(long key,long token) {
        Column c=columns.get(key);
        return c==null ? io.github.limuqy.mc.hassium.client.PullSnapshotTailClient.acceptReady(key,token)
                : token>0 && token==c.version && (!c.ack || c.publish)
                && c.injected==c.request && allows(DimensionKey.chunkXOf(key),DimensionKey.chunkZOf(key));
    }
    public static void applied(long key,long token) {
        if(columns.get(key)==null && !io.github.limuqy.mc.hassium.client.PullSnapshotTailClient.acceptReady(key,token)) return;
        if(io.github.limuqy.mc.hassium.client.PullSnapshotTailClient.applied(key,()->appliedAfterTail(key,token))) return;
        appliedAfterTail(key,token);
    }
    /** Native fallback still acknowledges sleep only after its actual FULL and ordered native tail. */
    public static void directlyApplied(long key) {
        Column c=columns.get(key);
        long token=c==null?io.github.limuqy.mc.hassium.client.PullSnapshotTailClient.incoming(key):c.version;
        io.github.limuqy.mc.hassium.client.PullSnapshotTailClient.applied(key,()->appliedAfterTail(key,token));
    }
    private static void appliedAfterTail(long key,long token) {
        Column c=columns.get(key);
        if(!advanceAfterTail(c,token)) return;
        recoveryEvent(key,"COLUMN_APPLIED-sent",c.request);
        io.github.limuqy.mc.hassium.compat.PullOrderingProbeCompat.Client.sleepEvent(key,"COLUMN_APPLIED-sent");
        send(SleepControl.Op.COLUMN_APPLIED,key,c.request);
    }
    /** State transition separated from the wire send so its phase identities can be verified in isolation. */
    private static boolean advanceAfterTail(Column c,long token) {
        if(c==null || c.done || c.ack || token<=0 || token!=c.version) return false;
        c.ack=true; c.publish=false; c.lightVersion=token;
        c.version=io.github.limuqy.mc.hassium.protocol.SnapshotTokens.next();
        return true;
    }
    public static void haloApplied(long key,long token) {
        Column c=columns.get(key);
        if(c!=null && c.version==token) applied(key,token);
        else if(c==null && io.github.limuqy.mc.hassium.client.PullSnapshotTailClient.pending(key,token)) applied(key,token);
    }
    public static void haloAppliedResponse(long key,long request) {
        Column c=columns.get(key); if(c!=null && c.request==request) applied(key,c.version);
    }
    /** Dev fixture: exercise updates that arrive after the snapshot, before native application. */
    public static boolean deferTestApply(long key) {
        if(io.github.limuqy.mc.hassium.compat.PullOrderingProbeCompat.Client.deferApply(key)) return true;
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
            recoveryEvent(key,"control-"+m.op(),request);
            io.github.limuqy.mc.hassium.compat.PullOrderingProbeCompat.Client.sleepEvent(key,"receive-"+m.op()+" request="+request);
            if(c==null || request!=c.request) return;
            if(m.op()==SleepControl.Op.COLUMN_READY) {
                c.done=true; c.chunk=null;
                // Native tail handlers have already queued their shadow mutations. Publish only
                // after that queue boundary, with a fresh revision; old packed snapshots stay invalid.
                long revision=c.version;
                var shadow=io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry.getInstance().get();
                if(shadow!=null) shadow.execute(()->{
                    if(c.done && c.version==revision) {
                        c.publish=true;
                        io.github.limuqy.mc.hassium.compat.PullOrderingProbeCompat.Client.sleepPublished(key,m.generation());
                        io.github.limuqy.mc.hassium.compat.PullOrderingProbeCompat.Client.haloTailFinished(key,c.lightVersion);
                    }
                    io.github.limuqy.mc.hassium.compat.PullOrderingProbeCompat.Client.sleepEvent(key,"COLUMN_READY-publish");
                });
                Minecraft.getInstance().levelRenderer.needsUpdate();
            } else restart(key,c);
        } catch(NumberFormatException ignored) { }
    }
    private static synchronized void restart(long key,Column c) {
        c.done=false; c.publish=false; c.version=io.github.limuqy.mc.hassium.protocol.SnapshotTokens.next(); c.floor=ShadowPullClient.nextRequestFloor();
        c.request=0; c.injected=0; c.chunk=null; c.ack=false; c.lightVersion=0; c.retryAt=0; c.refresh=null; invalidate(key);
    }
    public static void unloaded(String dim,ChunkPos pos) {
        long key=DimensionKey.key(dim,pos.x,pos.z); Column c=columns.get(key);
        io.github.limuqy.mc.hassium.client.PullSnapshotTailClient.cancel(key);
        if(c!=null) restart(key,c);
    }
    private static synchronized void send(SleepControl.Op op,long key,long request) {
        Services.NETWORK_MANAGER.sendSleepControlToServer(SleepControl.encode(new SleepControl.Message(op,generation,sequence.incrementAndGet(),
                DimensionKey.chunkXOf(key)+","+DimensionKey.chunkZOf(key)+","+request)));
    }
    public static synchronized void reset() { columns.clear(); generation=0; sequence.set(0); window=null; dimension=null; sleeping=false; }
}
