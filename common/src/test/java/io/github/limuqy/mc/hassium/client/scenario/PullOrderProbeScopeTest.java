package io.github.limuqy.mc.hassium.client.scenario;

import io.github.limuqy.mc.hassium.protocol.ColumnSnapshotFence;
import io.github.limuqy.mc.hassium.utils.DimensionKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.util.thread.ReentrantBlockableEventLoop;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PullOrderProbeScopeTest {
    private static final long TARGET=DimensionKey.key("minecraft:overworld",3,0);
    @Test void sameXNeighborsAndOtherDimensionsCannotDeferCountOrCompleteTheTarget() {
        var scope=new PullOrderProbeScope(); Object level=new Object(),connection=new Object();
        scope.arm(TARGET,level,connection,7);
        assertTrue(scope.response(TARGET,507,2114,level,connection,7));
        for(long wrong:new long[]{DimensionKey.key("minecraft:overworld",3,-2),
                DimensionKey.key("minecraft:overworld",3,1),DimensionKey.key("minecraft:the_nether",3,0)}) {
            assertFalse(scope.target(wrong,level,connection,7));
            assertFalse(scope.matches(wrong,2114,level,connection,7));
            assertFalse(scope.complete(wrong,2114,level,connection,7));
        }
        assertTrue(scope.complete(TARGET,2114,level,connection,7));
        assertFalse(scope.complete(TARGET,2114,level,connection,7));
    }
    @Test void staleSessionGenerationRequestAndOpaqueCannotClaimTheNewTransaction() {
        var scope=new PullOrderProbeScope(); Object level=new Object(),connection=new Object();
        scope.arm(TARGET,level,connection,7);
        assertTrue(scope.response(TARGET,507,2114,level,connection,7));
        assertTrue(scope.response(TARGET,508,2115,level,connection,7));
        assertFalse(scope.response(TARGET,507,2114,level,connection,7));
        assertFalse(scope.complete(TARGET,2114,level,connection,7));
        assertFalse(scope.complete(TARGET,2115,new Object(),connection,7));
        assertFalse(scope.complete(TARGET,2115,level,new Object(),7));
        assertFalse(scope.complete(TARGET,2115,level,connection,8));
        assertTrue(scope.complete(TARGET,2115,level,connection,7));
    }
    private static final class Owner extends ReentrantBlockableEventLoop<Runnable> {
        private final Thread thread=Thread.currentThread();
        Owner(){super("probe-tail-owner-test");}
        @Override protected Runnable wrapRunnable(Runnable task){return task;}
        @Override protected boolean shouldRun(Runnable task){return true;}
        @Override protected Thread getRunningThread(){return thread;}
    }
    @Test void onlyTheExactTransactionAfterItsRealOwnerTailCanStartTheCheck() {
        Owner owner=new Owner(); var render=new ArrayDeque<Runnable>();
        var fence=new ColumnSnapshotFence<String>(4,1,ColumnSnapshotFence.RESERVATION_BYTES);
        assertTrue(fence.begin(TARGET,507)); long token=fence.incoming(TARGET);
        Object level=new Object(),connection=new Object(),chunk=new Object();
        var scope=new PullOrderProbeScope(); scope.arm(TARGET,level,connection,7);
        assertTrue(scope.response(TARGET,507,token,level,connection,7));
        var checks=new AtomicInteger(); var block=new AtomicReference<>("stone");
        long neighbor=DimensionKey.key("minecraft:overworld",3,-2);
        // The old completed(x-only) callback ran here while target content was still STONE.
        owner.tell(()->assertFalse(scope.complete(neighbor,token,level,connection,7)));
        assertTrue(owner.pollTask()); assertEquals(0,checks.get());
        fence.injected(TARGET,token,chunk); fence.append(TARGET,"diamond",7);
        long drain=fence.startDrain(TARGET);
        for(String state:fence.takeTail(TARGET,drain)) owner.tell(()->block.set(state));
        owner.tell(()->render.add(()->{
            assertTrue(fence.finishDrain(TARGET,drain));
            if(!fence.active(TARGET) && scope.complete(TARGET,token,level,connection,7)) {
                assertEquals("diamond",block.get()); checks.incrementAndGet();
            }
        }));
        while(owner.pollTask()){} assertEquals(0,checks.get());
        render.remove().run(); assertEquals(1,checks.get()); assertEquals(0,fence.reservedBytes());
    }
    @Test void actualProbeUsesExactScopeAndOnlySnapshotTailOrExplicitLegacyApplicationCallbacks() throws Exception {
        Path root=Path.of("").toAbsolutePath();
        while(root!=null && !Files.isDirectory(root.resolve("common/src/main/java")))root=root.getParent();
        assertNotNull(root);
        String base="common/src/main/java/io/github/limuqy/mc/hassium/";
        String probe=Files.readString(root.resolve(base+"compat/PullOrderingProbeCompat.java"));
        assertTrue(probe.contains("scope.complete(key,token,source,mc.getConnection(),"));
        assertTrue(probe.contains("return target(key) && received.contains(x)"));
        assertTrue(probe.contains("scope.matches(key,token,mc.level,mc.getConnection(),"));
        assertTrue(probe.contains("PullSnapshotTailClient.active(key)"));
        String sleep=Files.readString(root.resolve(base+"client/sleep/SleepTerrainClient.java"));
        assertFalse(sleep.contains("PullOrderingProbeCompat.Client.completed("),
                "An unrelated halo/sleep completion must not start an ordinary snapshot check");
        String tail=Files.readString(root.resolve(base+"client/PullSnapshotTailClient.java"));
        assertTrue(tail.contains("PullOrderingProbeCompat.Client.completed(key,version)"));
        assertTrue(tail.contains("fence.finishDrain(key, version)"));
    }
}
