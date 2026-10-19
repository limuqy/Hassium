package io.github.limuqy.mc.hassium.mixin.modcompat;

import io.github.limuqy.mc.hassium.shadow.server.ShadowAssemblyScope;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.junit.jupiter.api.Assertions.*;

class MineColoniesRecipeIsolationTest {
    @Test
    void preservesRealRecipeReloadAndCancelsOnlyShadowApply() throws Exception {
        Method handler = Arrays.stream(MixinMineColoniesCrafterRecipes.class.getDeclaredMethods())
                .filter(method -> method.getName().endsWith("skipShadowCrafterRecipes"))
                .findFirst().orElseThrow();
        handler.setAccessible(true);
        Object target = new MixinMineColoniesCrafterRecipes() {};
        CallbackInfo real = new CallbackInfo("real", true);
        handler.invoke(target, real);
        assertFalse(real.isCancelled());
        CallbackInfo shadow = new CallbackInfo("shadow", true);
        ShadowAssemblyScope.wrap(Runnable::run).execute(() -> {
            try { handler.invoke(target, shadow); }
            catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        });
        assertTrue(shadow.isCancelled(), "apply executor must inherit the explicit shadow scope");
        CallbackInfo later = new CallbackInfo("real-after-shadow", true);
        handler.invoke(target, later);
        assertFalse(later.isCancelled(), "normal reloads must still update colony recipes");
    }
}
