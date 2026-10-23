package io.github.limuqy.mc.hassium.shadow.track;

import io.github.limuqy.mc.hassium.shadow.server.ShadowSeedServer;
import io.github.limuqy.mc.hassium.utils.DimensionKey;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShadowOvdGenerationTest {
    private static final String DIMENSION = "minecraft:overworld";

    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test void negativeFractionalCoordinatesKeepTheEntireOvdEdgeInsideTheSweep() {
        assertEquals(new ChunkPos(-3, 0), ShadowTrackingSession.trackingPosition(-32.5, 0.5));
        assertEquals(new ChunkPos(-1, -2), ShadowTrackingSession.trackingPosition(-0.5, -16.5));
        assertEquals(new ChunkPos(-1, 0), ShadowTrackingSession.trackingPosition(-16.0, 15.999));
        assertEquals(new ChunkPos(1, 2), ShadowTrackingSession.trackingPosition(16.0, 32.0));
    }

    @Test void ovdLeavesSharedCapacityForAuthorityWithoutReservingARejectedPosition() throws Exception {
        var fixture = new Fixture();
        int reservedForAuthority = Math.max(1, ShadowSeedServer.generationMaxInFlight() / 2);
        fixture.running.set(reservedForAuthority);
        assertFalse(fixture.submit(true));
        assertTrue(fixture.inflight().isEmpty(), "A saturated OVD attempt must not claim the shared position");
        assertTrue(fixture.submit(false), "Authority still has room in the same generation capacity");
    }

    @Test void existingSharedClaimPreventsDuplicateOvdGeneration() throws Exception {
        var fixture = new Fixture();
        fixture.inflight().put(fixture.key, 123L);
        assertFalse(fixture.submit(true));
        assertEquals(Map.of(fixture.key, 123L), fixture.inflight());
        assertTrue(fixture.retryAt().isEmpty(), "An in-flight generation is not a failed generation");
    }

    @Test void ovdGenerationFailureReleasesSharedClaimAndRetriesLocally() throws Exception {
        var fixture = new Fixture();
        long before = System.currentTimeMillis();
        // The immutable cache-only shell immediately returns null without any world or executor.
        assertTrue(fixture.submit(true));
        assertTrue(fixture.inflight().isEmpty());
        assertTrue(fixture.retryAt().get(fixture.key) >= before + 2_000L,
                "OVD failure must schedule local retry rather than authority FULL fallback");
    }

    @Test void persistenceUsesInjectedAuthorityInsteadOfStaleWorldgenHolder() throws Exception {
        var fixture = new Fixture();
        var unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        var authority = (net.minecraft.world.level.chunk.LevelChunk)
                unsafe.allocateInstance(net.minecraft.world.level.chunk.LevelChunk.class);
        field(ShadowSeedServer.class, "injectedChunks").set(fixture.shadow,
                new java.util.concurrent.ConcurrentHashMap<>(Map.of(fixture.key, authority)));
        var select = ShadowSeedServer.class.getDeclaredMethod("columnForSerialization", String.class, ChunkPos.class);
        select.setAccessible(true);
        // The shell has no native levels: even consulting the fallback holder would fail here.
        assertSame(authority, select.invoke(fixture.shadow, DIMENSION, fixture.pos),
                "Persist the current injected baseline, whose hash was confirmed by the server");
    }

    @Test @SuppressWarnings("unchecked")
    void authorityDeltaInvalidatesOvdLightAndRequiresTheNeighborhoodGateAgain() throws Exception {
        var compute = io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute.class;
        var gate = io.github.limuqy.mc.hassium.shadow.light.LightNeighborhoodGate.class;
        long key = DimensionKey.key(DIMENSION, 21, 22);
        for (String name : new String[]{"lightInitialized", "lightInitPassed", "lightRan"}) {
            ((java.util.Set<Long>) field(compute, name).get(null)).add(key);
        }
        ((java.util.Set<Long>) field(gate, "promoted").get(null)).add(key);
        ((java.util.Set<Long>) field(gate, "promotedClean").get(null)).add(key);
        try {
            var invalidate = compute.getDeclaredMethod("invalidateLightAfterContentChange", long.class);
            invalidate.setAccessible(true);
            invalidate.invoke(null, key);
            assertFalse(io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute.isLightInitPassed(key));
            assertFalse(io.github.limuqy.mc.hassium.shadow.light.LightNeighborhoodGate.wasPromoted(key));
            assertFalse(io.github.limuqy.mc.hassium.shadow.light.LightNeighborhoodGate.wasPromotedClean(key));
        } finally {
            io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute.cancelChunkWork(key);
        }
    }

    @Test void enteringAuthorityExpiresTheEarlierHaloVerdictWithoutDroppingResidentData() throws Exception {
        var fixture = new Fixture();
        var entering = new ChunkPos(0, 10);
        var overlap = new ChunkPos(0, 0);
        var epochs = (Map<Long, Long>) field(io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute.class,
                "shadowApplyEpochs").get(null);
        long enteringKey = DimensionKey.key(DIMENSION, 0, 10);
        io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate.confirm(DIMENSION, entering);
        io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate.confirm(DIMENSION, overlap);
        io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute.accountVisibleNetworkIngress(DIMENSION, entering);
        epochs.put(enteringKey, 123L);
        try {
            fixture.session.refreshAuthorityEntries(new ChunkPos(0, -3), 8, new ChunkPos(0, 2), 8);
            assertFalse(io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate.isConfirmed(DIMENSION, entering));
            assertFalse(io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute.wasNetworkIngressAccounted(DIMENSION, entering));
            assertTrue(io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate.isConfirmed(DIMENSION, overlap),
                    "Continuous server tracking keeps its verdict and avoids redundant compares");
            assertEquals(123L, epochs.get(enteringKey), "OVD residency survives authority revalidation");
        } finally {
            io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate.clearAll();
            epochs.remove(enteringKey);
            io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute.clearRequestMiss(DIMENSION, entering);
        }
    }

    @Test void increasingServerViewDistanceRevalidatesNewAuthorityColumns() throws Exception {
        var fixture = new Fixture();
        var entering = new ChunkPos(0, 8);
        io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate.confirm(DIMENSION, entering);
        try {
            fixture.session.refreshAuthorityEntries(new ChunkPos(0, 0), 4, new ChunkPos(0, 0), 8);
            assertFalse(io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate.isConfirmed(DIMENSION, entering));
        } finally {
            io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate.clearAll();
        }
    }

    @Test @SuppressWarnings("unchecked")
    void cancelledHaloCompareCanRetryOnAuthorityEntryWithoutOpeningTheDeliveryGate() throws Exception {
        var fixture = new Fixture();
        var entering = new ChunkPos(0,10);
        long key = DimensionKey.key(DIMENSION,entering.x,entering.z);
        var requests = (java.util.Set<Long>)field(io.github.limuqy.mc.hassium.protocol.ShadowPullClient.class,
                "COMPARE_REQUESTED").get(null);
        io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate.mark(DIMENSION,entering);
        requests.add(key);
        var pullClient = io.github.limuqy.mc.hassium.protocol.ShadowPullClient.class;
        Object window = field(pullClient,"RECEIVE_WINDOW").get(null);
        var intentType = Class.forName(pullClient.getName()+'$'+"PullIntent");
        var constructor = intentType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object intent = constructor.newInstance(DIMENSION,entering,true,false,null);
        var offer = window.getClass().getDeclaredMethod("offer",long.class,Object.class);
        offer.setAccessible(true);
        assertEquals(true,offer.invoke(window,key,intent));
        try {
            assertTrue(io.github.limuqy.mc.hassium.protocol.ShadowPullClient.columnRequestDiagnostics(key).contains("queued=true"));
            assertFalse(ShadowTrackingSession.shouldRetryAwaitingComparison(DIMENSION,entering),
                    "A live/queued compare must not be superseded by the sweep");
            fixture.session.refreshAuthorityEntries(new ChunkPos(0,-3),8,new ChunkPos(0,2),8);
            assertFalse(requests.contains(key), "Authority entry cancels the previous halo transaction");
            assertTrue(io.github.limuqy.mc.hassium.protocol.ShadowPullClient.columnRequestDiagnostics(key).contains("queued=false receive=none"));
            assertTrue(ShadowTrackingSession.shouldRetryAwaitingComparison(DIMENSION,entering),
                    "The old unconditional AWAITING skip stranded this material column forever");
            assertTrue(io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate.blockClientDelivery(
                    DIMENSION,entering.x,entering.z), "Retry must not blindly publish an unconfirmed baseline");
            requests.add(key); // The replacement has claimed request ownership.
            assertFalse(ShadowTrackingSession.shouldRetryAwaitingComparison(DIMENSION,entering));
            io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate.confirm(DIMENSION,entering);
            assertFalse(ShadowTrackingSession.shouldRetryAwaitingComparison(DIMENSION,entering));
        } finally {
            io.github.limuqy.mc.hassium.protocol.ShadowPullClient.clearCompareRequested(DIMENSION,entering);
            io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate.clearAll();
        }
    }

    @Test @SuppressWarnings("unchecked")
    void failedCompareRetainsItsCooldownBeforeTheAuthoritySweepRetries() throws Exception {
        var fixture = new Fixture();
        var failures = (Map<Long,Long>)field(io.github.limuqy.mc.hassium.protocol.ShadowPullClient.class,
                "lastFailAtMs").get(null);
        io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate.mark(DIMENSION,fixture.pos);
        try {
            io.github.limuqy.mc.hassium.protocol.ShadowPullClient.notePullFailure(DIMENSION,fixture.pos);
            assertFalse(ShadowTrackingSession.shouldRetryAwaitingComparison(DIMENSION,fixture.pos));
            failures.put(fixture.key,System.currentTimeMillis()-3_001L);
            assertTrue(ShadowTrackingSession.shouldRetryAwaitingComparison(DIMENSION,fixture.pos));
            assertTrue(io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate.blockClientDelivery(
                    DIMENSION,fixture.pos.x,fixture.pos.z));
        } finally {
            failures.remove(fixture.key);
            io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate.clearAll();
        }
    }

    @Test void lateNativeHolderCompletionCannotReopenAnAnsweredCompare() throws Exception {
        var fixture = new Fixture();
        var unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        var column = (net.minecraft.world.level.chunk.LevelChunk)
                unsafe.allocateInstance(net.minecraft.world.level.chunk.LevelChunk.class);
        field(ShadowSeedServer.class, "injectedChunks").set(fixture.shadow,
                new java.util.concurrent.ConcurrentHashMap<>(Map.of(fixture.key, column)));
        io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageHashes.put(DIMENSION, fixture.pos, 123L);
        io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate.confirm(DIMENSION, fixture.pos);
        fixture.inflight().put(fixture.key, System.currentTimeMillis());
        var bridge = io.github.limuqy.mc.hassium.platform.client.ShadowClientBridge.class;
        var previousBridge = field(bridge, "impl").get(null);
        var apiType = io.github.limuqy.mc.hassium.platform.client.ShadowClientApi.class;
        var api = (io.github.limuqy.mc.hassium.platform.client.ShadowClientApi)
                java.lang.reflect.Proxy.newProxyInstance(apiType.getClassLoader(), new Class<?>[]{apiType},
                        (proxy, method, args) -> {
                            if (method.getName().equals("isShadowEngineActive")) return true;
                            if (method.getReturnType() == boolean.class) return false;
                            if (method.getReturnType() == int.class) return 0;
                            if (method.getReturnType() == long.class) return 0L;
                            return null;
                        });
        try {
            io.github.limuqy.mc.hassium.platform.client.ShadowClientBridge.register(api);
            assertTrue(io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute.isEnabled());
            fixture.session.onChunkMaterialized(DIMENSION, fixture.pos, column);
            assertTrue(io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate.isConfirmed(DIMENSION, fixture.pos));
            assertFalse(io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate.isAwaiting(DIMENSION, fixture.pos),
                    "A late native callback must not wait forever for a deduplicated, unsent request");
        } finally {
            field(bridge, "impl").set(null, previousBridge);
            io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageHashes.clear();
            io.github.limuqy.mc.hassium.shadow.server.SeedGenCompareGate.clearAll();
        }
    }

    @Test @SuppressWarnings({"unchecked", "rawtypes"})
    void queuedReplacementWaitsUntilThePreviousLightSnapshotHasFinished() throws Exception {
        var fixture = new Fixture();
        var compute = io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute.class;
        var taskType = Class.forName(compute.getName() + '$' + "LightTask");
        var inflightType = Class.forName(compute.getName() + '$' + "InflightLight");
        var sourceType = (Class<? extends Enum>) Class.forName(compute.getName() + '$' + "LightSource");
        var unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        var barrier = unsafe.allocateInstance(inflightType);
        field(taskType, "key").setLong(barrier, fixture.key);
        var active = (Map<Long, Object>) field(compute, "inflightLight").get(null);
        var queued = (Map<Long, Object>) field(compute, "generated").get(null);
        Object replacement = new Object();
        var constructor = taskType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        var task = constructor.newInstance(fixture.key, Enum.valueOf(sourceType, "GENERATED"),
                replacement, null, null,
                io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute.LightMetric.RECOMPUTE,
                false, io.github.limuqy.mc.hassium.platform.client.TraceOrigin.SHADOW_MEMORY_CACHE);
        var submit = compute.getDeclaredMethod("submitLightBatch", ShadowSeedServer.class, java.util.List.class);
        submit.setAccessible(true);
        active.put(fixture.key, barrier);
        queued.put(fixture.key, replacement);
        try {
            submit.invoke(null, fixture.shadow, java.util.List.of(task));
            assertSame(barrier, active.get(fixture.key), "The preceding snapshot owns the column until packing ends");
            assertSame(replacement, queued.get(fixture.key), "Busy-column work must survive for the next pump");
        } finally {
            active.remove(fixture.key, barrier);
            queued.remove(fixture.key, replacement);
        }
    }

    @Test @SuppressWarnings("unchecked")
    void authorityContentRefreshesBusyNeighborsWithoutAStalePromotionEpochShortcut() throws Exception {
        var fixture = new Fixture();
        var compute = io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute.class;
        var unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        long neighborKey = DimensionKey.key(DIMENSION, 9, 0);
        var neighbor = (net.minecraft.world.level.chunk.LevelChunk)
                unsafe.allocateInstance(net.minecraft.world.level.chunk.LevelChunk.class);
        // 1.21.2+ setLightCorrect marks unsaved through the native listener.
        try {
            var listener = field(net.minecraft.world.level.chunk.LevelChunk.class, "unsavedListener");
            var type = listener.getType();
            listener.set(neighbor, java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),
                    new Class<?>[]{type}, (proxy, method, args) -> null));
        } catch (NoSuchFieldException ignored) {
            // Earlier versions store a boolean without an unsaved callback.
        }
        neighbor.setLightCorrect(true);
        var owner = (net.minecraft.server.level.ServerLevel)
                unsafe.allocateInstance(net.minecraft.server.level.ServerLevel.class);
        field(net.minecraft.server.MinecraftServer.class, "levels").set(fixture.shadow,
                Map.of(net.minecraft.world.level.Level.OVERWORLD, owner));
        field(ShadowSeedServer.class, "injectedChunks").set(fixture.shadow,
                new java.util.concurrent.ConcurrentHashMap<>(Map.of(neighborKey, neighbor)));
        var active = (Map<Long, Object>) field(compute, "inflightLight").get(null);
        var queued = (Map<Long, Object>) field(compute, "generated").get(null);
        Object preceding = unsafe.allocateInstance(Class.forName(compute.getName() + '$' + "InflightLight"));
        active.put(neighborKey, preceding);
        var refresh = compute.getDeclaredMethod("recheckAuthoritativeNeighbors", ShadowSeedServer.class,
                String.class, ChunkPos.class, boolean.class);
        refresh.setAccessible(true);
        try {
            // No clean epoch exists for the changed source, and its neighbor is still packing.
            refresh.invoke(null, fixture.shadow, DIMENSION, fixture.pos, true);
            assertFalse(neighbor.isLightCorrect(), "Old neighbor light must not remain a reusable disk snapshot");
            assertNotNull(queued.get(neighborKey), "Content changes need a coalesced follow-up even while busy");
            assertSame(preceding, active.get(neighborKey), "Refreshing must not replace the live barrier");
        } finally {
            active.remove(neighborKey, preceding);
            queued.remove(neighborKey);
            io.github.limuqy.mc.hassium.shadow.light.ShadowLightCompute.cancelChunkWork(neighborKey);
            io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageHashes.clear();
        }
    }

    private static final class Fixture {
        final ShadowTrackingSession session;
        final ShadowSeedServer shadow;
        final AtomicInteger running = new AtomicInteger();
        final ChunkPos pos = new ChunkPos(8, 0);
        final long key = DimensionKey.key(DIMENSION, 8, 0);

        Fixture() throws Exception {
            var constructor = ShadowTrackingSession.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            session = constructor.newInstance();
            var unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
            shadow = (ShadowSeedServer) unsafe.allocateInstance(ShadowSeedServer.class);
            field(ShadowSeedServer.class, "cacheOnly").setBoolean(shadow, true);
            field(ShadowSeedServer.class, "generationInFlight").set(shadow, running);
            field(ShadowTrackingSession.class, "boundServer").set(session, shadow);
            field(ShadowTrackingSession.class, "currentDimension").set(session, DIMENSION);
        }

        boolean submit(boolean ovd) throws Exception {
            var method = ShadowTrackingSession.class.getDeclaredMethod("submitLocalGeneration",
                    ShadowSeedServer.class, String.class, ChunkPos.class, long.class, boolean.class);
            method.setAccessible(true);
            return (boolean) method.invoke(session, shadow, DIMENSION, pos, System.currentTimeMillis(), ovd);
        }

        @SuppressWarnings("unchecked") Map<Long, Long> inflight() throws Exception {
            return (Map<Long, Long>) field(ShadowTrackingSession.class, "sweepInFlight").get(session);
        }

        @SuppressWarnings("unchecked") Map<Long, Long> retryAt() throws Exception {
            return (Map<Long, Long>) field(ShadowTrackingSession.class, "ovdMissRetryAt").get(session);
        }
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
