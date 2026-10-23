package io.github.limuqy.mc.hassium.protocol;

import io.github.limuqy.mc.hassium.client.sleep.SleepTerrainClient;
import io.github.limuqy.mc.hassium.utils.DimensionKey;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual sleep publication/refresh/dispatch state transitions without world IO. */
class SleepRecoveryAdmissionTest {
    private static final String DIM="minecraft:overworld";
    private static final long KEY=DimensionKey.key(DIM,-6,0);
    @BeforeAll static void bootstrapRegistries() {
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
    }
    private static Method method(String name) {
        return java.util.Arrays.stream(SleepTerrainClient.class.getDeclaredMethods())
                .filter(m->m.getName().equals(name)).findFirst().map(m->{m.setAccessible(true);return m;}).orElseThrow();
    }
    private static Object call(String name,Object... arguments) throws Exception { return method(name).invoke(null,arguments); }
    @SuppressWarnings("unchecked") private static Object column(long key) throws Exception {
        var field=SleepTerrainClient.class.getDeclaredField("columns"); field.setAccessible(true);
        return ((Map<Long,Object>)field.get(null)).get(key);
    }
    private static Object field(Object column,String name) throws Exception {
        var field=column.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(column);
    }
    private static void install(long generation,LongConsumer callback) throws Exception {
        call("installWindow",generation,DIM,0,0,10L,7,6,callback);
    }
    private static Object attempt(long key) throws Exception { return call("captureRefresh",key); }
    private static Object reserve(Object attempt) throws Exception { return call("reserveRefresh",attempt,1_000L,(Runnable)()->{}); }
    private static boolean send(Object permit,Runnable control,java.util.function.BooleanSupplier wire) throws Exception {
        return (boolean)call("sendRefresh",permit,control,wire,2_000L);
    }
    @AfterEach void clear() { SleepTerrainClient.reset(); }

