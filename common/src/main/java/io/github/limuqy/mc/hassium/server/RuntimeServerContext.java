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
 */
public final class RuntimeServerContext {

    private static volatile boolean dedicatedServer = false;

    private RuntimeServerContext() {
    }

    public static void setDedicatedServer(boolean dedicated) {
        dedicatedServer = dedicated;
    }

    /** @return 当前进程是否运行专用服务器（dedicated server） */
    public static boolean isDedicatedServerContext() {
        return dedicatedServer;
    }
}
