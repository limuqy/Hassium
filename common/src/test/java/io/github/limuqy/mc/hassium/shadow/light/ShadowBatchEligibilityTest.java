package io.github.limuqy.mc.hassium.shadow.light;

import java.lang.reflect.Field;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShadowBatchEligibilityTest {
    @SuppressWarnings("unchecked")
    private Map<Long, Object> queue(String name) throws Exception {
        Field f = ShadowLightCompute.class.getDeclaredField(name);
        f.setAccessible(true);
        return (Map<Long, Object>) f.get(null);
    }
    private boolean eligible(String method, long key) throws Exception {
        var m = ShadowLightCompute.class.getDeclaredMethod(method, long.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, key);
    }
    @Test void overlapAndInflightUseTheSameEligibilityAsSelection() throws Exception {
        long key = 938483L;
        var pending = queue("pending"); var generated = queue("generated"); var inflight = queue("inflightLight");
        try {
            assertTrue(eligible("canStartPending", key));
            assertTrue(eligible("canStartDelta", key));
            generated.put(key, new Object());
            assertFalse(eligible("canStartPending", key));
            assertFalse(eligible("canStartDelta", key));
            assertTrue(eligible("canStartQueuedLight", key));
            generated.remove(key);
            pending.put(key, new Object());
            assertFalse(eligible("canStartDelta", key));
            assertTrue(eligible("canStartPending", key));
            inflight.put(key, new Object());
            assertFalse(eligible("canStartPending", key));
            assertFalse(eligible("canStartDelta", key));
            assertFalse(eligible("canStartQueuedLight", key));
        } finally { pending.remove(key); generated.remove(key); inflight.remove(key); }
    }
}
