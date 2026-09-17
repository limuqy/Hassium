package io.github.limuqy.mc.hassium.client;

import io.github.limuqy.mc.hassium.utils.DimensionKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JoinWorldFocusTest {

    @AfterEach
    void tearDown() {
        JoinWorldFocus.clear();
    }

    @Test
    @DisplayName("切比雪夫与脚下柱判定")
    void chebyshevAndStandingColumn() {
        JoinWorldFocus.setFocusChunk(4, -2);
        assertTrue(JoinWorldFocus.isStandingColumn(4, -2));
        assertFalse(JoinWorldFocus.isStandingColumn(5, -2));
        assertEquals(0, JoinWorldFocus.chebyshev(4, -2));
        assertEquals(1, JoinWorldFocus.chebyshev(5, -2));
        assertEquals(2, JoinWorldFocus.chebyshev(4, 0));
        assertTrue(JoinWorldFocus.isStandingKey(DimensionKey.key(DimensionKey.OVERWORLD, 4, -2)));
        assertFalse(JoinWorldFocus.isStandingKey(DimensionKey.key(DimensionKey.OVERWORLD, 0, 0)));
    }

    @Test
    @DisplayName("加载屏只推迟 3×3 以外；关屏后不推迟")
    void deferFarOnlyWhileLoadingScreen() {
        JoinWorldFocus.setFocusChunk(0, 0);
        assertFalse(JoinWorldFocus.shouldDeferFarChunk(0, 0, true));
        assertFalse(JoinWorldFocus.shouldDeferFarChunk(1, 1, true));
        assertTrue(JoinWorldFocus.shouldDeferFarChunk(2, 0, true));
        assertFalse(JoinWorldFocus.shouldDeferFarChunk(8, 8, false));
        JoinWorldFocus.clear();
        assertFalse(JoinWorldFocus.shouldDeferFarChunk(8, 8, true));
    }

    @Test
    @DisplayName("有焦点时脚下柱优先级低于邻居，FIFO 只作并列打破")
    void standingPriorityBeatsFifo() {
        JoinWorldFocus.setFocusChunk(0, 0);
        double standingLate = JoinWorldFocus.chunkApplyPriority(0, 0, 999);
        double neighborEarly = JoinWorldFocus.chunkApplyPriority(1, 0, 1);
        assertTrue(standingLate < neighborEarly);
        JoinWorldFocus.clear();
        assertEquals(42.0d, JoinWorldFocus.chunkApplyPriority(0, 0, 42.0d));
    }

    @Test
    @DisplayName("取批时脚下柱永远第一")
    void fillDistanceFirstPutsStandingFirst() {
        JoinWorldFocus.setFocusChunk(3, 7);
        ConcurrentHashMap<Long, String> source = new ConcurrentHashMap<>();
        source.put(DimensionKey.key(DimensionKey.OVERWORLD, 9, 9), "far");
        source.put(DimensionKey.key(DimensionKey.OVERWORLD, 3, 8), "near");
        source.put(DimensionKey.key(DimensionKey.OVERWORLD, 3, 7), "stand");
        List<Map.Entry<Long, String>> batch = new ArrayList<>();
        JoinWorldFocus.fillDistanceFirst(source, batch, 2);
        assertEquals("stand", batch.get(0).getValue());
        assertEquals(2, batch.size());
        assertFalse(batch.stream().anyMatch(e -> "stand".equals(e.getValue()) && e != batch.get(0)));
    }

    @Test
    @DisplayName("第一柱立刻回传：脚下柱，或尚无焦点时本会话第一柱")
    void firstColumnEmitsImmediately() {
        JoinWorldFocus.setFocusChunk(2, 3);
        assertTrue(JoinWorldFocus.shouldEmitImmediately(2, 3));
        assertFalse(JoinWorldFocus.shouldEmitImmediately(4, 4));
        JoinWorldFocus.clear();
        assertTrue(JoinWorldFocus.shouldEmitImmediately(8, -1));
        assertTrue(JoinWorldFocus.isStandingColumn(8, -1));
        assertFalse(JoinWorldFocus.shouldEmitImmediately(9, -1));
    }
}
