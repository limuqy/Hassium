package io.github.limuqy.mc.hassium.network.dataplane;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 6 — failover lease 必须把旧 epoch 从新 bulk 选路中摘除，同时留存既有 KCP 队列直到 permit 过期。
 */
final class UdpLeaseRoutingTest {

    @Test
    void failoverLeaseRemovesSessionFromNewBulkRoutingButDefersCloseUntilExpiry() {
        DataPlaneSessionRegistry registry = new DataPlaneSessionRegistry();
        UUID player = UUID.randomUUID();
        ReliableDatagramSession session = session(player, 5L);
        registry.register(session);

        registry.beginFailoverLease(player, 5L, 1_100L);

        assertTrue(registry.sessionsByPlayer(player).isEmpty(),
                "旧 epoch lease 期间不得再接受新的 bulk 路由");
        assertFalse(session.isClosed(), "已接受的 KCP 帧必须保留到 lease 到期");

        registry.expireLeases(1_099L);
        assertFalse(session.isClosed());
        registry.expireLeases(1_100L);
        assertTrue(session.isClosed(), "permit 到期必须关闭旧 epoch 会话");
    }

    private static ReliableDatagramSession session(UUID player, long epoch) {
        UdpEndpoint endpoint = UdpEndpoint.builder()
                .role(UdpEndpoint.Role.SERVER)
                .localAddress(new InetSocketAddress("127.0.0.1", 18_000))
                .build();
        ReliableDatagramSession session = new ReliableDatagramSession(
                player, epoch, endpoint, new InetSocketAddress("127.0.0.1", 51_820),
                new byte[16], datagram -> { });
        session.receiveHandler(received -> { });
        return session;
    }
}
