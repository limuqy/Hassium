package io.github.limuqy.mc.hassium.compat;

import net.minecraft.network.chat.Component;

/**
 * onDisconnect 签名版本兼容层
 * <p>
 * 1.20.6-: onDisconnect(Component reason)
 * 1.21.1+: onDisconnect(DisconnectionDetails details)
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
}
