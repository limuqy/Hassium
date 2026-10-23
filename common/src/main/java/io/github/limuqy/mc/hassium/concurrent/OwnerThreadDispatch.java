package io.github.limuqy.mc.hassium.concurrent;

import java.util.concurrent.Executor;

/** Preserves the caller's owner transaction even when its executor defers reentrant tasks. */
public final class OwnerThreadDispatch {
    private OwnerThreadDispatch() {}

    public static void runOrSchedule(boolean onOwner, Executor owner, Runnable action) {
        // Minecraft's ReentrantBlockableEventLoop.execute queues while running a task, even
        // on its owner thread. A second hop would let the following native packets overtake
        // this payload. Only callers already on the owner may execute it inline.
        if (onOwner) {
            action.run();
        } else {
            owner.execute(action);
        }
    }
}
