package io.github.limuqy.mc.hassium.network.dataplane;

import io.github.limuqy.mc.hassium.config.HassiumConfig;
import org.junit.jupiter.api.Test;

import java.net.DatagramSocket;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同一 UDP listener 的 reachable candidates 必须串行尝试；本地 socket 成功 bind 不能冒充服务端认证成功。
 */
final class UdpClientCandidateFallbackTest {

    private static final UUID PLAYER = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @Test
    void blackholeCandidateFallsBackOnlyAfterAuthenticatedBindAck() throws Exception {
        int serverPort = reserveUdpPort();
        int blackholePort = reserveUdpPort();
        DataPlaneUdpServer server = DataPlaneUdpServer.forTest(List.of(
                new HassiumConfig.UdpListenerConfig("127.0.0.1", serverPort, 100, List.of(
                        new HassiumConfig.ReachableEndpoint("127.0.0.1", blackholePort, 100),
                        new HassiumConfig.ReachableEndpoint("127.0.0.1", serverPort, 10)))));
        DataPlaneClientBundle bundle = new DataPlaneClientBundle();
        try {
            server.bind();
            bundle.connectAndBind(PLAYER, 7L, server.getSessionToken(), List.of(
                    new UdpDataPlaneHandshakeTail.UdpListenerGroup(0, 100, List.of(
                            new UdpDataPlaneHandshakeTail.UdpReachableEndpoint("127.0.0.1", blackholePort, 100),
                            new UdpDataPlaneHandshakeTail.UdpReachableEndpoint("127.0.0.1", serverPort, 10)))));
            assertFalse(bundle.isBound(), "本地 UDP socket 已建立但尚未收到认证 BIND_ACK 时不能路由 bulk");

            long deadline = System.currentTimeMillis() + 3_000L;
            while (!bundle.isBound() && System.currentTimeMillis() < deadline) {
                long now = System.currentTimeMillis();
                bundle.tick(now);
                DataPlaneUdpServer.tick(now);
                Thread.sleep(10L);
            }

            assertTrue(bundle.isBound(), "首个黑洞 candidate 超时后必须经同 endpointId 的第二候选完成认证绑定");
        } finally {
            bundle.shutdown();
            server.shutdown();
        }
    }

    private static int reserveUdpPort() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
