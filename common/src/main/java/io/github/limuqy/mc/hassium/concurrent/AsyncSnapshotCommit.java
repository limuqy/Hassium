package io.github.limuqy.mc.hassium.concurrent;

import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Keep visible packet application inline; acknowledge only after the ordered owner commit. */
public final class AsyncSnapshotCommit {
    private AsyncSnapshotCommit() {}

    public static void submit(Executor owner, Executor client, BooleanSupplier current,
                              Runnable visible, Runnable commit, Runnable complete,
                              Consumer<Throwable> failed) {
        submit(owner, client, current, visible, commit, complete, failed, () -> {});
    }

    public static void submit(Executor owner, Executor client, BooleanSupplier current,
                              Runnable visible, Runnable commit, Runnable complete,
                              Consumer<Throwable> failed, Runnable discarded) {
        if (!current.getAsBoolean()) { discarded.run(); return; }
        try {
            visible.run();
            owner.execute(() -> {
                if (!current.getAsBoolean()) { client.execute(discarded); return; }
                Throwable failure = null;
                try { commit.run(); }
                catch (Throwable t) { failure = t; }
                Throwable result = failure;
                client.execute(() -> {
                    if (!current.getAsBoolean()) { discarded.run(); return; }
                    if (result == null) complete.run();
                    else failed.accept(result);
                });
            });
        } catch (Throwable t) {
            if (current.getAsBoolean()) failed.accept(t);
            else discarded.run();
        }
    }
}
