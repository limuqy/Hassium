package io.github.limuqy.mc.hassium.network.dataplane;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 10b §2.1 — UDP data-plane 切断验证。
 *
 * <p>断言 {@link DataPlaneUdpServer} 运行时使用的 Netty transport 类型集合：
 * <ul>
 *   <li>必须含 UDP（{@code NioDatagramChannel}）——证明生产数据面走 UDP/KCP；</li>
 *   <li>不得含 PoC 期间的 TCP data-plane transport（{@code NioServerSocketChannel}）——
 *       证明 PoC TCP 多通道监听已下线（plan §1082-1090/rollout §2.2）。</li>
 * </ul>
 * RED 在 hook 未引入前编译失败；引入后 GREEN 时表示 PoC TCP listener 已从 {@link DataPlaneUdpServer}
 * 删除（{@code DataPlaneServer} façade 不再启动 {@code ServerBootstrap}）。
 */
class DataPlaneTransportCutoverTest {

    /**
     * 历史 PoC data-plane 使用的 TCP 监听 channel 类完全限定名 —— 仅作「不在 UDP 运行时集合」
     * 的负向断言对象出现，不引用其类型以避免被误绑存活。
     */
    private static final String POC_TCP_TRANSPORT =
            "io.netty.channel.socket.nio.NioServerSocketChannel";

    @Test
    @DisplayName("UDP data-plane 运行时 transport 集合含 NioDatagramChannel")
    void runtimeTransportIncludesUdp() {
        Set<String> names = DataPlaneUdpServer.runtimeTransportNamesForTest();
        assertTrue(
                names.contains("io.netty.channel.socket.nio.NioDatagramChannel"),
                () -> "UDP data-plane 必须 NioDatagramChannel; 实际 = " + names);
    }

    @Test
    @DisplayName("UDP data-plane 运行时 transport 集合不含 PoC TCP NioServerSocketChannel")
    void runtimeTransportExcludesPocTcpServerSocket() {
        Set<String> names = DataPlaneUdpServer.runtimeTransportNamesForTest();
        assertFalse(
                names.contains(POC_TCP_TRANSPORT),
                () -> "PoC TCP data-plane listener 已退役，不应出现在运行时 transport 集合: " + names);
    }
}
