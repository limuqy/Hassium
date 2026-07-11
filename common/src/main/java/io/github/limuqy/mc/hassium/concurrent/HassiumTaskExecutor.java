package io.github.limuqy.mc.hassium.concurrent;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DebugLogger.LogType;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Hassium 统一异步任务执行器
 * <p>
 * 管理所有后台任务的提交、执行和回调。
 * 根据 Java 版本自动选择平台线程或虚拟线程。
 * 实现 {@link Executor} 以兼容 {@link CompletableFuture} 等标准库。
 * <p>
 * 客户端和服务端各有一个独立实例，生命周期随连接/服务器。
 */
public class HassiumTaskExecutor implements AutoCloseable, Executor {

    private static volatile HassiumTaskExecutor clientInstance;
    private static volatile HassiumTaskExecutor serverInstance;

    private final ExecutorService executor;
    private final String name;
    private final boolean virtual;

    /** 任务注册表：记录所有提交的任务，用于 disconnect 时按类别取消 */
    private final ConcurrentHashMap<String, TrackedTask> taskRegistry = new ConcurrentHashMap<>();

    private HassiumTaskExecutor(String name, int threads) {
        this.name = name;
        this.executor = ExecutorFactory.create(name, threads);
        this.virtual = ExecutorFactory.isVirtualThreadAvailable();
        Constants.LOG.info("Hassium: Created {} executor '{}' (virtual={}, threads={})",
                virtual ? "virtual" : "platform", name, virtual, virtual ? "unbounded" : threads);
    }

    // ==================== 核心 Executor 实现 ====================

    /**
     * 实现 {@link Executor} 接口，兼容 {@link CompletableFuture} 等标准库。
     * <p>
     * 默认以 SAFE_TO_CANCEL 类别提交，不注册到 taskRegistry。
     * 用于 {@code CompletableFuture.supplyAsync(supplier, executor)} 等场景。
     */
    @Override
    public void execute(Runnable command) {
        executor.execute(command);
    }

    // ==================== 任务提交 API ====================

    /**
     * 提交异步任务，不关心结果（默认 category=SAFE_TO_CANCEL）
     */
    public void submit(Runnable task) {
        submit(task, TaskCategory.SAFE_TO_CANCEL);
    }

    /**
     * 提交异步任务，不关心结果
     */
    public void submit(Runnable task, TaskCategory category) {
        TrackedTask tracked = new TrackedTask(category);
        String taskId = tracked.id;
        taskRegistry.put(taskId, tracked);

        executor.execute(() -> {
            try {
                task.run();
            } finally {
                taskRegistry.remove(taskId);
            }
        });
    }

    /**
     * 提交异步任务，返回 Future
     */
    public <T> Future<T> submit(Callable<T> task) {
        return submit(task, TaskCategory.SAFE_TO_CANCEL);
    }

    /**
     * 提交异步任务，返回 Future
     */
    public <T> Future<T> submit(Callable<T> task, TaskCategory category) {
        TrackedTask tracked = new TrackedTask(category);
        String taskId = tracked.id;
        taskRegistry.put(taskId, tracked);

        Future<T> future = executor.submit(task);
        FutureWrapper<T> wrapped = new FutureWrapper<>(future, taskId, taskRegistry);
        tracked.future = wrapped;
        return wrapped;
    }

    /**
     * 提交异步任务，完成后在主线程回调
     * <p>
     * 回调通过 MainThreadDispatcher 调度到主线程执行。
     *
     * @param task     后台任务
     * @param callback 结果回调（主线程执行）
     * @param <T>      结果类型
     */
    public <T> void submitAndCallback(Callable<T> task, Consumer<T> callback) {
        submitAndCallback(task, callback, TaskCategory.SAFE_TO_CANCEL);
    }

