package io.github.limuqy.mc.hassium.network.dataplane;

import io.github.limuqy.mc.hassium.metrics.NetworkStats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 4 — {@link DataPlaneUdpServer#tryRouteBulk(UUID, int, byte[])} 集成测。
 *
 * <p>不绑真实 UDP socket；经测试 seam {@link DataPlaneUdpServer#injectBoundSessionsForTest(UUID, List)}
 * 把 fake {@link BulkRouteTarget} 列表直接挂到 server，验证：
 * <ul>
 *   <li>有健康会话 → 返回 true 至少一次 + fake enqueue 已发生；</li>
 *   <li>调 {@link DataPlaneUdpServer#removeSessionsForTest(UUID)} 后 → 恒返回 false。</li>
 *   <li>无会话原生路径 → 恒 false。</li>
 * </ul>
 */
class UdpTryRouteBulkTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final int TYPE = DataPlaneFrame.TYPE_BULK_COMPRESSED_CHUNK;
    private static final byte[] PAY = new byte[] {1, 2, 3};
    private boolean statsWasOn;

    @BeforeEach
    void setUp() {
        DataPlaneUdpServer.forTest(new DataPlanePoCConfig.Endpoint[] {
                new DataPlanePoCConfig.Endpoint("127.0.0.1", 0, 50, "127.0.0.1", 0),
                new DataPlanePoCConfig.Endpoint("127.0.0.1", 0, 50, "127.0.0.1", 0),
        });
        DataPlaneUdpServer.bind();
        statsWasOn = NetworkStats.isEnabled();
        NetworkStats.setEnabled(true);
    }

    @AfterEach
    void tearDown() {
        NetworkStats.setEnabled(statsWasOn);
        DataPlaneUdpServer.shutdown();
    }

    @Test
    @DisplayName("健康会话 → tryRouteBulk 返回 true at least once + fake enqueue ≥3 bytes；remove → false")
    void tryRouteBulkReturnsTrueForUdpAndFalseForPrimaryFallback() {
        // weight=10000 vs PRIMARY_WEIGHT=100 → share 模式 99% 命中 DATA；50 帧必触发 true。
        FakeSessionTarget heavy = new FakeSessionTarget(0, 10_000, 10);
        assertTrue(DataPlaneUdpServer.isBound(), "server bound");
        DataPlaneUdpServer.injectBoundSessionsForTest(PLAYER, List.of(heavy));

        boolean anyTrue = false;
        for (int i = 0; i < 50; i++) {
            if (DataPlaneUdpServer.tryRouteBulk(PLAYER, TYPE, PAY)) anyTrue = true;
        }
        assertTrue(anyTrue, "至少一次 DATA_SENT 必须发生");
        assertTrue(heavy.enqueuedBytes >= 3, "至少一次 enqueue 已写入 payload bytes");

        DataPlaneUdpServer.removeSessionsForTest(PLAYER);
        for (int i = 0; i < 5; i++) {
            assertFalse(DataPlaneUdpServer.tryRouteBulk(PLAYER, TYPE, PAY), "无 session 后 false");
        }
    }

    @Test
    @DisplayName("无会话：恒返回 false")
    void noSessionsReturnsFalse() {
        for (int i = 0; i < 10; i++) {
            assertFalse(DataPlaneUdpServer.tryRouteBulk(PLAYER, TYPE, PAY));
        }
    }

    static class FakeSessionTarget implements BulkRouteTarget {
        final int eid; final int w; final long srtt;
        int enqueuedBytes = 0;
        FakeSessionTarget(int eid, int w, long srtt) { this.eid = eid; this.w = w; this.srtt = srtt; }
        @Override public int endpointId() { return eid; }
        @Override public int weight() { return w; }
        @Override public boolean isHealthy() { return srtt <= 1_000; }
        @Override public boolean isWritable() { return true; }
        @Override public boolean isClosed() { return false; }
        @Override public boolean isLeaseActive(long nowMs) { return true; }
        @Override public ReliableDatagramSession.Metrics metrics() {
            return new ReliableDatagramSession.Metrics(srtt, 0, 0, true);
        }
        @Override public boolean enqueueAuthenticated(int type, byte[] payload) {
            enqueuedBytes += payload == null ? 0 : payload.length;
            return true;
        }
    }
}
