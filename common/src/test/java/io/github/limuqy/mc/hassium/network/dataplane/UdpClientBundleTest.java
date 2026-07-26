package io.github.limuqy.mc.hassium.network.dataplane;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 5 — {@link DataPlaneClientBundle} demux seam 测试。
 *
 * <p>绕开真实 UDP socket_bind，直接走 {@link DataPlaneClientBundle#receiveForTest(int, byte[])}
 * 的同包测试 seam，验证 chunk 帧进入注入的 ChunkDispatcher；并校验 PoC 兼容的全局静态
 * 计数器在 frame 到达后正确累计。
 */
class UdpClientBundleTest {

    private DataPlaneClientBundle bundle;
    private boolean statsWasOn;

    @BeforeEach
    void setUp() {
        DataPlaneClientBundle.resetDataBulkCounters();
        bundle = new DataPlaneClientBundle();
    }

    @AfterEach
    void tearDown() {
        try { bundle.shutdown(); } catch (Throwable ignored) {}
        DataPlaneClientBundle.resetDataBulkCounters();
    }

    @Test
    @DisplayName("BULK_COMPRESSED_CHUNK → 注入 dispatcher 被调用 + 全局计数器累加")
    void authChunkUsesInjectedDispatcherAndCounts() {
        AtomicReference<byte[]> received = new AtomicReference<>();
        bundle.setChunkDispatcherForTest(received::set);

        byte[] payload = new byte[] {9, 8, 7};
        bundle.receiveForTest(DataPlaneFrame.TYPE_BULK_COMPRESSED_CHUNK, payload);

        assertArrayEquals(payload, received.get(), "dispatcher 收到原始 payload");
        assertEquals(1, DataPlaneClientBundle.getBulkFramesData(), "帧计数 +1");
        assertEquals(3, DataPlaneClientBundle.getBulkBytesData(), "字节累计 = payload 长度");
    }

    @Test
    @DisplayName("非 chunk 帧 → dispatcher 不被调用；计数器不动")
    void nonChunkFrameDoesNotInvokeChunkDispatcher() {
        AtomicReference<byte[]> received = new AtomicReference<>();
        bundle.setChunkDispatcherForTest(received::set);

        bundle.receiveForTest(DataPlaneFrame.TYPE_FAILOVER_PERMIT, new byte[] {1});
        assertNull(received.get(), "failover 帧不进 chunk 路径");
        assertEquals(0, DataPlaneClientBundle.getBulkFramesData());
    }

    @Test
    @DisplayName("resetDataBulkCounters 清零所有静态计数")
    void resetClearsAllStaticCounters() {
        AtomicReference<byte[]> ignored = new AtomicReference<>();
        bundle.setChunkDispatcherForTest(ignored::set);
        bundle.receiveForTest(DataPlaneFrame.TYPE_BULK_COMPRESSED_CHUNK, new byte[10]);
        assertEquals(1, DataPlaneClientBundle.getBulkFramesData());
        assertEquals(10, DataPlaneClientBundle.getBulkBytesData());

        DataPlaneClientBundle.resetDataBulkCounters();
        assertEquals(0, DataPlaneClientBundle.getBulkFramesData());
        assertEquals(0, DataPlaneClientBundle.getBulkBytesData());
        assertTrue(DataPlaneClientBundle.snapshotPerPort().isEmpty());
    }
}