    /**
     * 提交异步任务，完成后在主线程回调
     */
    public <T> void submitAndCallback(Callable<T> task, Consumer<T> callback, TaskCategory category) {
        DebugLogger.info(LogType.ASYNC, "[ASYNC] Submitting task to executor '{}'", name);
        executor.submit(() -> {
            try {
                DebugLogger.info(LogType.ASYNC, "[ASYNC] Task started on executor '{}'", name);
                T result = task.call();
                DebugLogger.info(LogType.ASYNC, "[ASYNC] Task completed on executor '{}', dispatching callback to main thread", name);
                MainThreadDispatcher.execute(() -> {
                    DebugLogger.info(LogType.ASYNC, "[ASYNC] Executing callback on main thread");
                    callback.accept(result);
                    DebugLogger.info(LogType.ASYNC, "[ASYNC] Callback executed successfully");
                });
            } catch (Exception e) {
                DebugLogger.error("[ASYNC] Task failed on executor '{}'", e, name);
            }
        });
    }

    /**
     * 提交异步任务，阻塞等待结果（带超时）
     * <p>
     * 仅在已知在后台线程时使用，不要在主线程调用。
     *
     * @param task      后台任务
     * @param timeoutMs 超时毫秒数
     * @param <T>       结果类型
     * @return 结果，超时返回 null
     */
    public <T> T submitAndJoin(Callable<T> task, long timeoutMs) {
        try {
            Future<T> future = executor.submit(task);
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            Constants.LOG.warn("Hassium: Async task '{}' timed out after {}ms", name, timeoutMs);
            return null;
        } catch (Exception e) {
            Constants.LOG.error("Hassium: Async task '{}' failed", name, e);
            return null;
        }
    }

