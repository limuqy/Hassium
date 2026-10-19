package io.github.limuqy.mc.hassium.shadow.server;

import java.util.concurrent.Executor;

/** Explicitly propagated only through the two executors supplied to shadow WorldLoader.load. */
public final class ShadowAssemblyScope {
    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> CACHE_ONLY = new ThreadLocal<>();
    private ShadowAssemblyScope() {}
    public static boolean isActive() { return Boolean.TRUE.equals(ACTIVE.get()); }
    public static boolean isCacheOnlyAssembly() { return Boolean.TRUE.equals(CACHE_ONLY.get()); }
    public static <T> T cacheOnly(java.util.function.Supplier<T> action) {
        Boolean previous = CACHE_ONLY.get();
        CACHE_ONLY.set(true);
        try { return call(action); }
        finally {
            if (previous == null) CACHE_ONLY.remove();
            else CACHE_ONLY.set(previous);
        }
    }
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
