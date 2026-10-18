package io.github.limuqy.mc.hassium.shadow.server;

import java.util.concurrent.Executor;

/** Explicitly propagated only through the two executors supplied to shadow WorldLoader.load. */
public final class ShadowAssemblyScope {
    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();
    private ShadowAssemblyScope() {}
    public static boolean isActive() { return Boolean.TRUE.equals(ACTIVE.get()); }
    public static <T> T call(java.util.function.Supplier<T> action) {
        Boolean previous = ACTIVE.get();
        ACTIVE.set(true);
        try { return action.get(); }
        finally {
            if (previous == null) ACTIVE.remove();
            else ACTIVE.set(previous);
        }
    }
    public static Executor wrap(Executor delegate) {
        return task -> delegate.execute(() -> {
            Boolean previous = ACTIVE.get();
            ACTIVE.set(true);
            try { task.run(); }
            finally {
                if (previous == null) ACTIVE.remove();
                else ACTIVE.set(previous);
            }
        });
    }
}
