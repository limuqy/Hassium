package io.github.limuqy.mc.hassium.server;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeServerContextTest {
    @Test
    void oldShutdownCannotClearNewAssemblyOrItsSharedState() {
        int old = RuntimeServerContext.beginShadowServer();
        RuntimeServerContext.setShadowServer(true);
        assertEquals(old, RuntimeServerContext.getShadowGeneration());
        int current = RuntimeServerContext.beginShadowServer();
        AtomicBoolean cleared = new AtomicBoolean();
        assertFalse(RuntimeServerContext.finishShadowServer(old, () -> cleared.set(true)));
        assertFalse(cleared.get());
        assertTrue(RuntimeServerContext.isShadowServerContext());
        assertTrue(RuntimeServerContext.finishShadowServer(current, () -> cleared.set(true)));
        assertTrue(cleared.get());
        assertFalse(RuntimeServerContext.isShadowServerContext());
    }
}
