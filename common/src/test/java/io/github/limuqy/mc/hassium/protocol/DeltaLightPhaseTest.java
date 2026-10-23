package io.github.limuqy.mc.hassium.protocol;

import io.github.limuqy.mc.hassium.client.sleep.SleepTerrainClient;
import io.github.limuqy.mc.hassium.concurrent.SnapshotContentCommit;
import io.github.limuqy.mc.hassium.utils.DimensionKey;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import io.github.limuqy.mc.hassium.testutil.QueuedTestEventLoop;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeltaLightPhaseTest {
    private static final class Owner extends QueuedTestEventLoop {
        private final Thread thread=Thread.currentThread();
        Owner(){super("halo-light-owner-test");}
        @Override public Runnable wrapRunnable(Runnable task){return task;}
        @Override protected boolean shouldRun(Runnable task){return true;}
        @Override protected Thread getRunningThread(){return thread;}
    }
    @Test void haloTailCanFinishBeforeDelayedInitAndLightWithoutAdmittingContentAgain() {
        Owner owner=new Owner(); var fence=new ColumnSnapshotFence<String>(4,1,ColumnSnapshotFence.RESERVATION_BYTES);
        assertTrue(fence.begin(1,10)); long token=fence.incoming(1);
        Object baseline=new Object(); var current=new AtomicReference<>(baseline);
        var commit=new SnapshotContentCommit<Object>(); var writes=new AtomicInteger(); var init=new AtomicInteger(); var light=new AtomicInteger();
        assertSame(baseline,commit.applyOnce(()->fence.pending(1,token),()->{writes.incrementAndGet();return baseline;}));
        fence.injected(1,token,baseline); long drain=fence.startDrain(1);
        assertFalse(fence.pending(1,token),"This was the erroneous LIGHT gate, even before ACK");
        assertTrue(fence.lightCurrent(1,token));
        fence.takeTail(1,drain); assertTrue(fence.finishDrain(1,drain));
        assertFalse(fence.pending(1,token)); assertFalse(fence.acceptsReady(1,token));
        assertTrue(fence.lightCurrent(1,token)); assertEquals(0,fence.reservedBytes());
        // Neighbour completion/owner admission arrives after the halo tail has already ACKed.
        owner.enqueue(()->{
            boolean valid=commit.applied() && fence.lightCurrent(1,token) && current.get()==commit.committed();
            assertTrue(valid);
            for(int retry=0;retry<3;retry++) assertSame(baseline,commit.applyOnce(()->valid,()->{
                fail("LIGHT retry must not resubmit old terrain");return null;
            }));
            init.incrementAndGet(); light.incrementAndGet();
        });
        assertTrue(owner.pollTask());
        assertEquals(1,writes.get()); assertEquals(1,init.get()); assertEquals(1,light.get());
        current.set(new Object());
        assertFalse(fence.lightCurrent(1,token) && current.get()==commit.committed(),"Even valid phase cannot borrow a new baseline");
    }
    @Test void newRequestCancellationUnloadAndDisconnectRevokeTheCompletedLightCredential() {
        for(int reason=0;reason<3;reason++) {
            var fence=new ColumnSnapshotFence<String>(4,1,ColumnSnapshotFence.RESERVATION_BYTES);
            assertTrue(fence.begin(1,10)); long token=fence.incoming(1); Object sameBaseline=new Object();
            fence.injected(1,token,sameBaseline); long drain=fence.startDrain(1); fence.takeTail(1,drain); fence.finishDrain(1,drain);
            assertTrue(fence.lightCurrent(1,token));
            if(reason==0) assertTrue(fence.begin(1,11));
            else if(reason==1) fence.cancel(1); else fence.clear();
            assertFalse(fence.lightCurrent(1,token),"Same baseline reference cannot revive a superseded phase");
            assertFalse(fence.lightCurrent(1,0)); fence.clear(); assertEquals(0,fence.reservedBytes());
        }
    }
    private static void field(Object target,String name,Object value) throws Exception {
        Field f=target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target,value);
    }
    @SuppressWarnings("unchecked")
    private static Object sleepColumn(long key,long token) throws Exception {
        Class<?> type=java.util.Arrays.stream(SleepTerrainClient.class.getDeclaredClasses())
                .filter(nested->nested.getSimpleName().equals("Column")).findFirst().orElseThrow();
        var constructor=type.getDeclaredConstructor(long.class); constructor.setAccessible(true);
        Object column=constructor.newInstance(1L); field(column,"request",10L); field(column,"injected",10L); field(column,"version",token);
        Field columns=SleepTerrainClient.class.getDeclaredField("columns"); columns.setAccessible(true);
        ((Map<Long,Object>)columns.get(null)).put(key,column); return column;
    }
    @Test void actualSleepAckTransitionRetainsOnlyLightAndEveryInvalidationRevokesIt() throws Exception {
        long key=DimensionKey.key("minecraft:overworld",3,0);
        try {
            for(int invalidation=0;invalidation<4;invalidation++) {
                SleepTerrainClient.reset(); long token=SnapshotTokens.next(); Object column=sleepColumn(key,token);
                assertTrue(SleepTerrainClient.acceptPending(key,token)); assertTrue(SleepTerrainClient.acceptLight(key,token));
                var content=new SnapshotContentCommit<Object>(); Object baseline=new Object();
                assertSame(baseline,content.applyOnce(()->SleepTerrainClient.acceptPending(key,token),()->baseline));
                var advance=SleepTerrainClient.class.getDeclaredMethod("advanceAfterTail",column.getClass(),long.class); advance.setAccessible(true);
                assertEquals(true,advance.invoke(null,column,token)); // Real state transition, without emitting a wire ACK in unit tests.
                assertFalse(SleepTerrainClient.acceptPending(key,token)); assertTrue(SleepTerrainClient.acceptLight(key,token));
                field(column,"done",true); field(column,"chunk",null); field(column,"publish",true);
                assertTrue(SleepTerrainClient.acceptLight(key,token),"COLUMN_READY dropping its chunk reference must not erase phase authority");
                Owner owner=new Owner(); var phases=new AtomicInteger();
                owner.enqueue(()->{
                    assertSame(baseline,content.applyOnce(()->SleepTerrainClient.acceptLight(key,token),()->{
                        fail("Normal sleep ACK must continue only LIGHT, never terrain"); return null;
                    }));
                    phases.incrementAndGet();
                });
                assertTrue(owner.pollTask()); assertEquals(1,phases.get(),"The delayed owner phase remains admissible after the real ACK state transition");
                if(invalidation==0) assertTrue(SleepTerrainClient.acceptResponse("minecraft:overworld",3,0,11));
                else if(invalidation==1) SleepTerrainClient.cancelSnapshot(key);
                else if(invalidation==2) SleepTerrainClient.invalidateReadySnapshots();
                else SleepTerrainClient.reset();
                assertFalse(SleepTerrainClient.acceptLight(key,token));
            }
        } finally { SleepTerrainClient.reset(); }
    }
    @Test void deltaAndDelayedGateUseThePhaseCredentialOnlyForAlreadyCommittedContent() throws Exception {
        Path root=Path.of("").toAbsolutePath();
        while(root!=null && !Files.isDirectory(root.resolve("common/src/main/java"))) root=root.getParent();
        assertNotNull(root);
        String source=Files.readString(root.resolve("common/src/main/java/io/github/limuqy/mc/hassium/shadow/light/ShadowLightCompute.java"));
        assertTrue(source.contains("work.content().applied() && SleepTerrainClient.acceptLight(key,work.sleepToken())"));
        assertTrue(source.contains("work.content().committed()"));
        assertTrue(source.contains("if(t.token instanceof DeltaWork delta"));
        assertTrue(source.contains("!deltaLightCurrent(t.key,delta,server)"));
        assertTrue(source.contains("server.shadowGeneration()=="));
        assertTrue(source.contains("if(!SleepTerrainClient.acceptPending(key,work.sleepToken())"),"Fresh content still uses the strict pending gate");
    }
}
