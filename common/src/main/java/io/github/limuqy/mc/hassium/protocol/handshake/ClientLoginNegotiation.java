package io.github.limuqy.mc.hassium.protocol.handshake;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 登录期能力握手——客户端协商结果（单连接进程内静态态）。
 * <p>
 * 应答 {@code login_hello} 时写入协商位（服务端声明位 × 客户端声明位按位与）；
 * Play 期各客户端链路（ZSTD 安装、压缩 ready ACK 等）按位查询。
 * 新连接握手会覆写；断连 hygiene 由 {@code ClientLifecycleHelper.cleanupOnDisconnect} 清零。
 */
public final class ClientLoginNegotiation {

    private static final AtomicInteger NEGOTIATED = new AtomicInteger(0);

    private ClientLoginNegotiation() {
    }

    /** 记录协商结果（客户端 mixin 应答时调用）。 */
    public static void set(int negotiatedCaps) {
        NEGOTIATED.set(negotiatedCaps);
    }

    /** 当前协商位（0 = 无协商/原版路径）。 */
    public static int current() {
        return NEGOTIATED.get();
    }

    /** 断连清零。 */
    public static void clear() {
        NEGOTIATED.set(0);
    }
}
