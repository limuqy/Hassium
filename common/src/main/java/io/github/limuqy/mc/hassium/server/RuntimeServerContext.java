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
 * 影子端标志和代际只用于生命周期记账、诊断及代际保护的共享状态清理。
 * 行为隔离必须检查 MinecraftServer/ServerLevel 实例、LevelTicks 绑定归属、
 * RegionFile 目录令牌或显式传播的 ShadowAssemblyScope，禁止把进程标志当世界归属。
 */
public final class RuntimeServerContext {

    private static volatile boolean dedicatedServer = false;
    private static volatile boolean shadowServer = false;
    private static volatile net.minecraft.server.MinecraftServer activeServer;
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
     * 当前主 MinecraftServer（集成服/专用服启动时写入；停止时清空）。
     * 供登录期等无 server 参数的路径读取 {@code isPublished()}（LAN 是否已开）。
     */
    public static void setActiveServer(net.minecraft.server.MinecraftServer server) {
        activeServer = server;
    }

    /** @return 当前主 MinecraftServer；未启动/已停止时可能为 {@code null} */
    public static net.minecraft.server.MinecraftServer getActiveServer() {
        return activeServer;
    }

    /**
     * 置位/复位影子服务端上下文（客户端进程内 {@code ShadowSeedServer} 装配/关停时）。
     * <p>
     * 影子端固定使用 Hassium 压缩存储（type 126 + chunkHash 落盘），不受
     * {@code storage.enabled} / 服务器类型门控约束。
     * <p>
     * 同一实例的幂等置位；新实例装配必须先调用 beginShadowServer 分配代际。
     */
    public static void setShadowServer(boolean shadow) {
        synchronized (RuntimeServerContext.class) {
            if (shadow) {
                if (!shadowServer) {
                    shadowServer = true;
                    shadowGeneration++;
                }
            } else {
                shadowServer = false;
            }
        }
    }

    /** Called once per new instance, before WorldLoader and MinecraftServer construction. */
    public static synchronized int beginShadowServer() {
        shadowServer = true;
        return ++shadowGeneration;
    }

    /** Keep the generation check and shared-state cleanup atomic with the next assembly. */
    public static synchronized boolean finishShadowServer(int generation, Runnable cleanup) {
        if (shadowGeneration != generation) {
            return false;
        }
        cleanup.run();
        shadowServer = false;
        return true;
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
