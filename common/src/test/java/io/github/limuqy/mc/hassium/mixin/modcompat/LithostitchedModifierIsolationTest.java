package io.github.limuqy.mc.hassium.mixin.modcompat;

import io.github.limuqy.mc.hassium.shadow.server.ShadowAssemblyScope;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import static org.junit.jupiter.api.Assertions.*;

class LithostitchedModifierIsolationTest {
    private static CallbackInfoReturnable<Map<?, ?>> invoke() {
        Method handler = Arrays.stream(MixinLithostitchedModifiers.class.getDeclaredMethods())
                .filter(method -> method.getName().endsWith("skipCacheOnlyWorldgen")).findFirst().orElseThrow();
        handler.setAccessible(true);
        var result = new CallbackInfoReturnable<Map<?, ?>>("worldgen-modifiers", true);
        try { handler.invoke(null, result); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        return result;
    }

    @Test void onlyCacheAssemblySkipsWorldgenAndItsGlobalCallbacks() {
        assertFalse(invoke().isCancelled(), "ordinary integrated/dedicated worldgen must run");
        assertFalse(ShadowAssemblyScope.call(LithostitchedModifierIsolationTest::invoke).isCancelled(),
                "SeedGen assembly has complete worldgen registries and must keep modifiers");
        var cache = ShadowAssemblyScope.cacheOnly(LithostitchedModifierIsolationTest::invoke);
        assertTrue(cache.isCancelled());
        assertEquals(Map.of(), cache.getReturnValue());
        assertFalse(invoke().isCancelled(), "cache scope must not affect later real worlds");
    }

    @Test void failedAndNestedCacheAssemblyCannotLeakIntoRealWorldgen() {
        assertThrows(IllegalStateException.class, () -> ShadowAssemblyScope.cacheOnly(() -> {
            assertTrue(ShadowAssemblyScope.call(LithostitchedModifierIsolationTest::invoke).isCancelled());
            throw new IllegalStateException("assembly failed");
        }));
        assertFalse(ShadowAssemblyScope.isActive());
        assertFalse(ShadowAssemblyScope.isCacheOnlyAssembly());
        assertFalse(invoke().isCancelled());
    }
}
