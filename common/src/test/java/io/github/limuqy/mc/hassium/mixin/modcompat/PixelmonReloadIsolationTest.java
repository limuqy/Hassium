package io.github.limuqy.mc.hassium.mixin.modcompat;

import io.github.limuqy.mc.hassium.shadow.server.ShadowAssemblyScope;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.junit.jupiter.api.Assertions.*;

class PixelmonReloadIsolationTest {
    @Test
    void suppressesOnlyExplicitShadowAssemblyReloads() throws Exception {
        Method handler = Arrays.stream(MixinPixelmonReloadListeners.class.getDeclaredMethods())
                .filter(method -> method.getName().endsWith("skipShadowGameplayReload"))
                .findFirst().orElseThrow();
        handler.setAccessible(true);
        CallbackInfo normal = new CallbackInfo("real-session", true);
        handler.invoke(null, new Object(), normal);
        assertFalse(normal.isCancelled(), "real integrated/dedicated session reload must remain active");
        CallbackInfo shadow = new CallbackInfo("shadow", true);
        ShadowAssemblyScope.call(() -> {
            try {
                handler.invoke(null, new Object(), shadow);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
            return null;
        });
        assertTrue(shadow.isCancelled());
        CallbackInfo later = new CallbackInfo("later-real-session", true);
        handler.invoke(null, new Object(), later);
        assertFalse(later.isCancelled(), "shadow scope must not leak into later real reloads");
    }
}
