package io.github.limuqy.mc.hassium.metrics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §14 第 4 步：send-side per-portIdx bulk 计数 prop 测试。
 * 验证 recordBulkSentDataByPort 累加 frames/bytes 维度独立、跨 portIdx 互不影响、reset 后回到 0。
 */
class SendPerPortMetricsTest {

    private final HassiumMetricsImpl m = new HassiumMetricsImpl();

    @AfterEach
    void cleanup() {
        m.reset();
    }

    @Test
    @DisplayName("recordBulkSentDataByPort 累加 frames+bytes 各自维度")
    void accumulates() {
        m.recordBulkSentDataByPort(1, 100L);
        m.recordBulkSentDataByPort(1, 50L);
        m.recordBulkSentDataByPort(1, 0L); // 0 字节不计 bytes 但 frame 仍计
        m.recordBulkSentDataByPort(2, 200L);

        assertEquals(3, m.getBulkSentFramesByPort(1), "portIdx=1 帧累计 3");
        assertEquals(150L, m.getBulkSentBytesByPort(1), "portIdx=1 字节累计 150");
        assertEquals(1, m.getBulkSentFramesByPort(2));
        assertEquals(200L, m.getBulkSentBytesByPort(2));
    }

    @Test
    @DisplayName("snapshotSendPerPort 返回 key 升序视图")
    void snapshotSorted() {
        m.recordBulkSentDataByPort(3, 10L);
        m.recordBulkSentDataByPort(1, 20L);
        m.recordBulkSentDataByPort(2, 30L);

        var snap = m.snapshotSendPerPort();
        assertEquals(java.util.List.of(1, 2, 3), new java.util.ArrayList<>(snap.keySet()),
                "key 应按升序");
        assertArrayEquals(new long[]{1, 20L}, snap.get(1));
        assertArrayEquals(new long[]{1, 30L}, snap.get(2));
        assertArrayEquals(new long[]{1, 10L}, snap.get(3));
    }

    @Test
    @DisplayName("getBulkSentByPort 不存在 portIdx 返回 0")
    void absentPortIsZero() {
        assertEquals(0L, m.getBulkSentFramesByPort(99));
        assertEquals(0L, m.getBulkSentBytesByPort(99));
    }

    @Test
    @DisplayName("recordBulkSentDataByPort 拒绝非正 portIdx")
    void rejectsNonPositivePortIdx() {
        m.recordBulkSentDataByPort(0, 100L);
        m.recordBulkSentDataByPort(-1, 100L);
        assertEquals(0L, m.getBulkSentFramesByPort(0));
        assertEquals(0L, m.getBulkSentFramesByPort(-1));
        assertTrue(m.snapshotSendPerPort().isEmpty());
    }

    @Test
    @DisplayName("reset 清空 send per-portIdx 计数")
    void resetClearsPerPort() {
        m.recordBulkSentDataByPort(1, 100L);
        m.recordBulkSentDataByPort(2, 50L);
        assertFalse(m.snapshotSendPerPort().isEmpty());

        m.reset();

        assertEquals(0L, m.getBulkSentFramesByPort(1));
        assertEquals(0L, m.getBulkSentBytesByPort(1));
        assertEquals(0L, m.getBulkSentFramesByPort(2));
        assertEquals(0L, m.getBulkSentBytesByPort(2));
        assertTrue(m.snapshotSendPerPort().isEmpty());
    }
}
