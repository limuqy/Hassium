package io.github.limuqy.mc.hassium.testutil;

import net.minecraft.util.thread.ReentrantBlockableEventLoop;

/** Explicit test admission, retaining Minecraft's normal reentrant execute policy inside tasks. */
public abstract class QueuedTestEventLoop extends ReentrantBlockableEventLoop<Runnable> {
    private boolean enqueueOnly;

    protected QueuedTestEventLoop(String name) { super(name); }

    @Override public Runnable wrapRunnable(Runnable task) { return task; }
    @Override public boolean scheduleExecutables() { return enqueueOnly || super.scheduleExecutables(); }

    public final void enqueue(Runnable task) {
        if (!isSameThread()) { execute(task); return; }
        boolean previous = enqueueOnly;
        enqueueOnly = true;
        try { execute(task); }
        finally { enqueueOnly = previous; }
    }
}
