package io.github.limuqy.mc.hassium.client.scenario;

import io.github.limuqy.mc.hassium.concurrent.SnapshotContentCommit;
import io.github.limuqy.mc.hassium.protocol.ColumnSnapshotFence;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import io.github.limuqy.mc.hassium.testutil.QueuedTestEventLoop;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HaloLightProbeTest {
    private static final class Owner extends QueuedTestEventLoop {
        private final Thread owner=Thread.currentThread();
        Owner(){super("halo-fixture-owner");}
        @Override public Runnable wrapRunnable(Runnable task){return task;}
        @Override protected boolean shouldRun(Runnable task){return true;}
        @Override protected Thread getRunningThread(){return owner;}
    }
    private static HaloLightProbe.Identity identity(long key,long token,Object server,long generation,Object baseline,Object level,Object connection) {
        return new HaloLightProbe.Identity(key,token,server,generation,baseline,level,connection);
    }
    @Test void actualOwnerTailMustFinishBeforeTheSameCommittedWorkCanEnterAndCompleteLight() {
        var owner=new Owner();var probe=new HaloLightProbe();Object level=new Object(),connection=new Object(),server=new Object(),baseline=new Object();
        var fence=new ColumnSnapshotFence<String>(4,1,ColumnSnapshotFence.RESERVATION_BYTES);
        assertTrue(fence.begin(10,20));long token=fence.incoming(10);
        var work=new SnapshotContentCommit<Object>();var writes=new AtomicInteger();
        assertSame(baseline,work.applyOnce(()->fence.pending(10,token),()->{writes.incrementAndGet();return baseline;}));
        fence.injected(10,token,baseline);
        probe.arm(level,connection,1,0);var id=identity(10,token,server,1,baseline,level,connection);
        assertTrue(probe.select(id,1));assertFalse(probe.select(identity(11,token,server,1,baseline,level,connection),2));
        assertEquals(HaloLightProbe.Decision.DEFER,probe.beforeLight(id,true,true,2));
        work.deferUntil(50);assertFalse(work.ready(49));assertTrue(work.ready(50));
        long drain=fence.startDrain(10);fence.takeTail(10,drain);
        assertFalse(fence.pending(10,token));assertTrue(fence.lightCurrent(10,token));
        assertEquals(HaloLightProbe.Decision.DEFER,probe.beforeLight(id,false,true,3),"Draining alone is not actual tail completion");
        owner.enqueue(()->{
            assertTrue(fence.finishDrain(10,drain));assertTrue(probe.tailFinished(10,token,4));
        });
        assertTrue(owner.pollTask());assertEquals(0,fence.reservedBytes());
        owner.enqueue(()->{
            assertEquals(HaloLightProbe.Decision.RELEASE,probe.beforeLight(id,false,true,5));
            assertSame(baseline,work.applyOnce(()->true,()->{fail("Fixture must retry only LIGHT");return null;}));
            assertTrue(probe.phaseCurrent(id,"admitted",level,connection,server,1,baseline,false,true,6));
            // This is an owner/phase model; the actual engine future is asserted by the runtime hook.
            assertTrue(probe.phaseCurrent(id,"engine-complete",level,connection,server,1,baseline,false,true,7));
        });
        assertTrue(owner.pollTask());assertEquals(1,writes.get());assertTrue(probe.complete());assertNull(probe.selected());
    }
    @Test void oldFutureCannotCompleteAfterTheCurrentOwnerBaselineWasReplaced() {
        var owner=new Owner();var probe=new HaloLightProbe();Object level=new Object(),connection=new Object(),server=new Object(),baseline=new Object();
        var current=new AtomicReference<>(baseline);var future=new CompletableFuture<Void>();
        var id=identity(10,20,server,1,baseline,level,connection);
        probe.arm(level,connection,1,0);assertTrue(probe.select(id,1));assertTrue(probe.tailFinished(10,20,2));
        assertEquals(HaloLightProbe.Decision.RELEASE,probe.beforeLight(id,false,true,3));
        owner.enqueue(()->assertTrue(probe.phaseCurrent(id,"admitted",level,connection,server,1,current.get(),false,true,4)));
        assertTrue(owner.pollTask());
        // The future still carries the original event/baseline and its LIGHT token remains valid.
        // The owner lookup, rather than the event object, must detect the replacement.
        future.thenRun(()->owner.enqueue(()->assertFalse(probe.phaseCurrent(id,"engine-complete",level,connection,server,1,current.get(),false,true,6))));
        owner.enqueue(()->current.set(new Object()));future.complete(null);
        assertTrue(owner.pollTask());assertTrue(owner.pollTask());
        assertFalse(probe.complete());assertNotNull(probe.failure());assertNull(probe.selected());
        assertEquals(HaloLightProbe.Decision.NONE,probe.beforeLight(id,false,true,7));
    }
    @Test void neighborOldTokenOrDifferentBaselineCannotBorrowTailOrPhaseEvents() {
        Object level=new Object(),connection=new Object(),server=new Object(),baseline=new Object();
        var probe=new HaloLightProbe();probe.arm(level,connection,1,0);var id=identity(10,20,server,1,baseline,level,connection);
        assertTrue(probe.select(id,1));
        assertFalse(probe.tailFinished(11,20,2));assertFalse(probe.tailFinished(10,19,2));
        assertEquals(HaloLightProbe.Decision.DEFER,probe.beforeLight(id,true,true,3));assertTrue(probe.tailFinished(10,20,4));
        assertEquals(HaloLightProbe.Decision.RELEASE,probe.beforeLight(id,false,true,5));
        assertFalse(probe.phase(identity(11,20,server,1,baseline,level,connection),"admitted",false,true,6));
        assertFalse(probe.phase(identity(10,20,server,1,new Object(),level,connection),"admitted",false,true,6));
        assertFalse(probe.phase(identity(10,20,server,2,baseline,level,connection),"admitted",false,true,6));
        assertFalse(probe.phase(identity(10,20,server,1,baseline,level,new Object()),"admitted",false,true,6));
        assertTrue(probe.phase(id,"admitted",false,true,7));assertTrue(probe.phase(id,"engine-complete",false,true,8));
    }
    @Test void lifecycleChangesAndNewerBoundedWorkCancelTheFixtureWithoutBlockingProduction() {
        for(int reason=0;reason<7;reason++) {
            Object level=new Object(),connection=new Object(),server=new Object(),baseline=new Object();
            var probe=new HaloLightProbe();probe.arm(level,connection,1,0);var id=identity(10,20,server,1,baseline,level,connection);assertTrue(probe.select(id,1));
            if(reason==5) {
                var queue=new ConcurrentHashMap<Long,Object>();Object replacement=new Object();queue.put(10L,replacement);
                assertSame(replacement,queue.putIfAbsent(10L,new Object()));
                probe.cancel(10,20,"newer work occupied the bounded retry slot");assertSame(replacement,queue.get(10L));
            } else probe.validate(reason==0?null:level,reason==1?new Object():connection,reason==2?new Object():server,
                    reason==3?2:1,reason==4?new Object():baseline,reason!=6,2);
            assertNotNull(probe.failure());assertNull(probe.selected());
            assertEquals(HaloLightProbe.Decision.NONE,probe.beforeLight(id,false,true,3),"Failure immediately removes the fixture delay");
            assertFalse(probe.phase(id,"engine-complete",false,true,4));
        }
    }
    @Test void missingRealDeltaTimeoutAndLateEngineCompletionAreFailures() {
        Object level=new Object(),connection=new Object(),server=new Object(),baseline=new Object();
        var missing=new HaloLightProbe();missing.arm(level,connection,1,0);missing.expire(HaloLightProbe.TIMEOUT_NANOS);
        assertTrue(missing.failure().contains("no real halo DELTA"));assertFalse(missing.complete());
        var late=new HaloLightProbe();late.arm(level,connection,1,0);var id=identity(10,20,server,1,baseline,level,connection);
        assertTrue(late.select(id,1));assertTrue(late.tailFinished(10,20,2));assertEquals(HaloLightProbe.Decision.RELEASE,late.beforeLight(id,false,true,3));
        assertTrue(late.phase(id,"admitted",false,true,4));
        assertFalse(late.phase(id,"engine-complete",false,true,HaloLightProbe.TIMEOUT_NANOS));
        assertNotNull(late.failure());assertFalse(late.complete());assertNull(late.selected());
    }
    @Test void lightCannotBeCreditedFromDrainingOrAnUnconfirmedTail() {
        Object level=new Object(),connection=new Object(),server=new Object(),baseline=new Object();
        var probe=new HaloLightProbe();probe.arm(level,connection,1,0);var id=identity(10,20,server,1,baseline,level,connection);
        assertTrue(probe.select(id,1));assertEquals(HaloLightProbe.Decision.DEFER,probe.beforeLight(id,false,true,2));
        assertFalse(probe.phase(id,"admitted",false,true,3));
        assertNotNull(probe.failure());assertFalse(probe.complete());assertNull(probe.selected());
    }
    @Test void realHooksRemainStrictlyGatedAndObserveActualTailAndEngineBoundaries() throws Exception {
        Path root=Path.of("").toAbsolutePath();while(root!=null && !Files.isDirectory(root.resolve("common/src/main/java")))root=root.getParent();assertNotNull(root);
        String probe=Files.readString(root.resolve("common/src/main/java/io/github/limuqy/mc/hassium/compat/PullOrderingProbeCompat.java"));
        assertTrue(probe.contains("ENABLED && \"pullorderhalo\".equals(System.getProperty(\"hassium.smokeScenario\"))"));
        assertTrue(probe.contains("\"Forge\".equals(io.github.limuqy.mc.hassium.platform.Services.PLATFORM.getPlatformName())"));
        assertTrue(probe.contains("if(!HALO) return false;"));assertTrue(probe.contains("(SAME || HALO) && repairs.get()!=repairBaseline"));
        assertTrue(probe.contains("server.execute(()->haloPhaseOnOwner(event,stage))"));
        int phaseOwner=probe.indexOf("private static void haloPhaseOnOwner(");
        int ownerGuard=probe.indexOf("if(!expected.isMainLoopThread())",phaseOwner);
        int lookup=probe.indexOf("current.injectedChunk(",ownerGuard);
        int phase=probe.indexOf("halo.phaseCurrent(event,stage",lookup);
        assertTrue(phaseOwner<ownerGuard && ownerGuard<lookup && lookup<phase);
        String compute=Files.readString(root.resolve("common/src/main/java/io/github/limuqy/mc/hassium/shadow/light/ShadowLightCompute.java"));
        int injected=compute.indexOf("SleepTerrainClient.injected(key, work.sleepToken(), baseline)");
        int capture=compute.indexOf("Client.haloCommitted(key,work.sleepToken(),server,baseline)");
        int applied=compute.indexOf("SleepTerrainClient.haloApplied(key, work.sleepToken())",capture);
        int defer=compute.indexOf("Client.deferHaloLight(key,work.sleepToken(),server,baseline)");
        assertTrue(injected<capture && capture<applied && applied<defer);
        assertTrue(compute.contains("work.content().deferUntil(System.nanoTime()+50_000_000L)"));
        assertTrue(compute.contains("if(pendingDeltas.putIfAbsent(key,work)!=null)"));
        assertTrue(compute.contains("Client.lightPhase(inf.key,delta.sleepToken(),\"engine-complete\",inf.chunk)"));
        String sleep=Files.readString(root.resolve("common/src/main/java/io/github/limuqy/mc/hassium/client/sleep/SleepTerrainClient.java"));
        int ready=sleep.indexOf("if(m.op()==SleepControl.Op.COLUMN_READY)");
        int publish=sleep.indexOf("c.publish=true",ready);
        int sleepTail=sleep.indexOf("Client.haloTailFinished(key,c.lightVersion)",publish);
        assertTrue(ready<publish && publish<sleepTail);
        String tail=Files.readString(root.resolve("common/src/main/java/io/github/limuqy/mc/hassium/client/PullSnapshotTailClient.java"));
        assertEquals(2,tail.lines().filter(line->line.contains("Client.haloTailFinished(key,version)")).count());
    }
}
