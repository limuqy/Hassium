package io.github.limuqy.mc.hassium.server;

/**
 * 当前进程运行的服务器类型上下文。
 * <p>
 * 由 {@code MixinMinecraftServer.onServerInit} 在 {@code runServer} 启动时写入；
 * 供依赖服务器类型的 common 代码（如存储格式门控）读取，避免各版本
 * {@code MinecraftServer.getServer()} API 差异（1.20.1 段无该静态方法）。
 * <p>
 * 默认 {@code false}：标志未写入（客户端上下文 / 启动早期 / 测试）时视为
 * 非专用服务器，任何调用方都应保守走原版行为，绝不改写存档。
 * <p>
 * <b>影子端代际（shadowGeneration）</b>：影子服务端关停是异步的（saver 线程），
 * 而新会话的影子端允许在旧会话 saveAll 落盘期间并发创建（T5cShadowReady 设计）。
 * 若旧会话关停末尾无条件 {@code setShadowServer(false)}，会清掉新会话在
 * {@code initServer} 里置位的标志 → 新会话的 RegionFile 读写 gate 全部失效
 * （原版直读 126 → "invalid chunk stream version 126" 风暴；原版格式写入混入
 * 126 文件）。代际计数：每次置 true 递增，关停末尾仅当代际未变（无新端接管）
 * 时才复位——两处转换均在锁内完成，杜绝检查与置位之间的交错。
 */
public final class RuntimeServerContext {

    private static volatile boolean dedicatedServer = false;
    private static volatile boolean shadowServer = false;
    private static int shadowGeneration = 0;

    private RuntimeServerContext() {
    }

    public static void setDedicatedServer(boolean dedicated) {
        dedicatedServer = dedicated;
    }

    /** @return 当前进程是否运行专用服务器（dedicated server） */
    public static boolean isDedicatedServerContext() {
        return dedicatedServer;
    }

    /**
     * 置位/复位影子服务端上下文（客户端进程内 {@code ShadowSeedServer} 装配/关停时）。
     * <p>
     * 影子端固定使用 Hassium 压缩存储（type 126 + chunkHash 落盘），不受
     * {@code storage.enabled} / 服务器类型门控约束。
     * <p>
     * 置 true 时递增代际（新影子端接管）；置 false 不递增（仅复位）。
     */
    public static void setShadowServer(boolean shadow) {
        synchronized (RuntimeServerContext.class) {
            shadowServer = shadow;
            if (shadow) {
                shadowGeneration++;
            }
        }
    }

    /** @return 当前影子端代际（每次影子端 initServer 置 true 时递增） */
    public static int getShadowGeneration() {
        synchronized (RuntimeServerContext.class) {
            return shadowGeneration;
        }
    }

    /**
     * 仅当代际仍为 {@code generation}（自捕获以来没有新影子端接管）时复位标志。
     * <p>
     * 旧会话异步关停末尾调用：捕获代际于关停开始处，若期间新会话已
     * {@code setShadowServer(true)}（代际已变）则不复位，避免误清新会话的
     * 存储格式门控（数据损坏根因）。锁内校验+置位，无检查-置位竞态窗口。
     */
    public static void clearShadowServerIfCurrentGeneration(int generation) {
        synchronized (RuntimeServerContext.class) {
            if (shadowGeneration == generation) {
                shadowServer = false;
            }
        }
    }

    /** @return 当前进程是否运行影子服务端（客户端进程内的世界管理后端） */
    public static boolean isShadowServerContext() {
        return shadowServer;
    }
}