    @Test void publishedColumnCannotRefreshDuringEntryAndWakeDoesNotInheritAnUnsentCooldown() throws Exception {
        SleepTerrainClient.reset();
        var published=new CountDownLatch(1);var release=new CountDownLatch(1);var reader=new CountDownLatch(1);
        var firstKey=new AtomicLong(Long.MIN_VALUE);var invalidations=new AtomicInteger();
        var workers=Executors.newFixedThreadPool(2);
        try {
            var entering=workers.submit(()->{
                install(1,key->{
                    if(invalidations.getAndIncrement()!=0) return;
                    firstKey.set(key);published.countDown();
                    try { if(!release.await(5,TimeUnit.SECONDS)) throw new AssertionError("Reader never released entry"); }
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
                });return null;
            });
            assertTrue(published.await(5,TimeUnit.SECONDS));long key=firstKey.get();
            assertNotNull(column(key));assertTrue(SleepTerrainClient.restricted(),"Sleep gate precedes every new Column publication");
            var refreshing=workers.submit(()->{
                reader.countDown();
                return SleepTerrainClient.requestRefresh(DIM,new ChunkPos(DimensionKey.chunkXOf(key),DimensionKey.chunkZOf(key)));
            });
            assertTrue(reader.await(5,TimeUnit.SECONDS));release.countDown();entering.get(5,TimeUnit.SECONDS);
            assertFalse(refreshing.get(5,TimeUnit.SECONDS),"Actual refresh entry cannot reserve/send against a partially installed sleep window");
            Object c=column(key);assertEquals(0L,field(c,"retryAt"));assertNull(field(c,"refresh"));
            call("wakeWindow");Object permit=reserve(attempt(key));assertNotNull(permit);
            assertEquals(0L,field(c,"retryAt"),"Queued/prepared work is not yet a sent request");
            var controls=new AtomicInteger();var pulls=new AtomicInteger();
            assertTrue(send(permit,controls::incrementAndGet,()->{pulls.incrementAndGet();return true;}));
            assertEquals(1,controls.get());assertEquals(1,pulls.get());assertEquals(30_000_002_000L,field(c,"retryAt"));
        } finally { release.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS)); }
    }
    @Test void readerThatSawAwakeBeforeEntryCannotBorrowTheNewColumnOrGeneration() throws Exception {
        install(1,key->{});call("wakeWindow");Object old=attempt(KEY);Object oldColumn=column(KEY);
        install(2,key->{});call("wakeWindow");Object current=column(KEY);assertNotSame(oldColumn,current);
        assertNull(reserve(old),"An old false/same-coordinate observation must recheck generation, window and Column identity");
        assertEquals(0L,field(current,"retryAt"));Object permit=reserve(attempt(KEY));assertNotNull(permit);
        ((ShadowPullClient.DispatchGate)permit).cancelled();
        assertEquals(0L,field(current,"retryAt"));assertNotNull(reserve(attempt(KEY)),"Filtered/abandoned work is immediately retryable");
    }
    @Test void preparedOldGenerationCannotSendOrDiscardAReplacementReceiveCredit() throws Exception {
        install(1,key->{});call("wakeWindow");Object oldPermit=reserve(attempt(KEY));assertNotNull(oldPermit);
        var receiver=new PullReceiveWindow<String>(1,4);assertTrue(receiver.offer(KEY,"old"));
        var old=receiver.drain().get(0);assertTrue(receiver.bind(KEY,old.token(),10));
        install(2,key->{});call("wakeWindow");receiver.cancel(KEY);assertTrue(receiver.offer(KEY,"new"));
        var current=receiver.drain().get(0);assertTrue(receiver.bind(KEY,current.token(),11));
        var wire=new AtomicInteger();assertFalse(send(oldPermit,wire::incrementAndGet,()->{wire.incrementAndGet();return true;}));
        assertEquals(0,wire.get());assertFalse(receiver.discard(KEY,old.token()));assertTrue(receiver.matches(KEY,11));
        Object c=column(KEY);assertEquals(0L,field(c,"retryAt"));assertNotNull(reserve(attempt(KEY)));
    }
    @Test void failedWireSendDoesNotChargeThirtySecondsOrLeaveARefreshLease() throws Exception {
        install(1,key->{});call("wakeWindow");Object permit=reserve(attempt(KEY));assertNotNull(permit);
        var failure=assertThrows(InvocationTargetException.class,()->send(permit,()->{},()->{throw new IllegalStateException("wire unavailable");}));
        assertInstanceOf(IllegalStateException.class,failure.getCause());Object c=column(KEY);
        assertEquals(0L,field(c,"retryAt"));assertNull(field(c,"refresh"));assertNotNull(reserve(attempt(KEY)));
    }
    @Test void knownNoSendResultFromTheRealAdmissionBridgeDoesNotChargeCooldown() throws Exception {
        install(1,key->{});call("wakeWindow");Object permit=reserve(attempt(KEY));assertNotNull(permit);
        var wire=new AtomicInteger();
        assertFalse(send(permit,()->{},()->io.github.limuqy.mc.hassium.network.PreparedClientSend.trySend(
                null,()->null,()->true,wire::incrementAndGet,failure->fail(failure))));
        assertEquals(0,wire.get());Object c=column(KEY);assertEquals(0L,field(c,"retryAt"));
        assertNull(field(c,"refresh"));assertNotNull(reserve(attempt(KEY)));
    }
    @Test void captureAndDiskPreparationAreOutsideTheLifecycleMonitor() {
        assertTrue(Modifier.isSynchronized(method("installWindow").getModifiers()));
        assertTrue(Modifier.isSynchronized(method("reserveRefresh").getModifiers()));
        assertTrue(Modifier.isSynchronized(method("sendRefresh").getModifiers()));
        assertFalse(Modifier.isSynchronized(method("requestRefresh").getModifiers()));
    }
}
