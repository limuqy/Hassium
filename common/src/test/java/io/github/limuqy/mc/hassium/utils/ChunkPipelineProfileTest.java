package io.github.limuqy.mc.hassium.utils;

import java.lang.reflect.Field;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static io.github.limuqy.mc.hassium.utils.ChunkPipelineProfile.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "hassium.chunkProfile", matches = "true")
class ChunkPipelineProfileTest {
    @BeforeEach void reset() { resetClient(); }

    private static Object field(Object owner, String name) throws Exception {
        Class<?> type = owner == null ? ChunkPipelineProfile.class : owner.getClass();
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(owner);
    }

    @SuppressWarnings("unchecked")
    private long count(Stage stage) throws Exception {
        var metrics = (Map<Source, Map<Stage, LatencyHistogram>>) field(field(null, "CLIENT"), "metrics");
        var stages = metrics.get(Source.FULL);
        var h = stages == null ? null : stages.get(stage);
        return h == null ? 0 : h.snapshot().count();
    }

    @Test void replacementAndOldSessionCallbacksCannotCompleteNewWork() throws Exception {
        long old = begin("overworld", 7, Source.FULL);
        long replacement = begin("overworld", 7, Source.FULL);
        assertNotEquals(old, replacement);
        ready(old); ready(replacement);
        startApply(old); applied(old); applied(old);
        assertEquals(1, count(Stage.READY_WAIT));
        assertEquals(1, count(Stage.INGEST_APPLY));
        startApply(replacement); applied(replacement);
        assertEquals(2, count(Stage.INGEST_APPLY));
        resetClient();
        long current = begin("nether", 7, Source.FULL);
        assertNotEquals(replacement, current);
        startApply(replacement); applied(replacement);
        assertEquals(0, count(Stage.INGEST_APPLY));
        ready(current); startApply(current); applied(current);
        assertEquals(1, count(Stage.INGEST_APPLY));
    }

    @Test void diagnosticRetentionIsBoundedAndEvictedCallbacksAreIgnored() throws Exception {
        long first = begin("overworld", 1, Source.FULL);
        for (int n = 0; n < 16_384; n++) begin("overworld", n, Source.FULL);
        assertEquals(16_384, ((Map<?, ?>) field(null, "FLOWS")).size());
        ready(first); startApply(first); applied(first);
        assertEquals(0, count(Stage.INGEST_APPLY));
    }
}
