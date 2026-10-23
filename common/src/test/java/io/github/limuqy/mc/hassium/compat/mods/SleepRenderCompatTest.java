package io.github.limuqy.mc.hassium.compat.mods;

import org.junit.jupiter.api.Test;
import java.net.URL;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SleepRenderCompatTest {
    @Test void vanillaRendererKeepsSleepWindow() {
        assertTrue(SleepRenderCompat.supportsVanillaRenderer(resources()));
    }

    @Test void oldSodiumAndItsForgeForksCannotUseVanillaRedirects() {
        assertFalse(SleepRenderCompat.supportsVanillaRenderer(resources(
                "me/jellysquid/mods/sodium/client/SodiumClientMod.class")));
    }

    @Test void newSodiumCannotUseVanillaRedirects() {
        assertFalse(SleepRenderCompat.supportsVanillaRenderer(resources(
                "net/caffeinemc/mods/sodium/client/SodiumClientMod.class")));
    }

    @Test void renamedEmbeddiumCannotUseVanillaRedirects() {
        assertFalse(SleepRenderCompat.supportsVanillaRenderer(resources(
                "org/embeddedt/embeddium/impl/Embeddium.class")));
    }

    @Test void sodiumOptionsAloneDoesNotDisableVanillaSleep() {
        assertTrue(SleepRenderCompat.supportsVanillaRenderer(resources("sodiumoptionsapi.mixins.json")));
    }

    private static ClassLoader resources(String... names) {
        Set<String> available = Set.of(names);
        URL marker = SleepRenderCompatTest.class.getResource("SleepRenderCompatTest.class");
        assertNotNull(marker);
        return new ClassLoader(null) {
            @Override public URL getResource(String name) { return available.contains(name) ? marker : null; }
            @Override protected Class<?> loadClass(String name, boolean resolve) {
                throw new AssertionError("Renderer detection must not define classes: " + name);
            }
        };
    }
}
