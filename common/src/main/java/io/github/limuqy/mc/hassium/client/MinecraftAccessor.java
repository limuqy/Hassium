package io.github.limuqy.mc.hassium.client;

import net.minecraft.network.Connection;

/**
 * M3 仅网关登录（主连接失效恢复，仅网关登录）：{@code Minecraft.pendingConnection} 访问器。
 * <p>
 * 由 {@code MixinMinecraft} 实现。仅网关登录会话把本地 Connection 挂到 pendingConnection
 * 后，{@code Minecraft.runTick}（level==null 分支）自动泵 {@code Connection.tick}——
 * 与单机 world load 同款泵语义（pendingConnection 在玩期保留亦与单机一致）。
 */
public interface MinecraftAccessor {

    void hassium$setPendingConnection(Connection connection);
}
