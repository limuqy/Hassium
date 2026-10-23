package io.github.limuqy.mc.hassium.concurrent;

import io.github.limuqy.mc.hassium.protocol.ColumnSnapshotFence;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.util.thread.ReentrantBlockableEventLoop;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotContentCommitTest {
    private static final class Owner extends ReentrantBlockableEventLoop<Runnable> {
        private final Thread thread=Thread.currentThread();
        Owner() { super("delta-content-owner-test"); }
        @Override protected Runnable wrapRunnable(Runnable task) { return task; }
        @Override protected boolean shouldRun(Runnable task) { return true; }
        @Override protected Thread getRunningThread() { return thread; }
    }

    @Test void asynchronousDeltaCommitAndNativeTailSurviveRepeatedBusyLightRetries() {
        Owner owner=new Owner();
        var client=new ArrayDeque<Runnable>();
        var fence=new ColumnSnapshotFence<String>(4,1,ColumnSnapshotFence.RESERVATION_BYTES);
        assertTrue(fence.begin(4,540)); long version=fence.incoming(4);
        var content=new SnapshotContentCommit<Object>(); Object baseline=new Object();
        var shadowBlock=new AtomicReference<>("stone"); var visibleBlock=new AtomicReference<>("stone");
        var writes=new AtomicInteger(); var acknowledgements=new AtomicInteger();
        fence.append(4,"diamond",10);
        AsyncSnapshotCommit.submit(owner::tell,client::add,()->fence.pending(4,version),
                ()->visibleBlock.set("gold"),
                ()->{
                    assertSame(baseline,content.applyOnce(()->fence.pending(4,version),()->{
                        writes.incrementAndGet(); shadowBlock.set("gold"); return baseline;
                    }));
                    fence.injected(4,version,baseline);
                },()->{
                    long drain=fence.startDrain(4);
                    for(String state:fence.takeTail(4,drain)) {
                        visibleBlock.set(state); owner.execute(()->shadowBlock.set(state));
                    }
                    // A busy LIGHT retry used to submit the same GOLD snapshot again here.
                    for(int i=0;i<3;i++) owner.tell(()->assertNull(content.applyOnce(
                            ()->fence.pending(4,version),()->{
                                writes.incrementAndGet(); shadowBlock.set("gold"); return baseline;
                            })));
                    owner.tell(()->assertSame(baseline,content.applyOnce(()->true,()->{
                        fail("Even a still-current LIGHT retry must not reapply content"); return null;
                    })));
                    owner.tell(()->client.add(()->{
                        assertTrue(fence.finishDrain(4,drain)); acknowledgements.incrementAndGet();
                    }));
                },t->fail(t));
        assertEquals("gold",visibleBlock.get()); assertEquals("stone",shadowBlock.get());
        assertTrue(owner.pollTask()); client.remove().run();
        while(owner.pollTask()) {}
        client.remove().run();
        assertEquals("diamond",visibleBlock.get()); assertEquals("diamond",shadowBlock.get());
        assertEquals(1,writes.get()); assertEquals(1,acknowledgements.get());
        assertEquals(0,fence.reservedBytes());
    }

    @Test void oldReappliedDeltaReproducesGoldOnlyInTheShadowAfterTheNativeTail() {
        Owner owner=new Owner(); var shadowBlock=new AtomicReference<>("stone");
        owner.tell(()->shadowBlock.set("gold"));
        owner.tell(()->shadowBlock.set("diamond"));
        owner.tell(()->shadowBlock.set("gold")); // Old busy-path repeated content submission.
        while(owner.pollTask()) {}
        assertEquals("gold",shadowBlock.get());
    }

    @Test void replacementAndDisconnectRejectACommitThatAlreadyPassedTheConsumerCheck() {
        for(boolean disconnect:new boolean[]{false,true}) {
            Owner owner=new Owner();
            var fence=new ColumnSnapshotFence<String>(4,1,ColumnSnapshotFence.RESERVATION_BYTES);
            assertTrue(fence.begin(4,1)); long version=fence.incoming(4);
            assertTrue(fence.pending(4,version));
            var content=new SnapshotContentCommit<Object>();
            owner.tell(()->assertNull(content.applyOnce(()->fence.pending(4,version),()->{
                fail("Stale consumer admission must be checked again on the owner"); return null;
            })));
            if(disconnect) fence.clear(); else assertTrue(fence.begin(4,2));
            assertTrue(owner.pollTask()); assertFalse(content.applied());
            fence.clear(); assertEquals(0,fence.reservedBytes());
        }
    }

    @Test void unavailableOwnerCanDeferWithoutApplyingOrReplacingNewerWork() {
        var content=new SnapshotContentCommit<Object>();
        assertNull(content.applyOnce(()->false,()->{fail("Inline fallback cannot mutate world");return null;}));
        content.deferUntil(100); assertFalse(content.ready(99)); assertTrue(content.ready(100));
        var queue=new ConcurrentHashMap<Long,SnapshotContentCommit<Object>>();
        var newer=new SnapshotContentCommit<Object>(); queue.put(4L,newer);
        queue.putIfAbsent(4L,content); assertSame(newer,queue.get(4L));
        assertThrows(IllegalStateException.class,()->content.applyOnce(()->true,()->{throw new IllegalStateException();}));
        assertFalse(content.applied()); Object baseline=new Object();
        assertSame(baseline,content.applyOnce(()->true,()->baseline));
        assertNull(content.applyOnce(()->false,()->fail("Superseded applied work cannot light")));
    }

    @Test void productionChecksTheRenderOwnerAndDispatchesBeforeTakingTheChunkLock() throws Exception {
        Path root=Path.of("").toAbsolutePath();
        while(root!=null && !Files.isDirectory(root.resolve("common/src/main/java"))) root=root.getParent();
        assertNotNull(root);
        String metadata=Files.readString(root.resolve("common/src/main/java/io/github/limuqy/mc/hassium/client/ClientMetadataHandler.java"));
        int guard=metadata.indexOf("if (!mc.isSameThread())");
        assertTrue(guard>0 && guard<metadata.indexOf("mc.player == null"));
        String light=Files.readString(root.resolve("common/src/main/java/io/github/limuqy/mc/hassium/shadow/light/ShadowLightCompute.java"));
        int dispatch=light.indexOf("LevelChunk baseline = io.github.limuqy.mc.hassium.compat.ShadowPoiGate.callExclusive");
        assertTrue(dispatch>0);
        String commit=light.substring(dispatch,light.indexOf("if (baseline == null)",dispatch));
        assertTrue(commit.indexOf("if(!server.isMainLoopThread())")<commit.indexOf("withChunkLock(pos"));
        assertTrue(commit.contains("work.content().applyOnce("));
        assertTrue(commit.contains("SleepTerrainClient.acceptPending(key,work.sleepToken())"));
        assertTrue(commit.contains("server.shadowGeneration()"));
        assertTrue(commit.contains("invalidateLightAfterContentChange(key)"));
        assertTrue(light.contains("pendingDeltas.putIfAbsent(key, work)"));
    }
}
