package io.github.limuqy.mc.hassium.concurrent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AsyncSnapshotCommitTest {
    @Test
    void busyOwnerDoesNotDelayVisibleSnapshotOrReorderLiveUpdates() {
        ArrayDeque<Runnable> owner = new ArrayDeque<>(), client = new ArrayDeque<>();
        List<String> order = new ArrayList<>();
        AsyncSnapshotCommit.submit(owner::add, client::add, () -> true,
                () -> order.add("visible snapshot"), () -> order.add("shadow snapshot"),
                () -> order.add("ack"), t -> fail(t));
        assertEquals(List.of("visible snapshot"), order);
        order.add("visible live update");
        owner.add(() -> order.add("shadow live update"));
        while (!owner.isEmpty()) owner.remove().run();
        assertFalse(order.contains("ack"));
        client.remove().run();
        assertEquals(List.of("visible snapshot", "visible live update", "shadow snapshot",
                "shadow live update", "ack"), order);
    }

    @Test
    void unloadReconnectOrNewerResponseCancelsQueuedCommitAndCompletion() {
        for (boolean beforeCommit : new boolean[] {true, false}) {
            ArrayDeque<Runnable> owner = new ArrayDeque<>(), client = new ArrayDeque<>();
            AtomicBoolean current = new AtomicBoolean(true);
            List<String> events = new ArrayList<>();
            AsyncSnapshotCommit.submit(owner::add, client::add, current::get,
                    () -> {}, () -> events.add("commit"), () -> fail("stale ack"), t -> fail(t));
            if (beforeCommit) current.set(false);
            owner.remove().run();
            current.set(false);
            while (!client.isEmpty()) client.remove().run();
            assertEquals(beforeCommit ? List.of() : List.of("commit"), events);
        }
    }

    @Test
    void failureRepairsOnClientWithoutAcknowledging() {
        ArrayDeque<Runnable> owner = new ArrayDeque<>(), client = new ArrayDeque<>();
        RuntimeException error = new RuntimeException("NBT");
        List<Throwable> failures = new ArrayList<>();
        AsyncSnapshotCommit.submit(owner::add, client::add, () -> true, () -> {},
                () -> { throw error; }, () -> fail("ack on failure"), failures::add);
        owner.remove().run();
        assertTrue(failures.isEmpty());
        client.remove().run();
        assertEquals(List.of(error), failures);
    }

    @Test
    void cancellationReleasesCreditOnClientAndRejectedOwnerRequestsRepair() {
        ArrayDeque<Runnable> owner = new ArrayDeque<>(), client = new ArrayDeque<>();
        AtomicBoolean current = new AtomicBoolean(true), released = new AtomicBoolean();
        AsyncSnapshotCommit.submit(owner::add, client::add, current::get, () -> {},
                () -> fail("stale commit"), () -> fail("stale ack"), t -> fail(t), () -> released.set(true));
        current.set(false);
        owner.remove().run();
        assertFalse(released.get());
        client.remove().run();
        assertTrue(released.get());
        var failures = new ArrayList<Throwable>();
        AsyncSnapshotCommit.submit(task -> { throw new java.util.concurrent.RejectedExecutionException(); },
                client::add, () -> true, () -> {}, () -> fail(), () -> fail(), failures::add);
        assertEquals(1, failures.size());
    }
}
