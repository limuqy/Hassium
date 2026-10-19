package io.github.limuqy.mc.hassium.mixin.modcompat;

import io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.world.level.LevelAccessor;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import static org.junit.jupiter.api.Assertions.*;

class DeepVoidStructureIsolationTest {
    static {
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test void onlyCacheWorldPlacementIsCancelledWithoutReadingOrChangingWorldData() throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) field.get(null);
        MinecraftServer real = (MinecraftServer) unsafe.allocateInstance(DedicatedServer.class);
        MinecraftServer shadow = (MinecraftServer) unsafe.allocateInstance(ShadowSeedServer.class);
        Method handler = Arrays.stream(MixinDeepVoidCultTemple.class.getDeclaredMethods())
                .filter(method -> method.getName().endsWith("skipShadowTemplePlacement"))
                .findFirst().orElseThrow();
        handler.setAccessible(true);
        for (MinecraftServer server : new MinecraftServer[]{real, shadow, null}) {
            LevelAccessor level = (LevelAccessor) Proxy.newProxyInstance(LevelAccessor.class.getClassLoader(),
                    new Class<?>[]{LevelAccessor.class}, (proxy, method, args) -> {
                        if (method.getName().equals("getServer")) return server;
                        throw new AssertionError("compat hook must not access world data: " + method);
                    });
            CallbackInfo callback = new CallbackInfo("temple", true);
            handler.invoke(null, null, level, callback);
            assertEquals(server == shadow, callback.isCancelled());
        }
    }
}
