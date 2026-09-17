package io.github.limuqy.mc.hassium.shadow.light;

import io.github.limuqy.mc.hassium.shadow.light.SmokeChunkTrace;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 冒烟延迟分布聚合的纯函数部分（不依赖 {@code hassium.smokeTest.probeDir} 开关）。
 */
class SmokeChunkTraceTest {

    @Test
    @DisplayName("最近秩百分位：p50/p95/max 取排序后对应下标，不插值")
    void nearestRankPercentiles() {
        List<Long> samples = new ArrayList<>(List.of(50L, 10L, 30L, 20L, 40L));
        SmokeChunkTrace.Latency latency = SmokeChunkTrace.latency(samples);
        assertEquals(5, latency.count());
        assertEquals(30, latency.p50Ms(), "5 样本中位 = 下标 (5-1)*50/100 = 2 → 30");
        assertEquals(40, latency.p95Ms(), "下标 (5-1)*95/100 = 3 → 40");
        assertEquals(50, latency.maxMs());
    }

    @Test
    @DisplayName("单样本三值相等；空样本返回 EMPTY")
    void degenerateSamples() {
        SmokeChunkTrace.Latency single = SmokeChunkTrace.latency(new ArrayList<>(List.of(7L)));
        assertEquals(1, single.count());
        assertEquals(7, single.p50Ms());
        assertEquals(7, single.p95Ms());
        assertEquals(7, single.maxMs());

        SmokeChunkTrace.Latency empty = SmokeChunkTrace.latency(new ArrayList<>());
        assertEquals(0, empty.count());
        assertEquals(0, empty.maxMs());
    }
}
