package io.github.limuqy.mc.hassium.concurrent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.util.thread.ReentrantBlockableEventLoop;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OwnerThreadDispatchTest {
    /** Uses Minecraft's actual runningTask/scheduleExecutables queue policy on both anchors. */
    private static final class Owner extends ReentrantBlockableEventLoop<Runnable> {
        private final Thread thread = Thread.currentThread();
        Owner() { super("pull-order-owner-test"); }
        @Override protected Runnable wrapRunnable(Runnable task) { return task; }
        @Override protected boolean shouldRun(Runnable task) { return true; }
        @Override protected Thread getRunningThread() { return thread; }
    }

    @Test void unconditionalExecuteReproducesOldSnapshotOverwritingTheNativeTail() {
        Owner owner = new Owner();
        List<String> wire = new ArrayList<>();
        AtomicReference<String> clientBlock = new AtomicReference<>("stone");
        owner.tell(() -> {
            assertTrue(owner.isSameThread());
            owner.execute(() -> { wire.add("FULL"); clientBlock.set("stone"); });
            wire.add("block tail"); clientBlock.set("diamond");
        });
        assertTrue(owner.pollTask());
        assertEquals(List.of("block tail"), wire, "Same-thread execute was deferred inside the owner task");
        assertEquals(1, owner.getPendingTasksCount());
        assertTrue(owner.pollTask());
        assertEquals(List.of("block tail", "FULL"), wire);
        assertEquals("stone", clientBlock.get(), "The native update was overwritten despite a complete send chain");
    }

    @Test void fullAndDeltaPayloadsRemainBeforeTheirNativeTailInsideAnOwnerTask() {
        for (String kind : List.of("FULL", "DELTA")) {
            Owner owner = new Owner();
            List<String> wire = new ArrayList<>();
            AtomicReference<String> clientBlock = new AtomicReference<>("stone");
            owner.tell(() -> {
                OwnerThreadDispatch.runOrSchedule(owner.isSameThread(), owner, () -> {
                    wire.add(kind); clientBlock.set("stone");
                });
                wire.add("block tail"); clientBlock.set("diamond");
                wire.add("BE tail");
            });
            assertTrue(owner.pollTask());
            assertEquals(List.of(kind, "block tail", "BE tail"), wire);
            assertEquals("diamond", clientBlock.get());
            assertEquals(0, owner.getPendingTasksCount(), "No second owner hop can outlive the tail transaction");
        }
    }

    @Test void aWorkerStillQueuesTheSendAndNeverTouchesTheConnectionOffOwner() throws Exception {
        Owner owner = new Owner();
        AtomicReference<Thread> sentOn = new AtomicReference<>();
        Thread worker = new Thread(() -> OwnerThreadDispatch.runOrSchedule(
                owner.isSameThread(), owner, () -> sentOn.set(Thread.currentThread())), "pull-encode-test");
        worker.start(); worker.join();
        assertNull(sentOn.get());
        assertEquals(1, owner.getPendingTasksCount());
        assertTrue(owner.pollTask());
        assertSame(Thread.currentThread(), sentOn.get());
        assertEquals(0, owner.getPendingTasksCount());
    }

    @Test void neoPlayPayloadsUseTheOwnerBoundaryRatherThanAnAdditionalExecute() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("neoforge/src/main/java"))) root = root.getParent();
        assertNotNull(root);
        String source = Files.readString(root.resolve(
                "neoforge/src/main/java/io/github/limuqy/mc/hassium/network/NeoForgeNetworkManager.java"));
        assertTrue(source.contains("OwnerThreadDispatch.runOrSchedule("));
        assertTrue(source.contains("server.isSameThread(), server, () -> player.connection.send(payload)"));
        assertFalse(source.contains("server.execute(() -> player.connection.send("));
        assertFalse(source.contains("player.connection.send(new ByteArrayPayload("),
                "Dictionary/index payloads must share the same owner boundary");
        assertTrue(source.contains("sendServerPayload(player, new io.github.limuqy.mc.hassium.compat.SleepPayloadCompat.ToClient(data))"));
    }
}
