package io.github.limuqy.mc.hassium.concurrent;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Owner-confined snapshot content; retrying later LIGHT must never reapply old content. */
public final class SnapshotContentCommit<T> {
    private volatile T committed;
    private volatile long retryAt;

    public T applyOnce(BooleanSupplier current, Supplier<T> apply) {
        if (!current.getAsBoolean()) return null;
        if (committed == null) committed = apply.get();
        return committed;
    }

    public boolean applied() { return committed != null; }
    public T committed() { return committed; }
    public boolean ready(long now) { return now >= retryAt; }
    public void deferUntil(long deadline) { retryAt = deadline; }
}
