package io.github.limuqy.mc.hassium.network.dataplane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

/**
 * Task 3 — {@link DataPlaneSessionRegistry} 单元测试（RED）。
 *
 * <p>验证 registry 在 (playerId, epoch) 维度对会话分桶、lease 起算/过期、过期即关闭会话。
 * 不依赖网络 socket，仅用 {@link ReliableDatagramSession#close()} 观测关闭副作用。
 */
final class DataPlaneSessionRegistryTest {

    private static final InetSocketAddress REMOTE = new InetSocketAddress("127.0.0.1", 51820);

    private static ReliableDatagramSession session(UUID player, long epoch, int endpointId) {
        // 16-byte key: per-server-bind SecureRandom token 由 server 持有；测试用确定性占位
        byte[] key = new byte[16];
        key[0] = (byte) endpointId;
        UdpEndpoint ep = UdpEndpoint.builder()
                .role(UdpEndpoint.Role.SERVER)
                .localAddress(new InetSocketAddress("127.0.0.1", 18000 + endpointId))
                .build();
        AtomicBoolean sinkCalled = new AtomicBoolean();
        var s = new ReliableDatagramSession(player, epoch, ep, REMOTE, key,
                datagram -> sinkCalled.set(true));
        s.receiveHandler(r -> {});
        return s;
    }

    @Test
    void sessionsAreSeparatedByUuidEpochAndEndpoint() {
        DataPlaneSessionRegistry registry = new DataPlaneSessionRegistry();
        UUID player = UUID.randomUUID();
        ReliableDatagramSession one = session(player, 10L, 1);
        ReliableDatagramSession two = session(player, 10L, 2);

        registry.register(one);
        registry.register(two);

        assertEquals(List.of(one, two), registry.sessions(player, 10L));
        assertTrue(registry.sessions(player, 11L).isEmpty());
    }

    @Test
    void primaryCloseRetainsOnlyLeaseThenClosesSessions() {
        DataPlaneSessionRegistry registry = new DataPlaneSessionRegistry();
        UUID player = UUID.randomUUID();
        ReliableDatagramSession s = session(player, 1L, 1);
        registry.register(s);

        registry.onPrimaryDisconnect(player, 1L, 100L, 1_000L);
        assertTrue(s.isLeaseActive(500L), "lease should be active within 1000ms window");
        assertTrue(s.isLeaseActive(1_099L));

        registry.expireLeases(1_100L);
        assertTrue(s.isClosed(), "lease expiry at 1100ms must close the session");
    }

    @Test
    void replaceEpochClosesOldEpochSessionsAndUnlocksNewEpochOnSameUuid() {
        DataPlaneSessionRegistry registry = new DataPlaneSessionRegistry();
        UUID player = UUID.randomUUID();
        ReliableDatagramSession old = session(player, 1L, 1);
        registry.register(old);

        registry.replaceEpoch(player, 2L);
        assertTrue(old.isClosed(), "old-epoch session must be closed on replaceEpoch");
        assertTrue(registry.sessions(player, 1L).isEmpty());
        // new-epoch registrations from same player id are accepted independently
        ReliableDatagramSession fresh = session(player, 2L, 1);
        registry.register(fresh);
        assertEquals(List.of(fresh), registry.sessions(player, 2L));
    }

    @Test
    void onPrimaryDisconnectUnknownUuidIsNoOp() {
        DataPlaneSessionRegistry registry = new DataPlaneSessionRegistry();
        // 未知 UUID 不得抛异常，也不触发任何会话关闭
        registry.onPrimaryDisconnect(UUID.randomUUID(), 7L, 0L, 5_000L);
        registry.expireLeases(6_000L);
    }
}
