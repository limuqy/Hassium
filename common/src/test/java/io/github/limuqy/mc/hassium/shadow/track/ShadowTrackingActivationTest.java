package io.github.limuqy.mc.hassium.shadow.track;

import io.github.limuqy.mc.hassium.platform.client.ShadowClientApi;
import io.github.limuqy.mc.hassium.platform.client.ShadowClientBridge;
import io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer;
import io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShadowTrackingActivationTest {
    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test void preservesPendingTrackingAndDoesNotReservePullsUntilPlayActivation() throws Exception {
        var active = new AtomicBoolean();
        var bridgeField = field(ShadowClientBridge.class, "impl");
        var serverField = field(ShadowServerRegistry.class, "server");
        var registry = ShadowServerRegistry.getInstance();
        var previousBridge = bridgeField.get(null);
        var previousServer = serverField.get(registry);
        var api = (ShadowClientApi) Proxy.newProxyInstance(ShadowClientApi.class.getClassLoader(),
                new Class<?>[]{ShadowClientApi.class}, (proxy, method, args) -> {
                    if (method.getName().equals("isShadowEngineActive")) return active.get();
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    return null;
                });
        var unsafeField = field(sun.misc.Unsafe.class, "theUnsafe");
        var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        var shadow = (ShadowSeedServer) unsafe.allocateInstance(ShadowSeedServer.class);
        var constructor = ShadowTrackingSession.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        var session = constructor.newInstance();
        var pendingField = field(ShadowTrackingSession.class, "pending");
        var pendingConstructor = pendingField.getType().getDeclaredConstructors()[0];
        pendingConstructor.setAccessible(true);
        var pending = pendingConstructor.newInstance("minecraft:overworld", 0d, 64d, 0d, 0f, 0f, true);
        var dimension = field(ShadowTrackingSession.class, "currentDimension");
        var center = field(ShadowTrackingSession.class, "trackingCenter");
        var bound = field(ShadowTrackingSession.class, "boundServer");
        var inFlight = (Map<Long, Long>) field(ShadowTrackingSession.class, "sweepInFlight").get(session);
        var position = new ChunkPos(0, 0);
        pendingField.set(session, pending);
        dimension.set(session, "minecraft:overworld");
        center.set(session, position);
        inFlight.put(1L, 1L);
        try {
            ShadowClientBridge.register(api);
            serverField.set(registry, shadow);
            session.consumeOnShadowLoop();
            assertSame(pending, pendingField.get(session), "Login state must survive until play_init");
            assertNull(bound.get(session));
            assertSame(position, center.get(session));
            assertEquals(Map.of(1L, 1L), inFlight);

            var request = ShadowTrackingSession.class.getDeclaredMethod("requestPullEligible",
                    String.class, ChunkPos.class, boolean.class);
            request.setAccessible(true);
            assertEquals(false, request.invoke(session, "minecraft:overworld", position, true));
            assertEquals(Map.of(1L, 1L), inFlight, "An unsent request must not reserve an in-flight slot");
            assertNull(ShadowChunkAcquire.tryAcquireForAuthority("minecraft:overworld", 0, 0));

            // The fake server has no levels: exercise activation/binding without starting native ticks.
            pendingField.set(session, null);
            active.set(true);
            session.consumeOnShadowLoop();
            assertSame(shadow, bound.get(session));
            assertNull(dimension.get(session));
            assertNull(center.get(session));
            assertTrue(inFlight.isEmpty());
        } finally {
            bridgeField.set(null, previousBridge);
            serverField.set(registry, previousServer);
        }
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
