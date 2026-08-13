package io.github.limuqy.mc.hassium.client;

import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;

/**
 * M3 仅网关登录（主连接失效恢复，仅网关登录）：{@code ConnectScreen} 私有字段访问器。
 * <p>
 * 由 {@code MixinConnectScreen} 实现（mixin 把接口挂到目标类上），业务代码 cast 接口
 * 而非 mixin 类——fabric Knot 运行时禁止直接引用 mixin 类（T10 真实运行修复，
 * {@code GatewayConnectionAccessor} 同款模式）。
 */
public interface ConnectScreenAccessor {

    /** 置空原版连接（停止 ConnectScreen.tick → handleDisconnection 复触发失败界面）。 */
    void hassium$setConnection(Connection connection);

    /** 更新连接状态文案（仅网关登录监听器 statusConsumer 回调）。 */
    void hassium$setStatus(Component status);
}
