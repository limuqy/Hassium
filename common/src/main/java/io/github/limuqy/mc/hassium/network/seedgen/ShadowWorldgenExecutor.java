package io.github.limuqy.mc.hassium.network.seedgen;

import io.github.limuqy.mc.hassium.Constants;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 影子端 worldgen / 光照 mailbox 专用 ForkJoin 池。
 * <p>
 * 原版 {@code ChunkMap} 把任务挂在进程级 {@code Util.backgroundExecutor()} 上，
 * 会和客户端 mesh / 资源加载抢同一口池。本池只服务影子 {@code MinecraftServer.executor}；
 * park 复用保留池，完整 {@link SeedGenLevelCompat#shutdown} 才关闭。不得
 * {@code ServerChunkCache.close()}——1.20.1 {@code BlockableEventLoop.close} 会把传入的
 * executor shutdown，关错全局池曾让 R2 light mailbox 全拒。
 */
public final class ShadowWorldgenExecutor {

    private static final Object LOCK = new Object();
    private static volatile ExecutorService pool;

    private ShadowWorldgenExecutor() {}

    /** 影子上下文才隔离；专用服 / 单人仍走原版全局池。 */
    public static boolean shouldIsolate(boolean shadowServerContext) {
        return shadowServerContext;
    }

    /**
     * 给渲染和客户端 {@code backgroundExecutor} 留核：{@code processors - 2}，至少 1。
     */
    public static int workerCount(int processors) {
        return Math.max(1, Math.min(255, processors - 2));
    }

    public static ExecutorService service() {
        synchronized (LOCK) {
            if (pool == null || pool.isShutdown()) {
                int workers = workerCount(Runtime.getRuntime().availableProcessors());
                pool = createPool(workers);
                Constants.LOG.info("Hassium: Shadow worldgen executor isolated (workers={})", workers);
            }
            return pool;
        }
    }

    /** 完整关停后释放；park 复用不得调用。 */
    public static void shutdown() {
        synchronized (LOCK) {
            ExecutorService current = pool;
            pool = null;
            if (current == null) {
                return;
            }
            current.shutdown();
            boolean terminated;
            try {
                terminated = current.awaitTermination(3L, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                terminated = false;
            }
            if (!terminated) {
                current.shutdownNow();
            }
        }
    }

    private static ExecutorService createPool(int workers) {
        AtomicInteger seq = new AtomicInteger(1);
        return new ForkJoinPool(workers, pool -> {
            ForkJoinWorkerThread thread = new ForkJoinWorkerThread(pool) {
                @Override
                protected void onTermination(Throwable exception) {
                    if (exception != null) {
                        Constants.LOG.warn("Hassium: Shadow worldgen worker died", exception);
                    }
                    super.onTermination(exception);
                }
            };
            thread.setName("hassium-shadow-worldgen-" + seq.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }, (t, e) -> Constants.LOG.error("Hassium: Uncaught exception in {}", t.getName(), e), true);
    }
}
