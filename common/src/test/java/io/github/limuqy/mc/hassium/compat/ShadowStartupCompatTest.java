package io.github.limuqy.mc.hassium.compat;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShadowStartupCompatTest {
    @Test void registryReplacingLoaderWaitsForRealClientLevel() {
        assertFalse(ShadowStartupCompat.mayStart(false, true));
        assertTrue(ShadowStartupCompat.mayStart(true, true));
    }
    @Test void loaderWithoutLoginRegistryReplacementKeepsSpeculativeStartup() {
        assertTrue(ShadowStartupCompat.mayStart(false, false));
        assertTrue(ShadowStartupCompat.mayStart(true, false));
    }
}
