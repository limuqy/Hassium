package io.github.limuqy.mc.hassium.compat;

import net.minecraft.network.chat.Component;

/**
 * 断开连接版本兼容层
 * <p>
 * 1.20.6-: onDisconnect(Component reason) / Connection.disconnect(Component)
 * 1.21.1+: onDisconnect(DisconnectionDetails details) / Connection.disconnect(DisconnectionDetails)
 */
public final class DisconnectCompat {
    private DisconnectCompat() {}

    /**
     * 从 onDisconnect 回调参数提取原因 Component
     *
     * @param disconnectArg onDisconnect 方法的第一个参数
     * @return 断开原因的 Component
     */
    public static Component getReason(Object disconnectArg) {
#if MC_VER < MC_1_21_1
        return (Component) disconnectArg;
#else
        return ((net.minecraft.network.DisconnectionDetails) disconnectArg).reason();
#endif
    }

    /**
     * 以原因断开连接（{@code <1.21.1} {@code disconnect(Component)} /
     * {@code >=1.21.1} {@code disconnect(DisconnectionDetails)} 双形态收口）。
     */
    public static void disconnect(net.minecraft.network.Connection connection, Component reason) {
#if MC_VER < MC_1_21_1
        connection.disconnect(reason);
#else
        connection.disconnect(new net.minecraft.network.DisconnectionDetails(reason));
#endif
    }

    /**
     * 提取连接的断开原因（{@code getDisconnectedReason()} vs
     * {@code getDisconnectionDetails().reason()}）；未断开返回 null。
     */
    public static Component disconnectedReason(net.minecraft.network.Connection connection) {
#if MC_VER < MC_1_21_1
        return connection.getDisconnectedReason();
#else
        net.minecraft.network.DisconnectionDetails details = connection.getDisconnectionDetails();
        return details == null ? null : details.reason();
#endif
    }
}