    /**
     * 提交多个异步任务，阻塞等待全部完成（带超时）
     *
     * @param tasks     任务列表
     * @param timeoutMs 超时毫秒数
     */
    public void submitAllAndJoin(List<Runnable> tasks, long timeoutMs) {
        if (tasks.isEmpty()) return;

        List<Future<?>> futures = new ArrayList<>(tasks.size());
        for (Runnable task : tasks) {
            futures.add(executor.submit(task));
        }

        long deadline = System.currentTimeMillis() + timeoutMs;
        for (Future<?> future : futures) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                Constants.LOG.warn("Hassium: Async batch '{}' timed out, {} tasks remaining",
                        name, futures.size() - futures.indexOf(future));
                break;
            }
            try {
                future.get(remaining, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                Constants.LOG.warn("Hassium: Async batch '{}' task timed out", name);
                break;
            } catch (Exception e) {
                Constants.LOG.error("Hassium: Async batch '{}' task failed", name, e);
            }
        }
    }

    /**
     * 提交异步任务并阻塞等待，带超时后放弃
     * <p>
     * 适用于 flush 等需要同步等待但有超时限制的场景。
     *
     * @param task      后台任务
     * @param timeoutMs 超时毫秒数
     * @return true=完成, false=超时
     */
    public boolean submitAndWait(Runnable task, long timeoutMs) {
        try {
            Future<?> future = executor.submit(task);
            future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            return false;
        } catch (Exception e) {
            Constants.LOG.error("Hassium: Async task '{}' failed", name, e);
            return false;
        }
    }

    // ==================== 任务取消 ====================

    /**
     * 取消指定类别的所有尚未完成的任务
     *
     * @param category 要取消的任务类别
     * @return 取消的任务数量
     */
    public int cancelAll(TaskCategory category) {
        int cancelled = 0;
        Iterator<TrackedTask> it = taskRegistry.values().iterator();
        while (it.hasNext()) {
            TrackedTask tracked = it.next();
            if (tracked.category == category) {
                if (tracked.future != null && tracked.future.cancel(true)) {
                    cancelled++;
                }
                it.remove();
            }
        }
        if (cancelled > 0) {
            Constants.LOG.info("Hassium: Cancelled {} '{}' tasks from executor '{}'", cancelled, category, name);
        }
        return cancelled;
    }

    // ==================== 生命周期 ====================

    /**
     * 获取客户端执行器实例
     */
    public static HassiumTaskExecutor getClient() {
        return clientInstance;
    }

    /**
     * 获取服务端执行器实例
     */
    public static HassiumTaskExecutor getServer() {
        return serverInstance;
    }

    /**
     * 初始化客户端执行器（连接时调用）
     *
     * @param threads 平台线程数上限（虚拟线程模式下忽略）
     */
    public static void initClient(int threads) {
        shutdownClient();
        clientInstance = new HassiumTaskExecutor("client", threads);
    }

    /**
     * 初始化服务端执行器（服务器启动时调用）
     *
     * @param threads 平台线程数上限
     */
    public static void initServer(int threads) {
        shutdownServer();
        serverInstance = new HassiumTaskExecutor("server", threads);
    }

    /**
     * 关闭客户端执行器（断开连接时调用）
     * <p>
     * 先关闭执行器，等待最多 timeoutMs 毫秒，超时则强制结束。
     */
    public static void shutdownClient(long timeoutMs) {
        if (clientInstance != null) {
            clientInstance.shutdown(timeoutMs);
            clientInstance = null;
        }
    }

    /**
     * 关闭客户端执行器（默认超时 3 秒）
     */
    public static void shutdownClient() {
        shutdownClient(3000);
    }

    /**
     * 关闭服务端执行器（服务器关闭时调用）
     */
    public static void shutdownServer() {
        if (serverInstance != null) {
            serverInstance.close();
            serverInstance = null;
        }
    }

    // ==================== 状态 ====================

    /**
     * 获取待处理任务数（近似值）
     */
    public int getPendingTaskCount() {
        if (executor instanceof java.util.concurrent.ThreadPoolExecutor tpe) {
            return tpe.getQueue().size();
        }
        return -1; // 虚拟线程执行器无法获取
    }

    /**
     * 获取注册表中的任务数
     */
    public int getRegisteredTaskCount() {
        return taskRegistry.size();
    }

    /**
     * 是否使用虚拟线程
     */
    public boolean isVirtual() {
        return virtual;
    }

    /**
     * 获取执行器名称
     */
    public String getName() {
        return name;
    }

    /**
     * 执行器是否正在运行（未关闭，可接受新任务）
     */
    public boolean isRunning() {
        return !executor.isShutdown();
    }

    /**
     * 执行器是否已关闭
     */
    public boolean isShutdown() {
        return executor.isShutdown();
    }

    // ==================== 内部 ====================

    private void shutdown(long timeoutMs) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
                Constants.LOG.warn("Hassium: Executor '{}' forced shutdown after {}ms", name, timeoutMs);
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        taskRegistry.clear();
        Constants.LOG.info("Hassium: Executor '{}' shut down", name);
    }

    @Override
    public void close() {
        shutdown(3000);
    }

    // ==================== 内部类 ====================

    /**
     * 带类别标记的任务记录
     */
    private static class TrackedTask {
        final String id = java.util.UUID.randomUUID().toString();
        final TaskCategory category;
        volatile Future<?> future;

        TrackedTask(TaskCategory category) {
            this.category = category;
        }
    }

    /**
     * Future 包装器，完成时自动从注册表移除
     */
    private static class FutureWrapper<T> implements Future<T> {
        private final Future<T> delegate;
        private final String taskId;
        private final ConcurrentHashMap<String, TrackedTask> registry;
        private volatile boolean removed;

        FutureWrapper(Future<T> delegate, String taskId, ConcurrentHashMap<String, TrackedTask> registry) {
            this.delegate = delegate;
            this.taskId = taskId;
            this.registry = registry;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean result = delegate.cancel(mayInterruptIfRunning);
            if (result) removeFromRegistry();
            return result;
        }

        @Override
        public boolean isCancelled() {
            return delegate.isCancelled();
        }

        @Override
        public boolean isDone() {
            boolean done = delegate.isDone();
            if (done) removeFromRegistry();
            return done;
        }

        @Override
        public T get() throws InterruptedException, ExecutionException {
            try {
                return delegate.get();
            } finally {
                removeFromRegistry();
            }
        }

        @Override
        public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            try {
                return delegate.get(timeout, unit);
            } finally {
                removeFromRegistry();
            }
        }

        private void removeFromRegistry() {
            if (!removed) {
                removed = true;
                registry.remove(taskId);
            }
        }
    }
}
