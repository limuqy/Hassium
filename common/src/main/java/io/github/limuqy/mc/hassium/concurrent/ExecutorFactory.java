package io.github.limuqy.mc.hassium.concurrent;

import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 线程池工厂
 * <p>
 * 根据 Java 版本自动选择平台线程或虚拟线程：
 * - Java 17：平台线程池，daemon，最多 16 线程
 * - Java 21+：虚拟线程，按需创建，无需限制数量
 * <p>
 * 虚拟线程通过反射调用，避免 Java 17 编译错误。
 */
public final class ExecutorFactory {

    private static final boolean VIRTUAL_AVAILABLE = detectVirtualThreads();
    private static final Method VIRTUAL_THREAD_BUILDER;
    private static final Method VIRTUAL_NAME_METHOD;
    private static final Method VIRTUAL_FACTORY_METHOD;
    private static final Method NEW_THREAD_PER_TASK_EXECUTOR;

    static {
        Method builder = null;
        Method nameMethod = null;
        Method factoryMethod = null;
        Method newExecutor = null;

        if (VIRTUAL_AVAILABLE) {
            try {
                // Thread.ofVirtual() -> Thread.Builder.OfVirtual
                builder = Thread.class.getMethod("ofVirtual");
                Class<?> builderOfVirtual = builder.getReturnType();

                // .name(prefix, start)
                nameMethod = builderOfVirtual.getMethod("name", String.class, long.class);

                // .factory()
                factoryMethod = builderOfVirtual.getMethod("factory");

                // Executors.newThreadPerTaskExecutor(ThreadFactory)
                newExecutor = Executors.class.getMethod("newThreadPerTaskExecutor", ThreadFactory.class);
            } catch (Exception e) {
                // 反射失败，回退到平台线程
                builder = null;
            }
        }

        VIRTUAL_THREAD_BUILDER = builder;
        VIRTUAL_NAME_METHOD = nameMethod;
        VIRTUAL_FACTORY_METHOD = factoryMethod;
        NEW_THREAD_PER_TASK_EXECUTOR = newExecutor;
    }

    private ExecutorFactory() {}

    /**
     * 检测当前 JVM 是否支持虚拟线程
     */
    private static boolean detectVirtualThreads() {
        try {
            Thread.class.getMethod("isVirtual");
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * 是否支持虚拟线程
     */
    public static boolean isVirtualThreadAvailable() {
        return VIRTUAL_AVAILABLE && VIRTUAL_THREAD_BUILDER != null;
    }

    /**
     * 创建线程池
     *
     * @param name    线程名前缀（如 "cache-save"、"light-recompute"）
     * @param threads 平台线程模式下的线程数；虚拟线程模式下忽略
     * @return ExecutorService
     */
    public static ExecutorService create(String name, int threads) {
        if (isVirtualThreadAvailable()) {
            return createVirtual(name, threads);
        } else {
            return createPlatform(name, threads);
        }
    }

    /**
     * 创建平台线程池（Java 17）
     * <p>
     * daemon 线程，线程数由配置控制。
     */
    private static ExecutorService createPlatform(String name, int threads) {
        int poolSize = Math.max(threads, 1);
        ThreadFactory factory = new HassiumThreadFactory(name);
        return Executors.newFixedThreadPool(poolSize, factory);
    }

    /**
     * 创建虚拟线程执行器（Java 21+）
     * <p>
     * 通过反射调用 Thread.ofVirtual().name(prefix, 0).factory()，
     * 然后使用 Executors.newThreadPerTaskExecutor(factory)。
     * 每个任务一个虚拟线程，无需限制数量。
     */
    private static ExecutorService createVirtual(String name, int fallbackThreads) {
        try {
            Object builder = VIRTUAL_THREAD_BUILDER.invoke(null);
            Object namedBuilder = VIRTUAL_NAME_METHOD.invoke(builder, "hassium-" + name + "-", 0L);
            ThreadFactory factory = (ThreadFactory) VIRTUAL_FACTORY_METHOD.invoke(namedBuilder);
            return (ExecutorService) NEW_THREAD_PER_TASK_EXECUTOR.invoke(null, factory);
        } catch (Exception e) {
            // 反射失败，回退到平台线程
            return createPlatform(name, fallbackThreads);
        }
    }

    /**
     * Hassium 平台线程工厂
     * <p>
     * 创建 daemon 线程，统一命名格式：hassium-{name}-{id}
     */
    private static final class HassiumThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(0);
        private final String namePrefix;

        HassiumThreadFactory(String name) {
            this.namePrefix = "hassium-" + name + "-";
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, namePrefix + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }
}
